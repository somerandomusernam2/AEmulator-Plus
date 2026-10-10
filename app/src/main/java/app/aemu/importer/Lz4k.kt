package app.aemu.importer

import java.io.IOException

/**
 * LZ4K, the bit-packed LZ4 relative that MediaTek-era kernels register as UBIFS compression type 3
 * (crypto name "lz4k"; mainline uses the same number for zstd, see [UbiFsReader]).
 *
 * The format was recovered from the firmware's own kernel (lz4k_decompress_safe) and the three lookup
 * tables below are copied from its read-only data. A block is an LSB-first bit stream:
 *
 *  - bit 0 = 1 selects this mode (0 would be the "LZ4K"-magic variant; not supported, never seen in UBIFS data);
 *  - then repeated: [literals] [match] where
 *      tag "1"            no literals
 *      tag "00"           one literal byte (8 raw bits follow)
 *      tag "01" ...       literal run: 7 bits (bits 1..7 of the window) index [LIT_RUN]; entry 0 means an explicit
 *                         12-bit count-1 after 6 tag bits, otherwise entry = (bits shift 8 | count) and the tag
 *                         costs shift + 1 bits; the run bytes follow as raw 8-bit values;
 *  - match: first bit 1 = explicit distance in bitlength(output position) bits, otherwise a 7-bit index into
 *    [DIST] (entry = bits<<8 | distance, distance 0 keeps the previous one); length: 5 bits 0x1f = explicit
 *    12-bit length, otherwise an 8-bit index into [LEN] (entry = bits<<8 | length);
 *  - decoding stops as soon as the output block (at most 4096 bytes) is full.
 */
internal object Lz4k {
    private val LIT_RUN = intArrayOf(
        257, 770, 257, 1029, 257, 1027, 257, 1543, 257, 770, 257, 1286, 257, 1028, 257, 1806,
        257, 770, 257, 1029, 257, 1027, 257, 1802, 257, 770, 257, 1289, 257, 1028, 257, 0,
        257, 770, 257, 1029, 257, 1027, 257, 1544, 257, 770, 257, 1286, 257, 1028, 257, 1808,
        257, 770, 257, 1029, 257, 1027, 257, 1804, 257, 770, 257, 1289, 257, 1028, 257, 0,
        257, 770, 257, 1029, 257, 1027, 257, 1543, 257, 770, 257, 1286, 257, 1028, 257, 1807,
        257, 770, 257, 1029, 257, 1027, 257, 1803, 257, 770, 257, 1289, 257, 1028, 257, 0,
        257, 770, 257, 1029, 257, 1027, 257, 1544, 257, 770, 257, 1286, 257, 1028, 257, 1809,
        257, 770, 257, 1029, 257, 1027, 257, 1805, 257, 770, 257, 1289, 257, 1028, 257, 0,
    )

    private val DIST = intArrayOf(
        768, 1560, 1284, 1814, 768, 1836, 1296, 1876, 768, 1820, 1288, 1860, 768, 1856, 1548, 2160,
        768, 1793, 1284, 1822, 768, 1848, 1296, 2144, 768, 1828, 1288, 1868, 768, 1806, 1556, 2176,
        768, 1560, 1284, 1818, 768, 1840, 1296, 2136, 768, 1824, 1288, 1864, 768, 1802, 1548, 2168,
        768, 1798, 1284, 1844, 768, 1852, 1296, 2152, 768, 1832, 1288, 1872, 768, 1810, 1556, 2082,
        768, 1560, 1284, 1814, 768, 1836, 1296, 1876, 768, 1820, 1288, 1860, 768, 1856, 1548, 2164,
        768, 1793, 1284, 1822, 768, 1848, 1296, 2148, 768, 1828, 1288, 1868, 768, 1806, 1556, 2050,
        768, 1560, 1284, 1818, 768, 1840, 1296, 2140, 768, 1824, 1288, 1864, 768, 1802, 1548, 2172,
        768, 1798, 1284, 1844, 768, 1852, 1296, 2156, 768, 1832, 1288, 1872, 768, 1810, 1556, 2180,
    )

