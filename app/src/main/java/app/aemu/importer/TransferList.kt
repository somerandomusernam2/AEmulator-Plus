package app.aemu.importer

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Сборка system.img из блочной OTA (system.new.dat + system.transfer.list, Android 5.0+).
 * В полных OTA встречаются только команды new/zero/erase — их и поддерживаем.
 * Результат — разреженный файл (дыры не занимают место на диске).
 */
object TransferList {
    private const val BLOCK = 4096

    fun build(list: String, data: InputStream, out: File) {
        val lines = list.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) throw IOException("transfer.list: missing header")
        val version = lines[0].toIntOrNull() ?: throw IOException("transfer.list: no version")
        val totalBlocks = lines[1].toLongOrNull() ?: throw IOException("transfer.list: bad block count")
        if (totalBlocks < 0) throw IOException("transfer.list: negative block count")
        val cmdStart = if (version >= 2) 4 else 2
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(totalBlocks * BLOCK)
            val buf = ByteArray(BLOCK * 256)
            for (ln in lines.drop(cmdStart)) {
                val parts = ln.split(Regex("\\s+")).filter { it.isNotEmpty() }
                when (parts[0]) {
                    "new" -> {
                        if (parts.size < 2) throw IOException("transfer.list: malformed new command")
                        for ((a, b) in ranges(parts[1])) {
                        var blk = a
                        while (blk < b) {
                            val n = minOf(256L, b - blk).toInt()
                            readFully(data, buf, n * BLOCK)
                            raf.seek(blk * BLOCK)
                            raf.write(buf, 0, n * BLOCK)
                            blk += n
                        }
                    }
                    }
                    "zero", "erase" -> {} // дыры и так нулевые
                    "move", "bsdiff", "imgdiff", "stash", "free" -> throw IOException("this is an incremental OTA, a full firmware is required")
                }
            }
        }
    }

    private fun ranges(s: String): List<Pair<Long, Long>> {
        val v = s.split(',').mapNotNull { it.toLongOrNull() }
        if (v.isEmpty() || v[0] < 0 || v.size != v[0].toInt() + 1 || v[0] % 2L != 0L)
            throw IOException("transfer.list: invalid rangeset")
        val out = ArrayList<Pair<Long, Long>>()
        var i = 1
        while (i + 1 < v.size) {
            val a = v[i]; val b = v[i + 1]
            if (a < 0 || b < a) throw IOException("transfer.list: invalid range $a,$b")
            out.add(a to b); i += 2
        }
        return out
    }

    private fun readFully(i: InputStream, b: ByteArray, n: Int) {
        var off = 0
        while (off < n) {
            val k = i.read(b, off, n - off)
            if (k <= 0) { b.fill(0, off, n); return }
            off += k
        }
    }
}
