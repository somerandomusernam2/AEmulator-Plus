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

    private fun hexBytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Samsung ICS stock recovery ev_init: `cmp r0,#0; blt; ldr r3,[sp,#0xc]; tst.w r3,#6; bne`, EVIOCGBIT(0) on the event FIFO. */
    @Test fun samsungIcsEvdevProbeIsNeutralised() {
        val d = hexBytes("23f0fffc" + "002803db039b13f0060f03d1" + "2046dff7")
        val mask = RecoveryImage.patchEvdevProbes(d)
        assertEquals(1 shl 2, mask)                       // not the LG touch-protocol-B bit
        assertEquals("23f0fffc" + "0028" + "00bf" + "039b13f0060f" + "03e0" + "2046dff7", d.joinToString("") { "%02x".format(it) })
    }

    /** CWM 6.0.3.7 (CM 10.1, Prestigio PMP7079D3G_QUAD) ev_init: `cmp r0,#0; blt; ldr r3,[sp,#0xc]; tst.w r3,#0xe; bne`, as found at 0x6e266. */
    @Test fun cwmCyanogenModEvdevProbeIsNeutralised() {
        val d = hexBytes("31f0cafe" + "002803db039b13f00e0f03d1" + "204623f0e8ef")
        val mask = RecoveryImage.patchEvdevProbes(d)
        assertEquals(1 shl 3, mask)                       // neither the LG touch-protocol-B bit nor the Samsung one
        assertEquals("31f0cafe" + "0028" + "00bf" + "039b13f00e0f" + "03e0" + "204623f0e8ef", d.joinToString("") { "%02x".format(it) })
        val once = d.copyOf()
        assertEquals(1 shl 3, RecoveryImage.patchEvdevProbes(d))   // a second run recognises its own work
        assertArrayEquals(once, d)
    }

    @Test fun evdevProbePatchIsIdempotent() {
        val d = hexBytes("002803db039b13f0060f03d1")
        val first = RecoveryImage.patchEvdevProbes(d)
        val once = d.copyOf()
        assertEquals(first, RecoveryImage.patchEvdevProbes(d))
        assertArrayEquals(once, d)
    }

    @Test fun binaryWithoutEvdevProbesIsLeftAlone() {
        val d = code(0x4620, 0x4621, nop, nop)
        val before = d.copyOf()
        assertEquals(0, RecoveryImage.patchEvdevProbes(d))
        assertArrayEquals(before, d)
    }

    // ---- line_length hook in a recovery whose code after the FBIOGET_FSCREENINFO check is 16-bit (Samsung ICS) ----

    private fun w16(d: ByteArray, o: Int, v: Int) { d[o] = v.toByte(); d[o + 1] = (v shr 8).toByte() }
    private fun w32(d: ByteArray, o: Int, v: Int) { w16(d, o, v and 0xFFFF); w16(d, o + 2, v ushr 16) }

    /** a one-segment ELF32/ARM executable, [hw] at 0x80, zero padding to the end of its page (room for the hook's cave) */
    private fun elf(vararg hw: Int): ByteArray {
        val d = ByteArray(0x1000)
        d[0] = 0x7F; d[1] = 'E'.code.toByte(); d[2] = 'L'.code.toByte(); d[3] = 'F'.code.toByte(); d[4] = 1; d[5] = 1
        w16(d, 18, 40); w32(d, 28, 52); w16(d, 42, 32); w16(d, 44, 1)
        val fs = 0x80 + hw.size * 2
        w32(d, 52, 1); w32(d, 56, 0); w32(d, 60, 0x8000); w32(d, 64, 0x8000); w32(d, 68, fs); w32(d, 72, fs); w32(d, 76, 5)
        hw.forEachIndexed { i, v -> w16(d, 0x80 + i * 2, v) }
        return d
    }

    /** gr_init as Samsung's ICS recovery has it: movw #0x4602; mov r2,r8; bl; cmp r0,#0; bge ok; ok: <[afterCheck]> ... */
    private fun samsungGrInit(vararg afterCheck: Int) = elf(
        0xF244, 0x6102,              // movw r1,#0x4602
        0x4642,                      // mov r2,r8        (r8 = &fi)
        0xF000, 0xF800,              // bl ioctl
        0x2800, 0xDA02,              // cmp r0,#0 ; bge ok
        nop, nop, nop,               // error path
        *afterCheck,                 // ok:
        0x2302, 0x7533,              // movs r3,#2 ; strb r3,[r6,#20]   (RGBX_8888)
        0x0890,                      // lsrs r0,r2,#2   stride
        0xE8BD, 0x8FF0,              // pop.w {r4-r11,pc}
    )

    private val hook = 0x80 + 10 * 2

    @Test fun lineLengthHookMovesTwoSixteenBitInstructions() {
        val d = samsungGrInit(0x9500, 0x2203)                            // str r5,[sp] ; movs r2,#3
        val fix = RecoveryImage.patchFramebuffer(d)!!
        assertEquals(1, fix.format)
        assertEquals(3, fix.patched)
        assertEquals(0xF000, hw(d, hook / 2) and 0xF800)                  // the first moved instruction is now a B.W
        val start = (0x80 + 2 * 17 + 3) and 3.inv()                        // the cave: right behind the old end of the segment (17 halfwords)
        assertEquals(0xF84D, hw(d, start / 2))                            // push {r0}
        assertEquals(0x9500, hw(d, (start + 20) / 2))                     // the moved instructions
        assertEquals(0x2203, hw(d, (start + 22) / 2))
        assertEquals(0x0890, hw(d, (hook + 4 + 4) / 2))                   // the stride is NOT halved when the hook is used
    }

    @Test fun lineLengthHookMovesA16And32BitPairWithANop() {
        val d = samsungGrInit(0x9500, 0xF8C6, 0x5020)                      // str r5,[sp] ; str.w r5,[r6,#0x20]
        assertEquals(3, RecoveryImage.patchFramebuffer(d)!!.patched)
        assertEquals(0xF000, hw(d, hook / 2) and 0xF800)
        assertEquals(0xBF00, hw(d, (hook + 4) / 2))                       // the tail of the moved 32-bit instruction
        val start = (0x80 + 2 * 18 + 3) and 3.inv()
        assertEquals(0x9500, hw(d, (start + 20) / 2))
        assertEquals(0xF8C6, hw(d, (start + 22) / 2)); assertEquals(0x5020, hw(d, (start + 24) / 2))
    }

    @Test fun lineLengthHookIsInstalledOnlyOnce() {
        val d = samsungGrInit(0x9500, 0x2203)
        RecoveryImage.patchFramebuffer(d)
        val once = d.copyOf()
        val fix = RecoveryImage.patchFramebuffer(d)!!
        assertEquals(0, fix.patched)
        assertArrayEquals(once, d)
    }

    @Test fun pcRelativeInstructionIsNotMovedSoTheStrideFallbackIsUsed() {
        val d = samsungGrInit(0x4C01, 0x2203)                            // ldr r4,[pc,#4] cannot be moved
        val fix = RecoveryImage.patchFramebuffer(d)!!
        assertEquals(1, fix.patched)                                      // lsrs r0,r2,#2 -> #1 only
        assertEquals(0x0850, hw(d, (hook + 4 + 4) / 2))
    }
}
