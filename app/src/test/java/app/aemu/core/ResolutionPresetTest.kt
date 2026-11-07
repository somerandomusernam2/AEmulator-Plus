package app.aemu.core

import org.junit.Assert.*
import org.junit.Test

class ResolutionPresetTest {
    private val hd = ResolutionPreset(720, 1280)

    @Test fun hostEnablesExistingModeWithoutOverwritingManualValues() {
        val manual = VmSettings(width = 600, height = 1000, density = 280)
        assertEquals(manual.copy(hostResolution = true), ResolutionPreset.HOST.apply(manual, 320))
    }
    @Test fun fixedPresetTakesFirmwareDensityAndLeavesHostMode() {
        assertEquals(VmSettings(width = 720, height = 1280, density = 213),
            hd.apply(VmSettings(hostResolution = true), 213))
    }
    @Test fun fixedPresetKeepsDensityWhenFirmwareHasNone() {
        assertEquals(VmSettings(width = 720, height = 1280, density = 280), hd.apply(VmSettings(density = 280), null))
    }
    @Test fun onlyHostIsSelectedWhenEnabled() {
        val settings = VmSettings(width = 480, height = 800, hostResolution = true)
        assertEquals(listOf(ResolutionPreset.HOST), ResolutionPreset.entries.filter { it.selected(settings) })
        assertEquals(listOf(ResolutionPreset(480, 800)), ResolutionPreset.entries.filter { it.selected(settings.copy(hostResolution = false)) })
    }
    @Test fun listHasPortraitThenLandscapeThenHost() {
        val e = ResolutionPreset.entries
        assertEquals(ResolutionPreset(280, 280), e.first())
        assertEquals(ResolutionPreset.HOST, e.last())
        assertEquals(1, e.indexOf(ResolutionPreset(320, 320)))
        assertTrue(e.indexOf(ResolutionPreset(1440, 3200)) < e.indexOf(ResolutionPreset(800, 480)))
        assertTrue(e.contains(ResolutionPreset(1080, 2400)))
        assertTrue(e.indexOf(ResolutionPreset(3200, 1440)) == e.lastIndex - 1)
        assertEquals(e.size, e.toSet().size)
    }
    @Test fun readsDensityFromBuildProp() {
        assertEquals(320, ResolutionPreset.densityOf("ro.build.id=X\nro.sf.lcd_density=320\n"))
        assertNull(ResolutionPreset.densityOf("ro.build.id=X\n"))
        assertNull(ResolutionPreset.densityOf("ro.sf.lcd_density=abc\n"))
    }
}
