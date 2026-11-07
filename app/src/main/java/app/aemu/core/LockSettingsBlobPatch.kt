package app.aemu.core

import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Added for AEmulator Plus (2026-10-25). Lets system_server start on firmware whose keystore cannot import an AES key.
 *
 * Google TV Streamer Pro (device "kirkwoodpro", 32-bit, Android 14) has no KeyMint, so keystore2 wraps the engine's
 * software keymaster 3.0, and that one answers every importKey for AES with UNSUPPORTED_ALGORITHM:
 *
 *   SyntheticPasswordCrypto: Failed to create blob / java.security.KeyStoreException: Failed to import secret key.
 *   System: ************ Failure starting system services
 *   java.lang.IllegalStateException: Failed to encrypt blob
 *     at SyntheticPasswordCrypto.createBlob(SyntheticPasswordCrypto.java:238)
 *     ... LockSettingsService.onThirdPartyAppsStarted(LockSettingsService.java:993)
 *
 * system_server dies there and zygote kills itself with SIGKILL (exit 137).
 *
 * createBlob() encrypts the data with a software AES key it generates itself, and then only *stores a copy of that key
 * in keystore* so decryptBlob() can find it again. This patch turns every `KeyStore.setEntry(String, Entry,
 * ProtectionParameter)` call inside SyntheticPasswordCrypto.createBlob() into three `nop`s (invoke-virtual is three code
 * units long, so nothing moves and no register is touched, which keeps the verifier happy). createBlob() then returns its
 * blob instead of throwing. decryptBlob() will not find the key later: it already catches that and returns null, which
 * LockSettingsService logs as "Fail to decrypt SP"; there is no file-based encryption in the guest to unlock.
 *
 * decryptBlob() does NOT return null for a missing key, though. In Android 14 it throws
 *
 *   java.lang.IllegalStateException: SP protector key is missing: synthetic_password_<id>
 *     at SyntheticPasswordCrypto.decryptBlob(SyntheticPasswordCrypto.java:175)
 *     ... LockSettingsService.unlockUserKeyIfUnsecured <- UserController.finishUserBoot <- ActivityManagerService.finishBooting
 *
 * and nothing catches it: it surfaces on the android.display thread when the boot animation ends, system_server dies
 * ("FATAL EXCEPTION IN SYSTEM PROCESS: android.display") right after boot completion and zygote exits with 137 - on every
 * boot, in a loop. So the same dex gets a second edit: in decryptBlob() the `new-instance IllegalStateException` that starts
 * the "SP protector key is missing" throw is overwritten with `const/4 vA, 0` + `return-object vA` (both are one code unit,
 * new-instance is two, so nothing moves). decryptBlob() then returns null for a missing key, which is what its other failures
 * do: unwrapSyntheticPasswordBlob() logs "Fail to decrypt SP" and gives up, and the user key is simply not unlocked. The throw
 * that is left behind is unreachable code.
 *
 * That is still not enough: unlockLskfBasedProtector() goes on to call verifyChallenge() with the null synthetic password
 * (NullPointerException, "deriveGkPassword() on a null object reference", same death of system_server). So the third edit makes
 * LockSettingsService.unlockUserKeyIfUnsecured() return at once (see [guardUnlock]).
 *
 * What is lost: lock screen credentials cannot be stored (nothing here has one) and the synthetic password cannot be
 * unwrapped on later boots. Any other caller that imports AES keys into keystore still fails exactly as before.
 *
 * Only the dex inside a jar is edited; the dex checksum (adler32) and SHA-1 signature are recomputed, the zip entry is
 * rewritten with the same compression method (a stored entry stays stored and 4-byte aligned). The original jar is
 * kept by the caller. No Android classes here, so it can be tested on a plain JVM.
 */
object LockSettingsBlobPatch {
    const val CLASS_DESC = "Lcom/android/server/locksettings/SyntheticPasswordCrypto;"
    const val METHOD = "createBlob"
    const val DECRYPT = "decryptBlob"
    /** Start of the message of the IllegalStateException decryptBlob() throws when keystore has no key of that alias. */
    const val KEY_MISSING = "SP protector key is missing"
    const val SERVICE_DESC = "Lcom/android/server/locksettings/LockSettingsService;"
    const val UNLOCK = "unlockUserKeyIfUnsecured"
    private const val ISE = "Ljava/lang/IllegalStateException;"

    enum class Outcome {
        /** at least one call was turned into nops */
        PATCHED,
        /** the method is there and holds no setEntry call (an earlier start patched it) */
        ALREADY,
        /** this dex has no such class/method (or no KeyStore.setEntry at all) */
        ABSENT,
        /** the class is there but the code is not what this patch knows */
        UNEXPECTED,
    }

