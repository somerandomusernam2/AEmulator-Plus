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

    /**
     * Added for AEmulator Plus, 2026-10-30. The stand-in HAL (engines/kk/audio.primary.default.so) carries a hw_device_t
     * template: tag 'HWDT' (54 44 57 48) followed by the device API version, 0x0200 (AUDIO_DEVICE_API_VERSION_2_0).
     * A Jelly Bean 4.1 AudioFlinger takes only the 1.0 devices and refuses the HAL (logcat: "load_audio_interface wrong
     * audio hw device version 0200", "loadHwModule() error -22 loading module primary", "Failed to open primary
     * output"), which leaves the audio policy half initialised and AudioService.<init> throws in system_server.
     * Returns a copy of the HAL with that version field set to [version], or null when the template is not found
     * exactly once (the file is not this HAL, nothing is changed then).
     */
    fun withDeviceVersion(elf: ByteArray, version: Int): ByteArray? {
        // 1) the M7 stand-in HAL: hw_device_t template in .data, tag 'HWDT' (54 44 57 48) then the version word
        var at = -1
        for (i in 0..elf.size - 8) {
            if (elf[i] == 0x54.toByte() && elf[i + 1] == 0x44.toByte() && elf[i + 2] == 0x57.toByte() && elf[i + 3] == 0x48.toByte()) {
                if (at >= 0) return null
                at = i
            }
        }
        if (at >= 0) {
            val cur = (elf[at + 4].toInt() and 0xff) or ((elf[at + 5].toInt() and 0xff) shl 8) or
                ((elf[at + 6].toInt() and 0xff) shl 16) or ((elf[at + 7].toInt() and 0xff) shl 24)
            if (cur != 0x0200 && cur != version) return null
            val out = elf.copyOf()
            out[at + 4] = (version and 0xff).toByte(); out[at + 5] = ((version shr 8) and 0xff).toByte()
            out[at + 6] = ((version shr 16) and 0xff).toByte(); out[at + 7] = ((version shr 24) and 0xff).toByte()
            return out
        }
        // 2) the AOSP-layout HAL (audio.primary.aosp.so, Thumb-2): hal_open loads the version with
        //    "mov.w ip,#0x200" (4f f4 00 7c) and stores it next to the tag built by "movw r2,#0x4454" (44 f2 54 42).
        //    The shipped file has 0x200 (imm8 0x00); with the i:imm3 bits of this encoding 0x100 is imm8 0x80 and 0x300 is 0x40.
        val imm8 = when (version) { 0x0100 -> 0x80; 0x0200 -> 0x00; 0x0300 -> 0x40; else -> return null }
        val movVer = byteArrayOf(0x4f, 0xf4.toByte(), 0x00, 0x7c)
        val movwTag = byteArrayOf(0x44, 0xf2.toByte(), 0x54, 0x42)
        var site = -1
        for (i in 0..elf.size - 4) {
            if (elf[i] == movVer[0] && elf[i + 1] == movVer[1] && elf[i + 2] == movVer[2] && elf[i + 3] == movVer[3]) {
                var tagNear = false
                for (k in i + 4..minOf(i + 48, elf.size - 4)) {
                    if (elf[k] == movwTag[0] && elf[k + 1] == movwTag[1] && elf[k + 2] == movwTag[2] && elf[k + 3] == movwTag[3]) { tagNear = true; break }
                }
                if (tagNear) { if (site >= 0) return null; site = i }
            }
        }
        if (site < 0) return null
        val out = elf.copyOf()
        out[site + 2] = imm8.toByte()
        return out
    }

    /**
     * Added for AEmulator Plus, 2026-10-30. The hw_device_t version the firmware's own libaudioflinger demands from the
     * primary HAL, read from the code instead of guessed from the API level. In AudioFlinger::loadHwModule_l (the
     * load_audio_interface helper is inlined into it) the device version is loaded with "ldr rX,[dev,#4]" and compared with
     * "cmp.w rX,#imm" followed by beq/bne, e.g. the Samsung GT-I8260 4.1.2 build: "ldr r7,[r0,#4]; cmp.w r7,#0x100; beq".
     * Returns the immediate (0x0100, 0x0200 or 0x0300) or null when this is not a Thumb-2 build with that shape, or when
     * the compares found disagree. Null means: change nothing.
     */
    fun expectedDeviceVersion(elf: ByteArray): Int? = runCatching {
        fun u16(o: Int) = (elf[o].toInt() and 0xff) or ((elf[o + 1].toInt() and 0xff) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        if (elf.size < 52 || elf[0] != 0x7f.toByte() || elf[1] != 'E'.code.toByte() || elf[4] != 1.toByte() ||
            u16(0x12) != 40) return null
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
        fun thumbExpandImm(imm12: Int): Int {
            val b = imm12 and 0xff
            if ((imm12 shr 10) == 0) return when ((imm12 shr 8) and 3) {
                0 -> b
                1 -> (b shl 16) or b
                2 -> (b shl 24) or (b shl 8)
                else -> (b shl 24) or (b shl 16) or (b shl 8) or b
            }
            val v = 0x80 or (b and 0x7f)
            val r = (imm12 shr 7) and 31
            return (v ushr r) or (v shl (32 - r))
        }
        var start = -1; var size = 0
        for (i in 0 until shnum) {
            val sh = shoff + i * shentsize
            if (u32(sh + 4) != 11) continue // SHT_DYNSYM
            val symOff = u32(sh + 16); val symSize = u32(sh + 20)
            val strOff = u32(shoff + u32(sh + 24) * shentsize + 16)
            var sym = symOff
            while (sym + 16 <= symOff + symSize) {
                val nameOff = strOff + u32(sym)
                if ((elf[sym + 12].toInt() and 0xf) == 2 && u32(sym + 8) > 0) {
                    var e = nameOff
                    while (e < elf.size && elf[e].toInt() != 0) e++
                    if (String(elf, nameOff, e - nameOff, Charsets.ISO_8859_1).startsWith("_ZN7android12AudioFlinger14loadHwModule_l")) {
                        start = fileOffset(u32(sym + 4) and 1.inv()) ?: return null
                        size = u32(sym + 8)
                    }
                }
                sym += 16
            }
        }
        if (start < 0) return null
        val end = minOf(start + size, elf.size - 6)
        var found = -1
        var p = start
        while (p + 6 <= end) {
            val h = u16(p)
            if ((h and 0xfbf0) == 0xf1b0) { // cmp.w Rn,#imm (T2)
                val h2 = u16(p + 2)
                if ((h2 and 0x8f00) == 0x0f00 && (u16(p + 4) and 0xfe00) == 0xd000) { // followed by beq / bne
                    val rn = h and 15
                    val imm = thumbExpandImm((((h shr 10) and 1) shl 11) or (((h2 shr 12) and 7) shl 8) or (h2 and 0xff))
                    var loadsVersion = false
                    var j = maxOf(start, p - 16)
                    while (j < p) {
                        val g = u16(j)
                        if ((g and 0xf800) == 0x6800 && ((g shr 6) and 31) == 1 && (g and 7) == rn) loadsVersion = true // ldr rn,[rx,#4]
                        else if ((g and 0xfff0) == 0xf8d0 && j + 2 < p && (u16(j + 2) shr 12) == rn && (u16(j + 2) and 0xfff) == 4) loadsVersion = true
                        j += 2
                    }
                    if (loadsVersion && (imm shr 8) in 1..3 && (imm and 0xff) == 0) {
                        if (found >= 0 && found != imm) return null
                        found = imm
                    }
                }
            }
            p += 2
        }
        if (found > 0) found else null
    }.getOrNull()

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
