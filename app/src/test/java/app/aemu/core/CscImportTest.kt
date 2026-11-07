package app.aemu.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class CscImportTest {
    private fun fixture(run: (File) -> Unit) {
        val dir = Files.createTempDirectory("csc-import-test").toFile()
        try { run(dir) } finally { dir.deleteRecursively() }
    }
    private fun prop(root: File, text: String) { File(root, "system").mkdirs(); File(root, "system/build.prop").writeText(text) }

    @Test fun onlySamsungBuildPropCounts() = fixture { root ->
        assertFalse(CscImport.isSamsung(root))
        prop(root, "ro.product.brand=google\nro.product.manufacturer=LGE\n")
        assertFalse(CscImport.isSamsung(root))
        prop(root, "ro.build.id=ERE27\nro.product.manufacturer=samsung\n")
        assertTrue(CscImport.isSamsung(root))
        prop(root, "ro.product.brand=Samsung\n")
        assertTrue(CscImport.isSamsung(root))
    }

    @Test fun applyReplacesStubAndStaysInsideCsc() = fixture { root ->
        File(root, "system/csc").mkdirs()
        File(root, "system/csc/customer.xml").writeText("<CustomerData/>")
        assertFalse(CscImport.present(root))
        val n = CscImport.apply(root, mapOf(
            "system/csc/customer.xml" to "real".toByteArray(),
            "system/csc/sales_code.dat" to "SER\n".toByteArray(),
            "system/csc/../escape.txt" to "x".toByteArray(),
        ))
        assertEquals(2, n)
        assertEquals("real", File(root, "system/csc/customer.xml").readText())
        assertTrue(CscImport.present(root))
        assertFalse(File(root, "system/escape.txt").exists())
        assertFalse(File(root, "system/csc").list()!!.any { it.endsWith(".aemu-tmp") })
    }
}
