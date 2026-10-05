/* Modified for AEmulator Sunset, 2026-10-03: VM archive restore and boot retention.
 * GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.importer

import android.content.Context
import android.net.Uri
import android.system.Os
import app.aemu.core.GuestImage
import app.aemu.core.ImageStore
import app.aemu.core.TreeFixer
import app.aemu.core.VmPaths
import app.aemu.core.RecoveryImage
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import com.github.junrar.Archive as RarArchive
import com.github.junrar.rarfile.FileHeader as RarFileHeader
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.brotli.dec.BrotliInputStream
import org.tukaani.xz.XZInputStream
import app.aemu.importer.tools.FirmwareToolset
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream

/**
 * Импорт прошивки в дерево гостя. Понимает (в том числе вложенные друг в друга):
 *  - ZIP для CWM/TWRP (MIUI, CyanogenMod, большинство прошивок 2.x–4.x): system/… + boot.img + updater-script
 *  - ZIP/TGZ factory-образов Google, прошивки с system.img внутри
 *  - TAR / TAR.MD5 (Samsung Odin, TouchWiz), TWRP-бэкапы (.win), tar.gz/xz/bz2
 *  - system.img: ext2/3/4, в том числе sparse (и разбитый на части: system.img_sparsechunk.N, system_sparsechunkN, system.img.N, system.N, system_N …)
 *  - system.new.dat(.br) + system.transfer.list (OTA 5.x–6.x)
 *  - уже готовое дерево rootfs в tar.gz (например, из стендов HTC)
 */
