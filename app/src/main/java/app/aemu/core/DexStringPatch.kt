package app.aemu.core

import java.util.zip.Adler32

/**
 * Dalvik's libcore reads /proc/net/if_inet6 (NetworkInterface.collectIpv6Addresses) and the host's /proc/net is
 * closed to apps (EACCES). The string constant is swapped, byte for byte, for a path inside the guest's /data.
 * Only a complete string item is matched (length byte before, NUL after). Same length, so no offset or length prefix in the dex moves. The dex adler32 is refreshed when it was valid
 * before (so [undo] gives the stock bytes back exactly); the SHA-1 signature is left alone on purpose, because the
 * dependency tables of every other odex/cache file record it.
 * Works on an odex ("dey\n" header) or a plain dex; never on a zipped jar.
 */
internal object DexStringPatch {
    val FROM = "/proc/net/if_inet6".toByteArray(Charsets.ISO_8859_1)
    val TO = "/data/.aemu_ifinet".toByteArray(Charsets.ISO_8859_1)

    /** @return 1 patched, 0 already patched, -1 string not found */
    fun apply(d: ByteArray): Int = if (swap(d, FROM, TO)) 1 else if (find(d, TO).isNotEmpty()) 0 else -1

    /** Back to the stock bytes. @return true if anything changed */
    fun undo(d: ByteArray): Boolean = swap(d, TO, FROM)

    /**
     * Only a whole dex string_data_item counts: the byte before is the ULEB128 length (18 UTF-16 units) and the byte
     * after is the terminating NUL. A longer string that merely contains the text, or code or data that happens to
     * hold the same bytes, never matches.
     */
    private fun find(d: ByteArray, p: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        val lenByte = p.size.toByte()
        if (d.size < p.size + 2) return out
        for (j in 1..d.size - p.size - 1) {
            if (d[j] == p[0] && d[j - 1] == lenByte && d[j + p.size] == 0.toByte() && p.indices.all { d[j + it] == p[it] }) out += j
        }
        return out
    }

    private fun u32(d: ByteArray, o: Int) =
        (d[o].toInt() and 0xff) or ((d[o + 1].toInt() and 0xff) shl 8) or ((d[o + 2].toInt() and 0xff) shl 16) or ((d[o + 3].toInt() and 0xff) shl 24)

    /** (offset, end) of the dex inside [d], or null when the file is neither a dex nor an odex. */
    private fun region(d: ByteArray): Pair<Int, Int>? {
        if (d.size < 120) return null
        val plain = d[0] == 'd'.code.toByte() && d[1] == 'e'.code.toByte() && d[2] == 'x'.code.toByte() && d[3] == '\n'.code.toByte()
        val odex = d[0] == 'd'.code.toByte() && d[1] == 'e'.code.toByte() && d[2] == 'y'.code.toByte() && d[3] == '\n'.code.toByte()
        val (off, end) = when {
            plain -> 0 to d.size
            odex -> u32(d, 8).let { it to it + u32(d, 12) }
            else -> return null
        }
        if (off < 0 || end > d.size || end - off < 112) return null
        if (d[off] != 'd'.code.toByte() || d[off + 1] != 'e'.code.toByte() || d[off + 2] != 'x'.code.toByte()) return null
        return off to end
    }

    private fun adler(d: ByteArray, r: Pair<Int, Int>): Int =
        Adler32().also { it.update(d, r.first + 12, r.second - r.first - 12) }.value.toInt()

    private fun swap(d: ByteArray, a: ByteArray, b: ByteArray): Boolean {
        val hits = find(d, a)
        if (hits.isEmpty()) return false
        val r = region(d)
        val valid = r != null && adler(d, r) == u32(d, r.first + 8)
        for (h in hits) for (k in b.indices) d[h + k] = b[k]
        if (valid && r != null) {
            val ck = adler(d, r)
            for (k in 0..3) d[r.first + 8 + k] = (ck ushr (8 * k)).toByte()
        }
        return true
    }
}
