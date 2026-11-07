package app.aemu.core

import java.io.File

/**
 * The binder protocol version the guest's libbinder insists on. A kernel driver answers BINDER_VERSION with one number
 * and libbinder aborts when it is not the one it was built for: 32-bit firmware built with BINDER_IPC_32BIT wants 7,
 * most others want 8. Which one a firmware needs cannot be read reliably from its files, so it is taken from what the
 * guest itself reports when it refuses ("kernel driver version (8) differs from user space version (7)", or the
 * hw-ProcessState form "Binder driver protocol(8) does not match user space protocol(7)!"), remembered in
 * run/binder.protocol and handed to the binder daemons (env [ENV]) on the next start.
 */
object BinderProtocol {
    const val ENV = "AEMU_BINDER_PROTOCOL"
    /** read by the guest runner (run64.c in each native/guest64/<api> folder): protocol 7 turns on the 32-bit binder front end for qemu-arm programs */
    const val GUEST_ENV = "AEMU64_BINDER_PROTOCOL"
    private const val FILE = "binder.protocol"
    private val MISMATCH = listOf(
        Regex("""kernel driver version \((\d+)\) differs from user space version \((\d+)\)"""),
        Regex("""[Bb]inder driver protocol\s*\((\d+)\) does not match user space protocol\s*\((\d+)\)"""),
    )

    /** the (driver, wanted) pair from a service's output, or null when it holds no such complaint */
    fun parseMismatch(text: String): Pair<Int, Int>? {
        for (re in MISMATCH) {
            val m = re.findAll(text).lastOrNull() ?: continue
            val driver = m.groupValues[1].toIntOrNull() ?: continue
            val wanted = m.groupValues[2].toIntOrNull() ?: continue
            if (wanted != driver && wanted in 1..32) return driver to wanted
        }
        return null
    }

    /** the remembered version, or null while the daemons run with their own default */
    fun saved(bin: File): Int? = runCatching { File(bin, FILE).readText().trim().toIntOrNull() }.getOrNull()?.takeIf { it in 1..32 }

    fun save(bin: File, version: Int) { runCatching { File(bin, FILE).writeText(version.toString()) } }

    fun forget(bin: File) { runCatching { File(bin, FILE).delete() } }

    /** BINDER_WRITE_READ = _IOWR('b', 1, struct binder_write_read): 24-byte struct (32-bit binder, protocol 7) / 48-byte struct (64-bit binder, protocol 8) */
    private const val WRITE_READ_32 = 0xc0186201L
    private const val WRITE_READ_64 = 0xc0306201L

    /**
     * True when the firmware's libbinder / servicemanager of a 32-bit program was built for the 64-bit binder structs
     * (a 32-bit userland on a 64-bit kernel: Android Things, some set-top and IoT boards, Wear OS 9). The universal
     * engine's binder daemon only speaks protocol 7, so such a firmware's servicemanager dies at once ("kernel driver
     * version (7) differs from user space version (8)"); the native engine with qemu-arm speaks 8.
     * The BINDER_WRITE_READ ioctl number is what tells: it carries the size of struct binder_write_read. The constant
     * is looked for as a literal word and as the movw/movt pair the ARM and Thumb-2 compilers load it with. Nothing
     * found (or both sizes found) = unknown = false, and the version the guest itself complains about decides later.
     */
    fun firmwareWants8(root: File): Boolean = runCatching {
        var has64 = false; var has32 = false
        for (rel in listOf("system/bin/servicemanager", "system/lib/libbinder.so")) {
            val f = File(root, rel)
            if (!f.isFile || f.length() > 16L shl 20) continue
            val d = f.readBytes()
            if (d.size < 64 || d[0] != 0x7f.toByte() || d[4] != 1.toByte()) continue      // ELF32 only: a 64-bit program uses the native protocol anyway
            if (loadsWriteRead(d, WRITE_READ_64)) has64 = true
            if (loadsWriteRead(d, WRITE_READ_32)) has32 = true
        }
        has64 && !has32
    }.getOrDefault(false)

    private fun u16(d: ByteArray, o: Int) = (d[o].toInt() and 0xff) or ((d[o + 1].toInt() and 0xff) shl 8)
    private fun u32(d: ByteArray, o: Int) = u16(d, o).toLong() or (u16(d, o + 2).toLong() shl 16)

    private fun loadsWriteRead(d: ByteArray, v: Long): Boolean {
        val lo = (v and 0xffff).toInt(); val hi = ((v shr 16) and 0xffff).toInt()
        // 1. a literal word (ldr rX, =const), 4-aligned in practice
        var o = 0
        while (o + 4 <= d.size) { if (u32(d, o) == v) return true; o += 4 }
        // 2. ARM: movw rd, #lo ; movt rd, #hi
        fun armImm(base: Long, imm: Int, rd: Int) = base or ((imm and 0xf000).toLong() shl 4) or (rd.toLong() shl 12) or (imm and 0xfff).toLong()
        o = 0
        while (o + 8 <= d.size) {
            val w = u32(d, o)
            val rd = ((w shr 12) and 0xf).toInt()
            if (w == armImm(0xe3000000L, lo, rd) && u32(d, o + 4) == armImm(0xe3400000L, hi, rd)) return true
            o += 4
        }
        // 3. Thumb-2: movw/movt are two halfwords each, 2-aligned
        fun thumbPair(op: Int, imm: Int, rd: Int): Pair<Int, Int> {
            val i = (imm shr 11) and 1; val imm4 = (imm shr 12) and 0xf; val imm3 = (imm shr 8) and 7; val imm8 = imm and 0xff
            return (op or (i shl 10) or imm4) to ((imm3 shl 12) or (rd shl 8) or imm8)
        }
        o = 0
        while (o + 8 <= d.size) {
            val h1 = u16(d, o)
            if ((h1 and 0xfbf0) == 0xf240) {                 // movw
                val h2 = u16(d, o + 2); val rd = (h2 shr 8) and 0xf
                if (thumbPair(0xf240, lo, rd) == (h1 to h2) && thumbPair(0xf2c0, hi, rd) == (u16(d, o + 4) to u16(d, o + 6))) return true
            }
            o += 2
        }
        return false
    }

    /** the 64-bit binder protocol is what the firmware wants: seen in its files, or remembered from what it complained about */
    fun wants8(paths: VmPaths): Boolean = saved(paths.bin) == 8 || firmwareWants8(paths.root)
}
