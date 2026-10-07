/* AEmulator Sunset addition: delta OTA support. GPL-3.0; see LICENSE. */
package app.aemu.core

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.Inflater

/** BSDIFF40, the format of Android's applypatch (bootable/recovery/applypatch/bspatch.cpp). */
internal object BsPatch {
    private const val MAGIC = "BSDIFF40"

    fun isBsdiff(patch: ByteArray, at: Int = 0) =
        patch.size - at >= 8 && String(patch, at, 8, Charsets.ISO_8859_1) == MAGIC

    /** sign-magnitude little-endian int64 */
    private fun offtin(b: ByteArray, o: Int): Long {
        var y = (b[o + 7].toLong() and 0x7f)
        for (i in 6 downTo 0) y = (y shl 8) or (b[o + i].toLong() and 0xff)
        return if (b[o + 7].toInt() and 0x80 != 0) -y else y
    }

    /** Applies the bsdiff patch that starts at [patchAt] in [patch] to old[oldStart, oldStart+oldLen). */
    fun apply(old: ByteArray, oldStart: Int, oldLen: Int, patch: ByteArray, patchAt: Int): ByteArray {
        if (!isBsdiff(patch, patchAt) || patch.size - patchAt < 32) throw IOException("corrupt bsdiff patch")
        require(oldStart >= 0 && oldLen >= 0 && oldStart.toLong() + oldLen <= old.size) { "bsdiff source out of range" }
        val ctrlLen = offtin(patch, patchAt + 8)
        val diffLen = offtin(patch, patchAt + 16)
        val newSize = offtin(patch, patchAt + 24)
        val avail = (patch.size - patchAt).toLong()
        if (ctrlLen < 0 || diffLen < 0 || newSize < 0 || newSize > Int.MAX_VALUE - 8 || 32 + ctrlLen + diffLen > avail)
            throw IOException("corrupt bsdiff patch header")
        val ctrlAt = patchAt + 32
        val diffAt = ctrlAt + ctrlLen.toInt()
        val extraAt = diffAt + diffLen.toInt()
        val ctrlS = DataInputStream(BZip2CompressorInputStream(ByteArrayInputStream(patch, ctrlAt, ctrlLen.toInt())))
        val diffS = DataInputStream(BZip2CompressorInputStream(ByteArrayInputStream(patch, diffAt, diffLen.toInt())))
        // the extra block runs to the end of the buffer; the bzip2 stream stops by itself at its end marker
        val extraS = DataInputStream(BZip2CompressorInputStream(ByteArrayInputStream(patch, extraAt, patch.size - extraAt)))
        val out = ByteArray(newSize.toInt())
        val ctrl = ByteArray(24)
        var oldPos = 0L
        var newPos = 0L
        try {
            while (newPos < newSize) {
                ctrlS.readFully(ctrl)
                val c0 = offtin(ctrl, 0)
                val c1 = offtin(ctrl, 8)
                val c2 = offtin(ctrl, 16)
                if (c0 < 0 || c1 < 0 || newPos + c0 > newSize) throw IOException("corrupt bsdiff patch")
                diffS.readFully(out, newPos.toInt(), c0.toInt())
                for (i in 0 until c0.toInt()) {
                    val op = oldPos + i
                    if (op >= 0 && op < oldLen) out[newPos.toInt() + i] = (out[newPos.toInt() + i] + old[oldStart + op.toInt()]).toByte()
                }
                newPos += c0
                oldPos += c0
                if (newPos + c1 > newSize) throw IOException("corrupt bsdiff patch")
                extraS.readFully(out, newPos.toInt(), c1.toInt())
                newPos += c1
                oldPos += c2
            }
        } catch (e: java.io.EOFException) {
            throw IOException("truncated bsdiff patch")
        } finally {
            runCatching { ctrlS.close(); diffS.close(); extraS.close() }
        }
        return out
    }
}

/**
 * Applies the patch files of an incremental OTA: plain BSDIFF40, or IMGDIFF2 (zip/apk/jar-aware: the compressed
 * chunks are inflated, bspatched and deflated again with the recorded zlib parameters), as in
 * bootable/recovery/applypatch/imgpatch.cpp. The caller checks the SHA-1 of the result.
 */
