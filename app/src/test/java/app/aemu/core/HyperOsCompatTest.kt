package app.aemu.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HyperOsCompatTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(root: File, path: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText("x") }

    @Test fun plainAospGetsNothing() {
        val root = tmp.newFolder()
        file(root, "system/framework/framework.jar")
        assertTrue(HyperOsCompat.overrides(root, 37, true).isEmpty())
    }

    @Test fun hyperOsGetsBlurOffAndRustOn() {
        val root = tmp.newFolder()
        file(root, "system_ext/framework/miui-framework.jar")
        file(root, "system_ext/etc/init/init.hyos_spawner.rc")
        val m = HyperOsCompat.overrides(root, 37, true)
        assertEquals("false", m["persist.sys.background_blur_supported"])
        assertEquals("1", m["persist.sys.rust_runtime_active"])
        assertEquals("1", m["rust.runtime_active"])
    }

    @Test fun miuiWithoutRustRuntimeOnlyGetsBlurOff() {
        val root = tmp.newFolder()
        file(root, "system/framework/miui-framework.jar")
        val m = HyperOsCompat.overrides(root, 33, true)
        assertEquals("false", m["persist.sys.background_blur_supported"])
        assertFalse(m.containsKey("persist.sys.rust_runtime_active"))
    }

    @Test fun rustRuntimeFoundByPermissionsFileOfTheProductPartition() {
        val root = tmp.newFolder()
        file(root, "system_ext/framework/miui-framework.jar")
        file(root, "product/etc/permissions/rustruntime_cfg_v3_v5.xml")
        assertTrue(HyperOsCompat.hasRustRuntime(root))
    }

    @Test fun otherEnginesAndOldAndroidAreLeftAlone() {
        val root = tmp.newFolder()
        file(root, "system_ext/framework/miui-framework.jar")
        assertTrue(HyperOsCompat.overrides(root, 37, false).isEmpty())
        assertTrue(HyperOsCompat.overrides(root, 24, true).isEmpty())
    }
}
