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
 * VCDIFF (RFC 3284), the format of xdelta3. The first Android OTAs (1.0, e.g. the T-Mobile G1 "RC29 -> RC30" delta)
 * used xdelta3 patches in their `applypatch` (the binary in those packages says "xd3_decode_input"); bsdiff came with
 * Android 1.5. Only what such packages contain is read: the default code table, VCD_SOURCE windows (the whole source
 * file or a part of it), an optional Adler-32 per window, no secondary compression, no VCD_TARGET windows.
 */
internal object VcDiff {
    fun isVcdiff(patch: ByteArray) = patch.size >= 5 && patch[0] == 0xD6.toByte() && patch[1] == 0xC3.toByte() &&
        patch[2] == 0xC4.toByte() && patch[3] == 0.toByte()

    private const val MAX_TARGET = 1L shl 30

    // RFC 3284 section 5.6, the default instruction code table: type (0 none, 1 add, 2 run, 3 copy), size, mode
    private val T1 = IntArray(256)
    private val S1 = IntArray(256)
    private val M1 = IntArray(256)
    private val T2 = IntArray(256)
    private val S2 = IntArray(256)
    private val M2 = IntArray(256)

    init {
        var i = 0
        fun put(t1: Int, s1: Int, m1: Int, t2: Int, s2: Int, m2: Int) {
            T1[i] = t1; S1[i] = s1; M1[i] = m1; T2[i] = t2; S2[i] = s2; M2[i] = m2; i++
        }
        put(2, 0, 0, 0, 0, 0)
        for (s in 0..17) put(1, s, 0, 0, 0, 0)
        for (m in 0..8) { put(3, 0, m, 0, 0, 0); for (s in 4..18) put(3, s, m, 0, 0, 0) }
        for (m in 0..5) for (a in 1..4) for (c in 4..6) put(1, a, 0, 3, c, m)
        for (m in 6..8) for (a in 1..4) put(1, a, 0, 3, 4, m)
        for (m in 0..8) put(3, 4, m, 1, 1, 0)
        check(i == 256)
    }

    private class Rd(val b: ByteArray, var pos: Int, val end: Int) {
        fun u8(): Int {
            if (pos >= end) throw IOException("truncated VCDIFF patch")
            return b[pos++].toInt() and 0xff
        }
        fun varint(): Long {
            var v = 0L
            while (true) {
                val c = u8()
                v = (v shl 7) or (c and 0x7f).toLong()
                if (v > (1L shl 56)) throw IOException("corrupt VCDIFF patch")
                if (c and 0x80 == 0) return v
            }
        }
        fun skip(n: Long) {
            if (n < 0 || pos + n > end) throw IOException("truncated VCDIFF patch")
            pos += n.toInt()
        }
    }

