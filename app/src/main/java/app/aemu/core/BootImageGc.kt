/* AEmulator Plus addition, 2026-10-28. GPL-3.0; see LICENSE. */
package app.aemu.core

import java.io.File

/**
 * Android 14+: ART refuses a boot image whose oat was built for another read-barrier state than the running runtime:
 *   "Error reading named image component header for /system/framework/boot.art, error: ValidateOatFile found read
 *    barrier state mismatch (oat file: false, runtime: true)"
 * zygote then tries to compile a full boot image itself (dex2oat32 under qemu, which fails), falls back to imageless
 * running, every class is loaded and verified from the dex files, and system_server is so slow that the watchdog kills
 * the permission controller.
 *
 * Cause: a firmware whose boot image was built for the userfaultfd collector (CMC: no read barrier, oat header key
 * "concurrent-copying" = false) is started here on a guest without userfaultfd, so the runtime falls back to the CC
 * collector (read barrier on, "Using CollectorTypeCC GC"). -Xgc:CMS does not help: this ART keeps CC.
 *
 * Fix: see [LibArtReadBarrierPatch], which lets libart accept the image. The image's compiled code has no read barriers,
 * so the guest must not run it under CC: the caller keeps the runtime interpreter-only.
 */
object BootImageGc {
    private const val KEY = "concurrent-copying"
    // the key-value store follows the fixed oat header, near the start of the oatdata section (offset 0x1000 in the
    // ELF); the long entries (dex2oat-cmdline, bootclasspath) are a few KB, so a modest window is enough
    private const val SCAN_BYTES = 256 * 1024

    /** Where a firmware keeps the oat of its boot image, most likely first. */
    private fun candidates(root: File): List<File> {
        val out = ArrayList<File>()
        for (base in listOf("system/framework", "system/system/framework")) {
            for (isa in listOf("arm", "arm64", "x86", "x86_64")) out += File(root, "$base/$isa/boot.oat")
            out += File(root, "$base/boot.oat")
        }
        return out
    }

    /** The firmware's boot.oat (the primary component), or null. */
    fun bootOat(root: File): File? = candidates(root).firstOrNull { it.isFile }

    /**
     * The "concurrent-copying" value in the oat header key-value store: true / false, or null when the file or the key
     * is missing (older ART, a firmware without a boot image).
     */
    fun oatReadBarrier(oat: File): Boolean? {
        if (!oat.isFile) return null
        val buf = ByteArray(SCAN_BYTES)
        val n = runCatching { oat.inputStream().use { it.read(buf) } }.getOrDefault(-1)
        return if (n > 0) parseReadBarrier(buf, n) else null
    }

    /** [buf] is the start of an oat file; entries of the store are "key\0value\0". */
    fun parseReadBarrier(buf: ByteArray, len: Int = buf.size): Boolean? {
        val key = KEY.toByteArray(Charsets.US_ASCII)
        var i = 0
        while (i + key.size + 2 <= len) {
            // the key must start an entry (the byte before is the NUL ending the previous value) and end with a NUL
            if (buf[i] == key[0] && (i == 0 || buf[i - 1].toInt() == 0) && buf[i + key.size].toInt() == 0 &&
                (key.indices).all { buf[i + it] == key[it] }) {
                val v = i + key.size + 1
                var e = v
                while (e < len && buf[e].toInt() != 0) e++
                if (e >= len) return null
                return when (String(buf, v, e - v, Charsets.US_ASCII)) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
            }
            i++
        }
        return null
    }

    /** True when the firmware's boot image was built without a read barrier. */
    fun bootImageIsNonCc(root: File): Boolean =
        candidates(root).firstNotNullOfOrNull { oatReadBarrier(it) } == false
}
