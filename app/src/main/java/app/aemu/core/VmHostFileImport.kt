package app.aemu.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import app.aemu.R
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/** Copies picked host documents into the guest's conventional temporary-file directory. */
internal object VmHostFileImport {
    private val idPattern = Regex("[a-z0-9]{6}")

    fun install(ctx: Context, id: String, uris: List<Uri>, progress: (String) -> Unit = {}): Int {
        require(uris.isNotEmpty()) { "No files selected" }
        require(idPattern.matches(id)) { "Invalid VM" }
        return VmStorageLease(ctx.filesDir).use { lease ->
            lease.acquire()
            GuestStorageWriters.requireIdle(ctx)
            val paths = VmPaths(ctx, id)
            val old = ImageStore.get(ctx, id) ?: localizedError("VM no longer exists", R.string.err_vm_no_longer_exists)
            require(!Files.isSymbolicLink(paths.dir.toPath()) && paths.dir.isDirectory) { "Unsafe VM directory" }
            require(!Files.isSymbolicLink(paths.root.toPath()) && paths.root.isDirectory) { "Unsafe VM root" }

            // /data/local/tmp is present on essentially all supported Android guests and is writable for file transfers.
            var parent = paths.root
            for (part in listOf("data", "local", "tmp")) {
                parent = File(parent, part)
                require(!Files.isSymbolicLink(parent.toPath())) { "Unsafe guest directory" }
                if (!parent.exists()) check(parent.mkdir()) { "Cannot create guest directory" }
                require(parent.isDirectory && parent.canonicalFile.toPath().startsWith(paths.root.canonicalFile.toPath())) {
                    "Unsafe guest directory"
                }
            }

            val created = mutableListOf<File>()
            try {
                for ((index, uri) in uris.withIndex()) {
                    val name = displayName(ctx, uri)
                    require(VmFileName.valid(name)) { "Invalid file name: $name" }
                    progress(name)
                    val dest = unique(parent, name)
                    val tempPath = Files.createTempFile(parent.toPath(), ".aemu-import-", ".tmp")
                    val temp = tempPath.toFile()
                    try {
                        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $name")
                        input.use { source -> temp.outputStream().use { output -> source.copyTo(output, 65536) } }
                        Os.chmod(temp.path, 0x1a4) // guest-readable regular file, mode 0644
                        try {
                            Files.move(temp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                            Files.move(temp.toPath(), dest.toPath())
                        }
                        created += dest
                    } finally {
                        Files.deleteIfExists(temp.toPath())
                    }
                }
                ImageStore.save(ctx, old.copy(sizeBytes = ImageStore.du(paths.dir)))
                created.size
            } catch (t: Throwable) {
                created.asReversed().forEach { runCatching { Files.deleteIfExists(it.toPath()) } }
                throw t
            }
        }
    }

    private fun displayName(ctx: Context, uri: Uri): String {
        if (uri.scheme == "file") uri.path?.let { return File(it).name }
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: throw IOException("Cannot determine the picked file name")
    }

    private fun unique(dir: File, name: String): File {
        val first = File(dir, name)
        if (!Files.exists(first.toPath(), LinkOption.NOFOLLOW_LINKS)) return first
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 2
        while (true) {
            val candidate = File(dir, "$base ($index)$ext")
            if (!Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) return candidate
            index++
        }
    }
}

private object VmFileName {
    fun valid(name: String): Boolean = name.isNotEmpty() && name != "." && name != ".." &&
        '/' !in name && '\\' !in name && '\u0000' !in name && name.toByteArray(Charsets.UTF_8).size <= 255
}
