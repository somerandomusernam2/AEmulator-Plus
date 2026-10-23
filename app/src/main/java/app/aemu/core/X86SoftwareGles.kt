package app.aemu.core

import android.content.Context
import android.system.Os
import java.io.File

/**
 * x86-32 firmware from real devices (Intel Atom watches/tablets/phones) often has no /system/lib/egl/libGLES_android.so: only the
 * vendor's GPU driver, which the app sets aside because it cannot run here. With ro.kernel.qemu.gles=0 libEGL loads exactly that
 * file and otherwise aborts ("couldn't find an OpenGL ES implementation", LOG_ALWAYS_FATAL -> ud2 -> SIGILL, SurfaceFlinger exit 132).
 * The AOSP software renderer built for the firmware's own API level (assets/engines/x86-gles/libGLES_android-<api>.so, i386;
 * 20 to 25 are bundled) is put in its place when the firmware has none. The renderer is version specific (libc++ from 6.0 on,
 * different libui/pixelflinger interfaces), so the file is chosen by exact API level; an API without a file is left alone.
 * Called at import and again before each start (images imported by an older build).
 */
object X86SoftwareGles {
    fun asset(api: Int) = "engines/x86-gles/libGLES_android-$api.so"

    /** deps of libGLES_android.so that the firmware itself has to provide */
    private val DEPS = listOf("libETC1.so", "libpixelflinger.so", "libui.so", "libhardware.so", "libcutils.so", "libutils.so")

    private fun present(f: File): Boolean = f.isFile && f.length() > 0   // isFile follows links: a dangling link is "absent"

    /** @return true when libGLES_android.so was missing and has been installed */
    private fun installMissing(ctx: Context, root: File, api: Int, log: (String) -> Unit): Boolean {
        val m = GuestArch.machineOf(root) ?: return false
        if (m.first != 1 || m.second != GuestArch.X86.elfMachine) return false          // only x86-32 trees
        val dirs = listOf("system/lib/egl", "vendor/lib/egl", "system/vendor/lib/egl").map { File(root, it) }
        if (dirs.any { present(File(it, "libGLES_android.so")) }) return false
        val asset = asset(api)
        if (!EngineAssets.exists(ctx, asset)) { log("GL: x86 API $api firmware has no libGLES_android.so and this build carries none for that API"); return false }
        val dir = dirs[0].apply { mkdirs() }
        val dst = File(dir, "libGLES_android.so")
        return runCatching {
            if (dst.exists() || runCatching { Os.lstat(dst.absolutePath) }.isSuccess) dst.delete()   // dangling link or empty file
            EngineAssets.open(ctx, asset).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            dst.setReadable(true, false); dst.setWritable(true, true); dst.setExecutable(false, false)
            log("GL: x86 API $api firmware has no libGLES_android.so, installed the bundled software renderer (libGLES_android-$api.so) in system/lib/egl")
            val missing = DEPS.filter { n -> listOf("system/lib", "vendor/lib", "system/vendor/lib").none { present(File(root, "$it/$n")) } }
            if (missing.isNotEmpty()) log("GL: warning, the firmware lacks ${missing.joinToString()} that libGLES_android.so links to")
            true
        }.getOrElse { log("GL: could not install libGLES_android.so: ${it.message}"); false }
    }

    /** Name libEGL 7.x can pick: any libGLES_<tag>.so other than libGLES_android.so (see [ensure]). */
    const val ALIAS = "libGLES_aemusw.so"

