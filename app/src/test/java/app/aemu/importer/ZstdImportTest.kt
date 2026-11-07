/* Added for AEmulator Aster (2026-11-07). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.importer

import app.aemu.importer.tools.FirmwareToolset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.Random

/**
 * zstd in the import: the signature (HyperOS fastboot images/super.zst and the like), the stream the importer decodes, and
 * the ZSTD operation (14) of a payload.bin, through the real toolset entry point.
 */
class ZstdImportTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun random(n: Int, seed: Long) = ByteArray(n).also { Random(seed).nextBytes(it) }

    @Test fun zstdIsRecognisedByItsSignature() {
        val z = com.github.luben.zstd.Zstd.compress(ByteArray(1000))
        assertEquals(FirmwareContainers.Compression.ZSTD, FirmwareContainers.compression(z.copyOf(16)))
        assertEquals(null, FirmwareContainers.compression(ByteArray(16)))
    }

    @Test fun zstdStreamAsTheImporterReadsIt() {
        val data = random(300_000, 1).copyOf(600_000)                   // half random, half zeros
        val back = org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream(
            ByteArrayInputStream(com.github.luben.zstd.Zstd.compress(data, 3))).use { it.readBytes() }
        assertArrayEquals(data, back)
    }

    private fun varint(o: ByteArrayOutputStream, v0: Long) { var v = v0; while (v >= 0x80) { o.write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }; o.write(v.toInt()) }
    private fun field(o: ByteArrayOutputStream, n: Int, v: Long) { varint(o, (n shl 3).toLong()); varint(o, v) }
    private fun field(o: ByteArrayOutputStream, n: Int, b: ByteArray) { varint(o, ((n shl 3) or 2).toLong()); varint(o, b.size.toLong()); o.write(b) }
    private fun msg(f: (ByteArrayOutputStream) -> Unit) = ByteArrayOutputStream().also(f).toByteArray()

    @Test fun payloadWithZstdOperations() {
        val blocks = List(3) { random(4096, it + 7L) }
        val xz = ByteArrayOutputStream().also { org.tukaani.xz.XZOutputStream(it, org.tukaani.xz.LZMA2Options()).use { x -> x.write(blocks[2]) } }.toByteArray()
        val zst = com.github.luben.zstd.Zstd.compress(blocks[1])
        val blobs = listOf(0 to blocks[0], 14 to zst, 8 to xz)
        var off = 0L
        val part = msg { p ->
            field(p, 1, "system".toByteArray())
            for ((i, b) in blobs.withIndex()) {
                field(p, 8, msg { op ->
                    field(op, 1, b.first.toLong()); field(op, 2, off); field(op, 3, b.second.size.toLong())
                    field(op, 6, msg { ext -> field(ext, 1, i.toLong()); field(ext, 2, 1) })
                })
                off += b.second.size
            }
            field(p, 7, msg { info -> field(info, 1, 3 * 4096L) })
        }
        val manifest = msg { m -> field(m, 13, part) }
        val head = ByteBuffer.allocate(24).put("CrAU".toByteArray()).putLong(2).putLong(manifest.size.toLong()).putInt(0)
        val payload = File(tmp.root, "payload.bin")
        payload.writeBytes(head.array() + manifest + blobs.fold(ByteArray(0)) { a, b -> a + b.second })
        val log = StringBuilder()
        val out = FirmwareToolset.extract(payload, tmp.newFolder("work")) { log.append(it).append('\n') }
        val system = out.map { it.file }.firstOrNull { it.name.startsWith("system") }
        assertNotNull("no system image; log:\n$log", system)
        assertArrayEquals(blocks[0] + blocks[1] + blocks[2], system!!.readBytes().copyOf(3 * 4096))
    }
}
