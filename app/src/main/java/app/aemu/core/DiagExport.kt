package app.aemu.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One-tap diagnostics: packs every host log of the VM (aemu.log, the guest programs' strace logs, the guest log buffers,
 * the factory test's own log files) together with a state snapshot (input device nodes, whether the rebuilt guest shim
 * is installed, input counters) into a zip in the public Downloads folder. No root needed.
 */
object DiagExport {
    private const val HEAD = 512L shl 10        // first 512 KiB of a big file
    private const val TAIL = 3L shl 20          // last 3 MiB of a big file
    private val INPUT_LINE = Regex("/dev/input|EVIOC|event[0-9]|epoll_(create|ctl)|input_event|ENOTTY|/dev/tty|/dev/graphics")
    private val READ16 = Regex("read\\(\\d+,0x[0-9a-f]+,(16|24)\\)")

    /** Returns a human readable location of the result, or throws. */
    fun export(ctx: Context, vm: GuestVm): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val name = "aemu-logs-${vm.img.id.take(24)}-$stamp.zip"
        val (out, where, finish) = openDownloads(ctx, name)
        out.use { raw ->
            ZipOutputStream(raw.buffered()).use { zip ->
                zip.setLevel(6)
                entry(zip, "diag.txt") { it.write(diag(ctx, vm).toByteArray()) }
                entry(zip, "ui-log.txt") { o -> vm.lines().forEach { o.write((it + "\n").toByteArray()) } }
                entry(zip, "input-events.txt") { o ->
                    o.write("last events sent to the guest (uptime ms, type: 0=SYN 1=KEY 3=ABS, code, value)\n".toByteArray())
                    vm.input.recentEvents().forEach { o.write((it + "\n").toByteArray()) }
                }
                entry(zip, "guest-logcat.txt") { o ->
                    runCatching { o.bufferedWriter().apply { GuestLog.writeExport(vm.paths.root, vm.paths.bin, this); flush() } }
                }
                vm.paths.bin.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { f -> addFile(zip, "run/${f.name}", f) }
                // files the factory test program writes itself
                listOf("cache/tki/tki.log", "data/mmi_ip.png", "data/local/tmp/mmi.log").forEach { rel ->
                    val f = File(vm.paths.root, rel)
                    if (f.isFile && f.length() < 8L shl 20) addFile(zip, "guest/$rel", f)
                }
                File(vm.paths.root, "data").listFiles()?.filter { it.isFile && (it.extension == "log" || it.extension == "txt") && it.length() < (4L shl 20) }
                    ?.forEach { addFile(zip, "guest/data/${it.name}", it) }
            }
        }
        finish()
        return where
    }

    private class Target(val out: OutputStream, val where: String, val finish: () -> Unit) {
        operator fun component1() = out
        operator fun component2() = where
        operator fun component3() = finish
    }

    private fun openDownloads(ctx: Context, name: String): Target {
        if (Build.VERSION.SDK_INT >= 29) {
            // IS_PENDING keeps the half-written zip out of the Downloads listing until it is complete
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                val os = ctx.contentResolver.openOutputStream(uri)
                if (os != null) return Target(os, "Download/$name") {
                    ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                }
            }
        }
        // Android 8/9 (no MediaStore.Downloads) or a failed insert: the public folder if we may write there, else the app's own one
        val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val f = runCatching { pub.mkdirs(); File(pub, name).also { it.createNewFile() } }.getOrNull()
        if (f != null && f.canWrite()) return Target(f.outputStream(), f.absolutePath) {}
        val app = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir, name)
        app.parentFile?.mkdirs()
        return Target(app.outputStream(), app.absolutePath) {}
    }

    private fun entry(zip: ZipOutputStream, name: String, body: (OutputStream) -> Unit) {
        zip.putNextEntry(ZipEntry(name))
        runCatching { body(zip) }.onFailure { zip.write("\n[export failed: ${it}]\n".toByteArray()) }
        zip.closeEntry()
    }

    private fun addFile(zip: ZipOutputStream, entryName: String, f: File) {
        val len = f.length()
        entry(zip, entryName) { o ->
            RandomAccessFile(f, "r").use { r ->
                if (len <= HEAD + TAIL) copyRange(r, 0, len, o)
                else {
                    copyRange(r, 0, HEAD, o)
                    o.write("\n\n[... ${len - HEAD - TAIL} bytes skipped ...]\n\n".toByteArray())
                    copyRange(r, len - TAIL, TAIL, o)
                }
            }
        }
        // strace logs are huge: pull every input-related line out of the whole file so nothing important is lost in the cut
        if (len > HEAD + TAIL && len < (200L shl 20) && File(f.parentFile, "strace.${f.nameWithoutExtension}").exists()) {
            entry(zip, "$entryName.input-extract.txt") { o ->
                var inputLines = 0; var reads = 0
                f.bufferedReader(Charsets.ISO_8859_1).useLines { seq ->
                    for (line in seq) {
                        val l = if (line.length > 240) line.substring(0, 240) else line
                        val hit = INPUT_LINE.containsMatchIn(l)
                        val rd = !hit && reads < 400 && READ16.containsMatchIn(l)
                        if ((hit && inputLines < 6000) || rd) {
                            o.write((l + "\n").toByteArray(Charsets.ISO_8859_1))
                            if (hit) inputLines++ else reads++
                        }
                    }
                }
            }
        }
    }

    private fun copyRange(r: RandomAccessFile, from: Long, count: Long, o: OutputStream) {
        r.seek(from)
        val buf = ByteArray(64 * 1024)
        var left = count
        while (left > 0) {
            val n = r.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) break
            o.write(buf, 0, n); left -= n
        }
    }

    private fun diag(ctx: Context, vm: GuestVm): String = buildString {
        val p = vm.paths
        appendLine("time: ${java.util.Date()}")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), abi ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("image: ${vm.img.name} (id ${vm.img.id}), api ${vm.img.api}")
        appendLine("state: ${vm.state}, nativeBoot=${vm.nativeBoot}, nativeUi=${vm.nativeUi}, recovery=${vm.recoveryMode}")
        val s = vm.settings
        appendLine("settings: mtMode=${s.mtMode}, touchHz=${s.touchHz}, navButtons=${s.navButtons}")
        appendLine("display: ${s.width}x${s.height}")
        appendLine("input: guest clients connected=${vm.input.connected} [${vm.input.clientInfo()}], trackball=${vm.input.trackballConnected}, events sent=${vm.input.sent}, effective mtMode=${vm.input.mtMode}")
        appendLine("strace: ${if (File(p.bin, "strace.keep").exists()) "on (run/strace.keep exists)" else "off"}")
        appendLine()
        appendLine("-- guest /dev/input and fb --")
        listOf("dev/input", "dev/graphics").forEach { d ->
            File(p.root, d).listFiles()?.sortedBy { it.name }?.forEach { appendLine("$d/${it.name}: ${kind(it)} size=${it.length()}") } ?: appendLine("$d: missing")
        }
        appendLine()
        appendLine("-- guest shim --")
        val shim = File(p.root, "system/lib/libaemushim.so")
        if (shim.isFile) {
            val bytes = shim.readBytes()
            val sha = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
            appendLine("libaemushim.so in the guest tree: ${bytes.size} bytes, sha1 $sha, mtime ${java.util.Date(shim.lastModified())}")
        } else appendLine("libaemushim.so: missing in the guest tree")
        appendLine()
        appendLine("-- run dir --")
        p.bin.listFiles()?.sortedBy { it.name }?.forEach { appendLine("${it.name}: ${kind(it)} ${it.length()} bytes, ${java.util.Date(it.lastModified())}") }
    }

    private fun kind(f: File): String = runCatching {
        val m = android.system.Os.lstat(f.absolutePath).st_mode and android.system.OsConstants.S_IFMT
        when (m) {
            android.system.OsConstants.S_IFREG -> "file"
            android.system.OsConstants.S_IFDIR -> "dir"
            android.system.OsConstants.S_IFIFO -> "FIFO"
            android.system.OsConstants.S_IFLNK -> "symlink"
            android.system.OsConstants.S_IFSOCK -> "socket"
            else -> "other(${m.toString(8)})"
        }
    }.getOrDefault("?")
}
