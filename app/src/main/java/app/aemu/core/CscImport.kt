package app.aemu.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.aemu.importer.CscPackage
import java.io.File
import java.nio.file.Files

/**
 * Samsung CSC data (/system/csc: customer.xml, others.xml, sales_code.dat, ...), see [CscPackage]. Used by the
 * firmware importer (automatically, when a CSC package is found in the firmware and build.prop says Samsung) and by
 * the VM settings (a CSC file picked by hand). Never touches anything outside system/csc.
 */
internal object CscImport {
    /** build.prop names Samsung as the manufacturer or brand. A tree without build.prop is not treated as Samsung. */
    fun isSamsung(root: File): Boolean = runCatching {
        File(root, "system/build.prop").useLines { lines ->
            lines.any { l ->
                val s = l.trim()
                (s.startsWith("ro.product.manufacturer=") || s.startsWith("ro.product.brand=")) &&
                    s.substringAfter('=').trim().equals("samsung", true)
            }
        }
    }.getOrDefault(false)

    /** A real CSC is in place: sales_code.dat exists (the empty stub the app writes has none). */
    fun present(root: File) = File(root, "system/csc/sales_code.dat").isFile

    /** Writes [files] (target path -> bytes, already under "system/csc/") into [root]; returns how many were written. */
    fun apply(root: File, files: Map<String, ByteArray>): Int {
        val base = File(root, "system/csc")
        var written = 0
        for ((path, bytes) in files) {
            val f = File(root, path)
            if (!f.canonicalPath.startsWith(base.canonicalPath + File.separator)) continue
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".aemu-tmp")
            tmp.writeBytes(bytes)
            Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            f.setReadable(true, false)
            written++
        }
        var d: File? = base
        while (d != null && d != root) { d.setReadable(true, false); d.setExecutable(true, false); d = d.parentFile }
        return written
    }

    /** Settings import into an existing Samsung VM: [uri] is a CSC package (tar.md5, zip, csc.rfs, ...). */
    fun install(ctx: Context, id: String, uri: Uri) {
        VmStorageLease(ctx.filesDir).use { lease ->
            lease.acquire()
            GuestStorageWriters.requireIdle(ctx)
            val paths = VmPaths(ctx, id)
            val old = ImageStore.get(ctx, id) ?: error("VM no longer exists")
            require(isSamsung(paths.root)) { "CSC is only imported into Samsung firmware" }
            val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "csc.tar.md5"
            val scratch = File(ctx.cacheDir, "csc-import-${System.nanoTime()}").apply { mkdirs() }
            try {
                val files = (ctx.contentResolver.openInputStream(uri) ?: error("Cannot open the CSC file")).use {
                    CscPackage.read(it, name, scratch)
                }
                apply(paths.root, files)
                ImageStore.save(ctx, old.copy(sizeBytes = ImageStore.du(paths.dir)))
            } finally { scratch.deleteRecursively() }
        }
    }
}
