package app.aemu.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MspaceHooksTest {
    /** A minimal ELF32 ARM shared object: one UND dynamic symbol per name, a GLOB_DAT relocation for each into a GOT in the RW segment. */
    private fun elf(names: List<String>, machine: Int = 40): ByteArray {
        val d = ByteArray(0x4e0)
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        d[0] = 0x7f; d[1] = 'E'.code.toByte(); d[2] = 'L'.code.toByte(); d[3] = 'F'.code.toByte(); d[4] = 1; d[5] = 1; d[6] = 1
        b.putShort(16, 3); b.putShort(18, machine.toShort()); b.putInt(20, 1)
        b.putInt(28, 52); b.putInt(32, 0x440)
        b.putShort(40, 52); b.putShort(42, 32); b.putShort(44, 2); b.putShort(46, 40); b.putShort(48, 4)
        // PT_LOAD r-x: whole file below 0x400; PT_LOAD rw-: 0x400.., mapped at 0x1400
        fun phdr(at: Int, off: Int, va: Int, size: Int, flags: Int) {
            b.putInt(at, 1); b.putInt(at + 4, off); b.putInt(at + 8, va); b.putInt(at + 12, va)
            b.putInt(at + 16, size); b.putInt(at + 20, size); b.putInt(at + 24, flags); b.putInt(at + 28, 0x1000)
        }
        phdr(52, 0, 0, 0x400, 5); phdr(84, 0x400, 0x1400, 0x40, 6)
        // .dynstr at 0x240, .dynsym at 0x200 (entry 0 is all zeros), .rel.dyn at 0x2c0
        var str = 0x241
        for ((i, n) in names.withIndex()) {
            val sym = 0x200 + 16 * (i + 1)
            b.putInt(sym, str - 0x240); b.putShort(sym + 14, 0)          // st_name, st_shndx = UND
            n.forEachIndexed { k, c -> d[str + k] = c.code.toByte() }
            str += n.length + 1
            b.putInt(0x2c0 + 8 * i, 0x1400 + 4 * i); b.putInt(0x2c0 + 8 * i + 4, ((i + 1) shl 8) or 21)
            b.putInt(0x400 + 4 * i, 0)
        }
        fun shdr(at: Int, type: Int, off: Int, size: Int, link: Int) {
            b.putInt(at + 4, type); b.putInt(at + 16, off); b.putInt(at + 20, size); b.putInt(at + 24, link)
        }
        shdr(0x440 + 40, 11, 0x200, 16 * (names.size + 1), 2)
        shdr(0x440 + 80, 3, 0x240, str - 0x240, 0)
        shdr(0x440 + 120, 9, 0x2c0, 8 * names.size, 1)
        return d
    }

    private val hooks = listOf("_ZN7android18mspace_malloc_statE", "_ZN7android16mspace_free_statE")

    @Test fun hookSlotsPointAtAZeroWord() {
        val d = elf(hooks)
        val out = MspaceHooks.patched(d)!!
        val b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0..1) {
            assertEquals(23, b.getInt(0x2c0 + 8 * i + 4))             // R_ARM_RELATIVE, symbol 0
            val target = b.getInt(0x400 + 4 * i)                      // the addend now sits in the GOT slot
            assertEquals(0x200, target)                               // first zero run in the read-only segment
            assertTrue((0 until 8).all { out[target + it] == 0.toByte() })
        }
    }

    @Test fun onlyRelocationsAndSlotsChange() {
        val d = elf(hooks)
        val out = MspaceHooks.patched(d)!!
        // only the two r_info words and the two GOT words differ
        val changed = out.indices.filter { out[it] != d[it] }
        assertTrue(changed.all { it in 0x2c0 until 0x2d0 || it in 0x400 until 0x408 })
    }

    @Test fun secondPassHasNothingToDo() = assertNull(MspaceHooks.patched(MspaceHooks.patched(elf(hooks))!!))

    @Test fun otherImportsAreLeftAlone() = assertNull(MspaceHooks.patched(elf(listOf("malloc", "free"))))

    @Test fun notArmIsLeftAlone() = assertNull(MspaceHooks.patched(elf(hooks, machine = 3)))

    @Test fun garbageIsLeftAlone() {
        assertNull(MspaceHooks.patched(ByteArray(0)))
        assertNull(MspaceHooks.patched(ByteArray(4096)))
    }
}
