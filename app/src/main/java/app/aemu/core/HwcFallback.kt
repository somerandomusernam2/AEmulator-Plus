package app.aemu.core

import java.io.File

/**
 * Added for AEmulator Plus, 2026-11-12. The stand-in composer (native/hwc1fb, see TreeFixer.framebufferComposer) goes into every
 * 4.2 - 4.4 image first. A vendor-modified SurfaceFlinger (MediaTek's, for one) can call composer entry points the stand-in does
 * not have and dies with SIGSEGV right after it starts. When that happens before the system has booted, the stand-in is taken out
 * again and the system is restarted without it; a marker file in the image directory remembers the verdict, so the image keeps
 * running on the framebuffer HAL alone (the same way QemuFallback remembers the translator choice).
 *
 * To try the stand-in again on an image: delete the file "hwc-standin-off" from its directory.
 */
object HwcFallback {
    private const val MARKER = "hwc-standin-off"
    private const val NAME_STRING = "AEmulator framebuffer hwcomposer"

    private fun marker(paths: VmPaths) = File(paths.dir, MARKER)

    /** a surfaceflinger crashed with the stand-in in place: it stays out of this image */
    fun disabled(paths: VmPaths): Boolean = marker(paths).exists()

    fun disable(paths: VmPaths): Boolean = runCatching { marker(paths).writeText("1"); true }.getOrDefault(false)

    /** the stand-in composer of this engine (it carries its own name string), from this or an earlier build */
    fun isStandIn(f: File): Boolean = f.isFile && runCatching {
        !java.nio.file.Files.isSymbolicLink(f.toPath()) && String(f.readBytes(), Charsets.ISO_8859_1).contains(NAME_STRING)
    }.getOrDefault(false)

    /** the stand-in is in the guest's hw directory right now */
    fun installed(root: File): Boolean = isStandIn(File(root, "system/lib/hw/hwcomposer.default.so"))

    /** SIGSEGV (139) or SIGABRT (134) of surfaceflinger: what the vendor-composer mismatch looks like */
    fun isCrashExit(code: Int): Boolean = code == 139 || code == 134

    /** Takes the stand-in out of the tree (the live module and the copy sanitize() parks); true when something was removed. */
    fun remove(root: File): Boolean {
        var removed = false
        File(root, "system/lib/hw/hwcomposer.default.so").takeIf { isStandIn(it) }?.let { removed = it.delete() || removed }
        File(root, "system/.aemu-parked/system#lib#hw#hwcomposer.default.so").takeIf { isStandIn(it) }?.let { removed = it.delete() || removed }
        return removed
    }
}
