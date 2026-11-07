/* AEmulator Plus addition, 2026-10-29. GPL-3.0; see LICENSE. */
package app.aemu.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Android 14+: the firmware's boot image was built for the userfaultfd collector (CMC): its oat has no read barrier
 * (header key "concurrent-copying" = false). This guest runs under qemu-user and has no userfaultfd, so libart falls
 * back to the CC collector (read barrier on) and refuses the image:
 *   "ValidateOatFile found read barrier state mismatch (oat file: false, runtime: true)".
 * [LibArtReadBarrierPatch] used to switch that check off and kept the runtime interpreter-only (-Xint, no JIT); every
 * app then runs its start-up code in the interpreter, which is too slow for the process start timeout.
 *
 * Here the image is built again by the firmware's own dex2oat, inside the guest. dex2oat takes the read-barrier
 * decision from the same userfaultfd probe as the runtime, so it emits a CC image the stock libart accepts: no libart
 * patch, no -Xint, and the JIT switch of the settings works again.
 *
 * What is rebuilt: the primary boot image, i.e. the jars of DEX2OATBOOTCLASSPATH (15 jars in the AW2A firmware, base
 * 0x70000000). The firmware's extension images (boot-framework-adservices... for the mainline jars after them in
 * BOOTCLASSPATH) were laid out behind the old primary image and no longer fit; they are parked, and those jars load
 * from their dex files like any jar a boot image does not cover.
 *
 * Once only: afterwards the oat says concurrent-copying=true, so [needed] is false. A failed attempt is remembered
 * (per firmware image and per [ALGO]) and the old files are put back, so the caller falls back to the libart patch.
 */
object CcBootImage {
    /** raise when the way the image is built changes: a remembered failure is then tried again */
    const val ALGO = 2

    private const val FAIL_MARK = "data/.aemu-cc-bootimage-failed"
    private const val PARK = "system/.aemu-parked"
    private const val DEFAULT_BASE = 0x70000000L
    private const val TIMEOUT_MS = 2L * 60 * 60 * 1000
    private val DEX2OAT = listOf(
        "apex/com.android.art/bin/dex2oat32", "apex/com.android.art/bin/dex2oat",
        "system/bin/dex2oat32", "system/bin/dex2oat")
    private val DEX2OAT64 = listOf(
        "apex/com.android.art/bin/dex2oat64", "apex/com.android.art/bin/dex2oat",
        "system/bin/dex2oat64", "system/bin/dex2oat")
    private val BOOT_FILE = Regex("^boot(-.+)?\\.(art|oat|vdex)$")

    class Result(val ok: Boolean, val note: String)

    /** The fields of an ART ImageHeader that matter here (version 109, Android 14: magic, version, then these). */
    class ImageInfo(val reservation: Long, val components: Int, val begin: Long, val size: Long)

    /**
     * The boot image cannot be used by this runtime: its oat has no read barrier and the guest has no userfaultfd
     * (always so under qemu-user; [runtimeHasUffd] is the hook for an engine that gets one).
     */
    fun needed(root: File, api: Int, runtimeHasUffd: Boolean = false): Boolean =
        api >= 34 && !runtimeHasUffd && !ownsImage(root) && BootImageGc.bootImageIsNonCc(root)

    /**
     * The image in system/framework/arm64 is the one [ensureScratch] built (its mark says "ok"), not the firmware's: it was
     * made by this guest's dex2oat for this guest's runtime, so the old-firmware rebuild must leave it alone (its oat may say
     * "no read barrier" simply because the runtime chose the userfaultfd collector).
     */
    fun ownsImage(root: File): Boolean =
        File(root, "$SCRATCH_DIR/boot.oat").isFile && readMark(root)["status"] == "ok"

    /** [needed] and not already tried in vain for this firmware image. */
    fun pending(root: File, api: Int): Boolean = needed(root, api) && !failedBefore(root)

    // ---- pure helpers (unit tested) ----

    fun parseImageHeader(d: ByteArray): ImageInfo? {
        if (d.size < 24 || d[0] != 'a'.code.toByte() || d[1] != 'r'.code.toByte() || d[2] != 't'.code.toByte() || d[3] != '\n'.code.toByte()) return null
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        return ImageInfo(b.getInt(8).toLong() and 0xffffffffL, b.getInt(12), b.getInt(16).toLong() and 0xffffffffL, b.getInt(20).toLong() and 0xffffffffL)
    }

