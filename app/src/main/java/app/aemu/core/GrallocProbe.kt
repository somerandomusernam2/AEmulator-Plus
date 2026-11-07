package app.aemu.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Static check of a gralloc HAL module (a 32-bit ARM ELF `gralloc.*.so`) for the "same-process unregister" bug described in
 * [GtiGralloc]: does `gralloc_unregister_buffer` munmap() a buffer that the calling process allocated itself?
 *
 * Eclair/Gingerbread gralloc guards the unmap with `hnd->pid != getpid()`, KitKat gralloc does not. The check follows the
 * HAL module struct (`HMI` in .dynsym, `gralloc_module_t.unregisterBuffer` at +132, which survives stripping) and looks for a
 * call to libc `getpid` in that function or in a local function it calls (two levels deep: validate/unmap helpers, or a pid
 * helper that the compiler outlined). Calls are found by decoding BL/BLX in ARM and Thumb-2 code; a PLT call is resolved to
 * its symbol through .rel.plt.
 *
 * Anything that cannot be read with confidence is [Verdict.UNKNOWN], and callers treat UNKNOWN as "leave it alone".
 */
object GrallocProbe {
    enum class Verdict {
        /** unregister consults getpid(): same-process buffers are not unmapped (Eclair/GB behaviour). */
        GUARDED,

        /** unregister never consults getpid(): it unmaps every buffer, including one the caller allocated (KitKat behaviour). */
        UNGUARDED,

        /** not a readable ARM gralloc module. */
        UNKNOWN,
    }

    /** Size of the alloc_device_t that AOSP gralloc.default builds for "gpu0": hw_device_t (0x40) + alloc, free, dump + 3 reserved words... = 0x68. */
    const val AOSP_ALLOC_DEVICE_SIZE = 0x68

    /**
     * Bytes that `gralloc_device_open` allocates (malloc + memset) for the "gpu0" device, read from the code, or null if unreadable.
     *
     * Sony 4.2.x (CAF) firmware ships a `gralloc.default.so` without any ION / libmemalloc reference that nevertheless builds a
     * larger alloc_device_t (0x6c): one extra field sits in front of `alloc`, so `alloc` is stored at +0x44 and the word at
     * +0x40, where the AOSP-layout libui calls it, stays NULL (SurfaceFlinger: SIGSEGV pc=0, lr libui+0x732f on the first
     * GraphicBuffer allocation). The marker strings of an ION gralloc are not there, so only the layout gives it away.
     */
    fun allocDeviceSize(elf: ByteArray): Int? {
      return try {
        val d = elf
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        fun u32(o: Int) = b.getInt(o)
        fun u16(o: Int) = b.getShort(o).toInt() and 0xffff
        if (d.size < 52 || d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte() || d[4].toInt() != 1 || d[5].toInt() != 1 || u16(18) != 40) null
        else {
            val loads = ArrayList<IntArray>()
            val phoff = u32(0x1c); val phent = u16(0x2a); val phnum = u16(0x2c)
            var dynOff = -1; var dynSize = 0
            for (i in 0 until phnum) {
                val p = phoff + i * phent
                if (u32(p) == 1) loads.add(intArrayOf(u32(p + 8), u32(p + 4), u32(p + 16)))
                if (u32(p) == 2) { dynOff = u32(p + 4); dynSize = u32(p + 16) }
            }
            fun fo(va: Int): Int? { for (l in loads) if (va >= l[0] && va < l[0] + l[2]) return va - l[0] + l[1]; return null }
            val tags = HashMap<Int, Int>()
            var i = 0
            while (dynOff >= 0 && i + 8 <= dynSize) { val t = u32(dynOff + i); if (t == 0) break; if (!tags.containsKey(t)) tags[t] = u32(dynOff + i + 4); i += 8 }
            val symtab = fo(tags[6] ?: return null) ?: return null
            val strtab = fo(tags[5] ?: return null) ?: return null
            val nsym = (strtab - symtab) / 16
            fun name(s: Int): String { var e = strtab + u32(symtab + s * 16); val st = e; while (d[e] != 0.toByte()) e++; return String(d, st, e - st, Charsets.ISO_8859_1) }
            val hmiSym = (0 until nsym).firstOrNull { name(it) == "HMI" && u16(symtab + it * 16 + 14) != 0 } ?: return null
            val hmi = fo(u32(symtab + hmiSym * 16 + 4)) ?: return null
            if (u32(hmi) != 0x48574d54) return null
            val methods = fo(u32(hmi + 20)) ?: return null      // hw_module_t.methods -> hw_module_methods_t.open
            val open = u32(methods)
            if ((open and 1) == 0) return null                    // Thumb only (what every 4.x gralloc is)
            val o = fo(open and 1.inv()) ?: return null
            // the first `movs r0,#imm8` that is immediately followed by a BL/BLX is malloc(sizeof(gralloc_context))
            var p = 0
            var size: Int? = null
            while (p < 0x60 && o + p + 6 <= d.size) {
                val h = u16(o + p); val n1 = u16(o + p + 2); val n2 = u16(o + p + 4)
                if ((h and 0xff00) == 0x2000 && (n1 and 0xf800) == 0xf000 && (n2 and 0xc000) == 0xc000) { size = h and 0xff; break }
                p += 2
            }
            size
        }
    } catch (_: Exception) { null }
    }

