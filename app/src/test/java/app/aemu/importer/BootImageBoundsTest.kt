package app.aemu.importer

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class BootImageBoundsTest {
    @Test fun rejectsEmptyOrOutOfBoundsAndroidRamdisk() {
        val img = ByteArray(2048)
        "ANDROID!".toByteArray().copyInto(img)
        val header = ByteBuffer.wrap(img).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(36, 2048)
        assertNull(BootImage.ramdisk(img))
        header.putInt(16, Int.MAX_VALUE)
        assertNull(BootImage.ramdisk(img))
    }
    private fun cpio(name: String, bytes: ByteArray, size: Long = bytes.size.toLong(), mode: Long = 0x81a4): ByteArray {
        val fields = LongArray(13).apply { this[1] = mode; this[6] = size; this[11] = name.length + 1L }
        val header = "070701" + fields.joinToString("") { "%08x".format(it) }
        return ByteArrayOutputStream().also {
            it.write(header.toByteArray()); it.write(name.toByteArray()); it.write(0)
            while (it.size() % 4 != 0) it.write(0)
            it.write(bytes)
            while (it.size() % 4 != 0) it.write(0)
        }.toByteArray()
    }
    @Test fun readsValidRamdiskFile() {
        val result = BootImage.cpio(cpio("init.rc", "init".toByteArray()))
        assertEquals("init.rc", result.single().name)
        assertArrayEquals("init".toByteArray(), result.single().data)
    }
    @Test fun rejectsTruncatedOrOverflowingCpioData() {
        assertThrows(IllegalArgumentException::class.java) { BootImage.cpio(cpio("init.rc", byteArrayOf(1), 4096)) }
        assertThrows(IllegalArgumentException::class.java) { BootImage.cpio(cpio("init.rc", byteArrayOf(1), 0xffffffffL)) }
    }
    @Test fun stripsTrailingNulFromSymlinkTarget() {
        // gen_init_cpio counts the terminating NUL in a symlink's file size
        val link = BootImage.cpio(cpio("sbin/ueventd", "../init\u0000".toByteArray(), mode = 0xA1ff)).single()
        assertArrayEquals("../init".toByteArray(), link.data)
        FirmwareContainers.checkLink(link.name, String(link.data))
        // regular files keep their bytes untouched, including trailing zeros
        assertArrayEquals(byteArrayOf(1, 0), BootImage.cpio(cpio("a.bin", byteArrayOf(1, 0))).single().data)
    }
}
