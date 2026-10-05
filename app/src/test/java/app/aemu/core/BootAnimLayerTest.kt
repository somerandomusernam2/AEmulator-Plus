package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootAnimLayerTest {
    private val stuck = """
Visible layers (count = 3)
+ Layer 0x41b800 (Keyguard)
      layerStack=   0, z=   131000, pos=(0,0), size=( 540, 960)
+ Layer 0x4167e8 (StatusBar)
      layerStack=   0, z=   161000, pos=(0,0), size=( 540,  38)
+ Layer 0x40cfd8 (BootAnimation)
      layerStack=   0, z=1073741824, pos=(0,0), size=( 540, 960)
   type=0, hwcId=0, layerStack=0, ( 540x 960), ANativeWindow=0x40aec0
"""

    @Test fun detectsDeadBootAnimOnTop() {
        assertEquals(listOf("Keyguard", "StatusBar", "BootAnimation"), BootAnimLayer.visibleLayers(stuck))
        assertTrue(BootAnimLayer.topIsBootAnim(stuck))
    }

    @Test fun normalTopLayer() {
        assertFalse(BootAnimLayer.topIsBootAnim(stuck.replace("BootAnimation", "NavigationBar")))
    }

    @Test fun emptyDump() { assertFalse(BootAnimLayer.topIsBootAnim("")) }
}
