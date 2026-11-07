package app.aemu.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Lpw39Dex2oatTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tree(): File = tmp.newFolder("root").also { File(it, "system/bin").mkdirs() }

    @Test fun scriptHasNoCarriageReturnsAndAShebang() {
        val s = Lpw39Dex2oat.script()
        assertTrue(s.startsWith("#!/system/bin/sh\n"))
        assertFalse(s.contains('\r'))
        assertTrue(s.contains("dex2oat.real"))
    }
    @Test fun movesTheOriginalAndWritesTheWrapper() {
        val root = tree()
        File(root, "system/bin/dex2oat").writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        assertTrue(Lpw39Dex2oat.install(root))
        assertTrue(Lpw39Dex2oat.isWrapper(File(root, "system/bin/dex2oat")))
        assertEquals(4L, File(root, "system/bin/dex2oat.real").length())
    }
    @Test fun secondRunChangesNothing() {
        val root = tree()
        File(root, "system/bin/dex2oat").writeText("firmware")
        assertTrue(Lpw39Dex2oat.install(root))
        assertFalse(Lpw39Dex2oat.install(root))
        assertEquals("firmware", File(root, "system/bin/dex2oat.real").readText())
    }
    @Test fun reimportedFirmwareReplacesTheOldReal() {
        val root = tree()
        File(root, "system/bin/dex2oat").writeText("v1")
        Lpw39Dex2oat.install(root)
        File(root, "system/bin/dex2oat").delete()
        File(root, "system/bin/dex2oat").writeText("v2")
        assertTrue(Lpw39Dex2oat.install(root))
        assertEquals("v2", File(root, "system/bin/dex2oat.real").readText())
    }
    @Test fun noDex2oatMeansNothingToDo() {
        assertFalse(Lpw39Dex2oat.install(tree()))
    }
}
