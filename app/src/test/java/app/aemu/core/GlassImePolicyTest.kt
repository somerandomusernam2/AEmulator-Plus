package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlassImePolicyTest {
    @Test fun seedsOnlyWhenEmptyAndInstalled() {
        assertEquals(GlassImePolicy.REMOTE_IME, GlassImePolicy.valueToSeed(null, true))
        assertEquals(GlassImePolicy.REMOTE_IME, GlassImePolicy.valueToSeed("", true))
        assertNull(GlassImePolicy.valueToSeed("com.x/.Ime", true))
        assertNull(GlassImePolicy.valueToSeed(null, false))
    }

    @Test fun enabledListKeepsExistingEntries() {
        val ime = GlassImePolicy.REMOTE_IME
        assertEquals(ime, GlassImePolicy.enabledWith(null, ime))
        assertEquals("a/.A:$ime", GlassImePolicy.enabledWith("a/.A", ime))
        assertEquals("a/.A:$ime", GlassImePolicy.enabledWith("a/.A:$ime", ime))
    }
}
