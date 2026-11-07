package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassLocationPolicyTest {
    private val xml = """<packages>
<package name="com.google.android.location" codePath="/system/app/NetworkLocation.apk" flags="1">
<disabled-components>
<item name="com.google.android.location.NetworkLocationService" />
<item name="com.google.android.location.internal.server.NetworkLocationService" />
</disabled-components>
<sigs count="1"></sigs>
</package>
<package name="com.other" codePath="/system/app/O.apk">
<disabled-components>
<item name="com.google.android.location.NetworkLocationService" />
</disabled-components>
</package>
</packages>"""

    @Test fun clearsOnlyTheLocationPackage() {
        val out = GlassLocationPolicy.reenable(xml)!!
        val loc = out.substringAfter("com.google.android.location\"").substringBefore("</package>")
        assertFalse(loc.contains("NetworkLocationService"))
        assertFalse(loc.contains("disabled-components"))
        assertTrue(out.substringAfter("com.other").contains("NetworkLocationService"))
    }

    @Test fun nothingToDoReturnsNull() {
        assertNull(GlassLocationPolicy.reenable("<packages><package name=\"com.google.android.location\" codePath=\"x\">\n</package></packages>"))
        assertNull(GlassLocationPolicy.reenable("<packages></packages>"))
        assertEquals(null, GlassLocationPolicy.reenable(GlassLocationPolicy.reenable(xml)!!))
    }

    @Test fun glassKeepsLocationComponentsEnabled() {
        val c = "com.google.android.location/com.google.android.location.NetworkLocationService"
        assertTrue(GlassLocationPolicy.keepEnabled(c, glass = true))
        assertFalse(GlassLocationPolicy.keepEnabled(c, glass = false))
        assertFalse(GlassLocationPolicy.keepEnabled("com.google.android.apps.maps/com.google.android.location.NetworkLocationService", true))
    }

    @Test fun detectsGlass() {
        assertTrue(GlassLocationPolicy.isGlass("Google Glass 1", "", false))
        assertTrue(GlassLocationPolicy.isGlass("x", "", true))
        assertFalse(GlassLocationPolicy.isGlass("Nexus 4", "Nexus 4", false))
    }
}
