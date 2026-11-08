package app.aemu.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure logic of the two optional input methods (VM settings › Controls):
 *  - "Mouse and keyboard": an emulated touchpad + keyboard replace the touch screen ([TouchpadProtocol], [KeyboardLatch]);
 *  - "Keypad": feature-phone keys sent through the ordinary key device ([Keypad]); never disables touch.
 */
object VirtualKeys {
    const val ESC = 1; const val BACKSPACE = 14; const val TAB = 15; const val ENTER = 28
    const val CTRL = 29; const val SHIFT = 42; const val ALT = 56; const val SPACE = 57; const val CAPS = 58
    const val HOME = 102; const val UP = 103; const val PAGE_UP = 104; const val LEFT = 105; const val RIGHT = 106
    const val END = 107; const val DOWN = 108; const val PAGE_DOWN = 109; const val INSERT = 110; const val DELETE = 111
    const val KPASTERISK = 55; const val SEND = 231; const val ENDCALL = 107
    /** Linux KEY_STAR / KEY_NUMERIC_POUND style codes used by Generic.kl of 5.0+; older layouts map them to nothing, harmless. */
    const val STAR = 522; const val POUND = 523
    const val SELECT = 232
}

/** One key of the floating keyboard. [shifted] is the label shown while Shift is latched. */
data class VKey(val label: String, val code: Int, val weight: Float = 1f, val shifted: String? = null) {
    val isModifier: Boolean get() = code == VirtualKeys.SHIFT || code == VirtualKeys.CTRL || code == VirtualKeys.ALT
}

object VirtualKeyboardLayout {
    private fun k(label: String, code: Int, w: Float = 1f, shifted: String? = null) = VKey(label, code, w, shifted)
    private val numbers = "1234567890"
    private val numShift = "!@#$%^&*()"
    val rows: List<List<VKey>> = listOf(
        listOf(k("Esc", 1, 1.4f)) + numbers.mapIndexed { i, c -> k(c.toString(), if (i == 9) 11 else i + 2, 1f, numShift[i].toString()) } +
            listOf(k("-", 12, 1f, "_"), k("=", 13, 1f, "+"), k("⌫", VirtualKeys.BACKSPACE, 1.5f)),
        listOf(k("Tab", VirtualKeys.TAB, 1.4f)) + "qwertyuiop".mapIndexed { i, c -> k(c.toString(), 16 + i, 1f, c.uppercase()) } +
            listOf(k("[", 26, 1f, "{"), k("]", 27, 1f, "}"), k("\\", 43, 1f, "|")),
        listOf(k("Caps", VirtualKeys.CAPS, 1.7f)) + "asdfghjkl".mapIndexed { i, c -> k(c.toString(), 30 + i, 1f, c.uppercase()) } +
            listOf(k(";", 39, 1f, ":"), k("'", 40, 1f, "\""), k("⏎", VirtualKeys.ENTER, 1.9f)),
        listOf(k("⇧", VirtualKeys.SHIFT, 2.2f)) + "zxcvbnm".mapIndexed { i, c -> k(c.toString(), 44 + i, 1f, c.uppercase()) } +
            listOf(k(",", 51, 1f, "<"), k(".", 52, 1f, ">"), k("/", 53, 1f, "?"), k("↑", VirtualKeys.UP, 1f), k("`", 41, 1f, "~")),
        listOf(k("Ctrl", VirtualKeys.CTRL, 1.6f), k("Alt", VirtualKeys.ALT, 1.4f), k("Home", VirtualKeys.HOME, 1.4f),
            k("␣", VirtualKeys.SPACE, 5f), k("Del", VirtualKeys.DELETE, 1.4f), k("←", VirtualKeys.LEFT), k("↓", VirtualKeys.DOWN), k("→", VirtualKeys.RIGHT)),
    )
}

/**
 * Shift/Ctrl/Alt on a touch keyboard: a tap latches the modifier (held down in the guest) until the next ordinary key
 * has been released, then it is let go. A second tap before that releases it. Caps Lock is an ordinary key.
 */
internal class KeyboardLatch(private val send: (Int, Boolean) -> Unit) {
    private val latched = linkedSetOf<Int>()
    val latchedModifiers: Set<Int> get() = latched
    private val pressed = mutableSetOf<Int>()

    fun tapModifier(code: Int) {
        if (latched.remove(code)) send(code, false) else { latched.add(code); send(code, true) }
    }

    fun down(code: Int) { if (pressed.add(code)) send(code, true) }

    fun up(code: Int) {
        if (!pressed.remove(code)) return
        send(code, false)
        if (latched.isNotEmpty()) { latched.toList().forEach { send(it, false) }; latched.clear() }
    }

    /** Window closed, app backgrounded: nothing may stay held in the guest. */
    fun releaseAll() {
        pressed.toList().forEach { send(it, false) }; pressed.clear()
        latched.toList().forEach { send(it, false) }; latched.clear()
    }
}

/** Buttons of the feature-phone keypad window. */
data class PadKey(val label: String, val code: Int, val sub: String = "")

