package app.aemu.importer

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class AcerBinTest {
    private fun source(bytes: ByteArray) = object : RandomSource {
        override val size = bytes.size.toLong()
        override fun read(pos: Long, dst: ByteBuffer) {
            val count = minOf(dst.remaining(), bytes.size - pos.toInt())
            if (count > 0) dst.put(bytes, pos.toInt(), count)
            while (dst.hasRemaining()) dst.put(0)
        }
    }

    /** Table of 0x40-byte records (count, offset, size, 0, 0, name at +0x14), then the payloads back to back. */
    private fun pack(files: List<Pair<String, ByteArray>>, trailer: Int = 0): ByteArray {
        val table = ByteBuffer.allocate(files.size * AcerBin.RECORD).order(ByteOrder.LITTLE_ENDIAN)
        var off = 0
        files.forEachIndexed { i, (name, data) ->
            val at = i * AcerBin.RECORD
            table.putInt(at, if (i == 0) files.size else 0)
            table.putInt(at + 4, off)
            table.putInt(at + 8, data.size)
            name.toByteArray(Charsets.US_ASCII).copyInto(table.array(), at + 0x14)
            off += data.size
        }
        return table.array() + files.flatMap { it.second.asList() }.toByteArray() + ByteArray(trailer)
    }

    private val mlf = ("[Package Download Profile]\r\nPROJECT = AUX\r\nPLATFORM = S1\r\nMODEL = AUX\r\nPACKAGE NUMBER = 15\r\n" +
        "[Flashloader]\r\nFLASHLOADER FILE = Acer_E110_1.008.00_EMEA.embflasher\r\n").toByteArray()

    private fun sample(extra: Int = 0): ByteArray {
        val files = ArrayList<Pair<String, ByteArray>>()
        files.add("Acer_E110_1.008.00_EMEA.mlf" to mlf)
        files.add("Acer_E110_1.008.00_EMEA.embflasher" to ByteArray(300) { 1 })
        files.add("rootfs.img" to ByteArray(5000) { 2 })
        files.add("system.img" to ByteArray(9000) { 3 })
        files.add("rootfs_ftm.img" to ByteArray(700) { 4 })
        for (i in 0 until extra) files.add("filler$i.img" to ByteArray(10) { 5 })
        return pack(files)
    }

    @Test fun recognisesATableFromTheFirstBytesOnly() {
        // the real package has 22 records = 1408 bytes of table, the importer only peeks at 1100 bytes
        val img = sample(extra = 17)
        assertTrue(AcerBin.probe(img.copyOf(1100)))
        assertTrue(AcerBin.probe(img))
    }

    @Test fun rejectsEverythingThatIsNotATable() {
        assertFalse(AcerBin.probe(ByteArray(0)))
        assertFalse(AcerBin.probe(ByteArray(4096)))
        assertFalse(AcerBin.probe(ByteArray(4096) { 0xff.toByte() }))
        assertFalse(AcerBin.probe(ByteArray(4096) { (it * 31 + 7).toByte() }))
        assertFalse(AcerBin.probe("PK\u0003\u0004".toByteArray() + ByteArray(4096)))
        val ok = sample()
        // wrong entry count
        val one = ok.copyOf(); ByteBuffer.wrap(one).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 1)
        assertFalse(AcerBin.probe(one))
        val huge = ok.copyOf(); ByteBuffer.wrap(huge).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 100000)
        assertFalse(AcerBin.probe(huge))
        // a gap between two payloads
        val gap = ok.copyOf(); ByteBuffer.wrap(gap).order(ByteOrder.LITTLE_ENDIAN).putInt(2 * AcerBin.RECORD + 4, 12345)
        assertFalse(AcerBin.probe(gap))
        // a name with a control character / text after the terminator
        val ctl = ok.copyOf(); ctl[AcerBin.RECORD + 0x14 + 3] = 7
        assertFalse(AcerBin.probe(ctl))
        val junk = ok.copyOf(); junk[AcerBin.RECORD + 0x14 + 43] = 'x'.code.toByte()
        assertFalse(AcerBin.probe(junk))
    }

    @Test fun entriesCarryAbsoluteOffsetsAndSizes() {
        val img = sample()
        val src = source(img)
        val entries = AcerBin.entries(src)
        assertEquals(listOf("Acer_E110_1.008.00_EMEA.mlf", "Acer_E110_1.008.00_EMEA.embflasher", "rootfs.img", "system.img", "rootfs_ftm.img"),
            entries.map { it.name })
        val table = 5L * AcerBin.RECORD
        assertEquals(table, entries[0].offset)
        assertEquals(table + mlf.size + 300, entries[2].offset)
        val system = entries.first { it.base == "system.img" }
        assertEquals(9000L, system.size)
        val b = ByteBuffer.allocate(9000)
        SliceSource(src, system.offset, system.size).read(0, b)
        assertTrue(b.array().all { it == 3.toByte() })
        assertEquals(img.size.toLong(), entries.last().offset + entries.last().size)
    }

    @Test fun ignoresTrailingPaddingButRejectsATruncatedDownload() {
        val padded = pack(listOf("a.mlf" to mlf, "system.img" to ByteArray(100)), trailer = 4)
        assertEquals(2, AcerBin.entries(source(padded)).size)
        val cut = padded.copyOf(padded.size - 4 - 10)
        try { AcerBin.entries(source(cut)); fail("accepted a truncated package") } catch (_: IOException) { }
        try { AcerBin.entries(source(ByteArray(10))); fail("accepted a tiny file") } catch (_: IOException) { }
    }

    @Test fun profileReadsOnlyTheGlobalSection() {
        val src = source(sample())
        val p = AcerBin.profile(src, AcerBin.entries(src))
        assertEquals("AUX", p["PROJECT"]); assertEquals("S1", p["PLATFORM"]); assertEquals("AUX", p["MODEL"])
        assertEquals("15", p["PACKAGE NUMBER"])
        assertFalse(p.containsKey("FLASHLOADER FILE"))
        // a package without a profile still parses
        val bare = source(pack(listOf("x.img" to ByteArray(10), "y.img" to ByteArray(10))))
        assertTrue(AcerBin.profile(bare, AcerBin.entries(bare)).isEmpty())
    }

    @Test fun rootfsIsTheNormalRootImageNotTheFactoryTestOne() {
        assertTrue(AcerBin.isRootfs("rootfs.img"))
        assertTrue(AcerBin.isRootfs("ROOTFS.IMG"))
        assertTrue(AcerBin.isRootfs("rootfs"))
        assertFalse(AcerBin.isRootfs("rootfs_ftm.img"))
        assertFalse(AcerBin.isRootfs("rootfs.img.ptt_header"))
        assertFalse(AcerBin.isRootfs("system.img"))
    }

    @Test fun packageNamesMapToThePartitionsTheImporterUses() {
        assertTrue(PartitionNames.isSystem("system.img"))
        assertFalse(PartitionNames.isSystem("modules.img"))
        assertFalse(PartitionNames.isSystem("hidden.img"))
        assertTrue(PartitionNames.isRecovery("recovery.img"))
    }
}