    /** [calls]: KeyStore.setEntry calls skipped in createBlob; [keyChecks]: "key is missing" throws turned into `return null` in decryptBlob. */
    class Result(val outcome: Outcome, val calls: Int = 0, val detail: String = "", val keyChecks: Int = 0, val unlockSkips: Int = 0)

    // ---- dex reading -----------------------------------------------------------------------------------------------

    private fun u2(d: ByteArray, o: Int) = (d[o].toInt() and 0xff) or ((d[o + 1].toInt() and 0xff) shl 8)
    private fun u4(d: ByteArray, o: Int) = u2(d, o) or (u2(d, o + 2) shl 16)

    /** (value, bytes read) of the unsigned LEB128 at [o]. */
    private fun uleb(d: ByteArray, o: Int): Pair<Int, Int> {
        var r = 0; var shift = 0; var n = 0
        while (true) {
            val b = d[o + n].toInt() and 0xff; n++
            r = r or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) throw IllegalArgumentException("bad uleb128")
        }
        return r to n
    }

    private class Dex(val d: ByteArray) {
        val stringIds = u4(d, 56); val stringIdsOff = u4(d, 60)
        val typeIds = u4(d, 64); val typeIdsOff = u4(d, 68)
        val protoIdsOff = u4(d, 76)
        val methodIds = u4(d, 88); val methodIdsOff = u4(d, 92)
        val classDefs = u4(d, 96); val classDefsOff = u4(d, 100)

        fun string(idx: Int): String {
            var o = u4(d, stringIdsOff + 4 * idx)
            o += uleb(d, o).second
            var e = o
            while (d[e] != 0.toByte()) e++
            return String(d, o, e - o, Charsets.UTF_8)
        }

        fun type(idx: Int) = string(u4(d, typeIdsOff + 4 * idx))
        fun methodClass(m: Int) = type(u2(d, methodIdsOff + 8 * m))
        fun methodProto(m: Int) = u2(d, methodIdsOff + 8 * m + 2)
        fun methodName(m: Int) = string(u4(d, methodIdsOff + 8 * m + 4))
        fun protoShorty(p: Int) = string(u4(d, protoIdsOff + 12 * p))
        fun protoReturn(p: Int) = type(u4(d, protoIdsOff + 12 * p + 4))
    }

    /** The method_ids index of java.security.KeyStore.setEntry(String, KeyStore$Entry, KeyStore$ProtectionParameter) or -1. */
    private fun keyStoreSetEntry(x: Dex): Int {
        for (m in 0 until x.methodIds) {
            if (x.methodName(m) != "setEntry" || x.methodClass(m) != "Ljava/security/KeyStore;") continue
            val p = x.methodProto(m)
            if (x.protoShorty(p) == "VLLL" && x.protoReturn(p) == "V") return m
        }
        return -1
    }

    /** code_item offsets of every method called [name] in class [cls] (only void ones with [voidOnly]). Null when the class is not in this dex. */
    private fun methodCode(x: Dex, name: String, cls: String = CLASS_DESC, voidOnly: Boolean = false): List<Int>? {
        val d = x.d
        for (c in 0 until x.classDefs) {
            val def = x.classDefsOff + 32 * c
            if (x.type(u4(d, def)) != cls) continue
            var o = u4(d, def + 24)                      // class_data_off
            if (o == 0) return emptyList()
            val sizes = IntArray(4)
            for (i in 0 until 4) { val (v, n) = uleb(d, o); sizes[i] = v; o += n }
            for (i in 0 until sizes[0] + sizes[1]) { o += uleb(d, o).second; o += uleb(d, o).second }   // fields
            val out = ArrayList<Int>()
            var method = 0
            for (list in 2..3) {                         // direct, then virtual (the index restarts for each list)
                method = 0
                for (i in 0 until sizes[list]) {
                    val (diff, n1) = uleb(d, o); o += n1
                    val (_, n2) = uleb(d, o); o += n2
                    val (code, n3) = uleb(d, o); o += n3
                    method += diff
                    if (code != 0 && x.methodName(method) == name && (!voidOnly || x.protoReturn(x.methodProto(method)) == "V")) out += code
                }
            }
            return out
        }
        return null
    }

    // ---- the patch --------------------------------------------------------------------------------------------------

    /** Index of the type descriptor [desc] in the type table, -1 when the dex has none. */
    private fun typeIndex(x: Dex, desc: String): Int {
        for (t in 0 until x.typeIds) if (x.type(t) == desc) return t
        return -1
    }

    /** Indexes of the strings that start with [prefix]. */
    private fun stringsStartingWith(x: Dex, prefix: String): Set<Int> {
        val out = HashSet<Int>()
        for (i in 0 until x.stringIds) if (x.string(i).startsWith(prefix)) out += i
        return out
    }

    /**
     * decryptBlob(): `throw new IllegalStateException(\"SP protector key is missing: \" + alias)` becomes `return null`.
     * The throw starts with `new-instance vA, IllegalStateException` (22 AA <type>, two code units) which is the last one
     * before the const-string of the message; it is replaced by `const/4 vA, #0` (12 0A) + `return-object vA` (11 AA).
     * Returns the number of throws replaced (0 when the dex has none, or when an earlier start did it already).
     */
    private fun guardDecryptBlob(x: Dex, codes: List<Int>): Int {
        val d = x.d
        val ise = typeIndex(x, ISE)
        val msgs = stringsStartingWith(x, KEY_MISSING)
        if (ise < 0 || msgs.isEmpty()) return 0
        var hits = 0
        for (code in codes) {
            val insnsSize = u4(d, code + 12)
            val start = code + 16
            if (insnsSize <= 0 || start + 2L * insnsSize > d.size) continue
            var lastNew = -1                              // code unit of the latest new-instance IllegalStateException
            var u = 0
            while (u < insnsSize - 1) {
                val o = start + 2 * u
                val op = d[o].toInt() and 0xff
                when {
                    op == 0x22 && u2(d, o + 2) == ise -> lastNew = u
                    // const-string vAA, string@BBBB
                    op == 0x1a && u2(d, o + 2) in msgs -> {
                        val n = lastNew
                        lastNew = -1
                        // the throw is a few instructions long (the message is built right behind the new-instance)
                        if (n >= 0 && u - n <= 24) {
                            val no = start + 2 * n
                            val r = d[no + 1].toInt() and 0xff
                            if (r > 15) return hits       // const/4 reaches v0..v15 only: leave it
                            d[no] = 0x12; d[no + 1] = r.toByte()                // const/4 vR, #0
                            d[no + 2] = 0x11; d[no + 3] = r.toByte()            // return-object vR
                            hits++
                        }
                    }
                }
                u++
            }
        }
        return hits
    }

    /** Length in code units of the instruction that starts with opcode [op] (payload pseudo-instructions are not needed here). */
    private fun insnLength(op: Int): Int = when (op) {
        in 0x01..0x0d -> when (op) { 0x02, 0x05, 0x08 -> 2; 0x03, 0x06, 0x09 -> 3; else -> 1 }
        0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c, 0x1f, 0x20, 0x22, 0x23, 0x29 -> 2
        0x14, 0x17, 0x1b, 0x24, 0x25, 0x26, 0x2a, 0x2b, 0x2c -> 3
        0x18 -> 5
        in 0x2d..0x3d, in 0x44..0x6d, in 0x90..0xaf, in 0xd0..0xe2, 0xfe, 0xff -> 2
        in 0x6e..0x72, in 0x74..0x78, 0xfc, 0xfd -> 3
        0xfa, 0xfb -> 4
        else -> 1
    }

    /**
     * LockSettingsService.unlockUserKeyIfUnsecured(int): the first instruction becomes `return-void` (0e 00), the rest of that
     * instruction nops, so that the code behind it is unreachable but still decodes. Without the protector key the synthetic
     * password cannot be unwrapped (decryptBlob() answers null) and unlockLskfBasedProtector() then calls verifyChallenge()
     * on that null: NullPointerException in system_server. The key is only unlocked through here for an unsecured user and
     * the guest has no file-based encryption; the unlock through StorageManager that follows in UserController still runs.
     * Returns the number of methods changed.
     */
    private fun guardUnlock(x: Dex, codes: List<Int>): Int {
        val d = x.d
        var hits = 0
        for (code in codes) {
            val insnsSize = u4(d, code + 12)
            val start = code + 16
            if (insnsSize <= 0 || start + 2L * insnsSize > d.size) continue
            val op = d[start].toInt() and 0xff
            val len = insnLength(op)
            if (op == 0x0e && d[start + 1].toInt() == 0) continue            // done by an earlier start
            if (len > insnsSize) continue
            d[start] = 0x0e; d[start + 1] = 0
            for (k in 2 until 2 * len) d[start + k] = 0
            hits++
        }
        return hits
    }

    /** Patches [d] (a plain dex) in place. */
    fun patchDex(d: ByteArray): Result {
        if (d.size < 112 || d[0] != 'd'.code.toByte() || d[1] != 'e'.code.toByte() || d[2] != 'x'.code.toByte() || d[3] != '\n'.code.toByte())
            return Result(Outcome.ABSENT, detail = "not a dex")
        val x = try { Dex(d) } catch (e: RuntimeException) { return Result(Outcome.UNEXPECTED, detail = "unreadable header: ${e.message}") }
        val codes = try { methodCode(x, METHOD) } catch (e: RuntimeException) { return Result(Outcome.UNEXPECTED, detail = "unreadable class data: ${e.message}") }
        val unlockCodes = try { methodCode(x, UNLOCK, SERVICE_DESC, voidOnly = true) } catch (e: RuntimeException) { return Result(Outcome.UNEXPECTED, detail = "unreadable class data: ${e.message}") }
        if (codes == null && unlockCodes == null) return Result(Outcome.ABSENT)
        val unlockSkips = if (unlockCodes != null) guardUnlock(x, unlockCodes) else 0
        if (codes == null) { if (unlockSkips == 0) return Result(Outcome.ALREADY); fixHeader(d); return Result(Outcome.PATCHED, unlockSkips = unlockSkips) }
        val decryptCodes = try { methodCode(x, DECRYPT).orEmpty() } catch (e: RuntimeException) { return Result(Outcome.UNEXPECTED, detail = "unreadable class data: ${e.message}") }
        if (codes.isEmpty() && decryptCodes.isEmpty()) return Result(Outcome.UNEXPECTED, detail = "$METHOD not found in the class")
        var hits = 0
        if (codes.isNotEmpty()) {
            val setEntry = try { keyStoreSetEntry(x) } catch (e: RuntimeException) { return Result(Outcome.UNEXPECTED, detail = "unreadable method table: ${e.message}") }
            if (setEntry < 0) return Result(Outcome.UNEXPECTED, detail = "the dex never calls KeyStore.setEntry")
            for (code in codes) {
                val insnsSize = u4(d, code + 12)
                val start = code + 16
                if (insnsSize <= 0 || start + 2L * insnsSize > d.size) return Result(Outcome.UNEXPECTED, detail = "code item out of range")
                for (u in 0 until insnsSize - 2) {
                    val o = start + 2 * u
                    // invoke-virtual {4 registers}, KeyStore.setEntry: 6e 4x <method idx> <registers>
                    if ((d[o].toInt() and 0xff) == 0x6e && (d[o + 1].toInt() and 0xf0) == 0x40 && u2(d, o + 2) == setEntry) {
                        for (k in 0 until 6) d[o + k] = 0       // nop, nop, nop
                        hits++
                    }
                }
            }
        }
        val keyChecks = try { guardDecryptBlob(x, decryptCodes) } catch (e: RuntimeException) { return Result(Outcome.UNEXPECTED, detail = "unreadable decryptBlob: ${e.message}") }
        if (hits == 0 && keyChecks == 0 && unlockSkips == 0) return Result(Outcome.ALREADY)
        fixHeader(d)
        return Result(Outcome.PATCHED, hits, keyChecks = keyChecks, unlockSkips = unlockSkips)
    }

    /** SHA-1 signature (over everything after it), then the adler32 (over everything after itself). */
    internal fun fixHeader(d: ByteArray) {
        val sha = MessageDigest.getInstance("SHA-1").apply { update(d, 32, d.size - 32) }.digest()
        System.arraycopy(sha, 0, d, 12, 20)
        val ck = Adler32().apply { update(d, 12, d.size - 12) }.value.toInt()
        for (k in 0..3) d[8 + k] = (ck ushr (8 * k)).toByte()
    }

    // ---- the jar -----------------------------------------------------------------------------------------------------

    private class Counting(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    /**
     * Patches every classes*.dex of the jar [jar] that has the method; rewrites the jar only when something changed.
     * [backup] gets a copy of the stock jar first (when it does not exist yet).
     */
    fun patchJar(jar: File, backup: File?): Result {
        val dexNames = ArrayList<String>()
        val patched = HashMap<String, ByteArray>()
        var calls = 0
        var keyChecks = 0
        var unlockSkips = 0
        var seen = Outcome.ABSENT
        var detail = ""
        ZipFile(jar).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory || !Regex("classes\\d*\\.dex").matches(e.name)) continue
                dexNames += e.name
                val bytes = z.getInputStream(e).use { it.readBytes() }
                val r = patchDex(bytes)
                when (r.outcome) {
                    Outcome.PATCHED -> { patched[e.name] = bytes; calls += r.calls; keyChecks += r.keyChecks; unlockSkips += r.unlockSkips; seen = Outcome.PATCHED }
                    Outcome.ALREADY -> if (seen != Outcome.PATCHED) seen = Outcome.ALREADY
                    Outcome.UNEXPECTED -> if (seen == Outcome.ABSENT) { seen = Outcome.UNEXPECTED; detail = "${e.name}: ${r.detail}" }
                    Outcome.ABSENT -> {}
                }
            }
        }
        if (patched.isEmpty()) return Result(seen, 0, detail)
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
        return Result(Outcome.PATCHED, calls, keyChecks = keyChecks, unlockSkips = unlockSkips)
    }

    /** Whether [value] is a device name this patch is for. */
    fun isTargetDevice(value: String?) = value?.trim() == "kirkwoodpro"
}
