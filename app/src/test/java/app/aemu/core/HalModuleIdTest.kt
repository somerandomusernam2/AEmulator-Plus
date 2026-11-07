package app.aemu.core

import org.junit.Assert.*
import org.junit.Test

class HalModuleIdTest {
    private fun put32(b: ByteArray, o: Int, v: Int) { for (i in 0 until 4) b[o + i] = (v ushr (8 * i)).toByte() }
    private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte() }

    /** Minimal ARM ELF32: one read-only PT_LOAD (identity mapped), a .dynsym/.dynstr with "HMI" at 0x180, id -> "audio". */
    private fun fixture(padding: Int = 24): ByteArray {
        val b = ByteArray(0x400)
        byteArrayOf(0x7f, 69, 76, 70, 1, 1, 1).copyInto(b)
        put16(b, 0x12, 40)
        put32(b, 0x1c, 52); put16(b, 0x2a, 32); put16(b, 0x2c, 1)   // one program header
        put32(b, 0x20, 0x300); put16(b, 0x2e, 40); put16(b, 0x30, 4) // four sections
        // PT_LOAD, offset 0, vaddr 0, filesz 0x400, flags R (4)
        put32(b, 52, 1); put32(b, 52 + 4, 0); put32(b, 52 + 8, 0); put32(b, 52 + 16, 0x400); put32(b, 52 + 24, 4)
        // .dynstr at 0x100: "\0HMI\0"
        "\u0000HMI\u0000".toByteArray().copyInto(b, 0x100)
        // .dynsym at 0x120: null symbol + HMI (name 1, value 0x180)
        put32(b, 0x130, 1); put32(b, 0x134, 0x180)
        // strings: "audio" at 0x200, then `padding` zero bytes, then another string
        "audio".toByteArray().copyInto(b, 0x200)
        val after = 0x206 + padding
        "tail".toByteArray().copyInto(b, after)
        // module struct at 0x180: tag, version, id -> 0x200
        put32(b, 0x180, 0x48574d54); put32(b, 0x184, 1); put32(b, 0x188, 0x200)
        // section headers at 0x300: [0] null, [1] dynsym, [2] dynstr
        put32(b, 0x300 + 40 + 4, 11); put32(b, 0x300 + 40 + 16, 0x120); put32(b, 0x300 + 40 + 20, 32); put32(b, 0x300 + 40 + 24, 2)
        put32(b, 0x300 + 80 + 4, 3); put32(b, 0x300 + 80 + 16, 0x100)
        // [3] .rodata: SHF_ALLOC|SHF_MERGE|SHF_STRINGS over the strings only
        put32(b, 0x300 + 120 + 4, 1); put32(b, 0x300 + 120 + 8, 0x32)
        put32(b, 0x300 + 120 + 16, 0x200); put32(b, 0x300 + 120 + 20, after + 5 - 0x200)
        return b
    }
    private fun idOf(b: ByteArray): String {
        val p = (0 until 4).sumOf { (b[0x188 + it].toInt() and 0xff) shl (8 * it) }
        var e = p; while (b[e].toInt() != 0) e++
        return String(b, p, e - p)
    }

    @Test fun longerIdGoesIntoPaddingAndPointerFollows() {
        val src = fixture()
        val out = HalModuleId.retarget(src, "libaudio")!!
        assertEquals("libaudio", idOf(out))
        assertEquals(src.size, out.size)
        assertEquals("tail", String(out, 0x206 + 24, 4))                 // neighbouring string untouched
        assertEquals("audio", String(out, 0x200, 5))                     // old string kept
    }
    @Test fun sameIdIsANoOp() { val f = fixture(); assertSame(f, HalModuleId.retarget(f, "audio")) }
    @Test fun shorterIdIsWrittenInPlace() {
        val out = HalModuleId.retarget(fixture(), "aud")!!
        assertEquals("aud", idOf(out))
        assertEquals(0x200, (0 until 4).sumOf { (out[0x188 + it].toInt() and 0xff) shl (8 * it) })
    }
    @Test fun noRoomMeansNull() = assertNull(HalModuleId.retarget(fixture(padding = 4), "libaudio"))
    @Test fun notAnElfMeansNull() {
        assertNull(HalModuleId.retarget(ByteArray(10), "libaudio"))
        assertNull(HalModuleId.retarget(fixture().also { it[0] = 0 }, "libaudio"))
    }
}
