package app.aemu.importer

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory

/**
 * Finds the OEM partition image in whatever the person picked in the settings: the image itself (ext2/3/4, sparse,
 * YAFFS2, Moto-signed), or a zip / tar / tar.md5 / gz / xz / bz2 / lz4 holding `oem.img`, `oem_signed(.img)` or
 * `oem.ext4.img`. Content-first like [BootPartitionSource]. The image is copied into [scratch] and returned; the
 * caller deletes it.
 */
internal object OemPartitionSource {
    private const val MAX_IMAGE = 4L shl 30

    fun read(input: InputStream, name: String, scratch: File, depth: Int = 0): File {
        require(depth <= 4) { "Too many nested archives" }
        val src = input.buffered(65536)
        src.mark(512)
        val head = ByteArray(512)
        var count = 0
        while (count < head.size) { val n = src.read(head, count, head.size - count); if (n < 0) break; count += n }
        src.reset()
        fun magic(offset: Int, value: String) = count >= offset + value.length &&
            String(head, offset, value.length, Charsets.ISO_8859_1) == value
        val lower = name.lowercase()
        if (magic(0, "PK\u0003\u0004")) {
            ZipArchiveInputStream(src).use { zip ->
                while (true) {
                    val e = zip.nextZipEntry ?: break
                    val base = e.name.substringAfterLast('/')
                    if (!e.isDirectory && PartitionNames.isOem(base.removeSuffix(".lz4"))) return read(zip, base, scratch, depth + 1)
                }
            }
            error("No oem partition in ZIP archive")
        }
        val compressed = runCatching { CompressorStreamFactory.detect(src) }.getOrNull()
        if (compressed != null) {
            CompressorStreamFactory(true, 65536).createCompressorInputStream(compressed, src).use {
                return read(it, lower.substringBeforeLast('.', lower), scratch, depth + 1)
            }
        }
        if (magic(257, "ustar") || lower.endsWith(".tar") || lower.endsWith(".tar.md5") || lower.endsWith(".md5")) {
            TarArchiveInputStream(src).use { tar ->
                while (true) {
                    val e = tar.nextTarEntry ?: break
                    val base = e.name.substringAfterLast('/')
                    if (e.isFile && PartitionNames.isOem(base.removeSuffix(".lz4"))) return read(tar, base, scratch, depth + 1)
                }
            }
            error("No oem partition in TAR archive")
        }
        return spill(src, scratch)
    }

    private fun spill(input: InputStream, scratch: File): File {
        val f = Files.createTempFile(scratch.toPath(), "oem-", ".img").toFile()
        try {
            f.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    total += n
                    require(total <= MAX_IMAGE) { "OEM image exceeds 4 GiB" }
                    out.write(buf, 0, n)
                }
            }
            require(f.length() >= 2048) { "OEM image is too small" }
        } catch (t: Throwable) { f.delete(); throw t }
        return f
    }
}
