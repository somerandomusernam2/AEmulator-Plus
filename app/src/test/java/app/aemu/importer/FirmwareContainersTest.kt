package app.aemu.importer

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class FirmwareContainersTest {
    @Test fun gzipSignatureWinsEvenWhenCalledTgzTar() {
        val compressed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(ByteArray(1024)) } }.toByteArray()
        assertEquals(FirmwareContainers.Compression.GZIP, FirmwareContainers.compression(compressed))
    }
    @Test fun signaturesAreLengthChecked() {
        assertNull(FirmwareContainers.compression(byteArrayOf('B'.code.toByte(), 'Z'.code.toByte())))
        assertEquals(FirmwareContainers.Compression.BZIP2, FirmwareContainers.compression("BZh9".toByteArray()))
        assertEquals(FirmwareContainers.Compression.XZ, FirmwareContainers.compression(byteArrayOf(0xfd.toByte(), 0x37, 0x7a, 0x58, 0x5a, 0)))
    }
    @Test fun archivePathsCannotEscapeOrUseSiblingPrefix() {
        val root = java.nio.file.Files.createTempDirectory("firmware-path-test").toFile()
        try {
            assertEquals(java.io.File(root, "system/build.prop"), FirmwareContainers.destination(root, "system/build.prop"))
            for (path in listOf("system/../../outside", "../${root.name}-other/file", "/tmp/file", "system\\file"))
                assertThrows(IllegalArgumentException::class.java) { FirmwareContainers.destination(root, path) }
        } finally { root.deleteRecursively() }
    }
    @Test fun symlinksStayInsideGuestRoot() {
        FirmwareContainers.checkLink("etc", "/system/etc")
        FirmwareContainers.checkLink("system/etc/config", "../../data/config")
        assertThrows(IllegalArgumentException::class.java) { FirmwareContainers.checkLink("etc", "../outside") }
        assertThrows(IllegalArgumentException::class.java) { FirmwareContainers.checkLink("system/link", "../../outside") }
    }
}
