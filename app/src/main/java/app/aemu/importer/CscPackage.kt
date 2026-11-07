package app.aemu.importer

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory

/**
 * Samsung CSC (Consumer Software Customization) packages: the carrier / country defaults that live in
 * /system/csc (customer.xml, others.xml, sales_code.dat, ...). Samsung firmware reads them at boot and in several
 * system services; with no real CSC the importer only has an empty stub.
 *
 * Odin ships it as `GT-xxxx-CSC-<code>.tar.md5` holding `csc.rfs`, a small FAT volume with `csc.zip` inside; the
 * zip carries the files of the system/csc folder plus a few carrier apps and libraries. Only the data under system/csc is taken: the
 * carrier apps are bundled extras, and customer.xml may name an input method among them that would then be missing.
 * Content-first: a zip, a (compressed) tar, a FAT image, or any nesting of those is understood whatever its name.
 */
internal object CscPackage {
    private const val MAX_FILE = 4L shl 20
    private const val MAX_TOTAL = 32L shl 20
    private const val MAX_FILES = 256
    private const val MAX_NESTED = 160L shl 20

    private val CONTAINER_EXT = Regex("(?i).*\\.(rfs|img|zip|tar|md5|bin|ext4|gz|xz|bz2|tgz|7z)$")
    private val CSC_WORD = Regex("(?i)(^|[^a-z])csc([^a-z]|$)")
    private val SAFE_NAME = Regex("[A-Za-z0-9._+-]+")
    private val KNOWN = setOf("customer.xml", "others.xml", "sales_code.dat", "contents.db", "isnew_csc.txt", "feature.xml")

    /** A package or image whose file name says CSC ("GT-I5500-CSC-SERJJ2.tar.md5", "csc.rfs", "CSC_SER.zip"). */
    fun isCscContainer(base: String) = CONTAINER_EXT.matches(base) && CSC_WORD.containsMatchIn(base)

    /** "system/csc/customer.xml", "csc/customer.xml" or a bare "customer.xml" -> "system/csc/customer.xml"; anything else null. */
    fun target(path: String): String? {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." || !SAFE_NAME.matches(it) }) return null
        val i = parts.indexOfFirst { it.equals("csc", true) }
        val rest = when {
            i == 0 || (i == 1 && parts[0].equals("system", true)) -> parts.drop(i + 1)
            parts.size == 1 && parts[0].lowercase() in KNOWN -> parts
            else -> return null
        }
        if (rest.isEmpty()) return null
        val fixed = rest.dropLast(1) + rest.last().let { if (it.lowercase() in KNOWN) it.lowercase() else it }
        return "system/csc/" + fixed.joinToString("/")
    }

    /**
     * The CSC files of [input] as target path -> bytes. [scratch] receives temporary copies that the caller deletes.
     * Fails when nothing that looks like CSC data (customer.xml or sales_code.dat) is found.
     */
    fun read(input: InputStream, name: String, scratch: File): Map<String, ByteArray> {
        val sink = Sink()
        collect(input, name, scratch, 0, sink)
        require(sink.files.keys.any { it.endsWith("/customer.xml") || it.endsWith("/sales_code.dat") }) {
            "No CSC data (customer.xml, sales_code.dat) found in this file"
        }
        return sink.files
    }

    private class Sink {
        val files = LinkedHashMap<String, ByteArray>()
        private var total = 0L
        fun put(key: String, s: InputStream) {
            val b = s.readNBytesLimited(MAX_FILE)
            check(files.size < MAX_FILES && total + b.size <= MAX_TOTAL) { "CSC package is too large" }
            total += b.size
            files[key] = b
        }
    }

    private fun InputStream.readNBytesLimited(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16384)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw IOException("CSC file is larger than ${limit shr 20} MB")
        }
        return out.toByteArray()
    }

    private class NoClose(s: InputStream) : FilterInputStream(s) { override fun close() {} }

    private fun collect(input: InputStream, name: String, scratch: File, depth: Int, sink: Sink) {
        require(depth <= 5) { "Too many nested archives" }
        val src = input.buffered(65536)
        src.mark(512)
        val head = ByteArray(512)
        var count = 0
        while (count < head.size) { val n = src.read(head, count, head.size - count); if (n < 0) break; count += n }
        src.reset()
        val h = head.copyOf(count)
        fun magic(offset: Int, value: String) = count >= offset + value.length &&
            String(h, offset, value.length, Charsets.ISO_8859_1) == value
        val lower = name.lowercase()
        if (magic(0, "PK\u0003\u0004")) {
            ZipArchiveInputStream(src).use { zip ->
                while (true) {
                    val e = zip.nextZipEntry ?: break
                    if (e.isDirectory) continue
                    val t = target(e.name)
                    if (t != null) sink.put(t, NoClose(zip))
                    else if (isCscContainer(e.name.substringAfterLast('/'))) collect(NoClose(zip), e.name.substringAfterLast('/'), scratch, depth + 1, sink)
                }
            }
            return
        }
        val compressed = runCatching { CompressorStreamFactory.detect(src) }.getOrNull()
        if (compressed != null) {
            CompressorStreamFactory(true, 65536).createCompressorInputStream(compressed, src).use {
                collect(it, lower.substringBeforeLast('.', lower), scratch, depth + 1, sink)
            }
            return
        }
        if (magic(257, "ustar") || lower.endsWith(".tar") || lower.endsWith(".tar.md5")) {
            TarArchiveInputStream(src).use { tar ->
                while (true) {
                    val e = tar.nextTarEntry ?: break
                    if (!e.isFile) continue
                    val base = PartitionNames.stripOdinMd5(e.name.substringAfterLast('/'))
                    val t = target(e.name)
                    if (t != null) sink.put(t, NoClose(tar))
                    else if (isCscContainer(base)) collect(NoClose(tar), base, scratch, depth + 1, sink)
                }
            }
            return
        }
        if (FatReader.probe(h)) {
            val img = Files.createTempFile(scratch.toPath(), "csc-", ".img").toFile()
            try {
                img.outputStream().use { src.copyTo(it, 1 shl 16) }
                val fat = FatReader.open(img) ?: throw IOException("Not a FAT volume")
                fat.use {
                    for (e in it.files()) {
                        val base = e.path.substringAfterLast('/')
                        val t = target(e.path)
                        if (t != null && e.size <= MAX_FILE) {
                            val tmp = java.io.ByteArrayOutputStream()
                            it.copy(e, tmp, MAX_FILE)
                            sink.put(t, tmp.toByteArray().inputStream())
                        } else if (isCscContainer(base) && e.size in 1..MAX_NESTED) {
                            val nested = Files.createTempFile(scratch.toPath(), "csc-n-", ".bin").toFile()
                            try {
                                nested.outputStream().use { o -> it.copy(e, o) }
                                nested.inputStream().use { s -> collect(s, base, scratch, depth + 1, sink) }
                            } finally { nested.delete() }
                        }
                    }
                }
            } finally { img.delete() }
            return
        }
        throw IOException("Not a CSC package")
    }
}
