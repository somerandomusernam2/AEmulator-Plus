package app.aemu.core

/** Recognizes the verified stock ZR Thumb callback layout, not merely a brand. */
object AudioHalAbi {
    private val timestampPrefix = byteArrayOf(
        0x13, 0xb5.toByte(), 0x0c, 0x46, // push; mov r4,r1
        0x80.toByte(), 0x69,            // ldr r0,[r0,#0x18] (stream)
        0x83.toByte(), 0x6e,            // ldr r3,[r0,#0x68] (presentation)
        0x13, 0xb9.toByte(),            // cbnz r3
        0x6f, 0xf0.toByte(), 0x25, 0x00 // missing callback -> -ENOSYS
    )

    fun usesDirectTrackTail(elf: ByteArray): Boolean {
        if (elf.size < 52 || elf[0] != 0x7f.toByte() || elf[1] != 'E'.code.toByte() ||
            elf[2] != 'L'.code.toByte() || elf[3] != 'F'.code.toByte() ||
            elf[4] != 1.toByte() || elf[5] != 1.toByte() ||
            elf[18] != 40.toByte() || elf[19] != 0.toByte()) return false
        if (!String(elf, Charsets.ISO_8859_1).contains("AudioStreamOutSink")) return false
        return (0..elf.size - timestampPrefix.size).any { start ->
            timestampPrefix.indices.all { elf[start + it] == timestampPrefix[it] }
        }
    }

    /**
     * Device-struct offset that the firmware's libaudioflinger calls for open_output_stream, found by reading
     * AudioFlinger::openOutput (ARM Thumb: "ldr rX,[rDev,#slot]; blx rX"). Stock ICS is 104 (0x68); a vendor build
     * with extra slots in front of it differs. Returns null when it cannot be determined.
     */
    fun openOutputSlot(elf: ByteArray): Int? = runCatching {
        fun u16(o: Int) = (elf[o].toInt() and 0xff) or ((elf[o + 1].toInt() and 0xff) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        if (elf.size < 52 || elf[0] != 0x7f.toByte() || elf[1] != 'E'.code.toByte() || elf[4] != 1.toByte()) return null
        val phoff = u32(0x1c); val phentsize = u16(0x2a); val phnum = u16(0x2c)
        val shoff = u32(0x20); val shentsize = u16(0x2e); val shnum = u16(0x30)
        fun fileOffset(vaddr: Int): Int? {
            for (i in 0 until phnum) {
                val ph = phoff + i * phentsize
                if (u32(ph) != 1) continue // PT_LOAD
                val off = u32(ph + 4); val va = u32(ph + 8); val filesz = u32(ph + 16)
                if (vaddr >= va && vaddr < va + filesz) return vaddr - va + off
            }
            return null
        }
        for (i in 0 until shnum) {
            val sh = shoff + i * shentsize
            if (u32(sh + 4) != 11) continue // SHT_DYNSYM
            val symOff = u32(sh + 16); val symSize = u32(sh + 20)
            val strSh = shoff + u32(sh + 24) * shentsize
            val strOff = u32(strSh + 16)
            var sym = symOff
            while (sym + 16 <= symOff + symSize) {
                val nameOff = strOff + u32(sym)
                val value = u32(sym + 4); val size = u32(sym + 8)
                val isFunc = (elf[sym + 12].toInt() and 0xf) == 2
                if (isFunc && size > 0) {
                    var e = nameOff
                    while (e < elf.size && elf[e].toInt() != 0) e++
                    val name = String(elf, nameOff, e - nameOff, Charsets.ISO_8859_1)
                    if (name.startsWith("_ZN7android12AudioFlinger10openOutput")) {
                        val start = fileOffset(value and 1.inv()) ?: return null
                        var best = -1
                        var p = start
                        val end = minOf(start + size, elf.size - 4)
                        while (p < end) {
                            val h = u16(p)
                            if ((h and 0xff87) == 0x4780) { // blx rm
                                val rm = (h shr 3) and 15
                                var j = maxOf(start, p - 14)
                                while (j < p) {
                                    val g = u16(j)
                                    var imm = -1
                                    if ((g shr 11) == 0b01101 && (g and 7) == rm) imm = ((g shr 6) and 31) * 4
                                    else if ((g and 0xfff0) == 0xf8d0 && j + 2 < p && (u16(j + 2) shr 12) == rm) imm = u16(j + 2) and 0xfff
                                    if (imm in 96..160 && imm > best) best = imm
                                    j += 2
                                }
                            }
                            p += 2
                        }
                        return if (best > 0) best else null
                    }
                }
                sym += 16
            }
        }
        null
    }.getOrNull()
}
