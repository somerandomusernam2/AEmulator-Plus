package app.aemu.importer

import app.aemu.core.LegacyFirmware
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

/**
 * A bare ramdisk (ramdisk.img of an SDK-emulator package): gzip'd newc cpio, no boot image header, and for a pre-1.0 build the init
 * script in /etc. The Importer decides with exactly these calls (BootImage.decompress, BootImage.cpio, LegacyFirmware.hasInitScript).
 */
class BareRamdiskTest {
    private fun newc(entries: List<Triple<String, Int, ByteArray>>): ByteArray {
        val o = ByteArrayOutputStream()
        fun hex(v: Long) = o.write("%08x".format(v).toByteArray())
        fun pad() { while (o.size() % 4 != 0) o.write(0) }
        var ino = 1L
        for ((name, mode, data) in entries + Triple("TRAILER!!!", 0, ByteArray(0))) {
            o.write("070701".toByteArray())
            hex(ino++); hex(mode.toLong()); hex(0); hex(0); hex(1); hex(0)
            hex(data.size.toLong()); hex(0); hex(0); hex(0); hex(0)
            hex(name.length + 1L); hex(0)
            o.write(name.toByteArray()); o.write(0); pad()
            o.write(data); pad()
        }
        return o.toByteArray()
    }

    private fun gzip(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private val f = 0x81ed   // regular file, 0755
    private val legacy = listOf(
        Triple("init", f, "ELF".toByteArray()),
        Triple("etc/init.rc", f, "service {\n runtime {\n  exec /android/bin/runtime\n }\n}\n".toByteArray()),
        Triple("etc/default.prop", 0x81a4, "net.bt.name = Android\n".toByteArray()),
        Triple("sbin/sh", f, "sh".toByteArray()),
    )

    @Test fun gzippedRamdiskWithEtcInitRcIsRecognised() {
        val rd = BootImage.decompress(gzip(newc(legacy)))?.let { BootImage.cpio(it) }
        assertNotNull(rd)
        assertEquals(listOf("init", "etc/init.rc", "etc/default.prop", "sbin/sh"), rd!!.map { it.name })
        assertTrue(LegacyFirmware.hasInitScript(rd.map { it.name }))
        val rc = String(rd.first { it.name == "etc/init.rc" }.data)
        assertTrue(LegacyFirmware.isLegacyInitRc(rc))
        assertEquals("/android/bin/runtime", LegacyFirmware.parseInitRc(rc).services.single().argv.single())
    }

    @Test fun plainCpioWorksToo() {
        val rd = BootImage.decompress(newc(legacy))?.let { BootImage.cpio(it) }
        assertTrue(LegacyFirmware.hasInitScript(rd!!.map { it.name }))
    }

    @Test fun aRamdiskWithRootInitRcIsStillRecognised() {
        val rd = BootImage.decompress(gzip(newc(listOf(Triple("init", f, ByteArray(1)), Triple("init.rc", f, "on boot\n".toByteArray())))))?.let { BootImage.cpio(it) }
        assertTrue(LegacyFirmware.hasInitScript(rd!!.map { it.name }))
        assertFalse(LegacyFirmware.isLegacyInitRc(String(rd.first { it.name == "init.rc" }.data)))
    }

    @Test fun aCpioWithoutAnyInitScriptIsNotARamdisk() {
        val rd = BootImage.decompress(gzip(newc(listOf(Triple("sbin/sh", f, ByteArray(1)), Triple("etc/hosts", 0x81a4, ByteArray(2))))))?.let { BootImage.cpio(it) }
        assertFalse(LegacyFirmware.hasInitScript(rd!!.map { it.name }))
    }
}
