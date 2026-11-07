package app.aemu.importer

import java.io.IOException
import java.nio.ByteBuffer
import java.util.TreeMap

/**
 * Android sparse image (system.img from fastboot/Odin) as a random-access source — nothing is
 * expanded to disk, the requested bytes are produced from the chunk table on demand.
 *
 * Parsing and validation are [Simg2Img.parse] (a port of AOSP libsparse), and several parts
 * (Motorola `system.img_sparsechunk.N`, `super_sparse.N`) are combined exactly like
 * `simg2img part0 part1 ... out`: each part describes the whole image, is applied from offset 0
 * in the given order (later parts overwrite earlier ones where they overlap), unlisted/DONT_CARE
 * ranges read as zeros, and the size is the last part's total_blks * blk_sz.
 */
class SparseSource(private val parts: List<RandomSource>) : RandomSource {
    /** [start, end) in output bytes; RAW reads from parts[part] at srcOff + (pos - start). */
    private class Piece(val start: Long, val end: Long, val type: Int, val part: Int, val srcOff: Long, val fill: Int) {
        fun cut(a: Long, b: Long) =
            Piece(a, b, type, part, if (type == Simg2Img.CHUNK_RAW) srcOff + (a - start) else 0, fill)
    }

    private val pieces = TreeMap<Long, Piece>()   // key = start; never overlapping
    override val size: Long

    init {
        if (parts.isEmpty()) throw IOException("no sparse images given")
        var last = 0L
        for ((pi, src) in parts.withIndex()) {
            val l = Simg2Img.parse(src)
            for (s in l.segs) {
                paint(Piece(s.startBlock * l.blockSize, (s.startBlock + s.blocks) * l.blockSize, s.type, pi, s.srcOff, s.fill))
            }
            last = l.size
        }
        size = last
    }

    /** Puts [n] on top of whatever is there (a later part overwrites an earlier one). */
    private fun paint(n: Piece) {
        pieces.lowerEntry(n.start)?.value?.let { p ->
            if (p.end > n.start) {
                pieces[p.start] = p.cut(p.start, n.start)
                if (p.end > n.end) pieces[n.end] = p.cut(n.end, p.end)
            }
        }
        while (true) {
            val e = pieces.ceilingEntry(n.start) ?: break
            if (e.key >= n.end) break
            pieces.remove(e.key)
            if (e.value.end > n.end) pieces[n.end] = e.value.cut(n.end, e.value.end)
        }
        pieces[n.start] = n
    }

    override fun read(pos: Long, dst: ByteBuffer) {
        var p = pos
        while (dst.hasRemaining()) {
            if (p >= size) { zeros(dst, dst.remaining()); return }
            val pc = pieces.floorEntry(p)?.value
            val pcEnd = if (pc != null) minOf(pc.end, size) else 0L
            if (pc != null && p < pcEnd) {
                val k = minOf(dst.remaining().toLong(), pcEnd - p).toInt()
                if (pc.type == Simg2Img.CHUNK_RAW) {
                    val lim = dst.limit()
                    dst.limit(dst.position() + k)
                    parts[pc.part].read(pc.srcOff + (p - pc.start), dst)
                    dst.limit(lim)
                } else {
                    fill(dst, pc.fill, k, (p - pc.start))
                }
                p += k
            } else {
                // hole (DONT_CARE / never written): zeros up to the next piece or the end
                val next = minOf(pieces.higherKey(p) ?: size, size)
                val k = minOf(dst.remaining().toLong(), next - p).toInt()
                zeros(dst, k)
                p += k
            }
        }
    }

    private fun zeros(dst: ByteBuffer, n: Int) {
        var left = n
        while (left > 0) { val k = minOf(left, ZERO.size); dst.put(ZERO, 0, k); left -= k }
    }

    /** [n] bytes of the little-endian 4-byte pattern [v], starting [phase] bytes into the pattern. */
    private fun fill(dst: ByteBuffer, v: Int, n: Int, phase: Long) {
        val pat = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        if (v == 0) { zeros(dst, n); return }
        val tmp = ByteArray(minOf(n, 8192))
        var left = n
        var ph = (phase and 3).toInt()
        while (left > 0) {
            val k = minOf(left, tmp.size)
            for (i in 0 until k) tmp[i] = pat[(ph + i) and 3]
            dst.put(tmp, 0, k)
            ph = (ph + k) and 3
            left -= k
        }
    }

    companion object {
        const val MAGIC = Simg2Img.MAGIC
        const val RAW = Simg2Img.CHUNK_RAW
        const val FILL = Simg2Img.CHUNK_FILL
        const val DONT_CARE = Simg2Img.CHUNK_DONT_CARE
        const val CRC = Simg2Img.CHUNK_CRC32
        private val ZERO = ByteArray(8192)

        fun probe(src: RandomSource): Boolean = Simg2Img.probe(src)
    }
}
