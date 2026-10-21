package app.aemu.core

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Собирает командную строку и окружение гостевого процесса:
 *   dhdrun <область свойств> 30 qemu-arm -L <root> -0 <argv0> <root/argv0> args…
 * dhdrun отображает область свойств на fd 30 (ANDROID_PROPERTY_WORKSPACE), создаёт сокеты init
 * (DHD_SOCK_<имя>=путь,права → ANDROID_SOCKET_<имя>) и переносит DHD_ENV_X в X перед exec qemu.
 */
class GuestRunner(
    val paths: VmPaths,
    val img: GuestImage,
    val net: NetConfig = NetConfig(),
) {
    data class NetConfig(val http80: Int = 0, val https443: Int = 0, val dns53: Int = 0, val glPath: String? = null)

    var qemuLog = false
    var qemuStrace = false
    var tbFlush = 0
    var noSmc = false
    var noTcgOpt = false
    var singleTouch = false
    var binderVerbose = false
    var sdcardHost: File? = null
    /** user's extra qemu options from firmware settings: KEY=VALUE tokens become env vars, the rest go to qemu */
    var userQemuArgs: String = ""

    private val userTokens: List<String> get() = Regex("""("[^"]*"|\S+)""").findAll(userQemuArgs).map { it.value.trim('"') }.toList()
    private fun isEnvToken(t: String) = !t.startsWith("-") && Regex("^[A-Z_][A-Z0-9_]*=").containsMatchIn(t)

    val engine: Engine get() = img.engine
    val kernel: Kernel64 get() = Kernel64.forApi(img.api)
    val qemu: File get() = paths.nativeBin(
        if (engine == Engine.N64) kernel.runner
        else if (QemuFallback.enabled(paths) && paths.nativeBin(engine.qemuXperiaPlay).canExecute()) engine.qemuXperiaPlay else engine.qemu)
    val runner: File get() = paths.nativeBin(engine.runner)

    fun cmdline(argv: List<String>, propsFile: File? = null): List<String> {
        val prog = argv.first()
        val host = File(paths.root, prog.removePrefix("/")).absolutePath
        val cmd = ArrayList<String>()
        if (engine == Engine.N64) return listOf(qemu.absolutePath, prog) + argv
        val props = propsFile ?: paths.props
        if (runner.canExecute() && props.isFile) {
            cmd += runner.absolutePath; cmd += props.absolutePath; cmd += "30"
        }
        if (img.settings.native32 && CpuInfo.hasAArch32) {
            cmd += host
            cmd += argv.drop(1)
            return cmd
        }
        cmd += qemu.absolutePath
        cmd += listOf("-L", paths.root.absolutePath, "-0", prog)
        if (qemuLog || qemuStrace) {
            if (qemuLog) cmd += listOf("-d", "unimp,guest_errors")
            if (qemuStrace) cmd += "-strace"
            cmd += listOf("-D", File(paths.bin, "qemu-${prog.substringAfterLast('/')}.log").absolutePath)
        }
        cmd += userTokens.filterNot(::isEnvToken)
        cmd += host
        cmd += argv.drop(1)
        return cmd
    }

    /**
     * 12+: the class paths (BOOTCLASSPATH, DEX2OATBOOTCLASSPATH, SYSTEMSERVERCLASSPATH) are put together at boot from
     * the modules' own lists by the firmware's derive_classpath, and init exports them to every service after it.
     */
    @Volatile var derivedExports: Map<String, String> = emptyMap()

    /** Native arm64 guest: libaemu64 redirects paths itself, the firmware's own linker and libraries run as they are. */
    private fun env64(extra: Map<String, String>, program: String?): Map<String, String> {
        val e = LinkedHashMap<String, String>()
        for ((k, v) in img.exports) if (k !in EXPORTS_SKIP) e[k] = v
        e["PATH"] = img.exports["PATH"] ?: "/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin"
        e["LD_LIBRARY_PATH"] = "/vendor/lib64:/system/lib64"
        e["ANDROID_ROOT"] = img.exports["ANDROID_ROOT"] ?: "/system"
        e["ANDROID_DATA"] = img.exports["ANDROID_DATA"] ?: "/data"
        e["ANDROID_ASSETS"] = img.exports["ANDROID_ASSETS"] ?: "/system/app"
        e["EXTERNAL_STORAGE"] = img.exports["EXTERNAL_STORAGE"] ?: img.sdcardPath
        e["BOOTCLASSPATH"] = img.bootclasspath
        e.putIfAbsent("EMULATED_STORAGE_SOURCE", "/mnt/shell/emulated")
        e.putIfAbsent("EMULATED_STORAGE_TARGET", "/storage/emulated")
        // a system-only image (GSI) has no init.environ.rc: that lives in the boot ramdisk
        e.putIfAbsent("ASEC_MOUNTPOINT", "/mnt/asec")
        e.putIfAbsent("ANDROID_BOOTLOGO", "1")
        e.putIfAbsent("ANDROID_STORAGE", "/storage")
        if ("DOWNLOAD_CACHE" !in e && img.api >= 26) {
            File(paths.root, "data/cache").mkdirs()
            e["DOWNLOAD_CACHE"] = "/data/cache"
        }
        if ("SYSTEMSERVERCLASSPATH" !in e) {
            val jars = listOf("services.jar", "ethernet-service.jar", "wifi-service.jar")
                .filter { File(paths.root, "system/framework/$it").isFile }
            if (jars.isNotEmpty()) e["SYSTEMSERVERCLASSPATH"] = jars.joinToString(":") { "/system/framework/$it" }
        }
        e["HOME"] = "/data"; e["TMPDIR"] = "/data/local/tmp"; e["TZ"] = "UTC"
        e["AEMU64_ROOT"] = paths.root.absolutePath
        e["AEMU64_PROPS"] = paths.props.absolutePath
        e["AEMU64_BINDER"] = paths.binderSock.absolutePath
        if (kernel.treble) {
            e["AEMU64_HWBINDER"] = paths.hwbinderSock.absolutePath
            e["AEMU64_VNDBINDER"] = paths.vndbinderSock.absolutePath
        }
        e["AEMU64_INPUT"] = paths.inputSock.absolutePath
        // 32-bit firmware whose libbinder insists on protocol 7: the guest runner (libaemu64_26/27) answers BINDER_VERSION
        // with it and translates the 32-bit binder structures for those programs (see BinderProtocol)
        BinderProtocol.saved(paths.bin)?.let { e[BinderProtocol.GUEST_ENV] = it.toString() }
        paths.nativeBin("libqemu_arm.so").takeIf { it.canExecute() }?.let { e["AEMU64_QEMU"] = it.absolutePath }
        sdcardHost?.let { e["AEMU64_SDCARD"] = it.absolutePath }
        if (img.settings.gpu && kernel.gpuBridge32 != null) {
            e["DHD_GL"] = "/dev/socket/gl"
            if (!HostInfo.gpu().contains("adreno")) e["DHD_GL3"] = "0"
            e["AEMU_GL_RETARGET"] = "0"; e["AEMU_GL_CLIENT_ARRAYS"] = "0"      // as on the 4.4 engine for 4.3+
        }
        e["AEMU64_FB"] = "${img.settings.width}x${img.settings.height}"
        // debugging: run/crash.report makes the runner print pc/lr and their mappings on a fatal signal
        if (File(paths.bin, "crash.report").exists()) e["AEMU64_CRASH"] = "1"
        for (t in userTokens.filter(::isEnvToken)) e[t.substringBefore('=')] = t.substringAfter('=')
        for ((k, v) in extra) when {
            k.startsWith("DHD_SOCK_") -> e["AEMU64_SOCK_" + k.removePrefix("DHD_SOCK_")] = v
            k == "DHD_UID" -> e["AEMU64_ID"] = v + "," + (extra["DHD_GID"] ?: v)
            k == "DHD_GID" -> {}
            k == "AEMU64_PROPS_FILE" -> e["AEMU64_PROPS"] = v
            else -> e[k] = v
        }
        // A firmware with no 64-bit side runs its own 32-bit installd under qemu-arm, and the phone's kernel answers
        // every extended-attribute call with ENOSYS. TreeFixer installs libaemuxattr.so for exactly that case, but only
        // the 32-bit engine's env() ever preloaded it: here installd kept failing on getxattr / lgetxattr / setxattr
        // ("Failed before getfilecon ... Function not implemented"), deleted /data/user_de/0/<pkg> for every package,
        // SettingsProvider had no database directory, system_server died and zygote went with it.
        // DHD_ENV_<X> is the guest-only form (the translator turns it into <X> for the guest program, not for itself).
        // The path depends on the program: a /vendor program's linker namespace (permitted_paths=/odm:/vendor) refuses
        // /system/lib/libaemuxattr.so ("CANNOT LINK EXECUTABLE", exit code 1), so it gets the copy in /vendor/lib.
        val xattrPath = GuestPreload.xattrFor(program)
        if (isPure32Tree(paths.root) && File(paths.root, xattrPath.removePrefix("/")).isFile) {
            val key = "DHD_ENV_LD_PRELOAD"
            val have = e[key].orEmpty().split(':').filter { it.isNotEmpty() && it != GuestPreload.XATTR && it != GuestPreload.XATTR_VENDOR }
            e[key] = (have + xattrPath).joinToString(":")
            // The native engine's runner (libaemu64) execs qemu-arm itself and never translates DHD_ENV_<X> (that is the
            // 32-bit engine's runner), so the guest saw no LD_PRELOAD at all. qemu-user's own guest-only environment
            // (QEMU_SET_ENV, "A=b,C=d") reaches the guest program without preloading the library into qemu itself.
            val set = e["QEMU_SET_ENV"].orEmpty().split(',').filter { it.isNotEmpty() && !it.startsWith("LD_PRELOAD=") }
            e["QEMU_SET_ENV"] = (set + ("LD_PRELOAD=" + e.getValue(key))).joinToString(",")
        }
        e.putAll(derivedExports)
        return e
    }

    /** [program]: the guest path of the program the environment is for (argv[0] of the service), null for a shell */
    fun env(extra: Map<String, String> = emptyMap(), program: String? = null): Map<String, String> {
        if (engine == Engine.N64) return env64(extra, program)
        val s = img.settings
        val e = LinkedHashMap<String, String>()
        // переменные из init.rc прошивки (export …), кроме тех, что задаём сами
        for ((k, v) in img.exports) if (k !in EXPORTS_SKIP) e[k] = v
        e["PATH"] = img.exports["PATH"] ?: "/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin"
        e["DHD_ENV_LD_LIBRARY_PATH"] = (img.exports["LD_LIBRARY_PATH"] ?: "/vendor/lib:/system/lib").let { lp ->
            // Dalvik's java.library.path is this variable: System.loadLibrary("android_servers") only looks there,
            // so a firmware export that leaves out /system/lib ends in "Library android_servers not found"
            if (lp.split(':').contains("/system/lib")) lp else "$lp:/system/lib"
        }
        // ashmem через memfd (движок) + наша прослойка от запрещённых в песочнице вызовов (mount, reboot…).
        // glibc firmware (Google TV) cannot load the bionic-linked libaemushim.so (DT_NEEDED libc.so): ashmem shim only.
        e["DHD_ENV_LD_PRELOAD"] = GuestPreload.value(GuestPreload.isGlibcFirmware(paths.root),
            xattr = isPure32Tree(paths.root) && File(paths.root, GuestPreload.xattrFor(program).removePrefix("/")).isFile,
            xattrPath = GuestPreload.xattrFor(program))
        e["ANDROID_ROOT"] = img.exports["ANDROID_ROOT"] ?: "/system"
        e["ANDROID_DATA"] = img.exports["ANDROID_DATA"] ?: "/data"
        e["ANDROID_ASSETS"] = img.exports["ANDROID_ASSETS"] ?: "/system/app"
        e["EXTERNAL_STORAGE"] = img.exports["EXTERNAL_STORAGE"] ?: img.sdcardPath
        e["ASEC_MOUNTPOINT"] = img.exports["ASEC_MOUNTPOINT"] ?: "/mnt/asec"
        e["LOOP_MOUNTPOINT"] = img.exports["LOOP_MOUNTPOINT"] ?: "/mnt/obb"
        e["BOOTCLASSPATH"] = img.bootclasspath
        // 4.2+: зигота монтирует карту для каждого пользователя и без этих переменных роняет приложения
        // 5.0+: Environment.isExternalStorageEmulated() ищет путь /storage/emulated/<user> среди томов MountService (storage_list.xml).
        // У прошивки с единственным физическим томом (SDK/goldfish: /storage/sdcard) такого тома нет, и ActivityManager не может
        // запустить ни один процесс приложения ("Failed to find storage device at /storage/emulated/0"), так что эти переменные не задаём.
        val ext = img.exports["EXTERNAL_STORAGE"]
        val physicalOnly = img.api >= 21 && ext != null && !ext.startsWith("/storage/emulated")
        if (img.api >= 17 && !physicalOnly) {
            e.putIfAbsent("EMULATED_STORAGE_SOURCE", "/mnt/shell/emulated")
            e.putIfAbsent("EMULATED_STORAGE_TARGET", "/storage/emulated")
        }
        // libaemushim: the precompiled code under /system is read-only like on the real mount
        e["AEMU_RO_SYSTEM"] = "1"
        // libaemushim serves /proc/meminfo scaled to this budget instead of the phone's own memory
        e["AEMU_RAM_KB"] = (guestRamMb(img.api, s.ramMb) * 1024L).toString()
        e["HOME"] = "/data"
        e["TMPDIR"] = "/data/local/tmp"
        e["ASHMEM_SHIM_DIR"] = "/data/local/tmp"
        e["TZ"] = "UTC"
        // uname -r / os.version: qemu-user reports this release instead of the host kernel's
        if (VmSettings.kernelCustom(img.settings)) e["QEMU_UNAME"] = VmSettings.kernelRelease(img.settings)
        e["DHD_FB_W"] = s.width.toString()
        e["DHD_FB_H"] = s.height.toString()
        e["DHD_IN_W"] = s.width.toString()
        e["DHD_IN_H"] = s.height.toString()
        e["DHD_FB_HZ"] = s.fbHz.toString()
        e["DHD_OWNERS"] = paths.owners.absolutePath
        e["DHD_NET_LOG"] = File(paths.bin, "net.log").absolutePath
        e["DHD_ASHMEM_LOG"] = File(paths.bin, "ashmem.log").absolutePath
        e["DHD_BINDER"] = paths.binderSock.absolutePath
        e["DHD_BINDER_LOG"] = File(paths.bin, "binder-warn.log").absolutePath
        e["DHD_CREDS"] = paths.creds.absolutePath
        e["DHD_INPUT"] = paths.inputSock.absolutePath
        if (singleTouch) e["DHD_INPUT_ST"] = "1"
        if (binderVerbose) e["DHD_BINDER_VERBOSE"] = "1"
        sdcardHost?.let { e["DHD_SDCARD"] = it.absolutePath }
        if (net.http80 > 0) e["DHD_HTTP80"] = net.http80.toString()
        if (net.https443 > 0) e["DHD_HTTPS443"] = net.https443.toString()
        if (net.dns53 > 0) {
            e["DHD_DNS53"] = net.dns53.toString()
            e["DHD_ENV_ANDROID_DNS_MODE"] = "local"
        }
        net.glPath?.let { e["DHD_GL"] = it }
        // GL-мост отдаёт гостю ES 3.0, но hwui ≤4.3 и загрузка текстур с шагом строки (ROW_LENGTH)
        // через мост не работают — для них сообщаем ES 2.0. На Adreno 4.4 с ES 3 и PBO работает правильно.
        if (img.api < 19 || !HostInfo.gpu().contains("adreno")) e["DHD_GL3"] = "0"
        // 4.3+: SurfaceFlinger (GLConsumer) re-targets its EGLImages itself; forcing it again blanks the screen
        if (img.api >= 18) { e["AEMU_GL_RETARGET"] = "0"; e["AEMU_GL_CLIENT_ARRAYS"] = "0" }
        if (tbFlush > 0) e["DHD_TBFLUSH"] = tbFlush.toString()
        if (noSmc) e["DHD_NO_SMC"] = "1"
        if (noTcgOpt) e["DHD_NO_TCGOPT"] = "1"
        for (t in userTokens.filter(::isEnvToken)) e[t.substringBefore('=')] = t.substringAfter('=')
        e.putAll(extra)
        return e
    }

    /** Одноразовый запуск гостевой команды (am, pm, settings, sh -c …) с ожиданием вывода. */
    fun run(argv: List<String>, timeoutMs: Long = 120_000, extra: Map<String, String> = emptyMap()): Pair<Int, String> {
        if (!qemu.canExecute()) return -1 to "no qemu"
        val pb = ProcessBuilder(cmdline(argv)).directory(paths.bin).redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(env(extra))
        val p = pb.start()
        val sb = StringBuilder()
        val t = Thread {
            runCatching { p.inputStream.bufferedReader().forEachLine { if (sb.length < 256_000) sb.append(it).append('\n') } }
        }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) p.destroyForcibly()
        t.join(2000)
        val code = runCatching { p.exitValue() }.getOrDefault(-9)
        return code to sb.toString()
    }

    companion object {
        /** PATH for an interactive/adb shell: /system/bin and /system/xbin first (the firmware's /sbin-first PATH breaks pm/am) */
        fun shellPath(path: String?): String {
            val rest = (path ?: "").split(':').filter { it.isNotEmpty() && it != "/system/bin" && it != "/system/xbin" }
            return (listOf("/system/bin", "/system/xbin") + rest).joinToString(":")
        }

        /**
         * qemu cannot exec a script (even with #!: "Error while loading .../bin/pm: Exec format error"), so typing
         * `pm`, `am`, `input`... in the shell failed. Shell functions run them through `sh <script>` instead.
         * Only for files that are really scripts (no ELF magic) and only in the shell the user types into.
         */
        fun scriptFns(root: File): String {
            val sb = StringBuilder()
            for (n in listOf("am", "pm", "input", "monkey", "svc", "ime", "bmgr", "wm", "content", "settings")) {
                val f = File(root, "system/bin/$n")
                if (!f.isFile) continue
                val head = runCatching { f.inputStream().use { s -> ByteArray(4).also { b -> s.read(b) } } }.getOrNull() ?: continue
                if (head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte()) continue
                sb.append(n).append("() { /system/bin/sh /system/bin/").append(n).append(" \"\$@\"; }; ")
            }
            return sb.toString()
        }

        private val EXPORTS_SKIP = setOf("PATH", "LD_LIBRARY_PATH", "BOOTCLASSPATH", "LD_PRELOAD")

        /** RAM the guest is told it has: the user's choice, or what phones of that Android version shipped with. */
        fun guestRamMb(api: Int, chosen: Int): Int {
            if (chosen > 0) return chosen
            val typical = when { api <= 10 -> 512; api <= 15 -> 1024; api <= 19 -> 2048; api <= 23 -> 3072; else -> 4096 }
            val hostMb = runCatching {
                java.io.File("/proc/meminfo").useLines { l -> l.first { it.startsWith("MemTotal:") } }.split(Regex("\\s+"))[1].toLong() / 1024
            }.getOrDefault(8192L)
            return typical.coerceAtMost((hostMb / 2).toInt())
        }
    }
}