    fun apply(src: ByteArray, patch: ByteArray): ByteArray {
        if (!isVcdiff(patch)) throw IOException("not a VCDIFF patch")
        val r = Rd(patch, 4, patch.size)
        val hdr = r.u8()
        if (hdr and 1 != 0) throw IOException("VCDIFF secondary compression is not supported")
        if (hdr and 2 != 0) throw IOException("VCDIFF custom code tables are not supported")
        if (hdr and 4 != 0) r.skip(r.varint()) // application header: xdelta3 puts the names of its temporary files there
        val out = java.io.ByteArrayOutputStream(src.size + 4096)
        while (r.pos < patch.size) {
            val wi = r.u8()
            if (wi and 2 != 0) throw IOException("VCDIFF target windows are not supported")
            var sPos = 0
            var sLen = 0
            if (wi and 1 != 0) {
                val l = r.varint(); val p = r.varint()
                if (p + l > src.size) throw IOException("source file too short")
                sLen = l.toInt(); sPos = p.toInt()
            }
            r.varint() // length of the delta encoding
            val tLen = r.varint()
            if (tLen > MAX_TARGET || out.size() + tLen > MAX_TARGET) throw IOException("VCDIFF target too large")
            if (r.u8() and 7 != 0) throw IOException("VCDIFF secondary compression is not supported")
            val dLen = r.varint(); val iLen = r.varint(); val aLen = r.varint()
            var adler = -1L
            if (wi and 4 != 0) {
                adler = 0
                repeat(4) { adler = (adler shl 8) or r.u8().toLong() }
            }
            if (dLen < 0 || iLen < 0 || aLen < 0 || r.pos + dLen + iLen + aLen > patch.size) throw IOException("truncated VCDIFF patch")
            val data = Rd(patch, r.pos, r.pos + dLen.toInt())
            val inst = Rd(patch, data.end, data.end + iLen.toInt())
            val addr = Rd(patch, inst.end, inst.end + aLen.toInt())
            r.pos = addr.end

            val win = ByteArray(tLen.toInt())
            var wp = 0
            val near = LongArray(4)
            val same = LongArray(768)
            var next = 0
            fun address(mode: Int): Long {
                val here = (sLen + wp).toLong()
                val a = when {
                    mode == 0 -> addr.varint()
                    mode == 1 -> here - addr.varint()
                    mode < 6 -> near[mode - 2] + addr.varint()
                    else -> same[(mode - 6) * 256 + addr.u8()]
                }
                if (a < 0 || a >= here) throw IOException("corrupt VCDIFF patch (address out of range)")
                near[next] = a; next = (next + 1) and 3
                same[(a % 768).toInt()] = a
                return a
            }
            fun run(type: Int, size0: Int, mode: Int) {
                if (type == 0) return
                val size = if (size0 != 0) size0 else inst.varint().also { if (it > win.size) throw IOException("corrupt VCDIFF patch") }.toInt()
                if (size > win.size - wp) throw IOException("corrupt VCDIFF patch (instruction runs past its window)")
                when (type) {
                    1 -> {
                        if (size > data.end - data.pos) throw IOException("truncated VCDIFF patch")
                        System.arraycopy(patch, data.pos, win, wp, size); data.pos += size; wp += size
                    }
                    2 -> { val c = data.u8().toByte(); java.util.Arrays.fill(win, wp, wp + size, c); wp += size }
                    else -> {
                        val a = address(mode)
                        if (a + size <= sLen) { System.arraycopy(src, sPos + a.toInt(), win, wp, size); wp += size }
                        else for (k in 0 until size) {
                            val at = a + k
                            win[wp++] = if (at < sLen) src[sPos + at.toInt()] else win[(at - sLen).toInt()]
                        }
                    }
                }
            }
            while (inst.pos < inst.end) {
                val op = inst.u8()
                run(T1[op], S1[op], M1[op])
                run(T2[op], S2[op], M2[op])
            }
            if (wp != win.size) throw IOException("corrupt VCDIFF patch (window has the wrong size)")
            if (adler >= 0) {
                val c = java.util.zip.Adler32(); c.update(win)
                if (c.value != adler) throw IOException("VCDIFF checksum mismatch (the source file is not the one this patch was made for)")
            }
            out.write(win, 0, win.size)
        }
        return out.toByteArray()
    }
}

/**
 * Applies the patch files of an incremental OTA: VCDIFF (xdelta3, Android 1.0), plain BSDIFF40, or IMGDIFF2 (zip/apk/jar-aware: the compressed
 * chunks are inflated, bspatched and deflated again with the recorded zlib parameters), as in
 * bootable/recovery/applypatch/imgpatch.cpp. The caller checks the SHA-1 of the result.
 */
internal object ImgPatch {
    private const val MAGIC = "IMGDIFF2"
    private const val CHUNK_NORMAL = 0
    private const val CHUNK_DEFLATE = 2
    private const val CHUNK_RAW = 3

    /**
     * [bonus]: applypatch's `--bonus` data (recovery-resource.dat). An imgdiff made with it counts the bonus bytes as the tail of
     * the inflated source of its second chunk, so they are appended there (imgpatch.cpp: `i == 1 && bonus_data != NULL`).
     */
    fun apply(old: ByteArray, patch: ByteArray, bonus: ByteArray? = null): ByteArray {
        if (VcDiff.isVcdiff(patch)) return VcDiff.apply(old, patch)
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
        repeat(chunks) { index ->
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
                    val bonusSize = if (index == 1 && bonus != null) bonus.size else 0
                    if (bonusSize > expanded) throw IOException("corrupt imgdiff patch")
                    val inflated = inflateRaw(old, start.toInt(), len.toInt(), expanded.toInt() - bonusSize)
                    val source = if (bonusSize > 0) inflated + bonus!! else inflated
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
