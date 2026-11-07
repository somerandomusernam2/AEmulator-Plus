package app.aemu.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormFactorSkinTest {
    private fun skin(chars: String?, api: Int) =
        Analyzer.formFactorSkin(if (chars == null) emptyMap() else mapOf("ro.build.characteristics" to chars), api)

    @Test fun tvBelowApi18IsGoogleTv() {
        assertEquals("Google TV", skin("tv", 13))
        assertEquals("Google TV", skin("tv", 17))
    }
    @Test fun tvFromApi18IsAndroidTv() {
        assertEquals("Android TV", skin("tv", 18))
        assertEquals("Android TV", skin("tablet,tv", 22))
    }
    @Test fun watchIsWear() {
        assertEquals("Wear", skin("watch", 19))
        assertEquals("Wear", skin("Watch,nosdcard", 20))
    }
    @Test fun matchesAnywhereInTheLine() {
        assertEquals("Wear", skin("nosdcard,smartwatch", 19))
        assertEquals("Android TV", skin("nosdcard,tv", 19))
    }
    @Test fun tvAndWatchTogetherIsAosp() {
        assertEquals("AOSP", skin("tv,watch", 19))
        assertEquals("AOSP", skin("watch,tv", 10))
    }
    @Test fun glassFingerprintIsGlassOs() {
        for (fp in listOf("google/glass_1/glass-1:4.4.2/XRV87/1:user/release-keys", "Google/glass_1/glass-1:4.4.2/x", "foo/GOOGLE/GLASS/x"))
            assertEquals("Glass OS", Analyzer.formFactorSkin(mapOf("ro.build.fingerprint" to fp), 19))
        assertNull(Analyzer.formFactorSkin(mapOf("ro.build.fingerprint" to "google/nexus/x"), 19))
    }
    @Test fun otherDevicesFallThrough() {
        assertNull(skin(null, 19))
        assertNull(skin("tablet,nosdcard", 19))
        assertNull(skin("default", 19))
    }
    @Test fun systemDeviceDesktopIsDesktop() {
        assertEquals("Desktop", Analyzer.formFactorSkin(mapOf("ro.product.system.device" to "desktop"), 37))
        assertEquals("Desktop", Analyzer.formFactorSkin(mapOf("ro.product.system.device" to "Desktop ", "ro.build.characteristics" to "tablet"), 37))
        assertNull(Analyzer.formFactorSkin(mapOf("ro.product.system.device" to "generic_x86"), 37))
    }
}
