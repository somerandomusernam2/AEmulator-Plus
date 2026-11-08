package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every code the host keyboard and the on-screen keyboard send to a pre-1.0 guest must have a line in that image's qwerty.kl. */
class LegacyKeysTest {
    private val mapped: Set<Int> = javaClass.getResourceAsStream("/pre1-qwerty.kl")!!.bufferedReader().readLines()
        .mapNotNull { l -> l.trim().split(Regex("\\s+")).takeIf { it.size >= 3 && it[0] == "key" }?.get(1)?.toIntOrNull() }.toSet()

    @Test fun onScreenKeysAreAllInTheImagesLayout() {
        // Menu, * and # are sent under the codes the layout gives SOFT_LEFT / STAR / POUND (InputService.legacyKeyAliases)
        val aliased = setOf(VirtualKeys.STAR, VirtualKeys.POUND, InputService.KEY_MENU)
        for (row in LegacyKeys.rows) for (k in row) assertTrue("${k.label} (${k.code}) is not in qwerty.kl", k.code in mapped || k.code in aliased)
    }

    @Test fun hostKeysAreAllInTheImagesLayout() {
        // KEYCODE_A..Z = 29..54, 0..9 = 7..16: whatever the host maps must be a key the guest knows (or an alias InputService resolves)
        val aliased = setOf(VirtualKeys.STAR, VirtualKeys.POUND, InputService.KEY_MENU)
        for (k in 0..300) LegacyKeys.fromAndroid(k)?.let { assertTrue("host key $k -> $it", it in mapped || it in aliased) }
    }

    @Test fun theWindowCarriesKeypadApplicationKeysAndKeyboard() {
        val codes = LegacyKeys.rows.flatten().map { it.code }.toSet()
        for (c in listOf(InputService.KEY_MENU, InputService.KEY_BACK, InputService.KEY_HOME, InputService.KEY_CENTER, VirtualKeys.SEND,
            VirtualKeys.ENDCALL, VirtualKeys.STAR, VirtualKeys.POUND, 150, 155, 212, 2, 11, 16, 57)) assertTrue("missing $c", c in codes)
    }

    @Test fun lettersFollowTheKeyboardRows() {
        assertEquals(16, LegacyKeys.letter('q')); assertEquals(30, LegacyKeys.letter('a')); assertEquals(50, LegacyKeys.letter('m'))
        assertNull(LegacyKeys.letter('1'))
    }
}
