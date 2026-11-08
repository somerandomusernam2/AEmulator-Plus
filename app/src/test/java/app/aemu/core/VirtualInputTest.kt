package app.aemu.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualInputTest {
    private data class Ev(val type: Int, val code: Int, val value: Int)

    private fun events(a: ByteArray): List<Ev> {
        val b = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
        return (0 until a.size / 16).map { Ev(b.getShort(it * 16 + 8).toInt(), b.getShort(it * 16 + 10).toInt(), b.getInt(it * 16 + 12)) }
    }

    @Test fun touchDownReportsContactThenButtonThenSync() {
        val p = TouchpadProtocol()
        val ev = events(p.frame(listOf(TouchpadProtocol.Contact(1, 100, 200)), 5_000_000_000L))
        assertEquals(Ev(3, 57, 1), ev.first())                 // ABS_MT_TRACKING_ID
        assertTrue(Ev(3, 53, 100) in ev && Ev(3, 54, 200) in ev)
        assertTrue(Ev(1, 330, 1) in ev)                        // BTN_TOUCH down
        assertEquals(Ev(0, 0, 0), ev.last())                   // SYN_REPORT
    }

    @Test fun unchangedContactsSendNothing() {
        val p = TouchpadProtocol(); val c = listOf(TouchpadProtocol.Contact(0, 1, 2))
        p.frame(c, 1L)
        assertEquals(0, p.frame(c, 2L).size)
    }

    @Test fun releaseEndsTheTouchOnce() {
        val p = TouchpadProtocol()
        p.frame(listOf(TouchpadProtocol.Contact(0, 5, 5)), 1L)
        val ev = events(p.frame(emptyList(), 2L))
        assertTrue(Ev(1, 330, 0) in ev)
        assertTrue(Ev(0, 2, 0) in ev)                          // SYN_MT_REPORT
        assertEquals(0, p.frame(emptyList(), 3L).size)
    }

    @Test fun coordinatesAreClampedToThePad() {
        val ev = events(TouchpadProtocol().frame(listOf(TouchpadProtocol.Contact(40, 9999, -5)), 1L))
        assertTrue(Ev(3, 53, TouchpadProtocol.PAD_MAX) in ev)
        assertTrue(Ev(3, 54, 0) in ev)
        assertTrue(Ev(3, 57, 31) in ev)
    }

    @Test fun padScalesTheWholeGuestScreenToTheWholePad() {
        assertEquals(0, TouchpadProtocol.pad(0, 480))
        assertEquals(TouchpadProtocol.PAD_MAX, TouchpadProtocol.pad(479, 480))
        assertEquals(0, TouchpadProtocol.pad(5, 1))
    }

    @Test fun padButtonsAreKeyEvents() {
        val ev = events(TouchpadProtocol().button(TouchpadProtocol.BTN_LEFT, true, 1L))
        assertEquals(listOf(Ev(1, 272, 1), Ev(0, 0, 0)), ev)
    }

    @Test fun shiftLatchesUntilTheNextKeyIsReleased() {
        val log = mutableListOf<Pair<Int, Boolean>>()
        val l = KeyboardLatch { c, d -> log.add(c to d) }
        l.tapModifier(VirtualKeys.SHIFT)
        l.down(30); l.up(30)
        assertEquals(listOf(VirtualKeys.SHIFT to true, 30 to true, 30 to false, VirtualKeys.SHIFT to false), log)
        assertTrue(l.latchedModifiers.isEmpty())
    }

    @Test fun secondTapReleasesTheModifier() {
        val log = mutableListOf<Pair<Int, Boolean>>()
        val l = KeyboardLatch { c, d -> log.add(c to d) }
        l.tapModifier(VirtualKeys.CTRL); l.tapModifier(VirtualKeys.CTRL)
        assertEquals(listOf(VirtualKeys.CTRL to true, VirtualKeys.CTRL to false), log)
    }

    @Test fun releaseAllLetsGoOfEverything() {
        val held = mutableSetOf<Int>()
        val l = KeyboardLatch { c, d -> if (d) held.add(c) else held.remove(c) }
        l.tapModifier(VirtualKeys.ALT); l.down(16)
        l.releaseAll()
        assertTrue(held.isEmpty())
    }

    @Test fun layoutHasNoDuplicateOrZeroCodesAndEveryRowIsFilled() {
        val keys = VirtualKeyboardLayout.rows.flatten()
        assertTrue(keys.all { it.code in 1..255 })
        assertEquals(keys.size, keys.map { it.code }.toSet().size)
        VirtualKeyboardLayout.rows.forEach { assertTrue(it.sumOf { k -> k.weight.toDouble() } in 13.0..16.0) }
    }

    @Test fun keypadHasTwelveDigitKeysInPhoneOrder() {
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"), Keypad.digits.map { it.label })
        assertEquals(Keypad.digits.size, Keypad.digits.map { it.code }.toSet().size)
    }

    @Test fun mouseKeyboardWorksOnEveryEngineButNotInRecoveryOrWithoutFramework() {
        val arm = GuestArch.ARM
        assertTrue(VirtualInputPolicy.mouseKeyboardSupported(Engine.N64, arm, false, false))
        assertTrue(VirtualInputPolicy.mouseKeyboardSupported(Engine.N64, GuestArch.X86_64, false, false))
        assertTrue(VirtualInputPolicy.mouseKeyboardSupported(Engine.KK, arm, false, false))
        assertTrue(VirtualInputPolicy.mouseKeyboardSupported(Engine.KK, GuestArch.X86, false, false))
        assertFalse(VirtualInputPolicy.mouseKeyboardSupported(Engine.KK, arm, true, false))
        assertFalse(VirtualInputPolicy.mouseKeyboardSupported(Engine.GB, arm, false, true))
        assertFalse(VirtualInputPolicy.mouseKeyboardSupported(Engine.KK, arm, false, false, preRelease = true))
    }

    @Test fun settingsSurviveJson() {
        val s = VmSettings(mouseKeyboard = true, keypad = true)
        val back = VmSettings.fromJson(s.toJson())
        assertTrue(back.mouseKeyboard && back.keypad)
        assertFalse(VmSettings.fromJson(org.json.JSONObject()).mouseKeyboard)
    }

    @Test fun keypadKeysAreStable() = assertArrayEquals(intArrayOf(103, 108, 105, 106, 232),
        intArrayOf(Keypad.up.code, Keypad.down.code, Keypad.left.code, Keypad.right.code, Keypad.ok.code))
}
