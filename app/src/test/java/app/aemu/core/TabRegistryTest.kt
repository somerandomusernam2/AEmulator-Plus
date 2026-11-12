/* Added for AEmulator Plus, 2026-11-09: two machines on one tab. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import org.junit.Assert.*
import org.junit.Test

class TabRegistryTest {
    @Test fun aSingleMachineLine() {
        val t = TabRegistry.parse("abc123")!!
        assertEquals(listOf("abc123"), t.ids)
        assertFalse(t.isGroup)
        assertNull(t.paneOf("abc123"))
        assertEquals("abc123", TabRegistry.format(t))
    }

    @Test fun aTabOfTwoMachinesRoundTrips() {
        val t = TabRegistry.parse("aaaaaa+bbbbbb@T")!!
        assertTrue(t.isGroup)
        assertEquals(listOf("aaaaaa", "bbbbbb"), t.ids)
        assertEquals('T', t.pos)
        assertEquals("aaaaaa+bbbbbb@T", TabRegistry.format(t))
    }

    @Test fun theOverlayIsTheSecondMachineOnTheDroppedSide() {
        val t = TabRegistry.parse("aaaaaa+bbbbbb@L")!!
        assertFalse(t.isOverlay("aaaaaa"))
        assertTrue(t.isOverlay("bbbbbb"))
        assertEquals('L', t.paneOf("bbbbbb"))
        assertEquals('R', t.paneOf("aaaaaa"))
        assertEquals("aaaaaa", t.otherThan("bbbbbb"))
        assertEquals("bbbbbb", t.otherThan("aaaaaa"))
    }

    @Test fun badLinesAreIgnored() {
        assertNull(TabRegistry.parse(""))
        assertNull(TabRegistry.parse("ab cd"))
        assertNull(TabRegistry.parse("aaaaaa+bbbbbb"))      // no side
        assertNull(TabRegistry.parse("aaaaaa+bbbbbb@X"))    // not a side
        assertEquals(listOf("aaaaaa"), TabRegistry.parse("aaaaaa+aaaaaa@T")!!.ids) // a machine cannot share with itself
    }

    @Test fun tabNamesAreCutAtTenCharacters() {
        assertEquals("Pixel", TabRegistry.shortName("Pixel"))
        assertEquals("0123456789", TabRegistry.shortName("0123456789"))
        assertEquals("0123456789...", TabRegistry.shortName("0123456789A"))
        assertEquals("Android 14...", TabRegistry.shortName("Android 14 Pixel 8"))
    }
}
