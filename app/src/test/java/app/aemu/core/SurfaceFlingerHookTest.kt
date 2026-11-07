package app.aemu.core

import org.junit.Assert.*
import org.junit.Test

class SurfaceFlingerHookTest {
    private val context = intArrayOf(0x88, 0x47, 0xa8, 0x68, 0x83, 0x6e, 0x13, 0xb1, 0x31, 0x46, 0x22, 0x46, 0x98, 0x47)
        .map { it.toByte() }.toByteArray()

    private fun elf(code: ByteArray, at: Int = 0x100) = ByteArray(0x400).also {
        it[0] = 0x7f; it[1] = 'E'.code.toByte(); it[2] = 'L'.code.toByte(); it[3] = 'F'.code.toByte(); it[4] = 1
        code.copyInto(it, at)
    }

    @Test fun findsTheLoadOfTheHook() = assertEquals(listOf(0x104), ElfPatch.skipNullDeviceHookOffsets(elf(context)))
    @Test fun ignoresOtherCode() = assertTrue(ElfPatch.skipNullDeviceHookOffsets(elf(ByteArray(14))).isEmpty())
    @Test fun ambiguousMatchIsLeftAlone() {
        val d = elf(context); context.copyInto(d, 0x200)
        assertTrue(ElfPatch.skipNullDeviceHookOffsets(d).isEmpty())
    }
    @Test fun patchedCodeIsNotMatchedAgain() {
        val d = elf(context); d[0x104] = 0x00; d[0x105] = 0x23
        assertTrue(ElfPatch.skipNullDeviceHookOffsets(d).isEmpty())
    }
    @Test fun rejectsNonElf() = assertTrue(ElfPatch.skipNullDeviceHookOffsets(context).isEmpty())
}
