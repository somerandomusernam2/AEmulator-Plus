package app.aemu.core

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screenshot of the running guest, done the `adb` way:
 *   adb shell screencap -p /data/local/tmp/<name>.png
 *   adb pull /data/local/tmp/<name>.png /sdcard/Pictures/AEmulator/<name>.png
 * The pull is the same guest-path → phone-file mapping the network ADB server uses for sync (AdbServer.host).
 */
object Screenshot {
    private const val GUEST_TMP = "/data/local/tmp"
    private const val PHONE_REL = "Pictures/AEmulator"

    /** /sdcard/Pictures/AEmulator on the phone */
    fun phoneDir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AEmulator")

    /** Blocking. Returns the phone path of the saved PNG, or null on failure. */
    fun take(ctx: Context, vm: GuestVm): String? {
        val name = "aemu-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".png"
        val remote = "$GUEST_TMP/$name"
        val local = vm.adb.host(remote)

        // adb shell screencap -p <file>
        vm.guestShell("mkdir -p $GUEST_TMP; screencap -p $remote", 60_000)
        if (!ok(local)) {
            // some firmwares only write the PNG to stdout
            local.delete()
            vm.guestShell("screencap -p > $remote", 60_000)
        }
        if (!ok(local)) { local.delete(); return null }

        // adb pull
        val saved = pull(ctx, local, name)
        local.delete()
        return saved
    }

    private fun ok(f: File) = f.isFile && f.length() > 0

    private fun pull(ctx: Context, src: File, name: String): String? {
        val shown = "/sdcard/$PHONE_REL/$name"
        val dir = phoneDir()
        val dst = File(dir, name)
        val direct = runCatching {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) error("cannot create ${dir.absolutePath}")
            src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            if (dst.length() != src.length()) error("short write")
        }.isSuccess
        if (direct) {
            MediaScannerConnection.scanFile(ctx, arrayOf(dst.absolutePath), arrayOf("image/png"), null)
            return shown
        }
        dst.delete()
        // scoped storage without all-files access: MediaStore creates the folder and indexes the file
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, PHONE_REL)
            }
            val uri = runCatching { ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) }.getOrNull() ?: return null
            val wrote = runCatching {
                ctx.contentResolver.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
            }.isSuccess
            if (!wrote) { runCatching { ctx.contentResolver.delete(uri, null, null) }; return null }
            return shown
        }
        return null
    }
}
