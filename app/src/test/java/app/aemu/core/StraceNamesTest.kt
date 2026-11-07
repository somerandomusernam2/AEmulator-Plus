package app.aemu.core

import org.junit.Assert.*
import org.junit.Test

class StraceNamesTest {
    @Test fun splitsOnCommasAndBlanks() {
        assertEquals(setOf("mediaserver", "zygote"), VmSettings.straceNames("mediaserver, zygote"))
        assertEquals(setOf("mediaserver", "zygote"), VmSettings.straceNames(" mediaserver,,zygote, "))
    }
    @Test fun dropsUnsafeCharacters() {
        assertTrue(VmSettings.straceNames("../etc").none { it.contains('/') })
        assertTrue(VmSettings.straceNames("..").isEmpty())
        assertTrue(VmSettings.straceNames("").isEmpty())
    }
    @Test fun roundTripsThroughJson() {
        val s = VmSettings(straceServices = "zygote")
        assertEquals("zygote", VmSettings.fromJson(s.toJson()).straceServices)
    }
}
