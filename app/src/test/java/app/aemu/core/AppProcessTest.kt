package app.aemu.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppProcessTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tree(vararg bins: String): File = tmp.newFolder().also { r ->
        File(r, "system/bin").mkdirs()
        for (b in bins) File(r, "system/bin/$b").writeText("x")
    }

    @Test fun prefersAppProcess() {
        assertEquals("/system/bin/app_process", AppProcess.path(tree("app_process", "app_process32")))
    }
    @Test fun fallsBackToApp_process32() {
        assertEquals("/system/bin/app_process32", AppProcess.path(tree("app_process32")))
    }
    @Test fun defaultNameWhenNothingExists() {
        assertEquals("/system/bin/app_process", AppProcess.path(tree()))
    }
    @Test fun noLinkWhenAppProcessExistsOrNoAlt() {
        assertFalse(AppProcess.ensureLink(tree("app_process")))
        assertFalse(AppProcess.ensureLink(tree()))
    }
    @Test fun fixExecRewritesBothFormsAndIsIdempotent() {
        val s = "base=/system\nexport CLASSPATH=x\nexec app_process \$base/bin Pm \"\$@\"\n  exec /system/bin/app_process \$base/bin Am\n"
        val out = AppProcess.fixExec(s, "/system/bin/app_process32")
        assertEquals(2, Regex("exec /system/bin/app_process32 ").findAll(out).count())
        assertEquals(out, AppProcess.fixExec(out, "/system/bin/app_process32"))
        assertEquals(s.replace("exec app_process", "exec /system/bin/app_process"),
            AppProcess.fixExec(s, "/system/bin/app_process"))
    }
}
