/* Added for AEmulator Plus, 2026-11-04: Chrome extension (CRX2/CRX3) containers, e.g. the Google Play Games developer emulator. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.importer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.util.zip.CRC32

/**
 * A CRX file is a ZIP behind a small header:
 *  - CRX3: "Cr24", version 3 (u32), header length (u32), a protobuf header of that length, then the ZIP;
 *  - CRX2: "Cr24", version 2 (u32), public key length (u32), signature length (u32), key, signature, then the ZIP.
 * The ZIP's offsets count from its own start, not from the start of the file, so it has to be read through a window.
 *
 * The Play Games developer emulator ships as a CRX3 holding one 7-Zip self-extractor (`HPE-*.exe`): the 7zr.exe stub
 * with a plain 7z archive appended ([locateSevenZ]). Inside the 7z, `emulator/avd/aggregate.img` is a GPT disk
 * (super, boot_a, vendor_boot_a, vbmeta_a, vbmeta_system_a), which the Importer's GPT path takes from there.
 */
internal object CrxContainer {
    private val MAGIC = byteArrayOf('C'.code.toByte(), 'r'.code.toByte(), '2'.code.toByte(), '4'.code.toByte())
    private val SEVENZ = byteArrayOf('7'.code.toByte(), 'z'.code.toByte(), 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)

    /** How far into an executable the 7z archive is looked for; a 7-Zip SFX stub is about a megabyte. */
    const val MAX_STUB = 16 shl 20

    fun probe(h: ByteArray): Boolean {
        if (h.size < 12) return false
        for (i in MAGIC.indices) if (h[i] != MAGIC[i]) return false
        val v = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
        return v == 2 || v == 3
    }

    /** Offset of the ZIP inside the CRX, or null if the header is not believable. */
    fun zipStart(src: RandomSource): Long? {
        val b = ByteBuffer.allocate(16).also { src.read(0, it) }.array()
        if (!probe(b)) return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val start = when (bb.getInt(4)) {
            3 -> 12L + (bb.getInt(8).toLong() and 0xffffffffL)
            else -> 16L + (bb.getInt(8).toLong() and 0xffffffffL) + (bb.getInt(12).toLong() and 0xffffffffL)
        }
        if (start >= src.size) return null
        val sig = ByteBuffer.allocate(4).also { src.read(start, it) }.array()
        // local file header, or an empty archive's end record
        return if (sig[0] == 'P'.code.toByte() && sig[1] == 'K'.code.toByte()) start else null
    }

    /**
     * Offset of the 7z archive in [buf] (its first [n] bytes), or -1. The signature alone is not enough: the stub is
     * a program that has the six bytes inside its own code (the Play Games installer has them 23 KB before the real
     * archive), so a candidate counts only if the CRC of its start header matches and the header it points to
     * fits in the remaining [total] bytes of the file.
     */
    fun locateSevenZ(buf: ByteArray, n: Int, total: Long): Int {
        var i = 0
        while (i + 32 <= n) {
            if (buf[i] == SEVENZ[0] && matches(buf, i) && validStartHeader(buf, i, total - i)) return i
            i++
        }
        return -1
    }

    private fun matches(b: ByteArray, at: Int): Boolean {
        for (k in SEVENZ.indices) if (b[at + k] != SEVENZ[k]) return false
        return true
    }

    private fun validStartHeader(b: ByteArray, at: Int, remaining: Long): Boolean {
        val bb = ByteBuffer.wrap(b, at, 32).order(ByteOrder.LITTLE_ENDIAN)
        val crc = CRC32().also { it.update(b, at + 12, 20) }.value
        if (bb.getInt(at + 8).toLong() and 0xffffffffL != crc) return false
        val nextOffset = bb.getLong(at + 12)
        val nextSize = bb.getLong(at + 20)
        if (nextOffset < 0 || nextSize < 0 || nextSize > (1L shl 30)) return false
        return 32 + nextOffset + nextSize <= remaining
    }
}

/** Read-only [SeekableByteChannel] over a window of a [RandomSource] starting at [base]: what a ZIP behind a CRX header needs. */
internal class RandomSourceChannel(private val src: RandomSource, private val base: Long) : SeekableByteChannel {
    private var pos = 0L
    private var open = true
    private val length get() = src.size - base

    override fun read(dst: ByteBuffer): Int {
        if (!open) throw java.nio.channels.ClosedChannelException()
        val n = minOf(dst.remaining().toLong(), length - pos).toInt()
        if (n <= 0) return -1
        val lim = dst.limit()
        dst.limit(dst.position() + n)
        src.read(base + pos, dst)
        dst.limit(lim)
        pos += n
        return n
    }

    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()
    override fun position(): Long = pos
    override fun position(newPosition: Long): SeekableByteChannel { require(newPosition >= 0); pos = newPosition; return this }
    override fun size(): Long = length
    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
    override fun isOpen(): Boolean = open
    override fun close() { open = false }
}