    /**
     * The engine (AOSP-layout) gralloc with `free` moved to device slot +0x48, or null if its `gralloc_device_open` is not the
     * known one. Sony 4.2.x libui calls `alloc` at +0x40 (as AOSP) but `free` at +0x48 (AOSP: +0x44): with the AOSP device the
     * first freed buffer (BufferLiberator thread) calls NULL, SurfaceFlinger dies (SIGSEGV pc=0, lr libui+0x74c3) and zygote with it.
     * The store sequence `str r0,[r4,#0x40]; mov r0,r5; add r3,pc; str r1,[r4,#0x44]; str r3,[r4,#0x3c]` becomes the same with
     * `str r1,[r4,#0x48]` (the device is 0x68 bytes, so +0x48 is inside it).
     */
    fun withFreeAtSlot48(elf: ByteArray): ByteArray? {
        val start = openFunctionOffset(elf) ?: return null
        val want = intArrayOf(0x6420, 0x4628, 0x447b, 0x6461, 0x63e3)
        var p = start
        val end = minOf(start + 0x80, elf.size - 10)
        while (p <= end) {
            var ok = true
            for (k in want.indices) {
                val h = (elf[p + 2 * k].toInt() and 0xff) or ((elf[p + 2 * k + 1].toInt() and 0xff) shl 8)
                if (h != want[k]) { ok = false; break }
            }
            if (ok) {
                val out = elf.copyOf()
                out[p + 6] = 0xa1.toByte(); out[p + 7] = 0x64   // 0x6461 -> 0x64a1: str r1,[r4,#0x48]
                return out
            }
            p += 2
        }
        return null
    }

