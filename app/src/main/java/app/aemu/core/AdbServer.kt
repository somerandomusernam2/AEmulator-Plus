package app.aemu.core

import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * ADB over the network for a running VM: `adb connect <phone-ip>:5555` from a PC on the same LAN.
 * The firmware's own adbd needs USB gadget nodes and root drops, so the host speaks the ADB wire protocol itself
 * and serves shell/exec (guest sh under qemu), sync (push/pull straight on the image's files), reboot, root, remount.
 * No shell_v2 / stat_v2 features are announced, so any adb client falls back to the legacy services
 * (install = push to /data/local/tmp + pm install).
 *
 * A client has to pass the RSA handshake of a real adbd first ([AdbAuth], wire protocol in [AdbConn]): a computer not
 * allowed before becomes the [pending] question, shown by the machine's screen (or a notification that opens it when
 * the screen is not in front), and nothing is served until it is allowed. [requireAuth] off serves every client
 * without the handshake, as before it existed (adb older than 1.0.31 has none).
 */
class AdbServer(private val vm: GuestVm) {
    @Volatile var port = 0
        private set
    private var server: ServerSocket? = null
    private val conns = ConcurrentHashMap.newKeySet<Socket>()

    /** the computers allowed to connect (shared by every firmware, like a phone's adb_keys) */
    val auth = AdbAuth(File(vm.ctx.filesDir, "adb_keys"))

    private val prefs by lazy { vm.ctx.getSharedPreferences("adb", android.content.Context.MODE_PRIVATE) }
    /** a computer has to be allowed before it is served (the handshake); off: anyone on the network, as before */
    var requireAuth: Boolean
        get() = prefs.getBoolean("require_auth", true)
        set(v) { prefs.edit().putBoolean("require_auth", v).apply() }

    /** A computer seen for the first time, waiting for the person at the phone. */
    class Ask(val key: AdbAuth.Key, val from: String) {
        internal val result = java.util.concurrent.CompletableFuture<AdbAuth.Answer>()
        fun answer(a: AdbAuth.Answer) { result.complete(a) }
    }

    /** the question on the screen now, if any (one at a time: another unknown computer is refused meanwhile) */
    @Volatile var pending: Ask? = null
        private set
    private val askListeners = java.util.concurrent.CopyOnWriteArrayList<(Ask?) -> Unit>()
    /** the screen shows [pending] as it changes (null: answered or given up) */
    fun onAsk(l: (Ask?) -> Unit) { askListeners.add(l) }
    fun removeOnAsk(l: (Ask?) -> Unit) { askListeners.remove(l) }
    /** the machine's screen is in front: the question needs no notification (until the screen goes behind) */
    @Volatile var screenVisible = false
        set(v) { field = v; if (!v) pending?.let { notice(it.from) } }
    private val asking = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun ask(key: AdbAuth.Key, from: String): AdbAuth.Answer {
        if (!asking.compareAndSet(false, true)) { vm.log("adb: $from refused, another computer is waiting for an answer"); return AdbAuth.Answer.DENY }
        val a = Ask(key, from)
        try {
            pending = a
            askListeners.forEach { runCatching { it(a) } }
            if (!screenVisible) notice(from)
            return runCatching { a.result.get(ASK_SECONDS, TimeUnit.SECONDS) }.getOrDefault(AdbAuth.Answer.DENY)
        } finally {
            pending = null
            askListeners.forEach { runCatching { it(null) } }
            runCatching { vm.ctx.getSystemService(android.app.NotificationManager::class.java).cancel(NOTE_ID) }
            asking.set(false)
        }
    }

