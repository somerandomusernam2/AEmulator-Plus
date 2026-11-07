package app.aemu.core

import java.io.File
import java.nio.file.Files
import org.junit.Test
import org.junit.Assert.*

class FlattenedLinksTest {
    private fun fixture(test: (File) -> Unit) {
        val root = Files.createTempDirectory("aess-flat-links").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
    private fun put(root: File, rel: String, text: String) = File(root, rel).apply { parentFile.mkdirs(); writeText(text) }

    @Test fun shAndToolboxAppletsBecomeLinks() = fixture { root ->
        put(root, "system/bin/mksh", "\u007fELF-binary-of-mksh")
        put(root, "system/bin/toolbox", "\u007fELF-binary-of-toolbox")
        put(root, "system/bin/sh", "mksh")
        put(root, "system/bin/ls", "toolbox")
        assertEquals(2, FlattenedLinks.repair(root))
        assertEquals("mksh", Files.readSymbolicLink(File(root, "system/bin/sh").toPath()).toString())
        assertEquals("toolbox", Files.readSymbolicLink(File(root, "system/bin/ls").toPath()).toString())
        assertTrue(File(root, "system/bin/sh").readText().startsWith("\u007fELF"))
        assertEquals(0, FlattenedLinks.repair(root)) // second run changes nothing
    }
    @Test fun absoluteTargetBecomesRelativeInsideTree() = fixture { root ->
        put(root, "system/lib/libreal.so", "ELF")
        put(root, "system/vendor/lib/libalias.so", "/system/lib/libreal.so")
        assertEquals(1, FlattenedLinks.repair(root))
        assertEquals("../../lib/libreal.so", Files.readSymbolicLink(File(root, "system/vendor/lib/libalias.so").toPath()).toString())
    }
    @Test fun ordinaryTextFilesAreLeftAlone() = fixture { root ->
        put(root, "system/bin/mksh", "ELF")
        put(root, "system/build.prop", "ro.build.id=JDQ39\n")      // newline, '=' : not a token
        put(root, "system/etc/note", "mksh\n")                      // ends in newline: real text file
        put(root, "system/etc/dangling", "no-such-file")            // target does not exist
        put(root, "system/etc/egl.cfg", "/sys/egl/egl.cfg")         // absolute, not in the tree
        put(root, "system/etc/escape", "../../../../etc/passwd")    // would leave the tree
        assertEquals(0, FlattenedLinks.repair(root))
    }
    @Test fun parkedFilesAreSkipped() = fixture { root ->
        put(root, "system/bin/mksh", "ELF")
        put(root, "system/.aemu-parked/sh", "mksh")
        assertEquals(0, FlattenedLinks.repair(root))
    }
    @Test fun targetOfRejectsBinaryAndLongContent() {
        assertNull(FlattenedLinks.targetOf(byteArrayOf()))
        assertNull(FlattenedLinks.targetOf(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())))
        assertNull(FlattenedLinks.targetOf(ByteArray(300) { 'a'.code.toByte() }))
        assertEquals("mksh", FlattenedLinks.targetOf("mksh".toByteArray()))
    }
}
