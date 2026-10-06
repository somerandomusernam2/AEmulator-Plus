/* AEmulator Plus addition. GPL-3.0; see LICENSE. */
package app.aemu.core

import org.junit.Assert.*
import org.junit.Test

/** minui's gr_init as AOSP 4.4 / Wear recoveries compile it (Thumb-2), hand-assembled. */
class RecoveryImageTest {
    private fun code(vararg hw: Int): ByteArray = ByteArray(hw.size * 2).also { b ->
        hw.forEachIndexed { i, v -> b[i * 2] = v.toByte(); b[i * 2 + 1] = (v shr 8).toByte() }
    }
    private fun hw(d: ByteArray, i: Int) = (d[i * 2].toInt() and 0xFF) or ((d[i * 2 + 1].toInt() and 0xFF) shl 8)

    private val nop = 0xBF00
    private val movwGetFscreeninfo = intArrayOf(0xF244, 0x6102)          // movw r1, #0x4602

    /** [format] is stored with `movs r3,#format; strb r3,[r6,#20]`; [add] is the second page's `add.w` */
    private fun grInit(format: Int, strideShift: Int, add: IntArray = intArrayOf(0xEB0B, 0x0002)): ByteArray {
        val lsrs = { rm: Int, rd: Int -> 0x0800 or (strideShift shl 6) or (rm shl 3) or rd }
        return code(
            0x4620, *movwGetFscreeninfo,                       // mov r0,r4 ; movw r1,#0x4602
            0xF8D5, 0xE004, 0x6013, nop,
            lsrs(2, 1),                                         // lsrs r1,r2,#N   stride of the first page
            0x2300 or format, 0x7533,                           // movs r3,#format ; strb r3,[r6,#20]
            0xFB0E, 0xF201,                                     // mul r2,lr,r1
            0xEBB0, 0x0F42,                                     // cmp.w r0,r2,lsl #1
            0xD315,                                             // blo
            lsrs(1, 1),                                         // lsrs r1,r1,#N   stride of the second page
            *add,                                               // add.w r0,r11,r2
            0xE8BD, 0x8FF0,                                     // pop.w {r4-r11,pc}
        )
    }

    @Test fun bgraStrideAndSecondPageAreMatchedToTheHost() {
        val d = grInit(format = 5, strideShift = 2)
        val fix = RecoveryImage.patchFramebuffer(d)!!
        assertEquals(2, fix.format)                       // BGRA_8888 is what the Moto 360 recovery draws
        assertEquals(3, fix.patched)
        assertEquals(0x0851, hw(d, 7))                    // lsrs r1,r2,#1
        assertEquals(0x0849, hw(d, 15))                   // lsrs r1,r1,#1
        assertEquals(0xEB0B, hw(d, 16)); assertEquals(0x0042, hw(d, 17)) // add.w r0,r11,r2,lsl #1
    }

    @Test fun secondRunChangesNothing() {
        val d = grInit(format = 5, strideShift = 2)
        RecoveryImage.patchFramebuffer(d)
        val once = d.copyOf()
        val fix = RecoveryImage.patchFramebuffer(d)!!
        assertEquals(2, fix.format)
        assertEquals(0, fix.patched)
        assertArrayEquals(once, d)
    }

    @Test fun pageOffsetInTheFirstOperandIsSwapped() {
        val d = grInit(format = 5, strideShift = 2, add = intArrayOf(0xEB02, 0x000B)) // add.w r0,r2,r11
        assertEquals(3, RecoveryImage.patchFramebuffer(d)!!.patched)
        assertEquals(0xEB0B, hw(d, 16)); assertEquals(0x0042, hw(d, 17))                // add.w r0,r11,r2,lsl #1
    }

    @Test fun rgbxAndRgbaAreDrawnAsRgbxOnTheHost() {
        assertEquals(1, RecoveryImage.patchFramebuffer(grInit(2, 2))!!.format)
        assertEquals(1, RecoveryImage.patchFramebuffer(grInit(1, 2))!!.format)
    }

    @Test fun sixteenBitRecoveryKeepsItsCode() {
        val d = grInit(format = 4, strideShift = 1)
        val before = d.copyOf()
        val fix = RecoveryImage.patchFramebuffer(d)!!
        assertEquals(0, fix.format)
        assertEquals(0, fix.patched)
        assertArrayEquals(before, d)
    }

    @Test fun binaryWithoutFbSetupIsLeftAlone() {
        val d = code(0x4620, 0x4621, nop, nop, 0xE8BD, 0x8FF0)
        assertNull(RecoveryImage.patchFramebuffer(d))
    }
}
