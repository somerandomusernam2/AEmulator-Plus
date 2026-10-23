/* Modified for AEmulator Sunset, 2026-10-03: persisted motion and host-display options.
 * Modified for AEmulator Super Plus, 2026-10-11: fake SIM card slots, Wi-Fi signal and neighbours.
 * GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import org.json.JSONArray
import org.json.JSONObject

/** Настройки конкретного образа, которые пользователь может менять. */
data class VmSettings(
    val width: Int = 540,
    val height: Int = 960,
    val density: Int = 240,
    /** аппаратная отрисовка через GPU телефона (иначе программный растеризатор прошивки) */
    val gpu: Boolean = true,
    /** hwui в гостевых приложениях (4.x) */
    val hwui: Boolean = true,
    /** JIT Dalvik; без него стабильнее, но медленнее */
    val jit: Boolean = true,
    /** ART (5.0+): compile apps fully to machine code at install/first boot; off = interpret-only, fast first boot */
    val fullDexopt: Boolean = false,
    /** частота развёртки гостя, Гц */
    val fbHz: Int = 60,
    /** касаний в секунду, которые шлём гостю */
    val touchHz: Int = 60,
    /** HTTP/HTTPS-прокси с современным TLS для старых браузеров */
    val netProxy: Boolean = true,
    val lowRam: Boolean = false,
    val showFrame: Boolean = false,
    val showNavBar: Boolean = true,
    val hideMenuButton: Boolean = false,
    val navButtons: String = NavControls.DEFAULT_BUTTONS,
    val trackball: Boolean = false,
    val trackballDpad: Boolean = false,
    val trackballStepDp: Int = 18,
    val keepScreenOn: Boolean = true,
    /** the guest phone's notification LED drawn above its screen (0.0.0.3) */
    val showLed: Boolean = true,
    val vibration: Boolean = true,
    val hostBattery: Boolean = true,
    /** Explicit host-camera opt-in; applies at the next full VM boot. */
    val camera: Boolean = false,
    /** Explicit host-microphone opt-in (guest app records from the phone microphone); applies at the next full VM boot. */
    val microphone: Boolean = false,
    /** Experimental host Bluetooth passthrough (scan, RFCOMM, GATT); applies at the next full VM boot. */
    val bluetooth: Boolean = false,
    val motionSensors: Boolean = false,
    /** Run the guest without any sensors; null = automatic (on for Nougat+ Wear SDK builds, see [SensorHalApi.crashesWithHostSensors]). */
    val disableSensors: Boolean? = null,
    /** With host motion sensors: 0 = disabled (real orientation), 1 = locked to 0°, 2 = locked to 180°, 3 = no gyroscope (see [GyroLock]). */
    val gyroLock: Int = GyroLock.DISABLED,
    val hostResolution: Boolean = false,
    val skipSetupWizard: Boolean = false,
    val disableGoogleApps: Boolean = false,
    val mtMode: Int = 0,
    /** старый движок для 2.x (GL через pbuffer, только GLES 1.x) — запасной вариант */
    val legacyEngine: Boolean = false,
    /** guest RAM budget in MB, 0 = automatic */
    val ramMb: Int = 0,
    /** emulate the radio (RIL); off = tablet-like firmware without telephony */
    val radio: Boolean = true,
    /** hardware 32-bit (AArch32 EL0) bypass without translator on supported CPUs */
    val native32: Boolean = false,
    /**
     * A 32-bit-only firmware of Android 8.0+ (armeabi-v7a, e.g. Wear OS 9) runs on the native arm64 engine with qemu-arm
     * translating its programs. The universal engine's binder daemon speaks binder protocol 7, but 8.0+ userspace
     * wants 8 (the 64-bit binder structs): servicemanager exits at once and nothing else comes up.
     */
    val translate32: Boolean = true,
    /** IMEI reported by the fake modem; blank = default sample IMEI */
    val imei: String = "",
    /** ro.serialno / ro.boot.serialno reported to the guest; blank = default sample serial */
    val serial: String = "",
    /** kernel version shown in /proc/version and uname -r; blank = built-in default. A full "Linux version ..." line is used as is */
    val kernel: String = "",
    /** branch/local-version suffix appended to the release (e.g. "perf" -> 3.0.31-aemu-perf); blank = none. Ignored when [kernel] is a full "Linux version ..." line */
    val kernelBranch: String = "",
    /** commit id appended to the release as -g<id> (e.g. "1a2b3c4d"); blank = none. Ignored when [kernel] is a full "Linux version ..." line */
    val kernelCommit: String = "",
    /** user in (user@host) of /proc/version; blank = aemu. Ignored when [kernel] is a full "Linux version ..." line */
    val kernelUser: String = "",
    /** host in (user@host) of /proc/version; blank = aemu. Ignored when [kernel] is a full "Linux version ..." line */
    val kernelHost: String = "",
    /** compiler text of /proc/version; blank = "gcc version 4.6". Ignored when [kernel] is a full "Linux version ..." line */
    val kernelCompiler: String = "",
    /** build counter after the # sign; blank = 1. Ignored when [kernel] is a full "Linux version ..." line */
    val kernelBuild: String = "",
    /** build flags after the counter; blank = "SMP PREEMPT". Ignored when [kernel] is a full "Linux version ..." line */
    val kernelFlags: String = "",
    /** build date at the end of /proc/version; blank = "Thu Jan 1 00:00:00 UTC 2026". Ignored when [kernel] is a full "Linux version ..." line */
    val kernelDate: String = "",
    /** gsm.version.baseband and the modem's baseband answer; blank = default */
    val baseband: String = "",
    /** radio technology the fake modem reports (see [NetworkType]); default 2G (EDGE) */
    val networkType: Int = NetworkType.DEFAULT,
    /** extra qemu options; KEY=VALUE tokens are passed as environment variables */
    val qemuArgs: String = "",
    /** Guest services whose qemu system calls are traced into the service log, comma separated ("mediaserver,zygote"). */
    val straceServices: String = "",
    /** ro.hardware given to the guest at launch; blank = whatever the firmware defines */
    val hardware: String = "",
    /** Emulated Wi-Fi: the guest sees a connected Wi-Fi network [wifiSsid] that carries the host's internet; applies at the next full VM boot. */
    val wifi: Boolean = false,
    /** Name of the emulated Wi-Fi network. */
    val wifiSsid: String = WifiEmu.DEFAULT_SSID,
    /** WPA2 passphrase of the emulated network; blank = open network. */
    val wifiPassword: String = "",
    /** Fake SIM in slot 1 (see [SimCard]); blank fields = the operator of the firmware's APN list, as before. */
    val sim: SimCard = SimCard(),
    /** Second slot: only used with [dualSim] on firmware built for two SIMs. */
    val sim2: SimCard = SimCard(present = false),
    val dualSim: Boolean = false,
    /** Bars the emulated Wi-Fi shows, 1..4 (see [WifiEmu.rssiFor]). */
    val wifiSignal: Int = WifiEmu.MAX_BARS,
    /** A scan also lists a few other (locked) networks nearby. */
    val wifiNeighbors: Boolean = true,
    /** Guest thermal_zone17 shows the phone's real sensor; off (default) = a constant cool 30 C (see [ThermalSysfs]). */
    val thermalPassthrough: Boolean = false,
    /** Value of thermal_zone17/temp in millidegrees set by the user; null = follow [thermalPassthrough]. */
    val thermalTemp: Int? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("width", width).put("height", height).put("density", density)
        .put("gpu", gpu).put("hwui", hwui).put("jit", jit).put("fullDexopt", fullDexopt).put("fbHz", fbHz)
        .put("touchHz", touchHz).put("netProxy", netProxy).put("lowRam", lowRam)
        .put("showFrame", showFrame).put("showNavBar", showNavBar)
        .put("hideMenuButton", hideMenuButton)
        .put("navButtons", NavControls.encode(NavControls.parse(navButtons)))
        .put("trackball", trackball).put("trackballDpad", trackballDpad)
        .put("trackballStepDp", trackballStepDp.coerceIn(4, 48))
        .put("keepScreenOn", keepScreenOn).put("showLed", showLed).put("native32", native32).put("translate32", translate32).put("mtMode", mtMode).put("legacyEngine", legacyEngine)
        .put("vibration", vibration)
        .put("hostBattery", hostBattery)
        .put("camera", camera)
        .put("microphone", microphone)
        .put("bluetooth", bluetooth)
        .put("motionSensors", motionSensors).put("disableSensors", disableSensors).put("gyroLock", gyroLock).put("hostResolution", hostResolution)
        .put("skipSetupWizard", skipSetupWizard)
        .put("disableGoogleApps", disableGoogleApps)
        .put("ramMb", ramMb).put("radio", radio).put("imei", imei).put("serial", serial).put("kernel", kernel).put("kernelBranch", kernelBranch).put("kernelCommit", kernelCommit).put("kernelUser", kernelUser).put("kernelHost", kernelHost).put("kernelCompiler", kernelCompiler).put("kernelBuild", kernelBuild).put("kernelFlags", kernelFlags).put("kernelDate", kernelDate).put("baseband", baseband).put("networkType", networkType).put("qemuArgs", qemuArgs).put("straceServices", straceServices)
        .put("hardware", hardware)
        .put("wifi", wifi).put("wifiSsid", wifiSsid).put("wifiPassword", wifiPassword)
        .put("sim", sim.toJson()).put("sim2", sim2.toJson()).put("dualSim", dualSim)
        .put("wifiSignal", wifiSignal).put("wifiNeighbors", wifiNeighbors)
        .put("thermalPassthrough", thermalPassthrough).put("thermalTemp", thermalTemp ?: JSONObject.NULL)

    companion object {
        const val NAV_DEFAULT = "back,home,recents,menu"
        val NAV_ALL = listOf("back", "home", "recents", "menu", "search", "voldown", "volup", "power", "dpad")

        /** Luhn-valid sample IMEI from the standard; the guest has no real modem */
        const val DEFAULT_IMEI = "490154203237518"

        /** Serial the guest sees when none is set (what the property template always used) */
        const val DEFAULT_SERIAL = "0123456789abcdef"

        /** Characters allowed in a serial: safe for adb, Build.SERIAL and property values (max 91 bytes). */
        const val SERIAL_MAX = 32
        /** Suggested ro.hardware values for the settings menu (any other value can be typed). */
        val HARDWARE_PRESETS = listOf("goldfish", "ranchu", "qcom")

        /** Characters allowed in ro.hardware: it names HAL libraries (gralloc.<hw>.so, init.<hw>.rc), so no path or shell characters. */
        const val HARDWARE_MAX = 32
        fun cleanHardware(v: String): String =
            v.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' || it == '.' }.take(HARDWARE_MAX)

        const val DEFAULT_KERNEL = "3.0.31-aemu"
        const val DEFAULT_BASEBAND = "AEmulator"
        const val KERNEL_MAX = 120
        const val BASEBAND_MAX = 64

        /** Single line of printable ASCII (no newline, so /proc/version and property values stay well-formed). */
        private fun cleanLine(v: String, max: Int): String = v.filter { it in ' '..'~' }.take(max)
        fun cleanKernel(v: String): String = cleanLine(v, KERNEL_MAX)
        /** Longest linux_banner line read from a kernel image (the importer's own scan stops at the same length). */
        const val BANNER_MAX = 512
        /** A whole linux_banner read from a kernel: printable ASCII, but not cut at [KERNEL_MAX], which would drop the build date at its end. */
        fun cleanBanner(v: String): String = cleanLine(v, BANNER_MAX)
        const val TOKEN_MAX = 32
        /** Release/user/host pieces: letters, digits and . _ - + only, so the line stays parseable ("Linux version REL (user@host)") */
        fun cleanToken(v: String, max: Int): String = v.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' || it == '+' }.take(max)
        /** Free text piece of /proc/version: printable ASCII without parentheses or # */
        /** Compiler text keeps its own parentheses (real banners: "gcc version 4.4.3 (GCC) "), only # is dropped */
        fun cleanCompiler(v: String): String = cleanLine(v, 80).filter { it != '#' }.trim()
        fun cleanText(v: String, max: Int): String = cleanLine(v, max).filter { it != '(' && it != ')' && it != '#' }.trim()
        fun cleanBaseband(v: String): String = cleanLine(v, BASEBAND_MAX)

        /** Release part for uname -r: the word after "Linux version " if a full line was typed, else the first token. */
        fun kernelRelease(v: String): String {
            val t = cleanKernel(v).trim().removePrefix("Linux version").trim()
            return t.substringBefore(' ').ifEmpty { DEFAULT_KERNEL }
        }

        private fun isFullLine(v: String) = cleanKernel(v).trim().startsWith("Linux version")
        /** True when any kernel setting differs from the default, so uname -r / /proc/version must be composed */
        fun kernelCustom(s: VmSettings): Boolean = listOf(s.kernel, s.kernelBranch, s.kernelCommit, s.kernelUser, s.kernelHost,
            s.kernelCompiler, s.kernelBuild, s.kernelFlags, s.kernelDate).any { it.isNotBlank() }
        /** uname -r: base release + "-branch" + "-g<commit>" (a full typed line wins and is used unchanged) */
        fun kernelRelease(s: VmSettings): String {
            val base = kernelRelease(s.kernel)
            if (isFullLine(s.kernel)) return base
            return base + (if (s.kernelBranch.isNotBlank()) "-" + s.kernelBranch.trim('-') else "") +
                (if (s.kernelCommit.isNotBlank()) "-g" + s.kernelCommit.removePrefix("g") else "")
        }
        /** Whole /proc/version from all kernel settings */
        fun procVersion(s: VmSettings): String {
            if (isFullLine(s.kernel)) return procVersion(s.kernel)
            val user = s.kernelUser.ifBlank { "aemu" }; val host = s.kernelHost.ifBlank { "aemu" }
            val cc = s.kernelCompiler.ifBlank { "gcc version 4.6" }
            val build = s.kernelBuild.ifBlank { "1" }
            val flags = s.kernelFlags.ifBlank { "SMP PREEMPT" }
            val date = s.kernelDate.ifBlank { "Thu Jan 1 00:00:00 UTC 2026" }
            return "Linux version ${kernelRelease(s)} ($user@$host) ($cc) #$build ${flags.trim()} $date".replace("  ", " ") + "\n"
        }

        /** Full /proc/version text for the setting. */
        fun procVersion(v: String): String {
            val t = cleanKernel(v).trim()
            return if (t.startsWith("Linux version")) t + "\n"
            else "Linux version ${kernelRelease(t).ifEmpty { DEFAULT_KERNEL }} (aemu@aemu) (gcc version 4.6) #1 SMP PREEMPT Thu Jan 1 00:00:00 UTC 2026\n"
        }

        /** The service names of a "mediaserver, zygote" list (commas, blanks or both); only characters valid in a file name are kept. */
        fun straceNames(v: String): Set<String> =
            v.split(',', ' ', '\t', ';', '\n').map { n -> n.filter { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' || it == '.' } }
                .filter { it.isNotEmpty() && it != "." && it != ".." }.toCollection(LinkedHashSet())

        fun cleanSerial(v: String): String = v.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.take(SERIAL_MAX)

        /** Random 16-char lowercase hex serial, like most real devices report. */
        fun randomSerial(): String = (1..16).joinToString("") { "0123456789abcdef"[(0..15).random()].toString() }

        /** Random Luhn-valid IMEI with a test TAC prefix. */
        fun randomImei(): String {
            val d = IntArray(14) { if (it < 2) intArrayOf(3, 5)[it] else (0..9).random() }
            var sum = 0
            for (i in 0 until 14) { var x = d[i]; if (i % 2 == 1) { x *= 2; if (x > 9) x -= 9 }; sum += x }
            return d.joinToString("") + ((10 - sum % 10) % 10)
        }

        fun fromJson(o: JSONObject?): VmSettings {
            if (o == null) return VmSettings()
            val d = VmSettings()
            return VmSettings(
                width = o.optInt("width", d.width),
                height = o.optInt("height", d.height),
                density = o.optInt("density", d.density),
                gpu = o.optBoolean("gpu", d.gpu),
                hwui = o.optBoolean("hwui", d.hwui),
                jit = o.optBoolean("jit", d.jit),
                fullDexopt = o.optBoolean("fullDexopt", d.fullDexopt),
                fbHz = o.optInt("fbHz", d.fbHz),
                touchHz = o.optInt("touchHz", d.touchHz),
                netProxy = o.optBoolean("netProxy", d.netProxy),
                lowRam = o.optBoolean("lowRam", d.lowRam),
                showFrame = o.optBoolean("showFrame", d.showFrame),
                showNavBar = o.optBoolean("showNavBar", d.showNavBar),
                hideMenuButton = o.optBoolean("hideMenuButton", d.hideMenuButton),
                navButtons = NavControls.encode(NavControls.parse(o.optString("navButtons", d.navButtons))),
                trackball = o.optBoolean("trackball", false),
                trackballDpad = o.optBoolean("trackballDpad", false),
                trackballStepDp = o.optInt("trackballStepDp", 18).coerceIn(4, 48),
                keepScreenOn = o.optBoolean("keepScreenOn", d.keepScreenOn),
                showLed = o.optBoolean("showLed", d.showLed),
                vibration = o.optBoolean("vibration", d.vibration),
                hostBattery = o.optBoolean("hostBattery", d.hostBattery),
                camera = o.optBoolean("camera", d.camera),
                microphone = o.optBoolean("microphone", d.microphone),
                bluetooth = o.optBoolean("bluetooth", d.bluetooth),
                motionSensors = o.optBoolean("motionSensors", false),
                disableSensors = if (o.has("disableSensors")) o.optBoolean("disableSensors") else null,
                gyroLock = GyroLock.sanitize(o.optInt("gyroLock", GyroLock.DISABLED)),
                hostResolution = o.optBoolean("hostResolution", false),
                skipSetupWizard = o.optBoolean("skipSetupWizard", d.skipSetupWizard),
                disableGoogleApps = o.optBoolean("disableGoogleApps", false),
                mtMode = o.optInt("mtMode", d.mtMode),
                legacyEngine = o.optBoolean("legacyEngine", false),
                ramMb = o.optInt("ramMb", 0),
                radio = o.optBoolean("radio", true),
                native32 = o.optBoolean("native32", false),
                translate32 = o.optBoolean("translate32", true),
                imei = o.optString("imei", ""),
                serial = cleanSerial(o.optString("serial", "")),
                kernel = cleanKernel(o.optString("kernel", "")),
                kernelBranch = cleanToken(o.optString("kernelBranch", ""), TOKEN_MAX),
                kernelCommit = cleanToken(o.optString("kernelCommit", ""), 40),
                kernelUser = cleanToken(o.optString("kernelUser", ""), TOKEN_MAX),
                kernelHost = cleanToken(o.optString("kernelHost", ""), 64),
                kernelCompiler = cleanCompiler(o.optString("kernelCompiler", "")),
                kernelBuild = o.optString("kernelBuild", "").filter { it in '0'..'9' }.take(6),
                kernelFlags = cleanText(o.optString("kernelFlags", ""), 40),
                kernelDate = cleanText(o.optString("kernelDate", ""), 40),
                baseband = cleanBaseband(o.optString("baseband", "")),
                networkType = NetworkType.sanitize(o.optInt("networkType", d.networkType)),
                qemuArgs = o.optString("qemuArgs", ""),
                straceServices = o.optString("straceServices", ""),
                hardware = cleanHardware(o.optString("hardware", "")),
                wifi = o.optBoolean("wifi", d.wifi),
                wifiSsid = WifiEmu.cleanSsid(o.optString("wifiSsid", d.wifiSsid)).ifEmpty { WifiEmu.DEFAULT_SSID },
                wifiPassword = WifiEmu.cleanPassword(o.optString("wifiPassword", "")),
                sim = SimCard.fromJson(o.optJSONObject("sim")),
                sim2 = o.optJSONObject("sim2")?.let { SimCard.fromJson(it) } ?: d.sim2,
                dualSim = o.optBoolean("dualSim", false),
                wifiSignal = o.optInt("wifiSignal", d.wifiSignal).coerceIn(1, WifiEmu.MAX_BARS),
                wifiNeighbors = o.optBoolean("wifiNeighbors", d.wifiNeighbors),
                thermalPassthrough = o.optBoolean("thermalPassthrough", d.thermalPassthrough),
                thermalTemp = if (o.has("thermalTemp") && !o.isNull("thermalTemp")) o.optInt("thermalTemp").coerceIn(ThermalSysfs.MIN_MILLI_C, ThermalSysfs.MAX_MILLI_C) else null,
            )
        }
    }
}

/** Служба гостя, которую мы запускаем вместо init. */
data class GuestService(
    val name: String,
    val argv: List<String>,
    /** сокеты init: имя → права (например "zygote" → "0666") */
    val sockets: Map<String, String> = emptyMap(),
    val uid: Int = 0,
    val gid: Int = 0,
    /** ждать появления этого сокета перед следующей службой */
    val waitSocket: String? = null,
    val delayMs: Long = 0,
    val restart: Boolean = false,
    /** вендорский демон: если упадёт — не страшно */
    val optional: Boolean = false,
    /** not started at boot: the guest asks for it with ctl.start (a service outside init's started classes) */
    val onDemand: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("optional", optional).put("onDemand", onDemand)
        .put("name", name)
        .put("argv", JSONArray(argv))
        .put("sockets", JSONObject(sockets as Map<*, *>))
        .put("uid", uid).put("gid", gid)
        .put("waitSocket", waitSocket ?: JSONObject.NULL)
        .put("delayMs", delayMs).put("restart", restart)

    companion object {
        fun fromJson(o: JSONObject): GuestService {
            val argv = o.getJSONArray("argv").let { a -> List(a.length()) { a.getString(it) } }
            val socks = o.optJSONObject("sockets")?.let { s -> s.keys().asSequence().associateWith { s.getString(it) } } ?: emptyMap()
            return GuestService(
                name = o.getString("name"),
                argv = argv,
                sockets = socks,
                uid = o.optInt("uid", 0),
                gid = o.optInt("gid", 0),
                waitSocket = if (o.isNull("waitSocket")) null else o.optString("waitSocket"),
                delayMs = o.optLong("delayMs", 0),
                restart = o.optBoolean("restart", false),
                optional = o.optBoolean("optional", false),
                onDemand = o.optBoolean("onDemand", false),
            )
        }
    }
}

/**
 * Импортированный образ прошивки. Всё, что нужно для запуска, выводится из самой прошивки
 * при импорте (build.prop, init*.rc, состав /system) — никаких захардкоженных профилей устройств.
 */
data class GuestImage(
    val id: String,
    val name: String,
    val release: String,
    val api: Int,
    val brand: String,
    val model: String,
    val skin: String,
    val engine: Engine,
    val abi: String = "armeabi-v7a",
    /**
     * Instruction set of the firmware's own programs (see [GuestArch]); detected at import from the ELF files, so it
     * also holds for a firmware whose ro.product.cpu.abi says something else. Picks the translator and the
     * architecture's guest-side helper libraries; the engine stays the universal one.
     */
    val arch: GuestArch = GuestArch.ARM,
    val bootclasspath: String,
    val exports: Map<String, String>,
    val dirs: List<String>,
    val services: List<GuestService>,
    val sdcardPath: String,
    /** тома из storage_list.xml, которыми управляет vold (не эмулируемые): о них должна знать заглушка vold */
    val volumes: List<String> = emptyList(),
    val settings: VmSettings,
    val createdAt: Long,
    val sizeBytes: Long = 0,
    val sourceName: String = "",
    val runtime: String = "dalvik",
    val warnings: List<String> = emptyList(),
    val lastBootMs: Long = 0,
    val bootCount: Int = 0,
    /** версия анализатора, которым построен профиль; устаревший профиль пересчитывается перед запуском */
    val profileVersion: Int = 0,
    /** container: id of the image whose /system this one shares (its own /data, card and settings) */
    val baseId: String = "",
    /** ro.build.id of the firmware (e.g. LCA43), shown next to the Android version */
    val buildId: String = "",
    /** Export-author note: consumed only after a reported, stable normal boot. */
    val oneTimeNote: String = "",
) {
    val displayVersion: String get() = "Android $release (API $api)" + if (buildId.isNotBlank()) " (Build $buildId)" else ""

    /** True when the guest runs without any sensors (the user's choice, else automatic; see [SensorHalApi.sensorsOff]). */
    val sensorsOff: Boolean get() = sensorsOff(settings)
    fun sensorsOff(s: VmSettings): Boolean = SensorHalApi.sensorsOff(s.disableSensors, api, skin, brand, model)

    /** Движок с учётом выбора пользователя. */
    fun effective(binder64: Boolean = false): GuestImage = copy(engine = when {
        // x86-32: only the universal engine has a translator for it (libqemu_i386.so); no native arm64 engine, no 2.x fallback
        arch == GuestArch.X86 -> Engine.KK
        abi == "arm64-v8a" -> Engine.N64
        settings.legacyEngine && api < 14 -> Engine.GB
        // a 32-bit firmware of 8.0+, or an older one built for the 64-bit binder structs (Android Things 1.0, ...): the
        // universal engine's binder daemon speaks protocol 7 only
        settings.translate32 && (api >= 26 || binder64) && abi.startsWith("armeabi") -> Engine.N64
        else -> Engine.KK
    })

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("release", release).put("api", api)
        .put("brand", brand).put("model", model).put("skin", skin)
        .put("engine", engine.id).put("abi", abi).put("arch", arch.id)
        .put("bootclasspath", bootclasspath)
        .put("exports", JSONObject(exports as Map<*, *>))
        .put("dirs", JSONArray(dirs))
        .put("services", JSONArray(services.map { it.toJson() }))
        .put("sdcardPath", sdcardPath)
        .put("volumes", JSONArray(volumes))
        .put("settings", settings.toJson())
        .put("createdAt", createdAt).put("sizeBytes", sizeBytes)
        .put("sourceName", sourceName).put("runtime", runtime)
        .put("warnings", JSONArray(warnings))
        .put("lastBootMs", lastBootMs).put("bootCount", bootCount)
        .put("profileVersion", profileVersion)
        .put("baseId", baseId)
        .put("buildId", buildId)
        .put("oneTimeNote", OneTimeVmNote.normalize(oneTimeNote))

    companion object {
        fun fromJson(o: JSONObject): GuestImage {
            val ex = o.optJSONObject("exports")
            val exports = ex?.keys()?.asSequence()?.associateWith { ex.getString(it) } ?: emptyMap()
            val dirs = o.optJSONArray("dirs")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            val svcs = o.optJSONArray("services")?.let { a -> List(a.length()) { GuestService.fromJson(a.getJSONObject(it)) } } ?: emptyList()
            val warn = o.optJSONArray("warnings")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            return GuestImage(
                id = o.getString("id"),
                name = o.optString("name", "Android"),
                release = o.optString("release", "?"),
                api = o.optInt("api", 19),
                brand = o.optString("brand", ""),
                model = o.optString("model", ""),
                skin = o.optString("skin", "AOSP"),
                engine = Engine.byId(o.optString("engine")),
                abi = o.optString("abi", "armeabi-v7a"),
                arch = GuestArch.byId(o.optString("arch", "arm")),
                bootclasspath = o.optString("bootclasspath", ""),
                exports = exports,
                dirs = dirs,
                services = svcs,
                sdcardPath = o.optString("sdcardPath", "/mnt/sdcard"),
                volumes = o.optJSONArray("volumes")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
                settings = VmSettings.fromJson(o.optJSONObject("settings")),
                createdAt = o.optLong("createdAt", 0),
                sizeBytes = o.optLong("sizeBytes", 0),
                sourceName = o.optString("sourceName", ""),
                runtime = o.optString("runtime", "dalvik"),
                warnings = warn,
                lastBootMs = o.optLong("lastBootMs", 0),
                bootCount = o.optInt("bootCount", 0),
                profileVersion = o.optInt("profileVersion", 0),
                baseId = o.optString("baseId", ""),
                buildId = o.optString("buildId", ""),
                oneTimeNote = OneTimeVmNote.normalize(o.optString("oneTimeNote", "")),
            )
        }
    }
}
