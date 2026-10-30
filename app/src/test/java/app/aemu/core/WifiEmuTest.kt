package app.aemu.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.StringReader
import java.util.Properties

class WifiEmuTest {
    @Test fun ssidIsCutAtThirtyTwoBytesOnACharacterBoundary() {
        assertEquals("ж".repeat(16), WifiEmu.cleanSsid("ж".repeat(40)))
        assertEquals(8, WifiEmu.cleanSsid("😀".repeat(10)).codePointCount(0, WifiEmu.cleanSsid("😀".repeat(10)).length))
        assertEquals("Café", WifiEmu.cleanSsid("Café\u0000\n"))
    }
    @Test fun passwordRules() {
        assertEquals("pssword1", WifiEmu.cleanPassword("pässword1"))
        assertEquals(63, WifiEmu.cleanPassword("a".repeat(80)).length)
        assertTrue(WifiEmu.passwordValid(""))
        assertFalse(WifiEmu.passwordValid("short"))
        assertTrue(WifiEmu.passwordValid("12345678"))
        assertFalse(WifiEmu.secured(VmSettings(wifiPassword = "short")))
        assertTrue(WifiEmu.secured(VmSettings(wifiPassword = "12345678")))
    }
    @Test fun bssidIsStableAndLocallyAdministered() {
        assertEquals(WifiEmu.bssid("home"), WifiEmu.bssid("home"))
        assertNotEquals(WifiEmu.bssid("home"), WifiEmu.bssid("work"))
        assertTrue(Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}").matches(WifiEmu.bssid("home")))
        assertEquals(2, WifiEmu.bssid("home").substring(0, 2).toInt(16) and 3)
    }
    @Test fun supportedGuests() {
        assertTrue(WifiEmu.supported(Engine.KK, 14, false, false))
        assertTrue(WifiEmu.supported(Engine.KK, 19, false, false))
        assertTrue(WifiEmu.supported(Engine.KK, 25, false, false))
        assertTrue(WifiEmu.supported(Engine.KK, 9, false, false))   // Gingerbread 2.3
        assertTrue(WifiEmu.supported(Engine.KK, 10, false, false))  // Gingerbread 2.3.3-2.3.7
        assertFalse(WifiEmu.supported(Engine.KK, 8, false, false))  // Froyo
        for (api in 11..13) assertFalse(WifiEmu.supported(Engine.KK, api, false, false)) // Honeycomb: not looked at
        assertFalse(WifiEmu.supported(Engine.GB, 10, false, false))
        assertFalse(WifiEmu.supported(Engine.KK, 10, true, false))
        assertFalse(WifiEmu.supported(Engine.KK, 26, false, false))
        assertFalse(WifiEmu.supported(Engine.GB, 19, false, false))
        assertFalse(WifiEmu.supported(Engine.KK, 19, true, false))
        assertFalse(WifiEmu.supported(Engine.KK, 19, false, true))
    }
    @Test fun propertiesRoundTrip() {
        val s = VmSettings(wifi = true, wifiSsid = "Мой \"Wi-Fi\" = test", wifiPassword = "pa ss:w0rd#!")
        val p = Properties().apply { load(String(WifiEmu.encode(WifiEmu.properties(s)), Charsets.ISO_8859_1).let(::StringReader)) }
        assertEquals(s.wifiSsid, p.getProperty("ssid"))
        assertEquals("true", p.getProperty("secured"))
        assertEquals(s.wifiPassword, p.getProperty("psk"))
        val open = WifiEmu.properties(VmSettings(wifi = true, wifiSsid = "Open", wifiPassword = ""))
        assertEquals("false", open.getProperty("secured"))
        assertEquals("", open.getProperty("psk"))
    }
    @Test fun fileIsWrittenAndRemoved() {
        val root = File.createTempFile("wifi", "").apply { delete(); mkdirs() }
        try {
            WifiEmu.write(root, VmSettings(wifi = true), true)
            assertTrue(File(root, WifiEmu.FILE).isFile)
            WifiEmu.write(root, VmSettings(wifi = true), false)
            assertFalse(File(root, WifiEmu.FILE).exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun settingsRoundTripThroughJson() {
        val s = VmSettings(wifi = true, wifiSsid = "Home", wifiPassword = "hunter2hunter2")
        val back = VmSettings.fromJson(s.toJson())
        assertTrue(back.wifi); assertEquals("Home", back.wifiSsid); assertEquals("hunter2hunter2", back.wifiPassword)
        assertFalse(VmSettings.fromJson(org.json.JSONObject()).wifi)
        assertEquals(WifiEmu.DEFAULT_SSID, VmSettings.fromJson(org.json.JSONObject().put("wifiSsid", "")).wifiSsid)
    }
}
