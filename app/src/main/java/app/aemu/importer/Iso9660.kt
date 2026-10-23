/* Added for AEmulator Plus, 2026-10-23: ISO 9660 images (Android-x86 live CDs). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.importer

import java.io.IOException
import java.nio.ByteBuffer

/**
 * Read-only ISO 9660 reader (ECMA-119) for the live CD / installer images of Android-x86 and its descendants
 * (Bliss OS, PrimeOS, Phoenix OS ...). Such an image is only a container: `kernel`, `initrd.img` (the live-boot
 * script), `ramdisk.img` (the Android root file system, a gzip'ed cpio), `system.sfs` (a SquashFS that holds
 * `system.img`, an ext4 image) or a plain `system.img`, and `install.img`. The importer picks the two files the
 * guest needs, see Importer.importIso.
 *
 * Only the primary volume descriptor is read. File names come from the Rock Ridge `NM` entry when the image has
 * one and from the ISO 9660 identifier otherwise (version suffix `;1` and a trailing dot removed). A file split
 * into several extents (multi-extent records, files of 4 GiB and more) is presented as one source. Nothing is
 * copied: [open] returns a window onto the image.
 */
internal class Iso9660(private val src: RandomSource) {

    /** A file or directory of the image. [path] has no leading slash. */
    class Entry(val path: String, val isDir: Boolean, val size: Long, internal val extents: List<Pair<Long, Long>>) {
        val name: String get() = path.substringAfterLast('/')
    }

    private val rootLba: Long
    private val rootSize: Long
    /** bytes of every system use area to skip before the SUSP entries (the `SP` entry of the root's `.` record) */
    private var suspSkip = 0

    init {
        val pvd = primaryDescriptor(src) ?: throw IOException("not an ISO 9660 image")
        // the root directory record is 34 bytes at offset 156 of the descriptor
        rootLba = u32le(pvd, 156 + 2)
        rootSize = u32le(pvd, 156 + 10)
        if (rootSize <= 0 || rootLba * SECTOR >= src.size) throw IOException("ISO 9660: bad root directory")
        readSuspSkip()
    }

    /** Every file and directory, parents first. [maxEntries] bounds a damaged or hostile image. */
    fun entries(checkCancelled: () -> Unit = {}, maxEntries: Int = 200_000): List<Entry> {
        val out = ArrayList<Entry>()
        val seen = HashSet<Long>()
        val queue = ArrayDeque<Pair<String, Pair<Long, Long>>>()   // (directory path, (extent lba, size))
        queue.add("" to (rootLba to rootSize))
        seen.add(rootLba)
        while (queue.isNotEmpty()) {
            checkCancelled()
            val (dir, ext) = queue.removeFirst()
            for (r in readDirectory(ext.first, ext.second)) {
                val path = if (dir.isEmpty()) r.name else "$dir/${r.name}"
                if (r.name.isEmpty() || r.name.contains('/') || r.name == "." || r.name == "..") continue
                if (out.size >= maxEntries) throw IOException("ISO 9660: too many entries")
                if (r.isDir) {
                    // a directory that points back to one already read would loop for ever
                    if (!seen.add(r.extents[0].first)) continue
                    out.add(Entry(path, true, 0, emptyList()))
                    queue.add(path to r.extents[0])
                } else {
                    out.add(Entry(path, false, r.extents.sumOf { it.second }, r.extents))
                }
            }
        }
        return out
    }

    /** The contents of a file as a random-access source onto the image. */
    fun open(e: Entry): RandomSource {
        require(!e.isDir) { "directory" }
        val sectors = e.extents.map { (lba, len) -> lba * SECTOR to len }
        for ((off, len) in sectors) if (off < 0 || off + len > src.size) throw IOException("ISO 9660: ${e.path} lies outside the image (truncated download?)")
        return ExtentSource(src, sectors)
    }

    // ------------------------------------------------------------------ directories

    private class Rec(val name: String, val isDir: Boolean, val extents: List<Pair<Long, Long>>)

