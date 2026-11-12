package app.aemu.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class QcomHandleProbeTest {
    private fun elf(vararg words: Int): ByteArray {
        val d = ByteArray(0x100 + words.size * 4)
        d[0] = 0x7f; d[1] = 'E'.code.toByte(); d[2] = 'L'.code.toByte(); d[3] = 'F'.code.toByte(); d[4] = 2; d[5] = 1
        ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN).apply { putShort(18, 183); words.forEachIndexed { i, w -> putInt(0x100 + i * 4, w) } }
        return d
    }
    private val ldrHandle = 0xf9403008.toInt()      // ldr x8, [x0, #0x60]
    private val cbz = 0xb40000a8.toInt()            // cbz x8, ...
    private val ldrSlotX = 0xf9402508.toInt()       // ldr x8, [x8, #0x48]
    private val ldrb = 0x39400109                    // ldrb w9, [x8]
    private val ldrSlotW = 0xb9404909.toInt()       // ldr w9, [x8, #0x48]

    @Test fun pointerThatIsFollowed() = assertEquals(QcomHandleProbe.Layout.METADATA_POINTER, QcomHandleProbe.classify(elf(ldrHandle, cbz, ldrSlotX, cbz, ldrb)))
    @Test fun plainValue() = assertEquals(QcomHandleProbe.Layout.VALUE, QcomHandleProbe.classify(elf(ldrHandle, cbz, ldrSlotW)))
    @Test fun pointerNeverFollowedIsUnknown() = assertEquals(QcomHandleProbe.Layout.UNKNOWN, QcomHandleProbe.classify(elf(ldrHandle, cbz, ldrSlotX)))
    @Test fun noSequence() = assertEquals(QcomHandleProbe.Layout.UNKNOWN, QcomHandleProbe.classify(elf(0, 0, 0, 0)))
    @Test fun notArm64() = assertEquals(QcomHandleProbe.Layout.UNKNOWN, QcomHandleProbe.classify(ByteArray(300)))
}