internal object ImgPatch {
    private const val MAGIC = "IMGDIFF2"
    private const val CHUNK_NORMAL = 0
    private const val CHUNK_DEFLATE = 2
    private const val CHUNK_RAW = 3

    fun apply(old: ByteArray, patch: ByteArray): ByteArray {
        if (BsPatch.isBsdiff(patch)) return BsPatch.apply(old, 0, old.size, patch, 0)
        if (patch.size < 12 || String(patch, 0, 8, Charsets.ISO_8859_1) != MAGIC) throw IOException("unknown patch format")
        val bb = ByteBuffer.wrap(patch).order(ByteOrder.LITTLE_ENDIAN)
        val chunks = bb.getInt(8)
        if (chunks < 0) throw IOException("corrupt imgdiff patch")
        var pos = 12
        val out = ByteArrayOutputStream(old.size + (64 shl 10))
        fun need(n: Int) { if (n < 0 || pos.toLong() + n > patch.size) throw IOException("truncated imgdiff patch") }
        fun range(start: Long, len: Long) {
            if (start < 0 || len < 0 || start + len > old.size) throw IOException("imgdiff chunk outside the source")
        }
        repeat(chunks) {
            need(4)
            val type = bb.getInt(pos); pos += 4
            when (type) {
                CHUNK_NORMAL -> {
                    need(24)
                    val start = bb.getLong(pos); val len = bb.getLong(pos + 8); val at = bb.getLong(pos + 16); pos += 24
                    range(start, len)
                    if (at < 0 || at >= patch.size) throw IOException("corrupt imgdiff patch")
                    out.write(BsPatch.apply(old, start.toInt(), len.toInt(), patch, at.toInt()))
                }
                CHUNK_RAW -> {
                    need(4)
                    val len = bb.getInt(pos); pos += 4
                    need(len)
                    out.write(patch, pos, len); pos += len
                }
                CHUNK_DEFLATE -> {
                    need(60)
                    val start = bb.getLong(pos); val len = bb.getLong(pos + 8); val at = bb.getLong(pos + 16)
                    val expanded = bb.getLong(pos + 24); val target = bb.getLong(pos + 32)
                    val level = bb.getInt(pos + 40); val method = bb.getInt(pos + 44); val windowBits = bb.getInt(pos + 48)
                    val memLevel = bb.getInt(pos + 52); val strategy = bb.getInt(pos + 56)
                    pos += 60
                    range(start, len)
                    if (at < 0 || at >= patch.size || expanded < 0 || expanded > Int.MAX_VALUE - 8 || target < 0)
                        throw IOException("corrupt imgdiff patch")
                    // java.util.zip can only produce raw deflate with a 32K window and memLevel 8: what AOSP uses
                    if (method != 8 || windowBits != -15 || memLevel != 8) throw IOException("unsupported deflate parameters")
                    val source = inflateRaw(old, start.toInt(), len.toInt(), expanded.toInt())
                    val data = BsPatch.apply(source, 0, source.size, patch, at.toInt())
                    if (data.size.toLong() != target) throw IOException("imgdiff chunk has unexpected size")
                    out.write(deflateRaw(data, level, strategy))
                }
                else -> throw IOException("unsupported imgdiff chunk type $type")
            }
        }
        return out.toByteArray()
    }

    private fun inflateRaw(src: ByteArray, off: Int, len: Int, expected: Int): ByteArray {
        val inf = Inflater(true)
        try {
            inf.setInput(src, off, len)
            val out = ByteArray(expected)
            var n = 0
            while (n < expected) {
                val r = inf.inflate(out, n, expected - n)
                if (r == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) break
                n += r
            }
            if (n != expected) throw IOException("deflate chunk of the source has unexpected size")
            return out
        } catch (e: java.util.zip.DataFormatException) {
            throw IOException("deflate chunk of the source is corrupt")
        } finally { inf.end() }
    }

    private fun deflateRaw(data: ByteArray, level: Int, strategy: Int): ByteArray {
        val def = Deflater(level, true)
        try {
            def.setStrategy(strategy)
            def.setInput(data)
            def.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 64)
            val buf = ByteArray(1 shl 16)
            while (!def.finished()) {
                val n = def.deflate(buf)
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally { def.end() }
    }
}
