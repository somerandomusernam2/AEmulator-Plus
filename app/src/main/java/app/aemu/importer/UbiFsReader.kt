package app.aemu.importer

import com.github.luben.zstd.Zstd
import org.anarres.lzo.LzoAlgorithm
import org.anarres.lzo.LzoLibrary
import org.anarres.lzo.lzo_uintp
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap
import java.util.TreeSet
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * Reader for raw UBI images (what a NAND "system.img" of MediaTek / Spreadtrum / Qualcomm-NAND phones is) carrying a UBIFS
 * volume.
 *
 * UBI layer: the erase block size is found from the stride of the "UBI#" erase-counter headers, every block's
 * volume-id header ("UBI!") maps (volume, LEB number) to a physical block; when a LEB is mapped twice the copy with
 * the higher sequence number wins. Internal volumes (the layout volume) are ignored and the first volume whose LEB 0
 * starts with a UBIFS node is the file system.
 *
 * UBIFS layer: instead of walking the index (which would need the master node and a replayed journal) every LEB of the
 * volume is scanned for nodes. Each node is CRC-checked; for every key (inode number / directory entry / data block)
 * the node with the highest sequence number wins, which is exactly what journal replay does. A directory entry with
 * inode number 0 is a deletion, an inode with nlink 0 is a deleted file. Truncation nodes and extended attributes are
 * not needed for an Android /system tree and are ignored; data blocks beyond the inode size are never read.
 *
 * Data compression: none, LZO, zlib and "type 3", which mainline defines as zstd but MediaTek-era kernels use for LZ4K
 * ([Lz4k]). A type-3 block that starts with the zstd frame magic is decoded as zstd, anything else as LZ4K.
 *
 * Not supported (reported as an [IOException]): encrypted (fscrypt) volumes.
 */
class UbiFsReader(private val src: RandomSource, private val checkCancelled: () -> Unit = {}) {
    /** [type]: 1 regular file, 2 symlink, 3 directory, 5 device / fifo / socket */
    class Node(val inum: Long, val type: Int, val mode: Int, val size: Long, val target: String)

    private class Leb(var sqnum: Long, var peb: Int)
    private class Ino(val sqnum: Long, val mode: Int, val size: Long, val nlink: Int, val target: String?)
    private data class DKey(val parent: Long, val name: String)
    private class Dent(val sqnum: Long, val inum: Long)
    private class Dat(val sqnum: Long, val leb: Int, val off: Int, val len: Int)
    private class Child(val name: String, val inum: Long)

    val warnings = ArrayList<String>()
    val pebSize: Int
    val lebSize: Int
    val volumeId: Int
    val defaultCompr: Int
    private val dataOffset: Int
    private val lebs: HashMap<Int, Leb>
    private val inodes = HashMap<Long, Ino>()
    private val dents = HashMap<DKey, Dent>()
    private val data = HashMap<Long, Dat>()
    private val comprSeen = TreeSet<Int>()

    /** One line for the import log. */
    val summary: String

