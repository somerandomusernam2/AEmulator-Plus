/* AEmulator Plus addition, 2026-11-01. GPL-3.0; see LICENSE. */
package app.aemu.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtBootImageSettingTest {
    @Test fun offByDefaultAlsoForSavedVmsThatNeverHadIt() {
        assertFalse(VmSettings().useArtBootImage)
        assertFalse(VmSettings.fromJson(JSONObject()).useArtBootImage)
        assertFalse(VmSettings.fromJson(null).useArtBootImage)
    }

    @Test fun survivesJsonRoundTrip() {
        assertTrue(VmSettings.fromJson(VmSettings(useArtBootImage = true).toJson()).useArtBootImage)
        assertFalse(VmSettings.fromJson(VmSettings(useArtBootImage = false).toJson()).useArtBootImage)
    }
}
