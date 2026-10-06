package app.aemu.core

import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * Integrated `adb shell`: one persistent guest /system/bin/sh (under qemu) with stdout+stderr merged,
 * so `cd`, variables and background jobs live across commands. No pty, hence no line editing/job control.
 */
class GuestShell(private val vm: GuestVm, private val onOutput: (String) -> Unit) {
    @Volatile private var proc: Process? = null
    private var stdin: OutputStream? = null

    val alive: Boolean get() = proc?.isAlive == true

    @Synchronized fun start(): Boolean {
        if (alive) return true
        val r = vm.guestRunner
        if (!r.qemu.canExecute()) { onOutput("[no qemu]\n"); return false }
        val pb = ProcessBuilder(r.cmdline(listOf("/system/bin/sh"))).directory(vm.paths.bin).redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(r.env(mapOf("HOME" to "/data/local/tmp", "TERM" to "dumb")))
        val p = runCatching { pb.start() }.getOrElse { onOutput("[${it.message}]\n"); return false }
        proc = p; stdin = p.outputStream
        thread(name = "guest-shell-out", isDaemon = true) {
            runCatching {
                val buf = ByteArray(8192)
                val i = p.inputStream
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    onOutput(String(buf, 0, n, Charsets.UTF_8))
                }
            }
            if (proc === p) onOutput("\n[shell exited]\n")
        }
        send("cd /data/local/tmp")
        return true
    }

    /** one command line; the shell is (re)started on demand */
    fun send(line: String) {
        if (!start()) return
        runCatching { stdin?.let { it.write((line + "\n").toByteArray()); it.flush() } }
    }

    /** no pty to deliver ^C to, so the session is killed and a fresh one started */
    @Synchronized fun interrupt() {
        val p = proc; proc = null
        runCatching { p?.destroyForcibly() }
        onOutput("^C\n")
        start()
    }

    @Synchronized fun close() {
        val p = proc; proc = null
        runCatching { p?.destroyForcibly() }
    }
}