    init {
        val head = ByteBuffer.allocate(64); src.read(0, head)
        if (head.getInt(0) != UBI_EC_MAGIC) throw IOException("not a UBI image")
        val vidOff = head.getInt(16)
        dataOffset = head.getInt(20)
        pebSize = detectPebSize(vidOff, dataOffset)
        lebSize = pebSize - dataOffset
        val pebCount = (src.size / pebSize).toInt()

        // UBI: (volume, LEB) -> physical erase block, newest copy wins
        val volumes = TreeMap<Int, HashMap<Int, Leb>>()
        val hdr = ByteBuffer.allocate(64)
        for (peb in 0 until pebCount) {
            if (peb % 64 == 0) checkCancelled()
            val base = peb.toLong() * pebSize
            hdr.clear(); src.read(base, hdr)
            if (hdr.getInt(0) != UBI_EC_MAGIC) continue
            hdr.clear(); src.read(base + vidOff, hdr)
            if (hdr.getInt(0) != UBI_VID_MAGIC) continue
            val volId = hdr.getInt(8).toLong() and 0xffffffffL
            if (volId >= UBI_INTERNAL_VOL_START) continue
            val lnum = hdr.getInt(12)
            val sqnum = hdr.getLong(40)
            if (lnum < 0 || lnum > 1_000_000) continue
            val map = volumes.getOrPut(volId.toInt()) { HashMap() }
            val old = map[lnum]
            if (old == null) map[lnum] = Leb(sqnum, peb)
            else if (sqnum > old.sqnum) { old.sqnum = sqnum; old.peb = peb }
        }
        if (volumes.isEmpty()) throw IOException("UBI image has no data volumes")

        // the UBIFS volume: LEB 0 holds the superblock node
        var chosen = -1
        for ((id, map) in volumes) {
            val l0 = map[0] ?: continue
            val b = ByteBuffer.allocate(24); src.read(l0.peb.toLong() * pebSize + dataOffset, b)
            b.order(ByteOrder.LITTLE_ENDIAN)
            if (b.getInt(0) == UBIFS_MAGIC && b.get(20).toInt() == NODE_SB) { chosen = id; break }
        }
        if (chosen < 0) throw IOException("UBI image has no UBIFS volume")
        volumeId = chosen
        lebs = volumes.getValue(chosen)

        val sb = readLeb(0)
        val sbb = ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN)
        val flags = sbb.getInt(28)
        defaultCompr = sbb.getShort(84).toInt() and 0xffff
        if (flags and UBIFS_FLG_ENCRYPTION != 0) throw IOException("UBIFS volume is encrypted (fscrypt), not supported")
        val sbLeb = sbb.getInt(36)
        if (sbLeb != lebSize) warnings.add("superblock LEB size $sbLeb differs from the UBI LEB size $lebSize")