    /**
     * Added for AEmulator Plus (2026-11-01): the alloc_device_t slot this firmware's libui frees buffers through, read from
     * its code: 0x44 (AOSP) or 0x48 (Sony 4.2.x), or null when it cannot be told. The call is `ldr r3,[rN,#slot]; blx r3`
     * (Thumb, 0x4798), which is how GraphicBufferAllocator::free and ::alloc dispatch (alloc is +0x40, so it does not mix
     * the two up). A firmware whose libui uses 0x44 must keep the engine gralloc as it is: the device size alone (a
     * bigger alloc_device_t, which an ION gralloc has too) said "Sony" for a MediaTek 4.2 build, free was moved to +0x48,
     * and libui called the NULL at +0x44 (SurfaceFlinger: SIGSEGV pc=0, lr libui+0x687d, in a loop).
     */
    fun libuiFreeSlot(libui: ByteArray): Int? {
        // GraphicBufferAllocator::free: `ldr r0,[rX]` (the device), `mov r1,rY` (the handle), `ldr r3,[r0,#slot]`, `blx r3`.
        // A bare `ldr r3,[rN,#0x44]; blx r3` is any virtual call, so the two set-up instructions are required as well.
        var at44 = 0; var at48 = 0
        fun h(o: Int) = (libui[o].toInt() and 0xff) or ((libui[o + 1].toInt() and 0xff) shl 8)
        var p = 0
        while (p + 8 <= libui.size) {
            if (h(p + 6) == 0x4798 && (h(p + 4) and 0xf83f) == 0x6803 /* ldr r3,[r0,#imm5*4] */) {
                val a = h(p); val b = h(p + 2)
                fun ldr0(x: Int) = (x and 0xffc0) == 0x6800 && (x and 7) == 0    // ldr r0,[rX,#0]
                fun mov1(x: Int) = (x and 0xff87) == 0x4601                       // mov r1,rY
                if ((ldr0(a) && mov1(b)) || (mov1(a) && ldr0(b))) when (((h(p + 4) shr 6) and 0x1f) * 4) { 0x44 -> at44++; 0x48 -> at48++ }
            }
            p += 2
        }
        return when { at44 > 0 && at48 == 0 -> 0x44; at48 > 0 && at44 == 0 -> 0x48; else -> null }
    }

    /** File offset of the Thumb `gralloc_device_open` (HMI.methods->open), or null. */
    private fun openFunctionOffset(d: ByteArray): Int? {
        return try {
            val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
            fun u32(o: Int) = b.getInt(o)
            fun u16(o: Int) = b.getShort(o).toInt() and 0xffff
            if (d.size < 52 || d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte() || d[4].toInt() != 1 || d[5].toInt() != 1 || u16(18) != 40) return null
            val loads = ArrayList<IntArray>()
            val phoff = u32(0x1c); val phent = u16(0x2a); val phnum = u16(0x2c)
            var dynOff = -1; var dynSize = 0
            for (i in 0 until phnum) {
                val p = phoff + i * phent
                if (u32(p) == 1) loads.add(intArrayOf(u32(p + 8), u32(p + 4), u32(p + 16)))
                if (u32(p) == 2) { dynOff = u32(p + 4); dynSize = u32(p + 16) }
            }
            fun fo(va: Int): Int? { for (l in loads) if (va >= l[0] && va < l[0] + l[2]) return va - l[0] + l[1]; return null }
            val tags = HashMap<Int, Int>()
            var i = 0
            while (dynOff >= 0 && i + 8 <= dynSize) { val t = u32(dynOff + i); if (t == 0) break; if (!tags.containsKey(t)) tags[t] = u32(dynOff + i + 4); i += 8 }
            val symtab = fo(tags[6] ?: return null) ?: return null
            val strtab = fo(tags[5] ?: return null) ?: return null
            val nsym = (strtab - symtab) / 16
            fun name(s: Int): String { var e = strtab + u32(symtab + s * 16); val st = e; while (d[e] != 0.toByte()) e++; return String(d, st, e - st, Charsets.ISO_8859_1) }
            val hmiSym = (0 until nsym).firstOrNull { name(it) == "HMI" && u16(symtab + it * 16 + 14) != 0 } ?: return null
            val hmi = fo(u32(symtab + hmiSym * 16 + 4)) ?: return null
            if (u32(hmi) != 0x48574d54) return null
            val methods = fo(u32(hmi + 20)) ?: return null
            val open = u32(methods)
            if ((open and 1) == 0) return null
            fo(open and 1.inv())
        } catch (_: Exception) { null }
    }

    fun verdict(elf: ByteArray): Verdict = try {
        Analysis(elf).run()
    } catch (_: Exception) {
        Verdict.UNKNOWN
    }

