package app.aemu.importer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.zip.GZIPOutputStream

class FirmwareFormatsTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Bytes(val b: ByteArray) : RandomSource {
        override val size: Long = b.size.toLong()
        override fun read(pos: Long, dst: ByteBuffer) {
            var p = pos
            while (dst.hasRemaining()) dst.put(if (p < b.size) b[(p++).toInt()] else { p++; 0 })
        }
    }

    private fun random(n: Int, seed: Long = n.toLong()) = ByteArray(n).also { Random(seed).nextBytes(it) }

    // ------------------------------------------------------------------ Huawei UPDATE.APP

    private fun updateApp(vararg files: Pair<String, ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(ByteArray(92))
        for ((i, f) in files.withIndex()) {
            val (name, data) = f
            val blocks = (data.size + 4095) / 4096
            val crc = ByteArray(blocks * 2)
            val h = ByteBuffer.allocate(98).order(ByteOrder.LITTLE_ENDIAN)
            h.putInt(0xA55AAA55.toInt()).putInt(98 + crc.size).putInt(1).put("HW7x27\u0000\u0000".toByteArray())
            h.putInt(i).putInt(data.size).put("2019.01.01".toByteArray().copyOf(16)).put("12.00.00".toByteArray().copyOf(16))
            h.put(name.toByteArray().copyOf(16)).put(ByteArray(16)).putShort(0).putShort(4096).putShort(0)
            o.write(h.array()); o.write(crc); o.write(data)
            while (o.size() % 4 != 0) o.write(0)
        }
        return o.toByteArray()
    }

    @Test fun huaweiUpdateAppListsItsImagesInPlace() {
        val system = random(10_001)                     // not a multiple of 4: the next header is aligned
        val boot = random(4_099)
        val cust = random(7)
        val app = updateApp("SYSTEM" to system, "BOOT" to boot, "CUST" to cust)
        assertTrue(HuaweiApp.probe(app.copyOf(1100)))
        val src = Bytes(app)
        val list = HuaweiApp.entries(src)
        assertEquals(listOf("SYSTEM", "BOOT", "CUST"), list.map { it.name })
        for ((e, want) in list.zip(listOf(system, boot, cust))) {
            assertEquals(want.size.toLong(), e.size)
            val got = ByteBuffer.allocate(e.size.toInt()).also { src.read(e.offset, it) }.array()
            assertArrayEquals(want, got)
        }
    }

    @Test fun otherFormatsAreNotTakenForUpdateApp() {
        assertFalse(HuaweiApp.probe(ByteArray(1100)))                          // a raw ext4 image starts with zeros
        assertFalse(HuaweiApp.probe(random(1100)))
        assertFalse(HuaweiApp.probe("CrAU".toByteArray() + ByteArray(1096)))
        assertTrue(HuaweiApp.entries(Bytes(ByteArray(4096))).isEmpty())
    }

    // ------------------------------------------------------------------ boot images

    private fun cpio(vararg files: Pair<String, ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        fun entry(name: String, mode: Int, data: ByteArray) {
            val n = name.toByteArray() + 0
            val fields = intArrayOf(0, mode, 0, 0, 1, 0, data.size, 0, 0, 0, 0, n.size, 0)
            o.write(("070701" + fields.joinToString("") { "%08X".format(it) }).toByteArray())
            o.write(n); while (o.size() % 4 != 0) o.write(0)
            o.write(data); while (o.size() % 4 != 0) o.write(0)
        }
        for ((name, data) in files) entry(name, 0x81a4, data)
        entry("TRAILER!!!", 0, ByteArray(0))
        return o.toByteArray()
    }

    private fun gzip(b: ByteArray) = ByteArrayOutputStream().also { GZIPOutputStream(it).use { g -> g.write(b) } }.toByteArray()

    private val initRc = "on init\n    start ueventd\n".toByteArray()
    private val ramdisk = cpio("init.rc" to initRc, "default.prop" to "ro.secure=0\n".toByteArray())

    @Test fun bootImageHeaderV0() {
        val page = 2048
        val kernel = random(3000)
        val rd = gzip(ramdisk)
        val h = ByteBuffer.allocate(page).order(ByteOrder.LITTLE_ENDIAN)
        h.put("ANDROID!".toByteArray()).putInt(kernel.size).putInt(0x8000).putInt(rd.size).putInt(0x1000000)
        h.putInt(0).putInt(0).putInt(0x100).putInt(page)
        val img = h.array() + kernel.copyOf(2 * page) + rd.copyOf(((rd.size + page - 1) / page) * page)
        val e = BootImage.ramdisk(img)
        assertNotNull(e)
        assertArrayEquals(initRc, e!!.first { it.name == "init.rc" }.data)
    }

    @Test fun bootImageHeaderV3AsOnDevicesLaunchedWith11() {
        val kernel = random(5000)
        val rd = gzip(ramdisk)
        val h = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        h.put("ANDROID!".toByteArray()).putInt(kernel.size).putInt(rd.size).putInt(0).putInt(1580)
        h.put(ByteArray(16)).putInt(3)
        val img = h.array() + kernel.copyOf(8192) + rd.copyOf(((rd.size + 4095) / 4096) * 4096)
        val e = BootImage.ramdisk(img)
        assertNotNull(e)
        assertArrayEquals(initRc, e!!.first { it.name == "init.rc" }.data)
        assertEquals(setOf("init.rc", "default.prop"), e.map { it.name }.toSet())
    }

    @Test fun bareRamdiskAsHuaweiShipsIt() {
        assertArrayEquals(initRc, BootImage.ramdisk(gzip(ramdisk))!!.first { it.name == "init.rc" }.data)
        assertArrayEquals(initRc, BootImage.ramdisk(ramdisk)!!.first { it.name == "init.rc" }.data)
    }

    // ------------------------------------------------------------------ payload.bin, zstd

    private fun varint(o: ByteArrayOutputStream, v0: Long) { var v = v0; while (v >= 0x80) { o.write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }; o.write(v.toInt()) }
    private fun field(o: ByteArrayOutputStream, n: Int, v: Long) { varint(o, (n shl 3).toLong()); varint(o, v) }
    private fun field(o: ByteArrayOutputStream, n: Int, b: ByteArray) { varint(o, ((n shl 3) or 2).toLong()); varint(o, b.size.toLong()); o.write(b) }
    private fun msg(f: (ByteArrayOutputStream) -> Unit) = ByteArrayOutputStream().also(f).toByteArray()

    @Test fun payloadWithZstdOperations() {
        val blocks = List(3) { random(4096, it + 7L) }
        val xz = ByteArrayOutputStream().also { org.tukaani.xz.XZOutputStream(it, org.tukaani.xz.LZMA2Options()).use { x -> x.write(blocks[2]) } }.toByteArray()
        val zst = com.github.luben.zstd.Zstd.compress(blocks[1])
        val blobs = listOf(0 to blocks[0], 14 to zst, 8 to xz)
        var off = 0L
        val part = msg { p ->
            field(p, 1, "system".toByteArray())
            for ((i, b) in blobs.withIndex()) {
                field(p, 8, msg { op ->
                    field(op, 1, b.first.toLong()); field(op, 2, off); field(op, 3, b.second.size.toLong())
                    field(op, 6, msg { ext -> field(ext, 1, i.toLong()); field(ext, 2, 1) })
                })
                off += b.second.size
            }
            field(p, 7, msg { info -> field(info, 1, 3 * 4096L) })
        }
        val manifest = msg { m -> field(m, 13, part) }
        val head = ByteBuffer.allocate(24).put("CrAU".toByteArray()).putLong(2).putLong(manifest.size.toLong()).putInt(0)
        val payload = head.array() + manifest + blobs.fold(ByteArray(0)) { a, b -> a + b.second }
        assertTrue(PayloadReader.probe(payload))
        val out = File(tmp.root, "system.img")
        assertTrue(PayloadReader.extractPartition(Bytes(payload), "system", out))
        assertArrayEquals(blocks[0] + blocks[1] + blocks[2], out.readBytes())
    }

    @Test fun zstdStreamAsTheImporterReadsIt() {
        val data = random(300_000).copyOf(600_000)                   // half random, half zeros
        val z = com.github.luben.zstd.Zstd.compress(data, 3)
        assertEquals(0x28, z[0].toInt() and 0xff); assertEquals(0xfd, z[3].toInt() and 0xff)
        val back = org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream(ByteArrayInputStream(z)).use { it.readBytes() }
        assertArrayEquals(data, back)
    }
}
