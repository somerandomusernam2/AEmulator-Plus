package app.aemu.importer

import app.aemu.importer.tools.FirmwareToolset
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class EdlCombineTest {
    private fun fixture(run: (File) -> Unit) {
        val dir = Files.createTempDirectory("edl-test").toFile()
        try { run(dir) } finally { dir.deleteRecursively() }
    }
    private fun program(file: String, label: String, start: Long, sectors: Long) =
        """<program SECTOR_SIZE_IN_BYTES="512" filename="$file" label="$label" num_partition_sectors="$sectors" start_sector="$start"/>"""

    @Test fun picksTheXmlWhoseChunksArePresentAndMergesRelativeToTheFirstSector() = fixture { dir ->
        val a = ByteArray(1024) { 1 }; val b = ByteArray(512) { 2 }
        File(dir, "system_1.img").writeBytes(a)
        File(dir, "System_2.img").writeBytes(b)          // case differs from the XML
        File(dir, "rawprogram0.xml").writeText("<data>" + program("system_1.img", "system", 5000, 2) +
            program("system_2.img", "system", 5004, 1) + program("boot.img", "boot", 10, 1) + "</data>")
        File(dir, "rawprogram_unsparse0.xml").writeText("<data>" + program("system.img", "system", 5000, 99999) + "</data>")
        val xmls = listOf("rawprogram0.xml", "rawprogram_unsparse0.xml").map { File(dir, it) }
        val plan = FirmwareToolset.planEdl(xmls, dir.list()!!.toList(), "system") { }!!
        assertEquals("rawprogram0.xml", plan.xml.name)
        assertEquals(listOf("system_1.img", "system_2.img"), plan.files())
        val out = FirmwareToolset.combineEdl(plan, dir, File(dir, "out.img")) { }.readBytes()
        assertEquals(5 * 512, out.size)                  // offsets are relative to sector 5000, not absolute
        assertArrayEquals(a, out.copyOfRange(0, 1024))
        assertTrue(out.copyOfRange(1024, 2048).all { it == 0.toByte() })   // gap stays zero
        assertArrayEquals(b, out.copyOfRange(2048, 2560))
    }

    @Test fun noXmlForThePartitionYieldsNull() = fixture { dir ->
        File(dir, "rawprogram0.xml").writeText("<data>" + program("modem.img", "modem", 1, 1) + "</data>")
        File(dir, "modem.img").writeBytes(ByteArray(512))
        assertNull(FirmwareToolset.planEdl(listOf(File(dir, "rawprogram0.xml")), dir.list()!!.toList(), "system") { })
    }
}
