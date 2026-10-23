/* Added for AEmulator Plus, 2026-10-23: tests for the ISO 9660 reader. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.importer

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class Iso9660Test {
    private class Mem(val data: ByteArray) : RandomSource {
        override val size get() = data.size.toLong()
        override fun read(pos: Long, dst: ByteBuffer) {
            for (i in 0 until dst.remaining()) { val p = pos + i; dst.put(if (p < data.size) data[p.toInt()] else 0) }
        }
    }

    /** One directory record: [name] is the raw identifier, [sysUse] the system use area (Rock Ridge). */
    private fun record(name: ByteArray, lba: Int, size: Int, flags: Int, sysUse: ByteArray = ByteArray(0)): ByteArray {
        val pad = if (name.size % 2 == 0) 1 else 0
        val len = 33 + name.size + pad + sysUse.size
        val b = ByteArray(len + len % 2)
        b[0] = b.size.toByte()
        ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(2, lba).putInt(10, size)
        b[25] = flags.toByte(); b[32] = name.size.toByte()
        name.copyInto(b, 33); sysUse.copyInto(b, 33 + name.size + pad)
        return b
    }
    private fun rr(name: String): ByteArray { val n = name.toByteArray(); return byteArrayOf('N'.code.toByte(), 'M'.code.toByte(), (5 + n.size).toByte(), 1, 0) + n }

    private class Dir(val lba: Int, val records: List<ByteArray>)

    /** A volume: sector 16 = primary descriptor, 17 = terminator, directories and file data after them. */
    private fun image(sectors: Int, root: Dir, others: List<Dir>, files: Map<Int, ByteArray>): ByteArray {
        val img = ByteArray(sectors * 2048)
        fun put(sector: Int, b: ByteArray) = b.copyInto(img, sector * 2048)
        val pvd = ByteArray(2048)
        pvd[0] = 1; "CD001".toByteArray().copyInto(pvd, 1); pvd[6] = 1
        record(byteArrayOf(0), root.lba, 2048, 2).copyInto(pvd, 156)
        put(16, pvd)
        put(17, ByteArray(2048).also { it[0] = 255.toByte(); "CD001".toByteArray().copyInto(it, 1); it[6] = 1 })
        for (d in listOf(root) + others) {
            val out = ByteArrayOutputStream()
            out.write(record(byteArrayOf(0), d.lba, 2048, 2)); out.write(record(byteArrayOf(1), root.lba, 2048, 2))
            d.records.forEach { out.write(it) }
            put(d.lba, out.toByteArray())
        }
        files.forEach { (sector, data) -> put(sector, data) }
        return img
    }

    @Test fun listsFilesWithVersionSuffixAndTrailingDotRemoved() {
        val img = image(40, Dir(20, listOf(
            record("SYSTEM.IMG;1".toByteArray(), 30, 5, 0),
            record("README.".toByteArray(), 31, 3, 0),
        )), emptyList(), mapOf(30 to "hello".toByteArray(), 31 to "abc".toByteArray()))
        assertTrue(Iso9660.probe(Mem(img)))
        val iso = Iso9660(Mem(img))
        val e = iso.entries()
        assertEquals(listOf("SYSTEM.IMG", "README"), e.map { it.path })
        val buf = ByteBuffer.allocate(5); iso.open(e[0]).read(0, buf)
        assertEquals("hello", String(buf.array()))
    }

    @Test fun rockRidgeNameWinsOverTheIsoIdentifier() {
        val img = image(40, Dir(20, listOf(record("SYSTEM.SFS;1".toByteArray(), 30, 4, 0, rr("system.sfs")))),
            emptyList(), mapOf(30 to "hsqs".toByteArray()))
        assertEquals("system.sfs", Iso9660(Mem(img)).entries().single().name)
    }

    @Test fun subdirectoriesGetTheirPath() {
        val img = image(40, Dir(20, listOf(record("android-5.1".toByteArray(), 21, 2048, 2))),
            listOf(Dir(21, listOf(record("ramdisk.img".toByteArray(), 30, 2, 0)))), mapOf(30 to byteArrayOf(1, 2)))
        val e = Iso9660(Mem(img)).entries()
        assertEquals(listOf("android-5.1", "android-5.1/ramdisk.img"), e.map { it.path })
        assertTrue(e[0].isDir); assertEquals(2L, e[1].size)
    }

    @Test fun directoryLoopIsNotFollowedTwice() {
        // the sub-directory record points back at the root
        val img = image(40, Dir(20, listOf(record("loop".toByteArray(), 20, 2048, 2))), emptyList(), emptyMap())
        assertTrue(Iso9660(Mem(img)).entries().none { it.path.startsWith("loop/") })
    }

    @Test fun multiExtentFileIsOneSource() {
        val img = image(40, Dir(20, listOf(
            record("big.bin".toByteArray(), 30, 2048, 0x80),   // more extents follow
            record("big.bin".toByteArray(), 31, 3, 0),
        )), emptyList(), mapOf(30 to ByteArray(2048) { 7 }, 31 to byteArrayOf(8, 9, 10)))
        val iso = Iso9660(Mem(img))
        val e = iso.entries().single()
        assertEquals(2051L, e.size)
        val buf = ByteBuffer.allocate(4); iso.open(e).read(2047, buf)
        assertArrayEquals(byteArrayOf(7, 8, 9, 10), buf.array())
    }

    @Test fun fileOutsideTheImageIsRejected() {
        val img = image(40, Dir(20, listOf(record("cut.bin".toByteArray(), 39, 99999, 0))), emptyList(), emptyMap())
        val iso = Iso9660(Mem(img))
        assertThrows(java.io.IOException::class.java) { iso.open(iso.entries().single()) }
    }

    @Test fun otherDataIsNotAnIso() {
        assertFalse(Iso9660.probe(Mem(ByteArray(64 * 1024))))
        val ext4Like = ByteArray(64 * 1024).also { it[1024 + 56] = 0x53; it[1024 + 57] = 0xEF.toByte() }
        assertFalse(Iso9660.probe(Mem(ext4Like)))
    }
}
