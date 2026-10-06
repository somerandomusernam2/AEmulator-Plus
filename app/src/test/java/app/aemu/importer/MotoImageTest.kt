package app.aemu.importer

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MotoImageTest {
    private class Mem(val data: ByteArray) : RandomSource {
        override val size get() = data.size.toLong()
        override fun read(pos: Long, dst: ByteBuffer) {
            var p = pos
            while (dst.hasRemaining()) {
                dst.put(if (p in 0 until size) data[p.toInt()] else 0)
                p++
            }
        }
    }

    private val blk = 4096

    /** Just enough of an ext4 superblock for the probes: magic, log block size 2 (4 KiB), block count. */
    private fun putSuperblock(img: ByteArray, at: Int, blocks: Int) {
        val b = ByteBuffer.wrap(img).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(at + 1024 + 0x38, 0xEF53.toShort())
        b.putInt(at + 1024 + 0x18, 2)
        b.putInt(at + 1024 + 0x4, blocks)
    }

    private fun putMotoHeader(img: ByteArray) {
        val h = "MOTO".toByteArray() + byteArrayOf(0x13, 0x57, 0x9b.toByte(), 0) + "MOT_PIV_FULL256".toByteArray()
        h.copyInto(img)
    }

    /** Layout of the Moto G system image: 128 KiB header, [fsBlocks] of ext4, one trailing block. */
    private fun moto(fsBlocks: Int, trailer: Boolean = true): ByteArray {
        val img = ByteArray((32 + fsBlocks + if (trailer) 1 else 0) * blk)
        putMotoHeader(img)
        putSuperblock(img, 32 * blk, fsBlocks)
        if (trailer) img.fill(0x5A, (32 + fsBlocks) * blk, img.size)
        return img
    }

    @Test fun recognisesTheHeader() {
        assertTrue(MotoImage.probe(Mem(moto(64))))
        assertFalse(MotoImage.probe(Mem(ByteArray(1 shl 16))))
        assertFalse(MotoImage.probe(Mem(ByteArray(100))))
        val plain = ByteArray(1 shl 16).also { putSuperblock(it, 0, 16) }
        assertFalse(MotoImage.probe(Mem(plain)))
    }

    @Test fun unwrapExposesTheFilesystemAtOffsetZero() {
        val src = Mem(moto(64))
        assertFalse(Ext4Reader.probe(src))
        val fs = MotoImage.unwrap(src)
        assertNotSame(src, fs)
        assertTrue(Ext4Reader.probe(fs))
        assertEquals(64L * blk, fs.size)
    }

    @Test fun trailerBehindTheFilesystemIsInvisible() {
        val fs = MotoImage.unwrap(Mem(moto(64)))
        val b = ByteBuffer.allocate(2 * blk)
        fs.read(63L * blk, b)   // last real block + 1 block past the end
        assertTrue(b.array().drop(blk).all { it == 0.toByte() })
        val tail = ByteBuffer.allocate(16)
        fs.read(64L * blk + 100, tail)
        assertTrue(tail.array().all { it == 0.toByte() })
    }

    @Test fun plainExt4IsNeverTouched() {
        val plain = Mem(ByteArray(1 shl 18).also { putSuperblock(it, 0, 64) })
        assertSame(plain, MotoImage.unwrap(plain))
    }

    @Test fun headerWithoutFilesystemIsLeftAlone() {
        val img = ByteArray(40 * blk).also { putMotoHeader(it) }
        val src = Mem(img)
        assertSame(src, MotoImage.unwrap(src))
    }

    @Test fun truncatedDumpImportsWhatIsThere() {
        // header says 64 blocks but the image stops after 40
        val full = moto(64, trailer = false)
        val cut = Mem(full.copyOf((32 + 40) * blk))
        val fs = MotoImage.unwrap(cut)
        assertTrue(Ext4Reader.probe(fs))
        assertEquals(40L * blk, fs.size)
    }

    @Test fun sliceReadsAreOffsetAndClipped() {
        val data = ByteArray(100) { it.toByte() }
        val s = SliceSource(Mem(data), 10, 20)
        val b = ByteBuffer.allocate(30)
        s.read(5, b)
        val out = b.array()
        assertEquals(15.toByte(), out[0])     // slice byte 5 = inner byte 15
        assertEquals(29.toByte(), out[14])    // last byte inside the slice
        assertTrue(out.drop(15).all { it == 0.toByte() })   // nothing from behind the window
    }
}