    private fun readDirectory(lba: Long, size: Long): List<Rec> {
        if (size > MAX_DIR_BYTES) throw IOException("ISO 9660: directory too large")
        val buf = ByteArray(size.toInt())
        src.read(lba * SECTOR, ByteBuffer.wrap(buf))
        val out = ArrayList<Rec>()
        var pos = 0
        // multi-extent file: records with the "more follows" flag are collected until the last one
        var pendingName: String? = null
        var pending = ArrayList<Pair<Long, Long>>()
        while (pos < buf.size) {
            val len = buf[pos].toInt() and 0xff
            if (len == 0) { pos = (pos / SECTOR.toInt() + 1) * SECTOR.toInt(); continue }   // padding at the end of a sector
            if (len < 34 || pos + len > buf.size) break
            val flags = buf[pos + 25].toInt() and 0xff
            val nameLen = buf[pos + 32].toInt() and 0xff
            if (33 + nameLen > len) break
            val ident = buf.copyOfRange(pos + 33, pos + 33 + nameLen)
            val special = nameLen == 1 && (ident[0].toInt() == 0 || ident[0].toInt() == 1)
            if (!special) {
                val sysStart = pos + 33 + nameLen + (if (nameLen % 2 == 0) 1 else 0)
                val name = rockRidgeName(buf, sysStart, pos + len) ?: isoName(ident)
                val extLba = u32le(buf, pos + 2)
                val extLen = u32le(buf, pos + 10)
                val isDir = flags and 0x02 != 0
                if (isDir) {
                    out.add(Rec(name, true, listOf(extLba to extLen)))
                } else {
                    pendingName = pendingName ?: name
                    pending.add(extLba to extLen)
                    if (flags and 0x80 == 0) {   // last (or only) extent
                        out.add(Rec(pendingName, false, pending))
                        pendingName = null; pending = ArrayList()
                    }
                }
            }
            pos += len
        }
        return out
    }

    /** Level 1-3 identifier: no version suffix, no trailing dot; the case is kept (Android-x86 images use lower case). */
    private fun isoName(ident: ByteArray): String {
        var s = String(ident, Charsets.ISO_8859_1)
        val semi = s.lastIndexOf(';')
        if (semi > 0) s = s.substring(0, semi)
        return s.trimEnd('.')
    }

    /** The `NM` (alternate name) entries of a record's system use area, or null when there are none. */
    private fun rockRidgeName(b: ByteArray, from: Int, to: Int): String? {
        var p = from + suspSkip
        var name: StringBuilder? = null
        while (p + 4 <= to) {
            val l = b[p + 2].toInt() and 0xff
            if (l < 4 || p + l > to) break
            if (b[p] == 'N'.code.toByte() && b[p + 1] == 'M'.code.toByte() && l > 5) {
                val flags = b[p + 4].toInt() and 0xff
                // bit 1 = ".", bit 2 = ".."; neither names a real file
                if (flags and 0x06 == 0) (name ?: StringBuilder().also { name = it }).append(String(b, p + 5, l - 5, Charsets.UTF_8))
            }
            p += l
        }
        return name?.toString()?.takeIf { it.isNotEmpty() }
    }

    /** SUSP `SP` entry in the system use area of the root's `.` record: how many bytes to skip in every area. */
    private fun readSuspSkip() {
        val buf = ByteArray(minOf(rootSize, SECTOR).toInt())
        src.read(rootLba * SECTOR, ByteBuffer.wrap(buf))
        val len = buf[0].toInt() and 0xff
        if (len < 34 || len > buf.size) return
        val nameLen = buf[32].toInt() and 0xff
        var p = 33 + nameLen + (if (nameLen % 2 == 0) 1 else 0)
        // SP: 'S' 'P' len=7 version=1 0xBE 0xEF skip
        if (p + 7 <= len && buf[p] == 'S'.code.toByte() && buf[p + 1] == 'P'.code.toByte() &&
            buf[p + 4] == 0xBE.toByte() && buf[p + 5] == 0xEF.toByte()) suspSkip = buf[p + 6].toInt() and 0xff
    }

    companion object {
        const val SECTOR = 2048L
        private const val MAX_DIR_BYTES = 64L shl 20

        /** True if [src] carries an ISO 9660 volume descriptor set: "CD001" in sector 16 or one of the next ones. */
        fun probe(src: RandomSource): Boolean = primaryDescriptor(src) != null

        private fun primaryDescriptor(src: RandomSource): ByteArray? {
            if (src.size < (17 + 1) * SECTOR) return null
            val d = ByteArray(SECTOR.toInt())
            for (i in 0 until 16) {   // the set ends with a terminator (type 255); 16 sectors are plenty
                src.read((16 + i) * SECTOR, ByteBuffer.wrap(d))
                if (String(d, 1, 5, Charsets.ISO_8859_1) != "CD001") return null
                when (d[0].toInt() and 0xff) {
                    1 -> return d.copyOf()
                    255 -> return null
                }
            }
            return null
        }

        private fun u32le(b: ByteArray, o: Int): Long =
            (b[o].toLong() and 0xff) or ((b[o + 1].toLong() and 0xff) shl 8) or
                ((b[o + 2].toLong() and 0xff) shl 16) or ((b[o + 3].toLong() and 0xff) shl 24)
    }
}
