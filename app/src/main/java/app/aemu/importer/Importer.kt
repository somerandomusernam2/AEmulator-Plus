/* Modified for AEmulator Plus, 2026-11-07: the choice between several images in a 7z (system_a.img / system_b.img) reads the archive forward only (SevenZMemberReader). */
/* Modified for AEmulator Plus, 2026-11-06: early route for encrypted LG KDZ files (FirmwareToolset.isEncryptedKdz). */
/* Modified for AEmulator Plus, 2026-11-06: an archive (zip, 7z, rar, tar, cpio) with several images of one partition asks which to import (PartitionChoice, planPartitionChoices);
 * vendor.tar, system_ext.tar and the other partition tars are read (importTarStream). */
/* Modified for AEmulator Sunset, 2026-10-03: VM archive restore and boot retention.
 * Modified for AEmulator Super Plus, 2026-10-11: Sony .ftf / .sin import.
 * Modified for AEmulator Plus, 2026-10-23: Android-x86 ISO 9660 import (importIso, Iso9660.kt).
 * Modified for AEmulator Plus, 2026-10-23: pre-1.0 firmware (bare ramdisk.img, /javalib, /android): see LegacyFirmware.
 * Modified for AEmulator Plus, 2026-10-30: recovery embedded in the kernel initramfs (early Samsung): see RecoveryImage.isEmbedded.
 * GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.importer

import app.aemu.R
import android.content.Context
import android.net.Uri
import android.system.Os
import app.aemu.core.CscImport
import app.aemu.core.GuestImage
import app.aemu.core.PropArea
import app.aemu.core.LegacyFirmware
import app.aemu.core.LocalizedIOException
import app.aemu.core.ImageStore
import app.aemu.core.TreeFixer
import app.aemu.core.X86SoftwareGles
import app.aemu.core.VmPaths
import app.aemu.core.RecoveryImage
import app.aemu.core.SystemLayout
import app.aemu.core.StockImage
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
 *  - Huawei dload/UPDATE.APP (packet chain, YAFFS2 system + boot/recovery images)
 *  - Acer flash package (*.bin / *.nb0: record table + YAFFS2 rootfs.img / system.img)
 *  - Sony Flashtool .ftf (ZIP из .sin v1–v4: system, kernel (ELF), fotakernel) и отдельные .sin
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
    private val MAX_RAMDISK = 64L * 1024 * 1024
    /** Bytes of a super partition read to find its geometry (the metadata slot follows, see LpMetadata.headSize). */
    private val LP_PROBE = 4096 + 52
    private val MAX_RAMDISK_FILE = 32L * 1024 * 1024
    private var recovery: ByteArray? = null
    /** recovery-from-boot.p, install-recovery.sh and recovery-resource.dat of an OTA zip ("recovery/" is extracted to /system by its script) */
    private var rfbPatch: ByteArray? = null
    private var rfbScript: String? = null
    private var rfbBonus: ByteArray? = null
    private val symlinks = ArrayList<Pair<String, String>>() // (цель, путь ссылки в госте)
    private val perms = ArrayList<Triple<String, Int, Boolean>>() // (путь, режим, рекурсивно-файлы)
    private var files = 0
    private var bytes = 0L
    private var gotSystem = false
    private var gotOem = false
    private var gotVendor = false
    /** a Samsung CSC package found in the firmware, kept aside until build.prop shows whether this is Samsung firmware */
    private var pendingCsc: File? = null
    /** The system partition held the whole root file system (Android 7+ A/B "system-as-root": init, init*.rc, sepolicy, sbin/ at its top). */
    private var systemAsRoot = false
    /** boot image whose ramdisk is only the recovery ramdisk (A/B devices keep recovery in boot); used only if the system partition is not system-as-root. */
    private var recoveryBoot: ByteArray? = null

    /** GKI `boot` (header v3/v4): kernel only, no ramdisk anywhere in the image. Still stored as boot.img (kernel version, later OTAs). */
    private var kernelBoot: ByteArray? = null
    /** vendor ramdisk of a vendor_boot partition: unpacked over the generic ramdisk of boot once every container is read */
    private var vendorRamdisk: List<BootImage.CpioEntry>? = null
    @Volatile var cancelled = false
    /**
     * Asked which image to take when an archive (zip, 7z, rar, tar, cpio) holds several of one partition (system_a.img, system_b.img, system.tar, a system/ folder).
     * Null (no screen to ask on): the first option is taken, the one in slot "a" or without a slot.
     */
    var chooser: PartitionChooser? = null
    /** Progress text must follow the language picked in the app, which the application context does not know. */
    private val localized: Context by lazy { app.aemu.AppPrefs.wrap(ctx) }
    private fun lt(@androidx.annotation.StringRes id: Int, vararg args: Any?): String = localized.getString(id, *args)

    fun import(uri: Uri, name: String): GuestImage {
        if (name.endsWith(".aessvm", true)) {
            return app.aemu.core.VmArchiveStorage.restore(ctx, uri) { path ->
                if (cancelled) throw IOException("cancelled")
                onProgress(lt(R.string.imp_restoring, path), -1f)
            }.also { onProgress(lt(R.string.import_ready), 1f) }
        }
        val id = ImageStore.newId(ctx)
        paths = VmPaths(ctx, id)
        root = paths.root
        root.mkdirs()
        try {
            onProgress(lt(R.string.imp_opening, name), 0f)
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw LocalizedIOException("file failed to open", R.string.imp_file_failed_to_open)
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
            useDeferredBoot()
            // build.prop is optional: some dumps/ports lack it, Analyzer infers the version from the tree
            if (LegacyFirmware.frameworkDirName(root) == null && !File(root, "system/build.prop").isFile)
                throw LocalizedIOException("no Android system partition found in file", R.string.imp_no_android_system_partition_found_in)
            finishTree()
            applyPendingCsc()
            if (recovery == null) runCatching { rebuildRecoveryFromBoot() }.onFailure { log("recovery: rebuild from boot failed (${it.message}), skipped") }
            if (recovery != null) recovery?.let { r -> runCatching { RecoveryImage.install(paths, r, log) }.onFailure { log("recovery: install failed (${it.message}), skipped") } }
            // no recovery image of its own: early Samsung kernels hold the recovery in the same initramfs as Android's init.rc
            else ramdisk?.let { rd -> runCatching { RecoveryImage.installEmbedded(paths, rd, log) }.onFailure { log("recovery: embedded recovery cannot be installed (${it.message}), skipped") } }
            onProgress(lt(R.string.imp_analyzing_firmware), 0.97f)
            val img = Analyzer(ctx, paths, ramdisk).analyze(id, name)
            // x86 5.x/6.0 firmware of real devices has no libGLES_android.so: libEGL then aborts SurfaceFlinger
            X86SoftwareGles.ensure(ctx, root, img.api, log)
            TreeFixer(ctx, paths, img, log).sanitize()
            ImageStore.save(ctx, img.copy(sizeBytes = ImageStore.du(paths.dir)))
            onProgress(lt(R.string.import_ready), 1f)
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

    private fun handle(src: RandomSource, ch: FileChannel?, name: String, depth: Int, role: FirmwareToolset.Role? = null) {
        if (depth > 8) return
        val head = ByteBuffer.allocate(1100)
        src.read(0, head); head.flip()
        val h = ByteArray(head.remaining()).also { head.get(it) }
        if (h.size < 2) throw LocalizedIOException("empty or truncated firmware file", R.string.imp_empty_or_truncated_firmware_file)
        // Odin's own "Orion.ops" (a ~100 byte settings file shipped next to Odin Multi Downloader) is not an OPPO .ops firmware
        if (depth > 0 && name.endsWith(".ops", true) && src.size < 0x200) { log("skipped $name: too small to be an OPS firmware container"); return }
        // a bare ramdisk (ramdisk.img of an SDK emulator package, initrd.gz, ramdisk.cpio): no boot image around it, so the
        // generic cpio / gzip branches below would unpack it as loose files and never record it as the ramdisk
        val bareRamdisk = decodeBareRamdisk(src, name, h)
        // a file called just "system" / "vendor" / "boot" ... (no extension): a partition image if it is big enough to be one
        val bare = PartitionNames.bareOk(name.replace('\\', '/'), src.size)
        when {
            bareRamdisk != null -> takeBareRamdisk(bareRamdisk, name)
            // Chrome extension container (.crx / .crx3): a ZIP behind a header; the Play Games developer emulator is one
            CrxContainer.probe(h) -> importCrx(src, name, depth)
            // Old LG .kdz (2008-2010 phones): an RC4-encrypted CAB. The bytes look random and the key is the file size, so it is
            // recognised here, ahead of the heuristic probes below; the toolset decrypts it while unpacking the cabinet
            FirmwareToolset.isEncryptedKdz(h, src.size) -> importViaToolset(src, name, depth)
            // Partitions of a payload.bin / fastboot package that hold no filesystem and that the guest never needs:
            // AVB metadata (vbmeta*), device-tree overlays (dtbo, dtb) and SoC firmware blobs (abl, xbl, tz, modem ...).
            // Inside an archive they are skipped instead of failing the whole import.
            depth > 0 && isSkippablePartition(name, h) -> log("skipped $name: partition the guest does not use")
            // raw NAND partition dump (mtd0.img ... mtdN.img): nothing in the name says which partition it is
            NandDump.isMtdName(name.replace('\\', '/').substringAfterLast('/')) -> importMtdDump(src, name)
            name.endsWith(".ofp", true) && h.size > 4 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() && h[2].toInt() == 3 && h[3].toInt() == 4 -> {
                val source = spill(streamOf(src), name)
                val toolWork = File(tmp, "tool-${System.nanoTime()}").apply { mkdirs() }
                try {
                    val artifacts = FirmwareToolset.extract(source, toolWork) { msg -> log(msg) }
                    if (artifacts.isEmpty()) throw LocalizedIOException("OFP ZIP produced no files", R.string.imp_ofp_zip_produced_no_files)
                    for (artifact in artifacts) {
                        if (!artifact.file.isFile) continue
                        FileChannel.open(artifact.file.toPath(), StandardOpenOption.READ).use { c ->
                            handle(ChannelSource(c), c, artifact.file.name, depth + 1, artifact.role)
                        }
                    }
                } finally { deleteTree(toolWork); source.delete() }
            }
            // Sony .sin partition image (a member of an .ftf bundle, or picked on its own); v4 is a bare tar/gzip,
            // so the name is checked before the archive probes below
            name.endsWith(".sin", true) || (h.size > 4 && h[0].toInt() == 3 && String(h, 1, 3, Charsets.ISO_8859_1) == "SIN") ->
                importSin(src, name)
            h.size > 4 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() && h[2].toInt() == 3 && h[3].toInt() == 4 -> {
                if (ch != null) importZip(ch, name, depth) else throw LocalizedIOException("zip without random access", R.string.imp_zip_without_random_access)
            }
            h.size > 6 && h[0] == '7'.code.toByte() && h[1] == 'z'.code.toByte() && h[2] == 0xBC.toByte() && h[3] == 0xAF.toByte() -> {
                if (ch != null) importSevenZ(ch, name, depth) else throw LocalizedIOException("7z without random access", R.string.imp_z_without_random_access)
            }
            h.size >= 7 && h[0] == 'R'.code.toByte() && h[1] == 'a'.code.toByte() &&
                h[2] == 'r'.code.toByte() && h[3] == '!'.code.toByte() && h[4] == 0x1a.toByte() -> {
                val archive = spill(streamOf(src), name)
                try { importRar(archive, name, depth) } finally { archive.delete() }
            }
            h.size >= 6 && (String(h, 0, 6, Charsets.US_ASCII) == "070701" ||
                String(h, 0, 6, Charsets.US_ASCII) == "070702" ||
                String(h, 0, 6, Charsets.US_ASCII) == "070707") ->
                importCpioStream(streamOf(src), name, depth, reopen = { streamOf(src) })
            // ISO 9660 CD image (Android-x86 live CD and its descendants): ramdisk.img + system.sfs / system.img inside.
            // Checked before the GPT probe: an isohybrid image may carry a partition table of its own.
            Iso9660.probe(src) -> importIso(src, name, depth)
            // full eMMC/UFS dump with a GUID partition table: boot / recovery / system are cut out of it
            GptDisk.probe(h) && src.size > (1L shl 20) -> importGptDisk(src, name)
            // Huawei dload/UPDATE.APP: packet chain of raw partition images (the container has no name-based hint)
            HuaweiApp.probe(h) -> importHuaweiApp(src, name, strict = depth == 0)
            // Acer flash package (*.bin / *.nb0): table of named images, YAFFS2 rootfs + system
            AcerBin.probe(h) -> importAcerBin(src, name, strict = depth == 0)
            // HTC: RUU_*.exe installer, Dream-style .nbh, 256-byte-signed rom.zip / *_signed.img.
            // Routed explicitly so the heuristic probes below never look at a multi-hundred-MB executable.
            FirmwareToolset.isHtcContainer(h) -> importViaToolset(src, name, depth)
            // A boot/recovery image that a container extractor (PAC, NBH, TOT/BIN, KDZ, OFP, payload.bin, RUU, ...)
            // already classified: its role decides, whatever the partition happened to be called in that container
            // ("005-recovery.img", "02_Recovery.img", "mmcblk0p15", ...).
            // oem: optional, never boot-critical. Matched before the sparse/ext4 probes below, which would take it for system.
            role == FirmwareToolset.Role.OEM || isOemName(name.replace('\\', '/').substringAfterLast('/'), bare) -> importOem(src)
            // optional /vendor partition (vendor.img, vendor_a.bin): matched before the ext4 probe, which would take it for system
            isVendorName(name.replace('\\', '/').substringAfterLast('/'), bare) -> importVendor(src)
            // product / system_ext / odm and the skins' own partitions (mi_ext, my_*, vgc, prism, optics)
            isExtraName(name.replace('\\', '/').substringAfterLast('/'), bare) -> importExtra(src, name.replace('\\', '/').substringAfterLast('/'))
            role == FirmwareToolset.Role.RECOVERY && src.size < 64_000_000 && isAndroidImage(h) ->
                takeRecovery(streamOf(src), recoveryLabel(name, h))
            role == FirmwareToolset.Role.RECOVERY && src.size < 64_000_000 && isLz4Frame(h) ->
                takeRecovery(streamOf(src), recoveryLabel(name, h))
            SparseSource.probe(src) -> importImage(SparseSource(listOf(src)), "system")
            Ext4Reader.probe(src) -> importImage(src, "system")
            MotoImage.probe(src) -> importImage(src, "system")
            UbiFsReader.probe(src) -> importImage(src, "system")
            Yaffs2Reader.probe(src) -> importImage(src, "system")
            ErofsReader.probe(src) -> importImage(src, "system")
            // Compression signatures win over misleading suffixes (a gzip file named *.tgz.tar is not a plain tar)
            FirmwareContainers.compression(h) != null -> when (FirmwareContainers.compression(h)!!) {
                FirmwareContainers.Compression.GZIP -> importCompressed(GZIPInputStream(streamOf(src), 1 shl 16), name, depth)
                FirmwareContainers.Compression.XZ -> importCompressed(XZInputStream(streamOf(src)), name, depth)
                FirmwareContainers.Compression.BZIP2 -> importCompressed(BZip2CompressorInputStream(streamOf(src)), name, depth)
            }
            isTar(h) || name.endsWith(".tar", true) || name.endsWith(".md5", true) || name.endsWith(".win", true) ->
                importTarStream(streamOf(src), name, depth, src)
            // a recovery image reached directly (extracted by the firmware toolset, nested archive, plain file)
            isRecoveryName(name.replace('\\', '/').substringAfterLast('/'), bare) && src.size < 64_000_000 ->
                takeRecovery(streamOf(src), name.replace('\\', '/').substringAfterLast('/'))
            // lz4-framed file that is not a recovery (system.img.lz4, a tar.lz4 ...): unpacked and recognised again
            isLz4Frame(h) -> importCompressed(org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream(streamOf(src)), name, depth)
            h.size >= 8 && String(h, 0, 8, Charsets.ISO_8859_1) == "ANDROID!" -> {
                val data = readAllFrom(src)
                val base = name.replace('\\', '/').substringAfterLast('/')
                // an unnamed / oddly named image ("part_7.img", "mmcblk0p9") whose ramdisk carries /sbin/recovery is a recovery
                if (role != FirmwareToolset.Role.BOOT && !isBootName(base, bare) && hasRecoveryBinary(data)) takeRecovery(data.inputStream(), recoveryLabel(name, h))
                else takeBoot(data)
            }
            // system.img / factoryfs / factoryfs.img / factoryfs.rfs: a system partition whatever the filesystem
            isSystemImageName(name.replace('\\', '/').substringAfterLast('/'), bare) ->
                importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
            else -> importViaToolset(src, name, depth)
        }
    }

    /**
     * Spills the file, lets [FirmwareToolset] unpack it and feeds every artifact back into [handle].
     * Large intermediates are dropped as early as possible: an HTC One S RUU is a 518 MB installer that
     * yields a 1 GB system.img, which would otherwise sit next to it until the whole import ends.
     */
    private fun importViaToolset(src: RandomSource, name: String, depth: Int) =
        importToolsetFile(spill(streamOf(src), name), name, depth)

    /** [source] is a private temp copy: it is deleted when done. (A 1.4 GB payload.bin must not be copied a second time.) */
    private fun importToolsetFile(source: File, name: String, depth: Int) {
        val toolWork = File(tmp, "tool-${System.nanoTime()}").apply { mkdirs() }
        try {
            val artifacts = FirmwareToolset.extract(source, toolWork) { msg -> log(msg) }
            if (artifacts.isEmpty()) {
                // *.bin is only a name guess ("allow-mbmloader-flashing-mbm.bin" of a Motorola package is a bootloader, not
                // an LG container): inside an archive that is no reason to abort the whole import
                if (depth > 0 && name.endsWith(".bin", true)) { log("skipped $name: not a firmware container"); return }
                throw LocalizedIOException("unknown file format \"$name\"", R.string.imp_unknown_file_format, name)
            }
            // the unpacked copy is all that is needed from here on (unless an artifact IS the spilled file)
            if (artifacts.none { it.file == source }) source.delete()
            for (artifact in artifacts) {
                if (cancelled) throw IOException("cancelled")
                if (!artifact.file.isFile) continue
                FileChannel.open(artifact.file.toPath(), StandardOpenOption.READ).use { c ->
                    handle(ChannelSource(c), c, artifact.file.name, depth + 1, artifact.role)
                }
                if (artifact.cleanup && artifact.file != source) artifact.file.delete()
            }
        } finally {
            deleteTree(toolWork)
            source.delete()
        }
    }

    private fun recognizedFirmwareTool(name: String): Boolean {
        val n = name.lowercase()
        if (n == "file_contexts.bin" || n == "file_contexts" || n.endsWith("/file_contexts.bin")) return false
        // HTC RUU installers only: a bare *.exe in an archive is usually adb/fastboot/driver tooling
        if (n.endsWith(".exe") && n.substringAfterLast('/').startsWith("ruu")) return true
        return n.endsWith(".pac") || n.endsWith(".sbf") || n.endsWith(".nbh") ||
            n.endsWith(".tot") || n.endsWith(".kdz") || n.endsWith(".dz") || isLikelyLgBinName(n) || n.endsWith(".ofp") ||
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
            .replace(Regex("(?i)\\.(gz|xz|bz2|lz4)$"), "").ifEmpty { "payload" }
        val f = decoded.use { spill(it, inner) }
        try {
            if (f.length() < 2) throw LocalizedIOException("compressed file is empty: $name", R.string.imp_compressed_file_is_empty, name)
            FileChannel.open(f.toPath(), StandardOpenOption.READ).use { c -> handle(ChannelSource(c), c, inner, depth + 1) }
        } finally { deleteTree(f.parentFile ?: f) }
    }

    /** recovery.img (optionally lz4-framed) or a TWRP raw recovery.*.win backup. */
    private fun isRecoveryName(base: String, bare: Boolean = false) = PartitionNames.isRecovery(base, bare)

    /** vbmeta / dtbo / dtb / bootloader and radio firmware blobs: recognised by name (slot suffix and extension ignored) or magic. */
    private fun isSkippablePartition(name: String, h: ByteArray): Boolean {
        fun be(i: Int) = if (h.size >= 4) ((h[i].toInt() and 255) shl 24) or ((h[i + 1].toInt() and 255) shl 16) or
            ((h[i + 2].toInt() and 255) shl 8) or (h[i + 3].toInt() and 255) else 0
        if (h.size >= 4 && (String(h, 0, 4, Charsets.ISO_8859_1) == "AVB0" || be(0) == 0xD7B7AB1E.toInt() || be(0) == 0xD00DFEED.toInt())) return true
        val stem = name.replace('\\', '/').substringAfterLast('/').lowercase()
            .substringBefore('.').removeSuffix("_a").removeSuffix("_b")
        return stem.startsWith("vbmeta") || stem in SKIPPABLE_PARTITIONS
    }

    private val SKIPPABLE_PARTITIONS = setOf(
        "dtbo", "dtb", "abl", "xbl", "xbl_config", "tz", "hyp", "aop", "devcfg", "keymaster", "cmnlib", "cmnlib64",
        "qupfw", "uefisecapp", "imagefv", "modem", "bluetooth",
        "pmic", "rpm", "sbl1", "uefi", "uefi_varstore", "dsp", "multiimgoem", "featenabler", "shrm", "storsec",
        "toolsfv", "cpucp", "qweslicstore", "vm-bootsys", "tee", "splash",
    )

    private fun isOemName(base: String, bare: Boolean = false) = PartitionNames.isOem(base, bare)

    private fun isVendorName(base: String, bare: Boolean = false) = PartitionNames.isVendor(base, bare)

    private fun isExtraName(base: String, bare: Boolean = false) = PartitionNames.extraMount(base, bare) != null

    /**
     * An optional partition besides system / vendor / oem: product and system_ext are read into system/ (the layout the
     * guest and [Analyzer] expect), odm and the skin partitions (mi_ext, my_*, vgc, prism, optics) into their own root
     * directory. Never boot-critical: a partition that cannot be read is logged and skipped.
     */
    private fun importExtra(src: RandomSource, base: String) {
        val mount = PartitionNames.extraMount(base, bareOk = true) ?: return
        if (src.size < 4096) return
        try {
            importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, mount)
        } catch (t: Throwable) {
            if (cancelled) throw t
            log("$mount: skipped, cannot read the partition (${t.message ?: t.javaClass.simpleName})")
        }
    }

    /**
     * The /vendor partition (A/B devices such as Android Things on MT8516 mount it from fstab) is read like system and
     * lands in the guest's /vendor. It is optional for importing, so a failure is logged and the partition skipped.
     */
    private fun importVendor(src: RandomSource) {
        try {
            importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "vendor")
            gotVendor = true
        } catch (t: Throwable) {
            if (cancelled) throw t
            log("vendor: skipped, cannot read the partition (${t.message ?: t.javaClass.simpleName})")
        }
    }

    /**
     * A partition delivered as several sparse chunks (vendor.img_sparsechunk.N, vendor.N ...), already copied to
     * [parts] in order. Only a real sparse set is used (names like vendor.0 are ambiguous). The vendor partition is
     * optional, so a failure there is logged and skipped; a split system one is not.
     */
    private fun importSplitSparse(parts: List<File>, partition: String) {
        val chans = parts.map { FileChannel.open(it.toPath(), StandardOpenOption.READ) }
        try {
            val srcs = chans.map { ChannelSource(it) }
            val names = parts.joinToString { it.name }
            if (!srcs.all { SparseSource.probe(it) }) { log("ignoring ${parts.first().name}: not a sparse image set"); return }
            log("sparse $partition in ${srcs.size} part(s): $names")
            if (partition == "system") { importImage(SparseSource(srcs), "system"); gotSystem = true }
            else try {
                importImage(SparseSource(srcs), partition)
                if (partition == "vendor") gotVendor = true
            } catch (t: Throwable) {
                if (cancelled) throw t
                log("$partition: skipped, cannot read the partition (${t.message ?: t.javaClass.simpleName})")
            }
        } finally { chans.forEach { it.close() }; parts.forEach { it.delete() } }
    }

    /** Keeps a CSC package aside (first one wins); it is read only once the system partition shows Samsung. */
    private fun takeCsc(s: InputStream, name: String) {
        if (pendingCsc != null) { drain(s); return }
        pendingCsc = spill(s, name)
    }

    /** Installs the CSC package found in the firmware into /system/csc. Anything but Samsung firmware ignores it silently. */
    private fun applyPendingCsc() {
        val f = pendingCsc ?: return
        pendingCsc = null
        if (!CscImport.isSamsung(root)) return
        val scratch = File(tmp, "csc-${System.nanoTime()}").apply { mkdirs() }
        try {
            val csc = f.inputStream().use { CscPackage.read(it, f.name, scratch) }
            val n = CscImport.apply(root, csc)
            log("csc: $n files from ${f.name} installed into /system/csc")
        } catch (t: Throwable) {
            if (cancelled) throw t
            log("csc: skipped ${f.name} (${t.message ?: t.javaClass.simpleName})")
        } finally { deleteTree(scratch) }
    }

    /**
     * The OEM partition (/oem: carrier / OEM customisation, sometimes the Samsung/Huawei/Moto extras) is optional and
     * not needed to boot, so anything that goes wrong with it is logged and skipped; a half-read one is removed again.
     */
    private fun importOem(src: RandomSource) {
        if (gotOem) return
        val dir = File(root, "oem")
        val existed = dir.exists()
        try {
            importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "oem")
            gotOem = true
        } catch (t: Throwable) {
            if (cancelled) throw t
            log("oem: skipped, cannot read the partition (${t.message ?: t.javaClass.simpleName})")
            symlinks.removeAll { it.second.startsWith("/oem/") }
            if (!existed) deleteTree(dir)
        }
    }

    /**
     * Settings import of an OEM image into an existing VM: [image] is a raw / sparse ext, YAFFS2 or Moto-signed image,
     * [dest] a staging folder that gets "oem/…". The caller moves it into the VM only if this returns normally.
     */
    internal fun importOemInto(image: File, dest: File) {
        root = dest
        File(dest, "oem").mkdirs()
        FileChannel.open(image.toPath(), StandardOpenOption.READ).use { c ->
            val src = ChannelSource(c)
            importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "oem")
        }
        for ((target, link) in symlinks) {
            val rel = link.trimStart('/')
            if (!rel.startsWith("oem/")) continue
            val f = File(root, rel)
            if (!f.canonicalPath.startsWith(File(root, "oem").canonicalPath)) continue
            f.parentFile?.mkdirs()
            runCatching {
                if (f.exists() || isLink(f)) { if (f.isDirectory && !isLink(f)) return@runCatching; f.delete() }
                Os.symlink(relTarget(link, target), f.absolutePath)
            }
        }
    }

    /** *.APP inside an archive: read in place when stored, otherwise through a temp file (the packets need random access). */
    private fun importZipHuaweiApp(zip: ZipFile, e: ZipArchiveEntry, base: String) {
        val ch = zipChannel
        if (e.method == ZipArchiveEntry.STORED && ch != null) {
            runCatching { zip.getRawInputStream(e).close() } // computes dataOffset
            if (e.dataOffset > 0) { importHuaweiApp(ChannelSource(ch, e.dataOffset, e.size), base, strict = false); return }
        }
        val t = spillEntry(zip, e)
        try {
            FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> importHuaweiApp(ChannelSource(c), base, strict = false) }
        } finally { deleteTree(t.parentFile ?: t) }
    }

    /** First [n] bytes of a zip entry (fewer if it is shorter). */
    private fun zipEntryHead(zip: ZipFile, e: ZipArchiveEntry, n: Int): ByteArray = zip.getInputStream(e).use { s ->
        val b = ByteArray(n)
        var o = 0
        while (o < n) { val r = s.read(b, o, n - o); if (r <= 0) break; o += r }
        b.copyOf(o)
    }

    /** Acer *.bin / *.nb0 inside an archive: read in place when stored, otherwise through a temp file (the table needs random access). */
    private fun importZipAcerBin(zip: ZipFile, e: ZipArchiveEntry, base: String) {
        val ch = zipChannel
        if (e.method == ZipArchiveEntry.STORED && ch != null) {
            runCatching { zip.getRawInputStream(e).close() } // computes dataOffset
            if (e.dataOffset > 0) { importAcerBin(ChannelSource(ch, e.dataOffset, e.size), base, strict = false); return }
        }
        onProgress(lt(R.string.imp_unpacking, base), -1f)
        val t = spillEntry(zip, e)
        try {
            FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> importAcerBin(ChannelSource(c), base, strict = false) }
        } finally { deleteTree(t.parentFile ?: t) }
    }

    /**
     * Acer flash package (see [AcerBin]). The importer takes
     *  - `system.img`, the YAFFS2 /system partition (any other image only if no system.img is there and its tree looks like /system),
     *  - `rootfs.img`, the YAFFS2 root file system, as the ramdisk (init*.rc, default.prop, sbin/...), because the
     *    kernel is flashed on its own and there is no boot.img,
     *  - an "ANDROID!" boot / recovery image if a (later) package carries one.
     * Boot loader pieces, kernel, modem, userdata, hidden/modules, factory-test rootfs and the YAFFS2 recovery
     * root file system are skipped.
     * [strict]: the file was chosen directly, so a package without a system partition is an error.
     */
    private fun importAcerBin(src: RandomSource, name: String, strict: Boolean) {
        val label = name.replace('\\', '/').substringAfterLast('/')
        val entries = try { AcerBin.entries(src) } catch (ex: IOException) {
            if (strict) throw ex
            log("skipped $label: ${ex.message}"); return
        }
        val profile = AcerBin.profile(src, entries)
        log("acer $label: ${entries.size} images" +
            (profile["PROJECT"]?.let { ", project $it" } ?: "") + (profile["PLATFORM"]?.let { ", platform $it" } ?: "") +
            (profile["MODEL"]?.let { ", model $it" } ?: ""))
        fun slice(e: AcerBin.Entry): RandomSource = SliceSource(src, e.offset, e.size)
        fun head(e: AcerBin.Entry, n: Int): ByteArray {
            val b = ByteBuffer.allocate(minOf(n.toLong(), e.size).toInt()); slice(e).read(0, b); return b.array()
        }

        // boot / recovery images in Android format (the Gingerbread-era E1xx packages have none)
        for (e in entries) {
            if (cancelled) throw IOException("cancelled")
            if (e.size < 4096 || e.size > 64_000_000) continue
            if (String(head(e, 8), Charsets.ISO_8859_1) != "ANDROID!") continue
            val data = readAllFrom(slice(e))
            if (isRecoveryName(e.base) || hasRecoveryBinary(data)) takeRecovery(data.inputStream(), "recovery (${e.base})") else takeBoot(data)
        }

        // ramdisk: the root file system image
        if (ramdisk == null) entries.firstOrNull { AcerBin.isRootfs(it.base) && it.size > 0 }?.let { takeYaffs2Ramdisk(slice(it), "$label ${it.base}") }

        // system partition: system.img, otherwise the file system image whose tree looks like /system
        val fsEntries = ArrayList<AcerBin.Entry>()
        val systemEntry = entries.firstOrNull { isSystemImageName(it.base) && it.size > 0 } ?: run {
            for (e in entries) {
                if (e.size < (4L shl 20) || AcerBin.isRootfs(e.base) || e.base.contains("ftm", true)) continue
                val h = head(e, 8)
                if (String(h, Charsets.ISO_8859_1) == "ANDROID!" || (h[0].toInt() == 0x7f && h[1] == 'E'.code.toByte())) continue
                val s = slice(e)
                if (SparseSource.probe(s) || Ext4Reader.probe(s) || Yaffs2Reader.probe(s) || UbiFsReader.probe(s) || ErofsReader.probe(s)) fsEntries.add(e)
            }
            fsEntries.firstOrNull { looksLikeSystem(slice(it)) }
        }
        if (systemEntry == null || gotSystem) {
            val what = if (gotSystem) "system already imported" else "no system partition"
            if (strict && !gotSystem) throw LocalizedIOException("\"$label\" has no system partition (not a full Acer firmware package?)", R.string.imp_has_no_system_partition_not_a, label)
            log("acer $label: $what")
            return
        }
        val s = slice(systemEntry)
        importImage(if (SparseSource.probe(s)) SparseSource(listOf(s)) else s, "system")
        for (e in entries) {
            if (e === systemEntry) continue
            if (e.base.endsWith(".img", true) && e.size >= 4096)
                log("acer $label: skipped ${e.base} (${e.size shr 10} KB)")
        }
    }

    /**
     * Root file system image (YAFFS2) as ramdisk: the same list of files finishTree takes from a boot.img ramdisk.
     * Without an init.rc the image is not a root file system and is ignored.
     */
    private fun takeYaffs2Ramdisk(src: RandomSource, label: String) {
        if (ramdisk != null) return
        try {
            val fs = Yaffs2Reader(src) { if (cancelled) throw IOException("cancelled") }
            val out = ArrayList<BootImage.CpioEntry>()
            var total = 0L
            fs.walk { path, node ->
                val perm = node.mode and 0xfff
                when (node.type) {
                    3 -> { out.add(BootImage.CpioEntry(path, 0x4000 or (if (perm != 0) perm else 0x1ed), ByteArray(0))) }
                    2 -> { if (node.alias.isNotEmpty()) out.add(BootImage.CpioEntry(path, 0xA000 or 0x1ff, node.alias.toByteArray())) }
                    1, 4 -> {
                        if (node.size > MAX_RAMDISK_FILE) { log("ramdisk $label: skipped $path (${node.size shr 20} MB)"); return@walk }
                        val bytes = java.io.ByteArrayOutputStream(node.size.toInt().coerceAtLeast(16))
                        fs.copy(node, bytes)
                        total += bytes.size()
                        if (total > MAX_RAMDISK) throw LocalizedIOException("ramdisk too large", R.string.imp_ramdisk_too_large)
                        out.add(BootImage.CpioEntry(path, 0x8000 or (fs.fileMode(node) and 0xfff), bytes.toByteArray()))
                    }
                    else -> {} // device nodes: the guest creates /dev itself
                }
            }
            fs.warnings.forEach { log("ramdisk $label: $it") }
            if (out.none { it.name == "init.rc" }) { log("ramdisk $label: no init.rc, not a root file system"); return }
            ramdisk = out
            log("boot: ramdisk from YAFFS2 $label, ${out.size} entries")
        } catch (ex: IOException) {
            if (cancelled) throw ex
            log("ramdisk $label: ${ex.message}, skipped")
        }
    }

    /**
     * Huawei UPDATE.APP (see [HuaweiApp]). Of its images the importer needs the system partition (YAFFS2 or ext4),
     * the boot image and, optionally, the recovery image; modem, bootloader pieces, userdata and the carrier
     * customisation partition are skipped. Which ANDROID! image is boot and which is recovery is decided by the
     * ramdisk (/sbin/recovery), because the image ids differ between devices.
     * [strict]: the file was chosen directly, so a package without a system partition (UPDATE_cust.APP) is an error.
     */
    private fun importHuaweiApp(src: RandomSource, name: String, strict: Boolean) {
        val label = name.replace('\\', '/').substringAfterLast('/')
        val packets = HuaweiApp.packets(src)
        if (packets.isEmpty()) {
            if (strict) throw LocalizedIOException("not a Huawei update package: $label", R.string.imp_not_a_huawei_update_package, label)
            log("skipped $label: not a Huawei update package"); return
        }
        log("huawei $label: ${packets.size} packets, hardware ${packets[0].hardware.ifEmpty { "?" }}, built ${packets[0].date}")
        fun slice(p: HuaweiApp.Packet): RandomSource = SliceSource(src, p.dataOffset, p.size)
        fun head(p: HuaweiApp.Packet, n: Int): ByteArray {
            val b = ByteBuffer.allocate(minOf(n.toLong(), p.size).toInt()); slice(p).read(0, b); return b.array()
        }

        // boot / recovery
        for (p in packets) {
            if (cancelled) throw IOException("cancelled")
            if (p.size < 4096 || p.size > 64_000_000) continue
            if (String(head(p, 8), Charsets.ISO_8859_1) != "ANDROID!") continue
            val data = readAllFrom(slice(p))
            val tag = "$label id 0x%02x".format(p.id)
            if (hasRecoveryBinary(data)) takeRecovery(data.inputStream(), "recovery ($tag)") else takeBoot(data)
        }

        // filesystems: the system partition is image id 0, otherwise the one whose tree looks like /system
        val fsParts = packets.filter { p ->
            if (p.size < (4L shl 20)) return@filter false
            val h = head(p, 8)
            if (String(h, Charsets.ISO_8859_1) == "ANDROID!" || (h[0].toInt() == 0x7f && h[1] == 'E'.code.toByte())) return@filter false
            val s = slice(p)
            SparseSource.probe(s) || Ext4Reader.probe(s) || Yaffs2Reader.probe(s) || UbiFsReader.probe(s) || ErofsReader.probe(s)
        }
        val systemPacket = fsParts.firstOrNull { it.id == HuaweiApp.ID_SYSTEM }
            ?: fsParts.firstOrNull { looksLikeSystem(slice(it)) }
        if (systemPacket == null || gotSystem) {
            val what = if (gotSystem) "system already imported" else "no system partition"
            if (strict && !gotSystem) throw LocalizedIOException("\"$label\" has no system partition (customization-only package?); import UPDATE.APP instead", R.string.imp_has_no_system_partition_customization_only, label)
            log("huawei $label: $what; skipped ${fsParts.size} other partition(s)")
            return
        }
        onProgress(lt(R.string.imp_verifying, label), -1f)
        val bad = HuaweiApp.verify(src, systemPacket) { if (cancelled) throw IOException("cancelled") }
        if (bad > 0) log("huawei $label: $bad block(s) of the system image fail their CRC, the download may be damaged")
        val s = slice(systemPacket)
        importImage(if (SparseSource.probe(s)) SparseSource(listOf(s)) else s, "system")
        for (p in fsParts) if (p !== systemPacket) log("huawei $label: skipped partition id 0x%02x (%d KB)".format(p.id, p.size shr 10))
    }

    private fun looksLikeSystem(src: RandomSource): Boolean = runCatching {
        var found = false
        val s = if (SparseSource.probe(src)) SparseSource(listOf(src)) else MotoImage.unwrap(src)
        if (UbiFsReader.probe(s)) UbiFsReader(s).walk { path, _ -> if (path == "build.prop" || path == "framework") found = true }
        else if (Yaffs2Reader.probe(s)) Yaffs2Reader(s).walk { path, _ -> if (path == "build.prop" || path == "framework" || path == "javalib") found = true }
        else if (Ext4Reader.probe(s)) Ext4Reader(s).walk { path, _ -> if (path == "build.prop" || path == "framework") found = true }
        else if (ErofsReader.probe(s)) ErofsReader(s).let { fs ->
            found = fs.lookup("build.prop") != null || fs.lookup("framework") != null || fs.lookup("system/build.prop") != null
        }
        found
    }.getOrDefault(false)

    private fun isAndroidImage(h: ByteArray) = h.size >= 8 && String(h, 0, 8, Charsets.ISO_8859_1) == "ANDROID!"
    private fun isLz4Frame(h: ByteArray) = h.size >= 4 && h[0] == 0x04.toByte() && h[1] == 0x22.toByte() && h[2] == 0x4d.toByte() && h[3] == 0x18.toByte()
    /** takeRecovery decides about lz4 framing by the name, so a framed image always gets the suffix. */
    private fun recoveryLabel(name: String, h: ByteArray): String {
        val base = name.replace('\\', '/').substringAfterLast('/')
        return if (isLz4Frame(h) && !base.endsWith(".lz4", true)) "$base.lz4" else base
    }
    private fun hasRecoveryBinary(img: ByteArray): Boolean =
        runCatching { BootImage.ramdisk(img)?.any { it.name.removePrefix("./") == "sbin/recovery" } == true }.getOrDefault(false)

    /** boot.img, or the signed one: boot_signed.img (HTC: 256-byte signature + image, BootImage strips it) / boot_signed (Motorola fastboot XML). */
    private fun isBootName(base: String, bare: Boolean = false) = PartitionNames.isBoot(base, bare)

    /** Recovery is optional: remember the first one found, and never let a bad one abort the import. */
    private fun takeRecovery(i: InputStream, base: String) {
        runCatching {
            val data = BootImage.stripHtcSignature((if (base.endsWith(".lz4", true))
                org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream(i) else i).readBytes())
            if (recovery == null && data.size >= 512) { recovery = data; log("recovery: found $base, ${data.size shr 10} KB") }
        }.onFailure { log("recovery: cannot read $base (${it.message}), skipped") }
    }

    /**
     * No recovery image in the firmware, but a stock recovery-from-boot.p (from the OTA's "recovery/" folder, or from /system of a
     * system image): the recovery is the boot image patched with it, exactly what install-recovery.sh does on the device.
     */
    private fun rebuildRecoveryFromBoot() {
        fun tree(rel: String): ByteArray? = File(root, rel).takeIf { it.isFile && it.length() in 1..(64L shl 20) }
            ?.let { runCatching { it.readBytes() }.getOrNull() }
        val patch = rfbPatch ?: tree("system/recovery-from-boot.p") ?: return
        val script = rfbScript ?: (tree("system/etc/install-recovery.sh") ?: tree("system/bin/install-recovery.sh"))?.toString(Charsets.UTF_8)
        val bonus = rfbBonus ?: tree("system/etc/recovery-resource.dat")
        val boot = File(paths.dir, "boot.img").takeIf { it.isFile }?.readBytes()
        if (boot == null) { log("recovery: recovery-from-boot.p found, but the firmware has no boot image to patch"); return }
        log("recovery: no recovery image, rebuilding it from the boot image and recovery-from-boot.p")
        RecoveryFromBoot.rebuild(boot, script, patch, bonus, log)?.let { takeRecovery(it.inputStream(), "recovery (recovery-from-boot.p)") }
        rfbPatch = null; rfbScript = null; rfbBonus = null
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
        // a tar skips the data of the members it does not read: a seek, not a read of every byte
        override fun skip(n: Long): Long {
            val k = minOf(maxOf(n, 0L), maxOf(src.size - pos, 0L))
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
        // product / system_ext / odm ...: imported after the system image (see importExtra)
        val extraEntries = ArrayList<Pair<String, ZipArchiveEntry>>()
        // product.tar / system_ext.tar: files for system/product, system/system_ext; imported after the system image like the extra images
        val extraTars = ArrayList<Pair<String, ZipArchiveEntry>>()
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
        val foundSysRoot = findSystemRoot(names.filter { !it.contains("__MACOSX/") })
        if (foundSysRoot != null && foundSysRoot.isNotEmpty()) log("system folder dump under \"${foundSysRoot.trimEnd('/')}\"")
        // zip of a raw system partition (build.prop + bin/ at the zip root, no system/ folder): all of it goes under system/
        val rawDump = foundSysRoot == null && PartitionNames.isRawSystemDump(names)
        // several images of one partition (system_a.img + system_b.img + system.tar + a system/ folder, boot_a.img + boot_b.img ...):
        // the person picks one, the others are left out. Null when nothing is doubled: the loop below then works as it always did.
        val plan = planSafely { planZip(zip, entries, wrap, foundSysRoot, rawDump) }
        // the system folder is ignored when an image or a tar was picked for system instead
        val sysRoot = if (plan?.dropSystemFolder == true) null else foundSysRoot
        if (rawDump) log("raw system partition dump (build.prop and bin/ at the archive root)")
        // a package with a vendor.img of its own: the vendor inside a "super" partition is not read a second time
        archiveHasVendorImage = entries.any { !it.isDirectory && it.size > 0 && it.name.replace('\\', '/').substringAfterLast('/').let { b -> PartitionNames.isVendor(b) || PartitionChoice.tarGroup(b) == "vendor" } }
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
            // a picked "system_b.img" / "boot_b.img" is imported under the plain name, which the matchers below know
            val base = plan?.canonical?.get(e.name) ?: n.substringAfterLast('/')
            val bare = PartitionNames.bareOk(n, e.size) && !e.isUnixSymlink
            when {
                n.contains("__MACOSX/") || base.startsWith("._") -> {}
                // an image, tar or folder the person did not pick for its partition
                plan != null && e.name in plan.skip -> {}
                rawDump -> {
                    val rel = "system/" + n.trimStart('/')
                    when {
                        e.isDirectory -> FirmwareContainers.destination(root, rel).mkdirs()
                        e.isUnixSymlink -> {
                            val target = zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }
                            if (target.isNotEmpty()) symlinks.add(target to "/$rel")
                        }
                        else -> zip.getInputStream(e).use { writeFile(rel, it, e.unixMode.takeIf { m -> m != 0 }) }
                    }
                    gotSystem = true
                }
                e.isDirectory -> {}
                // everything inside a system folder dump belongs to it (never scanned for boot.img etc.)
                systemRel(sysRoot, n) != null -> {
                    val srel = systemRel(sysRoot, n)!!
                    if (e.isUnixSymlink) {
                        // a zip that kept its symlinks stores the target as the entry body; writing it as a file left
                        // "sh" as a 4-byte text file ("Exec format error")
                        val target = zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }.trim('\u0000', '\n', '\r')
                        if (target.isNotEmpty()) symlinks.add(target to "/$srel")
                    } else zip.getInputStream(e).use { writeFile(srel, it, e.unixMode.takeIf { m -> m != 0 }) }
                    gotSystem = true
                }
                // whole-disk image with a GPT inside the archive (Android Things iot_rpi3.img: 4.5 GB, deflated):
                // read once as a stream, only the boot / oem / system / gapps slots are kept
                base.endsWith(".img", true) && e.size > (16L shl 20) && GptDisk.probe(zipEntryHead(zip, e, 4104)) -> {
                    val ok = zip.getInputStream(e).use { importGptStream(it, base) }
                    if (!ok) log("$base: partition table unreadable, skipped")
                }
                // stock OTA: no recovery.img, the recovery is boot.img + recovery-from-boot.p (see rebuildRecoveryFromBoot)
                RecoveryFromBoot.isPatchFile(base) && e.size in 1..(32L shl 20) -> zip.getInputStream(e).use { rfbPatch = it.readBytes() }
                RecoveryFromBoot.isScriptFile(base) && e.size in 1..(1L shl 20) ->
                    zip.getInputStream(e).use { val t = String(it.readBytes(), Charsets.UTF_8); if (rfbScript == null) rfbScript = t }
                RecoveryFromBoot.isBonusFile(base) && e.size in 1..(8L shl 20) -> zip.getInputStream(e).use { rfbBonus = it.readBytes() }
                base.equals("system.yaffs2.img", true) -> withEntrySource(zip, e) { importImage(it, "system") }
                base.endsWith(".yaffs2.img", true) -> {} // CWM user-data/cache backups are not firmware.
                // Sony Flashtool .ftf bundle: system.sin, kernel.sin, fotakernel.sin ...
                base.endsWith(".sin", true) -> withSinSource(zip, e) { importSin(it, base) }
                // a whole .ftf bundle inside another archive
                base.endsWith(".ftf", true) && e.size > 0 -> importSonyFile(spillEntry(zip, e), depth)
                // boot.img at any depth; the shallowest valid one is taken after the loop
                isBootName(base, bare) -> bootEntries.add(n to e)
                isRecoveryName(base, bare) && e.size < 64_000_000 -> zip.getInputStream(e).use { takeRecovery(it, base) }
                CscPackage.isCscContainer(base) && e.size > 0 -> zip.getInputStream(e).use { takeCsc(it, base) }
                isOemName(base, bare) -> withEntrySource(zip, e) { importOem(it) }
                isSystemImageName(base, bare) ->
                    withEntrySource(zip, e) { importImage(if (SparseSource.probe(it)) SparseSource(listOf(it)) else it, "system") }
                isVendorName(base, bare) -> withEntrySource(zip, e) { importVendor(it) }
                isExtraName(base, bare) && e.size > 4096 -> extraEntries.add(base to e)
                base.lowercase().endsWith(".zip") && (base.startsWith("image-") || depth == 0 && e.size > 50_000_000) ||
                    base.lowercase().endsWith(".rar") || base.lowercase().endsWith(".7z") -> {
                    handleNestedZip(spillEntry(zip, e), base, depth)
                }
                base.lowercase().endsWith(".zip") && e.size > 0 && !SKIP_NESTED.matches(base) -> pendingZips.add(e)
                base.matches(Regex("(?i).*\\.(tar|tar\\.md5|md5|tgz|tar\\.gz)")) 
                    // Odin: AP/PDA/CODE — система, BL/KERNEL/HOME — ядро с рамдиском; модем и CSC не нужны
                    && !base.startsWith("MODEM") && !base.startsWith("CP_") && !base.contains("CSC") -> {
                    val tarPart = PartitionChoice.tarGroup(base)
                    if (tarPart != null && PartitionChoice.mountOf(tarPart).startsWith("system/")) extraTars.add(base to e)
                    else importZipTar(zip, e, base, depth)
                }
                // Block OTA data/list files are useless alone; they are paired after the loop.
                base.endsWith(".new.dat", true) || base.endsWith(".new.dat.br", true) ||
                    base.endsWith(".transfer.list", true) -> {}
                base.endsWith(".app", true) && e.size > 0 -> importZipHuaweiApp(zip, e, base)
                // Acer packages are recognised by their table, not by the (generic) .bin / .nb0 extension
                (base.endsWith(".nb0", true) || base.endsWith(".bin", true)) && e.size > AcerBin.MIN_SIZE &&
                    AcerBin.probe(zipEntryHead(zip, e, 1100)) -> importZipAcerBin(zip, e, base)
                // payload.bin of an A/B OTA: the spilled copy is unpacked in place (it is already a private temp file)
                base.equals("payload.bin", true) && e.size > 0 -> importToolsetFile(spillEntry(zip, e), base, depth + 1)
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
            onProgress(lt(R.string.imp_extracting, base), 0.9f * done / total)
        }
        for ((_, be) in bootEntries.sortedBy { it.first.count { c -> c == '/' } }) {
            if (ramdisk != null) break
            zip.getInputStream(be).use { takeBoot(it.readBytes()) }
        }
        // Split boot dumps (kernel + ramdisk.gz, or an unpacked initrd/ folder) carry no boot.img: take the ramdisk directly,
        // otherwise init.rc, /sbin/healthd and friends never reach the tree and system_server dies in BatteryService.
        if (ramdisk == null && !systemAsRoot) takeLooseRamdisk(zip, entries, wrap, foundSysRoot)
        for (partition in listOf("system", "vendor", "product", "system_ext", "odm")) {
            if (partition == "system" && gotSystem) continue
            val dataE = entries.firstOrNull { it.name.matches(Regex("(?i)(.*/)?$partition\\.new\\.dat(\\.br)?")) } ?: continue
            val dir = dataE.name.substringBeforeLast('/', "")
            val listE = entries.firstOrNull {
                it.name.substringBeforeLast('/', "") == dir &&
                    it.name.substringAfterLast('/').equals("$partition.transfer.list", true)
            }
            if (listE == null) {
                if (partition == "system") throw LocalizedIOException("$partition.transfer.list missing", R.string.imp_transfer_list_missing, partition)
                log("$partition.transfer.list missing, skipping $partition.new.dat"); continue
            }
            val list = zip.getInputStream(listE).use { String(it.readBytes(), Charsets.UTF_8) }
            onProgress(lt(R.string.imp_building_img_from_ota, partition), 0.5f)
            val raw = File(tmp, "$partition.raw")
            // a Treble OTA (8.0+) carries the vendor partition the same way; with dynamic partitions (10+) product,
            // system_ext and odm come as their own data files too, and none of them is boot-critical
            val mount = if (partition == "product" || partition == "system_ext") "system/$partition" else partition
            try {
                zip.getInputStream(dataE).use { s ->
                    val data = if (dataE.name.endsWith(".br", true)) BrotliInputStream(BufferedInputStream(s, 1 shl 20)) else s
                    TransferList.build(list, data, raw)
                }
                keepStockImage = partition == "system"
                FileChannel.open(raw.toPath(), StandardOpenOption.READ).use { c ->
                    if (partition == "system") importImage(ChannelSource(c), mount)
                    else runCatching { importImage(ChannelSource(c), mount) }.onFailure { if (cancelled) throw it; log("$partition partition: ${it.message}") }
                }
            } finally { keepStockImage = false; raw.delete() }
        }
        for ((base, e) in extraEntries) withEntrySource(zip, e) { importExtra(it, base) }
        for ((base, e) in extraTars) { if (cancelled) throw IOException("cancelled"); importZipTar(zip, e, base, depth) }
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
        // split sparse vendor (vendor.img_sparsechunk.N, vendor.N, ...), same naming schemes as system
        if (!gotVendor) {
            val byName = entries.associateBy { it.name }
            val vendorChunks = SparseChunks.select(
                entries.filter { !it.isDirectory && !it.name.contains("__MACOSX/") }.map { it.name }, "vendor").mapNotNull { byName[it] }
            if (vendorChunks.isNotEmpty()) importSplitSparse(vendorChunks.map { spillEntry(zip, it) }, "vendor")
        }
        if (rawProgramEntries.isNotEmpty() && (!gotSystem || ramdisk == null || recovery == null || !gotOem)) importEdl(zip, entries, rawProgramEntries)
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

    // ------------------------------------------------------------------ several images of one partition

    /** What [planPartitionChoices] decided: members left out (by [Member.key]), the name a picked "_b" image is imported under, and whether the system/ folder lost. */
    private class ArchivePlan(val skip: Set<String>, val canonical: Map<String, String>, val dropSystemFolder: Boolean)

    /**
     * One file of an archive, as [planPartitionChoices] sees it. [key] is what the archive's own import loop calls it (the
     * name of a zip entry; the cleaned path in 7z, rar, tar and cpio), [name] the path with a wrapper folder taken off,
     * [index] its position among all entries (a stream archive is read again up to it), [offset] where its data starts in a
     * tar that can be read in place, or -1.
     */
    private class Member(val key: String, val name: String, val size: Long, val index: Int, val offset: Long = -1)

    /** The three ways the planner reads a member, whatever kind of archive it is in: its first bytes, as a random-access source, as a stream. */
    private interface MemberReader {
        fun head(m: Member, n: Int): ByteArray
        fun <T> source(m: Member, block: (RandomSource) -> T): T
        fun <T> stream(m: Member, block: (InputStream) -> T): T
    }

    /** One way to get a partition out of the archive. [members] are the files it is made of (all of the files of a folder). */
    private class Candidate(
        val kind: PartitionChoice.Kind, val label: String, val slot: Char?,
        val members: List<Member>, var identity: BuildIdentity? = null,
    )

    /** The candidates of one partition: [all] of them, and the [keep] ones that are not empty. */
    private class GroupPlan(val group: String, val all: List<Candidate>, val keep: List<Candidate>)

    /** An image, tar or folder looked into: [blank] when there is nothing in it, [props] its build.prop if it has one. */
    private class Inspected(val blank: Boolean, val props: Map<String, String>?)

    private class FirstEntry : RuntimeException()

    /** A 7z reader that leaves the file open when it is closed: the import's own reader still needs it. */
    private class NonClosingChannel(private val c: java.nio.channels.SeekableByteChannel) : java.nio.channels.SeekableByteChannel by c {
        override fun close() {}
    }

    /**
     * An archive (zip, 7z, rar, tar, cpio) with two or more images of one partition (system_a.img + system_b.img + system.tar +
     * a system/ folder, boot_a.img + boot_b.img, vendor.img + vendor.tar ...) used to import all of them on top of each other,
     * or take whichever came last. Here every such partition is looked into first: the empty ones (a size of 0, a partition of
     * zeros, a filesystem without files) are dropped, and if several are left the person is asked which one to import.
     * The identity shown with an option (device, API level, build number) is read from its build.prop; a partition
     * without one (boot, recovery) is listed by name only.
     *
     * [members] are the regular files of the archive. Returns null when no partition is doubled, so the ordinary import is
     * untouched. An image that is the only one left of its partition is taken without asking, even if it is a "_b" copy
     * (the other slot was empty). [lz4Names]: a name ending in .lz4 counts as the image under it (tars unpack those).
     */
    private fun planPartitionChoices(members: List<Member>, reader: MemberReader, sysRoot: String?, rawDump: Boolean, lz4Names: Boolean = false): ArchivePlan? {
        if (rawDump) return null
        class Raw(val m: Member, val base: String, val kind: PartitionChoice.Kind, val group: String)

        val raws = ArrayList<Raw>()
        val folderFiles = ArrayList<Member>()
        for (m in members) {
            val n = m.name
            val base = n.substringAfterLast('/')
            if (n.contains("__MACOSX/") || base.startsWith("._")) continue
            // (what sits in the system folder is part of it, and never a partition of its own)
            if (systemRel(sysRoot, n) != null) { folderFiles.add(m); continue }
            val tar = PartitionChoice.tarGroup(base)
            if (tar != null) { raws.add(Raw(m, base, PartitionChoice.Kind.TAR, tar)); continue }
            val group = PartitionChoice.imageGroup(base, PartitionNames.bareOk(n, m.size), lz4Names) ?: continue
            raws.add(Raw(m, base, PartitionChoice.Kind.IMAGE, group))
        }
        val doubled = raws.map { it.group }.distinct()
            .filter { g -> raws.count { it.group == g } + (if (g == "system" && sysRoot != null) 1 else 0) >= 2 }
            .sortedWith(compareBy({ PartitionChoice.groupRank(it) }, { it }))
        if (doubled.isEmpty()) return null

        // phase 1: look into every doubled partition, so the number of questions is known before the first one is asked
        val plans = ArrayList<GroupPlan>()
        for (g in doubled) {
            val mine = raws.filter { it.group == g }
            // the same file name in two folders: the path tells them apart
            val clash = mine.map { it.base.lowercase() }.distinct().size < mine.size
            val all = ArrayList<Candidate>()
            for (r in mine) all.add(Candidate(r.kind, if (clash) r.m.name else r.base, PartitionChoice.slotOf(r.base), listOf(r.m)))
            if (g == "system" && sysRoot != null) all.add(Candidate(PartitionChoice.Kind.FOLDER, sysRoot + "system", null, folderFiles))
            val keep = ArrayList<Candidate>()
            for (c in all.sortedBy { PartitionChoice.optionOrder(it.kind, it.slot) }) {
                if (cancelled) throw IOException("cancelled")
                onProgress(lt(R.string.imp_inspecting, c.label), -1f)
                val seen = when (c.kind) {
                    PartitionChoice.Kind.IMAGE -> inspectImageMember(reader, c.members[0], g)
                    PartitionChoice.Kind.TAR -> inspectTarMember(reader, c.members[0], g)
                    PartitionChoice.Kind.FOLDER -> inspectSystemFolder(reader, folderFiles, sysRoot!!)
                }
                if (seen.blank) { log("${c.label}: empty, not offered"); continue }
                c.identity = seen.props?.let { PartitionChoice.identity(g, it) }
                keep.add(c)
            }
            // nothing usable in any of them: left to the ordinary import, which says what is wrong
            if (keep.isNotEmpty()) plans.add(GroupPlan(g, all, keep))
        }
        if (plans.isEmpty()) return null

        // phase 2: one question per partition that still has more than one real option
        val questions = plans.count { it.keep.size > 1 }
        var asked = 0
        val skip = HashSet<String>()
        val canonical = HashMap<String, String>()
        var dropFolder = false
        for (plan in plans) {
            val chosen = if (plan.keep.size == 1) plan.keep[0] else {
                val options = plan.keep.map { c ->
                    PartitionChoice.line(c.label, c.identity, { lt(R.string.imp_choice_api, it) }, { lt(R.string.imp_choice_build, it) })
                }
                val pick = chooser?.choose(PartitionPrompt(plan.group, ++asked, questions, options)) ?: 0
                if (pick == PartitionChooser.CANCEL || cancelled) throw IOException("cancelled")
                plan.keep.getOrElse(pick) { plan.keep[0] }.also {
                    log("${plan.group}: ${plan.keep.size} candidates (${plan.keep.joinToString { k -> k.label }}), taking ${it.label}")
                }
            }
            for (c in plan.all) {
                if (c !== chosen) { for (m in c.members) skip.add(m.key); continue }
                if (c.kind == PartitionChoice.Kind.FOLDER) continue
                val m = c.members[0]
                val base = m.name.substringAfterLast('/')
                // the name the matchers of the import loop know: system_b.img -> system.img, system_b.bin -> system_a.bin
                val plain = PartitionChoice.importName(base, PartitionNames.bareOk(m.name, m.size), lz4Names)
                if (plain != base) canonical[m.key] = plain
                // an image or a tar taken for system: the files of the system folder are not imported on top of it
                if (plan.group == "system" && sysRoot != null) dropFolder = true
            }
        }
        if (dropFolder) for (m in folderFiles) skip.add(m.key)
        return ArchivePlan(skip, canonical, dropFolder)
    }

    /**
     * Runs one of the planners. The choice between several images is a convenience: an archive that cannot be listed (a "tar"
     * that is no tar, a damaged entry) goes on to the ordinary import, which says what is wrong. Cancelling stays cancelling.
     */
    private fun <T> planSafely(block: () -> T?): T? = try { block() } catch (t: Throwable) {
        if (cancelled || t.message == "cancelled") throw t
        log("choice between several images skipped: ${t.message ?: t.javaClass.simpleName}")
        null
    }

    private fun readUpTo(s: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var o = 0
        while (o < n) { val r = s.read(b, o, n - o); if (r <= 0) break; o += r }
        return b.copyOf(o)
    }

    /** [input] copied to a temp file (deleted again) and handed over as a random-access source. */
    private fun <T> withSpilledSource(input: InputStream, name: String, block: (RandomSource) -> T): T {
        val t = spill(input, name)
        try {
            return FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> block(ChannelSource(c)) }
        } finally { deleteTree(t.parentFile ?: t) }
    }

    /** A zip entry as a [RandomSource]: read in place if it is stored, through a temp copy (deleted again) if it is compressed. */
    private fun <T> withInspectSource(zip: ZipFile, e: ZipArchiveEntry, block: (RandomSource) -> T): T {
        val ch = zipChannel
        if (e.method == ZipArchiveEntry.STORED && ch != null) {
            runCatching { zip.getRawInputStream(e).close() } // works out dataOffset
            if (e.dataOffset > 0) return block(ChannelSource(ch, e.dataOffset, e.size))
        }
        val t = spillEntry(zip, e)
        try {
            return FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> block(ChannelSource(c)) }
        } finally { t.delete() }
    }

    private fun zipReader(zip: ZipFile, byName: Map<String, ZipArchiveEntry>): MemberReader = object : MemberReader {
        override fun head(m: Member, n: Int): ByteArray = zipEntryHead(zip, byName.getValue(m.key), n)
        override fun <T> source(m: Member, block: (RandomSource) -> T): T = withInspectSource(zip, byName.getValue(m.key), block)
        override fun <T> stream(m: Member, block: (InputStream) -> T): T = zip.getInputStream(byName.getValue(m.key)).use(block)
    }

    /** The plan of a zip. */
    private fun planZip(zip: ZipFile, entries: List<ZipArchiveEntry>, wrap: String, sysRoot: String?, rawDump: Boolean): ArchivePlan? {
        val members = ArrayList<Member>()
        entries.forEachIndexed { i, e ->
            if (e.isDirectory || e.isUnixSymlink) return@forEachIndexed
            members.add(Member(e.name, e.name.replace('\\', '/').removePrefix(wrap), e.size, i))
        }
        return planPartitionChoices(members, zipReader(zip, entries.associateBy { it.name }), sysRoot, rawDump)
    }

    /**
     * The plan of a 7z. The archive is read through a reader of its own, which is closed again before the import's reader
     * is opened: a 7z reads forward only, and a member read out of turn would throw that one off.
     */
    private fun planSevenZ(ch: FileChannel): ArchivePlan? {
        val members = ArrayList<Member>()
        val names = ArrayList<String>()
        ch.position(0)
        SevenZFile.builder().setSeekableByteChannel(NonClosingChannel(ch)).get().use { pz ->
            var i = 0
            for (e in pz.entries) {
                val idx = i++
                val n = e.name.replace('\\', '/').trimStart('/')
                names.add(n)
                if (e.isDirectory) continue
                members.add(Member(n, n, e.size, idx))
            }
        }
        val reader = SevenZMemberReader(ch)
        try {
            return planPartitionChoices(members, reader, findSystemRoot(names), false)
        } finally { reader.close() }
    }

    /**
     * Reads members of a 7z the only way a (solid) 7z can be read reliably: forward, with [SevenZFile.getNextEntry] and
     * [SevenZFile.read]. Asking commons-compress for an entry's own stream and leaving it half read (the first 4 KB are enough to
     * tell an image's kind) makes the next entry of the same solid block start at the wrong byte, so every later look at an
     * image saw garbage and the choice between system_a.img and system_b.img was dropped. A member before the current one reopens
     * the archive. What [head] took off the front of a member is handed on by the [source] / [stream] that follows it, so
     * an image is not decoded twice for the one look.
     */
    private inner class SevenZMemberReader(private val ch: FileChannel) : MemberReader, AutoCloseable {
        private var z: SevenZFile? = null
        /** Index of the entry the reader stands in (-1: none yet) and how many of its bytes are still unread. */
        private var at = -1
        private var left = 0L
        /** The bytes [head] took off the front of entry [at], while nobody has read behind them. */
        private var headBytes: ByteArray? = null

        private val body = object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (cancelled) throw IOException("cancelled")
                if (left <= 0) return -1
                val r = z!!.read(b, off, minOf(len.toLong(), left).toInt())
                if (r > 0) left -= r else if (r < 0) left = 0
                return r
            }
        }

        private fun enter(m: Member) {
            if (at == m.index && headBytes != null) return
            var f = z
            if (f == null || m.index <= at) {
                f?.close()
                ch.position(0)
                f = SevenZFile.builder().setSeekableByteChannel(NonClosingChannel(ch)).get()
                z = f; at = -1; left = 0; headBytes = null
            }
            val sink = ByteArray(1 shl 16)
            while (at < m.index) {
                while (left > 0) if (body.read(sink, 0, sink.size) < 0) break // what is unread of the entry we leave
                val e = f!!.nextEntry ?: throw IOException("7z entry ${m.name} not found")
                at++
                left = if (e.isDirectory) 0 else e.size
                headBytes = null
            }
        }

        /** The entry's bytes from its start: what [head] already took, then the rest. */
        private fun whole(): InputStream {
            val front = headBytes ?: ByteArray(0)
            headBytes = null
            return java.io.SequenceInputStream(java.io.ByteArrayInputStream(front), body)
        }

        override fun head(m: Member, n: Int): ByteArray {
            enter(m)
            headBytes?.let { return it.copyOf(minOf(n, it.size)) }
            return readUpTo(body, n).also { headBytes = it }
        }

        override fun <T> source(m: Member, block: (RandomSource) -> T): T {
            enter(m)
            return withSpilledSource(whole(), m.name.substringAfterLast('/'), block)
        }

        override fun <T> stream(m: Member, block: (InputStream) -> T): T {
            enter(m)
            return block(whole())
        }

        override fun close() { z?.close(); z = null }
    }

    /** Takes the first bytes of what a rar entry unpacks to, then stops the unpacking. */
    private class HeadSink(private val limit: Int) : java.io.OutputStream() {
        val buf = java.io.ByteArrayOutputStream()
        override fun write(b: Int) { buf.write(b); if (buf.size() >= limit) throw FirstEntry() }
        override fun write(b: ByteArray, off: Int, len: Int) {
            buf.write(b, off, minOf(len, limit - buf.size()))
            if (buf.size() >= limit) throw FirstEntry()
        }
    }

    /** The plan of a rar, read through a second [RarArchive] of the file (a solid archive keeps decoder state per reader). */
    private fun planRar(file: File): ArchivePlan? {
        RarArchive(file).use { rar ->
            val headers = rar.fileHeaders
            val byName = LinkedHashMap<String, RarFileHeader>()
            val members = ArrayList<Member>()
            val names = ArrayList<String>()
            headers.forEachIndexed { i, h ->
                val n = (h.fileName ?: "").replace('\\', '/').trimStart('/')
                names.add(n)
                if (h.isDirectory || n.isBlank() || n.split('/').any { it == ".." }) return@forEachIndexed
                byName[n] = h
                members.add(Member(n, n, h.fullUnpackSize, i))
            }
            fun extractTo(m: Member): File {
                val dir = File(tmp, "rarplan-${System.nanoTime()}").apply { mkdirs() }
                val f = File(dir, m.name.substringAfterLast('/'))
                f.outputStream().buffered(1 shl 20).use { out -> rar.extractFile(byName.getValue(m.key), out) }
                return f
            }
            val reader = object : MemberReader {
                override fun head(m: Member, n: Int): ByteArray {
                    val sink = HeadSink(n)
                    try { rar.extractFile(byName.getValue(m.key), sink) } catch (t: Throwable) { if (cancelled) throw IOException("cancelled") }
                    return sink.buf.toByteArray()
                }
                override fun <T> source(m: Member, block: (RandomSource) -> T): T {
                    val f = extractTo(m)
                    try { return FileChannel.open(f.toPath(), StandardOpenOption.READ).use { c -> block(ChannelSource(c)) } }
                    finally { deleteTree(f.parentFile ?: f) }
                }
                override fun <T> stream(m: Member, block: (InputStream) -> T): T {
                    val f = extractTo(m)
                    try { return f.inputStream().use(block) } finally { deleteTree(f.parentFile ?: f) }
                }
            }
            return planPartitionChoices(members, reader, findSystemRoot(names), false)
        }
    }

    /**
     * The plan of a tar or a cpio: [open] opens it from its first byte. One pass lists the members; the images are then read
     * in place when [under] is the tar as a random-access source, otherwise through the archive read again (or a temp copy).
     * What such an archive holds under system/ at its top level is its system folder.
     */
    private fun planStreamArchive(open: () -> org.apache.commons.compress.archivers.ArchiveInputStream<*>, under: RandomSource?): ArchivePlan? {
        val members = ArrayList<Member>()
        open().use { a ->
            var i = 0
            while (true) {
                if (cancelled) throw IOException("cancelled")
                val e = a.nextEntry ?: break
                val idx = i++
                val regular = when (e) {
                    is TarArchiveEntry -> e.isFile && !e.isSymbolicLink && !e.isLink
                    is CpioArchiveEntry -> e.isRegularFile
                    else -> !e.isDirectory
                }
                if (!regular) continue
                val n = e.name.removePrefix("./").trimStart('/')
                if (n.isEmpty() || n == "TRAILER!!!") continue
                members.add(Member(n, n, e.size, idx, if (under != null) a.bytesRead else -1L))
            }
        }
        // cheap exit before anything is inspected: nothing in the list can be doubled
        val sysRoot = if (members.any { systemRel("", it.name) != null }) "" else null
        val reader = object : MemberReader {
            override fun head(m: Member, n: Int): ByteArray = stream(m) { readUpTo(it, n) }
            override fun <T> source(m: Member, block: (RandomSource) -> T): T =
                if (under != null && m.offset >= 0) block(ExtentSource(under, listOf(m.offset to m.size)))
                else stream(m) { s -> withSpilledSource(s, m.name.substringAfterLast('/'), block) }
            override fun <T> stream(m: Member, block: (InputStream) -> T): T {
                if (under != null && m.offset >= 0) return block(streamOf(ExtentSource(under, listOf(m.offset to m.size))))
                return open().use { a ->
                    var i = 0
                    while (true) {
                        a.nextEntry ?: throw IOException("archive changed while it was read")
                        if (i++ == m.index) break
                    }
                    block(a.nonClosing())
                }
            }
        }
        return planPartitionChoices(members, reader, sysRoot, false, lz4Names = true)
    }

    /** Partition image in the archive: empty if it has no bytes, is all zeros, or holds a filesystem without files. */
    private fun inspectImageMember(reader: MemberReader, m: Member, group: String): Inspected {
        val head = try { reader.head(m, 4096) } catch (t: Throwable) {
            if (cancelled) throw IOException("cancelled")
            return Inspected(false, null) // unreadable: the person can still pick it, the import then says what is wrong
        }
        if (m.size == 0L || head.isEmpty() || PartitionChoice.isBlank(head, head.size)) return Inspected(true, null)
        // boot and recovery have no build.prop; what the importer ignores as a stub (below 4 KB) is no partition either
        if (group == "boot" || group == "recovery") return Inspected(false, null)
        if (group != "system" && m.size in 0L..4096L) return Inspected(true, null)
        // an lz4-packed image is listed by name: unpacking it here would cost as much as the import
        if (m.name.endsWith(".lz4", true)) return Inspected(false, null)
        return try { reader.source(m) { inspectFilesystem(it) } } catch (t: Throwable) {
            if (cancelled) throw IOException("cancelled")
            Inspected(false, null)
        }
    }

    private fun inspectFilesystem(source: RandomSource): Inspected = try {
        val src = MotoImage.unwrap(if (SparseSource.probe(source)) SparseSource(listOf(source)) else source)
        val first = ByteBuffer.allocate(minOf(65536L, src.size).toInt())
        src.read(0, first)
        when {
            PartitionChoice.isBlank(first.array(), first.capacity()) -> Inspected(true, null)
            Ext4Reader.probe(src) -> {
                val fs = Ext4Reader(src)
                if (fs.list(fs.inode(2)).none { it.name != "lost+found" }) Inspected(true, null)
                else Inspected(false, readBuildProp { path ->
                    fs.lookup(path)?.takeIf { it.isFile && it.size in 1..PartitionChoice.MAX_PROP_BYTES.toLong() }
                        ?.let { String(fs.readAll(it), Charsets.UTF_8) }
                })
            }
            ErofsReader.probe(src) -> {
                val fs = ErofsReader(src) { if (cancelled) throw IOException("cancelled") }
                val props = readBuildProp { path ->
                    fs.lookup(path)?.takeIf { it.type == 1 && it.size in 1..PartitionChoice.MAX_PROP_BYTES.toLong() }?.let {
                        val out = java.io.ByteArrayOutputStream(it.size.toInt())
                        fs.copy(it, out)
                        String(out.toByteArray(), Charsets.UTF_8)
                    }
                }
                if (props != null) Inspected(false, props)
                else {
                    var any = false
                    try { fs.walk { _, _ -> any = true; throw FirstEntry() } } catch (_: FirstEntry) {}
                    Inspected(!any, null)
                }
            }
            // YAFFS2, UBIFS, SquashFS, RFS ...: not opened here, listed by name
            else -> Inspected(false, null)
        }
    } catch (t: Throwable) {
        if (cancelled) throw IOException("cancelled")
        Inspected(false, null) // unreadable: the person can still pick it, the import then says what is wrong
    }

    /** The first build.prop [read] can find at the usual places of a partition, parsed. */
    private fun readBuildProp(read: (String) -> String?): Map<String, String>? {
        for (path in PartitionChoice.BUILD_PROP_PATHS) {
            val text = runCatching { read(path) }.getOrNull() ?: continue
            return PropArea.parseProps(text)
        }
        return null
    }

    /** system.tar, vendor.tar and the like: read as a stream up to its build.prop (or to the end, when it has none). */
    private fun inspectTarMember(reader: MemberReader, m: Member, group: String): Inspected {
        if (m.size == 0L) return Inspected(true, null)
        val wanted = setOf("${PartitionChoice.mountOf(group)}/build.prop", "$group/build.prop", "$group/etc/build.prop", "build.prop", "system/system/build.prop")
        return try {
            reader.stream(m) { raw ->
                val lower = m.name.lowercase()
                val s: InputStream = if (lower.endsWith(".gz") || lower.endsWith(".tgz")) GZIPInputStream(raw, 1 shl 16) else raw
                val tar = TarArchiveInputStream(s, "UTF-8")
                var any = false
                var props: Map<String, String>? = null
                while (props == null) {
                    if (cancelled) throw IOException("cancelled")
                    val te: TarArchiveEntry = tar.nextEntry ?: break
                    if (!te.isFile) continue
                    if (te.size > 0) any = true
                    val tn = te.name.removePrefix("./").trimStart('/')
                    if (tn in wanted && te.size in 1..PartitionChoice.MAX_PROP_BYTES.toLong())
                        props = PropArea.parseProps(String(tar.readBytes(), Charsets.UTF_8))
                }
                Inspected(!any, props)
            }
        } catch (t: Throwable) {
            if (cancelled) throw IOException("cancelled")
            Inspected(false, null)
        }
    }

    /** The system/ folder of the archive: it always has files, its build.prop (if any) gives the identity. */
    private fun inspectSystemFolder(reader: MemberReader, folderFiles: List<Member>, sysRoot: String): Inspected {
        val prop = folderFiles.firstOrNull { systemRel(sysRoot, it.name) == "system/build.prop" }
        val props = prop?.takeIf { it.size in 1..PartitionChoice.MAX_PROP_BYTES.toLong() }?.let {
            runCatching { PropArea.parseProps(String(reader.stream(it) { s -> s.readBytes() }, Charsets.UTF_8)) }.getOrNull()
        }
        return Inspected(folderFiles.isEmpty(), props)
    }

    /**
     * A tar (or tar.gz) entry of a zip. A stored one is read in place, so that the choice between several images and the
     * import itself both work on the bytes of the zip; a compressed one is streamed (and opened a second time for the choice).
     */
    private fun importZipTar(zip: ZipFile, e: ZipArchiveEntry, base: String, depth: Int) {
        val gz = base.endsWith("gz", true)
        val ch = zipChannel
        if (!gz && e.method == ZipArchiveEntry.STORED && ch != null) {
            runCatching { zip.getRawInputStream(e).close() } // works out dataOffset
            if (e.dataOffset > 0) {
                val src = ChannelSource(ch, e.dataOffset, e.size)
                importTarStream(streamOf(src), base, depth + 1, src)
                return
            }
        }
        zip.getInputStream(e).use { s ->
            if (gz) importCompressed(GZIPInputStream(s, 1 shl 16), base, depth + 1)
            else importTarStream(s, base, depth + 1, reopen = { zip.getInputStream(e) })
        }
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
            for (label in listOf("system", "boot", "recovery", "oem")) {
                if (cancelled) throw IOException("cancelled")
                if (label == "system" && gotSystem) continue
                if (label == "boot" && ramdisk != null) continue
                if (label == "recovery" && recovery != null) continue
                if (label == "oem" && gotOem) continue
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
                        onProgress(lt(R.string.imp_edl, base), -1f)
                        zip.getInputStream(pick).use { i -> File(chunks, base).outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                    }
                    FirmwareToolset.combineEdl(plan, chunks, raw) { msg -> log(msg) }
                    deleteTree(chunks)
                    FileChannel.open(raw.toPath(), StandardOpenOption.READ).use { c ->
                        val src = ChannelSource(c)
                        if (label == "system") importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                        else if (label == "oem") importOem(src)
                        else if (raw.length() <= 128L shl 20) {
                            if (label == "recovery") takeRecovery(raw.inputStream(), "recovery.img") else takeBoot(raw.readBytes())
                        }
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
        // handle() probed the header through the shared channel, which left it at that offset; SevenZFile reads
        // its signature from the current position, hence "Bad 7z signature" on a perfectly good archive
        // several images of one partition: the person picks one (read through a reader of its own, closed before the next one opens)
        val plan = planSafely { planSevenZ(ch) }
        ch.position(0)
        SevenZFile.builder().setSeekableByteChannel(ch).get().use { z ->
            val foundSysRoot = findSystemRoot(z.entries.map { it.name.replace('\\', '/').trimStart('/') })
            // the system folder is ignored when an image or a tar was picked for system instead
            val sysRoot = if (plan?.dropSystemFolder == true) null else foundSysRoot
            val nested = ArrayList<Pair<File, String>>()
            val vdat = LinkedHashMap<String, MutableMap<String, File>>()
            // split sparse system / vendor: an entry is readable once, so chunks are spilled and sorted after the pass
            val splitParts = LinkedHashMap<String, MutableList<Pair<String, File>>>()
            while (true) {
                if (cancelled) throw IOException("cancelled")
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val n = e.name.replace('\\', '/').trimStart('/')
                // a picked "system_b.img" / "boot_b.img" is imported under the plain name, which the matchers below know
                val base = plan?.canonical?.get(n) ?: n.substringAfterLast('/')
                val bare = PartitionNames.bareOk(n, e.size)
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
                    // an image, tar or folder the person did not pick for its partition
                    plan != null && n in plan.skip -> drain(entryStream)
                    // Sony .sin / .ftf: handled by name once the archive is read (see importSin)
                    isSonyName(base) && e.size > 0 -> nested.add(spill(entryStream, base) to "archive")
                    systemRel(sysRoot, n) != null -> {
                        writeFile(systemRel(sysRoot, n)!!, entryStream, null)
                        gotSystem = true
                    }
                    base.endsWith(".yaffs2.img", true) && !base.equals("system.yaffs2.img", true) -> drain(entryStream)
                    e.size > 4096 && listOf("system", "vendor").any { SparseChunks.indexOf(n, it) != null } -> {
                        val part = if (SparseChunks.indexOf(n, "system") != null) "system" else "vendor"
                        splitParts.getOrPut(part) { ArrayList() }.add(n to spill(entryStream, base))
                    }
                    isBootName(base, bare) -> takeBoot(entryStream.readBytes())
                    isRecoveryName(base, bare) && e.size < 64_000_000 -> takeRecovery(entryStream, base)
                    CscPackage.isCscContainer(base) && e.size > 0 -> takeCsc(entryStream, base)
                    isOemName(base, bare) -> nested.add(spill(entryStream, base) to "oem")
                    isVendorName(base, bare) -> nested.add(spill(entryStream, base) to "vendor")
                    isExtraName(base, bare) && e.size > 4096 -> nested.add(spill(entryStream, base) to "extra")
                    base.equals("system.yaffs2.img", true) ||
                        isSystemImageName(base, bare) -> {
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
                    // raw NAND partition dump: classified by content once the archive is read (see importMtdDump)
                    NandDump.isMtdName(base) && e.size > 0 -> nested.add(spill(entryStream, base) to "mtd")
                    else -> if (e.size < (4L shl 20) || !importGptStream(entryStream, base)) drain(entryStream)
                }
                onProgress(lt(R.string.imp_extracting, base), 0.5f)
            }
            for ((part, files) in splitParts) {
                val byPath = files.toMap()
                val chosen = SparseChunks.select(byPath.keys, part).mapNotNull { byPath[it] }
                (files.map { it.second } - chosen.toSet()).forEach { it.delete() }
                if (chosen.isEmpty() || (part == "system" && gotSystem) || (part == "vendor" && gotVendor)) chosen.forEach { it.delete() }
                else importSplitSparse(chosen, part)
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
            for ((f, kind) in nested.sortedBy { nestedRank(it.first.name) }) {
                try {
                    FileChannel.open(f.toPath(), StandardOpenOption.READ).use { c ->
                        val src = ChannelSource(c)
                        if (kind == "system") importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                        else if (kind == "oem") importOem(src)
                        else if (kind == "vendor") importVendor(src)
                        else if (kind == "extra") importExtra(src, f.name)
                        else handle(src, c, f.name, depth + 1)
                    }
                } finally { f.delete() }
            }
            if (!gotSystem && nested.isEmpty()) log("no system found in archive $name")
        }
    }

    /**
     * ISO 9660 image of Android-x86 (live CD / installer, also Bliss OS, PrimeOS ...). The ISO is only a container:
     *  - `ramdisk.img` is the Android root file system (gzip'ed cpio with init, init*.rc, sbin/ ...): it becomes the
     *    VM's ramdisk, like the ramdisk of a boot.img;
     *  - `system.sfs` is a SquashFS that holds one file, `system.img` (an ext4 image): that image is cut out of it
     *    and imported as the system partition. A plain `system.img` in the ISO is imported directly;
     *  - `kernel` (a bzImage the guest never runs: the translator runs the firmware's programs), `initrd.img` (the live
     *    boot script) and `install.img` (the installer) are not needed.
     * Files of a folder (an `android-x.y/` directory on the CD) are found too; the one nearest to the root wins.
     */
    private fun importIso(src: RandomSource, name: String, depth: Int) {
        val iso = Iso9660(src)
        val files = iso.entries(checkCancelled = { if (cancelled) throw IOException("cancelled") }).filter { !it.isDir }
        fun find(vararg names: String) = files.filter { f -> names.any { f.name.equals(it, true) } }
            .minByOrNull { it.path.count { c -> c == '/' } }
        val sfs = find("system.sfs")
        val plain = find("system.img")
        if (sfs == null && plain == null) {
            throw LocalizedIOException("no Android system found in the ISO image (expected system.sfs or system.img)", R.string.imp_iso_no_system)
        }
        log("iso: ${files.size} files; system from ${(sfs ?: plain)!!.path}")
        // the root file system first: it is small, and tells at once if the ISO is not an Android one after all
        find("ramdisk.img")?.takeIf { ramdisk == null }?.let { rd ->
            if (rd.size > MAX_RAMDISK) log("iso: ${rd.path} is too large for a ramdisk, skipped")
            else {
                val list = runCatching { BootImage.decompress(readAllFrom(iso.open(rd)))?.let { BootImage.cpio(it) } }.getOrNull()
                if (!list.isNullOrEmpty() && list.any { it.name == "init.rc" }) { ramdisk = list; log("boot: ramdisk ${rd.path}, ${list.size} files") }
                else log("iso: ${rd.path} is not a ramdisk with init.rc, skipped")
            }
        }
        if (sfs == null) {
            val s = iso.open(plain!!)
            onProgress(lt(R.string.imp_reading_image, "system", "ISO 9660"), -1f)
            importImage(if (SparseSource.probe(s)) SparseSource(listOf(s)) else s, "system")
            return
        }
        // SquashFS needs a file. It is deleted as soon as system.img is out of it: both at once would double the disk peak.
        onProgress(lt(R.string.imp_reading_image, "system", "SquashFS"), -1f)
        val sfsFile = spill(streamOf(iso.open(sfs)), "system.sfs")
        val dir = sfsFile.parentFile ?: sfsFile
        try {
            var image: File? = null
            FirmwareToolset.walkSquashFs(sfsFile, { log(it) }, object : FirmwareToolset.SquashVisitor {
                override fun dir(path: String, mode: Int) {}
                override fun link(path: String, target: String) {}
                override fun file(path: String, mode: Int, size: Long): java.io.OutputStream? {
                    if (cancelled) throw IOException("cancelled")
                    if (image != null || !path.substringAfterLast('/').equals("system.img", true)) return null
                    if (tmp.usableSpace < size + 64L * 1024 * 1024)
                        throw LocalizedIOException("Not enough space to unpack firmware", R.string.imp_not_enough_space_to_unpack_firmware)
                    return File(dir, "system.img").also { image = it }.outputStream().buffered(1 shl 20)
                }
                override fun fileEnd(path: String) {}
            })
            val img = image
            if (img == null) {
                // no system.img inside: the SquashFS is the /system tree itself
                log("iso: system.sfs holds no system.img, read as a file tree")
                FileChannel.open(sfsFile.toPath(), StandardOpenOption.READ).use { c -> importImage(ChannelSource(c), "system") }
                return
            }
            sfsFile.delete()
            log("iso: system.img, ${img.length() shr 20} MB, from system.sfs")
            FileChannel.open(img.toPath(), StandardOpenOption.READ).use { c ->
                val s = ChannelSource(c)
                importImage(if (SparseSource.probe(s)) SparseSource(listOf(s)) else s, "system")
            }
        } finally { deleteTree(dir) }
    }

    /**
     * One partition of a raw NAND dump (mtd0.img, mtd1.img ...). The name says nothing about what it holds, so the
     * content decides:
     *  - erased flash (all 0xff) is an unused partition and is skipped;
     *  - a YAFFS2 volume with build.prop at its root is the system partition;
     *  - a kernel with a ramdisk is the boot partition, or the recovery when the ramdisk carries /sbin/recovery
     *    (dumps that keep the spare area behind each page are read without it, see [NandDump]);
     *  - anything else (bootloader, calibration data, logos ...) is not needed and is skipped.
     */
    private fun importMtdDump(src: RandomSource, name: String) {
        val base = name.replace('\\', '/').substringAfterLast('/')
        if (src.size < 4096) { log("$base: too small to be a partition, skipped"); return }
        if (NandDump.isBlank(src)) { log("$base: erased flash, skipped"); return }
        if (Yaffs2Reader.probe(src) && yaffs2HoldsSystem(src)) {
            log("$base: YAFFS2 system partition")
            importImage(src, "system")
            return
        }
        if (src.size <= NandDump.MAX_BOOT_BYTES) {
            val raw = readAllFrom(src)
            for (image in NandDump.candidates(raw)) {
                val rd = runCatching { BootImage.ramdisk(image) }.getOrNull()
                if (rd.isNullOrEmpty() || rd.none { e -> e.name.removePrefix("./").let { it == "init.rc" || it == "sbin/recovery" } }) continue
                if (hasRecoveryBinary(image)) takeRecovery(image.inputStream(), "recovery ($base)") else takeBoot(image)
                return
            }
        }
        log("$base: not a boot, recovery or system partition, skipped")
    }

    /** True if the YAFFS2 volume has /build.prop at its root: a raw /system partition. */
    private fun yaffs2HoldsSystem(src: RandomSource): Boolean = try {
        var found = false
        Yaffs2Reader(src) { if (cancelled) throw IOException("cancelled") }.walk { path, node ->
            if (path == "build.prop" && node.type == 1) found = true
        }
        found
    } catch (t: Throwable) {
        if (cancelled) throw t
        false
    }

    /**
     * Android Things /gapps (Google Mobile Services, mounted nofail): /system/priv-app/PrebuiltGmsCoreThings and friends
     * are symlinks into it. Optional like /oem: a failure is logged and the partition skipped.
     */
    private fun importGapps(src: RandomSource) {
        val dir = File(root, "gapps")
        val existed = dir.exists()
        try {
            importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "gapps")
        } catch (t: Throwable) {
            if (cancelled) throw t
            log("gapps: skipped, cannot read the partition (${t.message ?: t.javaClass.simpleName})")
            symlinks.removeAll { it.second.startsWith("/gapps/") }
            if (!existed) deleteTree(dir)
        }
    }

    private fun applyGptPart(kind: GptDisk.Kind, src: RandomSource) {
        when (kind) {
            GptDisk.Kind.BOOT -> takeBoot(readAllFrom(src))
            GptDisk.Kind.VENDOR_BOOT -> takeVendorBoot(src)
            GptDisk.Kind.RECOVERY -> takeRecovery(streamOf(src), "recovery.img")
            GptDisk.Kind.OEM -> importOem(src)
            GptDisk.Kind.GAPPS -> importGapps(src)
            GptDisk.Kind.VENDOR -> importVendor(src)
            GptDisk.Kind.SYSTEM -> {
                importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                gotSystem = true
            }
            GptDisk.Kind.SUPER -> importSuper(src)
        }
    }

    /** True when the archive being read carries its own vendor image, so the one inside "super" is not read twice. */
    private var archiveHasVendorImage = false

    /**
     * A "super" partition (dynamic partitions, Android 10+) read in place: its logical partitions are cut out by their
     * extents and imported like the separate images of an older package (system, vendor, product, system_ext, odm).
     */
    private fun importSuper(src: RandomSource) {
        val first = ByteBuffer.allocate(LP_PROBE).also { src.read(0, it) }.array()
        val need = LpMetadata.headSize(first) ?: throw LocalizedIOException("super: unreadable partition metadata", R.string.imp_unreadable_gpt, "super")
        val head = if (need <= first.size) first else ByteBuffer.allocate(need).also { src.read(0, it) }.array()
        val parts = LpMetadata.parse(head) ?: throw LocalizedIOException("super: unreadable partition metadata", R.string.imp_unreadable_gpt, "super")
        val picks = LpMetadata.pick(parts)
        if (picks.isEmpty()) { log("super: no system partition among ${parts.joinToString { it.name }}"); return }
        log("super: " + picks.joinToString { "${it.name} ${it.size shr 20} MB" })
        for (p in picks) {
            if (cancelled) throw IOException("cancelled")
            applyLogical(LpMetadata.baseName(p.name), ExtentSource(src, p.extents.map { it.superOffset to it.length }))
        }
    }

    /** One logical partition of a super image, by its name without the slot suffix. */
    private fun applyLogical(name: String, src: RandomSource) {
        when (name) {
            "system" -> { importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system"); gotSystem = true }
            "vendor" -> if (archiveHasVendorImage) log("super: vendor skipped, the package has a vendor image of its own") else importVendor(src)
            else -> importExtra(src, "$name.img")
        }
    }

    /** Random-access full dump (plain .img, nested file): partitions are used in place, nothing is copied. */
    private fun importGptDisk(src: RandomSource, name: String) {
        val first = ByteBuffer.allocate(8192)
        src.read(0, first)
        val fb = first.array()
        val need = GptDisk.headSize(fb, fb.size) ?: throw LocalizedIOException("$name: unreadable GPT", R.string.imp_unreadable_gpt, name)
        val head = ByteBuffer.allocate(need).also { src.read(0, it) }.array()
        val picks = GptDisk.pick(GptDisk.parse(head) ?: throw LocalizedIOException("$name: unreadable GPT", R.string.imp_unreadable_gpt, name))
        if (picks.isEmpty()) throw LocalizedIOException("$name: no boot/recovery/system partition in GPT", R.string.imp_no_boot_recovery_system_partition_in, name)
        log("$name: full dump, partitions " + picks.joinToString { "${it.second.name} ${it.second.size shr 20} MB" })
        for ((kind, p) in picks) {
            if (cancelled) throw IOException("cancelled")
            applyGptPart(kind, SliceSource(src, p.start, minOf(p.size, src.size - p.start)))
        }
    }

    /**
     * Sequential full dump (a 7z entry cannot be seeked): only the wanted partitions are written to temp files
     * and decompression stops after the last one, so a 4 GB dump is neither stored nor unpacked completely.
     * Returns false (after consuming only the first bytes) if the stream is not a GPT disk.
     */
    private fun importGptStream(input: InputStream, label: String): Boolean {
        val first = ByteArray(8192)
        val n = GptDisk.readFully(input, first, 0, first.size)
        val need = GptDisk.headSize(first, n) ?: return false
        var head = first.copyOf(n)
        if (need > n) {
            val big = first.copyOf(need)
            val got = GptDisk.readFully(input, big, n, need - n)
            if (n + got < need) return false
            head = big
        }
        val picks = GptDisk.pick(GptDisk.parse(head) ?: return false)
        if (picks.isEmpty()) { log("$label: GPT disk without boot/recovery/system partitions"); return true }
        log("$label: full dump, partitions " + picks.joinToString { "${it.second.name} ${it.second.size shr 20} MB" })
        // What is written out: whole GPT partitions, and for a "super" partition its logical partitions (read from the
        // metadata at the start of the stream, so only the wanted runs are kept, not the whole 4 GB container).
        class Out(val kind: GptDisk.Kind, val logical: String?, val runs: List<LpMetadata.Extent>, val file: File)
        // a run of the disk: absolute start, length, offset inside the output file
        class Run(val start: Long, val length: Long, val fileOff: Long, val out: Int)
        val dir = File(tmp, "gpt-${System.nanoTime()}").apply { mkdirs() }
        try {
            val outs = ArrayList<Out>()
            val runs = ArrayList<Run>()
            for ((k, p) in picks) {
                if (k != GptDisk.Kind.SUPER) {
                    outs.add(Out(k, null, listOf(LpMetadata.Extent(p.start, p.size)), File(dir, "${k.name.lowercase()}-${outs.size}.img")))
                    continue
                }
                // the metadata sits in the first megabyte of the partition; it may be further than what was read for the GPT head
                val lpNeed = p.start + LP_PROBE + (4 shl 20)
                if (head.size < lpNeed) {
                    if (lpNeed > (64L shl 20)) return false
                    val big = head.copyOf(lpNeed.toInt())
                    val got = GptDisk.readFully(input, big, head.size, big.size - head.size)
                    if (head.size + got < big.size) return false
                    head = big
                }
                val sup = head.copyOfRange(p.start.toInt(), p.start.toInt() + LP_PROBE)
                val lpHead = LpMetadata.headSize(sup)?.let { n -> if (p.start + n <= head.size) head.copyOfRange(p.start.toInt(), p.start.toInt() + n) else null }
                val lp = lpHead?.let { LpMetadata.parse(it) }
                if (lp == null) { log("$label: super partition without readable metadata"); continue }
                val chosen = LpMetadata.pick(lp)
                log("$label: super holds " + lp.joinToString { "${it.name} ${it.size shr 20} MB" })
                for (lg in chosen) {
                    val base = LpMetadata.baseName(lg.name)
                    if (base == "vendor" && archiveHasVendorImage) { log("super: vendor skipped, the package has a vendor image of its own"); continue }
                    val abs = lg.extents.map { LpMetadata.Extent(p.start + it.superOffset, it.length) }
                    // a logical partition outside the super partition is not believed
                    if (abs.any { it.superOffset + it.length > p.start + p.size }) { log("super: $base extends past the partition, skipped"); continue }
                    outs.add(Out(GptDisk.Kind.SUPER, base, abs, File(dir, "super-$base.img")))
                }
            }
            if (outs.isEmpty()) { log("$label: nothing to import in the GPT disk"); return true }
            for ((i, o) in outs.withIndex()) {
                var off = 0L
                for (r in o.runs) { runs.add(Run(r.superOffset, r.length, off, i)); off += r.length }
            }
            runs.sortBy { it.start }
            val chans = outs.map { FileChannel.open(it.file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ) }
            try {
                val end = runs.maxOf { it.start + it.length }
                var pos = 0L
                fun feed(buf: ByteArray, len: Int) {
                    for (r in runs) {
                        val a = maxOf(r.start, pos)
                        val bb = minOf(r.start + r.length, pos + len)
                        if (bb > a) {
                            val w = ByteBuffer.wrap(buf, (a - pos).toInt(), (bb - a).toInt())
                            var at = r.fileOff + (a - r.start)
                            while (w.hasRemaining()) at += chans[r.out].write(w, at)
                        }
                    }
                    pos += len
                }
                // the head is already read: the bytes of it that belong to an output are written first
                feed(head, head.size)
                val buf = ByteArray(1 shl 20)
                var tick = 0
                while (pos < end) {
                    if (cancelled) throw IOException("cancelled")
                    if (tmp.usableSpace < (64L shl 20)) throw LocalizedIOException("Not enough space to unpack firmware", R.string.imp_not_enough_space_to_unpack_firmware)
                    val r = input.read(buf, 0, minOf(buf.size.toLong(), end - pos).toInt())
                    if (r < 0) break
                    if (r > 0) feed(buf, r)
                    if (++tick % 16 == 0) onProgress(lt(R.string.imp_reading, label), (pos.toFloat() / end).coerceIn(0f, 1f) * 0.5f)
                }
            } finally { chans.forEach { runCatching { it.close() } } }
            for (o in outs) {
                if (cancelled) throw IOException("cancelled")
                FileChannel.open(o.file.toPath(), StandardOpenOption.READ).use { c ->
                    val src = ChannelSource(c)
                    if (o.logical != null) applyLogical(o.logical, src) else applyGptPart(o.kind, src)
                }
                o.file.delete()
            }
        } finally { deleteTree(dir) }
        return true
    }

    private fun importVdatPair(data: File, list: File, key: String, depth: Int) {
        val part = key.substringAfterLast('/').lowercase()
        val mount = when { part.isBlank() || part == "system" -> "system"; part == "vendor" || part == "odm" -> part
            part == "product" || part == "system_ext" -> "system/$part"; else -> {
            log("skipping OTA partition \"$part\" (not system/vendor/product/system_ext/odm)"); return } }
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
            keepStockImage = mount == "system"
            FileChannel.open(raw.toPath(), StandardOpenOption.READ).use { c -> importImage(ChannelSource(c), mount) }
        } finally {
            keepStockImage = false
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
        if (depth > 8) throw LocalizedIOException("nested archive depth exceeded", R.string.imp_nested_archive_depth_exceeded)
        // several images of one partition: the person picks one (through a second reader of the file)
        val plan = planSafely { planRar(file) }
        RarArchive(file).use { rar ->
            val foundSysRoot = findSystemRoot(rar.fileHeaders.map { (it.fileName ?: "").replace('\\', '/').trimStart('/') })
            // the system folder is ignored when an image or a tar was picked for system instead
            val sysRoot = if (plan?.dropSystemFolder == true) null else foundSysRoot
            val pending = ArrayList<Pair<File, String>>()
            val vdat = LinkedHashMap<String, MutableMap<String, File>>()
            for (e: RarFileHeader in rar.fileHeaders) {
                if (cancelled) throw IOException("cancelled")
                if (e.isDirectory) continue
                val entryName = (e.fileName ?: "").replace('\\', '/').trimStart('/')
                if (entryName.isBlank() || entryName.split('/').any { it == ".." }) continue
                // an image, tar or folder the person did not pick for its partition
                if (plan != null && entryName in plan.skip) continue
                // a picked "system_b.img" / "boot_b.img" is imported under the plain name, which the matchers below know
                val base = plan?.canonical?.get(entryName) ?: entryName.substringAfterLast('/')
                val sysRel = systemRel(sysRoot, entryName)
                val bare = PartitionNames.bareOk(entryName, Long.MAX_VALUE)
                val interesting = sysRel != null || isSonyName(base) ||
                    isBootName(base, bare) || isRecoveryName(base, bare) || isOemName(base, bare) ||
                    // vendor / product / system_ext / odm images (and their tars, matched below with the other archives): read like in a zip
                    isVendorName(base, bare) || isExtraName(base, bare) ||
                    base.equals("system.yaffs2.img", true) ||
                    isSystemImageName(base, bare) ||
                    base.matches(Regex("(?i).*\\.(zip|rar|7z|tar|tar\\.md5|md5|tgz|tar\\.gz|new\\.dat|new\\.dat\\.br|transfer\\.list)")) ||
                    recognizedFirmwareTool(base)
                if (!interesting) continue
                val dir = File(tmp, "rar-${System.nanoTime()}").apply { mkdirs() }
                val temp = File(dir, base).apply { parentFile?.mkdirs() }
                try {
                    temp.outputStream().buffered(1 shl 20).use { out -> rar.extractFile(e, out) }
                } catch (t: com.github.junrar.exception.RarException) {
                    temp.delete()
                    pending.forEach { it.first.delete() }
                    vdat.values.forEach { slot -> slot.values.forEach { it.delete() } }
                    throw LocalizedIOException("RAR entry '$entryName' failed to unpack (${t.javaClass.simpleName}); " +
                        "the archive may be damaged or the RAR decoder (junrar) is too old", R.string.imp_rar_entry_failed_to_unpack_the, entryName, t.javaClass.simpleName)
                        .also { it.initCause(t) }
                }
                if (sysRel != null) {
                    FileInputStream(temp).use { input -> writeFile(sysRel, input, null) }
                    gotSystem = true
                    temp.delete()
                } else if (isSonyName(base)) {
                    pending.add(temp to "archive")
                } else if (isBootName(base, bare)) {
                    takeBoot(temp.readBytes()); temp.delete()
                } else if (isRecoveryName(base, bare) && temp.length() < 64_000_000) {
                    temp.inputStream().use { takeRecovery(it, base) }; temp.delete()
                } else if (isOemName(base, bare)) {
                    pending.add(temp to "oem")
                } else if (base.equals("system.yaffs2.img", true) ||
                    isSystemImageName(base, bare)) {
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
            for ((temp, kind) in pending.sortedBy { nestedRank(it.first.name) }) {
                try {
                    FileChannel.open(temp.toPath(), StandardOpenOption.READ).use { c ->
                        val src = ChannelSource(c)
                        if (kind == "system") importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                        else if (kind == "oem") importOem(src)
                        else handle(src, c, temp.name, depth + 1)
                    }
                } finally { temp.delete() }
            }
            if (!gotSystem && pending.isEmpty()) log("no system found in RAR archive $name")
        }
    }

    /** Partition tars that fill system/product or system/system_ext come after everything else: the system image must be in place first. */
    private fun nestedRank(name: String): Int =
        PartitionChoice.tarGroup(name.substringAfterLast('/'))?.let { if (PartitionChoice.mountOf(it).startsWith("system/")) 1 else 0 } ?: 0

    /** Sony Flashtool files that can sit inside any archive: a .sin partition image or a whole .ftf (ZIP of .sin) bundle. */
    private fun isSonyName(base: String) = base.endsWith(".sin", true) || base.endsWith(".ftf", true)

    /**
     * A .sin / .ftf found inside an archive, already copied to a temp file under its own name: [handle] routes it
     * (.sin -> [importSin], .ftf -> the ZIP reader, which finds the .sin members). Never deferred, like [withSinSource].
     */
    private fun importSonyFile(f: File, depth: Int) {
        try {
            FileChannel.open(f.toPath(), StandardOpenOption.READ).use { c -> handle(ChannelSource(c), c, f.name, depth + 1) }
        } finally { deleteTree(f.parentFile ?: f) }
    }

    /** Like [withEntrySource], but never deferred: a .sin must be unpacked before the importer knows what it holds. */
    private fun withSinSource(zip: ZipFile, e: ZipArchiveEntry, block: (RandomSource) -> Unit) {
        val ch = zipChannel
        if (e.method == ZipArchiveEntry.STORED && ch != null) {
            runCatching { zip.getRawInputStream(e).close() }
            if (e.dataOffset > 0) { block(ChannelSource(ch, e.dataOffset, e.size)); return }
        }
        val t = spillEntry(zip, e)
        try { FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> block(ChannelSource(c)) } } finally { deleteTree(t.parentFile ?: t) }
    }

    /**
     * Sony .sin (see [SonySin]): unpacked to the plain partition image, which then goes the usual way by partition:
     * system -> guest /system, kernel/boot -> ramdisk (ELF or "ANDROID!"), fotakernel/recovery -> recovery, oem, vendor.
     * Everything else in an .ftf (userdata, cache, modem, loader, TA units ...) is skipped.
     */
    private fun importSin(src: RandomSource, name: String) {
        val base = name.replace('\\', '/').substringAfterLast('/')
        val part = SonySin.partition(base)
        val kind = SonySin.kind(part)
        if (kind == SonySin.Kind.SKIP) { log("FTF: skipped $base ($part)"); return }
        if (kind == SonySin.Kind.SYSTEM && gotSystem) { log("FTF: skipped $base, system already imported"); return }
        onProgress(lt(R.string.imp_unpacking_sony_image, part), -1f)
        val work = File(tmp, "sin-${System.nanoTime()}").apply { mkdirs() }
        try {
            val images = SonySin.unpack(src, work, base, { log(it) }) { cancelled }
            log("FTF: $base -> $part, ${images.sumOf { it.length() } shr 20} MB")
            when (kind) {
                SonySin.Kind.BOOT -> takeBoot(images.first().readBytes())
                SonySin.Kind.RECOVERY -> images.first().inputStream().use { takeRecovery(it, "$part.img") }
                else -> {
                    val chans = images.map { FileChannel.open(it.toPath(), StandardOpenOption.READ) }
                    try {
                        val srcs = chans.map { ChannelSource(it) }
                        val img = when {
                            srcs.all { SparseSource.probe(it) } -> SparseSource(srcs)
                            srcs.size == 1 -> srcs[0]
                            else -> throw LocalizedIOException("$base: ${srcs.size} image parts that are not a sparse set", R.string.imp_image_parts_that_are_not_a, base, srcs.size)
                        }
                        when (kind) {
                            SonySin.Kind.OEM -> importOem(img)
                            SonySin.Kind.VENDOR -> importVendor(img)
                            else -> importImage(img, "system")
                        }
                    } finally { chans.forEach { it.close() } }
                }
            }
        } finally { deleteTree(work) }
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
            // system first (it resets the tree layout), then vendor / oem, then product / system_ext / odm ...:
            // the extras are mounted under system/ and must never be taken for the system image itself
            fun rank(entry: String): Int {
                val b = entry.substringAfterLast('/')
                return when {
                    PartitionNames.extraMount(b, bareOk = true) != null -> 3
                    isOemName(b, bare = true) -> 2
                    isVendorName(b, bare = true) -> 1
                    else -> 0
                }
            }
            for ((f, entry) in mine.sortedBy { rank(it.second) }) {
                if (cancelled) throw IOException("cancelled")
                val entryBase = entry.substringAfterLast('/')
                FileChannel.open(f.toPath(), StandardOpenOption.READ).use { c ->
                    val src = ChannelSource(c)
                    when {
                        PartitionNames.extraMount(entryBase, bareOk = true) != null -> importExtra(src, entryBase)
                        isOemName(entryBase, bare = true) -> importOem(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src)
                        isVendorName(entryBase, bare = true) -> importVendor(src)
                        isSystemImageName(entryBase, bare = true) ->
                            importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                        else -> log("$entryBase: not a partition image the importer uses, skipped")
                    }
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
        try {
            t.outputStream().use { o ->
                val buffer = ByteArray(1 shl 20)
                var count = 0L
                while (true) {
                    if (cancelled) throw IOException("cancelled")
                    val n = i.read(buffer)
                    if (n < 0) break
                    count += n
                    if (count > 16L * 1024 * 1024 * 1024 || tmp.usableSpace < n + 32L * 1024 * 1024)
                        throw LocalizedIOException("Not enough space to unpack firmware", R.string.imp_not_enough_space_to_unpack_firmware)
                    o.write(buffer, 0, n)
                }
            }
            return t
        } catch (toss: Throwable) { deleteTree(dir); throw toss }
    }

    /** CPIO (newc/crc/odc), used by recovery packages and Android TV firmware bundles. */
    private fun importCpioStream(input: InputStream, name: String, depth: Int, reopen: (() -> InputStream)? = null) {
        if (depth > 8) throw LocalizedIOException("nested archive depth exceeded", R.string.imp_nested_archive_depth_exceeded)
        // several images of one partition: the person picks one (the cpio is read once more to list it)
        val plan = if (reopen != null) planSafely { planStreamArchive({ CpioArchiveInputStream(reopen(), "UTF-8") }, null) } else null
        val dropSystem = plan?.dropSystemFolder == true
        CpioArchiveInputStream(input, "UTF-8").use { cpio ->
            while (true) {
                if (cancelled) throw IOException("cancelled")
                val e: CpioArchiveEntry = cpio.nextEntry ?: break
                val n = e.name.removePrefix("./").trimStart('/')
                if (n.isBlank() || n == "TRAILER!!!" || n.split('/').any { it == ".." }) continue
                // an image, tar or folder the person did not pick for its partition
                if (plan != null && n in plan.skip) continue
                if (dropSystem && n.startsWith("system/")) continue
                // a picked "system_b.img" / "boot_b.img" is imported under the plain name, which the matchers below know
                val base = plan?.canonical?.get(n) ?: n.substringAfterLast('/')
                val bare = e.isRegularFile && PartitionNames.bareOk(n, e.size)
                when {
                    e.isDirectory -> FirmwareContainers.destination(root, n).mkdirs()
                    isSonyName(base) && e.isRegularFile && e.size > 0 -> importSonyFile(spill(cpio.nonClosing(), base), depth)
                    n.startsWith("system/") -> {
                        writeFile(n, cpio.nonClosing(), e.mode.toInt())
                        gotSystem = true
                    }
                    isBootName(base, bare) -> takeBoot(cpio.readBytes())
                    isRecoveryName(base, bare) && e.size < 64_000_000 -> takeRecovery(cpio, base)
                    isOemName(base, bare) -> {
                        val t = spill(cpio.nonClosing(), base)
                        try { FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> importOem(ChannelSource(c)) } } finally { t.delete() }
                    }
                    isSystemImageName(base, bare) -> {
                        val t = spill(cpio.nonClosing(), base)
                        try {
                            FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c ->
                                val src = ChannelSource(c)
                                importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                            }
                        } finally { t.delete() }
                    }
                    // vendor / product / system_ext / odm images (a top-level file; a folder of that name is a tree, below)
                    e.isRegularFile && (isVendorName(base, bare) || isExtraName(base, bare) && e.size > 4096) -> {
                        val t = spill(cpio.nonClosing(), base)
                        try {
                            FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c ->
                                if (isVendorName(base, bare)) importVendor(ChannelSource(c)) else importExtra(ChannelSource(c), base)
                            }
                        } finally { t.delete() }
                    }
                    n.matches(Regex("^(data|dev|sbin|vendor|oem|etc)(/.*)?$")) ||
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

    /** [under]: the tar itself as a random-access source, when [s] reads it from its first byte (a tar that is the
     *  file being imported, not one inside a compressed stream or another archive) */
    private fun importTarStream(s: InputStream, name: String, depth: Int, under: RandomSource? = null, reopen: (() -> InputStream)? = null) {
        // vendor.tar, product.tar, system_ext.tar, odm.tar ...: the files of one partition. [partGroup] is that partition when the
        // tar carries files for it (they are filed under its mount); system, boot and recovery tars hold system/ files or images
        // and are read by the rules below.
        val partGroup = PartitionChoice.tarGroup(name.substringAfterLast('/').substringAfterLast('\\'))?.takeIf { PartitionChoice.takesFiles(it) }
        if (partGroup == "vendor") archiveHasVendorImage = true
        // several images of one partition (system_a.img + system_b.img ...): the person picks one. The tar is read once more to list it;
        // that needs a tar that can be opened again (the file itself, or a zip entry), and a partition tar holds only its own files.
        // (a TWRP .win backup is a tar of the files of one partition, never of images)
        val plan = if (partGroup != null || name.endsWith(".win", true)) null else when {
            under != null -> planSafely { planStreamArchive({ TarArchiveInputStream(streamOf(under), "UTF-8") }, under) }
            reopen != null -> planSafely { planStreamArchive({ TarArchiveInputStream(reopen(), "UTF-8") }, null) }
            else -> null
        }
        val dropSystem = plan?.dropSystemFolder == true
        val tar = TarArchiveInputStream(s, "UTF-8")
        var fullRoot: Boolean? = null
        while (true) {
            if (cancelled) throw IOException("cancelled")
            val e: TarArchiveEntry = tar.nextEntry ?: break
            var n = e.name.removePrefix("./").trimStart('/')
            if (n.isEmpty()) continue
            // an image, tar or folder the person did not pick for its partition
            if (plan != null && n in plan.skip) continue
            if (dropSystem && n.startsWith("system/")) continue
            // Odin names members "system.img.md5", "boot.img.md5", ...: match on the real image name
            // (a picked "system_b.img" / "boot_b.img" is imported under the plain name, which the matchers below know)
            val base = PartitionNames.stripOdinMd5(plan?.canonical?.get(n) ?: n.substringAfterLast('/'))
            // a bare partition name ("vendor", "boot", "product" ...) counts only for a regular, sizeable file: in a rootfs tree
            // it is far more often a directory or a symlink
            val bare = e.isFile && !e.isSymbolicLink && !e.isLink && PartitionNames.bareOk(n, e.size)
            // дерево rootfs целиком (system/, data/, dev/, …)
            if (fullRoot == null && ((n == "system" && !e.isFile) || n.startsWith("system/") || n.startsWith("init.rc") || n.startsWith("default.prop"))) fullRoot = true
            when {
                // a file of the partition a vendor.tar / system_ext.tar ... stands for (its images, at the top level, go on below)
                partGroup != null && !(('/' !in n) && PartitionChoice.imageGroup(base, bare, lz4Names = true) != null) -> {
                    val p = PartitionChoice.tarPath(partGroup, n)
                    when {
                        e.isDirectory -> FirmwareContainers.destination(root, p).mkdirs()
                        e.isSymbolicLink -> symlinks.add(e.linkName to "/$p")
                        e.isLink -> hardlink(p, PartitionChoice.tarPath(partGroup, e.linkName))
                        e.isFile -> { writeFile(p, tar.nonClosing(), e.mode); if (partGroup == "vendor") gotVendor = true }
                    }
                }
                isSonyName(base) && e.isFile && e.size > 0 -> importSonyFile(spill(tar.nonClosing(), base), depth)
                base.equals("system.yaffs2.img", true) && e.isFile -> {
                    val t = spill(tar.nonClosing(), base)
                    try { FileChannel.open(t.toPath(), StandardOpenOption.READ).use { importImage(ChannelSource(it), "system") } }
                    finally { t.delete() }
                }
                base.endsWith(".yaffs2.img", true) -> {}
                // Early Samsung Odin PDA tars (GT-I7500 and other Android 1.x/2.x era phones) name the partition images
                // "system", "userdata", "recovery", "kernel" with no extension. A regular file called "system" is the
                // YAFFS2 /system image, not a folder of the rootfs tree.
                n == "system" && e.isFile && e.size > 1_000_000 -> {
                    val t = spill(tar.nonClosing(), base)
                    try { FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> importImage(ChannelSource(c), "system") } }
                    finally { t.delete() }
                }
                n == "userdata" && e.isFile -> {}
                CscPackage.isCscContainer(base) && e.isFile && e.size > 0 && !inSystemDump(n) -> takeCsc(tar.nonClosing(), base)
                isOemName(base.replace(Regex("(?i)\\.lz4$"), ""), bare) && e.isFile && !inSystemDump(n) -> {
                    val t = spill(maybeLz4(tar, base), base)
                    try { FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> importOem(ChannelSource(c)) } } finally { t.delete() }
                }
                isSystemImageName(base.replace(Regex("(?i)\\.lz4$"), ""), bare) && e.isFile -> {
                    if (under != null && !base.endsWith(".lz4", true)) {
                        // an Odin tar holds the image as it is: read in place, the phone needs no second copy of it
                        val ext = ExtentSource(under, listOf(tar.bytesRead to e.size))
                        importImage(if (SparseSource.probe(ext)) SparseSource(listOf(ext)) else ext, "system")
                    } else {
                        val t = spill(maybeLz4(tar, base), base)
                        try {
                            FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c ->
                                val src = ChannelSource(c)
                                importImage(if (SparseSource.probe(src)) SparseSource(listOf(src)) else src, "system")
                            }
                        } finally { t.delete() }
                    }
                }
                // vendor / product / system_ext / odm / prism / optics ... as separate images (pre-super AP, Samsung CSC)
                isExtraName(base.replace(Regex("(?i)\\.lz4$"), ""), bare) && e.isFile && e.size > 4096 && !inSystemDump(n) -> {
                    val t = spill(maybeLz4(tar, base), base)
                    try { FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> importExtra(ChannelSource(c), base.replace(Regex("(?i)\\.lz4$"), "")) } } finally { t.delete() }
                }
                isVendorName(base.replace(Regex("(?i)\\.lz4$"), ""), bare) && e.isFile && e.size > 4096 && !inSystemDump(n) -> {
                    val t = spill(maybeLz4(tar, base), base)
                    try { FileChannel.open(t.toPath(), StandardOpenOption.READ).use { c -> importVendor(ChannelSource(c)) } } finally { t.delete() }
                }
                (base.matches(Regex("(?i)(boot\\.img|boot_signed(\\.img)?|zImage|kernel)(\\.lz4)?")) || isBootName(base, bare)) && e.isFile && !inSystemDump(n) -> takeBoot(maybeLz4(tar, base).readBytes())
                isRecoveryName(base, bare) && e.isFile && e.size < 64_000_000 && !inSystemDump(n) -> takeRecovery(tar.nonClosing(), base)
                base.matches(Regex("(?i).*\\.(tar|tar\\.md5)")) && e.isFile && (e.size > 20_000_000 || PartitionChoice.tarGroup(base) != null) -> importTarStream(tar.nonClosing(), base, depth + 1)
                // factory-образы Google: tgz → image-*.zip → system.img/boot.img; Samsung: zip внутри tar
                base.lowercase().endsWith(".zip") && e.isFile && e.size > 20_000_000 -> {
                    handleNestedZip(spill(tar.nonClosing(), base), base, depth)
                }
                n.matches(Regex("^(system|data|dev|sbin|vendor|oem|etc)(/.*)?$")) || n.matches(Regex("^[^/]+\\.rc$")) || n == "default.prop" || n.startsWith("dhd.") -> {
                    // файлы корня (рамдиск, dhd.*) берём, только если архив — целое дерево rootfs
                    if (fullRoot != true && !n.startsWith("system") && !(n.startsWith("dhd.") || n.endsWith(".rc") || n == "default.prop" || n.startsWith("sbin"))) continue
                    when {
                        e.isDirectory -> FirmwareContainers.destination(root, n).mkdirs()
                        e.isSymbolicLink -> symlinks.add(e.linkName to "/$n")
                        e.isLink -> hardlink(n, e.linkName)
                        e.isFile -> {
                            writeFile(n, tar.nonClosing(), e.mode)
                            if (n.startsWith("system/")) gotSystem = true
                        }
                    }
                }
                // TWRP oem.ext4.win (tar backup of the files): paths without the oem/ prefix
                name.contains("oem", true) && name.endsWith(".win", true) && !name.contains("system", true) -> {
                    val p = "oem/$n"
                    when {
                        e.isDirectory -> FirmwareContainers.destination(root, p).mkdirs()
                        e.isSymbolicLink -> symlinks.add(e.linkName to "/$p")
                        e.isFile -> writeFile(p, tar.nonClosing(), e.mode)
                    }
                }
                // TWRP system.ext4.win: пути без префикса system/
                name.contains("system", true) && name.endsWith(".win", true) -> {
                    val p = "system/$n"
                    when {
                        e.isDirectory -> FirmwareContainers.destination(root, p).mkdirs()
                        e.isSymbolicLink -> symlinks.add(e.linkName to "/$p")
                        e.isFile -> { writeFile(p, tar.nonClosing(), e.mode); gotSystem = true }
                    }
                }
            }
            if (files % 200 == 0) onProgress(lt(R.string.imp_extracting, base), -1f)
        }
    }

    private fun InputStream.nonClosing(): InputStream = object : java.io.FilterInputStream(this) { override fun close() {} }

    private fun maybeLz4(s: InputStream, name: String): InputStream =
        if (name.endsWith(".lz4", true)) org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream(s.nonClosing()) else s.nonClosing()

    private fun hardlink(n: String, target: String) {
        val src = FirmwareContainers.destination(root, target.removePrefix("./").trimStart('/'))
        val dst = FirmwareContainers.destination(root, n)
        if (src.isFile) { dst.parentFile?.mkdirs(); src.copyTo(dst, overwrite = true) }
    }

    // ------------------------------------------------------------------ образы ФС

    /**
     * Set while the system partition image built from a block OTA (system.new.dat + transfer list) is imported: a SquashFS
     * one cannot be rebuilt from its files later, so it is kept for delta OTAs ([StockImage]).
     */
    private var keepStockImage = false

    private fun importImage(source: RandomSource, mount: String) {
        // Motorola signed images carry a signature header in front of the ext4 (see MotoImage)
        val src = MotoImage.unwrap(source)
        if (src !== source) log("image $mount: Motorola signature header skipped, ${src.size shr 20} MB filesystem")
        if (UbiFsReader.probe(src)) { importUbifs(src, mount); return }
        if (Yaffs2Reader.probe(src)) { importYaffs2(src, mount); return }
        if (ErofsReader.probe(src)) { importErofs(src, mount); return }
        if (!Ext4Reader.probe(src)) { importOtherFilesystem(src, mount, keep = keepStockImage && mount == "system" && src === source); return }
        val fs = Ext4Reader(src)
        // Android 7+ A/B ("system-as-root"): the partition IS the root file system, /system is a folder in it.
        // Its top level (init*.rc, sepolicy, sbin/, default.prop...) is the ramdisk, so it goes to the guest root as is.
        val asRoot = mount == "system" && AbLayout.isSystemAsRoot(fs)
        if (asRoot) { systemAsRoot = true; log("image system: system-as-root layout (root file system in the partition)") }
        onProgress(lt(R.string.imp_reading_image_ext_block, mount, fs.blockSize), -1f)
        var n = 0
        // what is needed to rebuild this image later from the files (block-based OTAs work on the image)
        // every ext4 partition a block OTA can touch gets its layout recorded (system, vendor, product, system_ext, odm);
        // a system-as-root partition too: its top level is the guest root, which the layout's prefix says
        val layoutPartition = SystemLayout.partitionOf(mount)
        val cap = if (layoutPartition != null) SystemLayout.Capture(fs, src, SystemLayout.prefixOf(mount, asRoot)) else null
        fs.walk { path, node ->
            if (cancelled) throw IOException("cancelled")
            if (asRoot && AbLayout.skipRootEntry(path, node.isFile, node.isLink)) return@walk
            val rel = if (asRoot) path else "$mount/$path"
            val f = FirmwareContainers.destination(root, rel)
            when {
                node.isDir -> { f.mkdirs(); cap?.dir(path, node.perm) }
                node.isLink -> { val t = fs.linkTarget(node); symlinks.add(t to "/$rel"); cap?.link(path, t) }
                node.isFile -> {
                    f.parentFile?.mkdirs()
                    val md = java.security.MessageDigest.getInstance("SHA-1")
                    f.outputStream().buffered(1 shl 20).use { fs.copy(node, java.security.DigestOutputStream(it, md)) }
                    cap?.file(path, node, md.digest())
                    applyMode(f, node.perm)
                    files++; bytes += node.size
                }
            }
            if (++n % 150 == 0) onProgress(lt(R.string.ota_detail_with_name, mount, path.substringAfterLast('/')), -1f)
        }
        if (cap != null && layoutPartition != null) {
            onProgress(lt(R.string.imp_recording_the_layout_of_image, mount), -1f)
            val lf = SystemLayout.file(paths.dir, layoutPartition)
            runCatching { cap.finish(lf) }.onFailure {
                lf.delete()
                log("image $mount: layout not kept (${it.message}); block-based OTAs cannot be applied to this partition of the VM")
            }
        }
        if (mount == "system") gotSystem = true
        log("image $mount: $n objects")
    }

    /**
     * Image is neither YAFFS2 nor ext4: identify SquashFS / RFS (FAT) through the firmware toolset,
     * convert it to a temporary zip and unpack that into the guest root. Anything else fails with
     * a message naming the supported formats instead of an opaque ext4 magic error.
     */
    private fun importOtherFilesystem(src: RandomSource, mount: String, keep: Boolean = false) {
        val headFile = File(tmp, "probe-${System.nanoTime()}.bin")
        val kind = try {
            val head = ByteBuffer.allocate(minOf((8L shl 20) + 512, src.size).toInt()); src.read(0, head)
            headFile.writeBytes(head.array())
            FirmwareToolset.detect(headFile)
        } finally { headFile.delete() }
        if (kind != "SQUASHFS" && kind != "RFS")
            throw LocalizedIOException("unrecognized filesystem for $mount (expected ext4, YAFFS2, UBIFS, SquashFS or RFS)", R.string.imp_unrecognized_filesystem_for_expected_ext_yaf, mount)
        val fsName = if (kind == "SQUASHFS") "SquashFS" else "RFS"
        onProgress(lt(R.string.imp_reading_image, mount, fsName), -1f)
        val dir = File(tmp, "fs-${System.nanoTime()}").apply { mkdirs() }
        try {
            val image = spill(streamOf(src), "$mount.img").let { File(dir, "$mount.img").also { d -> it.copyTo(d, true); it.delete() } }
            val artifacts = FirmwareToolset.extract(image, File(dir, "out")) { msg -> log(msg) }
            val zipFile = artifacts.firstOrNull { it.file.isFile }?.file
                ?: throw LocalizedIOException("$fsName image produced no files", R.string.imp_image_produced_no_files, fsName)
            if (keep && kind == "SQUASHFS") {
                // the partition of a block OTA: delta OTAs run on this image, see StockImage
                val kept = StockImage.file(paths.dir)
                runCatching { image.copyTo(kept, overwrite = true) }.onFailure {
                    kept.delete()
                    log("image $mount: stock image not kept (${it.message}); block-based OTAs cannot be applied to this VM")
                }
            }
            image.delete()
            var n = 0
            ZipFile.builder().setFile(zipFile).get().use { zip ->
                for (e in zip.entries) {
                    if (cancelled) throw IOException("cancelled")
                    // the toolset wraps everything in one top folder named after the image: drop it
                    val inner = e.name.replace('\\', '/').substringAfter('/', "").trimEnd('/')
                    if (inner.isEmpty() || inner.split('/').any { it == ".." }) continue
                    val rel = "$mount/$inner"
                    val f = FirmwareContainers.destination(root, rel)
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
                    if (++n % 150 == 0) onProgress(lt(R.string.ota_detail_with_name, mount, inner.substringAfterLast('/')), -1f)
                }
            }
            if (mount == "system") gotSystem = true
            log("$fsName $mount: $n objects")
        } finally { deleteTree(dir) }
    }

    private fun importYaffs2(src: RandomSource, mount: String) {
        onProgress(lt(R.string.imp_reading_image_yaffs, mount), -1f)
        val fs = Yaffs2Reader(src) { if (cancelled) throw IOException("cancelled") }
        var n = 0
        fs.walk { path, node ->
            val rel = "$mount/$path"
            val f = FirmwareContainers.destination(root, rel)
            when (node.type) {
                3 -> { if (!f.isDirectory && !f.mkdirs()) throw LocalizedIOException("cannot create $rel", R.string.imp_cannot_create, rel) }
                2 -> {
                    val target = if (node.alias.startsWith("/")) node.alias.trimStart('/')
                        else rel.substringBeforeLast('/') + "/" + node.alias
                    val normalized = java.nio.file.Paths.get(target).normalize().toString()
                    if (node.alias.isEmpty() || normalized == ".." || normalized.startsWith("../"))
                        throw LocalizedIOException("YAFFS2 symlink escapes guest root", R.string.imp_yaffs_symlink_escapes_guest_root)
                    symlinks.add(node.alias to "/$rel")
                }
                1, 4 -> {
                    f.parentFile?.mkdirs()
                    // Links are only created by finishTree, after all file bytes.
                    if (isLink(f)) throw LocalizedIOException("YAFFS2 destination is a symlink", R.string.imp_yaffs_destination_is_a_symlink)
                    f.outputStream().buffered(1 shl 20).use { fs.copy(node, it) }
                    applyMode(f, fs.fileMode(node) and 0xfff)
                    files++; bytes += f.length()
                }
                5 -> {} // Guest /dev is created by the VM; never mknod on the host.
            }
            if (++n % 150 == 0) onProgress(lt(R.string.ota_detail_with_name, mount, path.substringAfterLast('/')), -1f)
        }
        if (mount == "system") gotSystem = true
        val geo = fs.geometry
        log("YAFFS2 $mount: $n objects, page ${geo.pageSize}+${geo.spareSize}, " +
            (if (geo.sequential) "spare-less (sequential), " else "tags at spare+${geo.tagOffset}, start offset ${geo.base}, ") +
            (if (geo.order == java.nio.ByteOrder.LITTLE_ENDIAN) "LE" else "BE"))
        fs.warnings.forEach { log("YAFFS2 $mount: $it") }
    }

    /**
     * UBI image with a UBIFS volume (NAND devices, e.g. MediaTek MT65xx/MT657x phones): the same tree walk as YAFFS2.
     * See [UbiFsReader] for the supported compression types (none, LZO, zlib, zstd and the MediaTek LZ4K).
     */
    private fun importUbifs(src: RandomSource, mount: String) {
        onProgress(lt(R.string.imp_reading_image_ubifs, mount), -1f)
        val fs = UbiFsReader(src) { if (cancelled) throw IOException("cancelled") }
        log("UBIFS $mount: ${fs.summary}")
        var n = 0
        fs.walk { path, node ->
            val rel = "$mount/$path"
            val f = FirmwareContainers.destination(root, rel)
            when (node.type) {
                3 -> { if (!f.isDirectory && !f.mkdirs()) throw LocalizedIOException("cannot create $rel", R.string.imp_cannot_create, rel) }
                2 -> {
                    val target = if (node.target.startsWith("/")) node.target.trimStart('/')
                        else rel.substringBeforeLast('/') + "/" + node.target
                    val normalized = java.nio.file.Paths.get(target).normalize().toString()
                    if (node.target.isEmpty() || normalized == ".." || normalized.startsWith("../"))
                        throw LocalizedIOException("UBIFS symlink escapes guest root", R.string.imp_ubifs_symlink_escapes_guest_root)
                    symlinks.add(node.target to "/$rel")
                }
                1 -> {
                    f.parentFile?.mkdirs()
                    // Links are only created by finishTree, after all file bytes.
                    if (isLink(f)) throw LocalizedIOException("UBIFS destination is a symlink", R.string.imp_ubifs_destination_is_a_symlink)
                    f.outputStream().buffered(1 shl 20).use { fs.copy(node, it) }
                    applyMode(f, fs.fileMode(node) and 0xfff)
                    files++; bytes += f.length()
                }
                else -> {} // Guest /dev is created by the VM; never mknod on the host.
            }
            if (++n % 150 == 0) onProgress(lt(R.string.ota_detail_with_name, mount, path.substringAfterLast('/')), -1f)
        }
        if (mount == "system") gotSystem = true
        log("UBIFS $mount: $n objects")
        fs.warnings.forEach { log("UBIFS $mount: $it") }
    }

    /**
     * EROFS partition (Android 13+ firmware and GSIs): the same tree walk as YAFFS2/UBIFS, see [ErofsReader] for the
     * supported layouts. Compression is LZ4 / LZ4HC / DEFLATE; LZMA and Zstandard images stop with an error.
     * A system partition that is the root file system (init + system/) is laid out like the ext4 system-as-root case.
     */
    private fun importErofs(src: RandomSource, mount: String) {
        onProgress("Reading image $mount (EROFS)", -1f)
        val fs = ErofsReader(src) { if (cancelled) throw IOException("cancelled") }
        // Android 10+: top-level "init" is a symlink to /system/bin/init, earlier ones have a real file there
        val asRoot = mount == "system" && runCatching {
            AbLayout.isSystemAsRoot(fs.lookup("init")?.type, fs.lookup("system")?.type,
                fs.lookup("system/build.prop") != null, fs.lookup("system/framework") != null)
        }.getOrDefault(false)
        if (asRoot) { systemAsRoot = true; log("image system: system-as-root layout (root file system in the partition)") }
        var n = 0
        fs.walk { path, node ->
            if (asRoot && AbLayout.skipRootEntry(path, node.type == 1, node.type == 2)) return@walk
            val rel = if (asRoot) path else "$mount/$path"
            val f = FirmwareContainers.destination(root, rel)
            when (node.type) {
                3 -> { if (!f.isDirectory && !f.mkdirs()) throw IOException("cannot create $rel") }
                2 -> {
                    val target = if (node.target.startsWith("/")) node.target.trimStart('/')
                        else rel.substringBeforeLast('/', "") + "/" + node.target
                    val normalized = java.nio.file.Paths.get(target).normalize().toString()
                    if (node.target.isEmpty() || normalized == ".." || normalized.startsWith("../"))
                        throw IOException("EROFS symlink escapes guest root: $rel")
                    symlinks.add(node.target to "/$rel")
                }
                1 -> {
                    f.parentFile?.mkdirs()
                    // Links are only created by finishTree, after all file bytes.
                    if (isLink(f)) throw IOException("EROFS destination is a symlink: $rel")
                    f.outputStream().buffered(1 shl 20).use { fs.copy(node, it) }
                    applyMode(f, fs.fileMode(node) and 0xfff)
                    files++; bytes += f.length()
                }
            }
            if (++n % 150 == 0) onProgress("$mount: ${path.substringAfterLast('/')}", -1f)
        }
        if (mount == "system") gotSystem = true
        log("EROFS $mount: $n objects")
    }

    // "ramdisk.img" is how Google's SDK system images (clockwork_sdk, sysimg_*) ship the root fs: a gzip'd cpio next to system.img
    private val LOOSE_RAMDISK = Regex("(?i)(ramdisk|initrd|initramfs)(\\.cpio)?(\\.(gz|xz|lzma|lz4|img))?")

    /** Ramdisk shipped without a boot.img: a packed ramdisk.gz (cpio) or an unpacked initrd/ folder. */
    /**
     * The entries of [src] if it is a bare ramdisk: named ramdisk / initrd / initramfs (.cpio, .gz, .img ...), compressed or plain
     * cpio, small enough, and holding an init script (/init.rc, or /etc/init.rc of a pre-1.0 ramdisk). Null otherwise, and then
     * the file goes the way it went before.
     */
    private fun decodeBareRamdisk(src: RandomSource, name: String, h: ByteArray): List<BootImage.CpioEntry>? {
        val base = name.replace('\\', '/').substringAfterLast('/')
        if (!LOOSE_RAMDISK.matches(base) || src.size !in 1024..MAX_RAMDISK) return null
        val gzip = h.size > 2 && h[0] == 0x1f.toByte() && h[1] == 0x8b.toByte()
        val lzma = h.size > 2 && h[0] == 0x5d.toByte() && h[1] == 0x00.toByte()
        val xz = h.size > 6 && h[0] == 0xfd.toByte() && String(h, 1, 4, Charsets.ISO_8859_1) == "7zXZ"
        val lz4 = h.size > 3 && h[0] == 0x02.toByte() && h[1] == 0x21.toByte() && h[2] == 0x4c.toByte()
        val cpio = h.size > 6 && String(h, 0, 6, Charsets.ISO_8859_1).let { it == "070701" || it == "070702" }
        if (!(gzip || lzma || xz || lz4 || cpio)) return null
        return runCatching { BootImage.decompress(readAllFrom(src))?.let { BootImage.cpio(it) } }.getOrNull()
            ?.takeIf { it.isNotEmpty() && LegacyFirmware.hasInitScript(it.map { e -> e.name }) }
    }

    private fun takeBareRamdisk(rd: List<BootImage.CpioEntry>, name: String) {
        if (ramdisk != null) { log("boot: $name ignored (a ramdisk was already taken)"); return }
        ramdisk = rd
        log("boot: bare ramdisk ${name.substringAfterLast('/')}, ${rd.size} files")
    }

    private fun takeLooseRamdisk(zip: ZipFile, entries: List<ZipArchiveEntry>, wrap: String, sysRoot: String?) {
        fun rel(e: ZipArchiveEntry) = e.name.replace('\\', '/').removePrefix(wrap)
        val usable = entries.filter { e ->
            val n = rel(e)
            !e.isDirectory && !n.contains("__MACOSX/") && systemRel(sysRoot, n) == null
        }
        // 1. packed ramdisk (keeps modes and symlinks)
        for (e in usable.filter { LOOSE_RAMDISK.matches(rel(it).substringAfterLast('/')) && it.size in 1024..MAX_RAMDISK }
            .sortedBy { rel(it).count { c -> c == '/' } }) {
            val rd = runCatching {
                val raw = zip.getInputStream(e).use { it.readBytes() }
                BootImage.decompress(raw)?.let { BootImage.cpio(it) }
            }.getOrNull()
            if (!rd.isNullOrEmpty() && LegacyFirmware.hasInitScript(rd.map { it.name })) {
                ramdisk = rd
                log("boot: loose ramdisk ${rel(e)}, ${rd.size} files")
                return
            }
        }
        // 2. unpacked ramdisk folder (.../initrd/init.rc, .../initrd/sbin/healthd)
        // (a pre-1.0 ramdisk keeps its init.rc in etc/, so the folder named initrd is one level up)
        val rc = usable.firstOrNull { rel(it).substringAfterLast('/') == "init.rc" && rel(it).substringBeforeLast('/', "").let { d ->
            d.substringAfterLast('/').matches(Regex("(?i)(initrd|ramdisk|rootfs)")) ||
                d.substringAfterLast('/') == "etc" && d.substringBeforeLast('/', "").substringAfterLast('/').matches(Regex("(?i)(initrd|ramdisk|rootfs)")) } } ?: return
        val dir = rel(rc).substringBeforeLast('/').removeSuffix("/etc") + "/"
        val out = ArrayList<BootImage.CpioEntry>()
        for (e in usable) {
            val n = rel(e)
            if (!n.startsWith(dir) || e.size > MAX_RAMDISK) continue
            val name = n.removePrefix(dir)
            if (name.isEmpty()) continue
            val data = zip.getInputStream(e).use { it.readBytes() }
            if (e.isUnixSymlink) { out.add(BootImage.CpioEntry(name, 0xA000 or 0x1ff, data)); continue }
            val perm = e.unixMode.and(0xfff).takeIf { it != 0 } ?: if (name.startsWith("sbin/") || name == "init" || name.endsWith(".sh")) 0x1ed else 0x1a4
            out.add(BootImage.CpioEntry(name, 0x8000 or perm, data))
        }
        if (LegacyFirmware.hasInitScript(out.map { it.name })) {
            ramdisk = out
            log("boot: unpacked ramdisk folder ${dir.trimEnd('/')}, ${out.size} files")
        }
    }

    /** After all containers are read: the recovery-only boot ramdisk is the fallback ramdisk of a non-system-as-root firmware. */
    private fun useDeferredBoot() {
        applyVendorRamdisk()
        val data = recoveryBoot
        recoveryBoot = null
        val kernel = kernelBoot
        kernelBoot = null
        if (data != null && !systemAsRoot && ramdisk == null) {
            val rd = runCatching { BootImage.ramdisk(data) }.getOrNull()?.takeIf { it.isNotEmpty() }
            if (rd != null) {
                ramdisk = rd
                File(paths.dir, "boot.img").writeBytes(data)
                log("boot: ramdisk, ${rd.size} files")
                return
            }
        } else if (data != null) log("boot: recovery ramdisk ignored (the root file system comes from the system partition)")
        // The root file system comes from the system partition (A/B system-as-root, GKI), so the boot image has no ramdisk to
        // contribute, but it is still the VM's boot image: kernel version for /proc/version, and what a later OTA is applied to.
        val keep = data ?: kernel ?: return
        val target = File(paths.dir, "boot.img")
        if (target.isFile) return
        val out = BootImage.trimmed(keep)
        target.writeBytes(out)
        log("boot: boot.img kept (${out.size} bytes, ramdisk not used)")
    }

    /** vendor_boot (header v3/v4): only its ramdisk matters; it is laid over boot's ramdisk in [useDeferredBoot]. */
    private fun takeVendorBoot(src: RandomSource) {
        val vb = VendorBoot.read(src)
        if (vb == null) { log("vendor_boot: not readable, skipped"); return }
        if (vb.ramdisk.isEmpty()) { log("vendor_boot: no vendor ramdisk"); return }
        vendorRamdisk = vb.ramdisk
        log("vendor_boot: vendor ramdisk, ${vb.ramdisk.size} entries" + (vb.bootconfig?.let { "; bootconfig: " + it.lines().joinToString(" ") } ?: ""))
    }

    /** Lays the vendor ramdisk over the boot ramdisk, if there are both. */
    private fun applyVendorRamdisk() {
        val vendor = vendorRamdisk ?: return
        vendorRamdisk = null
        val base = ramdisk
        if (base == null) { log("vendor_boot: no boot ramdisk to lay the vendor ramdisk over, ignored"); return }
        ramdisk = VendorBoot.merge(base, vendor)
        log("boot: vendor ramdisk merged, ${ramdisk!!.size} files")
    }

    /**
     * Opens a CRX container and imports what is in it. Only the ZIP's entries that can hold firmware are looked at
     * (not manifest.json and _metadata/). An entry is either a 7-Zip self-extractor / 7z archive, whose archive part is
     * cut out and imported, or anything else the importer knows, which is imported as it is.
     */
    private fun importCrx(src: RandomSource, name: String, depth: Int) {
        val start = CrxContainer.zipStart(src) ?: throw LocalizedIOException("unknown file format \"$name\"", R.string.imp_unknown_file_format, name)
        ZipFile.builder().setSeekableByteChannel(RandomSourceChannel(src, start)).get().use { zip ->
            val entries = zip.entries.toList()
                .filter { !it.isDirectory && it.size >= 1024 && !it.name.startsWith("_metadata/") && !it.name.equals("manifest.json", true) }
                .sortedByDescending { it.size }
            if (entries.isEmpty()) throw LocalizedIOException("unknown file format \"$name\"", R.string.imp_unknown_file_format, name)
            log("crx: " + entries.joinToString { "${it.name} ${it.size shr 20} MB" })
            for (e in entries) {
                if (cancelled) throw IOException("cancelled")
                val base = e.name.substringAfterLast('/')
                onProgress(lt(R.string.imp_extracting, base), 0.1f)
                zip.getInputStream(e).use { importCrxEntry(it, e.size, base, depth) }
                if (gotSystem) break
            }
        }
    }

    private fun importCrxEntry(input: InputStream, size: Long, base: String, depth: Int) {
        // the 7z archive follows the SFX stub (7zr.exe) in the same file: find it in the first bytes of the stream
        val first = ByteArray(minOf(size, CrxContainer.MAX_STUB.toLong()).toInt())
        val n = GptDisk.readFully(input, first, 0, first.size)
        val at = CrxContainer.locateSevenZ(first, n, size)
        val rest: InputStream
        val label: String
        if (at >= 0) {
            log("crx: $base holds a 7z archive at offset $at (${(size - at) shr 20} MB)")
            rest = java.io.SequenceInputStream(java.io.ByteArrayInputStream(first, at, n - at), input)
            label = "payload.7z"
        } else {
            rest = java.io.SequenceInputStream(java.io.ByteArrayInputStream(first, 0, n), input)
            label = base
        }
        // the 7z reader needs random access, so the archive is spilled (the stub in front of it is not)
        val file = spill(rest, label)
        try {
            try {
                FileChannel.open(file.toPath(), StandardOpenOption.READ).use { c -> handle(ChannelSource(c), c, label, depth + 1) }
            } catch (t: Throwable) {
                // a file of the extension that is no firmware (script, image, locale): not a reason to give up on the others
                if (cancelled || at >= 0 || gotSystem) throw t
                log("crx: $base skipped (${t.message ?: t.javaClass.simpleName})")
            }
        } finally { deleteTree(file.parentFile ?: file) }
    }

    private fun takeBoot(raw: ByteArray) {
        if (ramdisk != null) return
        val data = BootImage.stripHtcSignature(raw)
        val rd = runCatching { BootImage.ramdisk(data) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: squashRamdisk(data)
        if (rd.isNullOrEmpty()) {
            // Android 13+ (GKI 2.0, boot header v3/v4): `boot` holds the kernel only. That is a valid boot partition, it just has
            // no ramdisk to unpack (the generic ramdisk is in init_boot, the root file system in system).
            if (BootImage.isKernelOnly(data)) { kernelBoot = data; log("boot: kernel-only boot image (GKI), kept as boot.img"); return }
            log("boot: ramdisk not recognized"); return
        }
        if (AbLayout.isRecoveryRamdisk(rd)) {
            // A/B boot = kernel + recovery ramdisk; the real init.rc / sbin/ are in the system partition (system-as-root)
            recoveryBoot = data
            log("boot: recovery ramdisk (A/B boot image), ${rd.size} files; kept aside")
            return
        }
        ramdisk = rd
        File(paths.dir, "boot.img").writeBytes(data)
        log("boot: ramdisk, ${rd.size} files")
    }

    /**
     * Google TV (Marvell Berlin, e.g. Hisense GX1200V) ships boot.img as a plain SquashFS root file system, not an
     * "ANDROID!" image: init*.rc, sbin/ and the glibc userland (lib/ld-linux.so.3, libc, libpthread, ...) that the
     * firmware's native binaries are linked against ("interpreter /lib/ld-linux.so.3"). Without it every guest
     * program dies at once with "Could not open '/lib/ld-linux.so.3'". Returns the same entry list finishTree takes
     * from a boot.img ramdisk, or null if [raw] is not a SquashFS root file system.
     */
    private fun squashRamdisk(raw: ByteArray): List<BootImage.CpioEntry>? {
        if (raw.size < 96) return null
        val magic = String(raw, 0, 4, Charsets.ISO_8859_1)
        if (magic != "hsqs" && magic != "sqsh") return null
        val tmp = File(paths.dir, "boot-rootfs.sqfs")
        return try {
            tmp.writeBytes(raw)
            val out = ArrayList<BootImage.CpioEntry>()
            var total = 0L
            var curMode = 0
            var cur: java.io.ByteArrayOutputStream? = null
            FirmwareToolset.walkSquashFs(tmp, { log(it) }, object : FirmwareToolset.SquashVisitor {
                override fun dir(path: String, mode: Int) { out.add(BootImage.CpioEntry(path, 0x4000 or (mode and 0xfff), ByteArray(0))) }
                override fun link(path: String, target: String) { out.add(BootImage.CpioEntry(path, 0xA000 or 0x1ff, target.toByteArray())) }
                override fun file(path: String, mode: Int, size: Long): java.io.OutputStream? {
                    if (size > MAX_RAMDISK_FILE) { log("boot rootfs: skipped $path (${size shr 20} MB)"); return null }
                    total += size
                    if (total > MAX_RAMDISK) throw LocalizedIOException("boot rootfs too large", R.string.imp_boot_rootfs_too_large)
                    curMode = mode
                    return java.io.ByteArrayOutputStream(size.toInt().coerceAtLeast(16)).also { cur = it }
                }
                override fun fileEnd(path: String) {
                    cur?.let { out.add(BootImage.CpioEntry(path, 0x8000 or (curMode and 0xfff), it.toByteArray())) }
                    cur = null
                }
            })
            if (out.none { it.name == "init.rc" }) { log("boot: SquashFS has no init.rc, not a root file system"); null }
            else { log("boot: SquashFS root file system, ${out.size} entries (glibc userland: ${out.count { it.name.startsWith("lib/") }} files in /lib)"); out }
        } catch (ex: Exception) {
            log("boot: SquashFS root file system: ${ex.message}, skipped"); null
        } finally { tmp.delete() }
    }

    // ------------------------------------------------------------------ файлы, ссылки, права

    private fun writeFile(rel: String, i: InputStream, mode: Int?) {
        val f = FirmwareContainers.destination(root, rel)
        f.parentFile?.mkdirs()
        if (runCatching { android.system.OsConstants.S_ISLNK(Os.lstat(f.path).st_mode) }.getOrDefault(false)) f.delete()
        // Windows/Cygwin archives flatten symlinks into small files: "!<symlink>" + (BOM FF FE + UTF-16LE | UTF-8) target + NUL.
        val src = java.io.BufferedInputStream(i, 1 shl 16)
        src.mark(1024)
        val head = ByteArray(512)
        var hn = 0
        while (hn < head.size) { val r = src.read(head, hn, head.size - hn); if (r < 0) break; hn += r }
        src.reset()
        if (hn in 12 until head.size && String(head, 0, 10, Charsets.ISO_8859_1) == "!<symlink>") {
            val body = head.copyOfRange(10, hn)
            val target = (if (body.size >= 2 && body[0] == 0xff.toByte() && body[1] == 0xfe.toByte())
                String(body, 2, body.size - 2, Charsets.UTF_16LE) else String(body, Charsets.UTF_8)).trimEnd('\u0000', '\n', '\r').trim()
            if (target.isNotEmpty() && !target.contains('\u0000')) {
                symlinks.add(target to "/" + rel.trimStart('/'))
                return
            }
        }
        f.outputStream().buffered(1 shl 20).use { o ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                if (cancelled) throw IOException("cancelled")
                val n = src.read(buffer)
                if (n < 0) break
                bytes += n
                if (bytes > 16L * 1024 * 1024 * 1024 || root.usableSpace < n + 32L * 1024 * 1024)
                    throw LocalizedIOException("Not enough space to unpack firmware", R.string.imp_not_enough_space_to_unpack_firmware)
                o.write(buffer, 0, n)
            }
        }
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
            val f = FirmwareContainers.destination(root, n)
            when (type) {
                0x4000 -> f.mkdirs()
                0xA000 -> symlinks.add(String(e.data).trimEnd('\u0000') to "/$n")
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
            val f = FirmwareContainers.destination(root, rel)
            FirmwareContainers.checkLink(rel, target)
            f.parentFile?.mkdirs()
            val t = relTarget(link, target)
            runCatching {
                if (f.exists() || isLink(f)) { if (f.isDirectory && !isLink(f)) return@runCatching; f.delete() }
                Os.symlink(t, f.absolutePath); made++
            }
        }
        for ((p, mode, rec) in perms) {
            val f = FirmwareContainers.destination(root, p.trimStart('/'))
            if (rec) f.walkTopDown().filter { it.isFile }.forEach { applyMode(it, mode) } else if (f.isFile) applyMode(f, mode)
        }
        // исполняемые — всё в bin/xbin/sbin
        for (d in listOf("system/bin", "system/xbin", "sbin", "vendor/bin", "system/vendor/bin")) {
            File(root, d).listFiles()?.forEach { if (it.isFile) it.setExecutable(true, false) }
        }
        // zips made without symlink support leave "sh" containing the text "mksh" (see FlattenedLinks)
        made += app.aemu.core.FlattenedLinks.repair(root, log)
        standardLinks()
        fun rootLink(name: String, target: String) {
            val f = File(root, name)
            if (!f.exists() && !isLink(f) && File(root, target).exists()) runCatching { Os.symlink(target, f.absolutePath) }
        }
        rootLink("vendor", "system/vendor")
        rootLink("etc", "system/etc")
        rootLink("bin", "system/bin")
        // pre-1.0: /javalib is the framework directory, and the programs are linked against /android
        LegacyFirmware.ensureLayout(root, log)
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
    private fun isSystemImageName(base: String, bare: Boolean = false) = PartitionNames.isSystem(base, bare)

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
        private val SKIP_NESTED = Regex("(?i).*(gapps|supersu|magisk|busybox|xposed|twrp|modem|csc).*")
        /** Апплеты toolbox Android 2.2–6.0 (ссылка создаётся, только если апплет есть в бинарнике). */
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