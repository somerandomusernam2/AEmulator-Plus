package app.aemu.importer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.*
import org.junit.Test

class CscPackageTest {
    private val customer = "<CustomerData><GeneralInfo><SalesCode>SER</SalesCode></GeneralInfo></CustomerData>".toByteArray()
    private val sales = "SER\n".toByteArray()

    private fun fixture(run: (File) -> Unit) {
        val dir = Files.createTempDirectory("csc-test").toFile()
        try { run(dir) } finally { dir.deleteRecursively() }
    }

    private fun cscZip(): ByteArray = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use {
        it.putNextEntry(ZipEntry("system/csc/customer.xml")); it.write(customer); it.closeEntry()
        it.putNextEntry(ZipEntry("system/csc/sales_code.dat")); it.write(sales); it.closeEntry()
        it.putNextEntry(ZipEntry("system/app/Swype.apk")); it.write(ByteArray(3000)); it.closeEntry()
        it.putNextEntry(ZipEntry("system/lib/libSwypeCore.so")); it.write(ByteArray(3000)); it.closeEntry()
    } }.toByteArray()

    /** A FAT16 volume (4100 clusters of 512 bytes, the smallest that is FAT16) holding the given files in its root. */
    private fun fat16(files: Map<String, ByteArray>): ByteArray {
        val bps = 512; val clusters = 4100; val fatSz = (clusters + 2) * 2 / bps + 1; val rootSectors = 2
        val img = ByteArray((1 + fatSz + rootSectors + clusters) * bps)
        fun w16(o: Int, v: Int) { img[o] = v.toByte(); img[o + 1] = (v shr 8).toByte() }
        img[0] = 0xEB.toByte(); img[1] = 0x3C; img[2] = 0x90.toByte()
        w16(11, bps); img[13] = 1; w16(14, 1); img[16] = 1; w16(17, rootSectors * bps / 32)
        w16(19, 1 + fatSz + rootSectors + clusters); img[21] = 0xF8.toByte(); w16(22, fatSz)
        img[510] = 0x55; img[511] = 0xAA.toByte()
        val fat = bps; val root = (1 + fatSz) * bps; val data = root + rootSectors * bps
        w16(fat, 0xFFF8); w16(fat + 2, 0xFFFF)
        var cluster = 2; var entry = 0
        for ((name, bytes) in files) {
            val n = (bytes.size + bps - 1) / bps
            for (i in 0 until n) {
                val c = cluster + i
                w16(fat + c * 2, if (i == n - 1) 0xFFFF else c + 1)
            }
            System.arraycopy(bytes, 0, img, data + (cluster - 2) * bps, bytes.size)
            val e = root + entry * 32
            val dot = name.indexOf('.')
            val base = name.substring(0, dot).uppercase().padEnd(8); val ext = name.substring(dot + 1).uppercase().padEnd(3)
            (base + ext).toByteArray(Charsets.ISO_8859_1).copyInto(img, e)
            img[e + 12] = 0x18 // all-lower-case name in the short entry
            w16(e + 26, cluster)
            for (k in 0 until 4) img[e + 28 + k] = (bytes.size shr (8 * k)).toByte()
            cluster += n; entry++
        }
        return img
    }

    @Test fun targetMapsOnlyCscPaths() {
        assertEquals("system/csc/customer.xml", CscPackage.target("system/csc/customer.xml"))
        assertEquals("system/csc/customer.xml", CscPackage.target("csc/customer.xml"))
        assertEquals("system/csc/sales_code.dat", CscPackage.target("SALES_CODE.DAT"))
        assertEquals("system/csc/SER/customer.xml", CscPackage.target("./system/csc/SER/customer.xml"))
        assertNull(CscPackage.target("system/app/Swype.apk"))
        assertNull(CscPackage.target("system/csc/../../bin/su"))
        assertNull(CscPackage.target("system/csc"))
        assertNull(CscPackage.target("vendor/csc/customer.xml"))
    }

    @Test fun containerNamesNeedTheCscWord() {
        for (n in listOf("GT-I5500-CSC-SERJJ2_tar.md5", "csc.rfs", "CSC_SER_GT-I9000.tar.md5", "csc.zip", "GT-I5500-CSC-SERJJ2.tar"))
            assertTrue(n, CscPackage.isCscContainer(n))
        for (n in listOf("CSCVersion.txt", "system.rfs", "cscdata.img", "CSC.apk", "PDA_I5500.tar.md5"))
            assertFalse(n, CscPackage.isCscContainer(n))
    }

    @Test fun zipKeepsOnlyCscFiles() = fixture { dir ->
        val r = CscPackage.read(ByteArrayInputStream(cscZip()), "csc.zip", dir)
        assertEquals(setOf("system/csc/customer.xml", "system/csc/sales_code.dat"), r.keys)
        assertArrayEquals(customer, r["system/csc/customer.xml"])
    }

    @Test fun fatVolumeWithNestedZipIsRead() = fixture { dir ->
        val r = CscPackage.read(ByteArrayInputStream(fat16(mapOf("csc.zip" to cscZip(), "other.txt" to ByteArray(10)))), "csc.rfs", dir)
        assertArrayEquals(sales, r["system/csc/sales_code.dat"])
        assertEquals(2, r.size)
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test fun odinTarMd5WithFatImageIsRead() = fixture { dir ->
        val rfs = fat16(mapOf("csc.zip" to cscZip()))
        val tar = ByteArrayOutputStream().also { out -> TarArchiveOutputStream(out).use {
            it.putArchiveEntry(TarArchiveEntry("csc.rfs").apply { size = rfs.size.toLong() }); it.write(rfs); it.closeArchiveEntry()
        } }.toByteArray()
        val r = CscPackage.read(ByteArrayInputStream(tar), "GT-I5500-CSC-SERJJ2_tar.md5", dir)
        assertArrayEquals(customer, r["system/csc/customer.xml"])
    }

    @Test fun packageWithoutCscDataFails() = fixture { dir ->
        val zip = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use {
            it.putNextEntry(ZipEntry("system/app/Swype.apk")); it.write(ByteArray(100)); it.closeEntry()
        } }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { CscPackage.read(ByteArrayInputStream(zip), "csc.zip", dir) }
        assertThrows(java.io.IOException::class.java) { CscPackage.read(ByteArrayInputStream(ByteArray(2000)), "csc.bin", dir) }
    }
}