    private const val MAX_FUNCTION = 0x180
    private const val HMI_REGISTER = 128
    private const val HMI_UNREGISTER = 132
    private const val HMI_LOCK = 136
    private const val HMI_UNLOCK = 140

    private class Analysis(private val d: ByteArray) {
        private val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        private fun u32(o: Int) = b.getInt(o)
        private fun u16(o: Int) = b.getShort(o).toInt() and 0xffff

        private val loads = ArrayList<IntArray>() // vaddr, file offset, file size
        private fun fileOffset(va: Int): Int? {
            for (l in loads) if (va >= l[0] && va < l[0] + l[2]) return va - l[0] + l[1]
            return null
        }

        private fun cString(o: Int): String {
            var e = o
            while (d[e] != 0.toByte()) e++
            return String(d, o, e - o, Charsets.ISO_8859_1)
        }

        fun run(): Verdict {
            // ELF32, little endian, EM_ARM
            if (d.size < 52 || d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte() || d[2] != 'L'.code.toByte() || d[3] != 'F'.code.toByte()) return Verdict.UNKNOWN
            if (d[4].toInt() != 1 || d[5].toInt() != 1 || u16(18) != 40) return Verdict.UNKNOWN
            val phoff = u32(0x1c)
            val phent = u16(0x2a)
            val phnum = u16(0x2c)
            var dynOff = -1
            var dynSize = 0
            for (i in 0 until phnum) {
                val p = phoff + i * phent
                val type = u32(p)
                if (type == 1) loads.add(intArrayOf(u32(p + 8), u32(p + 4), u32(p + 16)))
                if (type == 2) { dynOff = u32(p + 4); dynSize = u32(p + 16) }
            }
            if (dynOff < 0) return Verdict.UNKNOWN
            val tags = HashMap<Int, Int>()
            var i = 0
            while (i + 8 <= dynSize) {
                val tag = u32(dynOff + i)
                if (tag == 0) break
                if (!tags.containsKey(tag)) tags[tag] = u32(dynOff + i + 4)
                i += 8
            }
            val symtab = fileOffset(tags[6] ?: return Verdict.UNKNOWN) ?: return Verdict.UNKNOWN
            val strtab = fileOffset(tags[5] ?: return Verdict.UNKNOWN) ?: return Verdict.UNKNOWN
            val nsym = (strtab - symtab) / 16 // .dynstr follows .dynsym
            if (nsym !in 1..65536) return Verdict.UNKNOWN
            val names = Array(nsym) { s -> cString(strtab + u32(symtab + s * 16)) }
            val values = IntArray(nsym) { s -> u32(symtab + s * 16 + 4) }
            val defined = BooleanArray(nsym) { s -> u16(symtab + s * 16 + 14) != 0 }

            // the HAL module descriptor: tag 'HWMT', then gralloc_module_t's function pointers at +128
            val hmiSym = (0 until nsym).firstOrNull { names[it] == "HMI" && defined[it] } ?: return Verdict.UNKNOWN
            val hmi = fileOffset(values[hmiSym]) ?: return Verdict.UNKNOWN
            if (u32(hmi) != 0x48574d54) return Verdict.UNKNOWN
            val unregister = u32(hmi + HMI_UNREGISTER)
            if (unregister == 0 || fileOffset(unregister and 1.inv()) == null) return Verdict.UNKNOWN

            // PLT: GOT slot -> imported symbol
            val gotToName = HashMap<Int, String>()
            val jmprel = tags[23]?.let { fileOffset(it) }
            val relsz = tags[2] ?: 0
            if (jmprel != null) {
                var r = 0
                while (r + 8 <= relsz) {
                    val info = u32(jmprel + r + 4)
                    if ((info and 0xff) == 22 && (info ushr 8) in 0 until nsym) gotToName[u32(jmprel + r)] = names[info ushr 8]
                    r += 8
                }
            }

            val starts = java.util.TreeSet<Int>()
            for (off in intArrayOf(HMI_REGISTER, HMI_UNREGISTER, HMI_LOCK, HMI_UNLOCK)) {
                val fn = u32(hmi + off)
                if (fn != 0) starts.add(fn and 1.inv())
            }
            for (s in 0 until nsym) if (defined[s] && values[s] != 0) starts.add(values[s] and 1.inv())

            fun armImm(w: Int): Int = Integer.rotateRight(w and 0xff, ((w shr 8) and 0xf) * 2)
            /** Name of the import behind an ARM PLT stub (add ip,pc,#; add ip,ip,#; ldr pc,[ip,#]!), or null. */
            fun pltName(target: Int): String? {
                val o = fileOffset(target) ?: return null
                if (o + 12 > d.size) return null
                val w0 = u32(o)
                val w1 = u32(o + 4)
                val w2 = u32(o + 8)
                if ((w0 and 0xfffff000.toInt()) != 0xe28fc000.toInt() || (w1 and 0xfffff000.toInt()) != 0xe28cc000.toInt() ||
                    (w2 and 0xfffff000.toInt()) != 0xe5bcf000.toInt()) return null
                return gotToName[target + 8 + armImm(w0) + armImm(w1) + (w2 and 0xfff)]
            }

            /** Targets of the BL/BLX instructions in the function at [entry] (bit 0 set = Thumb), bounded by the next known function. */
            fun callsOf(entry: Int): List<Int> {
                val thumb = (entry and 1) != 0
                val a = entry and 1.inv()
                val o = fileOffset(a) ?: return emptyList()
                val end = minOf(starts.higher(a) ?: (a + MAX_FUNCTION), a + MAX_FUNCTION)
                val out = ArrayList<Int>()
                var p = 0
                if (thumb) {
                    while (a + p + 4 <= end && o + p + 4 <= d.size) {
                        val h1 = u16(o + p)
                        val h2 = u16(o + p + 2)
                        if ((h1 and 0xf800) == 0xf000 && (h2 and 0xc000) == 0xc000) {
                            val toThumb = (h2 and 0x1000) != 0
                            if (toThumb || (h2 and 1) == 0) {
                                val s = (h1 shr 10) and 1
                                val i1 = ((h2 shr 13) and 1 xor s) xor 1
                                val i2 = ((h2 shr 11) and 1 xor s) xor 1
                                var imm = (s shl 24) or (i1 shl 23) or (i2 shl 22) or ((h1 and 0x3ff) shl 12) or ((h2 and 0x7ff) shl 1)
                                if (s != 0) imm -= 1 shl 25
                                val base = if (toThumb) a + p + 4 else (a + p + 4) and 3.inv()
                                out.add((base + imm) or (if (toThumb) 1 else 0))
                                p += 4
                                continue
                            }
                        }
                        p += 2
                    }
                } else {
                    while (a + p + 4 <= end && o + p + 4 <= d.size) {
                        val w = u32(o + p)
                        var imm = w and 0xffffff
                        if (imm and 0x800000 != 0) imm -= 1 shl 24
                        if (((w shr 24) and 0xf) == 0xb && (w ushr 28) != 0xf) out.add(a + p + 8 + imm * 4)                     // BL
                        else if ((w ushr 25) == 0x7d) out.add((a + p + 8 + imm * 4 + (if ((w shr 24) and 1 != 0) 2 else 0)) or 1) // BLX imm
                        p += 4
                    }
                }
                return out
            }

            fun reachesGetpid(entry: Int, depth: Int, seen: MutableSet<Int>): Boolean {
                if (!seen.add(entry and 1.inv())) return false
                for (t in callsOf(entry)) {
                    val plt = pltName(t and 1.inv())
                    if (plt == "getpid") return true
                    if (plt == null && depth > 0 && fileOffset(t and 1.inv()) != null && reachesGetpid(t, depth - 1, seen)) return true
                }
                return false
            }
            return if (reachesGetpid(unregister, 2, HashSet())) Verdict.GUARDED else Verdict.UNGUARDED
        }
    }
}
