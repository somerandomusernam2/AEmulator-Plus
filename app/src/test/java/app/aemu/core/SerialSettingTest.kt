package app.aemu.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SerialSettingTest {
    @Test fun defaultIsBlankAndFallsBackToSampleSerial() {
        assertEquals("", VmSettings().serial)
        assertEquals("0123456789abcdef", VmSettings.DEFAULT_SERIAL)
        assertEquals(VmSettings.DEFAULT_SERIAL, VmSettings.cleanSerial(VmSettings().serial).ifBlank { VmSettings.DEFAULT_SERIAL })
    }
    @Test fun cleanSerialKeepsOnlyLettersAndDigitsUpToLimit() {
        assertEquals("AB12cd", VmSettings.cleanSerial("AB 12-cd/\n"))
        assertEquals("", VmSettings.cleanSerial(" -_./"))
        assertEquals(VmSettings.SERIAL_MAX, VmSettings.cleanSerial("a".repeat(100)).length)
    }
    @Test fun randomSerialIsSixteenLowercaseHexAndAlreadyClean() {
        repeat(50) {
            val s = VmSettings.randomSerial()
            assertTrue(Regex("[0-9a-f]{16}").matches(s))
            assertEquals(s, VmSettings.cleanSerial(s))
        }
    }
    @Test fun serialSurvivesJsonRoundTripAndOldProfilesStayBlank() {
        val s = VmSettings(serial = "R58M12ABCDE")
        assertEquals("R58M12ABCDE", VmSettings.fromJson(s.toJson()).serial)
        assertEquals("", VmSettings.fromJson(JSONObject()).serial)
        assertEquals("ABC123", VmSettings.fromJson(JSONObject().put("serial", "ABC 123!")).serial)
    }
}
