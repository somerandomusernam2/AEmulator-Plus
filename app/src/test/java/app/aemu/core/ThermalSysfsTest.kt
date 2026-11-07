package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ThermalSysfsTest {
    private fun tmp(): File = Files.createTempDirectory("aemu-thermal").toFile()
    private fun temp(root: File) = File(root, "${ThermalSysfs.ZONE}/temp")

    @Test fun passthroughOffServesCoolConstant() {
        val root = tmp()
        assertEquals(30000, ThermalSysfs.apply(root, VmSettings()))
        assertEquals("30000", temp(root).readText().trim())
        assertTrue(File(root, "${ThermalSysfs.ZONE}/type").isFile)
    }

    @Test fun passthroughOnLeavesNoFileSoTheHostShowsThrough() {
        val root = tmp()
        ThermalSysfs.apply(root, VmSettings())                         // was served before
        assertNull(ThermalSysfs.apply(root, VmSettings(thermalPassthrough = true)))
        assertFalse(temp(root).exists())
    }

    @Test fun explicitValueWinsInBothModes() {
        for (pass in listOf(false, true)) {
            val root = tmp()
            assertEquals(45500, ThermalSysfs.apply(root, VmSettings(thermalPassthrough = pass, thermalTemp = 45500)))
            assertEquals("45500", temp(root).readText().trim())
        }
    }

    @Test fun clearingTheValueRestoresTheModeDefault() {
        val root = tmp()
        ThermalSysfs.apply(root, VmSettings(thermalTemp = 60000))
        ThermalSysfs.apply(root, VmSettings(thermalTemp = null))
        assertEquals("30000", temp(root).readText().trim())
    }

    @Test fun parsesDegreesAndMillidegrees() {
        assertEquals(30000, ThermalSysfs.parseInput("30"))
        assertEquals(30500, ThermalSysfs.parseInput("30,5"))
        assertEquals(36600, ThermalSysfs.parseInput(" 36.6 °C "))
        assertEquals(42000, ThermalSysfs.parseInput("42000"))
        assertEquals(-5000, ThermalSysfs.parseInput("-5"))
        assertNull(ThermalSysfs.parseInput(""))
        assertNull(ThermalSysfs.parseInput("hot"))
        assertNull(ThermalSysfs.parseInput("200"))
        assertNull(ThermalSysfs.parseInput("NaN"))
    }

    @Test fun settingsRoundTrip() {
        val s = VmSettings(thermalPassthrough = true, thermalTemp = 41000)
        assertEquals(s.thermalTemp, VmSettings.fromJson(s.toJson()).thermalTemp)
        assertTrue(VmSettings.fromJson(s.toJson()).thermalPassthrough)
        assertNull(VmSettings.fromJson(VmSettings().toJson()).thermalTemp)
        assertFalse(VmSettings.fromJson(org.json.JSONObject()).thermalPassthrough)
    }
}