    /** the machine runs behind its notification: this one, on a channel that pops up, opens its screen and the question */
    private fun notice(from: String) = runCatching {
        val ctx = vm.ctx
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("adb", ctx.getString(app.aemu.R.string.m_adb), android.app.NotificationManager.IMPORTANCE_HIGH))
        val open = android.app.PendingIntent.getActivity(ctx, 2, android.content.Intent(ctx, app.aemu.ui.VmActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(NOTE_ID, android.app.Notification.Builder(ctx, "adb")
            .setContentTitle(ctx.getString(app.aemu.R.string.m_adb_auth_title))
            .setContentText(from)
            .setSmallIcon(app.aemu.R.drawable.ic_stat_vm)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(ASK_SECONDS * 1000)
            .build())
    }

    private fun banner(): String {
        val p = vm.props
        return "device::ro.product.name=${p.get("ro.product.name") ?: "aemu"};" +
            "ro.product.model=${p.get("ro.product.model") ?: vm.img.name};" +
            "ro.product.device=${p.get("ro.product.device") ?: "aemu"};\u0000"
    }

    val running get() = server != null

    fun start(): Boolean {
        if (server != null) return true
        for (p in 5555..5585 step 2) {
            val s = runCatching { ServerSocket(p, 4, InetAddress.getByName("0.0.0.0")).apply { reuseAddress = true } }.getOrNull() ?: continue
            server = s; port = p
            vm.log("adb: listening on ${addresses().joinToString { "$it:$p" }.ifEmpty { "port $p" }}")
            thread(name = "adb-accept", isDaemon = true) {
                while (!s.isClosed) {
                    val c = runCatching { s.accept() }.getOrNull() ?: break
                    conns.add(c)
                    thread(name = "adb-conn", isDaemon = true) {
                        runCatching { AdbConn(c, auth, { requireAuth }, ::ask, ::banner, vm::log, ::serve).run() }
                            .onFailure { if (!s.isClosed) vm.log("adb: ${it.message}") }
                        conns.remove(c); runCatching { c.close() }
                    }
                }
            }
            return true
        }
        vm.log("adb: no free port")
        return false
    }

    fun stop() {
        val s = server ?: return
        server = null; port = 0
        runCatching { s.close() }
        conns.forEach { runCatching { it.close() } }
        vm.log("adb: stopped")
    }

    // ------------------------------------------------------------------ services

    private fun serve(s: AdbStream, service: String) {
        when {
            service.startsWith("shell:") -> shell(s, service.removePrefix("shell:"), interactive = service == "shell:")
            service.startsWith("exec:") -> shell(s, service.removePrefix("exec:"), interactive = false)
            service.startsWith("sync:") -> sync(s)
            service.startsWith("reboot:") -> {
                s.out.write(ByteArray(0)); s.close()
                val reason = service.removePrefix("reboot:")
                vm.onPower?.invoke(true, if (reason == "recovery") "recovery" else "")
            }
            service.startsWith("root:") -> s.out.write("adbd is already running as root\n".toByteArray())
            service.startsWith("unroot:") -> s.out.write("adbd cannot run as non-root in AEmulator\n".toByteArray())
            service.startsWith("remount:") -> s.out.write("remount succeeded\n".toByteArray())
            else -> vm.log("adb: unsupported service $service")
        }
    }

    /** guest sh under qemu; stdout+stderr merged (legacy shell protocol has no separate stderr) */
    private fun shell(s: AdbStream, cmd: String, interactive: Boolean) {
        if (!interactive && logcat(s, cmd)) return
        val r = vm.guestRunner
        val argv = if (interactive) listOf("/system/bin/sh", "-i") else listOf("/system/bin/sh", "-c", vm.shellPrefix + cmd)
        val pb = ProcessBuilder(r.cmdline(argv)).directory(vm.paths.bin).redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(r.env(mapOf("HOME" to "/data/local/tmp", "TERM" to "dumb")))
        val p = pb.start()
        // the same PATH order and tool wrappers as for a one-shot command
        if (interactive) runCatching { p.outputStream.write((vm.shellPrefix + "\n").toByteArray()); p.outputStream.flush() }
        val pump = thread(isDaemon = true) {
            runCatching {
                val buf = ByteArray(8192)
                val o = p.outputStream
                while (true) {
                    val n = s.inp.read(buf); if (n < 0) break
                    if (interactive) {
                        // the client's terminal is raw: Enter arrives as CR; there is no pty, so echo input ourselves
                        for (i in 0 until n) if (buf[i] == '\r'.code.toByte()) buf[i] = '\n'.code.toByte()
                        s.out.write(ByteArray(n) { if (buf[it] == '\n'.code.toByte()) '\r'.code.toByte() else buf[it] } + if (buf[n - 1] == '\n'.code.toByte()) byteArrayOf('\n'.code.toByte()) else ByteArray(0))
                        if (buf.take(n).any { it.toInt() == 4 }) { o.close(); break }
                    }
                    o.write(buf, 0, n); o.flush()
                }
                if (!interactive) p.outputStream.close()
            }
            if (s.closed) p.destroy()
        }
        runCatching {
            val buf = ByteArray(16384)
            val i = p.inputStream
            while (true) {
                val n = i.read(buf); if (n < 0) break
                if (interactive) {
                    // LF → CRLF for the raw terminal
                    val o = java.io.ByteArrayOutputStream(n + 64)
                    for (k in 0 until n) { if (buf[k] == '\n'.code.toByte()) o.write('\r'.code); o.write(buf[k].toInt()) }
                    s.out.write(o.toByteArray())
                } else s.out.write(buf, 0, n)
            }
        }
        p.waitFor(2, TimeUnit.SECONDS)
        p.destroy()
        pump.interrupt()
    }

    /**
     * `logcat` served by the host from the guest's log files: the guest's own logcat expects the kernel logger
     * (2.3–4.4) or logd's reader socket (5.0+), neither exists, and it only says "logcat read failure".
     * Supports -d (dump and exit), -c (clear) and -b <buffer>; without -d it keeps following like logcat does.
     * Records carry no time or pid, so the brief format "P/Tag: message" is used.
     */
    private fun logcat(s: AdbStream, raw: String): Boolean {
        // `adb logcat` sends: export ANDROID_LOG_TAGS="" ; exec logcat <args>
        val cmd = raw.trim().replaceFirst(Regex("^export ANDROID_LOG_TAGS=(\"[^\"]*\"|'[^']*'|\\S*)\\s*;\\s*"), "").removePrefix("exec ").trim()
        val args = cmd.split(Regex("\\s+"))
        if (args.firstOrNull() != "logcat" || cmd.contains('|') || cmd.contains('>') || cmd.contains(';')) return false
        val buffers = ArrayList<String>()
        var dump = false
        var i = 1
        while (i < args.size) {
            when (args[i]) {
                "-d" -> dump = true
                "-c" -> { GuestLog.clear(vm.paths.root); return true }
                "-b" -> { args.getOrNull(i + 1)?.let { b -> buffers += if (b == "all") listOf("main", "system", "radio") else b.split(',') }; i++ }
                "-v", "-f", "-r", "-n", "-t", "-T", "-s", "-g" -> if (args[i] != "-s" && args[i] != "-g") i++
            }
            i++
        }
        val names = buffers.ifEmpty { listOf("main", "system") }.distinct()
        val offsets = HashMap<String, Long>()
        fun emit(name: String) {
            val f = File(vm.paths.root, "dev/log/$name")
            val len = f.length()
            var from = offsets[name] ?: 0L
            if (len < from) from = 0L   // the log was cleared
            if (len == from) return
            val data = runCatching {
                java.io.RandomAccessFile(f, "r").use { r -> r.seek(from); ByteArray((len - from).coerceAtMost(4L shl 20).toInt()).also { r.readFully(it) } }
            }.getOrNull() ?: return
            offsets[name] = from + data.size
            val sb = StringBuilder()
            for (rec in GuestLog.parse(data)) sb.append(rec.prio).append('/').append(rec.tag).append(": ").append(rec.msg.trimEnd('\n')).append('\n')
            if (sb.isNotEmpty()) s.out.write(sb.toString().toByteArray())
        }
        names.forEach { emit(it) }
        if (dump) return true
        while (!s.closed) {
            Thread.sleep(250)
            names.forEach { emit(it) }
        }
        return true
    }

    /** guest path → file on the phone: memory-card paths go to the shared card folder, the rest into the image's root */
    private fun host(guest: String): File {
        val p = "/" + guest.split('/').filter { it.isNotEmpty() && it != "." }
            .fold(ArrayList<String>()) { acc, seg -> if (seg == "..") { if (acc.isNotEmpty()) acc.removeAt(acc.size - 1) } else acc.add(seg); acc }
            .joinToString("/")
        val sd = Sdcard.hostDir(vm.paths)
        if (sd != null) for (pre in Sdcard.guestPaths(vm.img)) {
            val g = "/$pre"
            if (p == g) return sd
            if (p.startsWith("$g/")) return File(sd, p.removePrefix("$g/"))
        }
        return if (p == "/") vm.paths.root else File(vm.paths.root, p.removePrefix("/"))
    }

    private fun sync(s: AdbStream) {
        val i = s.inp
        val o = java.io.BufferedOutputStream(s.out, 512 * 1024)
        val h = ByteArray(8)
        while (true) {
            if (!readFully(i, h)) break
            val id = String(h, 0, 4, Charsets.ISO_8859_1)
            val len = le(h, 4)
            if (len !in 0..SYNC_PATH_MAX) {
                val m = "path too long".toByteArray()
                o.write(id4("FAIL")); o.write(le4(m.size)); o.write(m); o.flush(); return
            }
            val arg = ByteArray(len).also { if (!readFully(i, it)) return }
            when (id) {
                "STAT" -> {
                    val f = host(String(arg))
                    val st = runCatching { android.system.Os.stat(f.absolutePath) }.getOrNull()
                    o.write(id4("STAT")); o.write(le4(st?.st_mode ?: 0)); o.write(le4((st?.st_size ?: 0).toInt())); o.write(le4((st?.st_mtime ?: 0).toInt()))
                }
                "LIST" -> {
                    val d = host(String(arg))
                    for (name in listOf(".", "..") + (d.list()?.sorted() ?: emptyList())) {
                        val f = if (name == "." || name == "..") d else File(d, name)
                        val st = runCatching { android.system.Os.lstat(f.absolutePath) }.getOrNull() ?: continue
                        val nb = name.toByteArray()
                        o.write(id4("DENT")); o.write(le4(st.st_mode)); o.write(le4(st.st_size.toInt())); o.write(le4(st.st_mtime.toInt()))
                        o.write(le4(nb.size)); o.write(nb)
                    }
                    o.write(id4("DONE")); o.write(ByteArray(16))
                }
                "SEND" -> {
                    val spec = String(arg)
                    val path = spec.substringBeforeLast(',')
                    val mode = spec.substringAfterLast(',', "420").toIntOrNull() ?: 420
                    val f = host(path)
                    var err: String? = null
                    runCatching { f.parentFile?.mkdirs(); if (f.isDirectory) error("is a directory") }.onFailure { err = it.message }
                    val fo = if (err == null) runCatching { f.outputStream().buffered(65536) }.onFailure { err = it.message }.getOrNull() else null
                    while (true) {
                        if (!readFully(i, h)) { fo?.close(); return }
                        val cid = String(h, 0, 4, Charsets.ISO_8859_1); val n = le(h, 4)
                        if (cid == "DATA") {
                            if (n !in 0..SYNC_DATA_MAX) { fo?.close(); return }
                            val chunk = ByteArray(n); if (!readFully(i, chunk)) { fo?.close(); return }
                            if (fo != null && err == null) runCatching { fo.write(chunk) }.onFailure { err = it.message }
                        } else if (cid == "DONE") {
                            runCatching { fo?.close() }
                            if (err == null) {
                                f.setReadable(true, false)
                                if (mode and 0x49 != 0) f.setExecutable(true, false)
                                if (n > 0) f.setLastModified(n.toLong() * 1000)
                            }
                            break
                        } else { err = "unexpected $cid"; fo?.close(); break }
                    }
                    if (err == null) { o.write(id4("OKAY")); o.write(ByteArray(4)) }
                    else { val m = "${f.name}: $err".toByteArray(); o.write(id4("FAIL")); o.write(le4(m.size)); o.write(m) }
                }
                "RECV" -> {
                    val f = host(String(arg))
                    if (!f.isFile) {
                        val m = "remote object '${String(arg)}' does not exist".toByteArray()
                        o.write(id4("FAIL")); o.write(le4(m.size)); o.write(m)
                    } else {
                        f.inputStream().use { fi ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = fi.read(buf); if (n <= 0) break
                                o.write(id4("DATA")); o.write(le4(n)); o.write(buf, 0, n)
                            }
                        }
                        o.write(id4("DONE")); o.write(ByteArray(4))
                    }
                }
                "QUIT" -> { o.flush(); return }
                else -> {
                    val m = "unknown sync command $id".toByteArray()
                    o.write(id4("FAIL")); o.write(le4(m.size)); o.write(m); o.flush(); return
                }
            }
            o.flush()
        }
    }

    companion object {
        /** adbd's own limits on a sync path and a sync data chunk */
        private const val SYNC_PATH_MAX = 1024
        private const val SYNC_DATA_MAX = 64 * 1024
        /** how long a computer waits for the person at the phone (adbd waits for ever; the client can connect again) */
        private const val ASK_SECONDS = 120L
        private const val NOTE_ID = 0xadb
        private fun le(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).int
        private fun le4(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        private fun id4(s: String) = s.toByteArray(Charsets.ISO_8859_1)

        /** the phone's LAN addresses (IPv4), for "adb connect" */
        fun addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet4Address>().map { it.hostAddress ?: "" }
                .filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
    }
}
