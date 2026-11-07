package app.aemu.core

/**
 * Rewrites the `hw_module_t.id` of a prebuilt audio HAL (the exported `HMI` symbol).
 *
 * MediaTek 4.x AudioFlinger loads its primary HAL through `hw_get_module_by_class("libaudio", "primary")`, and
 * libhardware refuses a module whose id differs ("load: id=libaudio != hmi->id=audio"). The engine's stand-in HAL
 * declares the AOSP id "audio", so AudioFlinger could not open it, AudioPolicyService failed init_check, the
 * audio_flinger service never appeared and every ToneGenerator (SystemUI's PowerUI) died with "Unable to marshal
 * AudioFlinger" - a SystemUI crash loop, with the launcher dragged down with it.
 *
 * The id string cannot grow in place, so the new one is written into zero padding of a read-only PT_LOAD segment and
 * the (R_ARM_RELATIVE) pointer in the module struct is redirected there. The file size does not change.
 */
object HalModuleId {
    private const val ID_OFFSET_IN_MODULE = 8 // tag u32, version_major u16, version_minor u16, id char*

    /** Returns [elf] with the module id set to [newId], [elf] itself if it already has it, or null if it cannot be done. */
    fun retarget(elf: ByteArray, newId: String): ByteArray? = runCatching {
        fun u16(o: Int) = (elf[o].toInt() and 0xff) or ((elf[o + 1].toInt() and 0xff) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        if (elf.size < 52 || elf[0] != 0x7f.toByte() || elf[1] != 'E'.code.toByte() || elf[2] != 'L'.code.toByte() ||
            elf[3] != 'F'.code.toByte() || elf[4] != 1.toByte() || elf[5] != 1.toByte()) return null
        val phoff = u32(0x1c); val phentsize = u16(0x2a); val phnum = u16(0x2c)
        val shoff = u32(0x20); val shentsize = u16(0x2e); val shnum = u16(0x30)
        if (phoff <= 0 || shoff <= 0 || phoff + phnum * phentsize > elf.size || shoff + shnum * shentsize > elf.size) return null
        class Seg(val off: Int, val va: Int, val filesz: Int, val flags: Int)
        val segs = (0 until phnum).map { phoff + it * phentsize }.filter { u32(it) == 1 }
            .map { Seg(u32(it + 4), u32(it + 8), u32(it + 16), u32(it + 24)) }
        fun fileOffset(va: Int): Int? = segs.firstOrNull { va >= it.va && va < it.va + it.filesz }?.let { va - it.va + it.off }

        // exported HMI symbol -> its address
        var hmiVa = -1
        for (i in 0 until shnum) {
            val sh = shoff + i * shentsize
            if (u32(sh + 4) != 11) continue // SHT_DYNSYM
            val symOff = u32(sh + 16); val symSize = u32(sh + 20)
            val strOff = u32(shoff + u32(sh + 24) * shentsize + 16)
            var sym = symOff
            while (sym + 16 <= symOff + symSize && sym + 16 <= elf.size) {
                var e = strOff + u32(sym)
                val s = e
                while (e < elf.size && elf[e].toInt() != 0) e++
                if (String(elf, s, e - s, Charsets.ISO_8859_1) == "HMI") hmiVa = u32(sym + 4)
                sym += 16
            }
        }
        if (hmiVa < 0) return null
        val idPtrOff = fileOffset(hmiVa + ID_OFFSET_IN_MODULE) ?: return null
        val curOff = fileOffset(u32(idPtrOff)) ?: return null
        var e = curOff
        while (e < elf.size && elf[e].toInt() != 0) e++
        val want = newId.toByteArray(Charsets.ISO_8859_1)
        if (e - curOff == want.size && (want.indices).all { elf[curOff + it] == want[it] }) return elf

        val out = elf.copyOf()
        if (want.size <= e - curOff) { // fits over the old string
            want.copyInto(out, curOff); out[curOff + want.size] = 0
            return out
        }
        // zero padding inside a mergeable-string section (.rodata) of a read-only PT_LOAD: the run must follow a string,
        // whose terminator stays, so headers and tables are never touched
        for (i in 0 until shnum) {
            val sh = shoff + i * shentsize
            val flags = u32(sh + 8)
            if (flags and 0x2 == 0 || flags and 0x20 == 0 || flags and 0x1 != 0) continue // SHF_ALLOC | SHF_STRINGS, not WRITE
            val sOff = u32(sh + 16); val sEnd = minOf(sOff + u32(sh + 20), elf.size)
            val seg = segs.firstOrNull { sOff >= it.off && sOff < it.off + it.filesz && it.flags and 2 == 0 } ?: continue
            var p = sOff
            while (p < sEnd) {
                if (elf[p].toInt() != 0) { p++; continue }
                var q = p
                while (q < sEnd && elf[q].toInt() == 0) q++
                val start = p + 1
                val afterString = p > sOff && elf[p - 1].toInt() in 0x20..0x7e
                if (afterString && q - start >= want.size + 1) {
                    want.copyInto(out, start); out[start + want.size] = 0
                    val va = start - seg.off + seg.va
                    for (k in 0 until 4) out[idPtrOff + k] = (va ushr (8 * k)).toByte()
                    return out
                }
                p = q
            }
        }
        null
    }.getOrNull()
}
