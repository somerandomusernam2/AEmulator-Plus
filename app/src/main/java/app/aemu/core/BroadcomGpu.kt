package app.aemu.core

import java.io.File
import java.io.RandomAccessFile

/**
 * Broadcom (BCM2153x: Samsung GT-S5300 / S5302 / S5360 ... 2.3) firmware talks to three device nodes that do not exist here:
 * /dev/gememalloc (physically contiguous buffers, used by gralloc and SurfaceFlinger), /dev/v3d (the 3D block SurfaceFlinger
 * composes with) and /dev/cache-ops. They are emulated inside libaemushim (native/bcmgpu, docs/bcm-v3d-emulation.md), so nothing
 * in the firmware is patched. This object only knows how to recognise such a firmware and how to take out what older builds did:
 * they moved the firmware gralloc aside, binary-patched libsurfaceflinger.so (a software composer in its text tail) and libui.so.
 */
object BroadcomGpu {
    /** True when [lib] is a libsurfaceflinger that composes through V3D_Compose_Impl. */
    fun hasV3dComposer(lib: File): Boolean = runCatching {
        String(lib.readBytes(), Charsets.ISO_8859_1).contains("V3D Composer creation failed")
    }.getOrDefault(false)

    /**
     * The pool file libaemushim opens for /dev/gememalloc (BG_POOL_FILE in native/bcmgpu/bcmgpu.h). qemu's -L prefix only redirects
     * a path that already exists in the tree, so the shim's open(O_CREAT) of a file that is not there went to the host's /dev, failed,
     * and the firmware gralloc got "failed to open: /dev/gememalloc" (err=Operation not permitted); SurfaceFlinger then dereferenced the
     * NULL V3D composer and took zygote down in a loop. The file is made here, empty and world read/write; the shim sizes it (sparse).
     * Returns true when the file is in place.
     */
    fun ensurePoolFile(root: File): Boolean = runCatching {
        val dev = File(root, "dev").apply { mkdirs() }
        dev.setReadable(true, false); dev.setExecutable(true, false)
        val f = File(dev, POOL_FILE)
        if (!f.isFile) { f.delete(); f.createNewFile() }
        // a new boot starts with an empty allocator: the book-keeping lives in the file's first page and survives the guest, and
        // buffers of killed processes are never freed, so the table of the previous run filled up and every allocation of the next
        // boot failed ("v3d_compose allocation of physical mem failed" -> NULL composer -> SurfaceFlinger SIGSEGV, zygote in a loop)
        runCatching { java.io.RandomAccessFile(f, "rw").use { it.setLength(0) } }
        f.setReadable(true, false); f.setWritable(true, false)
        f.isFile
    }.getOrDefault(false)

    /**
     * The system restarts inside a running guest (zygote crash): every app of the old zygote was killed with its buffers still
     * booked in the pool. Zero the header page so the allocator starts over (magic mismatch -> the shim re-initialises it); the file
     * is not truncated, other guest processes (mediaserver) may still have it mapped.
     */
    fun resetPoolHeader(root: File): Boolean = runCatching {
        val f = File(root, "dev/$POOL_FILE")
        if (!f.isFile || f.length() < 4096) return@runCatching false
        java.io.RandomAccessFile(f, "rw").use { it.seek(0); it.write(ByteArray(4096)) }
        true
    }.getOrDefault(false)

    const val POOL_FILE = ".aemu-gememalloc"

    /**
     * True for a Broadcom firmware whose SurfaceFlinger composes through /dev/v3d. Such a SurfaceFlinger draws the screen into fb0
     * itself and never calls eglSwapBuffers, so the in-app GPU bridge has nothing to show (blank screen) and would also take the
     * apps' GL away from the gralloc buffers SurfaceFlinger composes. The GPU option is treated as off for it.
     */
    fun v3dFirmware(root: File): Boolean =
        File(root, "system/lib/libsurfaceflinger.so").let { it.isFile && hasV3dComposer(it) }

    /** Puts back the gralloc modules an older build set aside as `*.gememalloc`; returns how many. */
    fun restoreParkedGralloc(root: File): Int {
        var n = 0
        File(root, "system/.aemu-parked").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".gememalloc") && it.name.contains("#gralloc.") }
            ?.forEach { parked ->
                val dst = File(root, parked.name.removeSuffix(".gememalloc").replace('#', '/'))
                if (dst.exists()) { parked.delete(); return@forEach }      // something already stands there: keep it
                if (parked.renameTo(dst)) n++
            }
        return n
    }

    /** The old software composer rewrote the V3D functions of the GT-S530x build, file range 0x24800..0x26c40. */
    private const val SF_V3D_FROM = 0x24800
    private const val SF_V3D_TO = 0x26c40

    /** 1 when the old composer patch was found in [lib] and [pristine] (the copy kept before any patch) was put back. */
    fun undoLegacyComposerPatch(lib: File, pristine: File): Int {
        if (!pristine.isFile || pristine.length() != lib.length() || !hasV3dComposer(pristine)) return 0
        val a = runCatching { lib.readBytes() }.getOrNull() ?: return 0
        val b = runCatching { pristine.readBytes() }.getOrNull() ?: return 0
        if (SF_V3D_TO > a.size) return 0
        var same = true
        for (i in SF_V3D_FROM until SF_V3D_TO) if (a[i] != b[i]) { same = false; break }
        if (same) return 0
        if (!lib.canWrite()) lib.setWritable(true, true)
        return if (runCatching { pristine.copyTo(lib, overwrite = true) }.isSuccess) 1 else 0
    }

    /** libui's five framebuffer-device offsets (old value, value the old patch wrote). */
    private val LIBUI = listOf(0x1b2c6 to (0x3098 to 0x3090), 0x1b09a to (0x3594 to 0x358c), 0x1b0b2 to (0x339c to 0x3394),
        0x1b0ca to (0x3290 to 0x3288), 0x1b5c4 to (0x3394 to 0x338c))

    /** 1 when the old libui patch was found and undone (in place, only the five halfwords it changed). */
    fun undoLegacyLibuiPatch(ui: File, backup: File): Int {
        val d = runCatching { ui.readBytes() }.getOrNull() ?: return 0
        fun h(o: Int) = (d[o].toInt() and 0xff) or ((d[o + 1].toInt() and 0xff) shl 8)
        if (LIBUI.any { (o, _) -> o + 2 > d.size }) return 0
        if (!LIBUI.all { (o, v) -> h(o) == v.second }) return 0
        if (!ui.canWrite()) ui.setWritable(true, true)
        RandomAccessFile(ui, "rw").use { raf ->
            for ((o, v) in LIBUI) { raf.seek(o.toLong()); raf.write(byteArrayOf((v.first and 0xff).toByte(), (v.first shr 8).toByte())) }
        }
        return 1
    }
}
