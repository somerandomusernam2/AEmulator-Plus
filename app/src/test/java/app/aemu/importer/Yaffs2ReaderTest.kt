package app.aemu.importer

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class Yaffs2ReaderTest {
    private fun source(bytes: ByteArray) = object : RandomSource {
        override val size = bytes.size.toLong()
        override fun read(pos: Long, dst: ByteBuffer) {
            val count = minOf(dst.remaining(), bytes.size - pos.toInt())
            if (count > 0) dst.put(bytes, pos.toInt(), count)
            while (dst.hasRemaining()) dst.put(0)
        }
    }
    private fun header(id: Int, parent: Int, name: String, type: Int = 3, size: Int = 0,
                       alias: String = "", target: Int = 0, page: Int = 2048,
                       order: ByteOrder = ByteOrder.LITTLE_ENDIAN, seq: Int = 0x1000,
                       tagOffset: Int = 0, extra: Boolean = false, shrink: Boolean = false,
                       shadows: Int = 0): ByteArray {
        val b = ByteBuffer.wrap(ByteArray(page + page / 32) { 0xff.toByte() }).order(order)
        b.putInt(0, type); b.putInt(4, parent)
        for (i in 10 until 266) b.put(i, 0)
        name.toByteArray().copyInto(b.array(), 10)
        b.putInt(268, if (type == 3) 0x41ed else 0x81ed)
        b.putInt(292, size); b.putInt(296, target)
        for (i in 300 until 460) b.put(i, 0)
        alias.toByteArray().copyInto(b.array(), 300)
        if (shadows != 0) b.putInt(504, shadows)
        val tag = page + tagOffset
        if (extra) {
            var chunk = 0x80000000.toInt() or parent
            if (shrink) chunk = chunk or 0x40000000
            if (shadows != 0) chunk = chunk or 0x20000000
            b.putInt(tag, seq); b.putInt(tag + 4, id or (type shl 28)); b.putInt(tag + 8, chunk)
            b.putInt(tag + 12, if (type == 1) size else 0)
        } else {
            if (shrink) b.putInt(508, 1)
            b.putInt(tag, seq); b.putInt(tag + 4, id); b.putInt(tag + 8, 0)
        }
        return b.array()
    }
    private fun data(id: Int, chunk: Int, bytes: ByteArray, page: Int = 2048,
                     order: ByteOrder = ByteOrder.LITTLE_ENDIAN, seq: Int = 0x1000,
                     tagOffset: Int = 0): ByteArray {
        val b = ByteBuffer.wrap(ByteArray(page + page / 32) { 0xff.toByte() }).order(order)
        bytes.copyInto(b.array())
        val tag = page + tagOffset
        b.putInt(tag, seq); b.putInt(tag + 4, id); b.putInt(tag + 8, chunk); b.putInt(tag + 12, bytes.size)
        return b.array()
    }
    private fun erased(page: Int = 2048) = ByteArray(page + page / 32) { 0xff.toByte() }
    private fun image(vararg pages: ByteArray) = source(pages.reduce { a, b -> a + b })
    private fun rejects(src: RandomSource) {
        try { Yaffs2Reader(src); fail("accepted malformed image") } catch (_: IOException) { }
    }
    private fun files(fs: Yaffs2Reader): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        fs.walk { path, node ->
            if (node.type == 1 || node.type == 4) { val o = ByteArrayOutputStream(); fs.copy(node, o); out[path] = o.toByteArray() }
            else out[path] = ByteArray(0)
        }
        return out
    }

    @Test fun readsFilesDirectoriesLinksAndHardLinksWithoutLoadingFileContents() {
        val bytes = "test file".toByteArray()
        val fs = Yaffs2Reader(image(header(1, 1, ""), header(258, 257, "file", 1, bytes.size),
            data(258, 1, bytes), header(257, 1, "bin"), header(259, 257, "link", 2, alias = "/system/bin/file"),
            header(260, 257, "hard", 4, target = 258)))
        val found = LinkedHashMap<String, Yaffs2Reader.Node>()
        fs.walk { path, node -> found[path] = node }
        assertEquals(setOf("bin", "bin/file", "bin/link", "bin/hard"), found.keys)
        assertEquals("/system/bin/file", found.getValue("bin/link").alias)
        for (path in listOf("bin/file", "bin/hard")) {
            val out = ByteArrayOutputStream(); fs.copy(found.getValue(path), out)
            assertArrayEquals(bytes, out.toByteArray())
            assertEquals(0x81ed, fs.fileMode(found.getValue(path)))
        }
    }
    @Test fun detectsBothGeometriesAndByteOrdersAndOrdersChunks() {
        for (page in listOf(2048, 4096)) for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val first = ByteArray(page) { (it % 251).toByte() }; val last = byteArrayOf(5, 6, 7)
            val fs = Yaffs2Reader(image(header(1, 1, "", page = page, order = order),
                header(257, 1, "file", 1, page + 3, page = page, order = order),
                data(257, 2, last, page, order), data(257, 1, first, page, order)))
            assertEquals(page, fs.geometry.pageSize); assertEquals(order, fs.geometry.order)
            fs.walk { _, node -> val out = ByteArrayOutputStream(); fs.copy(node, out); assertArrayEquals(first + last, out.toByteArray()) }
        }
    }
    @Test fun detectsTagOffsetInsideTheSpareArea() {
        for (offset in listOf(2, 8)) {
            val fs = Yaffs2Reader(image(header(1, 1, "", tagOffset = offset),
                header(257, 1, "f", 1, 2, tagOffset = offset), data(257, 1, byteArrayOf(9, 8), tagOffset = offset)))
            assertEquals(offset, fs.geometry.tagOffset)
            assertArrayEquals(byteArrayOf(9, 8), files(fs).getValue("f"))
        }
    }
    @Test fun rawNandNewestSequenceWinsRegardlessOfPagePosition() {
        // newer block (seq 0x1002) physically BEFORE the older block (seq 0x1001)
        val fs = Yaffs2Reader(image(
            header(1, 1, "", seq = 0x1002), header(257, 1, "new-name", 1, 3, seq = 0x1002),
            data(257, 1, byteArrayOf(7, 7, 7), seq = 0x1002), erased(),
            header(1, 1, "", seq = 0x1001), header(257, 1, "old-name", 1, 3, seq = 0x1001),
            data(257, 1, byteArrayOf(1, 1, 1), seq = 0x1001)))
        val f = files(fs)
        assertEquals(setOf("new-name"), f.keys)
        assertArrayEquals(byteArrayOf(7, 7, 7), f.getValue("new-name"))
    }
    @Test fun rawNandRenameAndDeletionAreReplayed() {
        val fs = Yaffs2Reader(image(
            header(1, 1, ""), header(257, 1, "dir"), header(258, 257, "a", 1, 1, seq = 0x1001), data(258, 1, byteArrayOf(1), seq = 0x1001),
            header(259, 1, "gone", 1, 1, seq = 0x1001), data(259, 1, byteArrayOf(2), seq = 0x1001),
            header(258, 1, "moved", 1, 1, seq = 0x1002),   // rename + move to root
            header(259, 4, "gone", 1, 1, seq = 0x1002),    // parent 4 = deleted
            header(260, 3, "unlinked", 1, 0, seq = 0x1002))) // parent 3 = unlinked
        assertEquals(setOf("dir", "moved"), files(fs).keys)
        assertArrayEquals(byteArrayOf(1), files(fs).getValue("moved"))
    }
    @Test fun rawNandShrinkDropsOlderChunksBeyondNewSizeAndExtendedHeadersWork() {
        val big = ByteArray(2048) { 1 }; val second = ByteArray(2048) { 2 }
        val fs = Yaffs2Reader(image(
            header(1, 1, "", extra = true), header(257, 1, "f", 1, 4096, extra = true, seq = 0x1001),
            data(257, 1, big, seq = 0x1001), data(257, 2, second, seq = 0x1001),
            header(257, 1, "f", 1, 2048, extra = true, shrink = true, seq = 0x1002),   // truncate
            header(257, 1, "f", 1, 4096, extra = true, seq = 0x1003),                  // extend again
            data(257, 1, big, seq = 0x1003)))
        val out = files(fs).getValue("f")
        assertEquals(4096, out.size)
        assertArrayEquals(big, out.copyOfRange(0, 2048))
        assertTrue("stale chunk 2 must not reappear", out.copyOfRange(2048, 4096).all { it == 0.toByte() })
    }
    @Test fun missingChunksReadAsZerosOrphansAndBadPagesAreIgnored() {
        val fs = Yaffs2Reader(image(header(1, 1, ""), header(257, 1, "sparse", 1, 4096),
            data(257, 2, ByteArray(2048) { 5 }), data(999, 1, byteArrayOf(1)), erased(),
            ByteArray(2048 + 64) { (it * 7).toByte() }))
        val out = files(fs).getValue("sparse")
        assertTrue(out.copyOfRange(0, 2048).all { it == 0.toByte() }); assertTrue(out.copyOfRange(2048, 4096).all { it == 5.toByte() })
        assertTrue(fs.warnings.any { "missing data chunks" in it }); assertTrue(fs.warnings.any { "no surviving header" in it })
    }
    @Test fun olderDuplicatePathIsDroppedInFavourOfNewer() {
        val fs = Yaffs2Reader(image(header(1, 1, ""), header(257, 1, "same", 1, 1, seq = 0x1001), data(257, 1, byteArrayOf(1), seq = 0x1001),
            header(258, 1, "same", 1, 1, seq = 0x1002), data(258, 1, byteArrayOf(2), seq = 0x1002)))
        assertArrayEquals(byteArrayOf(2), files(fs).getValue("same"))
    }
    @Test fun trailingPartialPageIsIgnored() {
        val base = header(1, 1, "") + header(257, 1, "file")
        Yaffs2Reader(source(base + ByteArray(1000) { 0xff.toByte() }))
        Yaffs2Reader(source(base + ByteArray(1000) { it.toByte() }))
    }
    @Test fun keepsAFinalPageThatOnlyLacksTheLastSpareBytes() {
        // Acer packages: the image starts 4 bytes before its first page, so the last page is 4 bytes short
        val bytes = ByteArray(1500) { (it * 7).toByte() }
        val raw = header(1, 1, "") + header(257, 1, "file", 1, bytes.size) + data(257, 1, bytes)
        val fs = Yaffs2Reader(source(raw.copyOf(raw.size - 4)))
        assertArrayEquals(bytes, files(fs).getValue("file"))
        assertTrue(fs.warnings.none { "missing data chunks" in it })
        // a remainder too short to hold the page data and its tags is still ignored
        Yaffs2Reader(source((header(1, 1, "") + header(257, 1, "file")) + ByteArray(2000) { 0x55 }))
    }
    @Test fun dropsUnsafeNamesInsteadOfAbortingAndRejectsDirectoryCycles() {
        for (name in listOf("..", "a/b", "a\\b", ".", "")) {
            val fs = Yaffs2Reader(image(header(1, 1, ""), header(257, 1, name), header(258, 1, "ok")))
            assertEquals(setOf("ok"), files(fs).keys)
            assertTrue(fs.warnings.any { "unusable names" in it })
        }
        // children of a dropped directory are orphans and disappear with it
        val tree = Yaffs2Reader(image(header(1, 1, ""), header(257, 1, "a/b"), header(258, 257, "child"), header(259, 1, "ok")))
        assertEquals(setOf("ok"), files(tree).keys)
        // a damaged newer header does not hide the older valid one
        val kept = Yaffs2Reader(image(header(1, 1, ""), header(257, 1, "good", seq = 0x1001), header(257, 1, "..", seq = 0x1002)))
        assertEquals(setOf("good"), files(kept).keys)
        rejects(image(header(1, 1, ""), header(257, 258, "a"), header(258, 257, "b")))
        val fs = Yaffs2Reader(image(header(1, 1, ""), header(257, 999, "orphan"), header(258, 1, "ok")))
        assertEquals(setOf("ok"), files(fs).keys)
    }
    @Test fun rejectsHardLinkCyclesAndDropsMissingTargets() {
        rejects(image(header(1, 1, ""), header(257, 1, "a", 4, target = 258), header(258, 1, "b", 4, target = 257)))
        val fs = Yaffs2Reader(image(header(1, 1, ""), header(257, 1, "hard", 4, target = 999), header(258, 1, "ok")))
        assertEquals(setOf("ok"), files(fs).keys)
    }
    @Test fun imageNotStartingOnAChunkBoundaryIsRealigned() {
        val pages = arrayOf(header(1, 1, "", extra = false), header(257, 1, "dir"), header(258, 257, "f", 1, 3),
            data(258, 1, byteArrayOf(4, 5, 6)), header(259, 1, "g", 1, 1), data(259, 1, byteArrayOf(9)))
        // header tags are rewritten with the 0xffff byte count real images use, so the signature detector applies
        for (p in pages) if (ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).getInt(2048 + 8) == 0) ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putInt(2048 + 12, 0xffff)
        val fs = Yaffs2Reader(source(ByteArray(300) { 0x5a } + pages.reduce { a, b -> a + b }))
        assertEquals(300L, fs.geometry.base)
        assertArrayEquals(byteArrayOf(4, 5, 6), files(fs).getValue("dir/f"))
    }
    @Test fun spareLessImageIsRebuiltFromSequentialPages() {
        fun page(b: ByteArray) = b.copyOf(2048)
        val fs = Yaffs2Reader(source(page(header(1, 1, "")) + page(header(257, 1, "dir")) +
            page(header(258, 257, "f", 1, 3)) + page(data(0, 0, byteArrayOf(4, 5, 6))) +
            page(header(259, 257, "l", 2, alias = "f")) + ByteArray(4 * 2048) { 0xff.toByte() }))
        assertTrue(fs.geometry.sequential)
        assertEquals(setOf("dir", "dir/f", "dir/l"), files(fs).keys)
        assertArrayEquals(byteArrayOf(4, 5, 6), files(fs).getValue("dir/f"))
    }
    @Test fun rejectsNonYaffs2() {
        assertFalse(Yaffs2Reader.probe(source(byteArrayOf(1, 2, 3))))
        assertFalse(Yaffs2Reader.probe(source(ByteArray(300_000) { (it * 31 + it / 7).toByte() })))
    }
    @Test fun cancellationStopsIndexingAndCopying() {
        try { Yaffs2Reader(image(header(1, 1, ""))) { throw IOException("cancelled") }; fail() } catch (e: IOException) { assertEquals("cancelled", e.message) }
        var cancelled = false
        val fs = Yaffs2Reader(image(header(1, 1, ""), header(257, 1, "file", 1, 1), data(257, 1, byteArrayOf(1)))) {
            if (cancelled) throw IOException("cancelled")
        }
        var file: Yaffs2Reader.Node? = null; fs.walk { _, n -> file = n }; cancelled = true
        try { fs.copy(file!!, ByteArrayOutputStream()); fail() } catch (e: IOException) { assertEquals("cancelled", e.message) }
    }
    @Test fun optionalRealCwmSnapshot() {
        val fixture = System.getenv("AEMU_YAFFS2_FIXTURE")?.let(::File) ?: return
        assertEquals("51824642b12922d01c83cf40ddd795fa", fixture.inputStream().use { input ->
            val md = MessageDigest.getInstance("MD5"); val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
            md.digest().joinToString("") { "%02x".format(it) }
        })
        FileChannel.open(fixture.toPath(), StandardOpenOption.READ).use { channel ->
            val fs = Yaffs2Reader(ChannelSource(channel)); var objects = 0; var files = 0; var bytes = 0L
            var buildProp = false; var framework = false
            fs.walk { path, node ->
                objects++
                if (node.type == 1 || node.type == 4) {
                    files++
                    fs.copy(node, object : OutputStream() {
                        override fun write(b: Int) { bytes++ }
                        override fun write(b: ByteArray, off: Int, len: Int) { bytes += len }
                    })
                }
                if (path == "build.prop" && node.type == 1) buildProp = true
                if (path == "framework" && node.type == 3) framework = true
            }
            assertTrue(buildProp && framework && files > 100 && bytes > 10_000_000)
            println("CWM YAFFS2 fixture: $objects objects, $files files, $bytes file bytes; all file chunks copied")
        }
    }
}