    fun jarList(v: String?): List<String> = v.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() }

    /** a flag is offered when the `--help` text names it; no help text (the probe failed) means: assume it is */
    fun supports(help: String?, flag: String): Boolean = help.isNullOrBlank() || help.contains(flag)

    fun bootFileName(n: String): Boolean = BOOT_FILE.matches(n)

    /** dex2oat's command line for the primary boot image; [guestDir] is where the firmware keeps boot.oat. */
    fun primaryArgs(bin: String, jars: List<String>, guestDir: String, base: Long, variant: String?, features: String?,
                    imageClasses: String?, threads: Int, help: String?, isa: String = "arm"): List<String> {
        val a = ArrayList<String>()
        a += bin
        a += listOf("--runtime-arg", "-Xms64m", "--runtime-arg", "-Xmx512m")
        for (j in jars) { a += "--dex-file=$j"; a += "--dex-location=$j" }
        a += "--oat-file=$guestDir/boot.oat"
        a += "--oat-location=$guestDir/boot.oat"
        a += "--image=$guestDir/boot.art"
        a += "--base=0x" + base.toString(16).padStart(8, '0')
        a += "--instruction-set=$isa"
        if (!variant.isNullOrBlank()) a += "--instruction-set-variant=$variant"
        a += "--instruction-set-features=${features?.takeIf { it.isNotBlank() } ?: "default"}"
        // the interpreter runs the boot code anyway until the JIT/AOT switch is proven on a device; verified classes are
        // what the image needs, and a compile of the whole framework under qemu would take hours
        a += "--compiler-filter=verify"
        if (supports(help, "--multi-image")) a += "--multi-image"
        if (supports(help, "--no-generate-debug-info")) a += "--no-generate-debug-info"
        if (supports(help, "--avoid-storing-invocation")) a += "--avoid-storing-invocation"
        if (imageClasses != null && supports(help, "--image-classes=")) a += "--image-classes=$imageClasses"
        if (threads > 0 && supports(help, "-j")) a += "-j$threads"
        return a
    }

    fun markerText(fp: String?, reason: String) = "algo=$ALGO\nfp=${fp ?: ""}\nreason=${reason.replace('\n', ' ').take(400)}\n"

    fun markerMatches(text: String, fp: String?): Boolean {
        val m = text.lines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
        return m["algo"] == ALGO.toString() && fp != null && m["fp"] == fp
    }

    // ---- state on disk ----

    fun fingerprint(root: File): String? {
        val oat = BootImageGc.bootOat(root) ?: return null
        val head = ByteArray(8192)
        val n = runCatching { oat.inputStream().use { it.read(head) } }.getOrDefault(-1)
        if (n <= 0) return null
        val sha = MessageDigest.getInstance("SHA-1").digest(head.copyOf(n)).joinToString("") { "%02x".format(it) }.take(16)
        return "${oat.length()}:$sha"
    }

    fun failedBefore(root: File): Boolean {
        val f = File(root, FAIL_MARK)
        return f.isFile && runCatching { markerMatches(f.readText(), fingerprint(root)) }.getOrDefault(false)
    }

    private fun fail(root: File, why: String, log: (String) -> Unit): Result {
        runCatching { File(root, FAIL_MARK).apply { parentFile?.mkdirs() }.writeText(markerText(fingerprint(root), why)) }
        log("art: boot image rebuild failed: $why")
        return Result(false, why)
    }

    /**
     * TreeFixer.protectOat takes the write bit off system/framework/<isa> (files and directory) before every start, so
     * that installd cannot touch the precompiled code: renaming a file out of it, or dex2oat writing into it, fails with
     * the bit gone. It is given back for the rebuild and taken away again afterwards, as protectOat leaves it.
     */
    private fun unlock(dir: File) {
        dir.setWritable(true, true)
        dir.listFiles().orEmpty().forEach { if (it.isFile) it.setWritable(true, true) }
    }

    private fun relock(dir: File) {
        dir.listFiles().orEmpty().forEach { if (it.isFile) it.setWritable(false, false) }
        dir.setWritable(false, false)
    }

    /** The firmware's boot* files go to system/.aemu-parked (as `a#b#c`), where [restore] finds them. */
    private fun park(root: File, dir: File): List<Pair<File, File>> {
        val parked = ArrayList<Pair<File, File>>()
        val rel = dir.relativeTo(root).path.replace(File.separatorChar, '#')
        val park = File(root, PARK).apply { mkdirs() }
        for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
            if (!f.isFile || !bootFileName(f.name)) continue
            val dst = File(park, "$rel#${f.name}")
            dst.delete()
            if (f.renameTo(dst)) parked += f to dst
            else { restore(parked, dir, deleteNew = false); throw IllegalStateException("cannot move ${f.name} aside") }
        }
        return parked
    }

    /** [deleteNew]: whatever boot* file is in [dir] now was written by the failed build, not parked. */
    private fun restore(parked: List<Pair<File, File>>, dir: File, deleteNew: Boolean = true) {
        if (deleteNew) dir.listFiles().orEmpty().filter { it.isFile && bootFileName(it.name) }.forEach { it.delete() }
        for ((orig, dst) in parked) dst.renameTo(orig)
    }

    /**
     * Builds the CC boot image in the guest. [exports] are the class paths derive_classpath wrote; [prop] reads the
     * guest's properties. Takes minutes to an hour under qemu; call it before the guest's init starts. When it fails the
     * firmware's files are back in place and the failure is remembered.
     */
    fun rebuild(root: File, runner: GuestRunner, exports: Map<String, String>, prop: (String) -> String?, log: (String) -> Unit,
                is64: Boolean = false): Result {
        val oat = BootImageGc.bootOat(root) ?: return fail(root, "no boot.oat in the firmware", log)
        val dir = oat.parentFile ?: return fail(root, "no boot image directory", log)
        val guestDir = "/" + dir.relativeTo(root).path.replace(File.separatorChar, '/')

        val jars = jarList(exports["DEX2OATBOOTCLASSPATH"])
        if (jars.isEmpty()) return fail(root, "DEX2OATBOOTCLASSPATH is empty (derive_classpath did not run)", log)
        val missing = jars.filter { !File(root, it.removePrefix("/")).isFile }
        if (missing.isNotEmpty()) return fail(root, "boot jars missing in the tree: ${missing.take(3).joinToString()}", log)
        val bin = (if (is64) DEX2OAT64 else DEX2OAT).firstOrNull { File(root, it).isFile }?.let { "/$it" } ?: return fail(root, "no dex2oat in the tree", log)

        val old = runCatching { File(dir, "boot.art").inputStream().use { s -> ByteArray(64).also { s.read(it) } } }.getOrNull()?.let(::parseImageHeader)
        val base = old?.begin?.takeIf { it in 0x10000000L..0xb0000000L && it % 4096 == 0L } ?: DEFAULT_BASE
        val help = runCatching { runner.run(listOf(bin, "--help"), 120_000).second }.getOrNull()
        val classes = File(root, "system/etc/preloaded-classes").takeIf { it.isFile && it.length() > 0 }?.let { "/system/etc/preloaded-classes" }
        if (classes == null) log("art: no /system/etc/preloaded-classes: dex2oat takes every class of the boot jars as an image class")
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val isa = if (is64) "arm64" else "arm"
        val args = primaryArgs(bin, jars, guestDir, base, prop("dalvik.vm.isa.$isa.variant"), prop("dalvik.vm.isa.$isa.features"), classes, threads, help, isa)

        unlock(dir)
        try { return build(root, runner, dir, jars, args, base, log) } finally { relock(dir) }
    }

    private fun build(root: File, runner: GuestRunner, dir: File, jars: List<String>, args: List<String>, base: Long, log: (String) -> Unit): Result {
        val parked = try { park(root, dir) } catch (e: Exception) { return fail(root, e.message ?: "park failed", log) }
        log("art: the firmware's boot image has no read barrier and this guest has no userfaultfd: building the boot image of " +
            "${jars.size} jars with the guest's dex2oat (first start only, this takes a while)")
        val t0 = System.currentTimeMillis()
        val (code, out) = runCatching { runner.run(args, TIMEOUT_MS) }.getOrElse { -1 to (it.message ?: "") }
        val secs = (System.currentTimeMillis() - t0) / 1000

        val problem = verify(root, dir, jars.size, base, code)
        if (problem != null) {
            restore(parked, dir)
            val tail = out.trim().let { if (it.length > 900) it.take(300) + " ... " + it.takeLast(600) else it }
            log("art: dex2oat exit $code after $secs s: $tail")
            return fail(root, problem, log)
        }
        log("art: boot image rebuilt for the CC collector in $secs s (exit $code); the firmware's files are in $PARK")
        runCatching { File(root, FAIL_MARK).delete() }
        return Result(true, "built in $secs s")
    }

    /** null when the files dex2oat left are a complete CC image of [count] jars; else what is wrong. */
    fun verify(root: File, dir: File, count: Int, base: Long, exit: Int, requireCc: Boolean = true): String? {
        // (under qemu dex2oat may die in exit() after the files are complete, as LPW39's did with 134: judge by the files)
        if (exit != 0 && exit != 134) return "dex2oat exit $exit"
        val art = File(dir, "boot.art"); val oat = File(dir, "boot.oat"); val vdex = File(dir, "boot.vdex")
        for (f in listOf(art, oat, vdex)) if (!f.isFile || f.length() == 0L) return "${f.name} was not written"
        val h = runCatching { art.inputStream().use { s -> ByteArray(64).also { s.read(it) } } }.getOrNull()?.let(::parseImageHeader)
            ?: return "boot.art has no image header"
        if (h.components != count) return "boot.art holds ${h.components} components, expected $count"
        if (h.begin != base) return "boot.art is at 0x${h.begin.toString(16)}, expected 0x${base.toString(16)}"
        if (art.length() < h.size) return "boot.art is shorter (${art.length()}) than its header says (${h.size})"
        val others = dir.listFiles().orEmpty().count { it.isFile && it.name.matches(Regex("^boot-.+\\.art$")) }
        if (others < count - 1) return "only $others of ${count - 1} component images were written"
        // (an image built from scratch is not judged by the firmware's old state: dex2oat took the collector decision from the
        // same probe as the runtime, so whichever it chose is the one the runtime will run)
        if (requireCc && BootImageGc.oatReadBarrier(oat) != true) return "the new boot.oat still has no read barrier"
        return null
    }

    // ---- Added for AEmulator Plus (2026-11-01): a boot image for a firmware that ships none ----

    /** raise when the way the from-scratch image is built changes */
    const val SCRATCH_ALGO = 1
    private const val SCRATCH_MARK = "data/.aemu-art-bootimage"
    private const val SCRATCH_DIR = "system/framework/arm64"

    /**
     * What the from-scratch image was built from: the boot jars with their sizes and the dex2oat's size. A changed boot jar
     * (an OTA) makes the image stale: ART checks the checksums of the jars against the image and would throw it away.
     */
    fun scratchSignature(root: File, jars: List<String>, bin: String): String =
        jars.joinToString(",") { j -> "${j.substringAfterLast('/')}:${File(root, j.removePrefix("/")).length()}" } +
            "|${File(root, bin.removePrefix("/")).length()}"

    private fun readMark(root: File): Map<String, String> =
        runCatching { File(root, SCRATCH_MARK).readLines() }.getOrDefault(emptyList())
            .mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()

    /** [status] is "ok" or "failed"; a failed mark for the same [sig] stops the next start from trying again in vain. */
    fun scratchMark(sig: String, status: String, reason: String = "") =
        "algo=$SCRATCH_ALGO\nsig=$sig\nstatus=$status\nreason=${reason.replace('\n', ' ').take(400)}\n"

    /**
     * Decides what [ensureScratch] has to do: "build" (no firmware boot image and none of ours, or ours is stale), "keep"
     * (ours is current, or the firmware has an image of its own: [rebuild] deals with that one), or "failed" (the same
     * build already failed for exactly this input).
     */
    fun scratchPlan(root: File, sig: String): String {
        val m = readMark(root)
        val same = m["algo"] == SCRATCH_ALGO.toString() && m["sig"] == sig
        val ours = File(root, "$SCRATCH_DIR/boot.oat").isFile && m["status"] == "ok"
        return when {
            same && m["status"] == "failed" -> "failed"
            same && ours -> "keep"
            ours -> "build" // built from other jars
            BootImageGc.bootOat(root) != null -> "keep" // the firmware's own image
            else -> "build"
        }
    }

    /**
     * Android 14+ with a 64-bit primary zygote, the firmware has no boot image (a GSI): ART's zygote would compile one in
     * memory and then relocate it to wherever its reservation landed, which aborts when the delta is not aligned to the
     * ELF segment size ("relocation delta must be Elf segment aligned", libart image.cc). Here the image is built by the
     * tree's own dex2oat64 before init starts, at the address the runtime wants, so that it is loaded in place and never
     * relocated. Takes seconds to minutes; the result is kept (and rebuilt when a boot jar changes).
     */
    fun ensureScratch(root: File, runner: GuestRunner, exports: Map<String, String>, prop: (String) -> String?, log: (String) -> Unit): Result {
        val jars = jarList(exports["DEX2OATBOOTCLASSPATH"])
        val bin = DEX2OAT64.firstOrNull { File(root, it).isFile }?.let { "/$it" }
        if (jars.isEmpty() || bin == null) return Result(false, if (jars.isEmpty()) "DEX2OATBOOTCLASSPATH is empty (derive_classpath did not run)" else "no dex2oat64 in the tree")
        val missing = jars.filter { !File(root, it.removePrefix("/")).isFile }
        if (missing.isNotEmpty()) return Result(false, "boot jars missing in the tree: ${missing.take(3).joinToString()}")
        val sig = scratchSignature(root, jars, bin)
        when (scratchPlan(root, sig)) {
            "keep" -> return Result(true, "kept")
            "failed" -> return Result(false, "the same boot image build failed before")
        }
        val dir = File(root, SCRATCH_DIR).apply { mkdirs() }
        val guestDir = "/$SCRATCH_DIR"
        val help = runCatching { runner.run(listOf(bin, "--help"), 120_000).second }.getOrNull()
        val classes = File(root, "system/etc/preloaded-classes").takeIf { it.isFile && it.length() > 0 }?.let { "/system/etc/preloaded-classes" }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val args = primaryArgs(bin, jars, guestDir, DEFAULT_BASE, prop("dalvik.vm.isa.arm64.variant"), prop("dalvik.vm.isa.arm64.features"),
            classes, threads, help, "arm64")
        fun markFailed(why: String): Result {
            runCatching { File(root, SCRATCH_MARK).apply { parentFile?.mkdirs() }.writeText(scratchMark(sig, "failed", why)) }
            log("art: boot image build failed: $why")
            return Result(false, why)
        }
        unlock(dir)
        try {
            // (a stale image of ours, or leftovers of an attempt that was cut short)
            dir.listFiles().orEmpty().filter { it.isFile && bootFileName(it.name) }.forEach { it.delete() }
            log("art: this firmware has no boot image: building one of ${jars.size} jars with the guest's dex2oat64 " +
                "(done once; ART would otherwise compile it in memory and fail relocating it)")
            val t0 = System.currentTimeMillis()
            val (code, out) = runCatching { runner.run(args, TIMEOUT_MS) }.getOrElse { -1 to (it.message ?: "") }
            val secs = (System.currentTimeMillis() - t0) / 1000
            val problem = verify(root, dir, jars.size, DEFAULT_BASE, code, requireCc = false)
            if (problem != null) {
                dir.listFiles().orEmpty().filter { it.isFile && bootFileName(it.name) }.forEach { it.delete() }
                val tail = out.trim().let { if (it.length > 900) it.take(300) + " ... " + it.takeLast(600) else it }
                log("art: dex2oat64 exit $code after $secs s: $tail")
                return markFailed(problem)
            }
            runCatching { File(root, SCRATCH_MARK).apply { parentFile?.mkdirs() }.writeText(scratchMark(sig, "ok")) }
            log("art: boot image built in $secs s at 0x${DEFAULT_BASE.toString(16)} (/$SCRATCH_DIR)")
            return Result(true, "built in $secs s")
        } finally { relock(dir) }
    }
}