    private val LEN = intArrayOf(
        514, 772, 515, 1031, 514, 1029, 515, 1545, 514, 772, 515, 1288, 514, 1030, 515, 2061,
        514, 772, 515, 1031, 514, 1029, 515, 1551, 514, 772, 515, 1291, 514, 1030, 515, 0,
        514, 772, 515, 1031, 514, 1029, 515, 1548, 514, 772, 515, 1288, 514, 1030, 515, 2067,
        514, 772, 515, 1031, 514, 1029, 515, 1802, 514, 772, 515, 1291, 514, 1030, 515, 0,
        514, 772, 515, 1031, 514, 1029, 515, 1545, 514, 772, 515, 1288, 514, 1030, 515, 2065,
        514, 772, 515, 1031, 514, 1029, 515, 1551, 514, 772, 515, 1291, 514, 1030, 515, 0,
        514, 772, 515, 1031, 514, 1029, 515, 1548, 514, 772, 515, 1288, 514, 1030, 515, 2072,
        514, 772, 515, 1031, 514, 1029, 515, 1808, 514, 772, 515, 1291, 514, 1030, 515, 0,
        514, 772, 515, 1031, 514, 1029, 515, 1545, 514, 772, 515, 1288, 514, 1030, 515, 2062,
        514, 772, 515, 1031, 514, 1029, 515, 1551, 514, 772, 515, 1291, 514, 1030, 515, 0,
        514, 772, 515, 1031, 514, 1029, 515, 1548, 514, 772, 515, 1288, 514, 1030, 515, 2068,
        514, 772, 515, 1031, 514, 1029, 515, 1802, 514, 772, 515, 1291, 514, 1030, 515, 0,
        514, 772, 515, 1031, 514, 1029, 515, 1545, 514, 772, 515, 1288, 514, 1030, 515, 2066,
        514, 772, 515, 1031, 514, 1029, 515, 1551, 514, 772, 515, 1291, 514, 1030, 515, 0,
        514, 772, 515, 1031, 514, 1029, 515, 1548, 514, 772, 515, 1288, 514, 1030, 515, 2076,
        514, 772, 515, 1031, 514, 1029, 515, 1808, 514, 772, 515, 1291, 514, 1030, 515, 0,
    )

    fun decompress(src: ByteArray, off: Int, len: Int, outLen: Int): ByteArray {
        if (len <= 0 || (src[off].toInt() and 1) == 0) throw IOException("LZ4K: unsupported block variant")
        val out = ByteArray(outLen)
        var o = 0
        var bit = 1L // bit 0 selected the mode
        var dist = 0

        fun peek(n: Int): Int {
            val byteIdx = (bit ushr 3).toInt()
            var v = 0L
            for (i in 0 until 5) {
                val idx = byteIdx + i
                if (idx < len) v = v or ((src[off + idx].toLong() and 0xff) shl (8 * i))
            }
            return ((v ushr (bit and 7).toInt()) and ((1L shl n) - 1)).toInt()
        }
        fun skip(n: Int) { bit += n }
        fun take(n: Int): Int { val r = peek(n); bit += n; return r }
        fun overrun() = bit > len * 8L + 64

        while (o < outLen) {
            if (overrun()) throw IOException("LZ4K: truncated block")
            if (peek(1) == 1) {
                skip(1)
            } else if (peek(2) == 0) {
                skip(2)
                out[o++] = take(8).toByte()
                if (o >= outLen) break
            } else {
                val e = LIT_RUN[(peek(8) and 0xfe) shr 1]
                val cnt: Int
                if (e == 0) { skip(6); cnt = take(12) + 1 } else { skip((e shr 8) + 1); cnt = e and 255 }
                var i = 0
                while (i < cnt && o < outLen) { out[o++] = take(8).toByte(); i++ }
                if (o >= outLen) break
            }
            // match
            if (peek(1) == 1) {
                val bl = 32 - Integer.numberOfLeadingZeros(o)
                skip(1)
                dist = if (bl > 0) take(bl) else 0
            } else {
                val e = DIST[(peek(8) and 0xfe) shr 1]
                skip(e shr 8)
                if ((e and 255) != 0) dist = e and 255
            }
            val l: Int
            if (peek(5) == 0x1f) { skip(5); l = take(12) } else {
                val e = LEN[peek(8)]
                skip(e shr 8); l = e and 255
            }
            if (dist <= 0 || dist > o) throw IOException("LZ4K: bad match distance $dist at $o")
            var n = minOf(l, outLen - o)
            while (n-- > 0) { out[o] = out[o - dist]; o++ }
        }
        return out
    }
}
