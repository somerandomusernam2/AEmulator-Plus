package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryModeTest {
    @Test fun parseFallsBackToMedium() {
        assertEquals(MemoryMode.MEDIUM, MemoryMode.parse(null))
        assertEquals(MemoryMode.MEDIUM, MemoryMode.parse("nonsense"))
        assertEquals(MemoryMode.LIGHT, MemoryMode.parse("light"))
        assertEquals(MemoryMode.AGGRESSIVE, MemoryMode.parse("AGGRESSIVE"))
    }

    @Test fun settingsRoundTripAndOldFilesGetMedium() {
        val s = VmSettings(memoryMode = MemoryMode.LIGHT.id)
        assertEquals("light", VmSettings.fromJson(s.toJson()).memoryMode)
        val old = s.toJson().apply { remove("memoryMode") }
        assertEquals("medium", VmSettings.fromJson(old).memoryMode)
    }

    /** An Android TV guest with Play services: 12 cached processes, the phone has 2.6 GB free. */
    @Test fun plentyOfMemoryWithManyCachedProcesses() {
        assertFalse(MemoryMode.LIGHT.shouldKill(false, 0, 2650, 12, 0))
        assertTrue(MemoryMode.MEDIUM.shouldKill(false, 0, 2650, 12, 0))
        assertTrue(MemoryMode.AGGRESSIVE.shouldKill(false, 0, 2650, 12, 0))
    }

    @Test fun everyModeActsWhenThePhoneIsNearlyOut() {
        for (m in MemoryMode.entries) assertTrue(m.id, m.shouldKill(false, 0, 200, 2, 0))
    }

    @Test fun nobodyKillsWhenNothingIsCached() {
        // a few cached processes and lots of free memory: never
        for (m in MemoryMode.entries) assertFalse(m.id, m.shouldKill(false, 0, 8000, 3, 0))
    }

    /** With the phone's memory in the window where the modes differ, each stricter mode kills whenever the milder one does. */
    @Test fun modesAreNested() {
        for (low in listOf(false, true)) for (ram in listOf(0, 1024, 2048)) for (avail in 0L..9000L step 50) for (cached in 0..19) {
            val l = MemoryMode.LIGHT.shouldKill(low, ram, avail, cached, ram * 2L)
            val m = MemoryMode.MEDIUM.shouldKill(low, ram, avail, cached, ram * 2L)
            val a = MemoryMode.AGGRESSIVE.shouldKill(low, ram, avail, cached, ram * 2L)
            assertTrue("light implies medium (low=$low ram=$ram avail=$avail cached=$cached)", !l || m)
            assertTrue("medium implies aggressive (low=$low ram=$ram avail=$avail cached=$cached)", !m || a)
        }
    }
}
