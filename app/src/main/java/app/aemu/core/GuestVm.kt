/* Modified for AEmulator Sunset through 2026-10-03: motion and boot-media bridges,
 * live library status; 2026-10-04: native boot of framework-less MMI/FTM builds.
 * GPL-3.0; upstream attribution retained in NOTICE.md. */
package app.aemu.core

import app.aemu.R
import android.content.Context
import android.os.Process as AProcess
import android.os.SystemClock
import android.view.Surface
import dev.lk.dhd.BinderSlot
import dev.lk.dhd.GlSlot
import dev.lk.dhd.Slot
import dev.lk.m7sense.GlBridge
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.CopyOnWriteArrayList

/** Daemons system_server cannot live without: restarted even when init.rc marks them oneshot
 * (MIUI installd dies in the LBE security hook on start). */
private val ALWAYS_RESTART = setOf("mediaserver", "installd", "netd", "keystore")

/** Native arm64 engine: vendor daemons that only poll hardware the phone does not have (modem, DSP, TrustZone). */
private val N64_SKIP = setOf("audiod", "ATFWD-daemon", "imsqmidaemon", "loc_launcher", "cnd", "dpmd", "cnss-daemon",
    "tftp_server", "qcom-system-daemon", "hvdcp_opti", "pm-service", "mtd", "mlipayd", "vsimd", "fdpp", "perfd", "ipacm",
    "qti", "netmgrd", "port-bridge", "ims_rtp_daemon", "imsdatadaemon", "wcnss_service", "msm_irqbalance", "energy-awareness", "sscrpcd", "adsprpcd", "cdsprpcd", "audioadsprpcd")

/**
 * Одна запущенная виртуальная машина. Живёт в процессе :vm (одна на процесс: GL-мост нельзя
 * поднять дважды), заменяет собой init: готовит дерево, поднимает службы хоста (свойства, ввод,
 * звук, заглушки радио и накопителя), binderd, GL-мост и службы гостя в порядке загрузки Android.
 */
class GuestVm(val ctx: Context, val img: GuestImage, val lowPowerBoot: Boolean = false) {
    enum class State { STOPPED, PREPARING, BOOTING, RUNNING, FAILED, STOPPING }

    val paths = VmPaths(ctx, img.id)
    val kernel: Kernel64 get() = Kernel64.forApi(img.api)
    /**
     * The firmware's settings as this boot uses them. An x86 guest has no GPU bridge until the x86 build of the guest-side
     * GL library (assets/engines/kk-x86/libGLES.so) exists: a guest that is told about a GPU it cannot reach loops in
     * EGL, so the GPU and hardware UI options are off for it meanwhile (software rendering in the guest).
     */
    val settings = img.settings.let { s ->
        if (img.arch != GuestArch.ARM && s.gpu &&
            !EngineAssets.exists(ctx, "engines/${Engine.KK.id}${img.arch.assetSuffix}/libGLES.so")) s.copy(gpu = false, hwui = false) else s
    }
    /** Factory-test (MMI/FTM) build: no Java framework, only a native test UI. Booted like the charging class. */
    val nativeBoot: Boolean by lazy { !lowPowerBoot && MmiBoot.frameworkless(paths.root) && MmiBoot.service(paths.root) != null }
    /** The guest draws into fb0 itself (double-buffered minui) instead of going through SurfaceFlinger. */
    val nativeUi: Boolean get() = lowPowerBoot || nativeBoot
    private val storageLease = VmStorageLease.forVm(ctx.filesDir)
    val engine get() = img.engine

    @Volatile var state = State.STOPPED
        private set
    @Volatile var bootAt = 0L
        private set
    @Volatile var bootDoneAt = 0L
    private val noteBoot = OneTimeVmNote()
    @Volatile var pendingNote = img.oneTimeNote
        private set
    /** Guest reported boot completion; this does not prove that its launcher rendered. */
    @Volatile private var everBooted = false
    @Volatile private var zygoteRestarts = 0
    @Volatile private var imeRetried = false
    // the boot animation has gone: the lock screen or the home screen is up (7.0+ reports boot_completed only after the user unlocks)
    @Volatile var screenUpAt = 0L
    /** Times of the zygote crashes: a system that keeps dying is stopped instead of being restarted forever. */
    private val zygoteCrashes = ArrayDeque<Long>()
    /** Why the last start failed; the screen shows [Msg.text] in the chosen language, logs get the English wording. */
    @Volatile var failure: Msg? = null
        private set
    @Volatile var surface: Surface? = null
        set(v) { field = v; if (engine == Engine.KK) GlBridge.surface(v) else if (engine == Engine.N64 && kernel.gpuBridge64) GlHost.surface(v) }

    private val lines = CopyOnWriteArrayList<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val stateListeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val logFile = File(paths.bin, "aemu.log")

    fun log(s: String) {
        val line = "${stamp()} $s"
        lines.add(line)
        while (lines.size > 2000) lines.removeAt(0)
        runCatching {
            if (engine == Engine.N64 && kernel.syncLog) FileOutputStream(logFile, true).use { it.write((line + "\n").toByteArray()); it.fd.sync() }
            else logFile.appendText(line + "\n")
        }
        listeners.forEach { runCatching { it(line) } }
    }
    fun lines(): List<String> = lines.toList()
    fun onLog(l: (String) -> Unit) { listeners.add(l) }
    fun onState(l: (State) -> Unit) { stateListeners.add(l) }
    private val liveStatus = LiveVmStatus(ctx.filesDir)
    private fun setState(s: State) {
        state = s
        runCatching { liveStatus.publish(img.id, VmUiPolicy.active(s.name)) }
            .onFailure { log("library status: ${it.message}") }
        stateListeners.forEach { runCatching { it(s) } }
    }

    val props = PropService(paths, ::log).also {
        // x86 without a GL bridge: only the ES 1 software renderer exists, so apps must not be told ES 2 is there
        // (libEGL sets qemu.gles=2 on its own; see PropService.pinned)
        if (img.arch != GuestArch.ARM && !settings.gpu) it.pinned = setOf("qemu.gles")
    }
    /** ADB over the LAN, switched on from the VM menu */
    val adb = AdbServer(this)
    val input = InputService(paths, ::log)
    private val vibration = VibrationBridge(ctx, paths, { settings.vibration }, ::log)
    private val battery = HostBatteryBridge(ctx, paths.root, img.api, settings.hostBattery,
        { everBooted && state == State.RUNNING && !stopping && !recoveryMode && !lowPowerBoot },
        { zygoteRestarts }, { command ->
            val result = guestRunner.run(listOf("/system/bin/sh", "-c", command), 4_000)
            check(result.first == 0) { "Guest battery update failed: ${result.first}" }
        }, ::log)
    fun setManualBattery(percent: Int, charging: Boolean) = battery.applyManual(percent, charging)
    fun resetBattery() = battery.reset(settings.hostBattery)
    val hostBatteryLevel get() = battery.latest?.percent ?: 80
    val hostBatteryCharging get() = battery.latest?.status in listOf(2, 5)
    private val motion = MotionBridge(ctx, paths, { img.api in 9..25 && engine != Engine.N64 && !recoveryMode && !nativeUi }, { settings.motionSensors }, { settings.gyroLock }, ::log)
    fun motionVisible(visible: Boolean) { motion.visible(visible) }
    fun simulateRotation() = motion.simulateRotation()
    val cameraSupported get() = engine == Engine.KK && img.api in 14..25 && !recoveryMode && !nativeUi
    private val camera = HostCameraBridge(ctx, paths, { settings.camera && cameraSupported }, ::log)
    fun cameraVisible(visible: Boolean) { camera.visible(visible) }
    /** 4.x-прошивки пишут через входной поток нашего HAL; 2.x-движок входной поток не отдаёт. */
    val microphoneSupported get() = engine == Engine.KK && img.api in 14..25 && !recoveryMode && !nativeUi
    private val microphone = MicBridge(ctx, paths, { settings.microphone && microphoneSupported }, ::log)
    fun micVisible(visible: Boolean) { microphone.visible(visible) }
    val bluetoothSupported get() = engine == Engine.KK && img.api in 9..25 && !recoveryMode && !nativeUi
    private val bluetooth = BluetoothBridge(ctx, paths, { settings.bluetooth && bluetoothSupported }, ::log)
    val wifiSupported get() = WifiEmu.supported(engine, img.api, recoveryMode, nativeUi)
    // 2.x пишет в /dev/eac через AudioHardwareGeneric на 44,1 кГц, HAL 4.x движка — на 48 кГц
    val audio = AudioOut(paths, ::log, if (img.api < 14) 44100 else AudioOut.RATE,
        if (TreeFixer.isMtkAudio(paths.root)) "dev/aemu_pcm" else "dev/eac")
    /** CDMA modem or GSM, decided once per start: the modem and the guest's telephony properties must agree */
    private val cdmaPlan = CdmaMode.plan(img.settings, img.api, paths)
    val ril = RilStub(paths, ::log, img.settings.imei.ifBlank { VmSettings.DEFAULT_IMEI }, img.api,
        VmSettings.cleanBaseband(img.settings.baseband).ifBlank { VmSettings.DEFAULT_BASEBAND },
        img.settings.networkType, img.settings.sim, cdmaPlan.cdma, cdmaPlan.lteOnCdma)
    // vold speaks the 6.0 volume protocol when the tree is 6.0 inside, whatever API a preview build reports
    val vold = VoldStub(paths, img.sdcardPath, ::log, others = img.volumes, api = BionicProbe.ruleApi(paths.root, img.api), onStorageState = ::setStorageState)
    /** Android 1.0-2.1 take the card state from this property (Settings > Storage shows "unknown" without it) */
    private fun setStorageState(v: String) { if (img.api in 1..7) props.put(VoldStub.STATE_PROP, v) }
    var onFrame: (() -> Unit)? = null
    val frames = FrameBell(paths, ::log) { onFrame?.invoke() }
    private val events = EventsSink(paths, ::log)
    private val logd = LogdSink(paths, ::log)
    /** notification / battery LED of the guest (lights HAL → /dev/aemu_lights) */
    val lights = Lights(paths.root, ::log)
    /** the phone's accelerometer and vibration motor for the guest */
    val sensors = Sensors(ctx, paths.root, ::log)
    /** the phone's microphone for the guest (audio HAL input → /dev/aemu_mic) */
    val mic = AudioIn(ctx, paths.root, ::log)
    /** the phone's cameras for the guest (camera HAL → /dev/socket/aemu_cam) */
    val cameraFeed = CameraFeed(ctx, paths, ::log)
    @Volatile private var lmk: GuestLmk? = null
    val net = NetProxy(ctx, paths, ::log)
    private val dns = DnsProxy(paths, img.api, ::log, img.engine == Engine.N64 && Kernel64.forApi(img.api).dnsLayoutProbe)
    /** Cupcake/Donut (API <= 4) have no multitouch: their EventHub only accepts ABS_X/ABS_Y/ABS_PRESSURE + BTN_TOUCH. */
    private fun singleTouchWanted(s: VmSettings) = s.mtMode == 4 || img.api <= 4
    private fun effectiveMtMode(s: VmSettings) = if (singleTouchWanted(s)) 4 else s.mtMode

    val runner by lazy { GuestRunner(paths, img.copy(settings = settings)) }

    private val extraStubs = ArrayList<VoldStub>()
    private val procs = LinkedHashMap<String, Process>()
    private val lazyStarts = HashMap<String, Int>()
    private val pgids = LinkedHashSet<Int>()
    @Volatile private var stopping = false

    // ------------------------------------------------------------------ загрузка

    @Synchronized fun boot() {
        if (state == State.BOOTING || state == State.RUNNING || state == State.PREPARING) return
        try { storageLease.acquire() } catch (t: Throwable) {
            failure = Msg("VM storage is busy: ${t.message}", R.string.vmfail_storage_busy, t.message)
            log(failure!!.english)
            setState(State.FAILED)
            return
        }
        stopping = false
        failure = null
        setState(State.PREPARING)
        try {
            if (recoveryMode) doRecovery() else if (lowPowerBoot) doCharging() else if (nativeBoot) doMmi() else doBoot()
        } catch (t: Throwable) {
            vibration.stop()
            battery.stop()
            camera.stop()
            bluetooth.stop()
            motion.stop()
            failure = Msg.of(t)
            log("✖ boot aborted: $failure")
            setState(State.FAILED)
        }
    }

    /** Boot the firmware's recovery (RecoveryImage) instead of Android. */
    @Volatile var recoveryMode = false
    /** recovery framebuffer format for the screen view: 0 RGB565, 1 RGBA/RGBX_8888, 2 BGRA_8888 */
    @Volatile var recoveryFormat = 0

