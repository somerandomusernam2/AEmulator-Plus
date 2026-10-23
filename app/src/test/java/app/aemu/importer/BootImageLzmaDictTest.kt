package app.aemu.importer

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAOutputStream

/**
 * A raw Samsung zImage (no "ANDROID!" header) whose kernel is LZMA-packed with a 64 MiB dictionary (`lzma -9`, the CyanogenMod
 * i9100 kernels). The decoder's memory limit used to equal the dictionary exactly, so it was refused and the firmware's
 * boot.img was never imported.
 */
class BootImageLzmaDictTest {
    private fun cpio(name: String, bytes: ByteArray, mode: Long = 0x81a4): ByteArray {
        val fields = LongArray(13).apply { this[1] = mode; this[6] = bytes.size.toLong(); this[11] = name.length + 1L }
        return ByteArrayOutputStream().also {
            it.write(("070701" + fields.joinToString("") { f -> "%08x".format(f) }).toByteArray())
            it.write(name.toByteArray()); it.write(0)
            while (it.size() % 4 != 0) it.write(0)
            it.write(bytes)
            while (it.size() % 4 != 0) it.write(0)
        }.toByteArray()
    }
    private fun trailer() = cpio("TRAILER!!!", ByteArray(0), 0)

    private fun lzma(data: ByteArray, dict: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val opts = LZMA2Options().apply { dictSize = dict }
        LZMAOutputStream(out, opts, -1).use { it.write(data) }   // .lzma ("alone") stream with an end marker, like `lzma -9`
        return out.toByteArray()
    }

    /** zImage-like blob: 4 KiB of non-magic bytes, then the packed kernel (1.2 MiB of filler + a two-stage initramfs). */
    private fun zImage(dict: Int): ByteArray {
        val boot = cpio("init.rc", "on early-init\n    mkdir /cache/dalvik-cache 0771 system system\n".toByteArray()) + trailer()
        val stage1 = cpio("stage1/boot.cpio", boot) + trailer()
        val kernel = ByteArray(1_200_000) + stage1
        return ByteArray(4096) { 0x11 } + lzma(kernel, dict)
    }

    @Test fun readsRamdiskFromKernelPackedWithA64MiBDictionary() {
        val rd = BootImage.ramdisk(zImage(64 shl 20))
        assertNotNull(rd)
        assertTrue(rd!!.any { it.name == "init.rc" })
    }

    @Test fun smallDictionaryStillWorks() {
        assertNotNull(BootImage.ramdisk(zImage(8 shl 20)))
    }
}
