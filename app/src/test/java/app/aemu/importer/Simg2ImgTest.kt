package app.aemu.importer

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** The sparse reader/converter must behave like AOSP libsparse (simg2img). */
class Simg2ImgTest {
    private val blk = 4096

    private class MemSource(val data: ByteArray) : RandomSource {
        override val size get() = data.size.toLong()
        override fun read(pos: Long, dst: ByteBuffer) {
            var p = pos.toInt()
            while (dst.hasRemaining()) dst.put(if (p < data.size) data[p++] else 0)
        }
    }

    private fun le(v: Long, n: Int) = ByteArray(n) { (v shr (8 * it)).toByte() }

    private fun image(totalBlocks: Int, chunks: List<ByteArray>, fileHdr: Int = 28, chunkHdr: Int = 12): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(le(0xED26FF3AL, 4)); o.write(le(1, 2)); o.write(le(0, 2))
        o.write(le(fileHdr.toLong(), 2)); o.write(le(chunkHdr.toLong(), 2))
        o.write(le(blk.toLong(), 4)); o.write(le(totalBlocks.toLong(), 4)); o.write(le(chunks.size.toLong(), 4)); o.write(le(0, 4))
        o.write(ByteArray(fileHdr - 28))
        chunks.forEach { o.write(it) }
        return o.toByteArray()
    }

    private fun chunk(type: Int, blocks: Int, data: ByteArray = ByteArray(0), chunkHdr: Int = 12): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(le(type.toLong(), 2)); o.write(le(0, 2)); o.write(le(blocks.toLong(), 4))
        o.write(le((chunkHdr + data.size).toLong(), 4)); o.write(ByteArray(chunkHdr - 12)); o.write(data)
        return o.toByteArray()
    }

    private fun raw(blocks: Int, seed: Int) = ByteArray(blocks * blk) { ((it * 31 + seed) xor (it ushr 7)).toByte() }
    private fun fillChunk(v: Int, blocks: Int, chunkHdr: Int = 12) = chunk(Simg2Img.CHUNK_FILL, blocks, le(v.toLong() and 0xffffffffL, 4), chunkHdr)
    private fun fillBytes(v: Int, blocks: Int) = ByteArray(blocks * blk) { (v shr (8 * (it and 3))).toByte() }

    private fun expand(vararg parts: ByteArray): ByteArray {
        val out = Files.createTempFile("simg", ".raw").toFile()
        try {
            Simg2Img.convert(parts.map { MemSource(it) }, out)
            val viaSource = SparseSource(parts.map { MemSource(it) })
            val a = out.readBytes()
            val b = ByteBuffer.allocate(viaSource.size.toInt()).also { viaSource.read(0, it) }.array()
            assertArrayEquals("SparseSource must equal simg2img output", a, b)
            return a
        } finally { out.delete() }
    }

    @Test fun rawFillDontCareAndCrcChunks() {
        val r = raw(2, 1)
        val img = image(2 + 3 + 2 + 1, listOf(
            chunk(Simg2Img.CHUNK_RAW, 2, r), fillChunk(0x11223344, 3), chunk(Simg2Img.CHUNK_DONT_CARE, 2),
            chunk(Simg2Img.CHUNK_CRC32, 0, le(0xdeadbeef, 4)), fillChunk(0, 1)))
        val out = expand(img)
        assertEquals(8 * blk, out.size)
        assertArrayEquals(r, out.copyOfRange(0, 2 * blk))
        assertArrayEquals(fillBytes(0x11223344, 3), out.copyOfRange(2 * blk, 5 * blk))
        assertTrue(out.copyOfRange(5 * blk, 8 * blk).all { it == 0.toByte() })
    }

    @Test fun longerFileAndChunkHeadersAreSkipped() {
        val r = raw(1, 9)
        val out = expand(image(3, listOf(chunk(Simg2Img.CHUNK_RAW, 1, r, 20), fillChunk(0x01020304, 2, 20)), fileHdr = 40, chunkHdr = 20))
        assertArrayEquals(r, out.copyOfRange(0, blk))
        assertArrayEquals(fillBytes(0x01020304, 2), out.copyOfRange(blk, 3 * blk))
    }

    @Test fun partsOverlayFromOffsetZeroLikeMotorolaSparsechunks() {
        val a = raw(2, 1); val b = raw(1, 2); val c = raw(2, 3)
        val p0 = image(7, listOf(chunk(Simg2Img.CHUNK_RAW, 2, a), chunk(Simg2Img.CHUNK_DONT_CARE, 5)))
        val p1 = image(7, listOf(chunk(Simg2Img.CHUNK_DONT_CARE, 2), chunk(Simg2Img.CHUNK_RAW, 1, b), chunk(Simg2Img.CHUNK_DONT_CARE, 4)))
        val p2 = image(7, listOf(chunk(Simg2Img.CHUNK_DONT_CARE, 3), fillChunk(0x5A5A5A5A, 2), chunk(Simg2Img.CHUNK_RAW, 2, c)))
        val out = expand(p0, p1, p2)
        assertEquals(7 * blk, out.size)
        assertArrayEquals(a, out.copyOfRange(0, 2 * blk))
        assertArrayEquals(b, out.copyOfRange(2 * blk, 3 * blk))
        assertArrayEquals(fillBytes(0x5A5A5A5A, 2), out.copyOfRange(3 * blk, 5 * blk))
        assertArrayEquals(c, out.copyOfRange(5 * blk, 7 * blk))
    }

    @Test fun laterPartOverwritesEarlierOne() {
        val a = raw(4, 1); val b = raw(2, 2)
        val out = expand(image(4, listOf(chunk(Simg2Img.CHUNK_RAW, 4, a))),
            image(4, listOf(chunk(Simg2Img.CHUNK_DONT_CARE, 1), chunk(Simg2Img.CHUNK_RAW, 2, b), chunk(Simg2Img.CHUNK_DONT_CARE, 1))))
        assertArrayEquals(a.copyOfRange(0, blk), out.copyOfRange(0, blk))
        assertArrayEquals(b, out.copyOfRange(blk, 3 * blk))
        assertArrayEquals(a.copyOfRange(3 * blk, 4 * blk), out.copyOfRange(3 * blk, 4 * blk))
    }

    @Test fun malformedImagesAreRejectedLikeLibsparse() {
        fun bad(img: ByteArray) = try { Simg2Img.parse(MemSource(img)); false } catch (e: Simg2Img.SparseFormatException) { true }
        val good = image(2, listOf(chunk(Simg2Img.CHUNK_RAW, 2, raw(2, 1))))
        assertFalse(bad(good))
        assertTrue("bad magic", bad(good.copyOf().also { it[0] = 0 }))
        assertTrue("major version 2", bad(good.copyOf().also { it[4] = 2 }))
        assertTrue("block count mismatch", bad(image(5, listOf(chunk(Simg2Img.CHUNK_RAW, 2, raw(2, 1))))))
        assertTrue("truncated raw data", bad(good.copyOf(good.size - 100)))
        assertTrue("unknown chunk", bad(image(1, listOf(chunk(0xCAFE, 1, ByteArray(blk))))))
        assertTrue("fill with 8 data bytes", bad(image(1, listOf(chunk(Simg2Img.CHUNK_FILL, 1, ByteArray(8))))))
        assertTrue("dont-care with data", bad(image(1, listOf(chunk(Simg2Img.CHUNK_DONT_CARE, 1, ByteArray(4))))))
        assertTrue("zero blocks", bad(image(0, emptyList())))
    }

    @Test fun invalidInputLeavesNoOutputFile() {
        val out = Files.createTempFile("simg", ".raw").toFile().also { it.delete() }
        try {
            assertThrows(Simg2Img.SparseFormatException::class.java) {
                Simg2Img.convert(listOf(MemSource(image(9, listOf(chunk(Simg2Img.CHUNK_RAW, 2, raw(2, 1)))))), out)
            }
            assertFalse(out.exists())
        } finally { out.delete() }
    }

    @Test fun numericOrderSortsChunkSuffixesNumerically() {
        val names = listOf("super_sparse.10", "super_sparse.2", "super_sparse.0", "super_sparse.1")
        assertEquals(listOf("super_sparse.0", "super_sparse.1", "super_sparse.2", "super_sparse.10"),
            Simg2Img.numericOrder(names.map { File(it) }).map { it.name })
    }
}
