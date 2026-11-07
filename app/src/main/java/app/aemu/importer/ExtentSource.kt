package app.aemu.importer

import java.nio.ByteBuffer

/**
 * A partition image assembled from extents ((offset, length) pairs) of another source: a member of an uncompressed
 * Odin tar read in place, or a partition of a super.img. Holes read as zeros. From AEmulator 0.0.0.3.
 */
internal class ExtentSource(private val src: RandomSource, private val extents: List<Pair<Long, Long>>) : RandomSource {
    constructor(src: RandomSource, offset: Long, length: Long) : this(src, listOf(offset to length))

    override val size: Long = extents.sumOf { it.second }

    override fun read(pos: Long, dst: ByteBuffer) {
        var p = pos
        while (dst.hasRemaining()) {
            if (p >= size) { while (dst.hasRemaining()) dst.put(0); return }
            var base = 0L
            val (off, len) = extents.first { e -> (p < base + e.second).also { if (!it) base += e.second } }
            val inExt = p - base
            val n = minOf(dst.remaining().toLong(), len - inExt).toInt()
            val lim = dst.limit()
            dst.limit(dst.position() + n)
            src.read(off + inExt, dst)
            dst.limit(lim)
            p += n
        }
    }
}
