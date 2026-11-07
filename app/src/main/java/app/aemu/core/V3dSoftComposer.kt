package app.aemu.core

import java.io.File
import java.io.RandomAccessFile

/**
 * Samsung's Broadcom (BCM21553-class, GT-S5302 2.3.6) libsurfaceflinger has no OpenGL path: every layer goes through
 * V3D_Compose_Impl::TextureCompose, which only queues work for /dev/v3d. Without the device V3D_Compose::Create() returns NULL,
 * the first frame calls through it (SIGSEGV at libsurfaceflinger.so+0x183d8, zygote exits with 137) and fb0 stays all zeros.
 *
 * [apply] installs a software composer (native/sfv3d/v3dsoft.c, ~1.5 KB placed in the unused tail of the text page) by the byte
 * table that tools/patch_sf_v3d.py derived from the firmware's library ([V3dSoftTable]). Every entry's old bytes must match, so
 * any other build is left alone. See docs/sf-v3d-software-composer.md.
 */
object V3dSoftComposer {
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /**
     * 1 when the library was patched now, 0 when it already was or is not this build. A library carrying an older version of
     * this patch (it no longer matches) is put back from [pristine], the copy kept before the first patch, and patched again.
     */
    fun apply(lib: File, pristine: File? = null): Int {
        val r = tryApply(lib)
        if (r >= 0) return r
        if (pristine == null || !pristine.isFile || pristine.length() != lib.length()) return 0
        if (!lib.canWrite()) lib.setWritable(true, true)
        val ok = runCatching { pristine.copyTo(lib, overwrite = true) }.isSuccess
        return if (ok) maxOf(tryApply(lib), 0) else 0
    }

    /** 1 patched now, 0 already patched, -1 the bytes are neither the original nor this patch. */
    private fun tryApply(lib: File): Int {
        val d = runCatching { lib.readBytes() }.getOrNull() ?: return -1
        val todo = ArrayList<Pair<Int, ByteArray>>()
        for ((off, oldHex, newHex) in V3dSoftTable.patches) {
            val old = unhex(oldHex); val new = unhex(newHex)
            if (off < 0 || off + new.size > d.size) return -1
            val cur = d.copyOfRange(off, off + new.size)
            when {
                cur.contentEquals(new) -> {}
                cur.copyOfRange(0, old.size).contentEquals(old) -> todo.add(off to new)
                else -> return -1
            }
        }
        if (todo.isEmpty()) return 0
        if (!lib.canWrite()) lib.setWritable(true, true)
        RandomAccessFile(lib, "rw").use { raf -> for ((off, new) in todo) { raf.seek(off.toLong()); raf.write(new) } }
        return 1
    }

    /**
     * Samsung's libui reads the framebuffer device's function pointers 8 bytes later than the engine gralloc stores them
     * (setSwapInterval 0x90/0x88, setUpdateRect 0x94/0x8c, post 0x98/0x90, compositionComplete 0x9c/0x94): FramebufferNativeWindow::
     * queueBuffer called a NULL post() (SIGSEGV pc=0, lr libui+0x1b2d1) on the first flip. Five `adds Rn,#imm` immediates are
     * lowered by 8; exact bytes only. 1 when patched now, 0 when already patched or another build.
     */
    fun applyLibui(lib: File): Int {
        val t = listOf(0x1b2c6 to (0x3098 to 0x3090), 0x1b09a to (0x3594 to 0x358c), 0x1b0b2 to (0x339c to 0x3394),
            0x1b0ca to (0x3290 to 0x3288), 0x1b5c4 to (0x3394 to 0x338c))
        val d = runCatching { lib.readBytes() }.getOrNull() ?: return 0
        fun h(o: Int) = (d[o].toInt() and 0xff) or ((d[o + 1].toInt() and 0xff) shl 8)
        if (t.any { (o, _) -> o + 2 > d.size }) return 0
        if (t.all { (o, v) -> h(o) == v.second }) return 0
        if (!t.all { (o, v) -> h(o) == v.first }) return 0
        if (!lib.canWrite()) lib.setWritable(true, true)
        RandomAccessFile(lib, "rw").use { raf ->
            for ((o, v) in t) { raf.seek(o.toLong()); raf.write(byteArrayOf((v.second and 0xff).toByte(), (v.second shr 8).toByte())) }
        }
        return 1
    }

    /** True when [lib] looks like the V3D build this patch is for (the composer class is in it). */
    fun needed(lib: File): Boolean = runCatching {
        String(lib.readBytes(), Charsets.ISO_8859_1).contains("V3D Composer creation failed")
    }.getOrDefault(false)
}
