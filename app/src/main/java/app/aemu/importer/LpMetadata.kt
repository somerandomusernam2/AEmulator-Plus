package app.aemu.importer

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android dynamic partitions (Android 10+): the "super" partition holds a small logical-partition table (liblp) and the
 * logical partitions system, system_ext, product, vendor, odm ... as runs of 512-byte sectors. SDK emulator packages of
 * Android 11+ (system.img = GPT disk with vbmeta + super) ship their whole system this way.
 *
 * Layout of the super partition: 4096 reserved bytes, geometry (4096 bytes) and its backup, then `metadata_slot_count`
 * copies of the metadata (header + tables), `metadata_max_size` bytes each, followed by the same number of backup copies.
 * Only the first (primary) slot is read: nothing here is ever written back.
 */
object LpMetadata {
    private const val GEOMETRY_MAGIC = 0x616c4467
    private const val HEADER_MAGIC = 0x414c5030
    private const val RESERVED = 4096
    private const val GEOMETRY_SIZE = 4096
    private const val SECTOR = 512L
    /** A metadata copy larger than this is not believed. */
    private const val MAX_METADATA = 4 shl 20
    private const val EXTENT_LINEAR = 0

    /** One run of a logical partition: [length] bytes taken from [superOffset] of the super partition. */
    class Extent(val superOffset: Long, val length: Long)

    class Partition(val name: String, val extents: List<Extent>) {
        val size: Long get() = extents.sumOf { it.length }
    }

    /** Bytes of the super partition that [parse] needs: reserved area, both geometry copies and one metadata slot. */
    fun headSize(first: ByteArray): Int? {
        if (first.size < RESERVED + 52) return null
        val g = ByteBuffer.wrap(first, RESERVED, 52).order(ByteOrder.LITTLE_ENDIAN)
        if (g.getInt(RESERVED) != GEOMETRY_MAGIC) return null
        val maxSize = g.getInt(RESERVED + 40)
        if (maxSize <= 0 || maxSize > MAX_METADATA || maxSize % 512 != 0) return null
        return RESERVED + 2 * GEOMETRY_SIZE + maxSize
    }

    /** True if [first] (the start of a partition) is the start of a super partition. */
    fun probe(first: ByteArray): Boolean = headSize(first) != null

    /**
     * Logical partitions of the first metadata slot, or null if [head] (at least [headSize] bytes) is not valid metadata.
     * Zero-length partitions and partitions with anything other than linear extents are left out.
     */
    fun parse(head: ByteArray): List<Partition>? = runCatching {
        val need = headSize(head) ?: return null
        if (head.size < need) return null
        val start = RESERVED + 2 * GEOMETRY_SIZE
        val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(start) != HEADER_MAGIC) return null
        val major = b.getShort(start + 4).toInt() and 0xffff
        if (major != 10) return null
        val headerSize = b.getInt(start + 8)
        val tablesSize = b.getInt(start + 44)
        if (headerSize < 128 || tablesSize < 0 || start.toLong() + headerSize + tablesSize > head.size) return null
        // descriptors: offset, count, entry size for partitions, extents, groups, block devices
        val pOff = b.getInt(start + 80); val pNum = b.getInt(start + 84); val pSz = b.getInt(start + 88)
        val eOff = b.getInt(start + 92); val eNum = b.getInt(start + 96); val eSz = b.getInt(start + 100)
        if (pSz < 52 || eSz < 24 || pNum !in 0..1024 || eNum !in 0..16384) return null
        if (pOff < 0 || eOff < 0 || pOff.toLong() + pNum.toLong() * pSz > tablesSize ||
            eOff.toLong() + eNum.toLong() * eSz > tablesSize) return null
        val tables = start + headerSize
        val out = ArrayList<Partition>()
        for (i in 0 until pNum) {
            val p = tables + pOff + i * pSz
            var n = 0
            while (n < 36 && head[p + n].toInt() != 0) n++
            val name = String(head, p, n, Charsets.US_ASCII)
            val firstExtent = b.getInt(p + 40)
            val numExtents = b.getInt(p + 44)
            if (numExtents <= 0 || firstExtent < 0 || firstExtent.toLong() + numExtents > eNum) continue
            val extents = ArrayList<Extent>()
            var ok = true
            for (k in 0 until numExtents) {
                val e = tables + eOff + (firstExtent + k) * eSz
                val sectors = b.getLong(e)
                val type = b.getInt(e + 8)
                val target = b.getLong(e + 12)
                if (type != EXTENT_LINEAR || sectors <= 0 || target < 0) { ok = false; break }
                extents.add(Extent(target * SECTOR, sectors * SECTOR))
            }
            if (ok && extents.isNotEmpty()) out.add(Partition(name, extents))
        }
        out
    }.getOrNull()

    /** "system_a" -> "system" (slot suffixes are dropped; the caller decides which slot it takes). */
    fun baseName(name: String): String = name.removeSuffix("_a").removeSuffix("_b")

    /**
     * The logical partitions worth importing: system, vendor, system_ext, product and odm, one each. A/B images list
     * both slots; slot "_a" wins and a "_b" copy is only used when there is no "_a" or unsuffixed one.
     */
    fun pick(parts: List<Partition>): List<Partition> {
        val wanted = listOf("system", "vendor", "system_ext", "product", "odm")
        return wanted.mapNotNull { w ->
            parts.firstOrNull { it.name == w } ?: parts.firstOrNull { it.name == w + "_a" } ?: parts.firstOrNull { it.name == w + "_b" }
        }
    }
}
