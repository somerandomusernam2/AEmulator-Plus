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
    private const val FBTEST_ELF = "7f454c4601010100000000000000000002002800010000005400010034000000000000000002000534002000010000000000000001000000000000000000010000000100d8000000d8000000070000000010000068008fe20210a0e30570a0e3000000ef000050e30200a0b3120000ba0040a0e10000a0e34b19a0e30320a0e30130a0e30050a0e3c070a0e3000000ef010a70e30300a0830700008a0060a0e14b1aa0e3013086e00020e0e3042086e4030056e1fcffff3a0000a0e30170a0e3000000ef2f6465762f67726170686963732f666230000000"
    private val INPUT_LINE = Regex("/dev/input|EVIOC|event[0-9]|epoll_(create|ctl)|input_event|ENOTTY|/dev/tty|/dev/graphics|ioctl\\(\\d+,(0x|FBIO)46[0-9a-fA-F]{2}|FBIO|mmap2?\\(.*MAP_SHARED")
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
                entry(zip, "guest-data-perms.txt") { it.write(runCatching { dataPerms(vm.paths.root, vm.paths.owners) }.getOrElse { "[failed: $it]" }.toByteArray()) }
                // Added for AEmulator Plus (2026-11-11): the whole guest tree as a recursive file list (no contents)
                entry(zip, "guest-file-list.txt") { o -> guestFileList(vm.paths.root, o) }
                entry(zip, "ui-log.txt") { o -> vm.lines().forEach { o.write((it + "\n").toByteArray()) } }
                entry(zip, "input-events.txt") { o ->
                    o.write("last events sent to the guest (uptime ms, type: 0=SYN 1=KEY 3=ABS, code, value)\n".toByteArray())
                    vm.input.recentEvents().forEach { o.write((it + "\n").toByteArray()) }
                }
                entry(zip, "guest-logcat.txt") { o ->
                    runCatching { o.bufferedWriter().apply { GuestLog.writeExport(vm.paths.root, vm.paths.bin, this); flush() } }
                }
                // what is on screen right now: layers (format, alpha, size), windows (flags, hardware acceleration) and the
                // top activity. This shows what covers the UI when the guest runs but the screen looks black.
                // a boot that never gets going has no RUNNING state to trigger the dumps below: what its processes are doing
                // right now (CPU time over three seconds, blocked system call, wait channel) says "spinning" from "waiting"
                if (vm.state != GuestVm.State.RUNNING) {
                    // a boot that stalls (system_server's android.display thread in AMS.finishBooting) needs the Java stacks too
                    if (vm.state == GuestVm.State.BOOTING) entry(zip, "guest-threads.txt") { o -> o.write(guestThreadDump(vm).toByteArray(Charsets.ISO_8859_1)) }
                    entry(zip, "guest-boot-processes.txt") { o -> o.write(bootProcessDump(vm).toByteArray(Charsets.ISO_8859_1)) }
                }
                if (vm.state == GuestVm.State.RUNNING) {
                    // Dalvik thread dumps (SIGQUIT) of the keyboard, system_server and every other guest app, taken first so the
                    // stacks show what the frozen keyboard's main thread is blocked on. Sent from the host: the Android 1.5 toolbox
                    // has no working ps/tail, so a guest-side `kill -3` pipeline dumps nothing there (guest-threads.txt came back empty).
                    entry(zip, "guest-threads.txt") { o -> o.write(guestThreadDump(vm).toByteArray(Charsets.ISO_8859_1)) }
                    if (!vm.recoveryMode) for ((file, cmd) in listOf(
                        // who the input dispatcher is waiting for, and the IME's own view of the focused client
                        "guest-input-method.txt" to "dumpsys input_method",
                        "guest-sf-dump.txt" to "dumpsys SurfaceFlinger",
                        "guest-window-dump.txt" to "dumpsys window windows", "guest-activity-top.txt" to "dumpsys activity top")) {
                        entry(zip, file) { o ->
                            val (code, text) = vm.guestRunner.run(listOf("/system/bin/sh", "-c",
                                "export PATH=/system/bin:/system/xbin:\$PATH; $cmd"), 15_000)
                            o.write("# $cmd (exit $code)\n".toByteArray()); o.write(text.toByteArray())
                        }
                    }
                }
                // does the /data/user/0 -> ../data link resolve inside the guest? (root view: ls errors show the errno)
                if (vm.state == GuestVm.State.RUNNING && !vm.recoveryMode) entry(zip, "guest-fs-probe.txt") { o ->
                    val cmd = "id; for p in /data /data/data /data/user /data/user/0 /data/user/0/ /data/user/0/com.android.providers.contacts " +
                        "/data/user/0/com.android.providers.contacts/cache /data/user/0/com.android.providers.contacts/databases " +
                        "/data/data/com.android.providers.contacts /data/data/com.android.providers.contacts/cache " +
                        "/data/data/com.android.providers.contacts/databases /data/data/com.google.android.gsf; do echo == \$p; ls -ld \$p 2>&1; done; " +
                        "echo == readlink; readlink /data/user/0 2>&1; echo == ls /data/user; ls -l /data/user 2>&1; " +
                        "echo == ls /data/user/0/com.android.providers.contacts; ls -l /data/user/0/com.android.providers.contacts 2>&1; " +
                        "echo == ls /data/data/com.android.providers.contacts/databases; ls -l /data/data/com.android.providers.contacts/databases 2>&1"
                    val (code, text) = vm.guestRunner.run(listOf("/system/bin/sh", "-c", "export PATH=/system/bin:/system/xbin:\$PATH; $cmd"), 15_000)
                    o.write("# exit $code\n".toByteArray()); o.write(text.toByteArray())
                }
                // Does a plain write through a shared mmap of fb0 (exactly what SurfaceFlinger does) reach the file the app displays?
                // A 216-byte ARM program maps fb0 read/write shared and fills the first 16bpp page with white; it never truncates the file.
                // If the screen turns white / the host sees non-zero bytes, the mapping and the display path work and the problem is SurfaceFlinger's drawing.
                if (vm.state == GuestVm.State.RUNNING && !vm.recoveryMode) entry(zip, "guest-fb-map-test.txt") { o ->
                    val bin = File(vm.paths.root, "data/local/tmp/aemu-fbtest")
                    bin.parentFile?.mkdirs()
                    bin.writeBytes(FBTEST_ELF.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                    bin.setExecutable(true, false); bin.setReadable(true, false)
                    val (code, text) = vm.guestRunner.run(listOf("/data/local/tmp/aemu-fbtest"), 10_000)
                    o.write("# guest exit $code (0 = mapped and filled; 2 = open failed; 3 = mmap failed)\n$text\n".toByteArray())
                    var nz = 0L
                    runCatching { java.io.RandomAccessFile(vm.paths.fb, "r").use { r -> val b = ByteArray(65536); var left = r.length(); while (left > 0) { val n = r.read(b); if (n <= 0) break; for (i in 0 until n) if (b[i].toInt() != 0) nz++; left -= n } } }
                    o.write("host sees $nz non-zero bytes in fb0 after the test (expected 307200 if the shared mapping reaches the file)\n".toByteArray())
                    bin.delete()
                }
                // recovery mode: the recovery's own log and what it has mapped (is fb0 mmap()ed? is any /dev/input node open?)
                if (vm.recoveryMode) {
                    val rlog = File(RecoveryImage.dir(vm.paths), "tmp/recovery.log")
                    if (rlog.isFile) addFile(zip, "recovery/tmp/recovery.log", rlog)
                    entry(zip, "guest-recovery-proc.txt") { o -> o.write(recoveryProcDump(vm).toByteArray(Charsets.ISO_8859_1)) }
                }
                entry(zip, "guest-sms-db.txt") { it.write(smsDb(ctx, vm).toByteArray()) }
                vm.paths.bin.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { f -> addFile(zip, "run/${f.name}", f) }
                // fatal-signal reports of the native runner (pc, lr, fault address, registers): where a native service died;
                // aemu-xattr.log is the runner's debug trace (failing /proc/self path calls, "proc-trace nr=N path ret=-errno")
                listOf("dev/aemu_crash.log", "dev/.aemu_crash.log", "dev/.aemu-trace.log", "dev/aemu-xattr.log").forEach { rel ->
                    val f = File(vm.paths.root, rel)
                    // Modified for AEmulator Plus (2026-11-11): the syscall trace goes in whole up to 50 MiB (addFile would cut out
                    // the middle, which is where hyos_spawner's lines are); bigger than that it is cut like any big file
                    if (f.isFile && rel == "dev/.aemu-trace.log" && f.length() <= (50L shl 20))
                        entry(zip, "guest/$rel") { o -> RandomAccessFile(f, "r").use { r -> copyRange(r, 0, f.length(), o) } }
                    else if (f.isFile) addFile(zip, "guest/$rel", f)
                }
                // files the factory test program writes itself
                listOf("cache/tki/tki.log", "data/mmi_ip.png", "data/local/tmp/mmi.log").forEach { rel ->
                    val f = File(vm.paths.root, rel)
                    if (f.isFile && f.length() < 8L shl 20) addFile(zip, "guest/$rel", f)
                }
                // stack dumps (ANR / boot-stall SIGQUIT): the first thing needed to see where system_server is blocked
                File(vm.paths.root, "data/anr").listFiles()?.filter { it.isFile && it.length() < (8L shl 20) }
                    ?.forEach { addFile(zip, "guest/data/anr/${it.name}", it) }
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

    /**
     * Every host process of this image (app_process of the zygote and its children, native programs, qemu): per thread the
     * state letter, the host system call it is blocked in and the wait channel, plus the CPU ticks (utime+stime) used in the
     * three seconds between two samples. A process that burns ticks is looping, one that burns none is waiting for something.
     * Does not signal anything.
     */
    private fun bootProcessDump(vm: GuestVm): String {
        val marker = "/images/${vm.img.id}/"
        fun ticks(t: File): Long = runCatching {
            val f = File(t, "stat").readText().substringAfterLast(')').trim().split(' ')
            f[11].toLong() + f[12].toLong()          // fields 14 and 15 of stat: utime, stime
        }.getOrDefault(-1L)
        fun procs() = File("/proc").listFiles()?.mapNotNull { d ->
            val pid = d.name.toIntOrNull() ?: return@mapNotNull null
            val cmd = runCatching { String(File(d, "cmdline").readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: return@mapNotNull null
            if (pid == android.os.Process.myPid() || cmd.isEmpty() || !(cmd.contains(marker) || cmd.contains("/system/bin/"))) null else pid to d
        }?.sortedBy { it.first } ?: emptyList()
        val first = procs().associate { (pid, d) -> pid to (File(d, "task").listFiles()?.associate { it.name to ticks(it) } ?: emptyMap()) }
        Thread.sleep(3000)
        val sb = StringBuilder("== guest processes while the VM is ${vm.state} (host view)\n" +
            "pid program / tid:comm:state:syscall:wchan:cpu ticks in 3 s\n")
        for ((pid, d) in procs()) {
            val cmd = runCatching { String(File(d, "cmdline").readBytes(), Charsets.ISO_8859_1) }.getOrDefault("")
            sb.append("$pid ").append(cmd.split('\u0000').filter { it.isNotEmpty() }.joinToString(" ").replace(marker, "<img>/").take(200)).append('\n')
            File(d, "task").listFiles()?.sortedBy { it.name.toIntOrNull() ?: 0 }?.forEach { t ->
                val name = runCatching { File(t, "comm").readText().trim() }.getOrDefault("?")
                val st = runCatching { File(t, "stat").readText().substringAfterLast(')').trim().firstOrNull() }.getOrNull() ?: '?'
                val sc = runCatching { File(t, "syscall").readText().trim().substringBefore(' ') }.getOrDefault("?")
                val wc = runCatching { File(t, "wchan").readText().trim() }.getOrDefault("?")
                val a = first[pid]?.get(t.name) ?: -1L; val b = ticks(t)
                sb.append("   ${t.name}:$name:$st:$sc:$wc:${if (a >= 0 && b >= 0) b - a else "?"}\n")
            }
            runCatching {
                File(d, "fd").listFiles()?.sortedBy { it.name.toIntOrNull() ?: 0 }?.take(40)?.forEach { f ->
                    sb.append("   fd ${f.name} -> ${runCatching { android.system.Os.readlink(f.path) }.getOrDefault("?")}\n")
                }
            }
        }
        return sb.toString()
    }

    /**
     * SIGQUIT to every guest app process (zygote children: the keyboard, system_server, ...), then the stack dumps that Dalvik's
     * signal catcher appended to /data/anr/traces.txt. The guest processes are ordinary host processes of this app's uid, so the
     * host can signal them directly; qemu hands SIGQUIT to the guest. Returns the host-side thread states plus only the bytes
     * this call added to traces.txt (the file also holds older crash dumps).
     */
    private fun guestThreadDump(vm: GuestVm): String {
        val traces = File(vm.paths.root, "data/anr/traces.txt")
        val before = if (traces.isFile) traces.length() else 0L
        val marker = "/images/${vm.img.id}/"
        val sb = StringBuilder("== guest app processes (host view: pid comm, thread tid:comm:state)\n")
        var signalled = 0
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            val cmd = runCatching { String(File(d, "cmdline").readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: return@forEach
            // Modified for AEmulator Plus (2026-11-07): the native arm64 engine's command line is "<libaemu64_N.so> <guest path> <argv...>"
            // and has no image root in it, so the marker never matched: "SIGQUIT sent to 0 processes", no stack dump of a
            // system_server that hangs (Android 12 / 13 stop right after StartFontManagerService) and no word about it anywhere.
            val native = vm.engine == Engine.N64 && cmd.contains("libaemu64_")
            // Modified for AEmulator Plus (2026-11-10): the zygote renames a forked system_server's command line to just
            // "system_server" (no app_process path, no image root), so the filter below never matched it: the dump went to the
            // secondary zygote only and a system_server hung in StartFontManagerService left no stack at all. Match it by name.
            val systemServer = cmd.substringBefore('\u0000') == "system_server"
            if (!systemServer && (!(cmd.contains(marker) || native) || !cmd.contains("/system/bin/app_process") || cmd.contains("aemu-stubs.jar") ||
                cmd.contains("aemu-bt.jar") || cmd.contains(WifiEmu.JAR_NAME) || cmd.contains("aemu-sensorhub.jar"))) return@forEach
            val comm = runCatching { File(d, "comm").readText().trim() }.getOrDefault("?")
            val threads = runCatching {
                File(d, "task").listFiles()?.sortedBy { it.name.toIntOrNull() ?: 0 }?.joinToString(" ") { t ->
                    val name = runCatching { File(t, "comm").readText().trim() }.getOrDefault("?")
                    // /proc/<pid>/task/<tid>/stat: "tid (name) S ..." - the state letter follows the last ')'
                    val st = runCatching { File(t, "stat").readText().substringAfterLast(')').trim().firstOrNull() }.getOrNull() ?: '?'
                    "${t.name}:$name:$st"
                }
            }.getOrNull() ?: "?"
            sb.append("$pid $comm  $threads\n")
            if (runCatching { android.os.Process.sendSignal(pid, 3) }.isSuccess) signalled++
        }
        sb.append("SIGQUIT sent to $signalled processes\n\n")
        sb.append(nativeServiceDump(marker, vm.engine == Engine.N64))
        // the catcher writes each process's stacks one after another; give them time (the emulated guest is slow)
        Thread.sleep(4000)
        sb.append("== new /data/anr/traces.txt content\n")
        runCatching {
            RandomAccessFile(traces, "r").use { r ->
                val len = r.length()
                val from = if (len >= before) before else 0L        // shorter than before = the guest truncated it: take it all
                val n = minOf(len - from, 900_000L).toInt()
                val b = ByteArray(n); r.seek(len - n); r.readFully(b)
                sb.append(String(b, Charsets.ISO_8859_1))
            }
        }.onFailure { sb.append("[cannot read traces.txt: $it]\n") }
        return sb.toString()
    }

    /**
     * The guest's native programs (bootanimation, peripheralman, surfaceflinger, ...): for each thread its state, the host
     * system call it is blocked in (the first word of /proc/<pid>/task/<tid>/syscall: futex, ioctl, nanosleep, read, ...) and
     * its wait channel, and the files the process holds open. Read-only; what the host will not show is left as "?".
     * Made to see why a service does not exit (the Android Things boot animation keeps the board's I2C bus claimed).
     */
    private fun nativeServiceDump(marker: String, nativeEngine: Boolean = false): String {
        val sb = StringBuilder("== guest native programs (host view: pid, program, then tid:comm:state:syscall:wchan per thread, then open files)\n")
        File("/proc").listFiles()?.sortedBy { it.name.toIntOrNull() ?: 0 }?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            val cmd = runCatching { String(File(d, "cmdline").readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: return@forEach
            // the native engine's command line is "<qemu> <guest path of the program> <argv...>" - the guest path has no image
            // root in it, so the image cannot be recognised by it; every process of this app's uid that is not the app itself or
            // a Dalvik process (those are in the list above) is a guest program
            // (the native engine's Dalvik processes - zygote, system_server - are listed here too, with the syscall and wait
            // channel of every thread: the list above cannot show where a hung system_server is blocked)
            if (pid == android.os.Process.myPid() || cmd.isEmpty() || (!nativeEngine && cmd.contains("/system/bin/app_process"))) return@forEach
            val prog = cmd.split('\u0000').filter { it.isNotEmpty() }.joinToString(" ").replace(marker, "<img>/").take(160)
            sb.append("$pid $prog\n")
            File(d, "task").listFiles()?.sortedBy { it.name.toIntOrNull() ?: 0 }?.forEach { t ->
                val name = runCatching { File(t, "comm").readText().trim() }.getOrDefault("?")
                val st = runCatching { File(t, "stat").readText().substringAfterLast(')').trim().firstOrNull() }.getOrNull() ?: '?'
                val sc = runCatching { File(t, "syscall").readText().trim().substringBefore(' ') }.getOrDefault("?")
                val wc = runCatching { File(t, "wchan").readText().trim() }.getOrDefault("?")
                sb.append("   ${t.name}:$name:$st:$sc:$wc\n")
            }
            val fds = runCatching {
                File(d, "fd").listFiles()?.sortedBy { it.name.toIntOrNull() ?: 0 }?.take(60)?.joinToString("\n") { f ->
                    "   fd ${f.name} -> " + runCatching { android.system.Os.readlink(f.path) }.getOrDefault("?")
                }
            }.getOrNull()
            if (fds != null) sb.append(fds).append('\n')
        }
        return sb.append('\n').toString()
    }

    /** The running recovery's memory map (fb0 / input / graphics lines only) and its threads' syscalls. */
    private fun recoveryProcDump(vm: GuestVm): String = buildString {
        val marker = "/images/${vm.img.id}/"
        var found = 0
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            val cmd = runCatching { String(File(d, "cmdline").readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: return@forEach
            if (!cmd.contains("/sbin/recovery") || !cmd.contains(marker)) return@forEach
            found++
            appendLine("== pid $pid: ${cmd.split('\u0000').filter { it.isNotEmpty() }.joinToString(" ").replace(marker, "<img>/").take(200)}")
            appendLine("-- maps mentioning fb0 / input / graphics")
            runCatching { File(d, "maps").readLines().filter { it.contains("fb0") || it.contains("input") || it.contains("graphics") } }
                .getOrDefault(emptyList()).forEach { appendLine(it.replace(marker, "<img>/")) }
            appendLine("-- open files")
            File(d, "fd").listFiles()?.sortedBy { it.name.toIntOrNull() ?: 0 }?.forEach { f ->
                val t = runCatching { android.system.Os.readlink(f.path) }.getOrDefault("?")
                if (!t.endsWith(".png")) appendLine("fd ${f.name} -> ${t.replace(marker, "<img>/")}")
            }
        }
        if (found == 0) appendLine("no recovery process found")
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
        appendLine("app: " + runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).let { "${it.versionName} (code ${it.longVersionCode}), updated ${java.util.Date(it.lastUpdateTime)}" } }.getOrDefault("unknown"))
        appendLine("time: ${java.util.Date()}")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), abi ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("image: ${vm.img.name} (id ${vm.img.id}), api ${vm.img.api}")
        appendLine("state: ${vm.state}, nativeBoot=${vm.nativeBoot}, nativeUi=${vm.nativeUi}, recovery=${vm.recoveryMode}")
        val s = vm.settings
        appendLine("settings: mtMode=${s.mtMode}, touchHz=${s.touchHz}, navButtons=${s.navButtons}")
        appendLine("radio: enabled=${s.radio}, cdma=${s.cdma ?: "auto"}, networkType=${NetworkType.label(s.networkType)}")
        appendLine("display: ${s.width}x${s.height}")
        appendLine("input: guest clients connected=${vm.input.connected} [${vm.input.clientInfo()}], trackball=${vm.input.trackballConnected}, ${vm.input.inputModeInfo()}, events sent=${vm.input.sent}, effective mtMode=${vm.input.mtMode}")
        appendLine("strace: ${if (File(p.bin, "strace.keep").exists()) "on (run/strace.keep exists)" else "off"}")
        appendLine()
        appendLine("-- guest /dev/input and fb --")
        listOf("dev/input", "dev/graphics").forEach { d ->
            File(p.root, d).listFiles()?.sortedBy { it.name }?.forEach { appendLine("$d/${it.name}: ${kind(it)} size=${it.length()}") } ?: appendLine("$d: missing")
        }
        appendLine()
        appendLine("-- fb0 content (what the guest has actually drawn) --")
        runCatching {
            val f = if (vm.recoveryMode) RecoveryImage.fb(p) else p.fb
            if (vm.recoveryMode) appendLine("(recovery mode: reading ${f.path.substringAfter("/images/")}, not the system tree's fb0)")
            val w = vm.settings.width; val h = vm.settings.height
            val page16 = w.toLong() * h * 2
            RandomAccessFile(f, "r").use { r ->
                val len = r.length()
                val buf = ByteArray(65536)
                var pos = 0L; var nzFirst = 0L; var nzSecond = 0L; var first = -1L; var last = -1L
                val distinct = HashSet<Int>()
                while (pos < len) {
                    val n = r.read(buf); if (n <= 0) break
                    for (i in 0 until n) if (buf[i].toInt() != 0) {
                        val at = pos + i
                        if (at < page16) nzFirst++ else nzSecond++
                        if (first < 0) first = at
                        last = at
                    }
                    if (distinct.size < 64) for (i in 0 until n - 1 step 2) { distinct.add((buf[i].toInt() and 0xff) or ((buf[i + 1].toInt() and 0xff) shl 8)); if (distinct.size >= 64) break }
                    pos += n
                }
                appendLine("size=$len, 16bpp page=$page16 bytes ($w x $h), non-zero bytes: first 16bpp page=$nzFirst, rest=$nzSecond, first non-zero at $first, last at $last, distinct 16-bit values (capped 64)=${distinct.size}")
                r.seek(0); val head = ByteArray(32); r.read(head)
                appendLine("first 32 bytes: ${head.joinToString("") { "%02x".format(it) }}")
            }
        }.onFailure { appendLine("[fb0 read failed: $it]") }
        appendLine()
        appendLine("-- hwui renderer properties (the guest's live property area) --")
        runCatching {
            val a = PropArea.open(p.props)
            if (a == null) appendLine("[property area not recognized]")
            else {
                for (k in listOf("debug.hwui.renderer", "ro.hwui.use_vulkan", "debug.renderengine.backend", "ro.hardware.vulkan",
                    "ro.kernel.qemu", "ro.kernel.qemu.gles", "qemu.gles", "ro.hardware.egl",
                    "persist.sys.background_blur_supported", "persist.sys.rust_runtime_active", "rust.runtime_active", "init.svc.hyos_spawner"))
                    appendLine("$k=${a.get(k) ?: "<unset>"}")
                a.close()
            }
        }.onFailure { appendLine("[property read failed: $it]") }
        appendLine()
        appendLine("-- hwui: property names read by the guest's libhwui.so --")
        runCatching {
            val rx = Regex("[a-z][a-z0-9_]*(\\.[a-z0-9_]+){1,6}")
            val want = Regex("(hwui|vulkan|renderer|skia|graphite|pipeline|gpu)", RegexOption.IGNORE_CASE)
            val roots = Regex("^(debug|ro|persist|sys|vendor|dalvik|ctl|init)\\.")
            for (path in listOf("system/lib64/libhwui.so", "system/lib64/libhwui_miui.so")) {
                val f = File(p.root, path)
                if (!f.isFile) { appendLine("$path: missing"); continue }
                val found = java.util.TreeSet<String>()
                val cur = StringBuilder()
                f.inputStream().buffered(1 shl 16).use { ins ->
                    while (true) {
                        val c = ins.read(); if (c < 0) break
                        if (c in 0x21..0x7e) cur.append(c.toChar())
                        else {
                            if (cur.length in 8..80) { val t = cur.toString(); if (roots.containsMatchIn(t) && want.containsMatchIn(t) && rx.matches(t)) found.add(t) }
                            cur.setLength(0)
                        }
                    }
                }
                appendLine("$path: ${f.length()} bytes, ${found.size} names")
                found.forEach { appendLine("  $it") }
            }
        }.onFailure { appendLine("[libhwui scan failed: $it]") }
        appendLine()
        appendLine("-- HyperOS Rust runtime (config, init services, files) --")
        runCatching {
            val dirs = listOf("product/etc/permissions", "system/product/etc/permissions", "system_ext/etc/permissions", "system/system_ext/etc/permissions", "system/etc/permissions", "vendor/etc/permissions")
            val seen = HashSet<String>()
            for (d in dirs) File(p.root, d).listFiles().orEmpty().filter { it.name.contains("rust", true) }.forEach {
                if (seen.add(it.canonicalPath)) { appendLine("== ${it.relativeTo(p.root).path} (${it.length()} bytes)"); appendLine(it.readText().take(4000)) }
            }
            File(p.root, "system/.aemu-parked").listFiles().orEmpty().filter { it.name.contains("rust", true) }.forEach { appendLine("parked: ${it.name} (${it.length()} bytes)") }
            val rcDirs = listOf("system/etc/init", "system/system/etc/init", "system_ext/etc/init", "system/system_ext/etc/init", "product/etc/init", "system/product/etc/init", "vendor/etc/init", "system/vendor/etc/init", "odm/etc/init")
            for (d in rcDirs) File(p.root, d).listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".rc") }.forEach { rc ->
                val txt = runCatching { rc.readText() }.getOrDefault("")
                if (txt.contains("rust", true)) {
                    appendLine("== ${rc.relativeTo(p.root).path}")
                    // the service blocks that mention rust
                    txt.split(Regex("(?m)^(?=service )")).filter { it.contains("rust", true) }.forEach { appendLine(it.trim().take(1200)) }
                }
            }
            val hits = ArrayList<String>()
            for (top in listOf("system/bin", "system/system/bin", "system_ext/bin", "system/system_ext/bin", "product/bin", "vendor/bin", "system/framework", "system_ext/framework", "system/system_ext/framework", "system/lib64", "system_ext/lib64", "system/system_ext/lib64", "system/priv-app", "system_ext/priv-app", "product/priv-app", "product/app", "system_ext/app"))
                File(p.root, top).listFiles().orEmpty().filter { it.name.contains("rust", true) }.forEach { hits += "${it.relativeTo(p.root).path} (${if (it.isDirectory) "dir" else "${it.length()} bytes"})" }
            appendLine("files named *rust*: ${hits.size}")
            hits.sorted().take(80).forEach { appendLine("  $it") }
            appendLine("sockets in dev/socket: " + (File(p.root, "dev/socket").list()?.sorted()?.joinToString(", ") ?: "none"))
        }.onFailure { appendLine("[rust runtime scan failed: $it]") }
        appendLine()
        appendLine("-- guest shim --")
        val shim = File(p.root, "system/lib/libaemushim.so")
        if (shim.isFile) {
            val bytes = shim.readBytes()
            val sha = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
            appendLine("libaemushim.so in the guest tree: ${bytes.size} bytes, sha1 $sha, mtime ${java.util.Date(shim.lastModified())}")
        } else appendLine("libaemushim.so: missing in the guest tree")
        for (path in listOf(GuestPreload.XATTR, GuestPreload.XATTR_VENDOR)) {
            val xa = File(p.root, path.removePrefix("/"))
            appendLine("$path: " + (if (xa.isFile) "${xa.length()} bytes" else "missing in the guest tree") +
                ", 32-bit-only tree=${isPure32Tree(p.root)}")
        }
        run {
            val g = File(p.root, GuestPreload.GUARD.removePrefix("/"))
            val st = BinderGuard.state(p.bin)
            appendLine("${GuestPreload.GUARD}: " + (if (g.isFile) "${g.length()} bytes" else "missing in the guest tree") +
                ", record: " + (st?.let { "armed=${it.armed} ineffective=${it.ineffective} daemon=${it.binderd} since=${it.since} bare=${it.bare}" } ?: "none (not armed)") +
                ", events this run: ${BinderGuard.events(p.bin)}")
            File(p.bin, BinderGuard.LOG).takeIf { it.isFile }?.useLines { ls -> ls.take(40).forEach { appendLine("  $it") } }
        }
        appendLine()
        // "No original dex files found" for services.jar and the other system_server jars means the precompiled code of the
        // framework was missing or refused: which files are there, how big, since when, writable or not, and what ART
        // keeps in dalvik-cache next to them (a boot image made there is preferred to the firmware's, and the firmware's
        // odex files, built for the other one, are then all refused) tells which of the two it was.
        appendLine("-- framework precompiled code (oat) and dalvik-cache --")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        fun listOat(rel: String, only: (File) -> Boolean) {
            val dir = File(p.root, rel)
            val fs = dir.listFiles()?.filter(only)?.sortedBy { it.name }
            if (fs == null) { appendLine("$rel: no such directory"); return }
            appendLine("$rel: ${fs.size} files, directory writable=${dir.canWrite()}")
            fs.forEach { f ->
                appendLine("  ${f.name}: ${f.length()} bytes, ${fmt.format(java.util.Date(f.lastModified()))}, writable=${f.canWrite()}")
            }
        }
        for (d in listOf("system/framework/oat/arm", "system/framework/oat/arm64", "system/framework/arm", "system/framework/arm64"))
            if (File(p.root, d).isDirectory) listOat(d) { it.isFile && (it.name.startsWith("boot") || it.name.startsWith("services") ||
                it.name.startsWith("wifi-service") || it.name.startsWith("ethernet-service") || it.name.startsWith("com.android.location.provider")) }
        for (isa in listOf("arm", "arm64"))
            if (File(p.root, "data/dalvik-cache/$isa").isDirectory) listOat("data/dalvik-cache/$isa") { it.name.startsWith("system@framework@") }
        for (jar in listOf("services.jar", "wifi-service.jar", "ethernet-service.jar", "com.android.location.provider.jar")) {
            val f = File(p.root, "system/framework/$jar")
            val hasDex = runCatching { java.util.zip.ZipFile(f).use { z -> z.getEntry("classes.dex") != null } }.getOrNull()
            appendLine("system/framework/$jar: " + (if (f.isFile) "${f.length()} bytes, classes.dex inside=$hasDex" else "missing"))
        }
        appendLine()
        // Modified for AEmulator Plus (2026-11-06): a Dalvik app_process that dies in libdvm (SIGSEGV addr 0x2c) is a class that the
        // runtime registers but no boot jar defines; the list the VM gets and the jars it could have got are what tells which.
        appendLine("-- boot class path (Dalvik) --")
        runCatching {
            appendLine("firmware list: ${vm.img.bootclasspath}")
            val r = BootClasspathRepair.repair(p.root, vm.img.bootclasspath)
            appendLine(r.report())
            appendLine("exported: ${r.bootclasspath}")
            if (r.undefined > 0) appendLine("classes libandroid_runtime names that no jar defines: ${r.undefined}")
            File(p.root, "system/framework").listFiles()?.filter { it.isFile && it.name.endsWith(".jar") }?.sortedBy { it.name }
                ?.let { jars -> appendLine("system/framework jars (${jars.size}): " + jars.joinToString { it.name }) }
        }.onFailure { appendLine("boot class path: ${it.message}") }
        appendLine()
        appendLine("-- GL drivers (where SwiftShader and the bridge are in the guest tree) --")
        for (d in listOf("vendor/lib64/egl", "system/lib64/egl", "system/vendor/lib64/egl", "vendor/lib/egl", "system/lib/egl")) {
            val dir = File(p.root, d)
            val fs = dir.listFiles()?.sortedBy { it.name }
            if (fs == null) { appendLine("$d: no such directory"); continue }
            appendLine("$d: ${fs.size} files")
            fs.forEach { f ->
                val link = runCatching { java.nio.file.Files.readSymbolicLink(f.toPath()).toString() }.getOrNull()
                appendLine("  ${f.name}: " + (if (link != null) "-> $link" + (if (f.exists()) "" else " (BROKEN)") else "${f.length()} bytes"))
            }
        }
        appendLine()
        appendLine("-- hwcomposer (SurfaceFlinger's vsync comes from it; none = it fakes the vsync, \"Timed out waiting for hw vsync\") --")
        for (d in listOf("system/lib/hw", "vendor/lib/hw", "system/vendor/lib/hw", "vendor/lib64/hw", "system/lib64/hw")) {
            File(p.root, "$d").listFiles()?.filter { it.name.startsWith("hwcomposer.") }?.sortedBy { it.name }?.forEach { f ->
                val ours = runCatching { String(f.readBytes(), Charsets.ISO_8859_1).contains("AEmulator framebuffer hwcomposer") }.getOrDefault(false)
                appendLine("$d/${f.name}: ${f.length()} bytes" + if (ours) " (the engine's stand-in, native/hwc1fb)" else "")
            }
        }
        if (HwcFallback.disabled(p)) appendLine("stand-in composer: OFF for this image (surfaceflinger crashed with it; delete \"hwc-standin-off\" in the image directory to try it again)")
        appendLine()
        appendLine("-- HALs: what the manifest promises vs what the tree can start --")
        runCatching { append(halReport(p.root)) }.onFailure { appendLine("[failed: $it]") }
        appendLine()
        appendLine("-- run dir --")
        p.bin.listFiles()?.sortedBy { it.name }?.forEach { appendLine("${it.name}: ${kind(it)} ${it.length()} bytes, ${java.util.Date(it.lastModified())}") }
        appendLine()
        appendLine("-- guest data permissions (mode, host uid, owners-table row) --")
        runCatching { dataPerms(p.root, p.owners) }.onSuccess { append(it) }.onFailure { appendLine("[failed: $it]") }
    }

    /**
     * Recursive listing of the whole guest tree (names only, never contents): one line per entry with type, octal mode,
     * host uid, size, mtime and path (symlinks show their target). The tree holds every partition that was imported
     * (system, vendor, product, system_ext, odm, data, cache, ...) merged under one root; the boot partition is not part of
     * it (boot.img and the recovery live next to the tree, not in it). Symlinks are never followed, so loops cannot
     * trap the walk. Written straight to the zip, so a huge /data does not have to fit in memory.
     */
    private fun guestFileList(root: File, out: OutputStream) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        var files = 0L; var dirs = 0L; var links = 0L; var other = 0L; var bytes = 0L
        w.write("# type(d dir, f file, l symlink, p fifo, s socket, o other) mode hostUid size mtime path\n")
        w.write("# every partition of the guest tree except boot; contents are not included; symlinks are not followed\n")
        // explicit stack: a deep tree must not overflow the thread's stack
        val stack = ArrayDeque<String>()
        stack.addLast("")
        while (stack.isNotEmpty()) {
            val rel = stack.removeLast()
            val dir = if (rel.isEmpty()) root else File(root, rel)
            val names = runCatching { dir.list() }.getOrNull()
            if (names == null) { w.write("# [cannot list /$rel]\n"); continue }
            names.sort()
            val subdirs = ArrayList<String>()
            for (n in names) {
                val path = if (rel.isEmpty()) n else "$rel/$n"
                val f = File(dir, n)
                val st = runCatching { android.system.Os.lstat(f.path) }.getOrNull()
                if (st == null) { w.write("? - - - - /$path\n"); continue }
                val fmtBits = st.st_mode and android.system.OsConstants.S_IFMT
                val t = when (fmtBits) {
                    android.system.OsConstants.S_IFDIR -> { dirs++; subdirs += path; 'd' }
                    android.system.OsConstants.S_IFREG -> { files++; bytes += st.st_size; 'f' }
                    android.system.OsConstants.S_IFLNK -> { links++; 'l' }
                    android.system.OsConstants.S_IFIFO -> { other++; 'p' }
                    android.system.OsConstants.S_IFSOCK -> { other++; 's' }
                    else -> { other++; 'o' }
                }
                w.write("$t ${Integer.toOctalString(st.st_mode and 0xFFF)} ${st.st_uid} ${st.st_size} ${fmt.format(java.util.Date(st.st_mtime * 1000))} /$path")
                if (t == 'l') w.write(" -> " + (runCatching { android.system.Os.readlink(f.path) }.getOrNull() ?: "?"))
                w.write("\n")
            }
            // depth-first in name order: push in reverse so the first folder is listed first
            for (i in subdirs.indices.reversed()) stack.addLast(subdirs[i])
        }
        w.write("# total: $files files ($bytes bytes), $dirs directories, $links symlinks, $other other\n")
        w.flush()
    }

    /** Mode / owners-table state of the directories behind the acore / gapps / dialer "Could not open database" crashes. */
    private fun dataPerms(root: File, owners: File): String = buildString {
        val rows = HashMap<String, String>()
        if (owners.isFile) owners.forEachLine { l ->
            val f = l.trim().split(Regex("\\s+"))
            if (f.size >= 4) rows[f[0] + " " + f[1]] = "uid=" + f[2].toLong(16) + " gid=" + f[3].toLong(16)   // last row wins
        }
        fun line(rel: String) {
            val f = File(root, rel)
            val st = runCatching { android.system.Os.lstat(f.path) }.getOrNull()
            if (st == null) { appendLine("$rel: missing"); return }
            val key = String.format("%016x %016x", st.st_dev, st.st_ino)
            appendLine("$rel: mode=${Integer.toOctalString(st.st_mode and 0xFFF)} hostUid=${st.st_uid} table=${rows[key] ?: "NO ROW (root)"}")
        }
        for (rel in listOf("data", "data/data", "data/user", "data/user/0")) line(rel)
        for (pkg in listOf("com.android.providers.contacts", "com.google.android.gsf", "com.android.dialer",
                "com.android.providers.media", "com.android.settings", "com.android.providers.telephony")) {
            line("data/data/$pkg")
            File(root, "data/data/$pkg").listFiles()?.sortedBy { it.name }?.forEach { line("data/data/$pkg/${it.name}") }
        }
        appendLine("owners table rows: ${rows.size}")
    }

    /**
     * What the guest's messaging provider actually stored: counts and the newest rows of sms / threads. Answers "was the
     * injected SMS saved?" without relying on the firmware's (often silent) logging. Works on a copy of the database.
     */
    private fun smsDb(ctx: Context, vm: GuestVm): String = buildString {
        val root = vm.paths.root
        val rels = listOf(
            "dbdata/databases/com.android.providers.telephony/mmssms.db",
            "data/data/com.android.providers.telephony/databases/mmssms.db",
        )
        val src = rels.map { File(root, it) }.firstOrNull { it.isFile }
        if (src == null) { appendLine("mmssms.db: not found in ${rels.joinToString()}"); return@buildString }
        appendLine("source: ${src.path.removePrefix(root.path)} (${src.length()} bytes, ${java.util.Date(src.lastModified())})")
        val tmp = File(ctx.cacheDir, "diag-mmssms.db")
        runCatching {
            for (suffix in listOf("", "-journal", "-wal", "-shm")) {
                val from = File(src.path + suffix); val to = File(tmp.path + suffix)
                to.delete()
                if (from.isFile) from.copyTo(to, overwrite = true)
            }
            android.database.sqlite.SQLiteDatabase.openDatabase(tmp.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { d ->
                fun table(name: String, sql: String) {
                    appendLine()
                    appendLine("-- $name --")
                    runCatching {
                        d.rawQuery(sql, null).use { c ->
                            appendLine(c.columnNames.joinToString(" | "))
                            while (c.moveToNext()) appendLine((0 until c.columnCount).joinToString(" | ") { i -> c.getString(i) ?: "null" })
                        }
                    }.onFailure { appendLine("[$name failed: $it]") }
                }
                table("counts", "SELECT (SELECT count(*) FROM sms) AS sms, (SELECT count(*) FROM threads) AS threads, " +
                    "(SELECT count(*) FROM canonical_addresses) AS addresses, (SELECT count(*) FROM pdu) AS pdu")
                table("sms (newest 30)", "SELECT _id, thread_id, address, date, type, read, seen, status, length(body) AS body_len, substr(body,1,40) AS body " +
                    "FROM sms ORDER BY _id DESC LIMIT 30")
                table("threads", "SELECT * FROM threads ORDER BY _id DESC LIMIT 20")
                table("schema (tables, triggers)", "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE type IN ('table','trigger') ORDER BY type, name")
                // the insert Messaging performs for an incoming SMS, on the COPY and rolled back: shows the exact error, if any
                appendLine()
                appendLine("-- test insert into sms (copy, rolled back) --")
                d.beginTransaction()
                try {
                    val tid = runCatching { d.rawQuery("SELECT _id FROM threads ORDER BY _id DESC LIMIT 1", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } }.getOrDefault(0L)
                    val cv = android.content.ContentValues().apply {
                        put("thread_id", tid); put("address", "+10000000000"); put("date", 1791723429000L)
                        put("read", 0); put("type", 1); put("body", "diag test")
                    }
                    val row = d.insertOrThrow("sms", null, cv)
                    appendLine("insert OK, rowid=$row (thread_id=$tid)")
                    table("threads after the test insert (triggers)", "SELECT _id, message_count, snippet, unread_count FROM threads WHERE _id=$tid")
                } catch (t: Throwable) {
                    appendLine("insert FAILED: $t")
                } finally { d.endTransaction() }
            }
        }.onFailure { appendLine("[could not read the copy: $it]") }
        for (suffix in listOf("", "-journal", "-wal", "-shm")) File(tmp.path + suffix).delete()
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

    /**
     * system_server, keystore and cameraserver wait for ever for a HAL the vendor manifest lists and nothing registers
     * ("Waited one second for android.hardware.keymaster@3.0::IKeymasterDevice/default"). This says, per manifest entry,
     * whether the tree has a program that could register it, and lists the HAL programs and libraries of the tree.
     */
    private fun halReport(root: File): String = buildString {
        val manifests = ArrayList<File>()
        for (rel in listOf("vendor/etc/vintf/manifest.xml", "vendor/manifest.xml", "system/vendor/manifest.xml", "system/vendor/etc/vintf/manifest.xml"))
            File(root, rel).takeIf { it.isFile }?.let { manifests += it }
        for (dir in listOf("vendor/etc/vintf", "system/vendor/etc/vintf", "odm/etc/vintf"))
            File(root, dir).listFiles()?.filter { it.isFile && it.name.startsWith("manifest_") && it.name.endsWith(".xml") }?.sortedBy { it.name }?.let { manifests += it }
        for (rel in listOf("vendor/etc/vintf/manifest", "system/vendor/etc/vintf/manifest"))
            File(root, rel).listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }?.sortedBy { it.name }?.let { manifests += it }
        // Added 2026-10-31: odm and APEX manifests are read by the service manager as well; a KeyMint declared there (Android 17
        // desktop: keystore2 "Failed to get_interface IKeyMintDevice/default: NAME_NOT_FOUND") was invisible in this listing.
        for (rel in listOf("odm/etc/vintf/manifest.xml", "system/odm/etc/vintf/manifest.xml", "system/etc/vintf/manifest.xml", "system_ext/etc/vintf/manifest.xml", "product/etc/vintf/manifest.xml"))
            File(root, rel).takeIf { it.isFile }?.let { manifests += it }
        for (rel in listOf("odm/etc/vintf/manifest", "system/odm/etc/vintf/manifest", "system/etc/vintf/manifest", "system_ext/etc/vintf/manifest", "product/etc/vintf/manifest"))
            File(root, rel).listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }?.sortedBy { it.name }?.let { manifests += it }
        for (a in File(root, "apex").listFiles()?.sortedBy { it.name }.orEmpty())
            File(a, "etc/vintf").listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }?.sortedBy { it.name }?.let { manifests += it }
        val bins = listOf("vendor/bin/hw", "system/vendor/bin/hw", "system/bin/hw", "system_ext/bin/hw").flatMap { d ->
            File(root, d).listFiles()?.filter { it.isFile }?.map { "$d/${it.name}" }.orEmpty()
        }.sorted()
        appendLine("HAL programs in the tree (${bins.size}):"); bins.forEach { appendLine("  $it") }
        // rc services that run a program of a HAL directory
        val rcs = (listOf("vendor/etc/init", "system/vendor/etc/init", "system/etc/init", "system_ext/etc/init", "odm/etc/init", "system/odm/etc/init", "").flatMap { d ->
            File(root, d).listFiles()?.filter { it.isFile && it.name.endsWith(".rc") }.orEmpty()
        } + File(root, "apex").listFiles().orEmpty().flatMap { a -> File(a, "etc").listFiles()?.filter { it.isFile && it.name.endsWith(".rc") }.orEmpty() })
        appendLine("rc services that run a HAL program:")
        for (rc in rcs) {
            var cur: String? = null; var bin: String? = null; var flags = ArrayList<String>()
            fun flush() { val b = bin; val c = cur; if (c != null && b != null && b.contains("/bin/hw/"))
                appendLine("  ${rc.name}: service $c $b ${if (File(root, b.removePrefix("/")).isFile) "(present)" else "(MISSING)"} ${flags.joinToString(" ")}") }
            for (raw in runCatching { rc.readLines() }.getOrDefault(emptyList())) {
                val t = raw.trim()
                if (t.startsWith("service ")) { flush(); val w = t.split(Regex("\\s+")); cur = w.getOrNull(1); bin = w.getOrNull(2); flags = ArrayList() }
                else if (t.startsWith("on ")) { flush(); cur = null; bin = null }
                else if (cur != null && (t == "disabled" || t == "oneshot" || t.startsWith("class ") || t.startsWith("user "))) flags += t
            }
            flush()
        }
        val hal = Regex("<hal[^>]*>(.*?)</hal>", RegexOption.DOT_MATCHES_ALL)
        val nm = Regex("<name>([^<]+)</name>"); val ver = Regex("<version>([^<]+)</version>")
        val itf = Regex("<interface>\\s*<name>([^<]+)</name>\\s*<instance>([^<]+)</instance>", RegexOption.DOT_MATCHES_ALL)
        appendLine("manifest entries:")
        if (manifests.isEmpty()) appendLine("  (no vendor manifest found)")
        for (m in manifests) {
            val txt = runCatching { m.readText() }.getOrDefault("")
            for (h in hal.findAll(txt)) {
                val body = h.groupValues[1]
                val name = nm.find(body)?.groupValues?.get(1) ?: continue
                val v = ver.find(body)?.groupValues?.get(1) ?: "?"
                val i = itf.findAll(body).joinToString(",") { it.groupValues[1] + "/" + it.groupValues[2] }
                appendLine("  ${m.relativeTo(root).path}: $name@$v $i")
            }
        }
        // every file of the tree's vintf and init places that mentions KeyMint, whatever the listing above made of it
        appendLine("files that mention KeyMint (declarations and rc):")
        for (m in manifests.distinctBy { it.path }) if (runCatching { m.readText().contains("security.keymint") }.getOrDefault(false)) appendLine("  ${m.relativeTo(root).path}")
        for (rc in rcs.distinctBy { it.path }) if (runCatching { rc.readText().contains("keymint") }.getOrDefault(false)) appendLine("  ${rc.relativeTo(root).path}")
        appendLine("keymaster / gatekeeper / camera libraries:")
        for (d in listOf("vendor/lib/hw", "vendor/lib64/hw", "system/lib/hw", "system/lib64/hw", "system/vendor/lib/hw")) {
            File(root, d).listFiles()?.filter { f -> val n = f.name; n.contains("keymaster") || n.contains("keystore") || n.contains("gatekeeper") || n.contains("camera") }
                ?.sortedBy { it.name }?.forEach { appendLine("  $d/${it.name} (${it.length()} bytes)") }
        }
        // the audio HAL host dlopen()s its implementation by bare name: which of them the tree has, and whether the vendor
        // namespace (/vendor/lib, /odm/lib) reaches it directly
        appendLine("HAL files set aside (system/.aemu-parked, hw modules and impls):")
        File(root, "system/.aemu-parked").list().orEmpty().filter { "#hw#" in it && it.endsWith(".so") }.sorted().forEach { appendLine("  $it") }
        appendLine("audio HAL implementations (the engine's host asks for these by bare name):")
        for (name in AudioImplLibs.HOST_IMPLS) {
            val reach = AudioImplLibs.reachable(root, name)
            val hw = listOf("vendor/lib/hw", "odm/lib/hw", "system/vendor/lib/hw", "system/vendor/lib").map { File(root, "$it/$name") }.filter { it.isFile }
            appendLine("  $name: " + when {
                reach != null -> "reachable (${reach.relativeTo(root).path}, ${reach.length()} bytes)"
                hw.isNotEmpty() -> "NOT reachable by bare name, only in ${hw.joinToString { it.relativeTo(root).path }}"
                else -> "not in the tree"
            })
        }
        for (d in listOf("vendor/lib/hw", "system/vendor/lib/hw", "system/lib/hw")) {
            File(root, d).listFiles()?.filter { it.name.contains("audio") }?.sortedBy { it.name }
                ?.forEach { appendLine("  $d/${it.name} (${it.length()} bytes)") }
        }
    }
}