    /** Native firmware charging class only: no Android framework or normal boot plan. */
    private fun doCharging() {
        paths.bin.mkdirs()
        logFile.writeText("")
        log("Offline charging boot: ${img.name}")
        val services = ChargingBoot.services(paths.root, img.api)
        killLeftovers()
        HostNative.limitStackSafe()
        TreeFixer(ctx, paths, img, ::log).fixup()
        battery.start()
        File(paths.root, "dev/tty0").delete() // minui cannot use host VT ioctls
        val sysLink = File(paths.bin, "sys")
        if (!java.nio.file.Files.exists(sysLink.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            android.system.Os.symlink(File(paths.root, "sys").path, sysLink.path)
        if (!props.prepare(ChargingBoot.properties + ("ro.aemu.host" to "qemu-user"))) error("property area not ready")
        props.onSet = { k, v -> if (k == "sys.powerctl") powerRequest(v) }
        props.onCtl = { start, name ->
            services.firstOrNull { it.name == name }?.let { service ->
                if (start && !stopping) startService(ChargingBoot.runtimeService(paths.root, service))
                else synchronized(procs) { procs.remove(name) }?.destroyForcibly()
            }
        }
        props.serve()
        input.rateHz = settings.touchHz
        input.mtMode = effectiveMtMode(settings)
        input.legacyMouseButton = LegacyFirmware.isPreRelease(img.release)
        input.serve()
        frames.serve()
        power.writeText("")
        bootAt = System.currentTimeMillis()
        setState(State.BOOTING)
        services.forEach { service ->
            if (stopping) return
            startService(ChargingBoot.runtimeService(paths.root, service))
        }
        setState(State.RUNNING)
        log("Charging services started: ${services.joinToString { it.name }}; Android framework not started")
        Thread({
            val renderers = services.filter { it.name != "healthd-charger" }.ifEmpty { services }
            while (!stopping) {
                Thread.sleep(500)
                if (power.length() > 0) { val request = power.readText().trim(); power.writeText(""); powerRequest(request) }
                val exited = renderers.firstOrNull { service -> synchronized(procs) { procs[service.name]?.isAlive != true } }
                if (exited != null && !stopping) {
                    failure = Msg("Charging service ${exited.name} exited. Check its host log; vendor hardware support may be missing.", R.string.vmfail_charging_service_exited, exited.name)
                    log(failure!!.english); setState(State.FAILED); break
                }
            }
        }, "charging-watchdog").apply { isDaemon = true; start() }
    }

    /**
     * Framework-less factory build (ZTE MMI, Qualcomm FFBM…): run just its native test program. Same recipe as
     * doCharging — fb0 and input come from the host; no zygote and no SurfaceFlinger, but binderd + servicemanager run because vendor libs link libbinder.
     */
    private fun doMmi() {
        paths.bin.mkdirs()
        runCatching { logFile.writeText("") }
        log("image \"${img.name}\": ${img.displayVersion}, native MMI/FTM boot (no Android framework)")
        val service = MmiBoot.service(paths.root) ?: error("no MMI/FTM test program found in this firmware")
        val qemu = paths.nativeBin(img.arch.translator(engine))
        if (!qemu.canExecute()) error(translatorMissing(qemu.name))
        val others = MmiBoot.controllable(paths.root)
        killLeftovers()
        HostNative.limitStackSafe()
        TreeFixer(ctx, paths, img, ::log).fixup()
        File(paths.root, "dev/tty0").delete() // minui cannot use host VT ioctls (KDSETMODE)
        val sysLink = File(paths.bin, "sys")
        if (!java.nio.file.Files.exists(sysLink.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            android.system.Os.symlink(File(paths.root, "sys").path, sysLink.path)
        // mmi opens fonts as "system/fonts/..." (no leading slash), i.e. relative to the cwd; init runs it from "/"
        for (n in listOf("system", "vendor")) {
            val l = File(paths.bin, n)
            if (!java.nio.file.Files.exists(l.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                runCatching { android.system.Os.symlink(File(paths.root, n).path, l.path) }
        }
        val serial = VmSettings.cleanSerial(settings.serial).ifBlank { VmSettings.DEFAULT_SERIAL }
        val overrides = LinkedHashMap(MmiBoot.properties)
        overrides["ro.aemu.host"] = "qemu-user"
        overrides["ro.serialno"] = serial
        overrides["ro.boot.serialno"] = serial
        VmSettings.cleanBaseband(settings.baseband).takeIf { it.isNotEmpty() }?.let { overrides["gsm.version.baseband"] = it }
        overrides["persist.sys.timezone"] = gmtZone()
        // debugging: run/props.extra — key=value lines on top of everything else
        File(paths.bin, "props.extra").takeIf { it.isFile }?.readLines()?.forEach { l ->
            val k = l.substringBefore('=').trim()
            if (k.isNotEmpty() && !k.startsWith("#") && l.contains('=')) overrides[k] = l.substringAfter('=').trim()
        }
        if (!props.prepare(overrides)) error("property area not ready")
        props.onSet = { k, v -> if (k == "sys.powerctl") powerRequest(v) }
        // the test program starts helper daemons (ztediag, getlog…) through ctl.start
        props.onCtl = { start, name ->
            val def = others[name]
            if (def == null) log("ctl.${if (start) "start" else "stop"} $name: not started (no guest binary)")
            else {
                synchronized(procs) { procs.remove(name) }?.destroyForcibly()
                if (start && !stopping) Thread { runCatching { startService(def) } }.start()
            }
        }
        props.serve()
        input.rateHz = settings.touchHz
        // factory test code is written for the device's kernel touch driver: MT type B (mode 5) unless a mode was chosen explicitly
        input.mtMode = if (settings.mtMode == 0 || settings.mtMode == 4) 5 else settings.mtMode
        input.multiPerPid = true // the factory test opens event0 twice (minui + touch probe); the second connection must not evict the first
        input.serve()
        frames.serve()
        runCatching { power.writeText(""); power.setWritable(true, false) }
        Keeper.hold(ctx, img.name)
        bootAt = System.currentTimeMillis()
        bootDoneAt = 0
        setState(State.BOOTING)
        // The vendor libs mmi pulls in (mmi_led.so, libmm-disp-apis.so, hwcomposer…) link libbinder, which starts a binder
        // thread pool. Without binderd every BINDER_WRITE_READ fails with ENOTCONN, and libbinder's joinThreadPool()
        // abort()s on any error except TIMED_OUT/ECONNREFUSED/EBADF ("returned unexpected error -107, aborting"):
        // that was the SIGABRT after ~13 s. On the device the kernel binder is simply idle, so run binderd the same way.
        paths.binderSock.delete()
        paths.creds.let { d -> d.listFiles()?.forEach { it.delete() }; d.mkdirs() }
        startBinder()
        if (!waitFor("binder socket", 15_000) { paths.binderSock.exists() }) error("binderd failed to start")
        // FFBM starts servicemanager too (srvmag_ffbm); without it getService() would just stall or fail
        startService(GuestService("servicemanager", listOf("/system/bin/servicemanager"), optional = true))
        // The "Versions" page (mmi_product.so) calls modem_api_init()/modem_read_nv() from libmodemapi, which speak QMI to a
        // modem that does not exist here: libqmi_cci dereferences a bad client handle (SIGSEGV in the page's worker thread)
        // or the page waits forever and the UI never gets its text or its Back key. These three are all mmi_product imports.
        runCatching {
            val modemapi = File(paths.root, "system/lib/libmodemapi.so")
            if (modemapi.isFile) {
                val n = ElfPatch.returnZero(modemapi, setOf("modem_api_init", "modem_api_release", "modem_read_nv"))
                if (n > 0) log("modem API: no modem here, init/read_nv answer at once ($n func.)")
            }
        }.onFailure { log("modem API patch failed: ${it.message}") }
        // diagnostics without adb: the guest's system calls go to the service log (see dumpMmiLog)
        // Tracing slows the factory UI to a crawl (every redraw prints hundreds of lines): opt in with run/strace.keep.
        runCatching {
            val flag = File(paths.bin, "strace.${service.name}")
            if (File(paths.bin, "strace.keep").exists()) flag.createNewFile() else flag.delete()
            paths.log(service.name).delete() // one run per log, not tens of megabytes of old ones
        }
        startService(service)
        setState(State.RUNNING)
        log("MMI started: ${service.argv.joinToString(" ")}; Android framework not started")
        Thread({
            while (!stopping) {
                Thread.sleep(500)
                if (power.length() > 0) { val request = power.readText().trim(); power.writeText(""); powerRequest(request) }
                val dead = synchronized(procs) { procs[service.name]?.isAlive != true }
                if (dead && !stopping && !powerHandled) {
                    val code = synchronized(procs) { procs[service.name] }?.let { runCatching { it.exitValue() }.getOrNull() }
                    failure = Msg("MMI program ${service.argv.first()} exited (code ${code ?: "?"}). See the ${service.name} log in the VM log folder; missing vendor hardware is expected, a crash is not.",
                        R.string.vmfail_mmi_program_exited, service.argv.first(), code ?: "?", service.name)
                    log("✖ $failure"); dumpMmiLog(service.name); setState(State.FAILED); break
                }
            }
        }, "mmi-watchdog").apply { isDaemon = true; start() }
    }

    /** Copies the interesting part of the MMI program's own log into the VM log, which the app can show. */
    private fun dumpMmiLog(name: String) {
        runCatching {
            val f = paths.log(name)
            val len = f.length()
            val text = java.io.RandomAccessFile(f, "r").use { r ->
                val n = minOf(len, 600_000L).toInt(); r.seek(len - n)
                val b = ByteArray(n); r.readFully(b); String(b, Charsets.ISO_8859_1)
            }
            val lines = text.lines().map { it.take(220) }
            val key = Regex("fb0|graphics|tty0|/dev/input|ioctl|mmap|openat|open\\(|ENOENT|EACCES|EINVAL|error|fail|FATAL|cannot|can't|could not|not found", RegexOption.IGNORE_CASE)
            log("── ${name} log: ${lines.size} lines, last 40 ──")
            lines.takeLast(40).forEach { log("  $it") }
            val picked = lines.dropLast(40).filter { key.containsMatchIn(it) && !it.contains("EVIOCGBIT") }.takeLast(80)
            log("── ${name} log: ${picked.size} earlier lines about display/input/errors ──")
            picked.forEach { log("  $it") }
        }.onFailure { log("cannot read the $name log: ${it.message}") }
    }

    private fun doRecovery() {
        paths.bin.mkdirs()
        runCatching { logFile.writeText("") }
        log("image \"${img.name}\": recovery mode")
        if (!RecoveryImage.installed(paths)) error("this firmware has no recovery installed")
        val is64 = RecoveryImage.isElf64(paths)
        val qemu = paths.nativeBin(if (is64) kernel.runner else img.arch.translator(Engine.KK))
        if (!is64 && !(img.arch == GuestArch.ARM && img.settings.native32 && CpuInfo.hasAArch32) && !qemu.canExecute()) error(translatorMissing(qemu.name))
        killLeftovers()
        HostNative.limitStackSafe()
        TreeFixer(ctx, paths, img, ::log).fixup()   // framebuffer, input node
        battery.start()
        val sd = Sdcard.setup(ctx, paths, img, ::log)
        val prep = RecoveryImage.prepare(paths, sd, ::log, img.api, settings)
        input.sinkProtocolB = prep.touchProtocolB
        prep.format?.let { recoveryFormat = it; log("recovery framebuffer: ${when (it) { 0 -> "RGB565"; 2 -> "BGRA_8888"; else -> "32-bit RGBX" }}") }
        input.rateHz = settings.touchHz
        input.mtMode = settings.mtMode
        input.trackballEnabled = false // recovery does not load the Android evdev shim
        input.serve()
        // recovery's minui opens /dev/input/event* through openat():
        // a FIFO there gets the raw input_event stream straight from InputService
        runCatching {
            val ev = File(RecoveryImage.dir(paths), "dev/input/event0")
            ev.delete()
            android.system.Os.mkfifo(ev.path, "600".toInt(8))
            val fd = android.system.Os.open(ev.path, android.system.OsConstants.O_RDWR or android.system.OsConstants.O_NONBLOCK, 0)
            input.sink = java.io.FileOutputStream(fd)
            for (alt in listOf("event1", "event2", "event3")) {
                val f = File(RecoveryImage.dir(paths), "dev/input/$alt")
                f.delete()
                runCatching { android.system.Os.symlink(ev.path, f.path) }
            }
        }.onFailure { log("recovery: no input channel: ${it.message}") }
        val (w, h) = runCatching { File(paths.root, "dhd.fbgeom").readText().trim().split(Regex("\\s+")).map { it.toInt() } }
            .getOrNull()?.takeIf { it.size >= 2 }?.let { it[0] to it[1] } ?: (settings.width to settings.height)
        val r = RecoveryImage.dir(paths)
        runCatching { File(r, "tmp/recovery.log").delete() }
        // debugging: run/recovery.strace traces the guest's system calls into run/recovery.trace
        val trace = if (File(paths.bin, "recovery.strace").exists()) listOf("-strace", "-D", File(paths.bin, "recovery.trace").absolutePath) else emptyList()
        val cmd = when {
            img.arch == GuestArch.ARM && img.settings.native32 && CpuInfo.hasAArch32 && !is64 -> listOf(File(r, "sbin/recovery").absolutePath)
            is64 -> listOf(qemu.absolutePath, "/sbin/recovery", File(r, "sbin/recovery").absolutePath)
            else -> listOf(qemu.absolutePath, "-L", r.absolutePath, "-0", "/sbin/recovery") + trace + File(r, "sbin/recovery").absolutePath
        }
        val pb = ProcessBuilder(cmd)
            .directory(r).redirectErrorStream(true).redirectOutput(File(paths.bin, "recovery.out"))
        pb.environment().clear()
        pb.environment().putAll(mapOf(
            "PATH" to "/sbin:/system/bin", "LD_LIBRARY_PATH" to ".:/sbin", "ANDROID_ROOT" to "/system", "ANDROID_DATA" to "/data",
            "EXTERNAL_STORAGE" to "/sdcard", "TZ" to "UTC",
            "LD_PRELOAD" to "/sbin/librecshim.so:/sbin/libaemushim.so", "AEMU_MOUNTS" to "/sdcard:/data:/system:/cache:/emmc:/external_sd:/usb-otg",
            "DHD_FB_W" to "$w", "DHD_FB_H" to "$h", "DHD_IN_W" to "$w", "DHD_IN_H" to "$h",
            "DHD_INPUT" to paths.inputSock.absolutePath,
        ))
        powerHandled = false
        bootAt = System.currentTimeMillis()
        val p = pb.start()
        synchronized(procs) { procs["recovery"] = p }
        setState(State.RUNNING)
        log("recovery started (${w}x$h)")
        // pixel format the recovery draws in, from its own log ("Pixel format: BGRA_8888", TWRP)
        Thread {
            val rlog = File(r, "tmp/recovery.log")
            repeat(40) {
                if (!p.isAlive || stopping) return@Thread
                val t = runCatching { rlog.readText() }.getOrDefault("")
                val fmt = Regex("Pixel format: (BGRA_8888|RGBX_8888|RGBA_8888|RGB_565)").findAll(t).lastOrNull()?.groupValues?.get(1)
                if (fmt != null) {
                    recoveryFormat = when (fmt) { "BGRA_8888" -> 2; "RGB_565" -> 0; else -> 1 }
                    log("recovery draws $fmt")
                    return@Thread
                }
                Thread.sleep(250)
            }
        }.start()
        // reboot requests of the recovery (librecshim → /dev/aemu_power)
        val power = File(r, "dev/aemu_power")
        Thread {
            while (p.isAlive && !stopping) {
                if (power.length() > 0) {
                    val v = runCatching { power.readText().trim() }.getOrDefault("reboot"); power.writeText("")
                    powerRequest(v); break
                }
                Thread.sleep(500)
            }
        }.start()
        Thread {
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (stopping || powerHandled) return@Thread
            // a normal "reboot system now" writes its request to /dev/aemu_power and then exits: honour it before calling this a failure
            val pw = File(RecoveryImage.dir(paths), "dev/aemu_power")
            val req = if (pw.length() > 0) runCatching { pw.readText().trim() }.getOrDefault("") else ""
            if (req.isNotEmpty()) { runCatching { pw.writeText("") }; powerRequest(req); return@Thread }
            powerHandled = true
            // do not fall through to the system: keep the evidence and let the person choose (retry recovery / boot system)
            recoveryEnded("recovery process exited by itself (code $code) after ${(System.currentTimeMillis() - bootAt) / 1000.0} s",
                "exit code $code")
        }.start()
    }

    /** A recovery that quits (or asks to reboot) right after it started is a failure, not a reboot request. */
    private fun recoveryEarlyExit(): Boolean = recoveryMode && System.currentTimeMillis() - bootAt < RECOVERY_EARLY_MS

    /**
     * The recovery ended without the person asking for it. Saves what the recovery and the host said to run/recovery-last.log
     * (the next start truncates aemu.log), writes the tail into the host log, and parks the VM in FAILED so the screen can offer
     * "try recovery again" / "boot the system".
     */
    private fun recoveryEnded(what: String, shortWhat: String) {
        log("✖ $what")
        val r = RecoveryImage.dir(paths)
        val recLog = runCatching { File(r, "tmp/recovery.log").readLines() }.getOrDefault(emptyList())
        val recOut = runCatching { File(paths.bin, "recovery.out").readLines() }.getOrDefault(emptyList())
        log("recovery: tmp/recovery.log ${if (recLog.isEmpty()) "is empty or missing" else "(last ${minOf(40, recLog.size)} of ${recLog.size} lines)"}")
        recLog.takeLast(40).forEach { log("  recovery.log| $it") }
        log("recovery: stdout/stderr ${if (recOut.isEmpty()) "is empty (a recovery writes its messages to tmp/recovery.log)" else "(last ${minOf(40, recOut.size)} lines)"}")
        recOut.takeLast(40).forEach { log("  recovery.out| $it") }
        runCatching {
            File(paths.bin, "recovery-last.log").writeText(buildString {
                append("=== host log (aemu.log) ===\n"); this@GuestVm.lines().forEach { append(it).append('\n') }
                append("\n=== recovery tmp/recovery.log (last 400 lines) ===\n"); recLog.takeLast(400).forEach { append(it).append('\n') }
                append("\n=== recovery stdout/stderr (run/recovery.out, last 400 lines) ===\n"); recOut.takeLast(400).forEach { append(it).append('\n') }
            })
            log("recovery: log saved to run/recovery-last.log (it is kept across restarts and goes into the exported log bundle)")
        }.onFailure { log("recovery: could not save the log: ${it.message}") }
        synchronized(procs) { procs["recovery"] }?.let { runCatching { it.destroyForcibly() } }
        failure = Msg("Recovery stopped on its own ($shortWhat). Its log was saved to run/recovery-last.log.",
            R.string.vmfail_recovery_exited, shortWhat)
        setState(State.FAILED)
    }

    /** Why a translator cannot be started. The x86-32 one is a separate build of the same QEMU that may be absent from an APK. */
    private fun translatorMissing(name: String): String =
        if (name == GuestArch.X86.qemuLib) "this firmware is x86 and the x86 translator ($name) is not in this build of the app"
        else "translator $name is not executable"

    /** A pre-1.0 tree (see LegacyFirmware.isLegacyLayout): the stubs below are for 1.5+ and cannot start on it. */
    private val legacyFw: Boolean by lazy { LegacyFirmware.isLegacyLayout(paths.root) }

    /** The services of the image. For a pre-1.0 tree the list made now: an image imported earlier has its runtime under another name. */
    private fun storedServices(): List<GuestService> = if (legacyFw) servicePlan() else img.services

    private fun doBoot() {
        paths.bin.mkdirs()
        runCatching { logFile.writeText("") }
        log("image \"${img.name}\": ${img.displayVersion}, ${img.skin}, engine ${engine.title}")
        if (img.arch != GuestArch.ARM) log("· guest architecture ${img.arch.id}: translator ${img.arch.qemuLib}, no helper shims until the ${img.arch.id} builds are in assets/engines/*${img.arch.assetSuffix}")
        else if (QemuFallback.enabled(paths)) log("translator: ${engine.qemuXperiaPlay} (patched guest mmap base)")
        // pre-1.0 firmware keeps its Java libraries in system/javalib and links its programs against /android: make both names
        // resolve first (a tree imported without them is repaired here), then look for the framework
        LegacyFirmware.ensureLayout(paths.root, ::log)
        if (!File(paths.root, "system/framework").isDirectory) error("firmware tree missing: no system/framework (or system/javalib) in the imported tree")
        if (MmiBoot.frameworkless(paths.root))
            error("this firmware has no Android framework (system/framework/framework.jar) and no native MMI/FTM test program to run instead")
        val preOne = engine != Engine.N64 && img.arch == GuestArch.ARM && LegacyFirmware.isPreRelease(img.release) &&
            paths.nativeBin(LegacyFirmware.PRE_RELEASE_TRANSLATOR).canExecute()
        val qemu = paths.nativeBin(if (engine == Engine.N64) kernel.runner else if (preOne) LegacyFirmware.PRE_RELEASE_TRANSLATOR else img.arch.translator(engine))
        if (!qemu.canExecute()) error(translatorMissing(qemu.name))
        if (preOne) log("translator: ${LegacyFirmware.PRE_RELEASE_TRANSLATOR} (pre-1.0 build: readable memory is executable)")
        if (engine == Engine.N64) log("· kernel ${kernel.tag} (${kernel.runner})")
        // a 32-bit-only firmware on the native engine: every program of it, init's services included, runs in qemu-arm
        if (engine == Engine.N64 && isPure32Tree(paths.root)) {
            if (!paths.nativeBin("libqemu_arm.so").canExecute())
                error("this firmware is 32-bit only and the native engine has no translator (libqemu_arm.so) in this build")
            log("· 32-bit firmware: translated by qemu-arm on the native engine")
        }
        // the guest's SwiftShader (legacy, reads SwiftShader.ini from the process's directory, this one) finds one core
        // on Android and draws every frame in a single thread: MIUI 8 at 1080×1920 flipped pages at 19 fps, 59 with 8.
        // Every N64 kernel ships that build (n64-gl), and the guest's services and apps all run in this directory
        // (run/SwiftShader.off: the old single thread, to compare)
        if (engine == Engine.N64) runCatching {
            if (File(paths.bin, "SwiftShader.off").exists()) File(paths.bin, "SwiftShader.ini").delete()
            else File(paths.bin, "SwiftShader.ini").writeText("[Processor]\nThreadCount=${Runtime.getRuntime().availableProcessors().coerceIn(1, 8)}\n")
        }
        killLeftovers()
        HostNative.limitStackSafe()

        // 1. дерево
        val fixer = TreeFixer(ctx, paths, img, ::log)
        fixer.fixup()
        battery.start()
        // политика звука уже подменялась раньше — обновить её файлы (обёртка/AOSP) до текущей версии
        if (File(paths.root, "system/.aemu-parked/system#lib#hw#audio_policy.default.so").isFile) swapAudioPolicy()
        GuestLog.clear(paths.root)
        val sd = Sdcard.setup(ctx, paths, img, ::log)

        // 2. свойства
        val s = settings
        val overrides = LinkedHashMap<String, String>()
        // 2.3: ro.kernel.qemu=1 включает в фреймворке режим эмулятора — ставим его только mediaserver
        overrides["ro.kernel.qemu"] = if (engine == Engine.GB) "0" else "1"
        overrides["ro.kernel.qemu.gles"] = if (s.gpu && (s.hwui || engine == Engine.GB)) "1" else "0"
        overrides["qemu.gles"] = if (s.gpu) "1" else "0"
        if (engine == Engine.N64) {             // SwiftShader (GLES 2/3 on the CPU) where the engine has it for this release
            // (8.0+ libEGL picks the driver by name: 1 is the emulator's host GPU pipe, 2 is SwiftShader)
            // (there libEGL takes it from /vendor/lib64/egl, and without that directory puts the flag back to 0)
            // (a firmware with no 64-bit side - Android Things, Wear OS - only gets the engine's 32-bit SwiftShader in
            // system/lib/egl: its 64-bit libEGL is never installed, and checking only that one put the flag at 0, so
            // libEGL fell back to libGLES_android.so (GLES 1 only) and SurfaceFlinger aborted with "no suitable
            // EGLConfig found, giving up")
            val hasSs = File(paths.root, kernel.eglLib).isFile ||
                File(paths.root, "system/lib/egl/libEGL_swiftshader.so").isFile
            val ss = if (hasSs) kernel.gles else "0"
            // (32-bit programs read it as 0 through the runner: they only have the GLES 1 renderer)
            overrides["ro.kernel.qemu.gles"] = ss; overrides["qemu.gles"] = ss
        }
        var glBridge = false
        var glInfo = ""
        if (engine == Engine.N64 && kernel.gpuBridge64 && s.gpu && File(paths.root, "${kernel.eglDir}/libGLES_aemu.so").isFile) {
            val r = GlHost.start(paths.socket("aemu_gl").absolutePath)
            log("GPU bridge: $r")
            glBridge = r.startsWith("ok"); glInfo = r
        }
        // 32-bit engine, test switch "glopen" (TreeFixer put the open bridge in front of the engine's own): programs'
        // contexts go to the same host renderer as on the 64-bit engine; the file's text is the mode (default 2: here the
        // "software" driver under the bridge is the engine's own, which has no texture EGLImages, and WebView's
        // threads share their tiles through those - in mode 1 they stay on it and a page draws only its background)
        if (engine == Engine.KK && s.gpu && File(paths.dir, "glopen").isFile && File(paths.root, "system/lib/egl/libGLES_split.so").isFile) {
            paths.socket("aemu_gl").parentFile?.mkdirs()
            val r = GlHost.start(paths.socket("aemu_gl").absolutePath)
            log("open GPU bridge (32-bit): $r")
            if (r.startsWith("ok")) overrides["debug.aemu.gl"] = File(paths.dir, "glopen").readText().trim().takeIf { it.isNotEmpty() } ?: "2"
        }
        // 7.0+ libEGL ignores egl.cfg and takes the first libGLES_*.so it finds; without the bridge: the software one
        if (img.api >= 24 && !s.gpu) {
            overrides["ro.hardware.egl"] = "android"
        }
        // ro.boot.hardware comes from the boot loader on a phone (androidboot.hardware); ColorOS tells Qualcomm from
        // MediaTek by it (OplusTelephonyManager: starts with "qcom" or "mt"), and with neither its Telecom has no SoC
        // side at all: system_server dies in CallAudioManager. Named as the firmware's own init.<hardware>.rc is.
        if (engine == Engine.N64 && kernel.bootHardwareProp) {
            val hw = File(paths.root, "vendor/etc/init/hw").list().orEmpty().mapNotNull { Regex("^init[.](qcom|mt[0-9]+[a-z]*)[.]rc$").find(it)?.groupValues?.get(1) }.firstOrNull()
            if (hw != null) overrides["ro.boot.hardware"] = hw
        }
        overrides["ro.sf.lcd_density"] = s.density.toString()
        overrides["qemu.sf.lcd_density"] = s.density.toString()
        // MIUI 10 forces the density of the phone's own panel from this one (440 on a 540-pixel screen)
        if (listOf("system/build.prop", "vendor/build.prop").any { runCatching { File(paths.root, it).readText() }.getOrDefault("").contains("persist.miui.density_v2") }) overrides["persist.miui.density_v2"] = s.density.toString()
        // Panels mounted sideways (Galaxy Tab 10.1 GT-P7300: "ro.sf.hwrotation=90", fb 800x1280 on a landscape UI) make
        // SurfaceFlinger turn the whole UI inside the framebuffer. Here the framebuffer IS the screen the user sees at
        // the resolution they picked, so the rotation only shows up as a UI lying on its side: 1280x720 came up as a
        // sideways portrait UI, 720x1280 as a sideways landscape one. Run the panel unrotated; the UI then follows the
        // framebuffer (wide = landscape, tall = portrait). run/props.extra can still set it back.
        val hwRot = runCatching {
            File(paths.root, "system/build.prop").readLines()
                .firstOrNull { it.trim().startsWith("ro.sf.hwrotation=") }?.substringAfter('=')?.trim()?.toIntOrNull()
        }.getOrNull()
        if (hwRot != null && hwRot != 0) {
            overrides["ro.sf.hwrotation"] = "0"
            log("display: ignoring the firmware's panel rotation ($hwRot°), the UI follows the framebuffer")
        }
        // Android 1.0-2.1: Environment.getExternalStorageState() reads this property (the real mountd/vold set it)
        if (img.api in 1..7) overrides[VoldStub.STATE_PROP] = VoldStub.MOUNTED_PROP
        overrides["ro.aemu.host"] = if (engine == Engine.N64) "native" else "qemu-user"
        // serial number from the image settings (blank = default); real devices expose it under both names
        val serial = VmSettings.cleanSerial(s.serial).ifBlank { VmSettings.DEFAULT_SERIAL }
        overrides["ro.serialno"] = serial
        overrides["ro.boot.serialno"] = serial
        // baseband from the settings; the RIL stub answers the same string
        VmSettings.cleanBaseband(s.baseband).takeIf { it.isNotEmpty() }?.let { overrides["gsm.version.baseband"] = it }
        // ro.hardware from the settings (blank = the firmware's own value); the guest picks HAL libraries by it
        VmSettings.cleanHardware(s.hardware).takeIf { it.isNotEmpty() }?.let {
            overrides["ro.hardware"] = it
            log("props: ro.hardware=$it (from settings)")
        }
        if (s.camera && cameraSupported) overrides["ro.hardware.camera"] = "aemu_host"
        if (img.api in 9..25 && engine != Engine.N64) overrides["ro.hardware.sensors"] = "aemu_host"
        if (engine == Engine.N64) {
            // the phone runs arm64 code only: the 32-bit zygote and its apps go through qemu-arm when it is there
            if (isPure32Tree(paths.root)) {
                // no 64-bit side at all: one 32-bit zygote. The firmware's own flat ABI lists stand where it has them; init of
                // 10+ derives them from the partition's (ro.product.<partition>.cpu.abilist32...), which nothing does here, and
                // app_process aborts on an empty ro.product.cpu.abilist32 ("Unable to determine ABI list")
                overrides["ro.zygote"] = "zygote32"
                val abi32 = pure32Abilist32()
                overrides["ro.product.cpu.abilist32"] = abi32
                overrides["ro.product.cpu.abilist"] = abi32
                overrides["ro.product.cpu.abilist64"] = ""
                log("props: 32-bit firmware, ro.product.cpu.abilist32=$abi32")
            } else if (n64Has32) {
                overrides["ro.zygote"] = "zygote64_32"
                overrides["ro.product.cpu.abilist"] = "arm64-v8a,armeabi-v7a,armeabi"
                overrides["ro.product.cpu.abilist64"] = "arm64-v8a"
                overrides["ro.product.cpu.abilist32"] = "armeabi-v7a,armeabi"
            } else {
                overrides["ro.zygote"] = "zygote64"
                overrides["ro.product.cpu.abilist"] = "arm64-v8a"
                overrides["ro.product.cpu.abilist64"] = "arm64-v8a"
                overrides["ro.product.cpu.abilist32"] = ""
            }
        }
        overrides["dalvik.vm.execution-mode"] = if (s.jit) "int:jit" else "int:fast"
        if (s.lowRam || s.ramMb in 1..768) overrides["ro.config.low_ram"] = "true"
        if (s.ramMb > 0) {
            // Dalvik heap sized from the guest RAM budget (like a real device of that class)
            val heap = (s.ramMb / 4).coerceIn(32, 512)
            overrides["dalvik.vm.heapsize"] = "${heap}m"
            overrides["dalvik.vm.heapgrowthlimit"] = "${(heap / 2).coerceAtLeast(24)}m"
        }
        // Cupcake..Froyo PhoneFactory.makeDefaultPhones(): SystemProperties.get("ro.radio.noril", "").equals("") -> real gsm.RIL
        // (our RilStub on /dev/socket/rild), ANY other value -> SimulatedCommands. The importer writes "false", which is not
        // empty, so the framework always took the built-in fake modem (SC< logs, "El Telco Loco", sendSMS answered with a
        // RuntimeException that SMSDispatcher.handleSendComplete casts to CommandException -> com.android.phone dies).
        if (!s.radio) overrides["ro.radio.noril"] = "true"
        else if (img.api <= 8) overrides["ro.radio.noril"] = ""
        // CDMA off: lteOnCdmaDevice stays 0 even if a guest init script sets it
        props.pinned = props.pinned + CdmaMode.pinnedProps(cdmaPlan, img.api)
        overrides.putAll(CdmaMode.propOverrides(cdmaPlan, img.api, ril.operatorNumeric, ril.operatorName) { CdmaMode.firmwareProp(paths, it) })
        if (!s.hwui) overrides["debug.hwui.renderer"] = "skia"
        // the 64-bit engine has GLES only (bridge or SwiftShader), no Vulkan device: hwui of firmwares that default to
        // Vulkan (HyperOS 2, MediaTek) aborts "Assertion failed: !gpuCount" in system_server's RenderThread
        else if (engine == Engine.N64) overrides["debug.hwui.renderer"] = "skiagl"
        // PBO для текстур шрифтов (hwui 4.4+ на GLES3) ломают часть драйверов (Mali) — грузим текстуры напрямую
        if (HostInfo.gpu().contains("mali")) overrides["ro.hwui.use_gpu_pixel_buffers"] = "false"
        if (img.api in 19..20 && !isLpw39) overrides["persist.sys.dalvik.vm.lib"] = "libdvm.so"
        // Glass (4.0.x): CameraService's Gcam (HDR+) path pre-allocates 2592x1944 buffers in the OMAP format 0x100
        // (TI NV12); the emulated gralloc refuses it (EINVAL), dequeueBuffer fails and libgui then dereferences a null
        // buffer: mediaserver dies with SIGSEGV on the first camera open. Without Gcam the plain HAL path is used.
        if (img.api in 9..15) overrides["persist.lab.gcam"] = "0"
        // зигота 4.4+ заранее открывает EGL; дети после fork наследуют соединение моста, и гостевая
        // библиотека моста уходит в бесконечную рекурсию (падение по стеку в каждом приложении)
        if (img.api >= 19) overrides["ro.zygote.disable_gl_preload"] = "1"
        if (engine == Engine.N64) overrides.putAll(kernel.props)
        if (glBridge) {
            overrides["ro.hardware.egl"] = "aemu"
            if (kernel.bridgeAsEmulation) {
                overrides["ro.kernel.qemu.gles"] = "1"; overrides["qemu.gles"] = "1"
                File(paths.root, "${kernel.eglDir}/libGLES_aemu.so").copyTo(File(paths.root, "${kernel.emulationDir ?: kernel.eglDir}/libGLES_emulation.so"), overwrite = true)
            }
            // which contexts go to the GPU (native/glbridge/guest.c, policy): 3 - everything, the compositor
            // included; a file "glmode" in the image's directory sets another for a test (0: none, 1: programs' own
            // contexts, 2: the window toolkit's as well)
            overrides["debug.aemu.gl"] = File(paths.dir, "glmode").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } ?: kernel.gpuBridgeMode
            // GLES 3.0, or 3.1 when the phone's contexts have it (the renderer says "ES 3.1 to the guest")
            overrides["ro.opengles.version"] = if (glInfo.contains("ES 3.1 to the guest")) "196609" else "196608"
        }
        // ART first boot compiles every app with dex2oat under qemu (minutes, single-threaded): by default only
        // skip it (verify-none: ART verifies classes lazily when an app runs) and use several threads; "full compilation" in the settings keeps machine code
        if (img.api >= 21) {
            // (the native arm64 engine too: dex2oat is a 32-bit program there and runs translated; the boot image is
            // still compiled in full, it has its own filter)
            if (!s.fullDexopt) overrides["dalvik.vm.dex2oat-filter"] = "verify-none"
            // 7.0+: PackageManager picks the filter per reason from pm.dexopt.*
            if (img.api >= 24 && !s.fullDexopt && engine != Engine.N64) for (r in listOf("first-boot", "boot", "install", "bg-dexopt", "ab-ota", "core-app", "forced-dexopt", "nsys-library"))
                overrides["pm.dexopt.$r"] = "verify-none"
            overrides["dalvik.vm.dex2oat-flags"] = "-j" + Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            // ART (5.0+) relocates the boot image to a random address (patchoat), which fails under qemu
            // (the relocated oat has a wrong oatdata offset, zygote then dies in dex2oat): keep it in place
            if (img.api >= 21) {
                overrides["dalvik.vm.extra-opts"] = "-Xnorelocate"
                // installd's dex2oat starts its own runtime: without this it tries patchoat too and fails every app
                overrides["dalvik.vm.dex2oat-flags"] = overrides["dalvik.vm.dex2oat-flags"] + " --runtime-arg -Xnorelocate"
                // Samsung 5.x/6.0 installd hands the whole property to dex2oat as one argument, which then fails to parse
                // ("-j" value '4 --runtime-arg -Xnorelocate'): split it in a dex2oat wrapper. Other devices are untouched.
                if (isSamsungImage && img.api in 21..23 && Dex2oatArgSplit.install(paths.root))
                    log("dex2oat: wrapper splits the combined dex2oat-flags argument (Samsung installd)")
            }
        }
        // Android L preview (API 20, build LPW39): ART only. Its libart has no -Xrelocate/-Xnorelocate and there is no patchoat,
        // so the 5.0 settings above must NOT be applied (an unknown runtime option aborts zygote at once).
        // The firmware has no pre-built boot image, so zygote runs dex2oat on first boot. Under qemu that dex2oat dies in
        // art/runtime/class_linker.cc:3371 (Check failed: IsExceptionPending, android.util.Log) while running
        // SQLiteDebug.<clinit> at compile time. Initialising only the core-library classes of framework.jar's preloaded-classes
        // (java.*, libcore.*, ...) avoids that; framework classes are then simply loaded lazily from the dex files.
        if (isLpw39 && img.api == 20) {
            overrides["dalvik.vm.dex2oat-flags"] = "--compiler-filter=interpret-only"
            // dex2oat finishes the image and then aborts in exit() (libsigchain: "Unable to find next sigaction"); see Lpw39Dex2oat
            if (Lpw39Dex2oat.install(paths.root)) log("LPW39: dex2oat wrapped (SIGABRT after a complete write counts as success)")
            if (writeLpw39ImageClasses()) {
                // later options win over the --image-classes* that libart puts on the dex2oat command line
                overrides["dalvik.vm.image-dex2oat-flags"] = "--image-classes-zip=/system/framework/aemu-imgclasses.zip --image-classes=preloaded-classes"
            }
        }
        // порты MIUI правят framework на smali так, что Dalvik-верификатор отвергает классы ядра
        // (зигота падает на VerifyError) — на телефонах они живут с выключенной проверкой байткода
        if (img.skin.contains("MIUI", true) && img.runtime == "dalvik") overrides["dalvik.vm.dexopt-flags"] = "v=n,o=a,m=y"
        // Dalvik's first boot runs dexopt over every apk without an odex, one after another and translated (S III: 88
        // apps, 104 s), and verification is most of it; like ART's verify-none above, unless "full compilation" is on
        else if (img.runtime == "dalvik" && !s.fullDexopt) overrides["dalvik.vm.dexopt-flags"] = "v=n,o=a"
        // MediaTek: звук, камера, радио ждут «NVRAM готов» от nvram_daemon, которого у нас нет
        if (TreeFixer.isMtkAudio(paths.root) || File(paths.root, "system/bin/nvram_daemon").isFile) overrides["nvram_init"] = "Ready"
        // TouchWiz 4.1+: TvOutService and Samsung EDS look for HDMI/MHL hardware and keep crashing or blocking boot
        if (img.api >= 16 && (img.skin.contains("TouchWiz", true) || img.skin.contains("One UI", true))) {
            overrides["ro.tvout.enable"] = "false"
            overrides["ro.SecEDS.enable"] = "false"
        }
        // своя панель кнопок у нас — экранную панель гостя (4.x) прячем, чтобы не дублировать
        overrides["qemu.hw.mainkeys"] = if (s.showNavBar && !(engine == Engine.N64 && kernel.navBarRequired)) "1" else "0"
        // отладка: run/props.extra — строки ключ=значение поверх всего остального
        File(paths.bin, "props.extra").takeIf { it.isFile }?.readLines()?.forEach { l ->
            val k = l.substringBefore('=').trim()
            if (k.isNotEmpty() && !k.startsWith("#") && l.contains('=')) overrides[k] = l.substringAfter('=').trim()
        }
        // 12+: package lists and settings stay binary XML (the default). An image once started with this set to false
        // (15.10, briefly) keeps the value in its saved properties, and 14 then cannot read its own text files
        // ("illegal attribute name: :strings" in DevicePolicyManagerService): put back explicitly.
        if (engine == Engine.N64) overrides["persist.sys.binary_xml"] = "true"
        overrides["net.dns1"] = "8.8.8.8"
        overrides["net.dns2"] = "1.1.1.1"
        // в прошивках старая база часовых поясов — передаём текущее смещение, а не название зоны
        overrides["persist.sys.timezone"] = gmtZone()
        // what init reads after /system/build.prop: the default properties of a system-only image (8.0+) and the
        // vendor's own files (on a Treble image the vendor side is the engine's). Only what the image does not say.
        val later = LinkedHashMap<String, String>()
        for (n in listOf("system/etc/prop.default", "vendor/default.prop", "vendor/build.prop")) runCatching {
            File(paths.root, n).takeIf { it.isFile }?.let { f -> PropArea.parseProps(f.readText()).forEach { (k, v) -> later.putIfAbsent(k, v) } }
        }
        // A firmware's own vendor built without VNDK (15+: no ro.vndk.version, the system has the unversioned LLNDK
        // list) must keep it unset - linkerconfig then gives the vendor the current LLNDK set; with the engine's
        // "34" it took VNDK 34's list and vendor libbinder lost libapexsupport.so.
        val vndkLessVendor = File(paths.root, "vendor/build.prop").isFile && !later.containsKey("ro.vndk.version") &&
            File(paths.root, "system/etc/llndk.libraries.txt").isFile
        if (engine == Engine.N64) kernel.propDefaults.forEach { (k, v) ->
            if (!(vndkLessVendor && k == "ro.vndk.version")) later.putIfAbsent(k, v)
        }
        if (engine == Engine.N64 && kernel.derivedIdentity) deriveIdentity(later)
        // Without Treble's own check (it needs SELinux), system_server of 12 compares the partitions' fingerprints
        // and shows "There's an internal problem with your device" when the vendor's is of another build - which it
        // is on every phone updated from an earlier release.
        // init reads the vendor's properties after the system's and a later value wins (but for ro.*): the generic
        // system part of such a firmware says a 36 MB Java heap, the vendor's file has the phone's real sizes
        if (engine == Engine.N64 && kernel.vendorHeapProps) runCatching {
            PropArea.parseProps(File(paths.root, "vendor/build.prop").readText()).forEach { (k, v) ->
                if (k.startsWith("dalvik.vm.heap")) overrides.putIfAbsent(k, v)
            }
        }
        if (engine == Engine.N64 && kernel.mediaProfilesFallback) runCatching {
            val etc = File(paths.root, "vendor/etc")
            // (Qualcomm's libmedia opens _vendor, or the chip's name on bengal: _khaje, _scuba)
            listOf("media_profiles_V1_0.xml", "media_profiles.xml").firstOrNull { File(etc, it).isFile }?.let { have ->
                for (name in listOf("vendor", "khaje", "scuba")) File(etc, "media_profiles_$name.xml").let {
                    if (!it.exists()) runCatching { android.system.Os.symlink(have, it.absolutePath) }
                }
            }
        }
        if (engine == Engine.N64 && kernel.oneFingerprint) runCatching {
            PropArea.parseProps(File(paths.root, "system/build.prop").readText())["ro.system.build.fingerprint"]?.let { fp ->
                overrides["ro.vendor.build.fingerprint"] = fp; overrides["ro.bootimage.build.fingerprint"] = fp
            }
        }
        if (!props.prepare(overrides, later, if (engine == Engine.N64) kernel.propAreaSize else PropArea.SIZE)) error("property area not ready")
        // images imported before these defaults existed (or whose init.rc lacks them): ActivityManager needs every one of them
        if (img.api <= 15) for ((k, v) in app.aemu.importer.Analyzer.LMK_DEFAULTS) if (props.get(k).isNullOrEmpty()) props.put(k, v)
        ensureMediaProfiles()
        fixer.skipPreBoot(props)
        // Do not let the older Sony migration un-provision data completed by the explicit option.
        if (SetupWizardOption.restoreSonyFlow(s.skipSetupWizard,
                File(paths.root, "data/system/aemu-setup-skip.properties").isFile))
            fixer.restoreSonySetupFlow()
        fixer.noScreenSleep()
        if (s.skipSetupWizard) fixer.provisionBeforeBoot()
        props.onSet = { k, v -> onProp(k, v) }
        props.onCtl = { start, svc -> onCtl(start, svc) }

        // 3. службы хоста
        props.serve()
        input.rateHz = s.touchHz
        input.mtMode = effectiveMtMode(s)
        input.legacyMouseButton = LegacyFirmware.isPreRelease(img.release)
        input.detectHome(paths.root)
        input.trackballEnabled = settings.trackball
        // 64-bit firmware on the native engine reads 24-byte events; a 32-bit-only one (qemu-arm) reads 16-byte ones
        input.wide = InputService.wideFor(engine, isPure32Tree(paths.root))
        input.serve()
        if (engine != Engine.N64) vibration.serve()   // 64-bit guests get vibration through Sensors
        motion.serve()
        if (s.camera && cameraSupported) camera.serve()
        if (s.microphone && microphoneSupported) microphone.serve()
        if (s.bluetooth && bluetoothSupported) bluetooth.serve()
        frames.serve()
        if (s.radio) {
            if (cdmaPlan.cdma) log("radio: CDMA emulation on (" + (if (cdmaPlan.lteOnCdma) "firmware is LTE on CDMA" else "CDMA-only phone") + ")")
            else if (cdmaPlan.firmwareLteOnCdma) log("radio: firmware declares LTE on CDMA, CDMA emulation is off in settings: telephony.lteOnCdmaDevice cleared, GSM phone")
            ril.serve()
        } else log("radio: emulation disabled in settings")
        vold.serve()
        // 1.0-1.5: the framework talks to mountd, not vold; the real daemon is not started (InitPlan.SKIP)
        if (img.api in 1..3) VoldStub(paths, img.sdcardPath, ::log, "mountd", api = img.api, onStorageState = ::setStorageState)
            .also { it.serve(); extraStubs.add(it) }
        // у vold производителей бывают дополнительные сокеты (Samsung: usbstorage, enc_report) — отвечаем «ладно»
        runCatching {
            // 7.0+ keeps vold (with its cryptd socket) in /system/etc/init
            val rcFiles = listOf(paths.root, File(paths.root, "system/etc/init"))
                .flatMap { d -> d.listFiles()?.filter { it.isFile && it.name.endsWith(".rc") } ?: emptyList() }
            val rc = InitPlan.parse(rcFiles)
            rc.services["vold"]?.sockets?.keys?.filter { it != "vold" }?.forEach { name ->
                VoldStub(paths, img.sdcardPath, ::log, name).also { it.serve(); extraStubs.add(it) }
            }
        }
        // MediaTek: libaudioflinger сам работает с устройством /dev/eac (звуковой драйвер MTK) и читает из
        // него — наш канал звука под тем же именем его вешает, а за ним mediaserver и всю систему.
        // Для MTK /dev/eac ведёт в /dev/null: без звука, но без зависания.
        if (TreeFixer.isMtkAudio(paths.root)) {
            runCatching {
                val eac = audio.fifo
                if (!isLinkTo(eac, "/dev/null")) { eac.delete(); android.system.Os.symlink("/dev/null", eac.absolutePath) }
                // голосовой канал модема (CCCI): без него AudioMTKHardware бесконечно ждёт модем
                for (n in listOf("ccci_pcm_rx", "ccci_pcm_tx")) {
                    val f = File(paths.root, "dev/$n")
                    if (!isLinkTo(f, "/dev/null")) { f.delete(); android.system.Os.symlink("/dev/null", f.absolutePath) }
                }
            }
            // our HAL replaces the MTK one (TreeFixer) and plays through its own channel
            audio.makeFifo(); audio.start()
            log("audio: MediaTek, emulator HAL via /dev/aemu_pcm")
        } else { audio.makeFifo(); audio.start() }
        events.start()
        // LPW39 (L preview, API 20) already logs through logd's /dev/socket/logdw; without the host receiver every ART message is lost
        if (img.api >= 21 || isLpw39) logd.start()
        lights.start()
        if (engine == Engine.N64) { sensors.start(); mic.start(); cameraFeed.start() }
        if (img.api >= 14) runCatching { dns.ensure() }
        // the RAM the guest is told it has (its /proc/meminfo, scaled by the kernel): the framework sizes its background by it
        if (engine == Engine.N64) runCatching {
            val f = File(paths.root, "dev/.aemu-ram")
            if (kernel.ramBudget) f.writeText((GuestRunner.guestRamMb(img.api, s.ramMb) * 1024L).toString()) else f.delete()
        }
        lmk = GuestLmk(paths.root, s.lowRam, ::log, s.ramMb).also { it.start() }
        val netCfg = if (s.netProxy) net.start() else GuestRunner.NetConfig()
        runner.sdcardHost = sd
        val r = GuestRunner(paths, img.copy(settings = settings), netCfg.copy(glPath = if (s.gpu) "/dev/socket/gl" else null)).also {
            it.sdcardHost = sd
            it.singleTouch = singleTouchWanted(s)
            // отладка: файл run/binder.verbose включает полную трассировку binder в binder-warn.log
            it.binderVerbose = File(paths.bin, "binder.verbose").exists()
            it.userQemuArgs = s.qemuArgs
        }
        runnerRef = r
        Keeper.hold(ctx, img.name)

        watchFb0()
        bootAt = System.currentTimeMillis()
        bootDoneAt = 0
        everBooted = false; zygoteRestarts = 0; imeRetried = false
        bootAnimCheckRunning.set(false); bootAnimFixes = 0; peripheralClaimReleased = false
        touchWizMediaStarted = false
        noteBoot.interrupted()
        pendingNote = ImageStore.get(ctx, img.id)?.oneTimeNote ?: img.oneTimeNote
        screenUpAt = 0; zygoteCrashes.clear()
        setState(State.BOOTING)

        // 4. binder
        paths.binderSock.delete()
        paths.hwbinderSock.delete()
        paths.vndbinderSock.delete()
        paths.creds.let { d -> d.listFiles()?.forEach { it.delete() }; d.mkdirs() }
        startBinder()
        if (!waitFor("binder socket", 15_000) { paths.binderSock.exists() }) error("binderd failed to start")
        if (engine == Engine.N64 && kernel.treble) {
            waitFor("hwbinder socket", 5_000) { paths.hwbinderSock.exists() }
            waitFor("vndbinder socket", 5_000) { paths.vndbinderSock.exists() }
        }

        // 11+: the linker takes its library namespaces (which APEX module gives which library to whom) from
        // /linkerconfig, written at every boot by the firmware's own generator; init runs it before any service
        if (engine == Engine.N64 && File(paths.root, kernel.linkerConfigTool).isFile) runCatching {
            apexInfoList()
            File(paths.root, "linkerconfig").mkdirs()
            // (a generator that writes the one-namespace configuration of a device before Treble for
            // ro.treble.enabled=false - MIUI 12.5's does, and no vendor program finds its VNDK - reads "true" here)
            val own = if (kernel.trebleLinkerConfig) props.privateCopy("linkerconfig", mapOf("ro.treble.enabled" to "true")) else null
            val (code, out) = r.run(listOf("/" + kernel.linkerConfigTool, "--target", "/linkerconfig"), 30_000,
                if (own != null) mapOf("AEMU64_PROPS_FILE" to own.absolutePath) else emptyMap())
            if (code != 0 || !File(paths.root, "linkerconfig/ld.config.txt").isFile) log("linkerconfig: exit $code ${out.trim().let { if (it.length > 700) it.take(400) + " ... " + it.takeLast(300) else it }}")
        }

        r.derivedExports = emptyMap()
        if (engine == Engine.N64 && File(paths.root, "apex/com.android.sdkext/bin/derive_classpath").isFile) runCatching {
            val out = File(paths.root, "data/system/environ/classpath")
            out.parentFile?.mkdirs(); out.delete()
            val (code, msg) = r.run(listOf("/apex/com.android.sdkext/bin/derive_classpath", "/data/system/environ/classpath"), 30_000)
            if (code != 0 || !out.isFile) log("derive_classpath: exit $code ${msg.trim().takeLast(300)}")
            else r.derivedExports = out.readLines().mapNotNull { l -> l.trim().split(' ', limit = 3).takeIf { it.size == 3 && it[0] == "export" }?.let { it[1] to it[2] } }.toMap()
        }

        // 5. службы гостя по плану из init.rc прошивки
        val basePlan = servicePlan().filter { it.name !in InitPlan.NEVER_START }.ifEmpty { InitPlan.fallback(img, paths.root) }
        // Samsung 2.x boot logo + PowerOn sound (playlogos1); images imported earlier have no such entry in their saved plan
        val plan = if (File(paths.bin, BootMediaServices.LEGACY_LOGO_OFF).exists()) basePlan
                   else BootMediaServices.withLegacyLogo(basePlan, paths.root, img.api)
        if (plan.any { it.name == BootMediaServices.LEGACY_LOGO }) log("boot logo: playlogos1 will run (Samsung 2.x boot animation and sound)")
        var glDone = false
        val wifiOn = settings.wifi && wifiSupported && File(paths.root, WifiEmu.JAR_PATH).isFile
        if (settings.wifi && wifiSupported && !wifiOn) log("wifi: ${WifiEmu.JAR_NAME} is not installed (build guest-wifi/build.sh), the guest keeps its normal network")
        WifiEmu.write(paths.root, settings, wifiOn)
        if (wifiOn) log("wifi: emulated network \"${settings.wifiSsid}\" (${if (WifiEmu.secured(settings)) "WPA2" else "open"})")
        // (debugging, see Kernel64.syncLog: only the first N services are started, then the guest is left as it is)
        val stopAfter = if (engine == Engine.N64 && kernel.syncLog) runCatching { File(paths.root, "dev/.aemu-stop-after").readText().trim().toInt() }.getOrNull() else null
        if (stopAfter != null) log("stop-after $stopAfter of: " + plan.filter { !it.onDemand }.mapIndexed { i, s -> "$i ${s.name}" }.joinToString())
        var started = 0
        for (svc in plan) {
            if (stopping) return
            if (svc.onDemand) { props.put("init.svc.${svc.name}", "stopped"); continue }
            if (stopAfter != null && started++ >= stopAfter) {
                log("stop-after $stopAfter: holding before ${svc.name}")
                Thread.sleep(60_000)
                log("stop-after $stopAfter: 60 s passed, alive: ${alive().joinToString()}")
                return
            }
            // GL-мост должен ждать гостя до SurfaceFlinger/zygote
            if (!glDone && (svc.name == "surfaceflinger" || svc.name == "zygote" || svc.name == "bootanim")) {
                glUp(); glDone = true
            }
            // (not on API 1, Pre-1.0 and 1.0: the placeholder services crash system_server there, see TreeFixer.installEngineFiles)
            // Samsung: native SensorService/libsensorhub wait for "sensorhubservice" forever; a placeholder binder ends the wait
            if (svc.name == "zygote" && !legacyFw && img.api >= 2 && engine == Engine.KK && File(paths.root, "system/framework/aemu-sensorhub.jar").isFile) {
                synchronized(procs) { procs.remove("aemu-sensorhub") }?.let { runCatching { it.destroyForcibly() } }
                startService(GuestService("aemu-sensorhub", listOf(appProcess,
                    "-Djava.class.path=/system/framework/aemu-sensorhub.jar", "/system/bin",
                    "app.aemu.stub.SensorHubStub", "sensorhubservice"), optional = true))
            }
            // 1.5–2.1: system_server's BluetoothA2dpService needs the BlueZ D-Bus socket, which the guest does not have, so it
            // fails to start ("Could not init BluetoothA2dpService") and "bluetooth_a2dp" is never registered. Vendor Bluetooth
            // apps then die in BluetoothA2dp.<init> ("Bluetooth A2DP service not available!", com.android.WizBTsettings
            // crashing at every BOOT_COMPLETED). The same placeholder binder answers every call "empty"; it exits at once
            // when a real service is already registered.
            if (svc.name == "zygote" && !legacyFw && img.api in 2..8 && engine == Engine.KK && File(paths.root, "system/framework/aemu-stubs.jar").isFile) {
                synchronized(procs) { procs.remove("aemu-a2dp") }?.let { runCatching { it.destroyForcibly() } }
                startStub(GuestService("aemu-a2dp", listOf(appProcess,
                    "-Djava.class.path=/system/framework/aemu-stubs.jar", "/system/bin",
                    "app.aemu.stub.BtStub", "bluetooth_a2dp"), optional = true))
            }
            // 10+: the package manager requires apexd's binder service, which is there once apexd reports "ready"
            if (svc.name == "zygote" && engine == Engine.N64 && plan.any { it.name == "apexd" })
                waitFor("apexd", 15_000) { props.get("apexd.status") == "ready" }
            // 4.2+: заглушка bluetooth_manager до зиготы (system_server с ro.kernel.qemu=1 свою не поднимает)
            if (svc.name == "zygote" && !legacyFw && img.api >= (if (engine == Engine.N64) 9 else 5) && engine != Engine.GB && File(paths.root, "system/framework/aemu-stubs.jar").isFile) {
                // 2.0–4.1 look up "bluetooth" instead; without it BluetoothAdapter is null and the vendor's code crashes. On
                // 2.0/2.1 (API 5-7, Samsung GT-I5500) that is StatusBarService.readQuickPanelSetting, called from systemReady()
                // in system_server: the NullPointerException kills it and zygote exits with 137.
                val btName = if (img.api >= 17) "bluetooth_manager" else "bluetooth"
                // Android 4.4 (API 19, incl. the KKWT Wear image): the real bridge client; everything else keeps the placeholder
                val bridgeJar = img.api == 19 && File(paths.root, "system/framework/aemu-bt.jar").isFile
                synchronized(procs) { procs.remove("aemu-bt") }?.let { runCatching { it.destroyForcibly() } }
                startStub(GuestService("aemu-bt", listOf(appProcess,
                    "-Djava.class.path=/system/framework/${if (bridgeJar) "aemu-bt.jar" else "aemu-stubs.jar"}", "/system/bin",
                    if (bridgeJar) "app.aemu.stub.BtService" else "app.aemu.stub.BtStub", btName), optional = true))
                // aemu-bt and aemu-net load the same aemu-stubs.jar. On 5.0+ the first ART start of that jar creates its
                // compiled copy in dalvik-cache; two processes doing it at once made one of them SIGBUS (exit 135) and the
                // guest then had no bluetooth_manager (BluetoothAdapter null, Wear home crash-loop). Let the first finish.
                if (!bridgeJar && img.api >= 21) {
                    val t0 = SystemClock.uptimeMillis()
                    waitFor("aemu-bt first start", 4_000) { SystemClock.uptimeMillis() - t0 > 2_500 || !alive().contains("aemu-bt") }
                }
            }
            // emulated Wi-Fi: the guest service answers WifiManager as a connected access point and, on 5.0+, registers the
            // Wi-Fi network itself (instead of the Ethernet placeholder below)
            if (svc.name == "zygote" && !legacyFw && wifiOn) {
                synchronized(procs) { procs.remove("aemu-wifi") }?.let { runCatching { it.destroyForcibly() } }
                startService(GuestService("aemu-wifi", listOf(appProcess,
                    "-Djava.class.path=/${WifiEmu.JAR_PATH}", "/system/bin", "app.aemu.stub.WifiStub"), optional = true))
            }
            // (well before zygote: apexd of 10+ looks for vold first and only then registers its own service, which the
            // package manager requires)
            if (svc.name == "surfaceflinger" && engine == Engine.N64 && kernel.binderVold && File(paths.root, "system/framework/aemu-stubs.jar").isFile) {
                synchronized(procs) { procs.remove("aemu-vold") }?.let { runCatching { it.destroyForcibly() } }
                startService(GuestService("aemu-vold", listOf("/system/bin/app_process",
                    "-Djava.class.path=/system/framework/aemu-stubs.jar", "/system/bin", "app.aemu.stub.VoldStub") + (if (kernel.volumeAtUserStart) listOf("userstart") else emptyList()), optional = true))
            }
            // 5.0–7.x: a registered network, otherwise ConnectivityService says "no active network" and browsers stay offline
            if (svc.name == "zygote" && !legacyFw && !wifiOn && (if (engine == Engine.N64) kernel.registeredNetwork else img.api in 21..25) && engine != Engine.GB && File(paths.root, "system/framework/aemu-stubs.jar").isFile) {
                synchronized(procs) { procs.remove("aemu-net") }?.let { runCatching { it.destroyForcibly() } }
                startStub(GuestService("aemu-net", listOf(appProcess,
                    "-Djava.class.path=/system/framework/aemu-stubs.jar", "/system/bin", "app.aemu.stub.NetStub") + (if (engine == Engine.N64 && Kernel64.forApi(img.api).ethernetStandIn) listOf("ethernet") else emptyList()), optional = true))
            }
            if (svc.name == "zygote" && engine == Engine.N64 && kernel.apexStandIn && File(paths.root, "system/framework/aemu-stubs.jar").isFile) {
                apexStandInList()
                synchronized(procs) { procs.remove("aemu-apex") }?.let { runCatching { it.destroyForcibly() } }
                startService(GuestService("aemu-apex", listOf("/system/bin/app_process",
                    "-Djava.class.path=/system/framework/aemu-stubs.jar", "/system/bin", "app.aemu.stub.ApexStub"), optional = true))
            }
            // services the firmware's programs call unchecked and nothing here registers
            if (svc.name == "zygote" && engine == Engine.N64 && File(paths.root, "system/framework/aemu-stubs.jar").isFile) {
                val names = Kernel64.forApi(img.api).standInServices.filter { (name, user) ->
                    runCatching { String(File(paths.root, user.substringBefore('#')).readBytes(), Charsets.ISO_8859_1).contains(user.substringAfter('#', name)) }.getOrDefault(false) }.keys
                synchronized(procs) { procs.remove("aemu-standin") }?.let { runCatching { it.destroyForcibly() } }
                if (names.isNotEmpty()) startService(GuestService("aemu-standin", listOf("/system/bin/app_process",
                    "-Djava.class.path=/system/framework/aemu-stubs.jar", "/system/bin", "app.aemu.stub.StandIn") + names, optional = true))
            }
            // 6.0-12: system_server's TrustManagerService calls KeyStore.onUserLockedStateChanged at boot phase 600 without a
            // null check. With no keymaster HAL (a 32-bit Qualcomm firmware: the engine's software keymaster cannot load its
            // libraries) keystore never registers "android.security.keystore", the call hits a null IKeystoreService,
            // system_server dies ("Failure starting system services") and zygote exits with 137. A stand-in answers
            // "no error" to every call so the system can come up (keys cannot be created without a real keymaster); a name
            // that a real keystore registered is left alone, and a real keystore that comes later replaces the stand-in.
            if (svc.name == "zygote" && engine == Engine.N64 && img.api in 23..32 &&
                File(paths.root, "system/framework/aemu-stubs.jar").isFile &&
                synchronized(procs) { procs.filter { (n, _) -> n.startsWith("keymaster") }.none { (_, p) -> p.isAlive } }) {
                log("keymaster: no keymaster HAL is running, so keystore cannot register; a stand-in answers the \"android.security.keystore\" calls")
                synchronized(procs) { procs.remove("aemu-keystore") }?.let { runCatching { it.destroyForcibly() } }
                startService(GuestService("aemu-keystore", listOf("/system/bin/app_process",
                    "-Djava.class.path=/system/framework/aemu-stubs.jar", "/system/bin", "app.aemu.stub.StandIn", "android.security.keystore"), optional = true))
            }
            // 4.4+: BatteryService.onStart dies on a null "batteryproperties" (healthd publishes it), and without the
            // battery state healthd pushes to its listener, the first isPowered() kills system_server. A 32-bit
            // firmware's static /sbin/healthd (linked at 0x8000) can fail to load under qemu-arm ("requires virtual
            // address space that is in use"): BatteryStub then serves the service from the guest's power_supply nodes.
            // (a name that is registered is left alone, so a healthd that did come up keeps its place)
            if (svc.name == "zygote" && engine == Engine.N64 && img.api >= 21 &&
                File(paths.root, "system/framework/aemu-stubs.jar").isFile &&
                synchronized(procs) { procs["healthd"]?.isAlive != true }) {
                log("healthd is not running: the battery service is served by the engine")
                synchronized(procs) { procs.remove("aemu-battery") }?.let { runCatching { it.destroyForcibly() } }
                startService(GuestService("aemu-battery", listOf("/system/bin/app_process",
                    "-Djava.class.path=/system/framework/aemu-stubs.jar", "/system/bin", "app.aemu.stub.BatteryStub"), optional = true))
            }
            if (svc.name == BootMediaServices.LEGACY_LOGO) { LegacyLogoPatch.apply(paths.root, ::log); LegacyLogoPatch.provideHeadsetState(paths.root, ::log) }
            if (svc.name == "zygote" && needsBatteryPropertiesGuard) ensureBatteryProperties(plan)
            startService(svc)
            svc.waitSocket?.let { sock -> waitFor("socket $sock", 20_000) { paths.socket(sock).exists() } }
            if (svc.delayMs > 0) Thread.sleep(svc.delayMs)
            if (svc.name == "surfaceflinger") startTouchWizBootMedia()
            if (svc.name == "installd") watchUserZeroLink()
        }
        if (!glDone) glUp()
        startTouchWizBootMedia() // no-op if already started above (e.g. a plan without surfaceflinger)
        log("system started: ${alive().joinToString()}")
        watchLegacyBoot()
        if (SetupWizardOption.runHelper(settings.skipSetupWizard,
                File(paths.root, "data/system/aemu-setup-skip.properties").isFile)) {
            val setupRunner = guestRunner
            Thread({
                if (!stopping) runCatching {
                    val (code, output) = setupRunner.run(listOf(appProcess,
                        "-Djava.class.path=/system/framework/aemu-setup.jar", "/system/bin",
                        "app.aemu.setup.SetupCtl", if (settings.skipSetupWizard) "skip" else "restore"), 180_000,
                        mapOf("DHD_UID" to "1000", "DHD_GID" to "1000"))
                    log("setup option: exit=$code ${output.trim()}")
                }.onFailure { log("setup option failed: ${it.message}") }
            }, "setup-option").start()
        }
        if (GoogleAppsOption.runHelper(img.api, settings.disableGoogleApps,
                File(paths.root, "data/system/aemu-google-disabled.properties").isFile, recoveryMode, lowPowerBoot)) {
            val googleRunner = guestRunner
            Thread({
                if (!stopping) runCatching {
                    val (code, output) = googleRunner.run(listOf(appProcess,
                        "-Djava.class.path=/system/framework/aemu-setup.jar", "/system/bin", "app.aemu.setup.GoogleCtl",
                        if (settings.disableGoogleApps) "disable" else "restore"), 180_000,
                        mapOf("DHD_UID" to "1000", "DHD_GID" to "1000"))
                    log("Google-app option: exit=$code ${output.trim()}")
                }.onFailure { log("Google-app option failed: ${it.message}") }
            }, "google-app-option").start()
        }
        watchdog()
    }

    /**
     * The guest's installd (4.2+) rewrites /data/user/0 as the absolute link "/data/data/" on every start. qemu resolves an
     * absolute link target on the HOST (the phone's own /data/data), so every app path /data/user/0/<pkg> lands in a
     * directory the guest cannot enter: "Unable to create cache directory", contacts2.db / gservices.db EACCES, and the
     * acore, gapps and dialer crash loops. The relative link "../data" resolves inside the guest tree, so put it back
     * whenever installd (also after its restarts) replaces it.
     */
    @Volatile private var user0Watcher: Thread? = null
    private fun watchUserZeroLink() {
        if (img.api < 14 || user0Watcher?.isAlive == true) return
        user0Watcher = Thread({
            val link = File(paths.root, "data/user/0").absolutePath
            while (!stopping) {
                runCatching {
                    val cur = android.system.Os.readlink(link)
                    if (cur != "../data") {
                        android.system.Os.remove(link)
                        android.system.Os.symlink("../data", link)
                        log("data/user/0: link was \"$cur\", restored ../data")
                    }
                }
                try { Thread.sleep(200) } catch (_: InterruptedException) { return@Thread }
            }
        }, "user0-link").apply { isDaemon = true; start() }
    }

    @Volatile private var runnerRef: GuestRunner? = null
    val guestRunner: GuestRunner get() = runnerRef ?: runner

    private fun startBinder() {
        val sock = paths.binderSock.absolutePath
        when (engine) {
            Engine.KK -> spawn("binderd", listOf(paths.nativeBin(engine.binderd).absolutePath, "-s", sock), emptyMap())
            Engine.N64 -> {
                val env = LinkedHashMap<String, String>()
                if (File(paths.bin, "binder.verbose").exists()) env["AEMU_BINDER_VERBOSE"] = "1"
                // the version this firmware's libbinder was seen to insist on (see BinderProtocol); none yet: the daemon's own
                BinderProtocol.saved(paths.bin)?.let { env[BinderProtocol.ENV] = it.toString(); log("· binder protocol $it (learned from this firmware)") }
                val bin = paths.nativeBin(kernel.binderd).absolutePath
                spawn("binderd", listOf(bin, sock), env)
                if (kernel.treble) {
                    spawn("hwbinderd", listOf(bin, paths.hwbinderSock.absolutePath), env)
                    spawn("vndbinderd", listOf(bin, paths.vndbinderSock.absolutePath), env)
                    log("· Treble hwbinder and vndbinder daemons started")
                }
            }
            Engine.GB -> if (File(paths.bin, "binderd.kk").exists()) {
                // эксперимент: более новый binderd движка KK
                spawn("binderd", listOf(paths.nativeBin(Engine.KK.binderd).absolutePath, "-s", sock), emptyMap())
            } else {
                Slot.start(ctx, BinderSlot::class.java, listOf("binderd", "-s", sock), emptyMap(), paths.log("binderd"))
                log("· binderd started (:binder service)")
            }
        }
    }

    private fun glUp() {
        if (!settings.gpu) { log("GPU bridge off, software rendering"); return }
        val (w, h) = if (engine == Engine.GB) 480 to 800 else settings.width to settings.height
        paths.glSock.parentFile?.mkdirs()
        paths.glSock.delete()
        when (engine) {
            Engine.N64 -> {
                log("native arm64: graphics are served by the engine's own HALs")
                // ...except for 32-bit programs, which draw through the 4.4 engine's GPU bridge (its frame file is not shown)
                if (kernel.gpuBridge32 != null && n64Has32) {
                    spawn("glserverd", listOf(paths.nativeBin(Engine.KK.glserverd).absolutePath,
                        "-s", paths.glSock.absolutePath, "-fb", File(paths.bin, "gl32.fb").absolutePath, "-w", "$w", "-h", "$h"), glServerEnv())
                    waitFor("GL bridge socket", 10_000) { paths.glSock.exists() }
                }
            }
            Engine.GB -> {
                Slot.start(ctx, GlSlot::class.java,
                    listOf("glserverd", "-s", paths.glSock.absolutePath, "-fb", paths.fb.absolutePath, "-w", "$w", "-h", "$h"),
                    emptyMap(), paths.log("glserverd"))
                waitFor("GL bridge socket", 10_000) { paths.glSock.exists() }
            }
            Engine.KK -> {
                if (surface == null) waitFor("screen surface", 10_000) { surface != null }
                val sf = surface
                HostNative.relaxFdsanSafe()
                // 5.0+: hwui's GL stream has crashed the in-app bridge (SIGSEGV in serve_one_raw took the whole app down);
                // the standalone glserverd keeps such a crash inside its own process
                // Mali: the in-app bridge puts an app's ES2 context into the share group of SurfaceFlinger's ES1 context, and the
                // driver then hands the app an ES 1.1 context: every glCreateProgram/glUseProgram fails (glserverd log: "OpenGL ES-CM 1.1"
                // for system_server, driver refused 0x502). Sense 3's Rosie launcher and the lockscreen are ES2 and stay invisible
                // (only the ES1 wallpaper shows). The standalone *_mali server keeps ES2 contexts out of that group, so Mali uses it
                // for old images too.
                val mali = HostInfo.gpu().contains("mali")
                if (img.api < 21 && !mali && sf != null && GlBridge.start(sf, paths.glSock.absolutePath, paths.log("glbridge").absolutePath, w, h, false)) {
                    log("★ GPU bridge running in-app: frames render straight to the surface")
                    waitFor("GL bridge socket", 5_000) { paths.glSock.exists() }
                    return
                }
                log(when {
                    img.api >= 21 -> "GPU bridge: standalone glserverd"
                    mali -> "GPU bridge: standalone glserverd (Mali: the in-app bridge hands ES2 apps an ES 1.1 context)"
                    else -> "in-app GPU bridge failed, falling back to standalone glserverd"
                })
                spawn("glserverd", listOf(paths.nativeBin(engine.glserverdFor(HostInfo.gpu())).absolutePath,
                    "-s", paths.glSock.absolutePath, "-fb", paths.fb.absolutePath,
                    "-notify", paths.frameSock.absolutePath, "-w", "$w", "-h", "$h"), glServerEnv())
                waitFor("GL bridge socket", 10_000) { paths.glSock.exists() }
            }
        }
    }

    /** the 4.4 engine's GL server reads every GL call of a client with its own system calls; native/glbuf reads the
     *  client sockets in large chunks instead (7.1 GLES 2/3 scenes were bound by those reads) */
    private fun glServerEnv(): Map<String, String> {
        val buf = paths.nativeBin("libglbuf.so")
        return if (buf.isFile) mapOf("LD_PRELOAD" to buf.absolutePath) else emptyMap()
    }

    /** guest files this kernel ships (asset names) */
    private val engineFiles: Set<String> by lazy { listOf("engines/n64-${kernel.tag}", kernel.gsiAssets).flatMap { runCatching { EngineAssets.layerNames(ctx, it) }.getOrNull().orEmpty() }.toSet() }

    /**
     * libmedia's MediaProfiles CHECK()s that the file media.settings.xml names can be opened, and aborts when it cannot:
     * zygote dies in libmedia_jni's preload, mediaserver in its start (MediaProfiles.cpp: CHECK((fp = fopen(xml, "r")))).
     * A vendor image can name a file its vendor partition does not have (a Wear 9 build: /vendor/etc/media_profiles_vendor.xml).
     * The name then becomes a link to the firmware's own profiles; with none at all the property is emptied, and libmedia
     * builds its built-in default.
     */
    private fun ensureMediaProfiles() = runCatching {
        val want = props.get("media.settings.xml")?.trim().orEmpty()
        if (!want.startsWith("/")) return@runCatching
        val f = File(paths.root, want.removePrefix("/"))
        if (f.exists()) return@runCatching        // (a link to nothing is not "exists": below it is replaced)
        val have = listOf("vendor/etc/media_profiles_V1_0.xml", "vendor/etc/media_profiles.xml",
            "system/etc/media_profiles_V1_0.xml", "system/etc/media_profiles.xml").map { File(paths.root, it) }.firstOrNull { it.isFile }
        if (have != null) {
            f.parentFile?.mkdirs()
            runCatching { android.system.Os.remove(f.absolutePath) }
            android.system.Os.symlink(f.parentFile!!.toPath().relativize(have.toPath()).toString(), f.absolutePath)
            log("media: $want was missing, now a link to ${have.relativeTo(paths.root).path}")
        } else {
            props.put("media.settings.xml", "")
            log("media: $want was missing and the firmware has no media_profiles.xml: libmedia's defaults")
        }
    }

    private val n64Has32: Boolean get() = paths.nativeBin("libqemu_arm.so").canExecute() &&
            File(paths.root, "system/bin/app_process32").isFile

    val glInApp: Boolean get() = engine == Engine.KK && GlBridge.running()

    /** LPW39: writes system/framework/aemu-imgclasses.zip holding a core-library-only copy of framework.jar's preloaded-classes. */
    private fun writeLpw39ImageClasses(): Boolean = runCatching {
        val fw = File(paths.root, "system/framework/framework.jar")
        val out = File(paths.root, "system/framework/aemu-imgclasses.zip")
        val keep = listOf("java.", "javax.", "libcore.", "dalvik.", "sun.", "org.apache.harmony.", "com.android.org.",
            "org.json.", "org.w3c.", "org.xml.", "org.kxml2.", "org.xmlpull.", "org.apache.xml.", "junit.")
        val text = java.util.zip.ZipFile(fw).use { z ->
            val e = z.getEntry("preloaded-classes") ?: return@runCatching false
            z.getInputStream(e).bufferedReader().readText()
        }
        val lines = text.lineSequence().map { it.trim() }.filter { l -> l.isNotEmpty() && !l.startsWith("#") && keep.any { l.startsWith(it) } }.toList()
        if (lines.isEmpty()) return@runCatching false
        java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zo ->
            zo.putNextEntry(java.util.zip.ZipEntry("preloaded-classes"))
            zo.write((lines.joinToString("\n") + "\n").toByteArray())
            zo.closeEntry()
        }
        log("LPW39: boot image classes limited to the core libraries (${lines.size} of the preloaded classes)")
        true
    }.getOrDefault(false)

    /**
     * Android L preview build LPW39 (Google ADT-1 / molly). It reports API 20 but, unlike 4.4W (also API 20), is ART-only:
     * every L-preview workaround is keyed on this exact ro.build.id so Dalvik-based 4.4 / 4.4W images are untouched.
     */
    private val isLpw39: Boolean by lazy { img.buildId.trim().equals("LPW39", ignoreCase = true) }

    /** app_process, or app_process32 on firmware that has only that. */
    private val appProcess: String get() = AppProcess.path(paths.root)

    /** Helper services that are not in the firmware's init plan; the watchdog restarts them after a signal death. */
    private val stubServices = java.util.concurrent.ConcurrentHashMap<String, GuestService>()

    private fun startStub(svc: GuestService) {
        stubServices[svc.name] = svc
        startService(svc)
    }

    /**
     * The services to start, read from the firmware's init.rc at every boot: the list stored at import was made by the
     * rules of the version that imported it, and fixes to those rules must reach firmware imported earlier. (InitPlan
     * ignores MediaTek's advanced_meta_init.rc, so images imported before that was learned need no re-import.)
     */
    private fun servicePlan(): List<GuestService> =
        // pre-1.0: the runtime of the tree's own init.rc, never the generic planner's zygote (see LegacyFirmware.bootPlan)
        LegacyFirmware.bootPlan(paths.root)?.takeIf { it.isNotEmpty() } ?: genericServicePlan()

    private fun genericServicePlan(): List<GuestService> = (runCatching {
        val root = paths.root
        val props = HashMap<String, String>()
        for (n in listOf("default.prop", "system/build.prop")) runCatching {
            File(root, n).forEachLine { l -> val i = l.indexOf('='); if (i > 0 && !l.startsWith("#")) props[l.substring(0, i).trim()] = l.substring(i + 1).trim() }
        }
        val rcFiles = (root.listFiles()?.filter { it.isFile && it.name.endsWith(".rc") }?.sortedBy { if (it.name == "init.rc") 0 else 1 } ?: emptyList()) +
            InitPlan.hwRcFiles(root, props) +
            listOf("system/etc/init", "vendor/etc/init").flatMap { d -> File(root, d).listFiles()?.filter { it.name.endsWith(".rc") }?.sortedBy { it.name } ?: emptyList() } +
            // 11+: a module's services are declared inside the module (the software codec service)
            (File(root, "apex").listFiles()?.sortedBy { it.name }?.flatMap { m -> File(m, "etc").listFiles()?.filter { it.isFile && it.name.endsWith(".rc") }?.sortedBy { it.name } ?: emptyList() } ?: emptyList())
        InitPlan.plan(InitPlan.parse(rcFiles, props), img.api, root).takeIf { p -> p.any { it.name == "zygote" } }
    }.getOrNull() ?: img.services.ifEmpty { InitPlan.fallback(img, paths.root) }).map { InitPlan.normalHealthd(it) }


    private fun startService(svc0: GuestService, propsFile: File? = null) {
        // The second zygote is of the other word size than the first. A firmware that ships all three zygote rc files
        // and does not name one (GSI 10: no ro.zygote) can leave both 64-bit in the plan - then 32-bit apps have no
        // zygote and system_server dies at boot ("Unsupported zygote ABI: armeabi-v7a").
        fun is64(path: String?): Boolean = path != null && runCatching {
            File(paths.root, path.removePrefix("/")).inputStream().use { i -> ByteArray(5).also { i.read(it) }[4].toInt() == 2 }
        }.getOrDefault(false)
        val svc = if (svc0.name == "zygote_secondary" && is64(svc0.argv.firstOrNull()) &&
            is64(img.services.firstOrNull { it.name == "zygote" }?.argv?.firstOrNull()) &&
            File(paths.root, "system/bin/app_process32").isFile)
            svc0.copy(argv = listOf("/system/bin/app_process32") + svc0.argv.drop(1)) else svc0
        if (!File(paths.root, svc.argv.first().removePrefix("/")).isFile) {
            log("· ${svc.name}: ${svc.argv.first()} missing, skipping")
            return
        }
        // a TEE KeyMint (MediaTek/Trustonic/OP-TEE client) aborts at once without a secure world, and a keymint left declared
        // in the manifests makes keystore2 panic: the program is never started, keystore2 runs its software KeyMint
        if (engine == Engine.N64 && kernel.treble && Regex("^android\\.hardware\\.security\\.keymint@[0-9.]+-service(\\..*)?$")
                .matches(svc.argv.first().substringAfterLast('/'))) {
            log("· ${svc.name}: TEE KeyMint, skipped (keystore2 uses its software KeyMint)")
            return
        }
        if (engine == Engine.N64 && ((svc.name == "zygote_secondary" && !n64Has32) || svc.argv.first().substringAfterLast('/') in N64_SKIP)) return
        if (engine == Engine.N64 && !paths.nativeBin("libqemu_arm.so").canExecute() && !runsNatively(File(paths.root, svc.argv.first().removePrefix("/")))) {
            log("· ${svc.name}: 32-bit program, skipped by the native arm64 engine")
            return
        }
        val extra = LinkedHashMap<String, String>()
        for ((sock, mode) in svc.sockets) extra["DHD_SOCK_$sock"] = "${paths.socket(sock).absolutePath},${if (engine == Engine.N64) mode else mode.substringBefore(',')}"
        if (svc.uid != 0) { extra["DHD_UID"] = svc.uid.toString(); extra["DHD_GID"] = svc.gid.toString() }
        var props = propsFile
        // звук: с ro.kernel.qemu=1 mediaserver берёт эмуляторный вывод в /dev/eac
        if (svc.name == "mediaserver") props = this.props.privateCopy("media", mapOf("ro.kernel.qemu" to "1"))
        // (-Xint:fast is an option of app_process' VM; the pre-1.0 runtime takes no options, with or without the legacy layout:
        // "Unrecognized option '-Xint:fast'", JNI_CreateJavaVM failed, zygote exits with code 0)
        val argv = if (svc.name == "zygote" && !legacyFw && img.api >= 2 && !settings.jit && img.runtime == "dalvik")
            svc.argv.take(1) + "-Xint:fast" + svc.argv.drop(1) else svc.argv
        if (svc.name == "zygote") log("zygote argv: " + argv.joinToString(" "))
        // отладка: файл run/strace.<служба> включает трассировку системных вызовов qemu
        // tracing slows a service down and fills its log: only for the services named in the VM settings
        // ("mediaserver,zygote"), or by the debugging file run/strace.<service>
        if (svc.name in VmSettings.straceNames(settings.straceServices) || File(paths.bin, "strace.${svc.name}").exists())
            extra["QEMU_STRACE"] = "1"
        if (engine == Engine.N64 && props != null) extra["AEMU64_PROPS_FILE"] = props.absolutePath
        // (8.0, for as long as its keymaster HAL does not register: the linker's own account of every library the
        // service loads, errors included, goes to the guest log in the diagnostics)
        if (engine == Engine.N64 && kernel.tag == "26" && svc.name == "keymaster-3-0") extra["LD_DEBUG"] = "2"
        // the engine's own 64-bit HAL service over a vendor's firmware: its libraries are the engine's copy of its VNDK
        if (engine == Engine.N64) kernel.ownVndkDir?.let { dir ->
            val name = svc.argv.first().removePrefix("/").replace('/', '#')
            if (name.startsWith("vendor#bin#") && name !in kernel.firmwareOwn && File(paths.root, dir).isDirectory &&
                name in engineFiles && runsNatively(File(paths.root, svc.argv.first().removePrefix("/")))) extra["AEMU64_LIBPATH"] = "/$dir"
        }
        if (engine == Engine.N64) kernel.ownLibDirs[svc.argv.first().removePrefix("/").replace('/', '#')]?.let { dir ->
            // (several directories: "a:b", searched in that order)
            val dirs = dir.split(':').filter { File(paths.root, it).isDirectory }
            if (dirs.isNotEmpty()) extra["AEMU64_LIBPATH"] = dirs.joinToString(":") { "/$it" }
        }
        spawn(svc.name, guestRunner.cmdline(argv, props), guestRunner.env(extra, svc.argv.first()))
    }

    /** a 64-bit ELF or a script */
    private fun runsNatively(f: File): Boolean = runCatching {
        f.inputStream().use { i ->
            val b = ByteArray(5)
            i.read(b) == 5 && ((b[0] == 0x7f.toByte() && b[4] == 2.toByte()) || b[0] == 0x23.toByte())
        }
    }.getOrDefault(false)

    private fun spawn(name: String, cmd: List<String>, env: Map<String, String>) {
        val log = paths.log(name)
        // A real init starts services with cwd "/", and some firmware daemons open relative paths (MediaTek netd does
        // fopen("proc/cpuinfo")). qemu only remaps absolute guest paths, so netd must start inside the guest root,
        // otherwise the open fails, netd calls feof(NULL) and dies, and system_server then fails to start.
        val cwd = if (name == "netd") paths.root else paths.bin
        val pb = ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(if (env.isEmpty()) mapOf("PATH" to "/system/bin") else env)
        // 7.0+ zygote refuses to fork with pipes among its descriptors (it only reopens files and devices):
        // its stdio go straight to the log file and /dev/null, which the guest shim shows as /dev/null
        val direct = name == "zygote" && img.api >= 24
        if (direct) {
            runCatching { log.appendText("\n=== $name ${stamp()} ===\n") }
            pb.redirectInput(File("/dev/null")).redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        }
        val p = pb.start()
        synchronized(procs) { procs[name] = p }
        pidOf(p)?.let { pgids.add(it) }
        log("· $name started")
        // init publishes every service's state; the framework waits on it (SystemService.waitForState: the WebView
        // zygote, the boot animation)
        props.put("init.svc.$name", "running")
        Thread({
            if (!direct) runCatching {
                OutputStreamWriter(FileOutputStream(log, true)).use { w ->
                    w.write("\n=== $name ${stamp()} ===\n")
                    p.inputStream.bufferedReader().forEachLine { w.write(it); w.write("\n"); w.flush() }
                }
            }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (!stopping) log("· $name exited, code $code")
            if (!stopping && code != 0) learnBinderProtocol(name, log)
            if (!stopping && code != 0 && !QemuFallback.enabled(paths) &&
                runCatching { QemuFallback.isLinkFailure(QemuFallback.lastRun(log.readText(), name)) }.getOrDefault(false))
                fallbackToPatchedQemu(name)
            // a restarted service is already running again under this name
            synchronized(procs) { if (procs[name].let { it == null || it === p }) props.put("init.svc.$name", "stopped") }
        }, "log-$name").apply { isDaemon = true; start() }
    }

    private val binderProtocolRetried = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * A program that died on "binder driver version (8) differs from user space version (7)" tells which protocol the
     * firmware wants. Remember it and start the system again once, with the binder daemons answering that version.
     */
    private fun learnBinderProtocol(name: String, log: File) {
        if (engine != Engine.N64) {
            // the universal engine's binder daemon speaks protocol 7; a 32-bit firmware built for the 64-bit binder structs
            // (protocol 8) cannot start on it. The native engine with qemu-arm can: remember it and start again on that.
            val (driver, wanted) = runCatching { BinderProtocol.parseMismatch(QemuFallback.lastRun(log.readText(), name)) }.getOrNull() ?: return
            if (wanted != 8 || !img.abi.startsWith("armeabi") || !img.settings.translate32 || !paths.nativeBin("libqemu_arm.so").canExecute()) return
            if (!binderProtocolRetried.compareAndSet(false, true)) return
            BinderProtocol.save(paths.bin, wanted)
            log("✖ $name: binder driver speaks protocol $driver, the firmware wants $wanted; remembered, starting the system again on the native engine (qemu-arm)")
            onPower?.invoke(true, "")
            return
        }
        val (driver, wanted) = runCatching { BinderProtocol.parseMismatch(QemuFallback.lastRun(log.readText(), name)) }.getOrNull() ?: return
        if (BinderProtocol.saved(paths.bin) == wanted) {
            log("✖ $name still wants binder protocol $wanted although ${BinderProtocol.ENV}=$wanted was set: the guest runner of this Android version (libaemu64_${kernel.tag}) does not honour ${BinderProtocol.GUEST_ENV} (only the 8.x kernels 26/27 translate protocol 7)")
            return
        }
        if (!binderProtocolRetried.compareAndSet(false, true)) return
        BinderProtocol.save(paths.bin, wanted)
        log("✖ $name: binder driver speaks protocol $driver, the firmware wants $wanted; remembered, starting the system again with protocol $wanted")
        onPower?.invoke(true, "")
    }

    private val qemuFallbackStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val swGlFallbackStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The last zygote run ended in a SIGSEGV with libGLES_android.so (the software GL) on the stack. */
    private fun softwareGlCrashed(): Boolean = runCatching {
        val last = paths.log("zygote").readText().substringAfterLast("=== zygote")
        (last.contains("SIGSEGV") || last.contains("Segmentation fault")) && last.contains("libGLES_android.so")
    }.getOrDefault(false)

    /** A 2.x guest linker could not map its first library (stock qemu holds 0x40000000): reboot with the patched translator. */
    private fun fallbackToPatchedQemu(service: String) {
        if (!qemuFallbackStarted.compareAndSet(false, true)) return
        if (!paths.nativeBin(engine.qemuXperiaPlay).canExecute()) { log("$service: ${engine.qemuXperiaPlay} is not available"); return }
        if (!QemuFallback.enable(paths)) { log("$service: cannot remember the translator choice"); return }
        log("$service: guest linker reported \"${QemuFallback.ERROR}\", restarting with ${engine.qemuXperiaPlay}")
        onPower?.invoke(true, "")
    }

    private fun alive(): List<String> = synchronized(procs) { procs.filterValues { it.isAlive }.keys.toList() }

    /**
     * Pixel format the boot logo uses in fb0 (0 = RGB565, 1 = RGBA_8888, 2 = BGRA_8888). The emulated fb0 is two 32-bit
     * pages and Samsung's fb is BGRA, so 2 is the default; run/logo-format (a single digit) overrides it for testing.
     */
    val logoFormat: Int get() = runCatching { File(paths.bin, "logo-format").readText().trim().toInt() }.getOrNull()?.takeIf { it in 0..2 } ?: 2

    /** True while Samsung's playlogos1 paints the boot animation into fb0 (the UI then shows fb0 instead of the GL surface). */
    fun legacyLogoRunning(): Boolean = bootDoneAt == 0L && synchronized(procs) { procs[BootMediaServices.LEGACY_LOGO]?.isAlive == true }

    /** The healthd/batteryproperties guard is limited to Samsung firmware on Android 5.0+ (API 21); other devices are left alone. */
    private val isSamsungImage: Boolean by lazy {
        img.brand.equals("samsung", ignoreCase = true) || img.skin.contains("touchwiz", ignoreCase = true)
    }
    private val needsBatteryPropertiesGuard: Boolean by lazy { img.api >= 21 && isSamsungImage }

    /**
     * 4.4+: BatteryService.onStart looks up "batteryproperties" (published by healthd) and dies with a
     * NullPointerException when it is missing; that kills system_server and zygote exits with 137. Check the
     * registration before zygote starts and give healthd another start if it is absent, so the boot does not
     * fail on a healthd that came up without registering. Never blocks the boot when the guest cannot be queried.
     */
    private fun ensureBatteryProperties(plan: List<GuestService>) {
        // only the healthd that the device's own plan started; nothing is started or invented here
        val hd = plan.firstOrNull { it.name == "healthd" }?.let { InitPlan.normalHealthd(it) } ?: return
        val running = synchronized(procs) { procs["healthd"] }?.takeIf { it.isAlive } ?: return
        log("healthd: command ${hd.argv.joinToString(" ")}, pid ${pidOf(running) ?: "?"}")
        for (attempt in 1..3) {
            if (stopping) return
            val until = SystemClock.uptimeMillis() + 6_000
            var verdict: Boolean? = null
            var lastCheck = ""
            do {
                val out = runCatching {
                    guestRunner.run(listOf("/system/bin/service", "check", "batteryproperties"), 10_000).second
                }.getOrDefault("")
                lastCheck = out.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("WARNING: linker") } ?: "(no output)"
                verdict = when {
                    out.contains("not found", true) -> false
                    out.contains("found", true) -> true
                    else -> null
                }
                if (verdict != false) break
                Thread.sleep(500)
            } while (SystemClock.uptimeMillis() < until && !stopping)
            log("healthd: service check batteryproperties -> \"${lastCheck.take(160)}\" ($attempt/3)")
            if (verdict == true) {
                if (attempt > 1) log("healthd: batteryproperties registered after restart ($attempt/3)")
                File(paths.bin, "strace.healthd").delete()
                return
            }
            if (verdict == null) { log("healthd: batteryproperties cannot be queried (no guest 'service' tool), continuing"); return }
            val alive = synchronized(procs) { procs["healthd"]?.isAlive == true }
            log("⚠ healthd (running=$alive) has not registered batteryproperties ($attempt/3)")
            if (attempt == 3) break
            // the next start is traced into the healthd log (qemu strace), to see where it stops
            runCatching { File(paths.bin, "strace.healthd").createNewFile() }
            synchronized(procs) { procs.remove("healthd") }?.let { runCatching { it.destroyForcibly() } }
            Thread.sleep(300)
            runCatching { startService(hd) }
        }
        File(paths.bin, "strace.healthd").delete()
        log("✖ batteryproperties is still missing, system_server will fail in BatteryService; healthd command: ${hd.argv.joinToString(" ")}")
    }

    private fun waitFor(what: String, ms: Long, cond: () -> Boolean): Boolean {
        val until = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < until && !stopping) {
            if (cond()) return true
            Thread.sleep(100)
        }
        val ok = cond()
        if (!ok) log("⚠ timed out waiting for: $what")
        return ok
    }

    // ------------------------------------------------------------------ присмотр

    /** Guest asked to reboot / power off ("reboot", "reboot,recovery", "shutdown"); the UI restarts or stops the VM. */
    @Volatile var onPower: ((reboot: Boolean, reason: String) -> Unit)? = null
    @Volatile private var powerHandled = false

    private fun powerRequest(v: String) {
        if (powerHandled || v.isEmpty()) return
        powerHandled = true
        val reboot = v.startsWith("reboot")
        val reason = v.substringAfter(',', "")
        log("guest requested ${if (reboot) "reboot" else "power off"}${if (reason.isNotEmpty()) " ($reason)" else ""}")
        if (recoveryEarlyExit()) {
            // nobody could have chosen that from a menu this fast: the recovery gave up on its own
            recoveryEnded("recovery asked to ${if (reboot) "reboot" else "power off"} ${(System.currentTimeMillis() - bootAt) / 1000.0} s after it started",
                "it asked to ${if (reboot) "reboot" else "power off"} right away")
            return
        }
        if (reason == "recovery" && !RecoveryImage.installed(paths)) log("no recovery installed, booting the system again")
        onPower?.invoke(reboot, reason)
    }

    /**
     * 12+: the generator of the linker's namespaces takes the active modules from /apex/apex-info-list.xml, which
     * apexd writes when it mounts them; here they are plain directories, so the list is written from their manifests.
     */
    private fun apexInfoList() {
        val apex = File(paths.root, "apex")
        val mods = apex.listFiles()?.filter { it.isDirectory && '@' !in it.name && File(it, "apex_manifest.pb").isFile }?.sortedBy { it.name } ?: return
        if (mods.isEmpty()) return
        val pre = File(paths.root, "system/apex").list() ?: emptyArray()
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<apex-info-list>\n")
        for (m in mods) {
            // apex_manifest.pb: field 1 the name, field 2 the version (a varint)
            val b = File(m, "apex_manifest.pb").readBytes()
            var i = 0; var version = 1L
            fun varint(): Long { var v = 0L; var sh = 0; while (i < b.size) { val c = b[i++].toInt(); v = v or ((c and 0x7f).toLong() shl sh); sh += 7; if (c and 0x80 == 0) break }; return v }
            while (i < b.size) {
                val tag = varint().toInt()
                when (tag and 7) { 0 -> { val v = varint(); if (tag shr 3 == 2) version = v }; 2 -> i += varint().toInt(); 1 -> i += 8; 5 -> i += 4; else -> i = b.size }
            }
            val file = pre.firstOrNull { it == m.name || it.substringBeforeLast('.') == m.name || it.startsWith(m.name + ".") } ?: m.name
            sb.append("  <apex-info moduleName=\"${m.name}\" modulePath=\"/system/apex/$file\" preinstalledModulePath=\"/system/apex/$file\" versionCode=\"$version\" versionName=\"\" isFactory=\"true\" isActive=\"true\" />\n")
        }
        sb.append("</apex-info-list>\n")
        File(apex, "apex-info-list.xml").writeText(sb.toString())
    }

    private fun clientComposition() {
        runCatching { guestShell("service call SurfaceFlinger 1008 i32 1", 15_000) }
    }

    private fun onProp(k: String, v: String) {
        if (k == "sys.powerctl") { powerRequest(v); return }
        if (k == "service.bootanim.exit") {
            for ((start, name) in BootMediaServices.triggers(paths.root, k, v)) onCtl(start, name)
            if (v == "1") onCtl(false, "bootanim")
        }
        // SurfaceFlinger has just come up (it resets this before starting the boot animation)
        if (k == "service.bootanim.exit" && v == "0" && engine == Engine.N64 && kernel.clientComposition)
            Thread { clientComposition() }.start()
        if (k == "service.bootanim.exit" && v == "1" && screenUpAt == 0L) screenUpAt = System.currentTimeMillis()
        if ((k == "sys.boot_completed" || k == "dev.bootcomplete") && v == "1" && bootDoneAt == 0L)
            completeBoot("guest reported boot completion")
    }

    /** Boot is over: state RUNNING (touch, adb, the library status and the rest wait for it), afterBoot() once. */
    @Synchronized private fun completeBoot(why: String) {
        if (bootDoneAt != 0L) return
        if (!recoveryMode && !lowPowerBoot) noteBoot.reported(SystemClock.elapsedRealtime())
        bootDoneAt = System.currentTimeMillis()
        everBooted = true
        log("★ $why in ${(bootDoneAt - bootAt) / 1000} s (UI not verified)")
        setState(State.RUNNING)
        Thread { afterBoot() }.start()
    }

    @Volatile private var legacyBootObservers: List<android.os.FileObserver> = emptyList()

    private fun stopLegacyBootWatch() {
        legacyBootObservers.forEach { runCatching { it.stopWatching() } }
        legacyBootObservers = emptyList()
    }

    /**
     * Pre-1.0 builds (HTC 20645 and the like) never set sys.boot_completed or dev.bootcomplete: the property did not exist
     * yet, so the app stayed in BOOTING for ever and touch, adb and everything else that waits for RUNNING never came up.
     *
     * What the runtime does do is deliver ACTION_BOOT_COMPLETED: the receivers of the installed apps are instantiated and
     * the media scanner answers by scanning the internal volume. The 20645 trace shows media.db being opened and then
     * open("/system/media"), open(".../audio"), open(".../audio/ringtones"), once, and nothing else touches that directory
     * at boot. An inotify watch on it (FileObserver) reports the first open / read, and that is the boot-completed event of
     * these builds. The watch starts once the system is started, after every fix-up the app itself does to the tree.
     * No event, no completion: nothing here fires on a clock.
     */
    private fun watchLegacyBoot() {
        if (recoveryMode || lowPowerBoot || !LegacyFirmware.isPreRelease(img.release)) return
        stopLegacyBootWatch()
        val dir = File(paths.root, LegacyFirmware.BOOT_SCAN_DIR)
        if (!dir.isDirectory) { log("pre-1.0 boot watch: ${LegacyFirmware.BOOT_SCAN_DIR} is not in this tree, no boot-completed signal to wait for"); return }
        @Suppress("DEPRECATION")
        val o = object : android.os.FileObserver(dir.absolutePath, android.os.FileObserver.OPEN or android.os.FileObserver.ACCESS) {
            override fun onEvent(event: Int, path: String?) {
                if (bootDoneAt != 0L || stopping) return
                if (alive().none { it != "binderd" }) return    // the runtime is gone: that is a failure, not a boot
                completeBoot("pre-1.0 build: the media scanner opened /${LegacyFirmware.BOOT_SCAN_DIR} (BOOT_COMPLETED was delivered)")
                stopLegacyBootWatch()
            }
        }
        o.startWatching()
        legacyBootObservers = listOf(o)
        log("pre-1.0 boot watch: waiting for the guest to open /${LegacyFirmware.BOOT_SCAN_DIR} (media scanner, after BOOT_COMPLETED)")
    }

    @Volatile private var touchWizMediaStarted = false

    /**
     * TouchWiz only: if /system/bin has samsungani (boot animation) and/or playsound (boot sound), run them during
     * boot. Missing binaries are skipped without an error, and nothing here can fail the boot.
     */
    private fun startTouchWizBootMedia() {
        if (touchWizMediaStarted || stopping || recoveryMode || lowPowerBoot) return
        touchWizMediaStarted = true
        runCatching {
            for (name in BootMediaServices.touchWizBootMedia(paths.root, img.skin)) {
                // below 4.1 the animation never quits on its own (see onCtl), so don't force it there
                if (name == "samsungani" && img.api < 16) { log("TouchWiz boot media: samsungani skipped on API ${img.api}"); continue }
                val key = if (name == "playsound") name else "bootanim"
                if (synchronized(procs) { procs[key]?.isAlive == true }) continue
                val def = BootMediaServices.resolve(paths.root, name) ?: continue
                Thread { runCatching { startService(def) } }.start()
                log("TouchWiz boot media: $name started")
            }
        }.onFailure { log("TouchWiz boot media: ${it.message}") }
    }

    /**
     * The modules ApexStub reports (<root>/apex/.aemu-apexes): name, package file, preinstalled path, version. The
     * package manager parses the package file itself (its AndroidManifest.xml), so it is the one the importer set
     * aside when it unpacked the module; the preinstalled path tells it the partition.
     */
    private fun apexStandInList() = runCatching {
        val apex = File(paths.root, "apex")
        val out = File(apex, ".aemu-apexes")
        val parked = File(paths.root, "system/.aemu-parked").listFiles()?.filter { it.name.endsWith(".apex") || it.name.endsWith(".capex") }?.sortedBy { it.name } ?: emptyList()
        if (out.isFile && out.lastModified() >= (parked.maxOfOrNull { it.lastModified() } ?: 0L) && out.length() > 0) return@runCatching
        fun fields(b: ByteArray): Pair<String?, Long> {
            var i = 0; var name: String? = null; var version = 1L
            fun varint(): Long { var v = 0L; var sh = 0; while (i < b.size) { val c = b[i++].toInt(); v = v or ((c and 0x7f).toLong() shl sh); sh += 7; if (c and 0x80 == 0) break }; return v }
            while (i < b.size) {
                val tag = varint().toInt()
                when (tag and 7) {
                    0 -> { val v = varint(); if (tag shr 3 == 2) version = v }
                    2 -> { val n = varint().toInt(); if (tag shr 3 == 1 && i + n <= b.size) name = String(b, i, n); i += n }
                    1 -> i += 8; 5 -> i += 4; else -> i = b.size
                }
            }
            return name to version
        }
        val sb = StringBuilder(); val seen = HashSet<String>()
        for (f in parked) runCatching {
            val (name, version) = java.util.zip.ZipFile(f).use { z -> fields(z.getInputStream(z.getEntry("apex_manifest.pb")).use { it.readBytes() }) }
            if (name == null || !seen.add(name) || !File(apex, "$name/apex_manifest.pb").isFile) return@runCatching
            // "system#apex#x.apex" is where the package was: /system/apex/x.apex
            val was = "/" + f.name.replace('#', '/')
            sb.append("$name\t/system/.aemu-parked/${f.name}\t$was\t$version\n")
        }
        out.writeText(sb.toString())
        log("apex stand-in: ${seen.size} modules")
    }.onFailure { log("apex stand-in: ${it.message}") }

    private fun onCtl(start: Boolean, svc: String) {
        if (svc.startsWith("aidl/")) {
            // a lazy AIDL service (artd of 14+, a skin's daemon): started when servicemanager is asked for it, and
            // a few times at most, as the question comes every second for as long as the client waits
            if (!kernel.lazyAidlServices) return
            if (kernel.apexStandIn && svc == "aidl/apexservice") return
            val def = InitPlan.lazyAidl(svc.removePrefix("aidl/"), paths.root) ?: return
            synchronized(procs) {
                if (procs[def.name]?.isAlive == true) return
                val n = lazyStarts[def.name] ?: 0
                if (n >= 3) return
                lazyStarts[def.name] = n + 1
            }
            log("lazy service ${def.name} asked for ($svc)")
            Thread { runCatching { startService(def) } }.start()
            return
        }
        val plan = servicePlan().filter { it.name !in InitPlan.NEVER_START }.ifEmpty { InitPlan.fallback(img, paths.root) }
        if (svc == "bootanim" || svc == "bootanimation" || svc == "samsungani") {
            if (!start) {
                // 4.x sets service.bootanim.exit first and the animation quits on its own, closing its audio.
                // Killing it outright left Samsung's boot sound track half-open: mediaserver hung and every
                // app creating a sound (the phone process, ToneGenerator) got an ANR.
                // Never kill it: a bootanim that is signalled (SIGTERM 143 / SIGKILL 137) dies without releasing its
                // SurfaceFlinger layer, which then stays on top of the UI. Leave it in `procs` and just wait: it
                // leaves by itself (exit 0) once its loop sees the property and calls stopProcess().
                val p = synchronized(procs) { procs["bootanim"] } ?: return
                Thread {
                    var waited = 0
                    while (!stopping && p.isAlive) {
                        if (p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) break
                        waited++
                        // Watchdog: the guest reported boot completion and set the exit property, yet the animation keeps drawing.
                        // Record what the guest can actually read, re-assert the property once, and only then give up waiting:
                        // a layer that is already stuck on top of a finished UI cannot be made worse by killing its owner.
                        if (waited == 10) {
                            val seen = props.get("service.bootanim.exit")
                            log("boot animation watchdog: still alive 10 s after exit=1; area value=\"$seen\", re-asserting")
                            props.put("service.bootanim.exit", "1")
                        }
                        // Android Things: the boot animation drives the board's LED array through peripheralman and keeps its
                        // claim on I2C1 until it exits. The animation finishes its work and starts to tear down, but the process never dies (no binder death, so peripheralman keeps the claim), and the IoT launcher's default app
                        // (OemService) dies with "I2C1 is already in use" and is restarted for ever. The animation is not
                        // touched: the claim lives in peripheralman, so it is the daemon that is started afresh.
                        if (waited >= 20 && bootDoneAt != 0L && !recoveryMode) releaseStuckPeripheralClaim()
                        if (waited == 30) log("boot animation is still running 30 s after service.bootanim.exit=1, waiting")
                        // a stuck animation on a Things image is never killed (its layer would stay on top, see above)
                        if (waited == 60 && bootDoneAt != 0L && !recoveryMode && !hasPeripheralman()) {
                            log("boot animation did not exit 60 s after boot completion (area value=\"${props.get("service.bootanim.exit")}\"), stopping it")
                            p.destroy()
                            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
                        }
                    }
                    // the watchdog reaps the exited process and checks the layer if the exit code was non-zero
                    // (only 2.3 / MIUI ICS animations never poll the property; see the api < 16 note below)
                }.apply { isDaemon = true; start() }
                return
            }
            // below 4.1 (2.3, MIUI ICS) the animation does not quit by itself: killed, it leaves the GL bridge on its last frame
            if (img.api < 16 || bootDoneAt != 0L || synchronized(procs) { procs.containsKey("bootanim") }) return
            InitPlan.optional(svc, img, paths.root)?.let { def ->
                Thread { runCatching { startService(def) } }.start()
                log("boot animation started")
            }
            return
        }
        val def = plan.firstOrNull { it.name == svc } ?: InitPlan.optional(svc, img, paths.root)
        if (svc == "playsound" && start && (bootDoneAt != 0L || synchronized(procs) { procs["playsound"]?.isAlive == true })) return
        if (def == null) { log("ctl.${if (start) "start" else "stop"} $svc: no such service"); return }
        synchronized(procs) { procs.remove(svc) }?.destroyForcibly()
        if (start) Thread { runCatching { startService(def) } }.start()
    }

    private fun hasPeripheralman() = servicePlan().any { it.name == "peripheralman" }

    /** user + system CPU ticks of a host process, or -1 */
    private fun cpuTicks(pid: Int): Long = runCatching {
        val f = File("/proc/$pid/stat").readText().substringAfterLast(')').trim().split(' ')
        f[11].toLong() + f[12].toLong()   // utime, stime (fields 14 and 15 of the line)
    }.getOrDefault(-1L)

    private var peripheralClaimReleased = false

    /**
     * The boot animation is still alive 20 s after it was told to leave, on an image with Android Things' peripheralman.
     * Records peripheralman's CPU use, then
     * starts peripheralman afresh: its table of who holds which pin/bus goes with it, so OemService can open I2C1 again,
     * Once per boot. The boot
     * animation itself is never signalled.
     */
    private fun releaseStuckPeripheralClaim() {
        if (peripheralClaimReleased) return
        val old = synchronized(procs) { procs["peripheralman"] } ?: return
        if (!old.isAlive) return
        val def = servicePlan().firstOrNull { it.name == "peripheralman" } ?: return
        peripheralClaimReleased = true
        val pid = pidOf(old)
        val before = pid?.let { cpuTicks(it) } ?: -1L
        try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
        val after = pid?.let { cpuTicks(it) } ?: -1L
        val bus = File(paths.root, "dev/i2c-1")
        log("I2C1 is held by the boot animation which is still running; peripheralman (pid $pid) used " +
            "${if (before >= 0 && after >= 0) "${after - before} ticks of CPU in 1 s" else "an unknown amount of CPU"}; " +
            "bus file ${bus.length()} bytes. Starting peripheralman afresh (the boot animation is left alone)")
        synchronized(procs) { procs.remove("peripheralman", old) }
        old.destroyForcibly()
        props.put("init.svc.peripheralman", "stopped")
        runCatching { startService(def) }.onFailure { log("peripheralman: restart failed: ${it.message}") }
    }

    /**
     * Read-only diagnostics: during the first two minutes, note in the log when the guest first puts a non-black pixel
     * into fb0 (e.g. the boot logo or the lock screen). A framebuffer that is zero at the end of the boot can mean "never
     * written" or "a black frame was written"; this tells the two apart. Never writes to the file.
     */
    private fun watchFb0() {
        if (recoveryMode) return
        val f = paths.fb
        Thread({
            val start = System.currentTimeMillis()
            val buf = ByteArray(64 * 1024)
            var seen = false
            while (!stopping && System.currentTimeMillis() - start < 120_000 && !seen) {
                runCatching {
                    java.io.RandomAccessFile(f, "r").use { r ->
                        val total = minOf(r.length(), 4L shl 20)
                        var pos = 0L; var nz = 0L; var firstAt = -1L
                        while (pos < total) {
                            val n = r.read(buf); if (n <= 0) break
                            for (i in 0 until n) if (buf[i].toInt() != 0) { nz++; if (firstAt < 0) firstAt = pos + i }
                            pos += n
                        }
                        if (nz > 0) {
                            seen = true
                            log("fb0: first non-black pixels ${(System.currentTimeMillis() - start) / 1000.0} s after boot start ($nz non-zero bytes, first at offset $firstAt)")
                        }
                    }
                }
                if (!seen) try { Thread.sleep(300) } catch (_: InterruptedException) { return@Thread }
            }
            if (!seen && !stopping) log("fb0: no non-black pixel in the first 120 s (guest wrote nothing, or only black frames)")
        }, "fb0-watch").apply { isDaemon = true; start() }
    }

    private fun afterBoot() {
        val r = guestRunner
        if (LegacyFirmware.isPreRelease(img.release)) {   // no sh, no svc, no settings database, no wizard on a pre-1.0 build: only the statistics
            runCatching {
                ImageStore.update(ctx, img.id) { it.copy(lastBootMs = bootDoneAt - bootAt, bootCount = it.bootCount + 1) }
            }.onFailure { log("boot statistics: could not save: ${it.message}") }
            return
        }
        if (skipWizardNow()) return   // the system comes up again and gets here once more
        // (again: something in the first seconds after the boot completes turns it back off, and it is not known what;
        // a call made half a minute later stays)
        if (engine == Engine.N64 && kernel.clientComposition) Thread {
            for (wait in longArrayOf(0, 10_000, 20_000)) {
                Thread.sleep(wait)
                if (state != State.RUNNING) return@Thread
                clientComposition()
            }
        }.start()
        r.run(listOf("/system/bin/sh", "-c", "export PATH=/system/bin:/system/xbin:\$PATH; svc power stayon true"), 60_000)
        if (engine == Engine.N64 && kernel.webViewSingleProcess)
            runCatching { guestShell("cmd webviewupdate disable-multiprocess", 30_000) }
        TreeFixer(ctx, paths, img, ::log).noScreenSleep()
        disableBrokenComponents()
        if (img.api >= 24) runCatching { enableHome() }.onFailure { log("home screen check failed: $it") }
        runCatching {
            ImageStore.update(ctx, img.id) { it.copy(lastBootMs = bootDoneAt - bootAt, bootCount = it.bootCount + 1) }
        }.onFailure { log("boot statistics: could not save: ${it.message}") }
    }

    /**
     * The first boot ends in the vendor's setup wizard: the package list it has to be switched off in exists only
     * now, and the running system keeps its own copy of the settings. Several wizards cannot be passed here at all
     * (Sony's 4.0 one shows an empty screen, Samsung's waits for the network time), so the system is restarted once,
     * with the wizard disabled and the device marked as set up while it is down.
     */
    private fun skipWizardNow(): Boolean {
        // only when the person asked for it (Settings > Skip setup wizard): the restart used to happen on every first boot
        if (!img.settings.skipSetupWizard || wizardSkipped || zygoteRestarts > 0 || img.bootCount > 0) return false
        // MIUI ships its own wizard under the AOSP provisioner's name, and it does not finish by itself
        val wizards = TreeFixer.FIRST_RUN_WIZARDS + (if (File(paths.root, "system/app/miui/miui.apk").isFile) listOf("com.android.provision") else emptyList())
        val pkgFile = File(paths.root, "data/system/packages.xml")
        // 12+ keeps its package lists in binary XML (ABX) - and a text one written by request (persist.sys.binary_xml)
        // is not read back by 14 ("illegal attribute name: :strings"). There the running system is asked instead.
        if (runCatching { pkgFile.inputStream().use { i -> ByteArray(3).also { i.read(it) } }.contentEquals("ABX".toByteArray()) }.getOrDefault(false)) {
            val listed = runCatching { guestShell("pm list packages", 60_000) }.getOrDefault("")
            val wizard = wizards.firstOrNull { listed.lines().any { l -> l.trim() == "package:$it" } } ?: return false
            wizardSkipped = true
            plannedRestart = true
            log("first boot: setup wizard ($wizard) switched off in the running system, restarting it")
            runCatching { guestShell("settings put global device_provisioned 1; settings put secure user_setup_complete 1; pm disable-user --user 0 $wizard", 60_000) }
            synchronized(procs) { procs["zygote"] }?.let { z -> pidOf(z)?.let { runCatching { AProcess.sendSignal(it, 9) } } }
            return true
        }
        val pkgs = runCatching { pkgFile.readText() }.getOrNull() ?: return false
        val restr = runCatching { File(paths.root, "data/system/users/0/package-restrictions.xml").readText() }.getOrDefault("")
        val wizard = wizards.firstOrNull {
            pkgs.contains("<package name=\"$it\"") && !Regex("<pkg name=\"${Regex.escape(it)}\"[^>]*enabled=\"2\"").containsMatchIn(restr)
        } ?: return false
        wizardSkipped = true
        plannedRestart = true
        log("first boot: restarting the system past the setup wizard ($wizard)")
        synchronized(procs) { procs["zygote"] }?.let { z -> pidOf(z)?.let { runCatching { AProcess.sendSignal(it, 9) } } }
        return true
    }

    /**
     * The home screen of a firmware can ship switched off and be switched on by its setup wizard or by an app that
     * runs during it (the Google app's launcher activity on Nexus 8.0, turned on for the GoogleHome stub). With the
     * wizard skipped the only home left is Settings' FallbackHome, a black screen. Then every home activity the
     * package manager knows of but does not resolve, in a package that has an entry in the app list, is switched on.
     */
    private fun enableHome() {
        val resolved = guestShell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME", 30_000)
        val homes = Regex("""\S+/\S+""").findAll(resolved).map { it.value }.toSet()
        if (homes.any { !it.endsWith("FallbackHome") }) return
        val table = guestShell("dumpsys package -f r", 90_000).lines()
        val off = LinkedHashSet<String>()
        for (i in table.indices) {
            if (!table[i].contains("\"android.intent.category.HOME\"")) continue
            // the component line is the one above the filter's action/category lines
            val comp = (i downTo maxOf(0, i - 8)).firstNotNullOfOrNull { j -> Regex("""\s[0-9a-f]+ (\S+/\S+) filter""").find(table[j])?.groupValues?.get(1) } ?: continue
            if (comp !in homes) off += comp
        }
        // (not the system's own fallbacks, and not a setup or recovery flow: a launcher's package shows in the app list)
        val own = homes.map { it.substringBefore('/') }.toSet() + "android"
        val listed = Regex("""(\S+)/\S+""").findAll(guestShell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER", 30_000))
            .map { it.groupValues[1] }.toSet()
        val c = off.firstOrNull { it.substringBefore('/') !in own && it.substringBefore('/') in listed } ?: return
        log("home screen $c was switched off, switching it on")
        guestShell("pm enable $c; am start -a android.intent.action.MAIN -c android.intent.category.HOME", 30_000)
    }

    /**
     * 2.2–4.1 Google network location (in Play services and in Maps) calls TelephonyRegistry.listen with notifyNow at
     * start: the reply of that call is lost between the nested oneway callback and the reply, and the service dies with
     * "Unknown exception code" and a "has stopped" dialog on every boot. Nothing here needs network location.
     */
    private fun disableBrokenComponents() {
        if (img.api !in 9..16) return
        val marker = File(paths.root, "data/.aemu-components-disabled2")
        if (marker.isFile) return
        val list = listOf(
            "com.google.android.location/com.google.android.location.NetworkLocationService",
            "com.google.android.location/com.google.android.location.internal.server.NetworkLocationService",
            "com.google.android.apps.maps/com.google.android.location.internal.server.NetworkLocationService",
            "com.google.android.apps.maps/com.google.android.location.NetworkLocationService",
        )
        // PATH of 2.2–4.x starts with /sbin: the pm script's "app_process" is then looked up there and qemu gives up
        // Glass needs the "network" provider (settings' NewTimeZoneService requests it at boot and dies without it)
        val glass = GlassLocationPolicy.isGlass(img.name, img.model, File(paths.root, "system/app/GlassBluetooth.apk").isFile ||
            File(paths.root, "system/.aemu-parked/system#app#GlassBluetooth.apk").isFile)
        for (c in list) if (!GlassLocationPolicy.keepEnabled(c, glass)) runCatching { guestRunner.run(listOf("/system/bin/sh", "-c", "export PATH=/system/bin:/system/xbin:\$PATH; pm disable $c"), 120_000) }
        runCatching { marker.writeText("1") }
    }

    /** qemu maps a guest path into the tree only if the file exists there, so the shim's request file is pre-made */
    private val power: File get() = File(paths.root, "dev/aemu_power")

    private fun watchdog() {
        runCatching { power.writeText(""); power.setWritable(true, false) }
        // 7.0+: the guest deletes the host-path alias of the root that the linker stats at start-up (see TreeFixer);
        // a program started without it aborts, so it is checked far more often than the supervision loop below
        if (img.api >= 24) Thread({
            val fixer = TreeFixer(ctx, paths, img, ::log)
            while (!stopping) {
                runCatching { fixer.ensureHostPathAlias() }
                try { Thread.sleep(200) } catch (_: InterruptedException) { break }
            }
        }, "host-path-alias").apply { isDaemon = true; start() }
        Thread({
            var restarts = HashMap<String, Int>()
            while (!stopping) {
                Thread.sleep(3000)
                if (power.length() > 0) { val v = runCatching { power.readText().trim() }.getOrDefault("reboot"); power.writeText(""); powerRequest(v) }
                if (img.api >= 14) runCatching { dns.ensure() }
                val dead = synchronized(procs) { procs.filter { !it.value.isAlive }.toList() }
                for ((name, p) in dead) {
                    if (stopping) break
                    // Consume this exact dead instance before spawning a replacement. Removing
                    // by name after restartZygote() erased the newly started zygote from supervision.
                    if (!synchronized(procs) { procs.remove(name, p) }) continue
                    val code = runCatching { p.exitValue() }.getOrDefault(-1)
                    if (name in setOf("zygote", "surfaceflinger", "mediaserver")) noteBoot.interrupted()
                    // a clean exit (code 0) is the normal end of the animation; anything else is a crash or a kill
                    if (name == "bootanim" && code != 0) checkDeadBootAnimLayer(code)
                    if (name == "zygote" || name == "zygote_secondary") {
                        // 2.x with "Phone GPU" off: the firmware's software renderer (pixelflinger, libGLES_android.so) crashes
                        // under qemu inside SurfaceFlinger, which lives in system_server, so zygote dies with 137 on every boot
                        // and the loop never ends. The GL bridge is the working path: switch it on and restart the VM once.
                        if (state != State.FAILED && engine == Engine.GB && !settings.gpu && softwareGlCrashed() &&
                            swGlFallbackStarted.compareAndSet(false, true)) {
                            log("✖ software renderer (libGLES_android.so) crashed in SurfaceFlinger; turning the Phone GPU bridge on and restarting the VM")
                            ImageStore.update(ctx, img.id) { it.copy(settings = it.settings.copy(gpu = true)) }
                            onPower?.invoke(true, "")
                            continue
                        }
                        // Glass 4.4 first boot: IMMS fails without a selected IME; settings.db now exists, so seed it and boot again once
                        if (state != State.FAILED && !everBooted && !imeRetried && guestLogHas(GlassImePolicy.FAILURE_MARK)) {
                            imeRetried = true
                            if (TreeFixer(ctx, paths, img, ::log).seedDefaultIme()) {
                                log("✖ system_server failed in InputMethodManagerService, default input method set, starting the system again")
                                restartZygote()
                                continue
                            }
                        }
                        if (state != State.FAILED && bootDoneAt == 0L && !(everBooted && zygoteRestarts < 6)) {
                            failure = if (name != "zygote") Msg("$name exited (code $code)" + if (code == 137) " — killed: the phone ran out of memory, or system_server died and took zygote with it (see the guest log)" else "")
                                else if (code == 137) Msg("zygote exited (code $code) — SIGKILL (cause not established)", R.string.vmfail_zygote_exited_sigkill, code)
                                else Msg("zygote exited (code $code)", R.string.vmfail_zygote_exited, code)
                            // zygote kills itself with SIGKILL when system_server dies, so 137 is only the end of the chain
                            if (code == 137 && guestLogHas("Failure starting system services"))
                                failure = Msg("system_server failed to start its services (zygote exited with 137 as a result); see the first BOOT FAILURE in the guest log", R.string.vmfail_system_server_services)
                            if (code == 137 && guestLogHas("IKeystoreService"))
                                failure = Msg("system_server died because the keystore service does not exist (no keymaster HAL registered); see the \"keymaster:\" lines of this log")
                            if (code == 137 && guestLogHas("IBatteryPropertiesRegistrar.registerListener")) {
                                // registered but not found by system_server, or never registered? record what the guest says now
                                runCatching {
                                    val r = guestRunner.run(listOf("/system/bin/service", "check", "batteryproperties"), 8_000).second
                                    log("healthd: after the failure, service check batteryproperties -> \"${r.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("WARNING: linker") }.orEmpty().take(160)}\"")
                                }
                            }
                            if (code == 137 && guestLogHas("IBatteryPropertiesRegistrar.registerListener"))
                                failure = Msg("system_server cannot start: healthd did not register the \"batteryproperties\" service (BatteryService got null); see the healthd log in the VM log folder", R.string.vmfail_system_server_healthd)
                            if (code == 137 && guestLogHas("No original dex files found"))
                                failure = Msg("system_server cannot start: the precompiled code of the framework (system/framework/oat) is missing from this container — import the firmware again", R.string.vmfail_system_server_oat_missing)
                            log("✖ $failure"); setState(State.FAILED)
                        } else if (plannedRestart) {
                            plannedRestart = false
                            killZygoteChildren()
                            TreeFixer(ctx, paths, img, ::log).noScreenSleep()
                            restartZygote()
                        } else if (state != State.FAILED && (bootDoneAt > 0 || everBooted)) {
                            zygoteRestarts++
                            val now = System.currentTimeMillis()
                            zygoteCrashes.addLast(now)
                            while (zygoteCrashes.isNotEmpty() && zygoteCrashes.first() < now - 600_000) zygoteCrashes.removeFirst()
                            val recentCrashesIn1Min = zygoteCrashes.count { it >= now - 60_000 }
                            if (recentCrashesIn1Min >= 5 || zygoteCrashes.size > 6) {
                                failure = Msg("system keeps crashing: $name exited $recentCrashesIn1Min times in 1 min (${zygoteCrashes.size} times in 10 min, last code $code)")
                                log("✖ $failure"); setState(State.FAILED)
                            } else {
                                // (system_server's LockSettingsService thread died on KeyStore.state(): AssertionError(1) is what it throws
                                // when the keystore service answers getState with anything but NO_ERROR / LOCKED / UNINITIALIZED)
                                if (code == 137 && guestLogHas("android.security.KeyStore.state"))
                                    log("keymaster: system_server died in KeyStore.state(): the \"android.security.keystore\" service gave getState an answer " +
                                        "it does not know (the stand-in must say NO_ERROR; the real keystore needs the keymaster HAL, see the \"keymaster:\" lines)")
                                log("✖ $name crashed (code $code), restarting system ($zygoteRestarts)")
                                restartZygote()
                            }
                        }
                        continue
                    }
                    // aemu-bt / aemu-net died on a signal (SIGBUS 135, SIGSEGV 139, SIGABRT 134): start the same stub again, the
                    // second start finds the compiled jar already in place. A clean exit or a deliberate kill is left alone.
                    val stub = stubServices[name]
                    if (stub != null) {
                        val k = restarts.getOrDefault(name, 0)
                        if (code in 129..159 && code != 137 && code != 143 && k < 3) {
                            restarts[name] = k + 1
                            log("service $name crashed (code $code), restarting (${k + 1}/3)")
                            runCatching { startService(stub) }
                        }
                        continue
                    }
                    val n = restarts.getOrDefault(name, 0)
                    val svc = storedServices().firstOrNull { it.name == name }
                        ?: if (name.contains("-hal-") && n < 3) servicePlan().firstOrNull { it.name == name } else null
                    // The engine's composer ("hwcomposer-2-1") is not a "-hal-" service and nothing started it again: after its
                    // crash SurfaceFlinger (which aborts on the DEAD_OBJECT) waited for IComposer for ever. Start it again;
                    // SurfaceFlinger, restarted below, finds it.
                    if (engine == Engine.N64 && (name.startsWith("hwcomposer") || name.contains("graphics.composer")) &&
                        (code == 139 || code == 135 || code == 134) && n < 6) {
                        val composer = svc ?: servicePlan().firstOrNull { it.name == name }
                        if (composer != null) {
                            restarts[name] = n + 1
                            log("service $name crashed (code $code), restarting (${n + 1}/6)")
                            runCatching { startService(composer) }
                            continue
                        }
                    }
                    // init restarts surfaceflinger and then zygote ("onrestart restart zygote"); without that a
                    // crashed compositor leaves the last frame on screen forever
                    // The engine's SurfaceFlinger (AOSP) died during the boot: with a libgui whose BufferItem is laid out
                    // differently (Google Jamboard, Tegra) FramebufferSurface::nextBuffer reads a garbage slot out of the
                    // item on the very first frame. The firmware's own SurfaceFlinger goes with its own libgui: put it back.
                    if (name == "surfaceflinger" && code == 139 && bootDoneAt == 0L && engine == Engine.N64 && useFirmwareSurfaceFlinger()) {
                        val sf = svc ?: servicePlan().firstOrNull { it.name == name }
                        if (sf != null) { runCatching { startService(sf) }; continue }
                    }
                    if (name == "surfaceflinger" && svc != null && bootDoneAt > 0 && n < 12) {
                        restarts[name] = n + 1
                        log("✖ surfaceflinger crashed (code $code), restarting it and the system (${n + 1}/12)")
                        runCatching { startService(svc) }
                        synchronized(procs) { procs["zygote"] }?.let { z -> pidOf(z)?.let { runCatching { AProcess.sendSignal(it, 9) } } }
                        continue
                    }
                    // родной audio_policy производителя может падать с нашим HAL — подменяем на AOSP
                    if (name == "mediaserver" && (code == 139 || code == 134) && n == 1) swapAudioPolicy()
                    // the AOSP stand-in crashed as well (different service ABI): keep the firmware's own policy for good
                    if (name == "mediaserver" && (code == 139 || code == 134) && n == 2) abandonAospPolicy()
                    // (init restarts every service that is not oneshot; a HAL that leaves when its only client, system_server, is
                    // restarted would otherwise keep the new system_server waiting for it forever)
                    val hal = name.contains("-hal-") && n < 3
                    if (svc != null && (svc.restart || name in ALWAYS_RESTART || hal) && code != 137 && code != 143 && n < 12) {
                        restarts[name] = n + 1
                        log("service $name crashed (code $code), restarting (${n + 1}/12)")
                        runCatching { startService(svc) }
                    }
                }
                if (pendingNote.isNotBlank() && !recoveryMode && !lowPowerBoot && !stopping) {
                    val healthy = synchronized(procs) {
                        listOf("zygote", "surfaceflinger").all { procs[it]?.isAlive == true }
                    }
                    if (noteBoot.ready(SystemClock.elapsedRealtime(), state == State.RUNNING, healthy)) {
                        runCatching {
                            val saved = ImageStore.update(ctx, img.id) { it.copy(oneTimeNote = "") }
                            if (saved != null) pendingNote = ""
                        }.onFailure { log("ROM note: could not save completion: ${it.message}") }
                    }
                }
            }
        }, "vm-watchdog").apply { isDaemon = true; start() }
    }

    /**
     * Ставит политику AOSP 4.3 за обёрткой: родная сохраняется, лишние потоки производителя
     * (Samsung: 0..14 против 0..9 в AOSP) обёртка сводит к MUSIC, чтобы AOSP-код не писал за массив.
     */
    private fun swapAudioPolicy(crashed: Boolean = false) {
        if (img.api < 16) return
        val f = File(paths.root, "system/lib/hw/audio_policy.default.so")
        val aosp = File(paths.root, "system/lib/libaemu_apaosp.so")
        val parked = File(paths.root, "system/.aemu-parked/system#lib#hw#audio_policy.default.so")
        val failedMark = File(paths.root, "system/.aemu-parked/audio-aosp-failed")
        if (failedMark.isFile) { // the AOSP stand-in already crashed on this firmware: never install it again
            if (parked.isFile) runCatching { parked.copyTo(f, overwrite = true); aosp.delete() }
            return
        }
        // The previous stream-tail bug made mediaserver crash and triggered a
        // policy fallback. ZR's stock policy itself was not the origin of PC=2;
        // the AOSP fallback has a different service ABI and crashes at 0x98.
        val directTrack = img.api in 19..20 && runCatching {
            AudioHalAbi.usesDirectTrackTail(File(paths.root, "system/lib/libnbaio.so").readBytes())
        }.getOrDefault(false)
        if (directTrack) {
            if (parked.isFile) runCatching {
                parked.copyTo(f, overwrite = true) // retain saved stock binary
                log("audio: CAF direct-track, restored firmware audio policy")
            }.onFailure { log("audio: policy restore failed: ${it.message}") }
            return
        }
        // MediaTek: AudioPolicyService переделан (другие ops и слоты), политика AOSP в нём падает.
        // Родная политика MTK падала только из-за проверки DRVB, которую теперь снимает TreeFixer
        if (TreeFixer.isMtkPlatform(paths.root)) {
            if (parked.isFile) runCatching {
                parked.copyTo(f, overwrite = true); parked.delete(); aosp.delete()
                log("audio: MediaTek, restored stock audio_policy")
            }
            return
        }
        // the AOSP policy itself crashes in some vendors' audio services (Samsung 4.4: its first output is not found
        // right after it is added): after three more crashes in a row it is taken out again for good, and mediaserver
        // runs as it did before the swap
        val bad = File(paths.root, "system/.aemu-parked/audio_policy.aosp-bad")
        if (bad.isFile) return
        fun assetSize(n: String) = runCatching { EngineAssets.size(ctx, "engines/kk/$n") }.getOrDefault(-1L)
        if (aosp.length() == assetSize("audio_policy.default.so") && f.length() == assetSize("audio_policy.wrap.so")) {
            if (!crashed) return
            runCatching {
                bad.parentFile?.mkdirs(); bad.writeText("")
                aosp.delete()
                if (parked.isFile) { parked.copyTo(f, overwrite = true); parked.delete() } else f.delete()
                log("audio: the AOSP audio_policy crashes here too, removed it")
            }
            return
        }
        runCatching {
            parked.parentFile?.mkdirs()
            if (!parked.isFile && f.isFile) f.copyTo(parked, overwrite = true)
            EngineAssets.open(ctx, "engines/kk/audio_policy.default.so").use { i -> aosp.outputStream().use { o -> i.copyTo(o) } }
            EngineAssets.open(ctx, "engines/kk/audio_policy.wrap.so").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            log("audio: firmware audio_policy crashes, installed AOSP one (stock kept)")
        }
    }

    /**
     * Puts the firmware's own SurfaceFlinger (set aside when the engine's was installed) back and remembers that the
     * engine's does not work with this firmware's libgui (see TreeFixer: the marker keeps it from being replaced again).
     * False when that was already done or there is nothing to put back.
     */
    private fun useFirmwareSurfaceFlinger(): Boolean {
        val dir = File(paths.root, "system/.aemu-parked")
        val marker = File(dir, "firmware-sf")
        if (marker.exists()) return false
        val pairs = listOf("system#bin#surfaceflinger" to "system/bin/surfaceflinger",
            "system#lib64#libsurfaceflinger.so" to "system/lib64/libsurfaceflinger.so")
        if (pairs.any { !File(dir, it.first).isFile }) return false
        return runCatching {
            for ((parked, live) in pairs) {
                val dst = File(paths.root, live)
                dst.delete()
                if (!File(dir, parked).renameTo(dst)) File(dir, parked).copyTo(dst, overwrite = true)
                dst.setReadable(true, false); dst.setExecutable(true, false)
            }
            marker.writeText("1")
            log("surfaceflinger: the engine's build crashed on the first frame (libgui mismatch), the firmware's own SurfaceFlinger takes over")
            true
        }.getOrElse { log("surfaceflinger: cannot put the firmware's own back: ${it.message}"); false }
    }

    /** Puts the firmware's own audio policy back and remembers that the AOSP stand-in does not work here. */
    private fun abandonAospPolicy() {
        val f = File(paths.root, "system/lib/hw/audio_policy.default.so")
        val aosp = File(paths.root, "system/lib/libaemu_apaosp.so")
        val parked = File(paths.root, "system/.aemu-parked/system#lib#hw#audio_policy.default.so")
        if (!parked.isFile || !aosp.isFile) return
        runCatching {
            parked.copyTo(f, overwrite = true)
            aosp.delete()
            File(paths.root, "system/.aemu-parked/audio-aosp-failed").writeText("1")
            log("audio: AOSP policy crashed too, restored the firmware's own audio policy")
        }.onFailure { log("audio: cannot restore firmware audio policy: ${it.message}") }
    }

    private val bootAnimCheckRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var bootAnimFixes = 0

    /**
     * bootanim died abnormally. If SurfaceFlinger still keeps its layer on top once the system is up (the dead
     * client's layer is never reaped here), the screen stays black on top of the real UI. Restart SurfaceFlinger
     * and the system through the usual crash path of the watchdog, which drops that layer.
     */
    private fun checkDeadBootAnimLayer(code: Int) {
        if (!bootAnimCheckRunning.compareAndSet(false, true)) return
        log("boot animation died (code $code), will check the top SurfaceFlinger layer after boot")
        Thread({
            try {
                while (!stopping && bootAnimFixes < 2) {
                    // while booting the animation layer is legitimately on top
                    if (!waitFor("boot completion (bootanim check)", 300_000) { bootDoneAt != 0L }) return@Thread
                    Thread.sleep(5000)
                    if (stopping) return@Thread
                    val (_, dump) = guestRunner.run(listOf("/system/bin/sh", "-c",
                        "export PATH=/system/bin:/system/xbin:\$PATH; dumpsys SurfaceFlinger"), 30_000)
                    val layers = BootAnimLayer.visibleLayers(dump)
                    if (layers.isEmpty() || !BootAnimLayer.isBootAnim(layers.last())) {
                        log("boot animation died, top layer is ${layers.lastOrNull() ?: "unknown"}: nothing to fix")
                        return@Thread
                    }
                    bootAnimFixes++
                    log("✖ dead boot animation is still the top layer, restarting SurfaceFlinger ($bootAnimFixes/2)")
                    val sf = synchronized(procs) { procs["surfaceflinger"] }
                    val pid = sf?.let { pidOf(it) }
                    if (pid == null) { log("surfaceflinger is not running, cannot restart it"); return@Thread }
                    // the watchdog sees it die, starts it again and restarts zygote
                    runCatching { AProcess.sendSignal(pid, 9) }
                    Thread.sleep(10_000) // let the watchdog reset bootDoneAt before waiting for the next boot
                }
            } catch (_: InterruptedException) {
            } finally { bootAnimCheckRunning.set(false) }
        }, "bootanim-layer-check").apply { isDaemon = true; start() }
    }
    @Volatile private var plannedRestart = false
    private var wizardSkipped = false

    /** Set by the screen: destroys the view's Surface and creates a new one (a new native window). */
    @Volatile var resurface: (() -> Unit)? = null

    /**
     * The in-app GPU bridge (API < 21) holds one EGL window surface on the app's native window, made for the guest's
     * system_server. When that process is SIGKILLed (planned first-boot restart, crash) the surface stays alive in the
     * bridge, the window is still connected, and the next system_server cannot create its own on it (EGL_BAD_ALLOC
     * 0x3003 in glbridge.log: "window did not accept the surface"): the screen stays black. Handing the bridge the
     * same Surface again changes nothing (same native window), so the view's Surface itself is recreated: the bridge
     * gets a different native window and drops the old one.
     */
    private fun reseatGlSurface() {
        if (!glInApp) return
        val cycle = resurface ?: return
        val old = surface
        runCatching {
            cycle()
            val end = SystemClock.elapsedRealtime() + 4_000
            while (SystemClock.elapsedRealtime() < end && (surface == null || surface === old)) Thread.sleep(50)
            log("GPU bridge: screen surface recreated before the system restarts (${if (surface != null && surface !== old) "ok" else "no new surface"})")
        }
    }

    private fun restartZygote() {
        val z = storedServices().firstOrNull { it.name == "zygote" } ?: return
        if (img.api >= 24) runCatching { TreeFixer(ctx, paths, img, ::log).ensureHostPathAlias() }
        bootDoneAt = 0
        setState(State.BOOTING)
        reseatGlSurface()
        paths.socket("zygote").delete()
        paths.socket("zygote_secondary").delete()
        // на настоящем ядре дети зиготы гибнут вместе с system_server; здесь они остаются жить и
        // держат ссылки на мёртвые службы (телефония отказывает новому system_server в правах)
        val n = killZygoteChildren()
        if (n > 0) log("killed apps of previous zygote: $n")
        // healthd keeps the battery listener of the dead system_server and stops answering the new one:
        // BatteryService.onStart then blocks in registerListener forever
        servicePlan().firstOrNull { it.name == "healthd" }?.let { h ->
            synchronized(procs) { procs.remove("healthd") }?.let { runCatching { it.destroyForcibly() } }
            runCatching { startService(h) }
        }
        runCatching { startService(z) }
        watchLegacyBoot()   // (a pre-1.0 build never reports boot completion by itself)
        img.services.firstOrNull { it.name == "zygote_secondary" }?.let { z2 ->
            synchronized(procs) { procs.remove("zygote_secondary") }?.let { runCatching { it.destroyForcibly() } }
            runCatching { startService(z2) }
        }
    }

    private fun guestLogHas(text: String): Boolean = runCatching {
        val f = File(paths.root, "dev/log/main")
        val len = f.length()
        java.io.RandomAccessFile(f, "r").use { r ->
            val n = minOf(len, 400_000L).toInt(); r.seek(len - n)
            val b = ByteArray(n); r.readFully(b)
            String(b, Charsets.ISO_8859_1).contains(text)
        }
    }.getOrDefault(false)

    private fun killZygoteChildren(): Int {
        val marker = "/images/${img.id}/"
        var n = 0
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            val cmd = runCatching { String(File(d, "cmdline").readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: return@forEach
            // app_process с aemu-stubs.jar — наша заглушка bluetooth_manager, не приложение зиготы
            if (cmd.contains(marker) && cmd.contains("/system/bin/app_process") && !cmd.contains("aemu-stubs.jar") && !cmd.contains("aemu-bt.jar") && !cmd.contains(WifiEmu.JAR_NAME) && !cmd.contains("aemu-sensorhub.jar")) {
                runCatching { AProcess.sendSignal(pid, 9) }; n++
            } else if (orphanOfThisImage(d)) {
                // 7.0+: a zygote child rewrites its whole command line ("system_server"), so the path is gone from it;
                // what is left of a dead zygote is known by the root in its environment and by having no parent
                runCatching { AProcess.sendSignal(pid, 9) }; n++
            }
        }
        return n
    }

    /** a guest program of this image whose parent has exited (init of the host adopted it) */
    private fun orphanOfThisImage(proc: File): Boolean {
        val status = runCatching { File(proc, "status").readLines() }.getOrNull() ?: return false
        if (status.none { it.startsWith("PPid:") && it.substringAfter(':').trim() == "1" }) return false
        val env = runCatching { String(File(proc, "environ").readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: return false
        return env.split(0.toChar()).any { it == "AEMU64_ROOT=${paths.root.absolutePath}" || it == "AEMU64_ROOT=${paths.root.canonicalPath}" }
    }

    // ------------------------------------------------------------------ остановка

    /**
     * Runs one teardown step on its own thread and gives up on it after [timeoutMs]: a component that hangs (a FIFO
     * nobody reads, a native call) must not keep the whole VM on "stopping system" forever.
     */
    private fun step(name: String, timeoutMs: Long = 4_000, action: () -> Unit) {
        val t = Thread({ runCatching { action() }.onFailure { log("stop: $name failed: $it") } }, "stop-$name").apply { isDaemon = true; start() }
        t.join(timeoutMs)
        if (t.isAlive) log("stop: $name did not finish in ${timeoutMs / 1000} s, skipped")
    }

    fun stop() {
        if (state == State.STOPPED) return
        stopping = true
        setState(State.STOPPING)
        log("stopping system")
        try {
            step("vibration") { vibration.stop() }
            step("battery", 8_000) { battery.stop() }
            step("camera") { camera.stop() }
            step("microphone") { microphone.stop() }
            step("bluetooth") { bluetooth.stop() }
            step("motion") { motion.stop() }
            step("sync", 8_000) { guestRunner.run(listOf("/system/bin/sync"), 5_000) }
            step("legacyBootWatch") { stopLegacyBootWatch() }
            step("kill") { killAll() }
            step("adb") { adb.stop() }
            step("props") { props.stop() }
            step("input") { input.stop() }
            step("frames") { frames.stop() }
            step("ril") { ril.stop() }
            step("vold") { vold.stop() }
            step("audio") { audio.stop() }
            step("net") { net.stop() }
            step("dns") { dns.stop() }
            step("events") { events.stop() }
            step("logd") { logd.stop() }
            step("lights") { lights.stop() }
            step("sensors") { sensors.stop() }
            step("mic") { mic.stop() }
            step("cameraFeed") { cameraFeed.stop() }
            step("gl") { GlHost.stop() }
            step("stubs") { extraStubs.forEach { it.stop() }; extraStubs.clear() }
            step("lmk") { lmk?.stop(); lmk = null }
        } finally {
            runCatching { Keeper.release(ctx) }
            setState(State.STOPPED)
        }
    }

    private fun killAll() {
        val list = synchronized(procs) { procs.values.toList() }
        for (g in pgids) runCatching { android.system.Os.kill(-g, android.system.OsConstants.SIGKILL) }
        list.forEach { runCatching { it.destroyForcibly() } }
        synchronized(procs) { procs.clear() }
        pgids.clear()
        killLeftovers()
    }

    /** Добивает процессы гостя, оставшиеся от прошлых запусков (qemu, binderd, dhdrun). */
    fun killLeftovers(): Int {
        val mine = AProcess.myPid()
        var n = 0
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            if (pid == mine) return@forEach
            val cmd = runCatching { String(File(d, "cmdline").readBytes(), Charsets.ISO_8859_1).replace('\u0000', ' ') }.getOrNull() ?: return@forEach
            val ours = cmd.contains("libaemu64") || cmd.contains("libbinderd64") || (cmd.contains("/images/") && (cmd.contains("libqemu") || cmd.contains("libbinderd") || cmd.contains("libdhdrun") || cmd.contains("libglserverd"))) ||
                cmd.startsWith("${ctx.packageName}:binder") || cmd.startsWith("${ctx.packageName}:gl")
            if (ours) { runCatching { AProcess.sendSignal(pid, 9) }; n++ }
        }
        if (n > 0) log("cleaned up processes from previous run: $n")
        return n
    }

    // ------------------------------------------------------------------ служебное

    fun bootSeconds(): Long = if (bootAt == 0L) 0 else ((if (bootDoneAt > 0) bootDoneAt else System.currentTimeMillis()) - bootAt) / 1000

    /**
     * One-shot `sh -c` in the guest. Same setup as the adb shell: /system/bin first in PATH and shell functions for
     * script tools (am, pm, content, settings...), because qemu cannot exec a script directly and sh then reports
     * "<tool>: not found".
     */
    fun guestShell(cmd: String, timeoutMs: Long = 60_000): String {
        if (engine == Engine.N64) return guestRunner.run(listOf("/system/bin/sh", "-c", shellPrefix + cmd), timeoutMs).second
        val r = guestRunner
        val path = GuestRunner.shellPath(r.env()["PATH"])
        return r.run(listOf("/system/bin/sh", "-c", GuestRunner.scriptFns(paths.root) + cmd), timeoutMs, mapOf("PATH" to path)).second
    }

    fun rotateGuest(): Pair<Int, String> = guestRunner.run(listOf(appProcess,
        "-Djava.class.path=/system/framework/aemu-setup.jar", "/system/bin", "app.aemu.setup.RotationCtl"),
        15_000, mapOf("DHD_UID" to "1000", "DHD_GID" to "1000"))

    /** Put in front of every guest shell command (menu, adb, terminal): PATH order and the tool wrappers. */
    val shellPrefix: String get() = "export PATH=/system/bin:/system/xbin:\$PATH; $toolWrappers"

    /**
     * Shell functions that run the firmware's Java tools (am, pm, input, svc…) through app.aemu.stub.Run, which
     * calls System.exit() after main(): left alone, the tool's VM waits forever for its binder thread and the command
     * never returns. Jar and class come from each tool's own script in /system/bin.
     */
    private val toolWrappers: String by lazy {
        val stubs = "/system/framework/aemu-stubs.jar"
        if (!File(paths.root, stubs.removePrefix("/")).isFile) return@lazy ""
        val sb = StringBuilder()
        File(paths.root, "system/bin").listFiles()?.forEach { f ->
            if (!f.isFile || f.length() > 8_000 || !f.name.matches(Regex("[a-z][a-z0-9_]*"))) return@forEach
            val text = runCatching { f.readText(Charsets.ISO_8859_1) }.getOrNull() ?: return@forEach
            val cls = Regex("app_process\\S*\\s+\\S+\\s+([A-Za-z_][\\w.$]*\\.[A-Z][\\w$]*)").find(text)?.groupValues?.get(1) ?: return@forEach
            val jar = Regex("framework/([\\w.-]+\\.jar)").find(text)?.groupValues?.get(1) ?: return@forEach
            sb.append("${f.name}() { CLASSPATH=/system/framework/$jar:$stubs app_process /system/bin app.aemu.stub.Run $cls \"\$@\"; }; ")
        }
        sb.toString()
    }

    fun installApk(apkOnSdcard: String): String = guestShell("pm install -r $apkOnSdcard", 600_000)

    private fun pidOf(p: Process): Int? {
        var c: Class<*>? = p.javaClass
        while (c != null) {
            val f = c.declaredFields.firstOrNull { it.name == "pid" }
            if (f != null) { f.isAccessible = true; return f.getInt(p) }
            c = c.superclass
        }
        return null
    }

    /** The 32-bit ABI list of a firmware with no 64-bit side: its own flat property if it has one, else the partition's
     *  (what init of 10+ derives it from), else its 32-bit entries of the combined list or its primary ABI. */
    private fun pure32Abilist32(): String {
        val all = HashMap<String, String>()
        for (n in listOf("system/build.prop", "vendor/default.prop", "vendor/build.prop", "odm/etc/build.prop", "odm/build.prop",
            "product/etc/build.prop", "product/build.prop", "system_ext/etc/build.prop", "system_ext/build.prop")) runCatching {
            File(paths.root, n).takeIf { it.isFile }?.let { f -> PropArea.parseProps(f.readText()).forEach { (k, v) -> if (v.isNotBlank()) all.putIfAbsent(k, v.trim()) } }
        }
        val order = (all["ro.product.property_source_order"] ?: "product,product_services,odm,vendor,system").split(',').map { it.trim() }
        val direct = all["ro.product.cpu.abilist32"]
            ?: all["ro.vendor.product.cpu.abilist32"]
            ?: order.firstNotNullOfOrNull { all["ro.product.$it.cpu.abilist32"] }
        if (direct != null) return direct
        fun is32(a: String) = !a.contains("64")
        val combined = (all["ro.product.cpu.abilist"] ?: order.firstNotNullOfOrNull { all["ro.product.$it.cpu.abilist"] })
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() && is32(it) }
        if (!combined.isNullOrEmpty()) return combined.joinToString(",")
        val abi = (all["ro.product.cpu.abi"] ?: all["ro.product.cpu.abi2"])?.takeIf { is32(it) }
        return if (abi != null && abi != "armeabi") "$abi,armeabi" else "armeabi-v7a,armeabi"
    }

    /** What init of 10+ does after reading the property files: ro.product.<what> from the first partition that has
     *  ro.product.<partition>.<what>, and the fingerprint from its parts. Only where the firmware does not say. */
    private fun deriveIdentity(later: MutableMap<String, String>) {
        val all = LinkedHashMap<String, String>()
        for (n in listOf("system/build.prop", "vendor/default.prop", "vendor/build.prop", "odm/etc/build.prop", "odm/build.prop",
            "product/etc/build.prop", "product/build.prop", "product_services/build.prop", "system_ext/etc/build.prop", "system_ext/build.prop")) runCatching {
            File(paths.root, n).takeIf { it.isFile }?.let { f -> PropArea.parseProps(f.readText()).forEach { (k, v) -> all.putIfAbsent(k, v) } }
        }
        // (the files init of 10 reads besides the ones above: the odm's and the product's)
        for (n in listOf("odm/etc/build.prop", "odm/build.prop", "product/etc/build.prop", "product/build.prop", "product_services/build.prop",
            "system_ext/etc/build.prop", "system_ext/build.prop", "mi_ext/etc/build.prop")) runCatching {
            File(paths.root, n).takeIf { it.isFile }?.let { f -> PropArea.parseProps(f.readText()).forEach { (k, v) -> later.putIfAbsent(k, v) } }
        }
        val order = (all["ro.product.property_source_order"] ?: "product,product_services,odm,vendor,system").split(',')
        for (what in listOf("brand", "device", "manufacturer", "model", "name")) {
            if (all.containsKey("ro.product.$what")) continue
            val v = order.firstNotNullOfOrNull { all["ro.product.${it.trim()}.$what"]?.takeIf { s -> s.isNotEmpty() } } ?: continue
            all["ro.product.$what"] = v; later.putIfAbsent("ro.product.$what", v)
        }
        if (!all.containsKey("ro.build.fingerprint")) {
            fun p(k: String) = all[k] ?: "unknown"
            later.putIfAbsent("ro.build.fingerprint", p("ro.product.brand") + "/" + p("ro.product.name") + "/" + p("ro.product.device") + ":" +
                p("ro.build.version.release") + "/" + p("ro.build.id") + "/" + p("ro.build.version.incremental") + ":" + p("ro.build.type") + "/" + p("ro.build.tags"))
        }
    }

    private fun gmtZone(): String {
        val h = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000
        return if (h == 0) "Etc/GMT" else "Etc/GMT" + (if (h > 0) "-$h" else "+${-h}")
    }

    private fun isLinkTo(f: File, target: String) = runCatching { android.system.Os.readlink(f.absolutePath) == target }.getOrDefault(false)

    private fun stamp() = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
}

/** A recovery that asks to reboot or exits within this long after it started did not get a person to choose anything. */
private const val RECOVERY_EARLY_MS = 8_000L