        scan()
        if (inodes[ROOT_INO] == null) throw IOException("UBIFS volume has no root directory (damaged image?)")
        summary = "UBI erase block ${pebSize shr 10} KB, LEB $lebSize, volume $chosen (${lebs.size} LEBs), " +
            "UBIFS ${inodes.size} inodes, ${dents.size} entries, ${data.size} data nodes, compression types $comprSeen"
    }

    private fun detectPebSize(vidOff: Int, dataOff: Int): Int {
        if (vidOff < 64 || dataOff <= vidOff) throw IOException("UBI: bad erase counter header")
        var cand = 0x4000
        val b = ByteBuffer.allocate(8)
        while (cand <= 0x1000000) {
            // every block of the image is checked: a stride that is too small hits file data sooner or later, while
            // sampling only the start could see nothing but free space and settle on half the real block size
            val n = (src.size / cand).toInt()
            if (cand > dataOff && n >= 2) {
                var ok = true
                for (i in 0 until n) {
                    if (i % 256 == 0) checkCancelled()
                    b.clear(); src.read(i.toLong() * cand, b)
                    val magic = b.getInt(0) == UBI_EC_MAGIC
                    val erased = b.getLong(0) == -1L
                    if (!magic && !erased) { ok = false; break }
                }
                if (ok) return cand
            }
            cand = cand shl 1
        }
        throw IOException("UBI: cannot determine the erase block size")
    }

    private fun readLeb(lnum: Int): ByteArray {
        val l = lebs[lnum] ?: throw IOException("UBIFS: LEB $lnum is not mapped")
        val arr = ByteArray(lebSize)
        src.read(l.peb.toLong() * pebSize + dataOffset, ByteBuffer.wrap(arr))
        return arr
    }

    private fun scan() {
        val crc = CRC32()
        var badCrc = 0
        val buf = ByteArray(lebSize)
        val wrap = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        for (lnum in lebs.keys.sorted()) {
            checkCancelled()
            val l = lebs.getValue(lnum)
            wrap.clear()
            src.read(l.peb.toLong() * pebSize + dataOffset, wrap)
            var p = 0
            while (p + 24 <= lebSize) {
                // anything that is not a node (free space, LPT / orphan areas) ends the scan of this LEB
                if (wrap.getInt(p) != UBIFS_MAGIC) break
                val len = wrap.getInt(p + 16)
                val type = buf[p + 20].toInt()
                if (len < 24 || p + len > lebSize) break
                val interesting = type == NODE_INO || type == NODE_DATA || type == NODE_DENT
                if (interesting) {
                    crc.reset(); crc.update(buf, p + 8, len - 8)
                    if (crc.value.toInt().inv() != wrap.getInt(p + 4)) { badCrc++; p += (len + 7) and 7.inv(); continue }
                    val sqnum = wrap.getLong(p + 8)
                    when (type) {
                        NODE_INO -> if (len >= 160) {
                            val inum = wrap.getInt(p + 24).toLong() and 0xffffffffL
                            val old = inodes[inum]
                            if (old == null || sqnum >= old.sqnum) {
                                val mode = wrap.getInt(p + 104)
                                val dataLen = wrap.getInt(p + 112)
                                val target = if ((mode and S_IFMT) == S_IFLNK && dataLen in 0..4096 && 160 + dataLen <= len)
                                    String(buf, p + 160, dataLen, Charsets.UTF_8) else null
                                inodes[inum] = Ino(sqnum, mode, wrap.getLong(p + 48), wrap.getInt(p + 92), target)
                                comprSeen.add(wrap.getShort(p + 132).toInt() and 0xffff)
                            }
                        }
                        NODE_DENT -> if (len >= 57) {
                            val parent = wrap.getInt(p + 24).toLong() and 0xffffffffL
                            val inum = wrap.getLong(p + 40)
                            val nlen = wrap.getShort(p + 50).toInt() and 0xffff
                            if (56 + nlen <= len) {
                                val key = DKey(parent, String(buf, p + 56, nlen, Charsets.UTF_8))
                                val old = dents[key]
                                if (old == null || sqnum >= old.sqnum) dents[key] = Dent(sqnum, inum)
                            }
                        }
                        NODE_DATA -> if (len > 48) {
                            val inum = wrap.getInt(p + 24).toLong() and 0xffffffffL
                            val blk = (wrap.getInt(p + 28).toLong() and 0x1fffffffL)
                            val key = (inum shl 32) or blk
                            val old = data[key]
                            if (old == null || sqnum >= old.sqnum) data[key] = Dat(sqnum, lnum, p, len)
                        }
                    }
                }
                p += (len + 7) and 7.inv()
            }
        }
        if (badCrc > 0) warnings.add("$badCrc node(s) with a bad CRC were ignored")
    }

    /** Visits every entry below the root (directories before their contents), paths relative to the volume root. */
    fun walk(visit: (String, Node) -> Unit) {
        val kids = HashMap<Long, ArrayList<Child>>()
        for ((k, d) in dents) if (d.inum != 0L) kids.getOrPut(k.parent) { ArrayList() }.add(Child(k.name, d.inum))
        val dirs = HashSet<Long>()
        dirs.add(ROOT_INO)
        var missing = 0
        fun rec(dir: Long, prefix: String, depth: Int) {
            if (depth > 200) throw IOException("UBIFS directory tree too deep")
            val list = kids[dir] ?: return
            list.sortBy { it.name }
            for (c in list) {
                checkCancelled()
                if (c.name.isEmpty() || c.name == "." || c.name == ".." || c.name.indexOf('/') >= 0 || c.name.indexOf('\u0000') >= 0)
                    throw IOException("UBIFS: unsafe file name")
                val ino = inodes[c.inum]
                if (ino == null || ino.nlink == 0) { missing++; continue }
                val path = prefix + c.name
                when (ino.mode and S_IFMT) {
                    S_IFDIR -> {
                        if (!dirs.add(c.inum)) throw IOException("UBIFS: directory loop at $path")
                        visit(path, Node(c.inum, 3, ino.mode, 0, ""))
                        rec(c.inum, "$path/", depth + 1)
                    }
                    S_IFREG -> visit(path, Node(c.inum, 1, ino.mode, ino.size, ""))
                    S_IFLNK -> visit(path, Node(c.inum, 2, ino.mode, ino.size, ino.target ?: ""))
                    else -> visit(path, Node(c.inum, 5, ino.mode, 0, ""))
                }
            }
        }
        rec(ROOT_INO, "", 0)
        if (missing > 0) warnings.add("$missing directory entries point to deleted inodes")
    }

    fun fileMode(node: Node): Int = node.mode

    /** Writes the contents of a regular file; blocks without a data node (sparse holes) read as zeros. */
    fun copy(node: Node, out: OutputStream) {
        val zeros = ByteArray(BLOCK)
        var remaining = node.size
        var blk = 0L
        while (remaining > 0) {
            checkCancelled()
            val n = minOf(BLOCK.toLong(), remaining).toInt()
            val d = data[(node.inum shl 32) or blk]
            val block = if (d == null) zeros else readBlock(d)
            val w = minOf(block.size, n)
            out.write(block, 0, w)
            if (w < n) out.write(zeros, 0, n - w)
            remaining -= n
            blk++
        }
    }

    private fun readBlock(d: Dat): ByteArray {
        val l = lebs.getValue(d.leb)
        val raw = ByteArray(d.len)
        src.read(l.peb.toLong() * pebSize + dataOffset + d.off, ByteBuffer.wrap(raw))
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val size = bb.getInt(40)
        val compr = bb.getShort(44).toInt() and 0xffff
        if (size < 0 || size > BLOCK) throw IOException("UBIFS: bad data node size $size")
        return decompress(compr, raw, 48, d.len - 48, size)
    }

    private fun decompress(type: Int, buf: ByteArray, off: Int, len: Int, outLen: Int): ByteArray {
        val out: ByteArray = when (type) {
            0 -> buf.copyOfRange(off, off + len)
            1 -> {
                val o = ByteArray(outLen)
                val n = lzo_uintp(outLen)
                val code = LzoLibrary.getInstance().newDecompressor(LzoAlgorithm.LZO1X, null).decompress(buf, off, len, o, 0, n)
                if (code != 0) throw IOException("UBIFS LZO error $code")
                if (n.value == outLen) o else o.copyOf(n.value)
            }
            2 -> {
                // UBIFS stores raw deflate; the JDK wants one extra byte after a nowrap stream
                val inp = buf.copyOfRange(off, off + len + 1)
                val inf = Inflater(true)
                try {
                    inf.setInput(inp)
                    val o = ByteArray(outLen)
                    var n = 0
                    while (n < outLen && !inf.finished()) {
                        val r = inf.inflate(o, n, outLen - n)
                        if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break
                        n += r
                    }
                    if (n == outLen) o else o.copyOf(n)
                } catch (e: java.util.zip.DataFormatException) {
                    throw IOException("UBIFS zlib block is corrupt: ${e.message}", e)
                } finally { inf.end() }
            }
            3 -> {
                if (len >= 4 && buf[off] == 0x28.toByte() && buf[off + 1] == 0xB5.toByte() &&
                    buf[off + 2] == 0x2F.toByte() && buf[off + 3] == 0xFD.toByte()) {
                    val o = ByteArray(outLen)
                    val n = Zstd.decompress(o, buf.copyOfRange(off, off + len))
                    if (Zstd.isError(n)) throw IOException("UBIFS zstd error: ${Zstd.getErrorName(n)}")
                    if (n.toInt() == outLen) o else o.copyOf(n.toInt())
                } else Lz4k.decompress(buf, off, len, outLen)
            }
            else -> throw IOException("UBIFS: unknown compression type $type")
        }
        return out
    }

    companion object {
        private const val UBI_EC_MAGIC = 0x55424923   // "UBI#"
        private const val UBI_VID_MAGIC = 0x55424921  // "UBI!"
        private const val UBI_INTERNAL_VOL_START = 0x7FFFEFFFL
        private const val UBIFS_MAGIC = 0x06101831
        private const val UBIFS_FLG_ENCRYPTION = 8
        private const val NODE_INO = 0
        private const val NODE_DATA = 1
        private const val NODE_DENT = 2
        private const val NODE_SB = 6
        private const val ROOT_INO = 1L
        private const val BLOCK = 4096
        private const val S_IFMT = 0xF000
        private const val S_IFDIR = 0x4000
        private const val S_IFREG = 0x8000
        private const val S_IFLNK = 0xA000

        /** Cheap check on the first erase-counter header; the constructor does the real validation. */
        fun probe(src: RandomSource): Boolean {
            if (src.size < 0x8000) return false
            val b = ByteBuffer.allocate(64); src.read(0, b)
            if (b.getInt(0) != UBI_EC_MAGIC || b.get(4).toInt() != 1) return false
            val vid = b.getInt(16); val dat = b.getInt(20)
            return vid in 64..0x10000 && dat > vid && dat <= 0x20000
        }
    }
}
