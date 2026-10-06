package app.aemu.importer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.*
import org.junit.Test

class OemPartitionSourceTest {
    private val image = ByteArray(4096) { (it % 251).toByte() }
    private fun fixture(run: (File) -> Unit) {
        val dir = Files.createTempDirectory("oem-source-test").toFile()
        try { run(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun plainImageIsPassedThrough() = fixture { dir ->
        assertArrayEquals(image, OemPartitionSource.read(ByteArrayInputStream(image), "oem.img", dir).readBytes())
    }

    @Test fun zipSelectsOemImageOnly() = fixture { dir ->
        val zip = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use {
            it.putNextEntry(ZipEntry("fw/system.img")); it.write(ByteArray(5000)); it.closeEntry()
            it.putNextEntry(ZipEntry("fw/oem_signed")); it.write(image); it.closeEntry()
        } }.toByteArray()
        assertArrayEquals(image, OemPartitionSource.read(ByteArrayInputStream(zip), "fw.zip", dir).readBytes())
    }

    @Test fun zipWithoutOemFails() = fixture { dir ->
        val zip = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use {
            it.putNextEntry(ZipEntry("system.img")); it.write(image); it.closeEntry()
        } }.toByteArray()
        assertThrows(IllegalStateException::class.java) { OemPartitionSource.read(ByteArrayInputStream(zip), "fw.zip", dir) }
    }

    @Test fun tarMd5AndGzipAreRecognized() = fixture { dir ->
        val tar = ByteArrayOutputStream().also { out -> TarArchiveOutputStream(out).use {
            it.putArchiveEntry(TarArchiveEntry("oem.img").apply { size = image.size.toLong() }); it.write(image); it.closeArchiveEntry()
        } }.toByteArray()
        assertArrayEquals(image, OemPartitionSource.read(ByteArrayInputStream(tar), "AP.tar.md5", dir).readBytes())
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(image) } }.toByteArray()
        assertArrayEquals(image, OemPartitionSource.read(ByteArrayInputStream(gz), "oem.img.gz", dir).readBytes())
    }

    @Test fun tinyFileIsRejected() = fixture { dir ->
        assertThrows(IllegalArgumentException::class.java) { OemPartitionSource.read(ByteArrayInputStream(ByteArray(100)), "oem.img", dir) }
        assertTrue(dir.list()!!.isEmpty())
    }
}
