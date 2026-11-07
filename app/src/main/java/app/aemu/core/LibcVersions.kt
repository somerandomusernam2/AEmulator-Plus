package app.aemu.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Symbol-version tags of the guest's own libraries against the firmware's libc.so.
 *
 * The engine's guest libraries (libashmemshim, libaemushim, the GL and audio HALs...) are built against an NDK libc
 * stub that versions its symbols ("syscall@LIBC", "open@LIBC"...). The Android 6.0 preview's libc.so ships a version
 * definition "LIBC" but gives nearly all of its symbols the plain global index, and its linker checks the version of
 * a reference: every program with libashmemshim.so preloaded died with
 * "CANNOT LINK EXECUTABLE: cannot locate symbol "syscall" referenced by .../libashmemshim.so".
 *
 * The cure is decided by the tree, not by the API level: for every undefined symbol of our library that is tagged with
 * a version needed from libc.so, look the symbol up in the tree's libc.so; when libc defines it unversioned, the
 * reference is made unversioned too (versym index 1). A libc that does carry the version is left alone, and so is a
 * linker that never looked at versions: the result is the same reference without the tag.
 */
object LibcVersions {
    private const val SHT_DYNSYM = 11
    private const val SHT_VERSYM = 0x6fffffff
    private const val SHT_VERNEED = 0x6ffffffe
    private const val VER_GLOBAL = 1

    private class Elf(val d: ByteArray) {
        val b: ByteBuffer = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        class Sec(val type: Int, val off: Int, val size: Int, val link: Int)
        val secs = ArrayList<Sec>()
        val ok: Boolean

        init {
            var good = d.size > 0x34 && d[0] == 0x7f.toByte() && d[1] == 'E'.code.toByte() && d[4].toInt() == 1 && d[5].toInt() == 1
            if (good) runCatching {
                val shoff = b.getInt(0x20); val shent = b.getShort(0x2e).toInt() and 0xffff; val shnum = b.getShort(0x30).toInt() and 0xffff
                if (shoff <= 0 || shent < 40) good = false
                else for (i in 0 until shnum) {
                    val o = shoff + i * shent
                    secs.add(Sec(b.getInt(o + 4), b.getInt(o + 16), b.getInt(o + 20), b.getInt(o + 24)))
                }
            }.onFailure { good = false }
            ok = good
        }

        fun sec(type: Int) = secs.firstOrNull { it.type == type }

        fun str(tab: Sec, idx: Int): String {
            var e = tab.off + idx
            while (e < d.size && d[e].toInt() != 0) e++
            return String(d, tab.off + idx, e - (tab.off + idx), Charsets.US_ASCII)
        }
    }

    /** name → version index for the symbols libc.so defines (index 0 and 1 both mean "no version") */
    private fun definedVersions(libc: File): Map<String, Int>? = runCatching {
        val e = Elf(libc.readBytes())
        if (!e.ok) return@runCatching null
        val dynsym = e.sec(SHT_DYNSYM) ?: return@runCatching null
        val versym = e.sec(SHT_VERSYM)
        val names = e.secs.getOrNull(dynsym.link) ?: return@runCatching null
        val out = HashMap<String, Int>()
        for (i in 0 until dynsym.size / 16) {
            val o = dynsym.off + i * 16
            if (e.b.getShort(o + 14).toInt() == 0) continue                       // undefined here
            val v = versym?.let { (e.b.getShort(it.off + i * 2).toInt() and 0x7fff) } ?: VER_GLOBAL
            out[e.str(names, e.b.getInt(o))] = v
        }
        out
    }.getOrNull()

    /** Makes the version tags of [target] against libc.so agree with [libcDefs]. Returns how many references changed. */
    private fun relax(target: File, libcDefs: Map<String, Int>): Int = runCatching {
        val e = Elf(target.readBytes())
        if (!e.ok) return@runCatching 0
        val dynsym = e.sec(SHT_DYNSYM) ?: return@runCatching 0
        val versym = e.sec(SHT_VERSYM) ?: return@runCatching 0
        val verneed = e.sec(SHT_VERNEED) ?: return@runCatching 0
        val needStr = e.secs.getOrNull(verneed.link) ?: return@runCatching 0
        val names = e.secs.getOrNull(dynsym.link) ?: return@runCatching 0
        // version indexes this library needs from libc.so
        val libcIdx = HashSet<Int>()
        var o = verneed.off
        while (o + 16 <= verneed.off + verneed.size) {
            val cnt = e.b.getShort(o + 2).toInt() and 0xffff
            val file = e.str(needStr, e.b.getInt(o + 4))
            var a = o + e.b.getInt(o + 8)
            if (file == "libc.so") for (k in 0 until cnt) {
                libcIdx.add(e.b.getShort(a + 6).toInt() and 0x7fff)
                val next = e.b.getInt(a + 12); if (next == 0) break; a += next
            }
            val next = e.b.getInt(o + 12); if (next == 0) break; o += next
        }
        if (libcIdx.isEmpty()) return@runCatching 0
        var changed = 0
        for (i in 0 until dynsym.size / 16) {
            val s = dynsym.off + i * 16
            if (e.b.getShort(s + 14).toInt() != 0) continue                      // defined here, not a reference
            val vo = versym.off + i * 2
            val v = e.b.getShort(vo).toInt() and 0x7fff
            if (v !in libcIdx) continue
            val def = libcDefs[e.str(names, e.b.getInt(s))] ?: continue          // libc has no such symbol: not ours to judge
            if (def <= VER_GLOBAL) { e.b.putShort(vo, VER_GLOBAL.toShort()); changed++ }
        }
        if (changed > 0) target.writeBytes(e.d)
        changed
    }.getOrDefault(0)

    private val PREFIXES = listOf("libaemu", "libashmemshim", "libshmshim", "libGLES", "audio.primary", "audio_policy", "gralloc.", "lights.")

    /** Applies [relax] to the engine's own libraries in the tree. Returns the number of files changed. */
    fun relaxEngineLibs(root: File): Int {
        val defs = definedVersions(File(root, "system/lib/libc.so")) ?: return 0
        var files = 0
        for (dir in listOf("system/lib", "system/lib/hw", "system/lib/egl", "vendor/lib")) {
            for (f in File(root, dir).listFiles().orEmpty()) {
                if (!f.isFile || !f.name.endsWith(".so") || PREFIXES.none { f.name.startsWith(it) }) continue
                if (runCatching { android.system.Os.lstat(f.absolutePath).st_mode and 0xF000 == 0xA000 }.getOrDefault(false)) continue
                if (relax(f, defs) > 0) files++
            }
        }
        return files
    }
}
