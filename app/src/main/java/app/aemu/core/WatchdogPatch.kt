/* AEmulator Plus addition, 2026-10-28. GPL-3.0; see LICENSE. */
package app.aemu.core

import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Opt-in: stops Android's own system_server Watchdog (com.android.server.Watchdog) from killing the system.
 *
 * Under qemu, and above all with the runtime kept interpreter-only (see [BootImageGc]), system_server's boot is slower
 * than the Watchdog's 60 s limit, so it kills the process while the main thread is still busy, e.g.
 *
 *   Watchdog: *** WATCHDOG KILLING SYSTEM PROCESS: Blocked in handler on main thread (main) for 60s
 *   at PermissionPolicyService.synchronizePermissionsAndAppOpsForUser <- onBootPhase <- ActivityManagerService.systemReady
 *
 * zygote then exits with 137 and the boot starts over, forever. Android has no property that turns the Watchdog off, so
 * the first instruction of `Watchdog.run()` (the body of the Watchdog thread) is overwritten with `return-void`
 * (`0e 00`, one code unit, so nothing moves). The thread ends at once: no handler is ever checked, nothing is killed.
 * The rest of the method becomes unreachable code, which the verifier ignores. The class's other methods
 * (addMonitor, pauseWatchingCurrentThread ...) stay as they are and keep working.
 *
 * What is lost: a real deadlock in system_server is no longer recovered by a restart. The dex checksum changes, so the
 * firmware's services.odex/.vdex no longer match the jar and are ignored; the code runs from the jar's dex (the same
 * consequence as any edit of a dex in a jar).
 *
 * Only the dex inside the jar is edited; its adler32 and SHA-1 are recomputed and the zip entry keeps its compression
 * method (a stored entry stays stored and 4-byte aligned). No Android classes here, so it runs on a plain JVM.
 */
object WatchdogPatch {
    const val CLASS_DESC = "Lcom/android/server/Watchdog;"
    const val METHOD = "run"
    /** Start of the message the Watchdog logs before it kills the process; a Watchdog without it is not the one we know. */
    const val KILL_MARK = "*** WATCHDOG KILLING SYSTEM PROCESS"

    /**
     * system_server's main thread, in PermissionPolicyService.onStartUser (from ActivityManagerService.systemReady), waits on
     * the permission controller app for the default runtime permissions. Under qemu with the runtime interpreter-only that app
     * does not answer in time ("PermissionControllerManager: Error granting or upgrading runtime permissions",
     * TimeoutException), the wait throws IllegalStateException, system_server dies and zygote exits with 137. The method
     * is made to return at once: default runtime permissions of the preinstalled apps are not granted/upgraded at boot.
     */
    const val PERM_CLASS = "Lcom/android/server/policy/PermissionPolicyService;"
    const val PERM_METHOD = "grantOrUpgradeDefaultRuntimePermissionsIfNeeded"
    const val PERM_MARK = "Error granting/upgrading runtime permissions"

    enum class Outcome {
        /** the method now returns at once */
        PATCHED,
        /** it already did (an earlier start patched it) */
        ALREADY,
        /** no Watchdog class in this dex / jar */
        ABSENT,
        /** the class is there but not in the shape this patch knows */
        UNEXPECTED,
    }

    class Result(val outcome: Outcome, val detail: String = "")

    private fun isDex(d: ByteArray) = d.size >= 112 && d[0] == 'd'.code.toByte() && d[1] == 'e'.code.toByte() &&
        d[2] == 'x'.code.toByte() && d[3] == '\n'.code.toByte()

    /** Makes [method] of [cls] return at once in [d] (a plain dex), in place; [mark] is a string the class must contain. */
    private fun patchOne(d: ByteArray, cls: String, method: String, mark: String): Result {
        if (!isDex(d)) return Result(Outcome.ABSENT, "not a dex")
        val x = try { DexTools.Dex(d) } catch (e: RuntimeException) { return Result(Outcome.UNEXPECTED, "unreadable header: ${e.message}") }
        val found = try {
            DexTools.methodCode(x, method, cls, voidOnly = true)
        } catch (e: RuntimeException) { return Result(Outcome.UNEXPECTED, "unreadable class data: ${e.message}") }
        val codes: List<Int> = found ?: return Result(Outcome.ABSENT)
        if (codes.isEmpty()) return Result(Outcome.UNEXPECTED, "no $method() with code in the class")
        if (codes.size != 1) return Result(Outcome.UNEXPECTED, "${codes.size} methods called $method")
        val marked = try { DexTools.stringsStartingWith(x, mark).isNotEmpty() }
            catch (e: RuntimeException) { return Result(Outcome.UNEXPECTED, "unreadable string table: ${e.message}") }
        if (!marked) return Result(Outcome.UNEXPECTED, "the dex has no \"$mark\" message")
        val code = codes[0]
        val insnsSize = DexTools.u4(d, code + 12)
        val start = code + 16
        if (insnsSize <= 0 || start + 2L * insnsSize > d.size) return Result(Outcome.UNEXPECTED, "code item out of range")
        if (d[start] == RETURN_VOID && d[start + 1] == 0.toByte()) return Result(Outcome.ALREADY)
        // The first instruction may be wider than one code unit (const/16, const-string ...). Overwriting only its first
        // unit would leave the rest to be decoded as an instruction of its own and break the verifier's walk, so the
        // remaining units become nops (a code unit of a wider instruction must never be left behind).
        val len = DexTools.insnLength(d[start].toInt() and 0xff)
        if (len > insnsSize) return Result(Outcome.UNEXPECTED, "first instruction runs past the code item")
        d[start] = RETURN_VOID; d[start + 1] = 0
        for (k in 2 until 2 * len) d[start + k] = 0
        DexTools.fixHeader(d)
        return Result(Outcome.PATCHED)
    }

