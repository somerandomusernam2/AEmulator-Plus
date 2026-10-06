package app.aemu.importer

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A window [base, base + size) of another source, read as if it started at 0. */
class SliceSource(private val inner: RandomSource, private val base: Long, override val size: Long) : RandomSource {
    override fun read(pos: Long, dst: ByteBuffer) {
        if (pos >= size) { while (dst.hasRemaining()) dst.put(0); return }
        // never let the filesystem see the bytes behind the window (trailer / signature)
        val inside = minOf(dst.remaining().toLong(), size - pos).toInt()
        val lim = dst.limit()
        dst.limit(dst.position() + inside)
        inner.read(base + pos, dst)
        dst.limit(lim)
        while (dst.hasRemaining()) dst.put(0)
    }
}

/**
 * Motorola signed partition images (`system.img_sparsechunk.N` of fastboot XML packages, e.g. the
 * Moto G / XT1032 "falcon" firmware). Once the sparse chunks are merged the image is not a bare
 * ext4 filesystem:
 *
 *   [ "MOTO" 13 57 9B 00 "MOT_PIV_FULL256" … partition name … signature ]  (128 KiB, ext4 starts after it)
 *   [ ext4, blocks * blockSize bytes ]
 *   [ a trailing signature block ]
 *
 * The importer looks for the ext4 superblock at offset 0, so without unwrapping it reports an
 * "unrecognized filesystem". [unwrap] finds the real start of the filesystem behind the header and
 * returns a slice limited to the filesystem's own size.
 */
object MotoImage {
    private val MAGIC = "MOTO".toByteArray(Charsets.ISO_8859_1)
    private val PIV = "MOT_PIV_".toByteArray(Charsets.ISO_8859_1)

    /** Header + padding is searched this far for the filesystem (the known images use 128 KiB). */
    private const val SEARCH_LIMIT = 1L shl 20
    private const val STEP = 4096L

    private fun read(src: RandomSource, pos: Long, n: Int): ByteBuffer {
        val b = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        src.read(pos, b); b.flip()
        return b
    }

    /** True when [src] starts with the Motorola "MOTO…MOT_PIV_" signature header. */
    fun probe(src: RandomSource): Boolean = runCatching {
        if (src.size < 4096) return false
        val h = read(src, 0, 16)
        MAGIC.indices.all { h.get(it) == MAGIC[it] } && PIV.indices.all { h.get(8 + it) == PIV[it] }
    }.getOrDefault(false)

    /** Extent of the ext2/3/4 filesystem whose superblock is at [off]: its own block count, or null if it is no ext4. */
    private fun ext4SizeAt(src: RandomSource, off: Long): Long? {
        if (off + 2048 > src.size) return null
        val sb = read(src, off + 1024, 1024)
        if (sb.getShort(0x38).toInt() and 0xffff != 0xEF53) return null
        val logBs = sb.getInt(0x18)
        if (logBs !in 0..6) return null
        val bs = 1024L shl logBs
        val incompat = sb.getInt(0x60)
        val lo = sb.getInt(0x4).toLong() and 0xffffffffL
        val hi = if (incompat and 0x80 != 0) sb.getInt(0x150).toLong() and 0xffffffffL else 0L
        val blocks = lo or (hi shl 32)
        if (blocks == 0L) return null
        return blocks * bs
    }

    /**
     * [src] itself when it is not a Motorola-wrapped image (or no filesystem is found behind the
     * header); otherwise the filesystem slice. A filesystem that already starts at 0 is never touched.
     */
    fun unwrap(src: RandomSource): RandomSource {
        if (!probe(src) || Ext4Reader.probe(src)) return src
        var off = STEP
        val limit = minOf(SEARCH_LIMIT, src.size)
        while (off <= limit) {
            val fsSize = runCatching { ext4SizeAt(src, off) }.getOrNull()
            if (fsSize != null) {
                // the slice may not run past the image (a truncated dump still imports what is there)
                return SliceSource(src, off, minOf(fsSize, src.size - off))
            }
            off += STEP
        }
        return src
    }
}
