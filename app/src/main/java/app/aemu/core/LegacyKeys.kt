package app.aemu.core

import android.view.KeyEvent

/**
 * Keys of a pre-1.0 build. Its EventHub reads the image's own qwerty.kl (see TreeFixer.keyLayout), which knows only the
 * evdev codes below: digits, the letters, DEL, NEWLINE, SHIFT, ALT, SYM, AT, SLASH, COMMA, PERIOD, TAB, SPACE, the D-pad,
 * the soft/call keys, Back, Home and the volume/power keys. A code the layout has no line for is dropped by the guest, so
 * the host keyboard and the on-screen keyboard send these and nothing else.
 */
object LegacyKeys {
    private const val LETTERS_ROW1 = "qwertyuiop"   // evdev 16..25
    private const val LETTERS_ROW2 = "asdfghjkl"    // evdev 30..38
    private const val LETTERS_ROW3 = "zxcvbnm"      // evdev 44..50

    const val DEL = 14; const val TAB = 15; const val NEWLINE = 28; const val SHIFT = 42; const val SHIFT_RIGHT = 54
    const val ALT = 56; const val SPACE = 57; const val SYM = 127; const val AT = 215
    /** The prototype's application keys: no later layout has them. */
    const val EXPLORER = 150; const val ENVELOPE = 155; const val CAMERA = 212
    const val COMMA = 51; const val PERIOD = 52; const val SLASH = 53

    /** evdev code of a letter key, or null. */
    fun letter(c: Char): Int? = LETTERS_ROW1.indexOf(c).takeIf { it >= 0 }?.let { 16 + it }
        ?: LETTERS_ROW2.indexOf(c).takeIf { it >= 0 }?.let { 30 + it }
        ?: LETTERS_ROW3.indexOf(c).takeIf { it >= 0 }?.let { 44 + it }

    /** The evdev code the guest takes for a key of the host's keyboard, or null when the key is not for the guest. */
    fun fromAndroid(k: Int): Int? = when (k) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> letter('a' + (k - KeyEvent.KEYCODE_A))
        KeyEvent.KEYCODE_0 -> 11
        in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> 2 + (k - KeyEvent.KEYCODE_1)
        KeyEvent.KEYCODE_SPACE -> SPACE
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> NEWLINE
        KeyEvent.KEYCODE_DEL -> DEL
        KeyEvent.KEYCODE_TAB -> TAB
        KeyEvent.KEYCODE_COMMA -> COMMA
        KeyEvent.KEYCODE_PERIOD -> PERIOD
        KeyEvent.KEYCODE_SLASH -> SLASH
        KeyEvent.KEYCODE_AT -> AT
        KeyEvent.KEYCODE_SHIFT_LEFT -> SHIFT
        KeyEvent.KEYCODE_SHIFT_RIGHT -> SHIFT_RIGHT
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> ALT
        KeyEvent.KEYCODE_SYM -> SYM
        KeyEvent.KEYCODE_STAR -> VirtualKeys.STAR       // InputService.key() turns these into the layout's own STAR / POUND codes
        KeyEvent.KEYCODE_POUND -> VirtualKeys.POUND
        KeyEvent.KEYCODE_DPAD_UP -> InputService.KEY_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> InputService.KEY_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> InputService.KEY_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> InputService.KEY_RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER -> InputService.KEY_CENTER
        KeyEvent.KEYCODE_ESCAPE -> InputService.KEY_BACK
        KeyEvent.KEYCODE_CALL -> VirtualKeys.SEND
        KeyEvent.KEYCODE_ENDCALL -> VirtualKeys.ENDCALL
        KeyEvent.KEYCODE_MENU -> InputService.KEY_MENU
        KeyEvent.KEYCODE_VOLUME_UP -> InputService.KEY_VOLUMEUP
        KeyEvent.KEYCODE_VOLUME_DOWN -> InputService.KEY_VOLUMEDOWN
        else -> null
    }

    /**
     * The one on-screen window of a pre-1.0 build, always available: the feature-phone keypad (soft keys, Call/End, Home, the
     * D-pad cross, digits with * and #), the prototype's application keys, and the keyboard. Limited to what the 0.9 layout
     * maps (no Esc, Ctrl, Caps, brackets or the punctuation it never had).
     */
    val rows: List<List<VKey>> = run {
        fun k(label: String, code: Int, w: Float = 1f, shifted: String? = null) = VKey(label, code, w, shifted)
        fun letters(chars: String) = chars.map { k(it.toString(), letter(it)!!, 1f, it.uppercase()) }
        listOf(
            listOf(k("◀ Menu", InputService.KEY_MENU, 1.4f), k("▲", InputService.KEY_UP), k("Back ▶", InputService.KEY_BACK, 1.4f)),
            listOf(k("◀", InputService.KEY_LEFT), k("OK", InputService.KEY_CENTER), k("▶", InputService.KEY_RIGHT)),
            listOf(k("Call", VirtualKeys.SEND, 1.4f), k("▼", InputService.KEY_DOWN), k("End", VirtualKeys.ENDCALL, 1.4f)),
            listOf(k("Home", InputService.KEY_HOME), k("Explorer", EXPLORER, 1.3f), k("Envelope", ENVELOPE, 1.3f), k("Camera", CAMERA, 1.3f)),
            "1234567890".mapIndexed { i, c -> k(c.toString(), if (i == 9) 11 else i + 2) } +
                listOf(k("*", VirtualKeys.STAR), k("#", VirtualKeys.POUND)),
            letters(LETTERS_ROW1),
            letters(LETTERS_ROW2) + k("⌫", DEL, 1.5f),
            listOf(k("⇧", SHIFT, 1.6f)) + letters(LETTERS_ROW3) + listOf(k(",", COMMA), k(".", PERIOD), k("⏎", NEWLINE, 1.6f)),
            listOf(k("Alt", ALT, 1.4f), k("Sym", SYM, 1.4f), k("@", AT), k("␣", SPACE, 4f), k("/", SLASH), k("Tab", TAB, 1.3f)),
        )
    }
}
