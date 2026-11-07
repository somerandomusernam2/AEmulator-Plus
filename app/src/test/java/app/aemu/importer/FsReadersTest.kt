package app.aemu.importer

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * The image readers against images made by the real tools, in the variants vendors build: mkfs.erofs (erofs-utils)
 * with the compressors and layouts of Android 13+ system images, mkfs.ext4 with old and new feature sets, and the
 * sparse form of img2simg. Every file, link, directory and permission bit must read back as it went in. Skipped where
 * the tools are not installed (apt: erofs-utils, e2fsprogs, android-sdk-libsparse-utils).
 */
class FsReadersTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tool(name: String): String? = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
        .plus(listOf("/usr/sbin", "/sbin")).map { File(it, name) }.firstOrNull { it.canExecute() }?.path

    private fun run(vararg cmd: String) {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(120, TimeUnit.SECONDS)
        check(p.exitValue() == 0) { cmd.joinToString(" ") + ": " + out.takeLast(800) }
    }

    /** what a system partition has: empty to multi-megabyte files, compressible and not, deep and wide directories,
     *  short and long links, executables, non-ASCII names */
    private fun tree(): File {
        val d = tmp.newFolder("src")
        val r = Random(42)
        fun rnd(n: Int) = ByteArray(n).also { r.nextBytes(it) }
        fun text(n: Int): ByteArray {
            val sb = StringBuilder()
            var i = 0
            while (sb.length < n) sb.append("ro.product.property_${i % 97}=value ${i++ * 7919 % 1000}\n")
            return sb.toString().toByteArray().copyOf(n)
        }
        fun put(rel: String, b: ByteArray, mode: String = "rw-r--r--") {
            val f = File(d, rel); f.parentFile.mkdirs(); f.writeBytes(b)
            Files.setPosixFilePermissions(f.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString(mode))
        }
        put("empty", ByteArray(0))
        put("one", byteArrayOf(7))
        for (n in listOf(100, 4095, 4096, 4097, 65539, 131072)) put("sizes/r$n", rnd(n))
        for (n in listOf(3000, 8191, 70_000, 1_100_000)) put("sizes/t$n", text(n))
        put("build.prop", text(5000))
        put("bin/sh", rnd(20_000), "rwxr-xr-x")
        put("lib64/libbig.so", text(900_000) + rnd(300_000) + ByteArray(400_000) + text(200_000), "rwxr-xr-x")
        put("zeros", ByteArray(600_000))
        put("a/b/c/d/e/f/g/deep.txt", text(1234))
        for (i in 0 until 300) put("framework/many/file_with_a_rather_long_name_number_$i.jar", if (i % 3 == 0) rnd(i * 37) else text(i * 53))
        // (a JVM without a UTF-8 locale cannot name such a file)
        if (System.getProperty("sun.jnu.encoding").orEmpty().contains("UTF-8", true)) put("fonts/中文字体.ttf", rnd(5000))
        Files.createSymbolicLink(File(d, "bin/toybox-ls").toPath(), File("toybox").toPath())
        Files.createSymbolicLink(File(d, "etc").toPath(), File("/system/etc").toPath())
        Files.createSymbolicLink(File(d, "long-link").toPath(), File("/" + "x".repeat(120) + "/target").toPath())
        File(d, "empty-dir").mkdirs()
        return d
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** path -> "d 755" / "l target" / "f 644 <sha>" */
    private fun expected(d: File): Map<String, String> {
        val m = HashMap<String, String>()
        d.walkTopDown().filter { it != d }.forEach { f ->
            val rel = f.relativeTo(d).path
            val p = f.toPath()
            m[rel] = when {
                Files.isSymbolicLink(p) -> "l " + Files.readSymbolicLink(p)
                f.isDirectory -> "d"
                else -> "f " + Integer.toOctalString(Files.getAttribute(p, "unix:mode") as Int and 0x1ff) + " " + sha(f.readBytes())
            }
        }
        return m
    }

    private fun read(t: TreeSource): Map<String, String> {
        val m = HashMap<String, String>()
        t.walk { path, isDir, link, mode, _, data ->
            val rel = path.trim('/')
            if (rel.isEmpty()) return@walk
            m[rel] = when {
                link != null -> "l $link"
                isDir -> "d"
                else -> "f " + Integer.toOctalString(mode and 0x1ff) + " " + sha(data!!().use { it.readBytes() })
            }
        }
        return m
    }

    private fun read(e: Ext4Reader): Map<String, String> {
        val m = HashMap<String, String>()
        e.walk { path, n ->
            val rel = path.trim('/')
            if (rel.isEmpty() || rel == "lost+found" || rel.startsWith("lost+found/")) return@walk
            m[rel] = when {
                n.isLink -> "l " + e.linkTarget(n)
                n.isDir -> "d"
                else -> "f " + Integer.toOctalString(n.perm and 0x1ff) + " " + sha(e.readAll(n))
            }
        }
        return m
    }

    private fun <T> open(img: File, f: (RandomSource) -> T): T =
        FileChannel.open(img.toPath(), StandardOpenOption.READ).use { c -> f(ChannelSource(c)) }

    private fun diff(want: Map<String, String>, got: Map<String, String>): String {
        val bad = (want.keys + got.keys).filter { want[it] != got[it] }.sorted()
        return bad.take(8).joinToString("\n") { "$it: want ${want[it]} got ${got[it]}" } + if (bad.size > 8) "\n… ${bad.size} in all" else ""
    }

    @Test fun erofsAsVendorsBuildIt() {
        val mkfs = tool("mkfs.erofs")
        assumeTrue("no mkfs.erofs", mkfs != null)
        val src = tree()
        val want = expected(src)
        val variants = listOf(
            listOf(),                                                   // uncompressed, inline tails
            listOf("-Enoinline_data"),
            listOf("--chunksize=4096"),                                 // chunk-based
            listOf("-Eforce-inode-extended"),
            listOf("-zlz4"),
            listOf("-zlz4hc,9"),                                        // what AOSP builds
            listOf("-zlz4hc,9", "-Elegacy-compress"),                   // the full (8-byte) index layout of older builds
            listOf("-zlz4hc,9", "-C65536"),                             // big physical clusters (Pixel: 256 KB)
            listOf("-zlz4hc,9", "-C262144"),
            listOf("-zlz4hc,9", "-Eztailpacking"),
            listOf("-zlz4hc,9", "-Efragments"),
            listOf("-zlz4hc,9", "-Eall-fragments"),
            listOf("-zlz4hc,9", "-Ededupe"),
            listOf("-zlz4hc,9", "-Eztailpacking,fragments,dedupe", "-C131072"),
            listOf("-zlz4", "--max-extent-bytes=65536"),
            listOf("-zdeflate"),
            listOf("-zlzma"),
        )
        val failures = ArrayList<String>()
        for ((i, v) in variants.withIndex()) {
            val img = File(tmp.root, "e$i.img")
            run(*(listOf(mkfs!!, "--quiet", "-T0", "--all-root") + v + listOf(img.path, src.path)).toTypedArray())
            val got = runCatching { open(img) { s -> check(ErofsReader.probe(s)); read(ErofsImage(s)) } }
            val d = got.fold({ diff(want, it) }, { "error: $it" })
            if (d.isNotEmpty()) failures += "mkfs.erofs ${v.joinToString(" ")}:\n$d"
        }
        assertEquals("", failures.joinToString("\n\n"))
    }

    @Test fun ext4OldAndNewAndSparse() {
        val mkfs = tool("mkfs.ext4")
        assumeTrue("no mkfs.ext4", mkfs != null)
        val src = tree()
        val want = expected(src)
        val variants = listOf(
            listOf(),                                                   // e2fsprogs defaults: 64bit, metadata_csum, extents
            listOf("-b", "1024"),
            listOf("-O", "^64bit,^metadata_csum"),                      // make_ext4fs-era images
            listOf("-O", "^extents,^64bit,^flex_bg", "-I", "128"),      // block maps, small inodes (2.x ext3-like)
            listOf("-O", "^dir_index"),
            listOf("-O", "inline_data"),
            listOf("-O", "^has_journal"),
            listOf("-O", "meta_bg,^resize_inode"),
        )
        val simg = tool("img2simg")
        val failures = ArrayList<String>()
        for ((i, v) in variants.withIndex()) {
            val img = File(tmp.root, "x$i.img")
            run(*(listOf(mkfs!!, "-q", "-F", "-E", "root_owner=0:0") + v + listOf("-d", src.path, img.path, "40M")).toTypedArray())
            val got = runCatching { open(img) { s -> check(Ext4Reader.probe(s)); read(Ext4Reader(s)) } }
            val d = got.fold({ diff(want, it) }, { "error: $it" })
            if (d.isNotEmpty()) failures += "mkfs.ext4 ${v.joinToString(" ")}:\n$d"
            if (simg != null && i == 0) {
                val sparse = File(tmp.root, "x$i.simg")
                run(simg, img.path, sparse.path)
                val gs = runCatching { open(sparse) { s -> check(SparseSource.probe(s)); read(Ext4Reader(SparseSource(listOf(s)))) } }
                val ds = gs.fold({ diff(want, it) }, { "error: $it" })
                if (ds.isNotEmpty()) failures += "img2simg of mkfs.ext4 defaults:\n$ds"
            }
        }
        assertEquals("", failures.joinToString("\n\n"))
    }
}
