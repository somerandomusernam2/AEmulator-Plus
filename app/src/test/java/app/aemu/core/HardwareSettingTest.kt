package app.aemu.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HardwareSettingTest {
    @Test fun defaultIsBlankMeaningFirmwareValue() {
        assertEquals("", VmSettings().hardware)
        assertEquals("", VmSettings.fromJson(JSONObject()).hardware)
    }
    @Test fun cleanHardwareKeepsOnlyPropertyNameSafeCharsUpToLimit() {
        assertEquals("goldfish", VmSettings.cleanHardware(" gold fish/"))
        assertEquals("mt6580_v1-2.x", VmSettings.cleanHardware("mt6580_v1-2.x"))
        assertEquals("", VmSettings.cleanHardware(" /\\\n"))
        assertEquals(VmSettings.HARDWARE_MAX, VmSettings.cleanHardware("a".repeat(100)).length)
    }
    @Test fun presetsAreAlreadyClean() {
        VmSettings.HARDWARE_PRESETS.forEach { assertEquals(it, VmSettings.cleanHardware(it)) }
    }
    @Test fun hardwareSurvivesJsonRoundTripAndIsSanitisedOnLoad() {
        assertEquals("ranchu", VmSettings.fromJson(VmSettings(hardware = "ranchu").toJson()).hardware)
        assertEquals("qcom", VmSettings.fromJson(JSONObject().put("hardware", "q com!")).hardware)
    }
}
