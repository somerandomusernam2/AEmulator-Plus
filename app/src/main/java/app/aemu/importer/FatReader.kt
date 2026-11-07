package app.aemu.importer

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Read-only reader for a FAT12/16/32 volume, enough to list and copy files out of it. Samsung's Odin CSC packages
 * (`csc.rfs`) are a small FAT16 volume that holds `csc.zip` and `recovery/command`; the stock recovery unpacked
 * the zip into /system on the phone. Long file names are honoured, nothing is ever written.
 */
internal class FatReader private constructor(
    private val raf: RandomAccessFile,
    private val bps: Int, private val spc: Int, private val fatType: Int,
    private val rootStart: Long, private val rootBytes: Int, private val rootCluster: Int,
    private val dataStart: Long, private val clusters: Int, private val fat: ByteArray,
) : AutoCloseable {
    class Entry(val path: String, val size: Long, val cluster: Int)

    private val clusterBytes = bps * spc

    override fun close() = raf.close()

    /** Every regular file in the volume, with its full path ("recovery/command"). */
    fun files(): List<Entry> {
        val out = ArrayList<Entry>()
        val seen = HashSet<Int>()
        fun walk(buf: ByteArray, prefix: String, depth: Int) {
            for (e in parseDir(buf)) {
                if (out.size >= MAX_ENTRIES) return
                val p = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
                if (!e.dir) { out.add(Entry(p, e.size, e.cluster)); continue }
                if (depth >= MAX_DEPTH || e.cluster < 2 || !seen.add(e.cluster)) continue
                walk(readChain(e.cluster, MAX_DIR_BYTES), p, depth + 1)
            }
        }
        val root = if (fatType == 32) readChain(rootCluster, MAX_DIR_BYTES)
        else ByteArray(rootBytes).also { raf.seek(rootStart); raf.readFully(it) }
        walk(root, "", 0)
        return out
    }

    /** Copies at most [maxBytes] of the file to [out]; a chain that ends early is an error. */
    fun copy(e: Entry, out: OutputStream, maxBytes: Long = Long.MAX_VALUE) {
        var remaining = minOf(e.size, maxBytes)
        var c = e.cluster
        var guard = 0
        val buf = ByteArray(clusterBytes)
        while (remaining > 0 && c >= 2 && guard++ <= clusters + 2) {
            val want = minOf(remaining, clusterBytes.toLong()).toInt()
            raf.seek(dataStart + (c - 2).toLong() * clusterBytes)
            raf.readFully(buf, 0, want)
            out.write(buf, 0, want)
            remaining -= want
            c = next(c)
        }
        if (remaining > 0) throw EOFException("FAT file ${e.path} is truncated")
    }

    private fun readChain(first: Int, cap: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var c = first
        var guard = 0
        val buf = ByteArray(clusterBytes)
        while (c >= 2 && guard++ <= clusters + 2 && out.size() < cap) {
            raf.seek(dataStart + (c - 2).toLong() * clusterBytes)
            raf.readFully(buf)
            out.write(buf)
            c = next(c)
        }
        return out.toByteArray()
    }

    /** Next cluster of a chain, or 0 at the end (free, reserved, bad and end-of-chain marks all end it). */
    private fun next(c: Int): Int {
        val v = when (fatType) {
            12 -> {
                val o = c + c / 2
                if (o + 1 >= fat.size) return 0
                val w = u8(o) or (u8(o + 1) shl 8)
                (if (c and 1 == 1) w shr 4 else w and 0xFFF).also { if (it >= 0xFF0) return 0 }
            }
            16 -> {
                val o = c * 2
                if (o + 1 >= fat.size) return 0
                (u8(o) or (u8(o + 1) shl 8)).also { if (it >= 0xFFF0) return 0 }
            }
            else -> {
                val o = c * 4
                if (o + 3 >= fat.size) return 0
                ((u8(o) or (u8(o + 1) shl 8) or (u8(o + 2) shl 16) or (u8(o + 3) shl 24)) and 0x0FFFFFFF)
                    .also { if (it >= 0x0FFFFFF0) return 0 }
            }
        }
        return if (v < 2) 0 else v
    }

    private fun u8(o: Int) = fat[o].toInt() and 0xFF

    private class Raw(val name: String, val dir: Boolean, val cluster: Int, val size: Long)

    private fun parseDir(buf: ByteArray): List<Raw> {
        val out = ArrayList<Raw>()
        var lfn = ArrayList<String>()
        var i = 0
        while (i + 32 <= buf.size) {
            val first = buf[i].toInt() and 0xFF
            val attr = buf[i + 11].toInt() and 0xFF
            if (first == 0) break
            if (first == 0xE5) { lfn = ArrayList(); i += 32; continue }
            if (attr == 0x0F) {
                val sb = StringBuilder()
                for (r in listOf(1 until 11, 14 until 26, 28 until 32)) for (k in r step 2) {
                    val ch = ((buf[i + k].toInt() and 0xFF) or ((buf[i + k + 1].toInt() and 0xFF) shl 8)).toChar()
                    if (ch == '\u0000' || ch == '\uFFFF') break
                    sb.append(ch)
                }
                lfn.add(0, sb.toString())
                i += 32; continue
            }
            if (attr and 0x08 == 0) {
                val nt = buf[i + 12].toInt() and 0xFF
                var base = String(buf, i, 8, Charsets.ISO_8859_1).trimEnd()
                if (first == 0x05) base = "\u00E5" + base.drop(1)
                var ext = String(buf, i + 8, 3, Charsets.ISO_8859_1).trimEnd()
                if (nt and 0x08 != 0) base = base.lowercase()
                if (nt and 0x10 != 0) ext = ext.lowercase()
                val name = if (lfn.isNotEmpty()) lfn.joinToString("") else if (ext.isEmpty()) base else "$base.$ext"
                val hi = if (fatType == 32) (buf[i + 20].toInt() and 0xFF) or ((buf[i + 21].toInt() and 0xFF) shl 8) else 0
                val lo = (buf[i + 26].toInt() and 0xFF) or ((buf[i + 27].toInt() and 0xFF) shl 8)
                val size = (buf[i + 28].toLong() and 0xFF) or ((buf[i + 29].toLong() and 0xFF) shl 8) or
                    ((buf[i + 30].toLong() and 0xFF) shl 16) or ((buf[i + 31].toLong() and 0xFF) shl 24)
                if (name != "." && name != "..") out.add(Raw(name, attr and 0x10 != 0, (hi shl 16) or lo, size))
            }
            lfn = ArrayList()
            i += 32
        }
        return out
    }

    companion object {
        private const val MAX_ENTRIES = 20_000
        private const val MAX_DEPTH = 8
        private const val MAX_DIR_BYTES = 4 shl 20
        private const val MAX_FAT_BYTES = 64 shl 20

        private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        private fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)

        /** Boot sector sanity check on the first 512 bytes: jump code, 55 AA, sane geometry. */
        fun probe(h: ByteArray): Boolean {
            if (h.size < 512) return false
            val j = h[0].toInt() and 0xFF
            if (!((j == 0xEB && (h[2].toInt() and 0xFF) == 0x90) || j == 0xE9)) return false
            if ((h[510].toInt() and 0xFF) != 0x55 || (h[511].toInt() and 0xFF) != 0xAA) return false
            val bps = u16(h, 11); val spc = h[13].toInt() and 0xFF
            val nfat = h[16].toInt() and 0xFF
            return bps in listOf(512, 1024, 2048, 4096) && spc in 1..128 && spc and (spc - 1) == 0 &&
                u16(h, 14) >= 1 && nfat in 1..2 && (h[21].toInt() and 0xFF) >= 0xF0
        }

        /** The volume in [file], or null when it is not a FAT volume. */
        fun open(file: File): FatReader? {
            val raf = RandomAccessFile(file, "r")
            try {
                val h = ByteArray(512)
                if (raf.length() < 512) { raf.close(); return null }
                raf.readFully(h)
                if (!probe(h)) { raf.close(); return null }
                val bps = u16(h, 11); val spc = h[13].toInt() and 0xFF
                val res = u16(h, 14); val nfat = h[16].toInt() and 0xFF
                val rootEnt = u16(h, 17)
                val total = (if (u16(h, 19) != 0) u16(h, 19).toLong() else u32(h, 32))
                val fatSz = (if (u16(h, 22) != 0) u16(h, 22).toLong() else u32(h, 36))
                val rootSectors = (rootEnt * 32 + bps - 1) / bps
                val dataSectors = total - (res + nfat * fatSz + rootSectors)
                if (fatSz <= 0 || dataSectors <= 0) { raf.close(); return null }
                val clusters = (dataSectors / spc).toInt()
                val type = if (clusters < 4085) 12 else if (clusters < 65525) 16 else 32
                val fatBytes = minOf(fatSz * bps, MAX_FAT_BYTES.toLong(), raf.length() - res.toLong() * bps).toInt()
                if (fatBytes <= 0) { raf.close(); return null }
                val fat = ByteArray(fatBytes)
                raf.seek(res.toLong() * bps); raf.readFully(fat)
                val rootStart = (res + nfat * fatSz) * bps
                val dataStart = rootStart + rootSectors.toLong() * bps
                return FatReader(raf, bps, spc, type, rootStart, rootSectors * bps,
                    if (type == 32) u32(h, 44).toInt() else 0, dataStart, clusters, fat)
            } catch (t: IOException) {
                raf.close(); throw t
            }
        }
    }
}
