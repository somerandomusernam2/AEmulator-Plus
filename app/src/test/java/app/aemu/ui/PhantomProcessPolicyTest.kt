package app.aemu.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhantomProcessPolicyTest {
    private fun warn(sdk: Int = 34, global: String? = null, persist: String? = null,
                     override: String? = null, default: String? = null) =
        PhantomProcessPolicy.shouldWarn(sdk, global, persist, override, default)

    @Test fun androidBelow12NeverWarns() {
        assertFalse(warn(sdk = 30))
        assertFalse(warn(sdk = 28, global = "true"))
    }

    @Test fun nothingSetMeansMonitorIsOn() = assertTrue(warn())

    @Test fun developerOptionToggleWritesPersistProperty() {
        assertFalse(warn(persist = "false"))      // toggle ON
        assertTrue(warn(persist = "true"))        // toggle OFF
    }

    @Test fun adbSettingOverridesProperty() {
        assertFalse(warn(global = "false", persist = "true"))
        assertTrue(warn(global = "true", persist = "false"))
    }

    @Test fun emptyValuesFallThroughToNextSource() {
        assertFalse(warn(global = "", persist = "", override = "false"))
        assertFalse(warn(default = "false"))
    }
}
