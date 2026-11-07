package app.aemu.importer

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Acer flash package ("PTT" download package: `Acer_E110_1.008.00_EMEA.nb0`, also shipped as `*.bin`;
 * Liquid / Liquid mini / Liquid E and other ST-Ericsson / Qualcomm Acer phones of the Froyo-Gingerbread era).
 *
 * The file is a flat archive: a table of fixed 0x40-byte records, then the payloads back to back with no padding.
 *
 *   +0x00  table:  count * 0x40 bytes
 *            record  +0x00  u32 LE   entry count (record 0 only, 0 in the others)
 *                    +0x04  u32 LE   payload offset, relative to the end of the table (= previous offset + size)
 *                    +0x08  u32 LE   payload size
 *                    +0x0C  u64      reserved (0)
 *                    +0x14  char[44] file name, NUL padded
 *   +table payloads, in table order
 *
 * The first payload is the download profile (`*.mlf`, INI text: PROJECT / PLATFORM / MODEL and the flash address
 * of every image); the remaining ones are the flasher, boot loader pieces, kernel and the NAND partition images
 * (`rootfs.img`, `system.img`, `recovery.img`, `userdata.img`, ...). The file system images are raw YAFFS2 NAND
 * dumps (2048 + 64 byte pages, tags at the start of the spare area); every image starts with one extra 32-bit word
 * in front of its first page, so its last page is 4 bytes short (see [Yaffs2Reader], which finds the offset itself).
 */
internal object AcerBin {
    const val RECORD = 0x40
    private const val NAME_AT = 0x14
    private const val MAX_ENTRIES = 256
    private const val MAX_PROFILE = 64 * 1024

    /** Smallest file that can hold a table with two records. */
    const val MIN_SIZE = 2L * RECORD

    /** [offset] is absolute (from the start of the file). */
    class Entry(val name: String, val offset: Long, val size: Long) {
        /** file name without folders */
        val base: String get() = name.substringAfterLast('/').substringAfterLast('\\')
        override fun toString() = "$name ($size bytes)"
    }

    /**
     * True if [h] (the first bytes of a file, at least a few records) is the table of an Acer package. Every record
     * that fits into [h] has to be well formed and contiguous with the previous one, which makes accidental
     * matches practically impossible.
     */
    fun probe(h: ByteArray): Boolean {
        if (h.size < MIN_SIZE) return false
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val count = u32(b, 0)
        if (count < 2 || count > MAX_ENTRIES) return false
        val records = minOf(count, (h.size / RECORD).toLong()).toInt()
        if (records < 2) return false
        var expect = 0L
        for (i in 0 until records) {
            val at = i * RECORD
            if (u32(b, at + 4) != expect) return false
            if (readName(h, at + NAME_AT) == null) return false
            expect += u32(b, at + 8)
        }
        return true
    }

    /**
     * All entries of the package in table order. Trailing bytes after the last payload (the packages end with a few
     * padding bytes) are ignored; a payload that reaches beyond the end of the file means a truncated download.
     */
    fun entries(src: RandomSource): List<Entry> {
        if (src.size < MIN_SIZE) throw IOException("Acer package: file too small")
        val first = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        src.read(0, first); first.flip()
        val count = u32(first, 0)
        if (count < 2 || count > MAX_ENTRIES) throw IOException("Acer package: bad entry count $count")
        val tableSize = count * RECORD
        if (tableSize > src.size) throw IOException("Acer package: truncated file (table needs $tableSize bytes)")
        val t = ByteBuffer.allocate(tableSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        src.read(0, t); t.flip()
        val out = ArrayList<Entry>(count.toInt())
        var expect = 0L
        for (i in 0 until count.toInt()) {
            val at = i * RECORD
            val off = u32(t, at + 4)
            val size = u32(t, at + 8)
            val name = readName(t.array(), at + NAME_AT) ?: throw IOException("Acer package: bad file name in record $i")
            if (off != expect) throw IOException("Acer package: record $i ($name) is not contiguous")
            val abs = tableSize + off
            if (abs + size > src.size)
                throw IOException("Acer package: truncated file ($name needs ${abs + size} bytes, file has ${src.size})")
            out.add(Entry(name, abs, size))
            expect = off + size
        }
        return out
    }

    /**
     * Key/value pairs of the `[Package Download Profile]` section of the `*.mlf` entry (PROJECT, PLATFORM, MODEL,
     * PACKAGE NUMBER, ...), or an empty map. Informational only.
     */
    fun profile(src: RandomSource, entries: List<Entry>): Map<String, String> {
        val e = entries.firstOrNull { it.base.endsWith(".mlf", true) } ?: return emptyMap()
        if (e.size <= 0) return emptyMap()
        val buf = ByteBuffer.allocate(minOf(e.size, MAX_PROFILE.toLong()).toInt())
        SliceSource(src, e.offset, e.size).read(0, buf)
        val text = String(buf.array(), Charsets.ISO_8859_1)
        val out = LinkedHashMap<String, String>()
        var inFirst = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                if (inFirst) break // only the global section
                inFirst = line.equals("[Package Download Profile]", true)
                continue
            }
            if (!inFirst) continue
            val eq = line.indexOf('=')
            if (eq > 0) out[line.substring(0, eq).trim().uppercase()] = line.substring(eq + 1).trim()
        }
        return out
    }

    /** The root file system image of the package (the ramdisk contents). `rootfs_ftm.img` is the factory-test one. */
    fun isRootfs(base: String) = ROOTFS.matches(base)

    private val ROOTFS = Regex("(?i)rootfs(\\.img)?")

    /** Entry name: printable ASCII, NUL terminated (and NUL padded). Null if the field is not a plausible name. */
    private fun readName(a: ByteArray, at: Int): String? {
        val len = RECORD - NAME_AT
        if (at + len > a.size) return null
        var end = 0
        while (end < len && a[at + end].toInt() != 0) end++
        if (end == 0) return null
        for (i in 0 until end) {
            val c = a[at + i].toInt() and 0xff
            if (c < 0x20 || c >= 0x7f) return null
        }
        for (i in end until len) if (a[at + i].toInt() != 0) return null
        return String(a, at, end, Charsets.US_ASCII)
    }

    private fun u32(b: ByteBuffer, at: Int) = b.getInt(at).toLong() and 0xffffffffL
}