    /**
     * Android 7.0+ (API 24, 25): libEGL's Loader never takes libGLES_android.so from a directory scan ("always skip the software
     * renderer"). On an emulated kernel (ro.kernel.qemu=1, ro.kernel.qemu.gles=0) it still picks the file by name, but only while
     * /vendor/lib/egl does not exist; when that directory exists (even empty, as on this Wear image) it logs "Emulator has vendor
     * provided software renderer, qemu.gles is set to 2", scans for libGLES_*.so, finds nothing and aborts with
     * "couldn't find an OpenGL ES implementation" (SIGABRT, exit 134). The renderer is therefore also installed under [ALIAS]
     * in system/lib/egl, which that scan does find. The firmware's own libGLES_android.so is the source when it has one.
     * @return true when the alias was created
     */
    private fun installAlias(ctx: Context, root: File, api: Int, log: (String) -> Unit): Boolean {
        if (api < 24) return false
        val dirs = listOf("vendor/lib/egl", "system/vendor/lib/egl", "system/lib/egl").map { File(root, it) }
        // a driver the scan would take already: the firmware's, or ours from an earlier run
        if (dirs.any { d -> d.listFiles().orEmpty().any { it.name.startsWith("libGLES_") && it.name != "libGLES_android.so" && present(it) } }) return false
        val dir = File(root, "system/lib/egl").apply { mkdirs() }
        val own = File(dir, "libGLES_android.so")
        val dst = File(dir, ALIAS)
        return runCatching {
            if (dst.exists() || runCatching { Os.lstat(dst.absolutePath) }.isSuccess) dst.delete()
            val from = if (present(own)) { own.inputStream() } else {
                val a = asset(api)
                if (!EngineAssets.exists(ctx, a)) { log("GL: API $api libEGL needs a libGLES_<tag>.so and there is no software renderer to use"); return false }
                EngineAssets.open(ctx, a)
            }
            from.use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            dst.setReadable(true, false); dst.setWritable(true, true); dst.setExecutable(false, false)
            log("GL: API $api libEGL skips libGLES_android.so when /vendor/lib/egl exists, software renderer also installed as $ALIAS")
            true
        }.getOrElse { log("GL: could not install $ALIAS: ${it.message}"); false }
    }

    /**
     * Android 5.x/6.0 (API 20 to 23) libEGL, when it does not take the emulator path (ro.kernel.qemu.gles=0 -> libGLES_android.so
     * by name), looks for the exact file egl/libGLES.so and then scans the directory for libGLES_<tag>.so; a directory that
     * holds only libGLES_android.so (this one still gets "couldn't find an OpenGL ES implementation", SIGILL, SurfaceFlinger
     * exit 132; the trace probes libGLES.so and never libGLES_android.so). The exact name is tried first and exists for
     * every API level of the loader, so the software renderer is also installed under it, from the firmware's own
     * libGLES_android.so when it has one, else the bundled one.
     * @return true when the file was created
     */
    private fun installPlain(ctx: Context, root: File, api: Int, log: (String) -> Unit): Boolean {
        if (api >= 24) return false            // 7.x: see installAlias
        val dirs = listOf("vendor/lib/egl", "system/vendor/lib/egl", "system/lib/egl").map { File(root, it) }
        if (dirs.any { present(File(it, "libGLES.so")) }) return false
        val dir = File(root, "system/lib/egl").apply { mkdirs() }
        val own = File(dir, "libGLES_android.so")
        val dst = File(dir, "libGLES.so")
        return runCatching {
            if (dst.exists() || runCatching { Os.lstat(dst.absolutePath) }.isSuccess) dst.delete()
            val from = if (present(own)) own.inputStream() else {
                val a = asset(api)
                if (!EngineAssets.exists(ctx, a)) { log("GL: API $api libEGL looks for egl/libGLES.so and there is no software renderer to use"); return false }
                EngineAssets.open(ctx, a)
            }
            from.use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            dst.setReadable(true, false); dst.setWritable(true, true); dst.setExecutable(false, false)
            log("GL: API $api libEGL looks for egl/libGLES.so first, software renderer also installed under that name")
            true
        }.getOrElse { log("GL: could not install libGLES.so: ${it.message}"); false }
    }

    /** x86-32 trees only. @return true when something was installed */
    fun ensure(ctx: Context, root: File, api: Int, log: (String) -> Unit): Boolean {
        val m = GuestArch.machineOf(root) ?: return false
        if (m.first != 1 || m.second != GuestArch.X86.elfMachine) return false
        val a = installMissing(ctx, root, api, log)
        val b = installAlias(ctx, root, api, log)
        val c = installPlain(ctx, root, api, log)
        return a || b || c
    }
}