class Importer(
    private val ctx: Context,
    private val onProgress: (String, Float) -> Unit,
    private val log: (String) -> Unit,
) {
    private lateinit var paths: VmPaths
    private lateinit var root: File
    private val tmp = File(ctx.cacheDir, "import").apply { mkdirs() }
    private var ramdisk: List<BootImage.CpioEntry>? = null
    private var recovery: ByteArray? = null
    private val symlinks = ArrayList<Pair<String, String>>() // (цель, путь ссылки в госте)
    private val perms = ArrayList<Triple<String, Int, Boolean>>() // (путь, режим, рекурсивно-файлы)
    private var files = 0
    private var bytes = 0L
    private var gotSystem = false
    @Volatile var cancelled = false

    fun import(uri: Uri, name: String): GuestImage {
        if (name.endsWith(".aessvm", true)) {
            return app.aemu.core.VmArchiveStorage.restore(ctx, uri) { path ->
                if (cancelled) throw IOException("cancelled")
                onProgress("Restoring $path", -1f)
            }.also { onProgress("Done", 1f) }
        }
        val id = ImageStore.newId(ctx)
        paths = VmPaths(ctx, id)
        root = paths.root
        root.mkdirs()
        try {
            onProgress("Opening $name", 0f)
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("file failed to open")
            pfd.use {
                val ch = FileInputStream(pfd.fileDescriptor).channel
                val seekable = runCatching { ch.position(0); ch.size() > 0 }.getOrDefault(false)
                if (seekable) handle(ChannelSource(ch), ch, name, 0)
                else {
                    val t = spill(FileInputStream(pfd.fileDescriptor), "whole")
                    FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> handle(ChannelSource(c), c, name, 0) }
                    t.delete()
                }
            }
            // build.prop is optional: some dumps/ports lack it, Analyzer infers the version from the tree
            if (!File(root, "system/framework").isDirectory && !File(root, "system/build.prop").isFile)
                throw IOException("no Android system partition found in file")
            finishTree()
            recovery?.let { runCatching { RecoveryImage.install(paths, it, log) } }
            onProgress("Analyzing firmware", 0.97f)
            val img = Analyzer(ctx, paths, ramdisk).analyze(id, name)
            TreeFixer(ctx, paths, img, log).sanitize()
            ImageStore.save(ctx, img.copy(sizeBytes = ImageStore.du(paths.dir)))
            onProgress("Done", 1f)
            log("import: $files files, ${bytes shr 20} MB")
            return ImageStore.get(ctx, id)!!
        } catch (t: Throwable) {
            ImageStore.delete(ctx, id)
            throw t
        } finally {
            tmp.listFiles()?.forEach { deleteTree(it) }
        }
    }

    // ------------------------------------------------------------------ разбор контейнеров

    private fun handle(src: RandomSource, ch: FileChannel?, name: String, depth: Int) {
        if (depth > 4) return
        val head = ByteBuffer.allocate(1100)
        src.read(0, head); head.flip()
        val h = ByteArray(head.remaining()).also { head.get(it) }
        if (h.size < 2) throw IOException("empty or truncated firmware file")
        when {
            name.endsWith(".ofp", true) && h.size > 4 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() && h[2].toInt() == 3 && h[3].toInt() == 4 -> {
                val source = spill(streamOf(src), name)
                val toolWork = File(tmp, "tool-${System.nanoTime()}").apply { mkdirs() }
                try {
                    val artifacts = FirmwareToolset.extract(source, toolWork) { msg -> log(msg) }
                    if (artifacts.isEmpty()) throw IOException("OFP ZIP produced no files")
                    for (artifact in artifacts) {
                        if (!artifact.file.isFile) continue
                        FileChannel.open(artifact.file.toPath(), StandardOpenOption.READ).use { c ->
                            handle(ChannelSource(c), c, artifact.file.name, depth + 1)
                        }
                    }
                } finally { deleteTree(toolWork); source.delete() }
            }
            h.size > 4 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() && h[2].toInt() == 3 && h[3].toInt() == 4 -> {
                if (ch != null) importZip(ch, name, depth) else throw IOException("zip without random access")
            }
            h.size > 6 && h[0] == '7'.code.toByte() && h[1] == 'z'.code.toByte() && h[2] == 0xBC.toByte() && h[3] == 0xAF.toByte() -> {
                if (ch != null) importSevenZ(ch, name, depth) else throw IOException("7z without random access")
            }
            h.size >= 7 && h[0] == 'R'.code.toByte() && h[1] == 'a'.code.toByte() &&
                h[2] == 'r'.code.toByte() && h[3] == '!'.code.toByte() && h[4] == 0x1a.toByte() -> {
                val archive = spill(streamOf(src), name)
                try { importRar(archive, name, depth) } finally { archive.delete() }
            }
            h.size >= 6 && (String(h, 0, 6, Charsets.US_ASCII) == "070701" ||
                String(h, 0, 6, Charsets.US_ASCII) == "070702" ||
                String(h, 0, 6, Charsets.US_ASCII) == "070707") ->
                importCpioStream(streamOf(src), name, depth)
            SparseSource.probe(src) -> importImage(SparseSource(listOf(src)), "system")
            Ext4Reader.probe(src) -> importImage(src, "system")
            Yaffs2Reader.probe(src) -> importImage(src, "system")
            isTar(h) || name.endsWith(".tar", true) || name.endsWith(".md5", true) || name.endsWith(".win", true) ->
                importTarStream(streamOf(src), name, depth)
            h[0] == 0x1f.toByte() && h[1] == 0x8b.toByte() -> importCompressed(GZIPInputStream(streamOf(src), 1 shl 16), name, depth)
            h[0] == 0xfd.toByte() && h[1] == '7'.code.toByte() -> importCompressed(XZInputStream(streamOf(src)), name, depth)
            h[0] == 'B'.code.toByte() && h[1] == 'Z'.code.toByte() && h[2] == 'h'.code.toByte() -> importCompressed(BZip2CompressorInputStream(streamOf(src)), name, depth)
            h.size >= 8 && String(h, 0, 8, Charsets.ISO_8859_1) == "ANDROID!" -> takeBoot(readAllFrom(src))
            // system.img / factoryfs / factoryfs.img / factoryfs.rfs: a system partition whatever the filesystem
            isSystemImageName(name.replace('\\', '/').substringAfterLast('/')) ->
                importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
            else -> {
                val source = spill(streamOf(src), name)
                val toolWork = File(tmp, "tool-${System.nanoTime()}").apply { mkdirs() }
                try {
                    val artifacts = FirmwareToolset.extract(source, toolWork) { msg -> log(msg) }
                    if (artifacts.isEmpty()) throw IOException("unknown file format \"$name\"")
                    for (artifact in artifacts) {
                        if (cancelled) throw IOException("cancelled")
                        if (!artifact.file.isFile) continue
                        FileChannel.open(artifact.file.toPath(), StandardOpenOption.READ).use { c ->
                            handle(ChannelSource(c), c, artifact.file.name, depth + 1)
                        }
                    }
                } finally {
                    deleteTree(toolWork)
                    source.delete()
                }
            }
        }
    }

    private fun recognizedFirmwareTool(name: String): Boolean {
        val n = name.lowercase()
        if (n == "file_contexts.bin" || n == "file_contexts" || n.endsWith("/file_contexts.bin")) return false
        return n.endsWith(".pac") || n.endsWith(".sbf") || n.endsWith(".nbh") ||
            n.endsWith(".tot") || isLikelyLgBinName(n) || n.endsWith(".ofp") ||
            n.endsWith(".ops") || n.endsWith(".pkg") || n.endsWith(".rfs") ||
            n.endsWith(".squashfs") || n.endsWith(".new.dat") || n.endsWith(".new.dat.br") ||
            n.endsWith(".transfer.list") || n == "payload.bin" || n == "super.img"
    }

    // A generic *.bin is too broad: Android root files such as file_contexts.bin
    // are data files, not LG firmware containers. Only treat conventional LG
    // container names as tools here; header-based detection still handles an
    // actual LG BIN when it is presented directly to handle().
    private fun isLikelyLgBinName(n: String): Boolean {
        val b = n.substringAfterLast('/')
        if (!b.endsWith(".bin")) return false
        if (b == "file_contexts.bin") return false
        return b.startsWith("lg", true) || b.contains("kdz", true) ||
            b.contains("firmware", true) || b.contains("flash", true)
    }

    private fun spillPreservingName(i: InputStream, name: String): File {
        val safe = name.substringAfterLast('/').replace('\\', '_')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifEmpty { "entry" }
        val dir = File(tmp, "entries-${System.nanoTime()}").apply { mkdirs() }
        val f = File(dir, safe)
        f.outputStream().use { o -> i.copyTo(o, 1 shl 20) }
        return f
    }

    /**
     * gzip/xz/bz2 wrapper: unpack to a temp file and re-dispatch on the content. Feeding a decompressing
     * stream straight into the TAR reader broke .tar.gz/.tgz ("Corrupted TAR archive"), and a plain .gz
     * of a non-tar image never worked. A seekable file goes through the same path as an uncompressed tar.
     */
    private fun importCompressed(decoded: InputStream, name: String, depth: Int) {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val inner = base.replace(Regex("(?i)\\.(tgz|tbz2?|txz)$"), ".tar")
            .replace(Regex("(?i)\\.(gz|xz|bz2)$"), "").ifEmpty { "payload" }
        val f = decoded.use { spill(it, inner) }
        try {
            if (f.length() < 2) throw IOException("compressed file is empty: $name")
            FileChannel.open(f.toPath(), StandardOpenOption.READ).use { c -> handle(ChannelSource(c), c, inner, depth + 1) }
        } finally { deleteTree(f.parentFile ?: f) }
    }

    private fun isTar(h: ByteArray) = h.size > 262 && String(h, 257, 5, Charsets.ISO_8859_1) == "ustar"

    private fun streamOf(src: RandomSource): InputStream = object : InputStream() {
        var pos = 0L
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= src.size) return -1
            val k = minOf(len.toLong(), src.size - pos).toInt()
            src.read(pos, ByteBuffer.wrap(b, off, k))
            pos += k
            return k
        }
    }.let { BufferedInputStream(it, 1 shl 20) }

    private fun readAllFrom(src: RandomSource): ByteArray {
        val b = ByteBuffer.allocate(src.size.toInt()); src.read(0, b); return b.array()
    }

    private var zipChannel: FileChannel? = null
    /** partition images spilled out of a nested zip, imported only after that zip's temp file is gone (disk peak) */
    private var deferred: MutableList<Pair<File, String>>? = null

    private fun importZip(ch: FileChannel, name: String, depth: Int) {
        val zip = ZipFile.builder().setSeekableByteChannel(ch).get()
        val prevCh = zipChannel
        zipChannel = ch
        try { importZipEntries(zip, name, depth) } finally { zipChannel = prevCh }
    }

    private fun importZipEntries(zip: ZipFile, name: String, depth: Int) {
        val entries = zip.entries.toList()
        val total = entries.sumOf { maxOf(0L, it.size) }.coerceAtLeast(1)
        var done = 0L
        // сначала — сценарий установки (ссылки и права)
        entries.firstOrNull { it.name.endsWith("META-INF/com/google/android/updater-script") }?.let { e ->
            zip.getInputStream(e).use { parseUpdaterScript(String(it.readBytes())) }
        }
        // прошивка, упакованная вместе с папкой (Имя/META-INF/…, Имя/system/…): папку-обёртку снимаем
        val wrap = entries.firstOrNull { it.name.endsWith("META-INF/com/google/android/updater-script") }
            ?.name?.substringBefore("META-INF/")?.takeIf { it.isNotEmpty() && it.count { c -> c == '/' } == 1 } ?: ""
        if (wrap.isNotEmpty()) log("archive with wrapper folder \"${wrap.trimEnd('/')}\"")
        val names = entries.map { it.name.replace('\\', '/').removePrefix(wrap) }
        // system folder dump at any depth (Firmware/system/…); null if the archive has none
        val sysRoot = findSystemRoot(names.filter { !it.contains("__MACOSX/") })
        if (sysRoot != null && sysRoot.isNotEmpty()) log("system folder dump under \"${sysRoot.trimEnd('/')}\"")
        val bootEntries = ArrayList<Pair<String, ZipArchiveEntry>>()
        val pendingZips = ArrayList<ZipArchiveEntry>()
        // split sparse system (system.img_sparsechunk.N, system_sparsechunkN, system.img.N, system.N, system_N, …)
        val sparseChunks = SparseChunks.select(entries.filter { !it.isDirectory && !it.name.contains("__MACOSX/") }.map { it.name })
            .let { sel -> val byName = entries.associateBy { it.name }; sel.mapNotNull { byName[it] } }
        val rawProgramEntries = entries.filter { !it.isDirectory && !it.name.contains("__MACOSX/") &&
            it.name.substringAfterLast('/').matches(Regex("(?i)rawprogram.*\\.xml")) }
        for (e in entries) {
            if (cancelled) throw IOException("cancelled")
            val n = e.name.replace('\\', '/').removePrefix(wrap)
            val base = n.substringAfterLast('/')
            when {
                e.isDirectory -> {}
                n.contains("__MACOSX/") || base.startsWith("._") -> {}
                // everything inside a system folder dump belongs to it (never scanned for boot.img etc.)
                systemRel(sysRoot, n) != null -> {
                    zip.getInputStream(e).use { writeFile(systemRel(sysRoot, n)!!, it, e.unixMode.takeIf { m -> m != 0 }) }
                    gotSystem = true
                }
                base.equals("system.yaffs2.img", true) -> withEntrySource(zip, e) { importImage(it, "system") }
                base.endsWith(".yaffs2.img", true) -> {} // CWM user-data/cache backups are not firmware.
                // boot.img at any depth; the shallowest valid one is taken after the loop
                base.equals("boot.img", true) -> bootEntries.add(n to e)
                base.equals("recovery.img", true) && e.size < 64_000_000 -> zip.getInputStream(e).use { recovery = it.readBytes() }
                isSystemImageName(base) ->
                    withEntrySource(zip, e) { importImage(if (SparseSource.probe(it)) SparseSource(listOf(it)) else it, "system") }
                base.matches(Regex("(?i)vendor(\\.ext4)?\\.img")) ->
                    withEntrySource(zip, e) { runCatching { importImage(if (SparseSource.probe(it)) SparseSource(listOf(it)) else it, "vendor") } }
                base.lowercase().endsWith(".zip") && (base.startsWith("image-") || depth == 0 && e.size > 50_000_000) ||
                    base.lowercase().endsWith(".rar") || base.lowercase().endsWith(".7z") -> {
                    handleNestedZip(spillEntry(zip, e), base, depth)
                }
                base.lowercase().endsWith(".zip") && e.size > 0 && !SKIP_NESTED.matches(base) -> pendingZips.add(e)
                base.matches(Regex("(?i).*\\.(tar|tar\\.md5|md5|tgz|tar\\.gz)")) 
                    // Odin: AP/PDA/CODE — система, BL/KERNEL/HOME — ядро с рамдиском; модем и CSC не нужны
                    && !base.startsWith("MODEM") && !base.startsWith("CP_") && !base.contains("CSC") -> {
                    zip.getInputStream(e).use { s ->
                        if (base.endsWith("gz", true)) importCompressed(GZIPInputStream(s, 1 shl 16), base, depth + 1)
                        else importTarStream(s, base, depth + 1)
                    }
                }
                // Block OTA data/list files are useless alone; they are paired after the loop.
                base.endsWith(".new.dat", true) || base.endsWith(".new.dat.br", true) ||
                    base.endsWith(".transfer.list", true) -> {}
                recognizedFirmwareTool(base) -> {
                    val nestedFile = spillEntry(zip, e)
                    try {
                        FileChannel.open(nestedFile.toPath(), StandardOpenOption.READ).use { c ->
                            handle(ChannelSource(c), c, nestedFile.name, depth + 1)
                        }
                    } finally { nestedFile.delete() }
                }
            }
            done += maxOf(0L, e.size)
            onProgress("Extracting: $base", 0.9f * done / total)
        }
        for ((_, be) in bootEntries.sortedBy { it.first.count { c -> c == '/' } }) {
            if (ramdisk != null) break
            zip.getInputStream(be).use { takeBoot(it.readBytes()) }
        }
        for (partition in listOf("system", "vendor")) {
            if (partition == "system" && gotSystem) continue
            val dataE = entries.firstOrNull { it.name.matches(Regex("(?i)(.*/)?$partition\\.new\\.dat(\\.br)?")) } ?: continue
            val dir = dataE.name.substringBeforeLast('/', "")
            val listE = entries.firstOrNull {
                it.name.substringBeforeLast('/', "") == dir &&
                    it.name.substringAfterLast('/').equals("$partition.transfer.list", true)
            }
            if (listE == null) {
                if (partition == "system") throw IOException("$partition.transfer.list missing")
                log("vendor.transfer.list missing, skipping vendor.new.dat"); continue
            }
            val list = zip.getInputStream(listE).use { String(it.readBytes(), Charsets.UTF_8) }
            onProgress("Building $partition.img from OTA", 0.5f)
            val raw = File(tmp, "$partition.raw")
            try {
                zip.getInputStream(dataE).use { s ->
                    val data = if (dataE.name.endsWith(".br", true)) BrotliInputStream(BufferedInputStream(s, 1 shl 20)) else s
                    TransferList.build(list, data, raw)
                }
                FileChannel.open(raw.toPath(), StandardOpenOption.READ).use { c ->
                    if (partition == "vendor") runCatching { importImage(ChannelSource(c), partition) }
                    else importImage(ChannelSource(c), partition)
                }
            } finally { raw.delete() }
        }
        if (sparseChunks.isNotEmpty() && !gotSystem) {
            val parts = sparseChunks.map { spillEntry(zip, it) }
            val chans = parts.map { FileChannel.open(it.toPath(), StandardOpenOption.READ) }
            try {
                val srcs = chans.map { ChannelSource(it) }
                // names like system.0 / system_1 are ambiguous: only a real sparse set is used
                if (srcs.all { SparseSource.probe(it) }) {
                    log("sparse system in ${srcs.size} part(s): ${sparseChunks.joinToString { it.name.substringAfterLast('/') }}")
                    importImage(SparseSource(srcs), "system")
                } else log("ignoring ${sparseChunks.first().name}: not a sparse image set")
            } finally { chans.forEach { it.close() }; parts.forEach { it.delete() } }
        }
        if (rawProgramEntries.isNotEmpty() && (!gotSystem || ramdisk == null)) importEdl(zip, entries, rawProgramEntries)
        // zips at any depth (Firmware/fw.zip): opened only if the outer archive had no system / boot
        for (pe in pendingZips) {
            if (cancelled) throw IOException("cancelled")
            if (gotSystem && ramdisk != null) break
            val nested = spillEntry(zip, pe)
            if (!gotSystem) handleNestedZip(nested, pe.name.substringAfterLast('/'), depth)
            else try { bootFromArchive(nested, pe.name) } finally { nested.delete() }
        }
        if (!gotSystem) log("no system found in archive $name")
    }

    /**
     * Qualcomm EDL package (rawprogram*.xml + split images). Of all rawprogram XMLs the one that
     * describes the partition and whose chunk files are really in the archive is chosen, only the
     * chunks it names are copied out, merged into one raw image (offsets relative to the lowest
     * start sector, sparse chunks expanded) and imported. Same for boot when none was found.
     */
    private fun importEdl(zip: ZipFile, entries: List<ZipArchiveEntry>, xmlEntries: List<ZipArchiveEntry>) {
        val edlDir = File(tmp, "edl-${System.nanoTime()}").apply { mkdirs() }
        try {
            val xmls = xmlEntries.map { spillEntry(zip, it) }
            val byBase = HashMap<String, MutableList<ZipArchiveEntry>>()
            for (x in entries) if (!x.isDirectory && !x.name.contains("__MACOSX/"))
                byBase.getOrPut(x.name.replace('\\', '/').substringAfterLast('/').lowercase()) { ArrayList() }.add(x)
            for (label in listOf("system", "boot")) {
                if (cancelled) throw IOException("cancelled")
                if (label == "system" && gotSystem) continue
                if (label == "boot" && ramdisk != null) continue
                val chunks = File(edlDir, label).apply { mkdirs() }
                val raw = File(tmp, "edl-$label.raw.img")
                try {
                    val plan = FirmwareToolset.planEdl(xmls, byBase.keys, label) { msg -> log(msg) } ?: continue
                    if (plan.missing > 0) log("EDL: ${plan.missing} $label chunk(s) missing from archive")
                    val xmlDir = xmlEntries.getOrNull(xmls.indexOf(plan.xml))?.name?.replace('\\', '/')?.substringBeforeLast('/', "") ?: ""
                    for (file in plan.files()) {
                        val base = file.replace('\\', '/').substringAfterLast('/')
                        val cands = byBase[base.lowercase()] ?: continue
                        // prefer the copy next to the XML, otherwise the closest one
                        val pick = cands.firstOrNull { it.name.replace('\\', '/').substringBeforeLast('/', "") == xmlDir }
                            ?: cands.minByOrNull { it.name.length }!!
                        onProgress("EDL: $base", -1f)
                        zip.getInputStream(pick).use { i -> File(chunks, base).outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                    }
                    FirmwareToolset.combineEdl(plan, chunks, raw) { msg -> log(msg) }
                    deleteTree(chunks)
                    FileChannel.open(raw.toPath(), StandardOpenOption.READ).use { c ->
                        val src = ChannelSource(c)
                        if (label == "system") importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                        else if (raw.length() <= 128L shl 20) takeBoot(raw.readBytes())
                    }
                } catch (t: Throwable) {
                    if (cancelled) throw t
                    log("EDL $label combine skipped: ${t.message ?: t.javaClass.simpleName}")
                } finally { deleteTree(chunks); raw.delete() }
            }
            xmls.forEach { it.delete() }
        } finally { deleteTree(edlDir) }
    }

    /**
     * Commons Compress SevenZFile is a sequential reader: each entry must be consumed
     * through SevenZFile.read() before advancing. Do not create an independent stream
     * per entry; that breaks solid archives and versions of Commons Compress that do
     * not expose entry streams.
     */
    private fun importSevenZ(ch: FileChannel, name: String, depth: Int) {
        SevenZFile.builder().setSeekableByteChannel(ch).get().use { z ->
            val sysRoot = findSystemRoot(z.entries.map { it.name.replace('\\', '/').trimStart('/') })
            val nested = ArrayList<Pair<File, String>>()
            val vdat = LinkedHashMap<String, MutableMap<String, File>>()
            while (true) {
                if (cancelled) throw IOException("cancelled")
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val n = e.name.replace('\\', '/').trimStart('/')
                val base = n.substringAfterLast('/')
                val entryStream = object : InputStream() {
                    override fun read(): Int {
                        val one = ByteArray(1)
                        val count = read(one, 0, 1)
                        return if (count < 0) -1 else one[0].toInt() and 0xff
                    }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (len == 0) return 0
                        if (cancelled) throw IOException("cancelled")
                        return z.read(b, off, len)
                    }
                }
                when {
                    n.contains("__MACOSX/") || base.startsWith("._") -> drain(entryStream)
                    systemRel(sysRoot, n) != null -> {
                        writeFile(systemRel(sysRoot, n)!!, entryStream, null)
                        gotSystem = true
                    }
                    base.endsWith(".yaffs2.img", true) && !base.equals("system.yaffs2.img", true) -> drain(entryStream)
                    base.equals("boot.img", true) -> takeBoot(entryStream.readBytes())
                    base.equals("recovery.img", true) && e.size < 64_000_000 -> recovery = entryStream.readBytes()
                    base.equals("system.yaffs2.img", true) ||
                        isSystemImageName(base) -> {
                        nested.add(spill(entryStream, base) to "system")
                    }
                    base.matches(Regex("(?i).*\\.(zip|rar|7z|tar|tar\\.md5|md5|tgz|tar\\.gz)")) &&
                        e.size > 0 && !base.startsWith("MODEM") && !base.startsWith("CP_") && !base.contains("CSC") -> {
                        nested.add(spill(entryStream, base) to "archive")
                    }
                    base.endsWith(".new.dat", true) || base.endsWith(".new.dat.br", true) || base.endsWith(".transfer.list", true) -> {
                        val nestedFile = spillPreservingName(entryStream, base)
                        val key = base.lowercase()
                            .removeSuffix(".br")
                            .removeSuffix(".new.dat")
                            .removeSuffix(".transfer.list")
                        val slot = vdat.getOrPut(key) { linkedMapOf() }
                        when {
                            base.endsWith(".transfer.list", true) -> slot["list"] = nestedFile
                            else -> slot["data"] = nestedFile
                        }
                    }
                    recognizedFirmwareTool(base) -> {
                        val nestedFile = spill(entryStream, base)
                        nested.add(nestedFile to "tool")
                    }
                    else -> drain(entryStream)
                }
                onProgress("Extracting: $base", 0.5f)
            }
            for ((key, pair) in vdat) {
                val data = pair["data"]
                val list = pair["list"]
                if (data != null && list != null) {
                    try {
                        importVdatPair(data, list, key, depth + 1)
                    } finally { data.delete(); list.delete() }
                } else {
                    pair.values.forEach { it.delete() }
                    log("incomplete VDAT pair in 7z archive: $key")
                }
            }
            for ((f, kind) in nested) {
                try {
                    FileChannel.open(f.toPath(), StandardOpenOption.READ).use { c ->
                        val src = ChannelSource(c)
                        if (kind == "system") importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                        else handle(src, c, f.name, depth + 1)
                    }
                } finally { f.delete() }
            }
            if (!gotSystem && nested.isEmpty()) log("no system found in archive $name")
        }
    }

    private fun importVdatPair(data: File, list: File, key: String, depth: Int) {
        val part = key.substringAfterLast('/').lowercase()
        val mount = when { part.isBlank() || part == "system" -> "system"; part == "vendor" -> "vendor"; else -> {
            log("skipping OTA partition \"$part\" (not system/vendor)"); return } }
        val raw = File(tmp, "${if (key.isBlank()) "system" else key}.img")
        val actualData = if (data.name.endsWith(".br", true)) {
            val dec = File(tmp, "${if (key.isBlank()) "system" else key}.new.dat")
            BrotliInputStream(BufferedInputStream(FileInputStream(data), 1 shl 20)).use { input ->
                dec.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
            dec
        } else data
        try {
            val transfer = list.readText(Charsets.UTF_8)
            FileInputStream(actualData).use { dataIn ->
                TransferList.build(transfer, dataIn, raw)
            }
            FileChannel.open(raw.toPath(), StandardOpenOption.READ).use { c -> importImage(ChannelSource(c), mount) }
        } finally {
            if (actualData !== data) actualData.delete()
            raw.delete()
        }
    }

    private fun drain(input: InputStream) {
        val buffer = ByteArray(64 * 1024)
        while (input.read(buffer) >= 0) if (cancelled) throw IOException("cancelled")
    }

    /** RAR support is used for RAR4/RAR5 firmware bundles; entries are streamed to temp files. */
    private fun importRar(file: File, name: String, depth: Int) {
        if (depth > 4) throw IOException("nested archive depth exceeded")
        RarArchive(file).use { rar ->
            val sysRoot = findSystemRoot(rar.fileHeaders.map { (it.fileNameString ?: "").replace('\\', '/').trimStart('/') })
            val pending = ArrayList<Pair<File, String>>()
            val vdat = LinkedHashMap<String, MutableMap<String, File>>()
            for (e: RarFileHeader in rar.fileHeaders) {
                if (cancelled) throw IOException("cancelled")
                if (e.isDirectory) continue
                val entryName = (e.fileNameString ?: "").replace('\\', '/').trimStart('/')
                if (entryName.isBlank() || entryName.split('/').any { it == ".." }) continue
                val base = entryName.substringAfterLast('/')
                val sysRel = systemRel(sysRoot, entryName)
                val interesting = sysRel != null ||
                    base.equals("boot.img", true) || base.equals("recovery.img", true) ||
                    base.equals("system.yaffs2.img", true) ||
                    isSystemImageName(base) ||
                    base.matches(Regex("(?i).*\\.(zip|rar|7z|tar|tar\\.md5|md5|tgz|tar\\.gz|new\\.dat|transfer\\.list)")) ||
                    recognizedFirmwareTool(base)
                if (!interesting) continue
                val dir = File(tmp, "rar-${System.nanoTime()}").apply { mkdirs() }
                val temp = File(dir, base).apply { parentFile?.mkdirs() }
                temp.outputStream().use { out -> rar.extractFile(e, out) }
                if (sysRel != null) {
                    FileInputStream(temp).use { input -> writeFile(sysRel, input, null) }
                    gotSystem = true
                    temp.delete()
                } else if (base.equals("boot.img", true)) {
                    takeBoot(temp.readBytes()); temp.delete()
                } else if (base.equals("recovery.img", true) && temp.length() < 64_000_000) {
                    recovery = temp.readBytes(); temp.delete()
                } else if (base.equals("system.yaffs2.img", true) ||
                    isSystemImageName(base)) {
                    pending.add(temp to "system")
                } else if (base.endsWith(".new.dat", true) || base.endsWith(".new.dat.br", true) || base.endsWith(".transfer.list", true)) {
                    val key = base.lowercase().removeSuffix(".br").removeSuffix(".new.dat").removeSuffix(".transfer.list")
                    val slot = vdat.getOrPut(key) { linkedMapOf() }
                    when {
                        base.endsWith(".transfer.list", true) -> slot["list"] = temp
                        else -> slot["data"] = temp
                    }
                } else pending.add(temp to "archive")
            }
            for ((key, pair) in vdat) {
                val data = pair["data"]
                val list = pair["list"]
                if (data != null && list != null) {
                    try { importVdatPair(data, list, key, depth + 1) }
                    finally { data.delete(); list.delete() }
                } else { pair.values.forEach { it.delete() }; log("incomplete VDAT pair in RAR archive: $key") }
            }
            for ((temp, kind) in pending) {
                try {
                    FileChannel.open(temp.toPath(), StandardOpenOption.READ).use { c ->
                        val src = ChannelSource(c)
                        if (kind == "system") importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                        else handle(src, c, temp.name, depth + 1)
                    }
                } finally { temp.delete() }
            }
            if (!gotSystem && pending.isEmpty()) log("no system found in RAR archive $name")
        }
    }

    private fun withEntrySource(zip: ZipFile, e: ZipArchiveEntry, block: (RandomSource) -> Unit) {
        // несжатую запись читаем прямо из архива по смещению, сжатую — через временный файл
        val ch = zipChannel
        if (e.method == ZipArchiveEntry.STORED && ch != null) {
            runCatching { zip.getRawInputStream(e).close() } // вычисляет dataOffset
            if (e.dataOffset > 0) { block(ChannelSource(ch, e.dataOffset, e.size)); return }
        }
        val t = spillEntry(zip, e)
        deferred?.let { d -> d.add(t to e.name); return }
        FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> block(ChannelSource(c)) }
        t.delete()
    }

    /** a nested zip: its images are copied out first, the zip deleted, then the images unpacked */
    private fun handleNestedZip(t: File, base: String, depth: Int) {
        val outer = deferred
        val mine = ArrayList<Pair<File, String>>()
        deferred = mine
        try {
            FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> handle(ChannelSource(c), c, base, depth + 1) }
        } finally { deferred = outer; t.delete() }
        try {
            for ((f, entry) in mine) {
                val part = if (entry.substringAfterLast('/').lowercase().startsWith("vendor")) "vendor" else "system"
                FileChannel.open(f.toPath(), StandardOpenOption.READ).use { c ->
                    val src = ChannelSource(c)
                    val img = if (SparseSource.probe(src)) SparseSource(listOf(src)) else src
                    if (part == "vendor") runCatching { importImage(img, part) } else importImage(img, part)
                }
                f.delete()
            }
        } finally { mine.forEach { it.first.delete() } }
    }

    private fun spillEntry(zip: ZipFile, e: ZipArchiveEntry): File = zip.getInputStream(e).use { spill(it, e.name.substringAfterLast('/')) }

    /**
     * Copies a stream into a temp file that keeps its ORIGINAL name. Uniqueness comes from a
     * private directory instead of a "<nanoTime>-" name prefix: the prefix leaked into
     * "<random>-<random>-system.new.dat" names and made sibling lookups
     * (system.new.dat <-> system.transfer.list) impossible.
     */
    private fun spill(i: InputStream, name: String): File {
        val safe = name.substringAfterLast('/').replace('\\', '_').ifEmpty { "entry" }
        val dir = File(tmp, "spill-${System.nanoTime()}").apply { mkdirs() }
        val t = File(dir, safe)
        t.outputStream().use { o -> i.copyTo(o, 1 shl 20) }
        return t
    }

    /** CPIO (newc/crc/odc), used by recovery packages and Android TV firmware bundles. */
    private fun importCpioStream(input: InputStream, name: String, depth: Int) {
        if (depth > 4) throw IOException("nested archive depth exceeded")
        CpioArchiveInputStream(input, "UTF-8").use { cpio ->
            while (true) {
                if (cancelled) throw IOException("cancelled")
                val e: CpioArchiveEntry = cpio.nextEntry ?: break
                val n = e.name.removePrefix("./").trimStart('/')
                if (n.isBlank() || n == "TRAILER!!!" || n.split('/').any { it == ".." }) continue
                val base = n.substringAfterLast('/')
                when {
                    e.isDirectory -> File(root, n).mkdirs()
                    n.startsWith("system/") -> {
                        writeFile(n, cpio.nonClosing(), e.mode.toInt())
                        gotSystem = true
                    }
                    base.equals("boot.img", true) -> takeBoot(cpio.readBytes())
                    base.equals("recovery.img", true) && e.size < 64_000_000 -> recovery = cpio.readBytes()
                    isSystemImageName(base) -> {
                        val t = spill(cpio.nonClosing(), base)
                        try {
                            FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c ->
                                val src = ChannelSource(c)
                                importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                            }
                        } finally { t.delete() }
                    }
                    n.matches(Regex("^(data|dev|sbin|vendor|etc)(/.*)?$")) ||
                        n.matches(Regex("^[^/]+\\.rc$")) || n == "default.prop" -> {
                        if (e.isSymbolicLink) {
                            // In cpio, a symlink target is stored as the entry payload;
                            // unlike tar entries there is no linkName field.
                            val target = cpio.readBytes().toString(Charsets.UTF_8).trimEnd('\u0000')
                            if (target.isNotEmpty() && target.length <= 4096) {
                                symlinks.add(target to "/$n")
                            }
                        } else if (e.isRegularFile) {
                            writeFile(n, cpio.nonClosing(), e.mode.toInt())
                        }
                    }
                }
            }
        }
    }

    private fun importTarStream(s: InputStream, name: String, depth: Int) {
        val tar = TarArchiveInputStream(s, "UTF-8")
        var fullRoot: Boolean? = null
        while (true) {
            if (cancelled) throw IOException("cancelled")
            val e: TarArchiveEntry = tar.nextEntry ?: break
            var n = e.name.removePrefix("./").trimStart('/')
            if (n.isEmpty()) continue
            val base = n.substringAfterLast('/')
            // дерево rootfs целиком (system/, data/, dev/, …)
            if (fullRoot == null && (n == "system" || n.startsWith("system/") || n.startsWith("init.rc") || n.startsWith("default.prop"))) fullRoot = true
            when {
                base.equals("system.yaffs2.img", true) && e.isFile -> {
                    val t = spill(tar.nonClosing(), base)
                    try { FileChannel.open(t.toPath(), StandardOpenOption.READ).use { importImage(ChannelSource(it), "system") } }
                    finally { t.delete() }
                }
                base.endsWith(".yaffs2.img", true) -> {}
                isSystemImageName(base.replace(Regex("(?i)\\.lz4$"), "")) && e.isFile -> {
                    val t = spill(maybeLz4(tar, base), base)
                    FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c ->
                        val src = ChannelSource(c)
                        importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                    }
                    t.delete()
                }
                base.matches(Regex("(?i)(boot\\.img|zImage|kernel)(\\.lz4)?")) && e.isFile && !inSystemDump(n) -> takeBoot(maybeLz4(tar, base).readBytes())
                base.matches(Regex("(?i)recovery\\.img(\\.lz4)?")) && e.isFile && e.size < 64_000_000 && !inSystemDump(n) -> recovery = maybeLz4(tar, base).readBytes()
                base.matches(Regex("(?i).*\\.(tar|tar\\.md5)")) && e.isFile && e.size > 20_000_000 -> importTarStream(tar.nonClosing(), base, depth + 1)
                // factory-образы Google: tgz → image-*.zip → system.img/boot.img; Samsung: zip внутри tar
                base.lowercase().endsWith(".zip") && e.isFile && e.size > 20_000_000 -> {
                    handleNestedZip(spill(tar.nonClosing(), base), base, depth)
                }
                n.matches(Regex("^(system|data|dev|sbin|vendor|etc)(/.*)?$")) || n.matches(Regex("^[^/]+\\.rc$")) || n == "default.prop" || n.startsWith("dhd.") -> {
                    // файлы корня (рамдиск, dhd.*) берём, только если архив — целое дерево rootfs
                    if (fullRoot != true && !n.startsWith("system") && !(n.startsWith("dhd.") || n.endsWith(".rc") || n == "default.prop" || n.startsWith("sbin"))) continue
                    when {
                        e.isDirectory -> File(root, n).mkdirs()
                        e.isSymbolicLink -> symlinks.add(e.linkName to "/$n")
                        e.isLink -> hardlink(n, e.linkName)
                        e.isFile -> {
                            writeFile(n, tar.nonClosing(), e.mode)
                            if (n.startsWith("system/")) gotSystem = true
                        }
                    }
                }
                // TWRP system.ext4.win: пути без префикса system/
                name.contains("system", true) && name.endsWith(".win", true) -> {
                    val p = "system/$n"
                    when {
                        e.isDirectory -> File(root, p).mkdirs()
                        e.isSymbolicLink -> symlinks.add(e.linkName to "/$p")
                        e.isFile -> { writeFile(p, tar.nonClosing(), e.mode); gotSystem = true }
                    }
                }
            }
            if (files % 200 == 0) onProgress("Extracting: $base", -1f)
        }
    }

    private fun InputStream.nonClosing(): InputStream = object : java.io.FilterInputStream(this) { override fun close() {} }

    private fun maybeLz4(s: InputStream, name: String): InputStream =
        if (name.endsWith(".lz4", true)) org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream(s.nonClosing()) else s.nonClosing()

    private fun hardlink(n: String, target: String) {
        val src = File(root, target.removePrefix("./").trimStart('/'))
        val dst = File(root, n)
        if (src.isFile) { dst.parentFile?.mkdirs(); src.copyTo(dst, overwrite = true) }
    }

    // ------------------------------------------------------------------ образы ФС

    private fun importImage(src: RandomSource, mount: String) {
        if (Yaffs2Reader.probe(src)) { importYaffs2(src, mount); return }
        if (!Ext4Reader.probe(src)) { importOtherFilesystem(src, mount); return }
        val fs = Ext4Reader(src)
        onProgress("Reading image $mount (ext4, block ${fs.blockSize})", -1f)
        var n = 0
        fs.walk { path, node ->
            if (cancelled) throw IOException("cancelled")
            val rel = "$mount/$path"
            val f = File(root, rel)
            when {
                node.isDir -> f.mkdirs()
                node.isLink -> symlinks.add(fs.linkTarget(node) to "/$rel")
                node.isFile -> {
                    f.parentFile?.mkdirs()
                    f.outputStream().buffered(1 shl 20).use { fs.copy(node, it) }
                    applyMode(f, node.perm)
                    files++; bytes += node.size
                }
            }
            if (++n % 150 == 0) onProgress("$mount: ${path.substringAfterLast('/')}", -1f)
        }
        if (mount == "system") gotSystem = true
        log("image $mount: $n objects")
    }

    /**
     * Image is neither YAFFS2 nor ext4: identify SquashFS / RFS (FAT) through the firmware toolset,
     * convert it to a temporary zip and unpack that into the guest root. Anything else fails with
     * a message naming the supported formats instead of an opaque ext4 magic error.
     */
    private fun importOtherFilesystem(src: RandomSource, mount: String) {
        val headFile = File(tmp, "probe-${System.nanoTime()}.bin")
        val kind = try {
            val head = ByteBuffer.allocate(minOf((8L shl 20) + 512, src.size).toInt()); src.read(0, head)
            headFile.writeBytes(head.array())
            FirmwareToolset.detect(headFile)
        } finally { headFile.delete() }
        if (kind != "SQUASHFS" && kind != "RFS")
            throw IOException("unrecognized filesystem for $mount (expected ext4, YAFFS2, SquashFS or RFS)")
        val fsName = if (kind == "SQUASHFS") "SquashFS" else "RFS"
        onProgress("Reading image $mount ($fsName)", -1f)
        val dir = File(tmp, "fs-${System.nanoTime()}").apply { mkdirs() }
        try {
            val image = spill(streamOf(src), "$mount.img").let { File(dir, "$mount.img").also { d -> it.copyTo(d, true); it.delete() } }
            val artifacts = FirmwareToolset.extract(image, File(dir, "out")) { msg -> log(msg) }
            val zipFile = artifacts.firstOrNull { it.file.isFile }?.file
                ?: throw IOException("$fsName image produced no files")
            image.delete()
            var n = 0
            ZipFile.builder().setFile(zipFile).get().use { zip ->
                for (e in zip.entries) {
                    if (cancelled) throw IOException("cancelled")
                    // the toolset wraps everything in one top folder named after the image: drop it
                    val inner = e.name.replace('\\', '/').substringAfter('/', "").trimEnd('/')
                    if (inner.isEmpty() || inner.split('/').any { it == ".." }) continue
                    val rel = "$mount/$inner"
                    val f = File(root, rel)
                    if (!f.canonicalPath.startsWith(root.canonicalPath + File.separator)) continue
                    when {
                        e.isDirectory -> f.mkdirs()
                        e.isUnixSymlink -> {
                            val target = zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }
                            if (target.isNotEmpty()) symlinks.add(target to "/$rel")
                        }
                        else -> {
                            zip.getInputStream(e).use { writeFile(rel, it, e.unixMode.takeIf { m -> m != 0 }) }
                        }
                    }
                    if (++n % 150 == 0) onProgress("$mount: ${inner.substringAfterLast('/')}", -1f)
                }
            }
            if (mount == "system") gotSystem = true
            log("$fsName $mount: $n objects")
        } finally { deleteTree(dir) }
    }

    private fun importYaffs2(src: RandomSource, mount: String) {
        onProgress("Reading image $mount (YAFFS2)", -1f)
        val fs = Yaffs2Reader(src) { if (cancelled) throw IOException("cancelled") }
        var n = 0
        fs.walk { path, node ->
            val rel = "$mount/$path"
            val f = File(root, rel)
            if (!f.canonicalPath.startsWith(root.canonicalPath + File.separator)) throw IOException("unsafe YAFFS2 path")
            when (node.type) {
                3 -> { if (!f.isDirectory && !f.mkdirs()) throw IOException("cannot create $rel") }
                2 -> {
                    val target = if (node.alias.startsWith("/")) node.alias.trimStart('/')
                        else rel.substringBeforeLast('/') + "/" + node.alias
                    val normalized = java.nio.file.Paths.get(target).normalize().toString()
                    if (node.alias.isEmpty() || normalized == ".." || normalized.startsWith("../"))
                        throw IOException("YAFFS2 symlink escapes guest root")
                    symlinks.add(node.alias to "/$rel")
                }
                1, 4 -> {
                    f.parentFile?.mkdirs()
                    // Links are only created by finishTree, after all file bytes.
                    if (isLink(f)) throw IOException("YAFFS2 destination is a symlink")
                    f.outputStream().buffered(1 shl 20).use { fs.copy(node, it) }
                    applyMode(f, fs.fileMode(node) and 0xfff)
                    files++; bytes += f.length()
                }
                5 -> {} // Guest /dev is created by the VM; never mknod on the host.
            }
            if (++n % 150 == 0) onProgress("$mount: ${path.substringAfterLast('/')}", -1f)
        }
        if (mount == "system") gotSystem = true
        val geo = fs.geometry
        log("YAFFS2 $mount: $n objects, page ${geo.pageSize}+${geo.spareSize}, " +
            (if (geo.sequential) "spare-less (sequential), " else "tags at spare+${geo.tagOffset}, start offset ${geo.base}, ") +
            (if (geo.order == java.nio.ByteOrder.LITTLE_ENDIAN) "LE" else "BE"))
        fs.warnings.forEach { log("YAFFS2 $mount: $it") }
    }

    private fun takeBoot(data: ByteArray) {
        if (ramdisk != null) return
        val rd = runCatching { BootImage.ramdisk(data) }.getOrNull()
        if (rd.isNullOrEmpty()) { log("boot: ramdisk not recognized"); return }
        ramdisk = rd
        File(paths.dir, "boot.img").writeBytes(data)
        log("boot: ramdisk, ${rd.size} files")
    }

    // ------------------------------------------------------------------ файлы, ссылки, права

    private fun writeFile(rel: String, i: InputStream, mode: Int?) {
        val f = File(root, rel)
        if (!f.canonicalPath.startsWith(root.canonicalPath)) return // защита от ../ в архиве
        f.parentFile?.mkdirs()
        if (runCatching { android.system.OsConstants.S_ISLNK(Os.lstat(f.path).st_mode) }.getOrDefault(false)) f.delete()
        f.outputStream().buffered(1 shl 20).use { o -> bytes += i.copyTo(o, 1 shl 16) }
        if (mode != null) applyMode(f, mode and 0xfff)
        files++
    }

    private fun applyMode(f: File, perm: Int) {
        if (perm and 0x49 != 0) f.setExecutable(true, false)
        f.setReadable(true, false)
    }

    private fun parseUpdaterScript(s: String) {
        val text = s.replace(Regex("#[^\n]*"), "")
        for (m in Regex("symlink\\s*\\(([^;]*?)\\)\\s*;", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            val args = Regex("\"([^\"]*)\"").findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
            if (args.size >= 2) for (link in args.drop(1)) symlinks.add(args[0] to link)
        }
        for (m in Regex("set_perm_recursive\\s*\\(([^;]*?)\\)\\s*;", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            val a = m.groupValues[1].split(',').map { it.trim().trim('"') }
            if (a.size >= 5) a[3].toIntOrNull(8)?.let { perms.add(Triple(a[4], it, true)) }
        }
        for (m in Regex("set_perm\\s*\\(([^;]*?)\\)\\s*;", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            val a = m.groupValues[1].split(',').map { it.trim().trim('"') }
            if (a.size >= 4) a[2].toIntOrNull(8)?.let { perms.add(Triple(a[3], it, false)) }
        }
        for (m in Regex("set_metadata(_recursive)?\\s*\\(([^;]*?)\\)\\s*;", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            val a = m.groupValues[2].split(',').map { it.trim().trim('"') }
            if (a.isEmpty()) continue
            val key = if (m.groupValues[1].isNotEmpty()) "fmode" else "mode"
            val i = a.indexOf(key)
            if (i > 0 && i + 1 < a.size) a[i + 1].toIntOrNull(8)?.let { perms.add(Triple(a[0], it, m.groupValues[1].isNotEmpty())) }
        }
        log("updater-script: ${symlinks.size} symlinks, ${perms.size} perms")
    }

    /** Ссылки (с переводом абсолютных целей в относительные), права, служебные ссылки корня. */
    private fun finishTree() {
        // рамдиск: init*.rc, default.prop, sbin/, file_contexts…
        ramdisk?.forEach { e ->
            val type = e.mode and 0xF000
            val n = e.name
            if (n.isEmpty() || n == "." || n.startsWith("system/") || n.startsWith("data/") || n.startsWith("dev/") || n.startsWith("proc") || n.startsWith("sys/")) return@forEach
            val f = File(root, n)
            when (type) {
                0x4000 -> f.mkdirs()
                0xA000 -> symlinks.add(String(e.data) to "/$n")
                0x8000 -> {
                    if (n == "init" || n.startsWith("sbin/ueventd") || n.startsWith("sbin/adbd")) return@forEach
                    f.parentFile?.mkdirs(); f.writeBytes(e.data)
                    applyMode(f, e.mode and 0xfff)
                }
            }
        }
        var made = 0
        for ((target, link) in symlinks) {
            val rel = link.trimStart('/')
            val f = File(root, rel)
            if (!f.canonicalPath.startsWith(root.canonicalPath)) continue
            f.parentFile?.mkdirs()
            val t = relTarget(link, target)
            runCatching {
                if (f.exists() || isLink(f)) { if (f.isDirectory && !isLink(f)) return@runCatching; f.delete() }
                Os.symlink(t, f.absolutePath); made++
            }
        }
        for ((p, mode, rec) in perms) {
            val f = File(root, p.trimStart('/'))
            if (rec) f.walkTopDown().filter { it.isFile }.forEach { applyMode(it, mode) } else if (f.isFile) applyMode(f, mode)
        }
        // исполняемые — всё в bin/xbin/sbin
        for (d in listOf("system/bin", "system/xbin", "sbin", "vendor/bin", "system/vendor/bin")) {
            File(root, d).listFiles()?.forEach { if (it.isFile) it.setExecutable(true, false) }
        }
        standardLinks()
        fun rootLink(name: String, target: String) {
            val f = File(root, name)
            if (!f.exists() && !isLink(f) && File(root, target).exists()) runCatching { Os.symlink(target, f.absolutePath) }
        }
        rootLink("vendor", "system/vendor")
        rootLink("etc", "system/etc")
        rootLink("bin", "system/bin")
        runCatching { File(root, "system/etc/mtab").let { if (!it.exists() && !isLink(it)) Os.symlink("../../proc/mounts", it.absolutePath) } }
        log("symlinks created: $made")
    }

    private fun isLink(f: File) = runCatching { android.system.OsConstants.S_ISLNK(Os.lstat(f.absolutePath).st_mode) }.getOrDefault(false)

    /** Абсолютную цель ссылки гостя делаем относительной — иначе ядро телефона уйдёт за пределы дерева. */
    /**
     * Архивы без updater-script и без ссылок в самом архиве (multirom, выгрузки system/ из TWRP) не
     * содержат sh → mksh и ссылок на апплеты toolbox — без них не стартует ни одна служба init.
     * Создаём их сами, если их нет: апплет берём, только если его имя есть в самом toolbox.
     */
    private fun standardLinks() {
        val bin = File(root, "system/bin")
        fun missing(n: String) = File(bin, n).let { !it.exists() && !isLink(it) }
        var made = 0
        if (missing("sh")) {
            val sh = listOf("mksh", "ash").firstOrNull { File(bin, it).isFile }
            if (sh != null && runCatching { Os.symlink(sh, File(bin, "sh").absolutePath) }.isSuccess) made++
        }
        val toolbox = File(bin, "toolbox")
        if (toolbox.isFile) {
            val text = runCatching { String(toolbox.readBytes(), Charsets.ISO_8859_1) }.getOrDefault("")
            for (a in TOOLBOX_APPLETS) {
                if (!missing(a)) continue
                if (!text.contains("\u0000$a\u0000")) continue
                if (runCatching { Os.symlink("toolbox", File(bin, a).absolutePath) }.isSuccess) made++
            }
        }
        if (made > 0) log("sh/toolbox symlinks created: $made (missing from archive)")
    }

    private fun relTarget(link: String, target: String): String {
        if (!target.startsWith("/")) return target
        val from = link.trimStart('/').split('/').dropLast(1)
        val to = target.trimStart('/').split('/').filter { it.isNotEmpty() }
        var i = 0
        while (i < from.size && i < to.size && from[i] == to[i]) i++
        val ups = List(from.size - i) { ".." }
        return (ups + to.drop(i)).joinToString("/").ifEmpty { "." }
    }

    private fun deleteTree(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
        runCatching { f.delete() }
    }

    /** Image names that always mean the system partition (RFS dumps call it factoryfs). */
    private fun isSystemImageName(base: String) = SYSTEM_IMG.matches(base)

    /**
     * Folder dump of the system partition at any depth ("system/…", "Firmware/system/…").
     * Returns the prefix in front of "system/" ("" or "Firmware/"), or null if there is no dump.
     * Below the top level a folder only counts if it holds typical system content, so that an
     * unrelated "system" folder is not mistaken for the partition. The shallowest dump wins.
     */
    private fun findSystemRoot(names: Collection<String>): String? {
        val markers = listOf("framework/", "app/", "bin/", "lib/", "etc/")
        val found = LinkedHashMap<String, Boolean>() // prefix -> has build.prop
        for (raw in names) {
            val n = raw.replace('\\', '/').trimStart('/')
            var i = n.indexOf('/')
            var start = 0
            while (true) {
                if (n.regionMatches(start, "system/", 0, 7, ignoreCase = true)) {
                    val prefix = n.substring(0, start)
                    val rest = n.substring(start + 7)
                    if (start == 0) found.putIfAbsent("", false) // plain top-level system/ (legacy layout)
                    if (rest == "build.prop") found[prefix] = true
                    else if (markers.any { rest.startsWith(it) }) found.putIfAbsent(prefix, false)
                }
                if (i < 0) break
                start = i + 1
                i = n.indexOf('/', start)
            }
        }
        if (found.isEmpty()) return null
        return found.entries.sortedWith(compareBy<Map.Entry<String, Boolean>>({ it.key.count { c -> c == '/' } }, { !it.value })).first().key
    }

    /** "system/…" path for an entry under the dump rooted at [root], or null if it is outside it. */
    private fun systemRel(root: String?, n: String): String? {
        if (root == null || n.length < root.length + 7 || !n.startsWith(root)) return null
        if (!n.regionMatches(root.length, "system/", 0, 7, ignoreCase = true)) return null
        return "system/" + n.substring(root.length + 7)
    }

    /** A boot.img in a folder named system is part of that dump, not the boot partition. */
    private fun inSystemDump(n: String) = n.startsWith("system/") || n.contains("/system/")

    private fun bootFromArchive(f: File, name: String) {
        runCatching { FileInputStream(f).use { takeBoot(BootPartitionSource.read(it, name, tmp)) } }
    }

    companion object {
        private val SYSTEM_IMG = Regex("(?i)system(\\.ext4)?\\.img(\\.ext4)?|system_image\\.img|system\\.raw\\.img|system\\.rfs|factoryfs(\\.img|\\.rfs)?")
        private val SKIP_NESTED = Regex("(?i).*(gapps|supersu|magisk|busybox|xposed|twrp|modem|csc).*")
        /** Апплеты toolbox Android 2.3–6.0 (ссылка создаётся, только если апплет есть в бинарнике). */
        private val TOOLBOX_APPLETS = listOf(
            "cat", "chcon", "chmod", "chown", "clear", "cmp", "cp", "date", "dd", "df", "dmesg", "du", "getenforce",
            "getevent", "getprop", "getsebool", "grep", "hd", "id", "ifconfig", "iftop", "insmod", "ioctl", "ionice",
            "kill", "ln", "load_policy", "log", "ls", "lsmod", "lsof", "md5", "mkdir", "mkswap", "mount", "mv",
            "nandread", "netstat", "newfs_msdos", "nohup", "notify", "printenv", "ps", "readlink", "renice",
            "restorecon", "rm", "rmdir", "rmmod", "route", "runcon", "schedtop", "sendevent", "setenforce",
            "setprop", "setsebool", "sleep", "smd", "start", "stop", "swapoff", "swapon", "sync", "top", "touch",
            "umount", "uptime", "vmstat", "watchprops", "wipe", "chroot", "sh",
        ).filter { it != "sh" }
    }
}