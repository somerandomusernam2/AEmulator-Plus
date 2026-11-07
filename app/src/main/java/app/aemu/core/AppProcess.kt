package app.aemu.core

import android.system.Os
import java.io.File

/**
 * 64-bit-split and some vendor firmware ship only /system/bin/app_process32 (no app_process, or a dangling link to it),
 * while init scripts, the pm/am/input wrappers and our own helper services all say /system/bin/app_process
 * ("adb shell pm": /system/bin/app_process: not found). path() names the binary that really exists and ensureLink()
 * adds the missing app_process -> app_process32 link so that every script resolves it, whatever it calls it.
 */
object AppProcess {
    const val DEFAULT = "/system/bin/app_process"
    private const val ALT = "/system/bin/app_process32"

    /** File.isFile follows links, so a dangling app_process link counts as missing. */
    private fun has(root: File, p: String) = File(root, p.removePrefix("/")).isFile

    /** The app_process binary to run in the guest tree [root]: app_process, else app_process32, else the default name. */
    fun path(root: File): String = when {
        has(root, DEFAULT) -> DEFAULT
        has(root, ALT) -> ALT
        else -> DEFAULT
    }

    /** Makes system/bin/app_process point at app_process32 when only that exists. True when a link was created. */
    fun ensureLink(root: File): Boolean {
        if (has(root, DEFAULT) || !has(root, ALT)) return false
        val link = File(root, DEFAULT.removePrefix("/"))
        return runCatching {
            // a dangling link (or an empty leftover) is in the way
            if (runCatching { Os.lstat(link.absolutePath) }.isSuccess) link.delete()
            Os.symlink("app_process32", link.absolutePath)
            true
        }.getOrDefault(false)
    }

    private val EXEC = Regex("(?m)^(\\s*)exec (?:/system/bin/)?app_process\\b")

    /** Rewrites "exec app_process" / "exec /system/bin/app_process" in a wrapper script to the full path [ap]. */
    fun fixExec(script: String, ap: String): String = EXEC.replace(script) { it.groupValues[1] + "exec " + ap }
}