object Keypad {
    val softLeft = PadKey("◀ Menu", InputService.KEY_MENU)
    val softRight = PadKey("Back ▶", InputService.KEY_BACK)
    val call = PadKey("Call", VirtualKeys.SEND)
    val end = PadKey("End", VirtualKeys.ENDCALL)
    val up = PadKey("▲", VirtualKeys.UP)
    val down = PadKey("▼", VirtualKeys.DOWN)
    val left = PadKey("◀", VirtualKeys.LEFT)
    val right = PadKey("▶", VirtualKeys.RIGHT)
    val ok = PadKey("OK", VirtualKeys.SELECT)
    /** 1-9, then * 0 #, as on a phone keypad. */
    val digits: List<PadKey> = listOf(
        PadKey("1", 2), PadKey("2", 3, "abc"), PadKey("3", 4, "def"),
        PadKey("4", 5, "ghi"), PadKey("5", 6, "jkl"), PadKey("6", 7, "mno"),
        PadKey("7", 8, "pqrs"), PadKey("8", 9, "tuv"), PadKey("9", 10, "wxyz"),
        PadKey("*", VirtualKeys.STAR), PadKey("0", 11, "+"), PadKey("#", VirtualKeys.POUND),
    )
}

/**
 * Touchpad frames (multi-touch protocol A, the one every Android from 3.2 reads). The device is a touchpad, not a
 * screen: the guest's own gesture code turns taps into clicks and two fingers into scrolling. Coordinates are
 * normalised to the pad range, so the whole guest screen is the pad.
 */
class TouchpadProtocol {
    data class Contact(val id: Int, val x: Int, val y: Int)

    private var down = false
    private var last: List<Contact> = emptyList()

    /** Events for the new contact set, or an empty array when nothing changed. */
    fun frame(now: List<Contact>, nanos: Long): ByteArray {
        if (now.isEmpty() && !down) return ByteArray(0)
        if (now.isNotEmpty() && now == last) return ByteArray(0)
        val b = ByteBuffer.allocate((now.size * 6 + 6) * EV).order(ByteOrder.LITTLE_ENDIAN)
        fun put(type: Int, code: Int, value: Int) {
            b.putInt((nanos / 1_000_000_000).toInt()).putInt(((nanos % 1_000_000_000) / 1000).toInt())
            b.putShort(type.toShort()).putShort(code.toShort()).putInt(value)
        }
        for (c in now) {
            put(EV_ABS, ABS_MT_TRACKING_ID, c.id.coerceIn(0, 31))
            put(EV_ABS, ABS_MT_POSITION_X, c.x.coerceIn(0, PAD_MAX))
            put(EV_ABS, ABS_MT_POSITION_Y, c.y.coerceIn(0, PAD_MAX))
            put(EV_ABS, ABS_MT_TOUCH_MAJOR, 40)
            put(EV_ABS, ABS_MT_PRESSURE, 64)
            put(EV_SYN, SYN_MT_REPORT, 0)
        }
        val isDown = now.isNotEmpty()
        if (isDown != down) { put(EV_KEY, BTN_TOUCH, if (isDown) 1 else 0); down = isDown }
        if (!isDown) put(EV_SYN, SYN_MT_REPORT, 0)
        put(EV_SYN, SYN_REPORT, 0)
        last = now
        return b.array().copyOf(b.position())
    }

    /** A click on the pad's own button (BTN_LEFT / BTN_RIGHT). */
    fun button(code: Int, pressed: Boolean, nanos: Long): ByteArray {
        val b = ByteBuffer.allocate(2 * EV).order(ByteOrder.LITTLE_ENDIAN)
        fun put(type: Int, c: Int, value: Int) {
            b.putInt((nanos / 1_000_000_000).toInt()).putInt(((nanos % 1_000_000_000) / 1000).toInt())
            b.putShort(type.toShort()).putShort(c.toShort()).putInt(value)
        }
        put(EV_KEY, code, if (pressed) 1 else 0); put(EV_SYN, SYN_REPORT, 0)
        return b.array()
    }

    companion object {
        const val PAD_MAX = 4095
        const val BTN_LEFT = 272
        const val BTN_RIGHT = 273
        private const val EV = 16
        private const val EV_SYN = 0; private const val EV_KEY = 1; private const val EV_ABS = 3
        private const val SYN_REPORT = 0; private const val SYN_MT_REPORT = 2
        private const val ABS_MT_TOUCH_MAJOR = 48; private const val ABS_MT_POSITION_X = 53
        private const val ABS_MT_POSITION_Y = 54; private const val ABS_MT_TRACKING_ID = 57; private const val ABS_MT_PRESSURE = 58
        private const val BTN_TOUCH = 330

        fun pad(v: Int, size: Int): Int = if (size <= 1) 0 else (v.toLong() * PAD_MAX / (size - 1)).toInt().coerceIn(0, PAD_MAX)
    }
}

/** What the guest must provide for [VmSettings.mouseKeyboard]; the keypad needs nothing but the ordinary key device. */
object VirtualInputPolicy {
    /**
     * The emulated evdev nodes (aemu-touchpad, aemu-keyboard) come from the guest shim on the qemu engines (libaemushim,
     * ARM and x86-32 builds) and from the runner's own input emulation on the native arm64 engine (libaemu64_*, any
     * firmware architecture). Recovery and framework-less boots need none. A pre-1.0 build (see [LegacyFirmware]) has no
     * support for it at all: it gets the one combined keypad/keyboard window instead (see [LegacyKeys.rows]).
     */
    @Suppress("UNUSED_PARAMETER")
    fun mouseKeyboardSupported(engine: Engine, arch: GuestArch, recovery: Boolean, nativeUi: Boolean, preRelease: Boolean = false): Boolean =
        !recovery && !nativeUi && !preRelease   // a pre-1.0 build has neither the guest shim's nodes nor a pad-aware input stack
}
