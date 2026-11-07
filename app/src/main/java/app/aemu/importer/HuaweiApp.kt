package app.aemu.importer

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Huawei / Honor UPDATE.APP (EMUI firmware, Android 4.4–10; also inside update.zip and update_full_base.zip).
 *
 * After 92 zero bytes come the partition images one after another, each behind its own header:
 *   magic A55AAA55, header length, 1, hardware id[8], sequence, data size, date[16], time[16], name[16] ("SYSTEM",
 *   "VENDOR", "BOOT"…), [16], header crc (2), block size (2), (2)  = 98 bytes, then a crc16 per block up to the header
 *   length, then the data; the next header starts at the next 4-byte boundary of the file.
 * The images are stored as they are (sparse or raw ext4/EROFS), so they are read in place.
 */
object HuaweiApp {
    private const val MAGIC = 0xA55AAA55.toInt()
    private const val HEAD = 98

    data class Entry(val name: String, val offset: Long, val size: Long)

    /** a file header in the first bytes, where the format puts the first one */
    fun probe(h: ByteArray): Boolean = firstHeader(h) >= 0

    private fun firstHeader(h: ByteArray): Int {
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i + HEAD <= h.size && i <= 1024) {
            if (bb.getInt(i) == MAGIC && bb.getInt(i + 4) in HEAD..(1 shl 24)) return i
            // (only zeros may come before it: anything else is another format)
            if (h[i] != 0.toByte() || h[i + 1] != 0.toByte() || h[i + 2] != 0.toByte() || h[i + 3] != 0.toByte()) return -1
            i += 4
        }
        return -1
    }

    fun entries(src: RandomSource): List<Entry> {
        val first = ByteArray(minOf(src.size, 2048L).toInt()).also { src.read(0, ByteBuffer.wrap(it)) }
        var pos = firstHeader(first).toLong().takeIf { it >= 0 } ?: return emptyList()
        val out = ArrayList<Entry>()
        val hb = ByteBuffer.allocate(HEAD).order(ByteOrder.LITTLE_ENDIAN)
        while (pos + HEAD <= src.size) {
            hb.clear(); src.read(pos, hb)
            if (hb.getInt(0) != MAGIC) {
                // a few vendors pad differently: look for the next header in the next bytes
                pos = next(src, pos) ?: break
                continue
            }
            val headLen = hb.getInt(4).toLong() and 0xffffffffL
            val size = hb.getInt(24).toLong() and 0xffffffffL
            if (headLen < HEAD || pos + headLen + size > src.size) break
            val raw = ByteArray(16).also { System.arraycopy(hb.array(), 60, it, 0, 16) }
            val name = String(raw, Charsets.US_ASCII).substringBefore('\u0000').trim()
            out += Entry(name, pos + headLen, size)
            pos = (pos + headLen + size + 3) and 3L.inv()
        }
        return out
    }

    private fun next(src: RandomSource, from: Long): Long? {
        val b = ByteBuffer.allocate(minOf(4096L, src.size - from).toInt().coerceAtLeast(0)).order(ByteOrder.LITTLE_ENDIAN)
        src.read(from, b)
        for (i in 0..b.capacity() - 4) if (b.getInt(i) == MAGIC) return from + i
        return null
    }
}
