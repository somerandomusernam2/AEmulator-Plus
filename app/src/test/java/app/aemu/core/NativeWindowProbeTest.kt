package app.aemu.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Firmware with a longer android_native_base_t (Sprd MocorDroid): the probe reads queueBuffer's buffer->handle displacement, and
 * the shipped GLES bridge gets exactly its three window-slot calls moved. The libui fixtures are tiny hand-made ELF files (own
 * Thumb-2 code: `mov r6,r1; ldr.w sl,[r6,#imm]; bx lr` under the real symbol name), not vendor binaries.
 */
class NativeWindowProbeTest {
    private val res = File("src/test/resources")
    private val bridge = File("src/main/assets/engines/kk/libGLES.so").readBytes()
    private fun words(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

    @Test fun mocordroidLibuiHasHandleAt0x40() {
        val libui = File(res, "libui-queuebuffer-mocordroid.so").readBytes()
        assertEquals(0x40, NativeWindowProbe.bufferHandleOffset(libui))
        assertEquals(4, NativeWindowProbe.windowShift(libui))
    }

    @Test fun aospLayoutIsNotShifted() {
        val libui = File(res, "libui-queuebuffer-aosp.so").readBytes()
        assertEquals(0x3c, NativeWindowProbe.bufferHandleOffset(libui))
        assertEquals(0, NativeWindowProbe.windowShift(libui))
    }

    @Test fun unreadableInputIsUnknown() {
        assertNull(NativeWindowProbe.windowShift(ByteArray(0)))
        assertNull(NativeWindowProbe.windowShift("not an elf at all".toByteArray()))
        val libui = File(res, "libui-queuebuffer-mocordroid.so").readBytes()
        assertNull(NativeWindowProbe.windowShift(libui.copyOf(0x40)))                       // truncated
        assertNull(NativeWindowProbe.windowShift(libui.copyOf().also { it[18] = 3 }))       // EM_386
        // a displacement that is neither layout: not guessed
        val odd = libui.copyOf().also { it[0x140 + 4] = 0x48 }
        assertEquals(0x48, NativeWindowProbe.bufferHandleOffset(odd))
        assertNull(NativeWindowProbe.windowShift(odd))
    }

    @Test fun bridgeGetsExactlyThreeSlotsMoved() {
        val patched = NativeWindowProbe.withWindowShift(bridge, 4)!!
        assertEquals(bridge.size, patched.size)
        val a = words(bridge); val b = words(patched)
        val changed = (0 until bridge.size / 4).filter { a.getInt(it * 4) != b.getInt(it * 4) }.map { it * 4 }
        assertEquals(listOf(0xd33c, 0xd468, 0xd7d4), changed)                               // query, perform (create), perform (destroy)
        for (o in changed) {
            assertEquals((a.getInt(o) and 0xfff) + 4, b.getInt(o) and 0xfff)
            assertEquals(a.getInt(o) and 0xfff.inv(), b.getInt(o) and 0xfff.inv())
        }
        assertEquals(0x54, a.getInt(0xd33c) and 0xfff)
        assertEquals(0x58, b.getInt(0xd33c) and 0xfff)
    }

    @Test fun noShiftLeavesTheBridgeAlone() {
        assertSame(bridge, NativeWindowProbe.withWindowShift(bridge, 0))
    }

    @Test fun anotherBuildIsRefusedNotGuessed() {
        val patched = NativeWindowProbe.withWindowShift(bridge, 4)!!
        assertNull(NativeWindowProbe.withWindowShift(patched, 4))                           // already patched: not the 3 expected sites
        assertNull(NativeWindowProbe.withWindowShift(ByteArray(10), 4))
        assertNull(NativeWindowProbe.withWindowShift(File("src/main/assets/engines/kk/libGLES_split.so").readBytes(), 4))
    }
}
