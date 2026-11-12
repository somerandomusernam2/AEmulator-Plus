package app.aemu.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MediaTek builds of libpixelflinger.so carry a modified dlmalloc: mspace_malloc() and mspace_free() call a debug hook through
 * two WEAK imports, `android::mspace_malloc_stat` and `android::mspace_free_stat` (function-pointer variables that MTK's own
 * libc is meant to define). The code never tests the address, it loads the pointer at once:
 *
 *     ldr r1, [GOT slot]   ; address of mspace_malloc_stat
 *     ldr r3, [r1]         ; <- SIGSEGV, addr 0x0, when nothing defines the symbol
 *     cbz r3, skip
 *     blx r3
 *
 * Here nothing does (the engine's libc is not MTK's), so the linker resolves the weak import to 0 and the first software-GL
 * allocation faults. The boot animation draws with this renderer and died on its first frame (exit 139, pc in libpixelflinger.so
 * right after "ldr r1, [r1]"); SurfaceFlinger on an image booted without the GPU bridge takes the same path.
 *
 * The fix keeps the code and changes the two relocations: R_ARM_GLOB_DAT against the weak symbol becomes R_ARM_RELATIVE to a
 * word of zeros inside the library's own read-only segment (its addend goes into the GOT slot). The hook then reads as "not
 * installed" and is skipped, which is what the code was written to handle. Ten bytes change; nothing else is touched.
 */
object MspaceHooks {
    private val HOOKS = setOf("_ZN7android18mspace_malloc_statE", "_ZN7android16mspace_free_statE")
    private const val EM_ARM = 40
    private const val SHT_REL = 9
    private const val SHT_DYNSYM = 11
    private const val R_ARM_GLOB_DAT = 21
    private const val R_ARM_JUMP_SLOT = 22
    private const val R_ARM_RELATIVE = 23

    /** The bytes of [d] with the hook relocations pointed at zero, or null when [d] has nothing to patch (not ARM ELF32, no such import, already done). */
    fun patched(d: ByteArray): ByteArray? {
        if (d.size < 52 || d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte() || d[2] != 'L'.code.toByte() || d[3] != 'F'.code.toByte() ||
            d[4] != 1.toByte() || d[5] != 1.toByte()) return null
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getShort(18).toInt() != EM_ARM) return null
        val shoff = b.getInt(32); val shentsize = b.getShort(46).toInt() and 0xffff; val shnum = b.getShort(48).toInt() and 0xffff
        val phoff = b.getInt(28); val phentsize = b.getShort(42).toInt() and 0xffff; val phnum = b.getShort(44).toInt() and 0xffff
        if (shoff <= 0 || shentsize < 40 || shoff.toLong() + shnum.toLong() * shentsize > d.size) return null
        if (phoff <= 0 || phentsize < 32 || phoff.toLong() + phnum.toLong() * phentsize > d.size) return null

        // undefined dynamic symbols with the hook names
        val wanted = HashSet<Int>()
        for (i in 0 until shnum) {
            val sh = shoff + i * shentsize
            if (b.getInt(sh + 4) != SHT_DYNSYM) continue
            val symOff = b.getInt(sh + 16); val symSize = b.getInt(sh + 20); val link = b.getInt(sh + 24)
            if (link !in 0 until shnum) continue
            val strOff = b.getInt(shoff + link * shentsize + 16)
            var s = symOff
            while (s >= 0 && s + 16 <= symOff + symSize && s + 16 <= d.size) {
                if ((b.getShort(s + 14).toInt() and 0xffff) == 0) {
                    var e = strOff + b.getInt(s)
                    val start = e
                    while (e in 0 until d.size && d[e] != 0.toByte()) e++
                    if (start in 0 until d.size && String(d, start, e - start, Charsets.ISO_8859_1) in HOOKS) wanted += (s - symOff) / 16
                }
                s += 16
            }
        }
        if (wanted.isEmpty()) return null

        // their relocations
        val rels = ArrayList<Int>()
        for (i in 0 until shnum) {
            val sh = shoff + i * shentsize
            if (b.getInt(sh + 4) != SHT_REL) continue
            val off = b.getInt(sh + 16); val size = b.getInt(sh + 20)
            var e = off
            while (e >= 0 && e + 8 <= off + size && e + 8 <= d.size) {
                val info = b.getInt(e + 4)
                if ((info ushr 8) in wanted && ((info and 0xff) == R_ARM_GLOB_DAT || (info and 0xff) == R_ARM_JUMP_SLOT)) rels += e
                e += 8
            }
        }
        if (rels.isEmpty()) return null

        // PT_LOAD segments: (file offset, vaddr, file size, flags)
        class Seg(val off: Int, val va: Int, val fileSize: Int, val flags: Int)
        val segs = (0 until phnum).map { phoff + it * phentsize }.filter { b.getInt(it) == 1 }
            .map { Seg(b.getInt(it + 4), b.getInt(it + 8), b.getInt(it + 16), b.getInt(it + 24)) }
        // a word of zeros (8 bytes, so a lone literal 0 is not taken) in the read-only segment: never written, so it stays 0
        val ro = segs.firstOrNull { (it.flags and 2) == 0 } ?: return null
        var z = (maxOf(ro.off, 0x200) + 3) and 3.inv()
        var zeroVa = -1
        while (z + 8 <= ro.off + ro.fileSize && z + 8 <= d.size) {
            if ((0 until 8).all { d[z + it] == 0.toByte() } && (ro.va - ro.off + z) % 4 == 0) { zeroVa = ro.va - ro.off + z; break }
            z += 4
        }
        if (zeroVa < 0) return null

        // every GOT slot must be in file-backed data (a slot in .bss would get its addend added to nothing)
        val slotOffsets = rels.map { r ->
            val slot = b.getInt(r)
            val seg = segs.firstOrNull { slot >= it.va && slot + 4 <= it.va + it.fileSize } ?: return null
            slot - seg.va + seg.off
        }
        val out = d.copyOf()
        val ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for ((i, r) in rels.withIndex()) {
            ob.putInt(r + 4, R_ARM_RELATIVE)      // symbol 0, type RELATIVE
            ob.putInt(slotOffsets[i], zeroVa)     // REL keeps the addend in the patched word itself
        }
        return out
    }

    /** Patches [f] in place; true when it changed (false: nothing to do). */
    fun apply(f: File): Boolean {
        val d = runCatching { f.readBytes() }.getOrNull() ?: return false
        val out = patched(d) ?: return false
        if (!f.canWrite()) f.setWritable(true, true)
        f.writeBytes(out)
        return true
    }
}