    /**
     * Patches [d] (a plain dex) in place: the Watchdog thread, and the wait for the permission controller (see
     * [PERM_METHOD]). The result is PATCHED when either changed, else ALREADY when either was patched before.
     */
    fun patchDex(d: ByteArray): Result {
        val rs = listOf(
            patchOne(d, CLASS_DESC, METHOD, KILL_MARK),
            patchOne(d, PERM_CLASS, PERM_METHOD, PERM_MARK),
        )
        for (o in listOf(Outcome.PATCHED, Outcome.ALREADY, Outcome.UNEXPECTED)) rs.firstOrNull { it.outcome == o }?.let { return it }
        return rs[0]
    }

    private const val RETURN_VOID: Byte = 0x0e

    private val dexName = Regex("classes\\d*\\.dex")

    /** Whether [jar] already holds the patch (and no dex still has the original Watchdog). */
    fun isPatched(jar: File): Boolean = runCatching {
        var already = false
        ZipFile(jar).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory || !dexName.matches(e.name)) continue
                when (patchDex(z.getInputStream(e).use { it.readBytes() }).outcome) {
                    Outcome.ALREADY -> already = true
                    Outcome.PATCHED -> return@runCatching false      // a dex still has the original
                    else -> {}
                }
            }
        }
        already
    }.getOrDefault(false)

    /**
     * Whether [jar] is exactly the stock jar [stock] with this patch applied, and nothing else. The stock jar is patched in
     * a scratch copy (the jar rewrite is deterministic) and compared byte for byte. True when the check itself cannot be
     * made, so that a failure here never costs a working jar.
     */
    fun isStockPlusPatch(jar: File, stock: File): Boolean {
        val tmp = File(jar.parentFile, jar.name + ".aemu-chk")
        return try {
            stock.copyTo(tmp, overwrite = true)
            patchJar(tmp, null)
            tmp.readBytes().contentEquals(jar.readBytes())
        } catch (e: Exception) {
            true
        } finally {
            tmp.delete()
        }
    }

    private class Counting(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    /**
     * Patches every classes*.dex of [jar] that has the Watchdog; rewrites the jar only when something changed.
     * [backup] gets a copy of the jar as it was first (when it does not exist yet).
     */
    fun patchJar(jar: File, backup: File?): Result {
        val dexNames = ArrayList<String>()
        val patched = HashMap<String, ByteArray>()
        var seen = Outcome.ABSENT
        var detail = ""
        ZipFile(jar).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory || !dexName.matches(e.name)) continue
                dexNames += e.name
                val bytes = z.getInputStream(e).use { it.readBytes() }
                val r = patchDex(bytes)
                when (r.outcome) {
                    Outcome.PATCHED -> { patched[e.name] = bytes; seen = Outcome.PATCHED }
                    Outcome.ALREADY -> if (seen != Outcome.PATCHED) seen = Outcome.ALREADY
                    Outcome.UNEXPECTED -> if (seen == Outcome.ABSENT) { seen = Outcome.UNEXPECTED; detail = "${e.name}: ${r.detail}" }
                    Outcome.ABSENT -> {}
                }
            }
        }
        if (patched.isEmpty()) return Result(seen, detail)
        if (backup != null && !backup.exists()) { backup.parentFile?.mkdirs(); jar.copyTo(backup) }
        val tmp = File(jar.parentFile, jar.name + ".aemu-tmp")
        try {
            ZipFile(jar).use { z ->
                Counting(FileOutputStream(tmp)).use { cnt ->
                    ZipOutputStream(cnt).use { zo ->
                        for (e in z.entries()) {
                            val data = patched[e.name] ?: if (e.isDirectory) ByteArray(0) else z.getInputStream(e).use { it.readBytes() }
                            val ne = ZipEntry(e.name)
                            if (e.time != -1L) ne.time = e.time
                            if (e.isDirectory) { zo.putNextEntry(ne); zo.closeEntry(); continue }
                            if (e.method == ZipEntry.STORED) {
                                ne.method = ZipEntry.STORED
                                ne.size = data.size.toLong(); ne.compressedSize = data.size.toLong()
                                ne.crc = CRC32().apply { update(data) }.value
                                // keep the data 4-byte aligned like zipalign (the runtime maps a stored dex straight from the file)
                                val base = cnt.count + 30 + e.name.toByteArray(Charsets.UTF_8).size
                                var pad = ((4 - base % 4) % 4).toInt()
                                if (pad in 1..3) pad += 4
                                if (pad > 0) ne.extra = ByteArray(pad).also { it[0] = 0x35; it[1] = 0xD9.toByte(); it[2] = (pad - 4).toByte() }
                            } else ne.method = ZipEntry.DEFLATED
                            zo.putNextEntry(ne); zo.write(data); zo.closeEntry()
                        }
                    }
                }
            }
            // a zip that does not read back is never put in place
            ZipFile(tmp).use { z -> for (n in dexNames) if (z.getEntry(n) == null) throw IllegalStateException("$n missing after rewrite") }
            if (!tmp.renameTo(jar)) { jar.delete(); if (!tmp.renameTo(jar)) throw IllegalStateException("cannot replace ${jar.name}") }
        } finally {
            tmp.delete()
        }
        return Result(Outcome.PATCHED)
    }
}
