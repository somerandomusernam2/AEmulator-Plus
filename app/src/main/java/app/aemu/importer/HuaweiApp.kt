package app.aemu.importer

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Huawei `UPDATE.APP` (dload/UPDATE.APP, UPDATE_cust.APP; U8xxx / Ideos / Honor-era Qualcomm firmware).
 *
 * The file is 0x5C bytes of zero padding followed by a chain of packets, one per flashed image:
 *
 *   +0x00  55 AA 5A A5            magic
 *   +0x04  u32 LE                 header length = 98 + 2 * blocks
 *   +0x08  u32 LE                 format version (1)
 *   +0x0C  char[8]               hardware id, e.g. "HW7x27A\xFF"
 *   +0x14  u32 BE                 image id (0x00 system, 0x30 userdata, 0x20 modem ELF, ... boot/recovery vary by device)
 *   +0x18  u32 LE                 payload size
 *   +0x1C  char[16] date, +0x2C char[16] time, +0x3C char[16] "INPUT", +0x4C char[16] blank
 *   +0x5C  u16 LE                 header checksum (not verified)
 *   +0x5E  u16 LE                 block size (0x1000)
 *   +0x60  u16                    blank
 *   +0x62  u16 LE[blocks]         CRC-16/X-25 (poly 0x1021 reflected, init/xorout 0xFFFF) of each block
 *   +hdr   payload, size bytes; the next packet starts at the next 4-byte boundary
 *
 * Payloads are raw partition images: NAND YAFFS2 dumps (2048+64 pages) on the Gingerbread-era devices, boot/recovery
 * "ANDROID!" images, the modem ELF, bootloader pieces and small partition descriptors.
 */
internal object HuaweiApp {
    private const val FIRST = 0x5CL
    private const val FIXED = 98
    private val MAGIC = byteArrayOf(0x55, 0xAA.toByte(), 0x5A, 0xA5.toByte())

    /** image id of the system partition in every package seen so far */
    const val ID_SYSTEM = 0x00

    class Packet(
        val id: Int, val offset: Long, val dataOffset: Long, val size: Long, val blockSize: Int,
        val hardware: String, val date: String,
    ) {
        val blocks: Int get() = if (blockSize > 0) ((size + blockSize - 1) / blockSize).toInt() else 0
        override fun toString() = "id 0x%02x, %d bytes".format(id, size)
    }

    fun probe(h: ByteArray): Boolean = h.size >= 0x60 && (0..3).all { h[0x5C + it] == MAGIC[it] }

    /**
     * All packets of the chain. Stops quietly at the first position that does not carry the magic (trailing
     * padding); a packet that claims more bytes than the file holds means a truncated download.
     */
    fun packets(src: RandomSource): List<Packet> {
        val out = ArrayList<Packet>()
        val b = ByteBuffer.allocate(FIXED).order(ByteOrder.LITTLE_ENDIAN)
        var pos = FIRST
        while (pos + FIXED <= src.size) {
            b.clear(); src.read(pos, b); b.flip()
            if ((0..3).any { b.get(it) != MAGIC[it] }) break
            val hl = u32(b, 4)
            val id = ((b.get(0x14).toInt() and 0xff) shl 24) or ((b.get(0x15).toInt() and 0xff) shl 16) or
                ((b.get(0x16).toInt() and 0xff) shl 8) or (b.get(0x17).toInt() and 0xff)
            val size = u32(b, 0x18)
            val bs = b.getShort(0x5E).toInt() and 0xffff
            val blocks = if (bs > 0) (size + bs - 1) / bs else 0L
            if (hl != FIXED + 2 * blocks) throw IOException("Huawei update: corrupt packet header at 0x${pos.toString(16)}")
            val data = pos + hl
            if (data + size > src.size) throw IOException("Huawei update: truncated file (packet at 0x${pos.toString(16)} needs ${data + size} bytes)")
            out.add(Packet(id, pos, data, size, bs, cstr(b, 0x0C, 8), cstr(b, 0x1C, 16)))
            pos = (data + size + 3) and 3L.inv()
        }
        return out
    }

    /** Number of blocks of [p] whose CRC does not match the packet's checksum table (0 = intact). */
    fun verify(src: RandomSource, p: Packet, checkCancelled: () -> Unit = {}): Int {
        val n = p.blocks
        if (n == 0) return 0
        val table = ByteBuffer.allocate(2 * n).order(ByteOrder.LITTLE_ENDIAN)
        src.read(p.offset + FIXED, table); table.flip()
        val per = maxOf(1, (1 shl 20) / p.blockSize)
        val buf = ByteArray(per * p.blockSize)
        var bad = 0
        var block = 0
        while (block < n) {
            checkCancelled()
            val count = minOf(per, n - block)
            val start = p.dataOffset + block.toLong() * p.blockSize
            val len = minOf(count.toLong() * p.blockSize, p.size - block.toLong() * p.blockSize).toInt()
            val bb = ByteBuffer.wrap(buf, 0, len); src.read(start, bb)
            for (i in 0 until count) {
                val off = i * p.blockSize
                val l = minOf(p.blockSize, len - off)
                if (crc16(buf, off, l) != (table.getShort(2 * (block + i)).toInt() and 0xffff)) bad++
            }
            block += count
        }
        return bad
    }

    private val TABLE = IntArray(256).also {
        for (i in 0 until 256) {
            var c = i
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8408 else c ushr 1 }
            it[i] = c
        }
    }

    /** CRC-16/X-25 */
    fun crc16(d: ByteArray, off: Int, len: Int): Int {
        var c = 0xffff
        for (i in off until off + len) c = (c ushr 8) xor TABLE[(c xor d[i].toInt()) and 0xff]
        return c xor 0xffff
    }

    private fun u32(b: ByteBuffer, at: Int) = b.getInt(at).toLong() and 0xffffffffL
    private fun cstr(b: ByteBuffer, at: Int, n: Int): String {
        val a = ByteArray(n) { b.get(at + it) }
        val end = a.indexOfFirst { it.toInt() == 0 || it.toInt() == -1 }.let { if (it < 0) n else it }
        return String(a, 0, end, Charsets.ISO_8859_1)
    }
}
