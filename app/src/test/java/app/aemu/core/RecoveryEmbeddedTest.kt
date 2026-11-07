/* AEmulator Plus addition. GPL-3.0; see LICENSE. */
package app.aemu.core

import app.aemu.importer.BootImage
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

/**
 * Early Samsung kernels (Galaxy S GT-I9000 and its generation) keep the recovery in the kernel's built-in initramfs, next to
 * Android's init.rc: one cpio, /init picks /recovery.rc or /init.rc by `bootmode=`. The importer takes that ramdisk as the boot
 * ramdisk and, when the firmware has no recovery image, as the recovery as well.
 */
class RecoveryEmbeddedTest {
    private val file = 0x81ed   // regular file, 0755
    private val dir = 0x41ed
    private val link = 0xA1ff

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

    private val androidInit = "service zygote /system/bin/app_process -Xzygote /system/bin --zygote --start-system-server\n".toByteArray()
    private val recoveryOnlyInit = "service recovery /sbin/recovery\n".toByteArray()

    /** what GT-I9000 JW4's zImage holds, reduced to the files that matter */
    private fun samsung(initRc: ByteArray = androidInit) = listOf(
        Triple("/init", file, "ELF".toByteArray()),
        Triple("/init.rc", file, initRc),
        Triple("/recovery.rc", 0x81a4, "service recovery /sbin/recovery\n".toByteArray()),
        Triple("/sbin", dir, ByteArray(0)),
        Triple("/sbin/ueventd", link, "../init".toByteArray()),
        Triple("/sbin/recovery", file, "ELF".toByteArray()),
        Triple("/recovery", file, "ELF".toByteArray()),
        Triple("/res/recovery.fstab", 0x81a4, "[device]\n/system\trfs\t/dev/block/stl9\tdefault\tdefault\n".toByteArray()),
    )

    private fun rd(entries: List<Triple<String, Int, ByteArray>>) = BootImage.cpio(newc(entries))

    @Test fun androidAndRecoveryInOneRamdiskIsEmbeddedRecovery() {
        val r = rd(samsung())
        assertTrue(RecoveryImage.isEmbedded(r))
        assertEquals("recovery.rc", RecoveryImage.embeddedScript(r))
    }

    @Test fun recoveryImageOfItsOwnIsNotEmbedded() {
        // a recovery ramdisk (TWRP, CWM, stock) starts no zygote: it is the recovery image itself, not a boot image holding one
        assertFalse(RecoveryImage.isEmbedded(rd(samsung(recoveryOnlyInit))))
        // and one with no init.rc at all
        assertFalse(RecoveryImage.isEmbedded(rd(samsung().filter { it.first != "/init.rc" })))
    }

    @Test fun plainBootRamdiskHasNoEmbeddedRecovery() {
        val r = rd(samsung().filter { !it.first.contains("recovery") })
        assertFalse(RecoveryImage.isEmbedded(r))
        assertNull(RecoveryImage.embeddedScript(r))
    }

    @Test fun aDirectoryNamedSbinRecoveryIsNotTheBinary() {
        assertFalse(RecoveryImage.isEmbedded(rd(samsung().map { if (it.first == "/sbin/recovery") Triple(it.first, dir, ByteArray(0)) else it })))
    }

    @Test fun zImageWithBuiltInInitramfsIsFoundAndHoldsTheRecovery() {
        // zImage = decompressor stub, then the gzip'd kernel Image (>= 1 MB) with the uncompressed initramfs inside it
        val kernel = ByteArray(1_200_000) + newc(samsung())
        val zImage = ByteArray(64) + gzip(kernel) + ByteArray(53)
        val r = BootImage.ramdisk(zImage)
        assertNotNull(r)
        assertTrue(r!!.any { it.name == "init.rc" })
        assertTrue(RecoveryImage.isEmbedded(r))
        // names come back without the leading slash of gen_init_cpio, and the symlink target without its NUL
        assertEquals("../init", String(r.first { it.name == "sbin/ueventd" }.data))
    }
}
