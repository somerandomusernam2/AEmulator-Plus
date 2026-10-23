/* Modified for AEmulator Sunset, 2026-10-03: source-backed host motion HAL.
 * Modified for AEmulator Super Plus, 2026-10-11: camcorder profiles for video.
 * GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.system.Os
import android.system.OsConstants
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Подгонка дерева прошивки под запуск без ядра и железа.
 *  - [sanitize] выполняется один раз после импорта: убирает вендорские драйверы, которые полезли бы
 *    в несуществующее железо (GPU, композитор, OMX, датчики, камера…). Файлы не удаляются —
 *    переносятся в /system/.aemu-parked, чтобы можно было вернуть.
 *  - [fixup] выполняется перед каждым запуском: кладёт файлы движка, создаёт узлы /dev и /sys,
 *    каталоги, которые создаёт init, восстанавливает владельцев и т.п.
 */
class TreeFixer(
    private val ctx: Context,
    private val paths: VmPaths,
    private val img: GuestImage,
    private val log: (String) -> Unit,
) {
    private val root get() = paths.root
    private val engine get() = img.engine
    private val kernel get() = Kernel64.forApi(img.api)

    // ---------------------------------------------------------------- однократно после импорта

    /** android.hardware.graphics.allocator@2.0-impl.so / android.hardware.graphics.composer@2.1-impl.so (HIDL passthrough impls) */
    private fun isGraphicsHidlImpl(name: String) =
        Regex("^android\\.hardware\\.graphics\\.(allocator|composer)@[0-9.]+-impl\\.so$").matches(name)

    /**
     * Any HIDL passthrough implementation of the firmware (android.hardware.audio@4.0-impl.so, ...keymaster@3.0-impl.so,
     * ...light@2.0-impl.so, ...camera.provider@2.4-impl.so and so on). They are not hardware modules: the HAL service
     * dlopen()s them by name, and without one it dies with "Could not get passthrough implementation".
     */
    private fun isHidlImpl(name: String) =
        Regex("^android\\.(hardware|hidl)\\.[A-Za-z0-9_.]+@[0-9.]+-impl[A-Za-z0-9_.-]*\\.so$").matches(name)

    fun sanitize() {
        val parked = File(root, "system/.aemu-parked").apply { mkdirs() }
        var n = 0
        fun park(f: File) {
            if (!f.exists() && !isLink(f)) return
            val rel = f.relativeTo(root).path.replace('/', '#')
            val dst = File(parked, rel)
            dst.delete()
            if (f.renameTo(dst)) n++
        }
        // 1. HAL-модули: оставляем только нейтральные *.default.so и безвредные классы
        val keepClasses = setOf("audio_policy", "local_time", "power", "keystore", "consumerir", "vibrator", "memtrack")
        val parkClasses = setOf(
            "gralloc", "hwcomposer", "copybit", "overlay", "sensors", "camera", "gps", "nfc", "nfc_nci",
            "lights", "audio", "fm", "bluetooth", "hdmi", "tv", "display", "ir", "wlan", "gpu", "fingerprint",
        )
        for (dir in listOf("system/lib/hw", "vendor/lib/hw", "system/vendor/lib/hw")) {
            File(root, dir).listFiles()?.forEach { f ->
                val name = f.name
                if (!name.endsWith(".so")) return@forEach
                val cls = name.substringBefore('.')
                val variant = name.removeSuffix(".so").substringAfter('.', "")
                val isDefault = variant == "default" || variant == "goldfish"
                when {
                    // HIDL passthrough implementations (android.hidl.memory@1.0-impl.so: 32-bit audioserver maps its
                    // effect buffers through it) are not hardware modules
                    name.contains('@') && dir == "system/lib/hw" -> {}
                    // A firmware with no 64-bit side: the engine's arm64 graphics impls cannot replace these, so the
                    // firmware's own 32-bit allocator/composer impls are the only ones there will ever be
                    // (the same goes for every other impl: audio, keymaster, gatekeeper, light, power, memtrack, thermal,
                    // sensors, camera provider... the engine ships its arm64 impls only, and a 32-bit HAL service
                    // cannot load those; parked, the services found no impl at all and system_server/audioserver/
                    // cameraserver/keystore waited for them forever)
                    isPure32Tree(root) && (isGraphicsHidlImpl(name) || isHidlImpl(name)) -> {}
                    // родной gralloc.default прошивки работает с fb0, который эмулирует qemu, — оставляем
                    cls == "gralloc" && isDefault -> {}
                    // звук даёт движок
                    cls == "gralloc" || (cls == "audio" && variant == "primary.default") -> park(f)
                    cls == "audio" && (variant.startsWith("a2dp") || variant.startsWith("usb") || variant.startsWith("r_submix")) -> {}
                    // Android Things: the board's pin map (peripheral_io.<board>.so) is plain user-space code that tells the
                    // peripheral manager daemon which GPIO/I2C/SPI/UART names exist; set aside, every app that opens
                    // a peripheral dies with "Unknown I/O name"
                    cls == "peripheral_io" -> {}
                    cls in keepClasses && isDefault -> {}
                    cls == "audio_policy" -> if (!isDefault) park(f)
                    cls in parkClasses -> park(f)
                    !isDefault && cls !in keepClasses -> park(f)
                    !isDefault -> park(f)
                }
            }
        }
        // 2. вендорские драйверы OpenGL: их место займёт GL-мост
        for (dir in listOf("system/lib/egl", "vendor/lib/egl", "system/vendor/lib/egl")) {
            File(root, dir).listFiles()?.forEach { f ->
                val nm = f.name
                val vendorGl = (nm.startsWith("libEGL_") || nm.startsWith("libGLESv1_CM_") || nm.startsWith("libGLESv2_") ||
                    nm.startsWith("libGLESv3_") || (nm.startsWith("libGLES_") && nm != "libGLES_android.so" && nm != X86SoftwareGles.ALIAS) ||
                    nm.startsWith("libq3dtools") || nm.startsWith("eglsubAndroid") || nm.startsWith("libRBEGL") || nm.startsWith("libRBGLES"))
                if (vendorGl) park(f)
            }
        }
        // 3. аппаратные кодеки OMX переполняют стек OMXMaster и роняют mediaserver
        for (rel in listOf("system/lib/libstagefrighthw.so", "vendor/lib/libstagefrighthw.so", "system/vendor/lib/libstagefrighthw.so")) park(File(root, rel))
        // 3b. Qualcomm libavenhancements.so (/vendor/lib): libmedia/libstagefright/libmedia_jni dlopen() it by name, so zygote and every
        // app_process helper load it, and it drags in libmediaplayerservice, libstagefright_wfd, libExtendedExtractor and libdashplayer
        // (strace: openat chain in zygote). DashPlayer's weak symbols then override the real ones and every ART process dies with
        // SIGSEGV at a heap address, lr = libdashplayer+0xc003 (Razer Forge, API 23). The callers treat a failed dlopen as "no
        // extensions" and use the stock code paths; mediaserver does not need it either.
        for (rel in listOf("system/lib/libavenhancements.so", "vendor/lib/libavenhancements.so", "system/vendor/lib/libavenhancements.so")) park(File(root, rel))
        // 4. заслонки ядра: /dev/binder как каталог, группы планировщика
        File(root, "dev/binder").takeIf { it.isDirectory }?.let { wipe(it) }
        File(root, "dev/cpuctl").takeIf { it.exists() }?.let { wipe(it) }
        log("firmware prep: vendor modules set aside: $n")
    }

    // ---------------------------------------------------------------- перед каждым запуском

    /**
     * Precompiled code of 5.0+ system apps and of the framework (the odex files in oat/arm and framework/arm) is the only copy: the jars
     * and apks are stripped. installd's dexopt on a fresh /data replaced or unlinked those files inside /system
     * (a real /system is read-only), and the next boot died in system_server with "No original dex files found".
     * Taking the write bit away makes such writes fail harmlessly; ImageStore.wipe gives it back before deleting.
     */
    private fun protectOat() {
        val sys = File(root, "system")
        var n = 0
        fun lock(d: File) {
            d.listFiles()?.forEach { f -> if (f.isFile) { if (f.canWrite()) { f.setWritable(false, false); n++ } } else if (f.isDirectory) lock(f) }
            d.setWritable(false, false)
        }
        File(sys, "framework").listFiles()?.filter { it.isDirectory && (it.name == "oat" || it.name == "arm" || it.name == "arm64" || it.name == "x86") }?.forEach { lock(it) }
        for (top in listOf("app", "priv-app")) File(sys, top).listFiles()?.forEach { app ->
            File(app, "oat").takeIf { it.isDirectory }?.let { lock(it) }
        }
        if (n > 0) log("oat files write-protected: $n")
    }

    fun fixup(owners: Boolean = true) {
        if (owners) seedOwners()
        installEngineFiles()
        installSensorHal()
        File(root, "system/framework/aemu-setup.jar").let { dst ->
            dst.parentFile?.mkdirs()
            eaOpen("engines/common/aemu-setup.jar").use { src -> dst.outputStream().use { src.copyTo(it) } }
            dst.setReadable(true, false)
        }
        installCameraHal()
        protectOat()
        fixThemeXml()
        parkWhetstone()
        parkPlayReady()
        disableTegraRmInit()
        parkQcomServices()
        swapMtkAudioHal()
        // glibc (Google TV) firmware: the engine's GL shims are bionic libraries (NEEDED libdl.so / libc.so) and cannot
        // be loaded by a glibc userland ("load_driver(libGLES_android.so): libdl.so: cannot open shared object file",
        // then no EGL display, SurfaceFlinger cannot pick a config and system_server dies with SIGSEGV).
        // They also replace the firmware's own libGLES_android.so, so use the firmware's software renderer instead.
        // ⚠ the GPU bridge/shim of the KK engine exists for ARM guests only until the x86 build of it does (GuestVm.settings turns the
        // GPU off for such a guest): with the stored setting this would put ARM libraries into an x86 tree (libEGL: "unexpected e_machine: 40").
        val gpuForArch = img.arch == GuestArch.ARM || EngineAssets.exists(ctx, "engines/${Engine.KK.id}${img.arch.assetSuffix}/libGLES.so")
        eglConfig(img.settings.gpu && gpuForArch && !GuestPreload.isGlibcFirmware(root))
        FbDirectPatch.apply(root, img.api, log) // Android 1.x: no page flipping -> render straight into fb0 (otherwise the screen stays black)
        makeDataDirs()
        fixDataRootAccess()
        runCatching {
            if (GuestRootAliases.ensureEtc(root)) log("init: restored /etc -> /system/etc alias")
        }.onFailure { log("init: could not prepare /etc alias: ${it.message}") }
        miuiSdkFiles()
        makeUserZeroLink()
        makeDevNodes()
        rootLinks()
        fixApex()
        guestOverlay()
        bpfMapNotes()
        aconfigStorage()
        kernelPolicyUnread()
        makeSysNodes()
        ThermalSysfs.apply(root, img.settings, log)
        makeProcMounts()
        vendorAudioPolicy()
        resolvConf()
        caCerts()
        audioPolicy()
        audioOpenSlot()
        seedSamsungCsc()
        qemuAudioPolicy()
        qtaguid()
        if (engine == Engine.N64 && kernel.webView64) for (dir in listOf("product/app", "system/app", "system/product/app", "system_ext/app"))
            File(root, dir).listFiles().orEmpty().filter { it.isDirectory && it.name.contains("webview", true) }
                .flatMap { it.listFiles().orEmpty().filter { f -> f.isFile && f.name.endsWith(".apk") } }
                .forEach { WebView64.prefer64(it, log) }
        vendorChecks()
        flattenedLinks()
        shellLink()
        pldHints()
        berlinOsalGuard()
        hdmiRxGuard()
        appProcessLink()
        scriptShebangs()
        samsungEfs()
        selinuxOff()
        surfaceGuard()
        surfaceFlingerGuard()
        executableCodeCache()
        glassGestureGuard()
        glassBluetoothPark()
        parkAutoSmsApps()
        disableStallingPackages()
        glassLocationProvider()
        seedDefaultIme()
        lockWearRotation()
        keyLayout()
        for (n in LOGS) File(root, "dev/log/$n").let { if (!it.isFile) { it.parentFile?.mkdirs(); it.createNewFile() } }
        makeFb()
    }

    /**
     * Скрипты am/pm/input/monkey… в старых прошивках начинаются с «# Script…» без #!. Ядро на такой
     * execve отвечает ENOEXEC и mksh запускает файл сам, а qemu вместо этого падает «Exec format error».
     */
    /** Only app_process32 shipped: add the app_process link the pm/am/input scripts and our helper services expect. */
    private fun appProcessLink() {
        if (AppProcess.ensureLink(root)) log("app_process: only app_process32 present, linked system/bin/app_process -> app_process32")
    }

    private fun scriptShebangs() {
        val ap = AppProcess.path(root)
        var fixed = 0
        val failed = ArrayList<String>()
        // the commands (system/bin, system/xbin) plus the *.sh helpers the framework runs itself (WifiHW runs
        // system/etc/wifi/wifi_device_detect.sh: "Error while loading ...: Exec format error" in logcat)
        val files = ArrayList<File>()
        for (dir in listOf("system/bin", "system/xbin")) File(root, dir).listFiles()?.let { files += it }
        File(root, "system/etc").walkTopDown().maxDepth(3).filter { it.isFile && it.name.endsWith(".sh") }.forEach { files += it }
        run {
            for (f in files) {
                if (!f.isFile || f.length() > 64_000 || java.nio.file.Files.isSymbolicLink(f.toPath())) continue
                val head = runCatching { f.inputStream().use { s -> ByteArray(2).also { s.read(it) } } }.getOrNull() ?: continue
                // am/pm/input/monkey…: "exec app_process" is looked up through PATH, and the default PATH of 2.2–4.x/Google TV
                // starts with /sbin:/vendor/bin:/system/sbin, so run from a plain shell (adb shell) it fails where the same
                // command with /system/bin first works. These scripts all set base=/system: call it by its full path.
                if (head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()) {
                    runCatching {
                        val t = f.readText(Charsets.ISO_8859_1)
                        val u = AppProcess.fixExec(t, ap)
                        if (u != t) { f.writeText(u, Charsets.ISO_8859_1); fixed++ }
                    }
                    continue
                }
                if (head[0] != '#'.code.toByte()) continue
                // a read-only or odd-owner file made writeBytes() fail silently and left "pm: Exec format error" behind:
                // retry through a temp file that replaces the original, keeping the mode bits
                val ok = runCatching {
                    val body = ("#!/system/bin/sh\n" + AppProcess.fixExec(String(f.readBytes(), Charsets.ISO_8859_1), ap)).toByteArray(Charsets.ISO_8859_1)
                    val exec = f.canExecute()
                    try { f.writeBytes(body) } catch (_: Exception) {
                        f.setWritable(true, true)
                        val tmp = File(f.parentFile, f.name + ".aemu-tmp")
                        tmp.writeBytes(body)
                        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) { tmp.delete(); error("cannot replace") } }
                    }
                    f.setReadable(true, false)
                    f.setExecutable(true, false)
                    if (exec) f.setExecutable(true, false)
                }.isSuccess
                if (ok) fixed++ else failed += f.name
            }
        }
        if (fixed > 0) log("scripts: #! added to $fixed shell script(s) and \"exec app_process\" made absolute (system/bin, system/xbin, system/etc/*.sh)")
        if (failed.isNotEmpty()) log("scripts: could not add #! to ${failed.joinToString()}")
    }

    /**
     * Samsung: без /efs/FactoryApp/factorymode = ON прошивка считает себя заводской и поверх всего
     * рисует таблицу «PDA / CSC / RF Cal Date…», а часть служб работает в режиме заводского теста.
     */
    private fun samsungEfs() {
        val efs = File(root, "efs")
        if (!efs.isDirectory) return
        val dir = File(efs, "FactoryApp").apply { mkdirs() }
        for (n in listOf("factorymode", "keystr")) {
            val f = File(dir, n)
            if (runCatching { f.readText().trim() }.getOrNull() != "ON") runCatching { f.writeText("ON") }
        }
    }

    private fun qcomAudioFlinger(): Boolean = runCatching {
        val af = File(root, "system/lib/libaudioflinger.so")
        af.isFile && String(af.readBytes(), Charsets.ISO_8859_1).contains("setFmVolume")
    }.getOrDefault(false)

    /**
     * Samsung 4.0 AudioFlinger (AudioPolicyService::getParamFromPolicy) calls an extra slot (+108) of the audio_policy
     * struct that only Samsung's own policy library (audio_policy.<board>.so) fills in. The generic
     * audio_policy.default.so leaves it null, so the first getParameters("situationVolume") from the framework killed
     * mediaserver with PC=0. When the firmware ships that library (sanitize parked it) it is installed as the
     * default policy module; the generic one is kept aside.
     */
    private fun vendorAudioPolicy() {
        if (img.api >= 16) return
        runCatching {
            val af = File(root, "system/lib/libaudioflinger.so")
            val marker = "getParamFromPolicy".toByteArray(Charsets.ISO_8859_1)
            fun has(b: ByteArray): Boolean {
                outer@ for (i in 0..b.size - marker.size) {
                    for (k in marker.indices) if (b[i + k] != marker[k]) continue@outer
                    return true
                }
                return false
            }
            if (!af.isFile || !has(af.readBytes())) return
            val parkedDir = File(root, "system/.aemu-parked")
            val hw = File(root, "system/lib/hw")
            val candidates = (parkedDir.listFiles()?.filter { it.name.startsWith("system#lib#hw#audio_policy.") && it.name.endsWith(".so") && !it.name.endsWith("#audio_policy.default.so") } ?: emptyList()) +
                (hw.listFiles()?.filter { it.name.startsWith("audio_policy.") && it.name.endsWith(".so") && it.name != "audio_policy.default.so" } ?: emptyList())
            val vendor = candidates.firstOrNull { has(it.readBytes()) } ?: return
            val dst = File(hw, "audio_policy.default.so")
            val bytes = vendor.readBytes()
            if (dst.isFile && dst.length() == bytes.size.toLong() && dst.readBytes().contentEquals(bytes)) return
            val keep = File(parkedDir, "audio_policy.default.so.generic")
            if (!keep.isFile && dst.isFile) { parkedDir.mkdirs(); dst.copyTo(keep) }
            dst.writeBytes(bytes)
            dst.setReadable(true, false)
            dst.setExecutable(true, false)
            log("audio: Samsung audio policy (${vendor.name.substringAfterLast('#')}) installed as the default policy")
        }.onFailure { log("audio: Samsung audio policy install failed: ${it.message}") }
    }

    /**
     * Samsung firmware reads /system/csc/customer.xml (carrier customisation), normally supplied by the CSC partition,
     * which the importer skips. Without it Messaging (com.android.mms.csc.CscParser.search, "File didn't exist") dies with
     * a NullPointerException in Application.onCreate, instantly on every launch. An empty but well-formed file is enough
     * for the parser to find "no carrier overrides".
     */
    private fun seedSamsungCsc() {
        runCatching {
            val samsung = img.skin.contains("TouchWiz", true) || img.skin.contains("Samsung", true)
            val f = File(root, "system/csc/customer.xml")
            if (!samsung || f.exists() || !File(root, "system/app").isDirectory) return
            f.parentFile?.mkdirs()
            f.writeText("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<CustomerData>\n  <GeneralInfo>\n  </GeneralInfo>\n  <Settings>\n  </Settings>\n</CustomerData>\n")
            f.setReadable(true, false)
            f.parentFile?.setReadable(true, false); f.parentFile?.setExecutable(true, false)
            log("csc: wrote an empty /system/csc/customer.xml (Samsung Messaging needs the file)")
        }.onFailure { log("csc: cannot write customer.xml: ${it.message}") }
    }

    /**
     * Samsung KitKat AudioFlinger calls open_output_stream at another offset of audio_hw_device than AOSP does (108):
     * on SM-C115M 4.4.2 the call landed on a stub of the stand HAL, "Failed to open primary output" followed and every
     * audio policy (Samsung's, then the AOSP stand-in) died in mediaserver with no output to work with. The offset is
     * read from the firmware's own libaudioflinger and handed to the HAL in /aemu.audio.slot; the HAL moves the end of
     * its device structure accordingly. No file means the AOSP layout.
     */
    private fun audioOpenSlot() {
        val f = File(root, "aemu.audio.slot")
        runCatching {
            val samsung = img.skin.contains("TouchWiz", true) || img.skin.contains("Samsung", true)
            val af = File(root, "system/lib/libaudioflinger.so")
            val slot = if (img.api >= 16 && samsung && af.isFile) AudioHalAbi.openOutputSlot(af.readBytes()) else null
            if (slot != null && slot > 108 && slot <= 172 && slot % 4 == 0) {
                if (!f.isFile || f.readText().trim() != slot.toString()) f.writeText("$slot\n")
                f.setReadable(true, false)
                log("audio: AudioFlinger opens the output at device slot $slot (AOSP 108), HAL layout shifted")
            } else {
                if (f.exists()) f.delete()
                if (img.api >= 16 && samsung) log("audio: AudioFlinger open_output_stream slot: ${slot ?: "unknown"}")
            }
        }.onFailure { log("audio: cannot write aemu.audio.slot: ${it.message}") }
    }

    /**
     * The mediaserver runs with ro.kernel.qemu=1 (emulator audio output). Samsung 2.x AudioPolicyService() skips
     * createAudioPolicyManager() in that mode and then calls setSystemProperty() on the NULL policy manager:
     * mediaserver died with SIGSEGV at the first start, every start (see ElfPatch.policyBranchIn).
     * The firmware's libaudioflinger is patched to always create its policy; the stock file is kept aside.
     */
    private fun qemuAudioPolicy() = runCatching {
        val lib = File(root, "system/lib/libaudioflinger.so")
        if (!lib.isFile) return@runCatching
        val keep = File(root, "system/.aemu-parked/system#lib#libaudioflinger.so")
        val d = lib.readBytes()
        if (ElfPatch.policyBranchOffsets(d).isEmpty()) { // not this firmware, or already patched
            log("audio: libaudioflinger policy branch not found (already patched or different build)")
            return@runCatching
        }
        if (!keep.exists()) { keep.parentFile?.mkdirs(); lib.copyTo(keep) }
        if (ElfPatch.policyManagerAlways(lib) > 0) log("audio: AudioPolicyService creates its policy in emulator mode (libaudioflinger patched)")
        else log("audio: libaudioflinger policy patch was not written")
    }.onFailure { log("audio: libaudioflinger policy patch failed: ${it.message}") }

    private fun samsungDeviceSlots(): Boolean = runCatching {
        val af = File(root, "system/lib/libaudioflinger.so")
        af.isFile && AudioHalAbi.openOutputSlot(af.readBytes()) == 116
    }.getOrDefault(false)

    private fun openOutputSlotIs(slot: Int): Boolean = runCatching {
        val af = File(root, "system/lib/libaudioflinger.so")
        af.isFile && AudioHalAbi.openOutputSlot(af.readBytes()) == slot
    }.getOrDefault(false)

    private fun directTrackAudio(): Boolean = img.api in 19..20 && runCatching {
        AudioHalAbi.usesDirectTrackTail(File(root, "system/lib/libnbaio.so").readBytes())
    }.getOrDefault(false)

    /**
     * MediaTek 4.x: libaudioflinger loads the primary HAL from /system/lib/libaudio.primary.default.so
     * (AudioMTKHardware, which needs the MTK sound driver — silent here). Park it as libaudio.mtk.so and put
     * our HAL there; it forwards the DcRemove filter AudioFlinger links from that library to the original.
     */
    private fun swapMtkAudioHal() {
        swapMtkAudioHalImpl()
        redirectMtkAudioDeps()
    }

    /**
     * Other MediaTek libraries link symbols that only MTK's own HAL has (libmtkplayer.so needs
     * AudioResourceManager::getInstance(); libmtkplayer is pulled in by libmediaplayerservice and, through
     * libandroid_servers, by system_server — both died at link time). The stand-in exports just the DcRemove filter,
     * so those libraries are pointed at the parked original instead; the path has exactly the length of the old
     * name and is the one the stand-in itself uses, so the library is loaded once per process.
     * libaudioflinger is left alone: it is the one that must see the stand-in.
     */
    private fun redirectMtkAudioDeps() = runCatching {
        if (!File(root, "system/lib/libaudio.mtk.so").isFile) return@runCatching
        var n = 0
        for (dir in listOf("system/lib", "system/lib/hw", "vendor/lib", "vendor/lib/hw")) {
            File(root, dir).listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".so") && f.name != "libaudioflinger.so" && f.name != "libaudio.mtk.so" &&
                    f.name != "libaudio.primary.default.so" && !isLink(f))
                    n += ElfPatch.redirectNeeded(f, "libaudio.primary.default.so", "/system/lib/libaudio.mtk.so")
            }
        }
        if (n > 0) log("audio: $n MediaTek librar${if (n == 1) "y" else "ies"} linked to the original HAL (libaudio.mtk.so)")
    }.onFailure { log("audio: HAL link redirect failed: ${it.message}") }

    private fun swapMtkAudioHalImpl() {
        if (engine != Engine.KK || img.api < 17) return
        val lib = File(root, "system/lib/libaudio.primary.default.so")
        val orig = File(root, "system/lib/libaudio.mtk.so")
        if (!orig.isFile) {
            if (!lib.isFile || !isMtkAudio(root)) return
            if (!lib.renameTo(orig)) return
        }
        val asset = "engines/kk/audio.primary.mtk.so"
        runCatching {
            // AudioFlinger asks libhardware for module class "libaudio"/"primary" here, and libhardware rejects a module
            // whose id differs ("load: id=libaudio != hmi->id=audio"). The same asset serves the AOSP-style loader
            // (hw/audio.primary.default.so, id "audio"), so only this copy gets the id changed.
            val raw = eaOpen(asset).use { it.readBytes() }
            val hal = HalModuleId.retarget(raw, MTK_HAL_MODULE_ID)
            if (hal == null) log("audio: cannot set the HAL module id to $MTK_HAL_MODULE_ID, AudioFlinger will refuse it")
            val bytes = hal ?: raw
            if (lib.isFile && lib.length() == bytes.size.toLong() && lib.readBytes().contentEquals(bytes)) return
            lib.outputStream().use { it.write(bytes) }
            lib.setReadable(true, false)
            log("audio: MediaTek HAL replaced with the emulator HAL (module id $MTK_HAL_MODULE_ID)")
        }.onFailure { log("audio: MediaTek HAL swap failed: ${it.message}") }
    }

    /**
     * MIUI 8 reads system/media/theme/theme_compatibility.xml while zygote preloads classes. Some builds ship it with
     * nested comments ("<!-- <!-- ...") that Android's strict XML parser rejects; ThemeCompatibility and MiuiResources
     * then fail to initialise in every process and the system never finishes booting (black screen). Double hyphens
     * inside comments are separated so the file parses.
     */
    private fun fixThemeXml() {
        val f = File(root, "system/media/theme/theme_compatibility.xml")
        if (!f.isFile || f.length() > 4_000_000) return
        runCatching {
            val s = f.readText()
            if (!s.contains("<!-- <!--")) return
            val out = StringBuilder(s.length)
            var i = 0
            while (true) {
                val a = s.indexOf("<!--", i)
                if (a < 0) { out.append(s, i, s.length); break }
                out.append(s, i, a + 4)
                val e = s.indexOf("-->", a + 4)
                if (e < 0) { out.append(s, a + 4, s.length); break }
                out.append(s.substring(a + 4, e).replace("--", "- -")).append("-->")
                i = e + 3
            }
            f.writeText(out.toString())
            log("theme_compatibility.xml: nested comments fixed")
        }
    }

    /**
     * MIUI 8: ActivityManagerService calls MIUI's Whetstone service (an app process) while holding its own lock, and that
     * process in turn waits for the same lock. The watchdog then restarts system_server over and over and the boot never
     * finishes. Whetstone only does background-power bookkeeping, so its package is set aside.
     */
    private fun parkWhetstone() {
        val apk = File(root, "system/app/Whetstone.apk")
        if (!apk.isFile || !File(root, "system/app/miuisystem.apk").isFile) return
        runCatching {
            val dir = File(root, "system/.aemu-parked").apply { mkdirs() }
            if (apk.renameTo(File(dir, "Whetstone.apk"))) {
                File(root, "data/dalvik-cache/system@app@Whetstone.apk@classes.dex").delete()
                log("MIUI: Whetstone package set aside (deadlock with the activity manager)")
            }
        }
    }

    /**
     * Samsung/TouchWiz ICS: drmserver loads every plugin from /system/lib/drm while it handles the first DRM
     * request after boot. libplayreadyplugin.so (Microsoft PlayReady) dereferences a NULL pointer there (SIGSEGV at
     * 0x4, exit 139) because its device-key / platform setup cannot succeed on the emulator. drmserver then dies,
     * 'drm.drmManager' disappears from servicemanager and every DrmManagerClient call blocks waiting for it.
     * The plugin only serves PlayReady-protected media, which can never play here, so it is set aside; drmserver
     * simply registers the remaining plugins (OMA/forward-lock). The stock file is kept for a later delta OTA.
     */
    private fun parkPlayReady() = runCatching {
        val plugin = File(root, "system/lib/drm/libplayreadyplugin.so")
        if (!plugin.isFile && !isLink(plugin)) return@runCatching
        val parked = File(root, "system/.aemu-parked/system#lib#drm#libplayreadyplugin.so")
        parked.parentFile?.mkdirs()
        val moved = if (isLink(plugin) || parked.exists()) plugin.delete() else plugin.renameTo(parked)
        if (moved) log("DRM: PlayReady plugin set aside (drmserver crashed in it)")
    }.onFailure { log("DRM: could not set aside the PlayReady plugin: ${it.message}") }

    /**
     * NVIDIA Tegra firmware (Google Jamboard, Shield, Nexus 9, ...): the vendor libjpeg depends on libnvtvmr.so, whose
     * constructor calls into libnvrm.so as soon as the library is loaded. libnvrm reads the Tegra chip id from sysfs/fuse
     * nodes a phone does not have ("NvRmPrivGetChipPlatform: Could not read platform information", "No matching chip
     * spec found for chip Id=685"), is left without a chip spec and dereferences it (SIGSEGV at 0x38 inside libnvrm).
     * The constructor runs in every process that loads libjpeg, so zygote, bootanimation and the 32-bit daemons
     * (mediaserver, cameraserver, app_process32; under qemu the guest fault is re-raised as a host SIGSEGV) all died
     * at start. The constructor only sets up the hardware video/JPEG path, which cannot work here anyway, so its
     * DT_INIT / DT_INIT_ARRAY are switched off; the stock file is kept in system/.aemu-parked as *.stock.
     */
    private fun disableTegraRmInit() = runCatching {
        var patched = 0
        for (dir in listOf("vendor/lib64", "vendor/lib", "system/vendor/lib64", "system/vendor/lib", "system/lib64", "system/lib")) {
            val f = File(root, "$dir/libnvtvmr.so")
            if (!f.isFile || File(f.parentFile, "libnvrm.so").let { !it.isFile && !isLink(it) }) continue
            val real = f.canonicalFile
            if (!real.path.startsWith(root.canonicalPath + "/")) continue
            val stock = File(root, "system/.aemu-parked/" + real.relativeTo(root.canonicalFile).path.replace('/', '#') + ".stock")
            if (!stock.exists()) { stock.parentFile?.mkdirs(); runCatching { real.copyTo(stock) } }
            if (ElfPatch.disableInit(real) == 1) { patched++; log("NVIDIA: ${real.relativeTo(root.canonicalFile).path}: constructor switched off (libnvrm has no Tegra chip here)") }
        }
    }.onFailure { log("NVIDIA: could not switch off the libnvtvmr constructor: ${it.message}") }

    /**
     * qemu names the input device "dhd-touch"; with no layout of that name the guest falls back to the firmware's
     * Generic.kl / qwerty.kl, where vendors map the codes differently (4.x Generic.kl: 102 = MOVE_HOME, 580 often
     * unmapped), so Home and Recents did nothing on many firmwares. A layout under the device's own name is looked up
     * first by every version from 2.3 on and gives the navigation bar the same codes everywhere.
     */
    private fun keyLayout() {
        val f = File(root, "system/usr/keylayout/dhd-touch.kl")
        val vf = File(root, "vendor/usr/keylayout/dhd-touch.kl")
        val text = buildString {
            append("# AEmulator: navigation bar and hardware keys of the emulator\n")
            append("key ${InputService.KEY_HOME} HOME WAKE\n")
            append("key 172 HOME WAKE\n")
            append("key ${InputService.KEY_BACK} BACK WAKE_DROPPED\n")
            append("key ${InputService.KEY_MENU} MENU WAKE_DROPPED\n")
            append("key ${InputService.KEY_SEARCH} SEARCH WAKE_DROPPED\n")
            append("key ${InputService.KEY_POWER} POWER WAKE\n")
            append("key ${InputService.KEY_VOLUMEDOWN} VOLUME_DOWN WAKE\n")
            append("key ${InputService.KEY_VOLUMEUP} VOLUME_UP WAKE\n")
            append("key 113 VOLUME_MUTE\n")
            append("key 212 CAMERA\n")
            append("key 103 DPAD_UP\nkey 108 DPAD_DOWN\nkey 105 DPAD_LEFT\nkey 106 DPAD_RIGHT\nkey 232 DPAD_CENTER\n")
            // APP_SWITCH exists from 3.0; older layouts reject unknown labels and drop the whole file
            if (img.api >= 11) {
                append("key ${InputService.KEY_APPSELECT} APP_SWITCH\n")
                append("key 254 APP_SWITCH\n")
                append("key 187 APP_SWITCH\n")
            }
        }.let {
            // 5.0 dropped the WAKE flags from the layout syntax: an unknown flag makes the guest reject the whole file
            if (img.api >= 21) it.replace(" WAKE_DROPPED", "").replace(" WAKE", "") else it
        }
        val targets = listOf(
            f, vf,
            File(root, "system/usr/keylayout/Vendor_18d1_Product_0001.kl"),
            File(root, "system/usr/keylayout/AEmulator_Touchscreen.kl")
        )
        for (target in targets) {
            if (target == vf && !File(root, "vendor/usr/keylayout").isDirectory) continue
            if (runCatching { target.readText() }.getOrNull() != text) {
                runCatching { target.parentFile?.mkdirs(); target.writeText(text); target.setReadable(true, false) }
                    .onFailure { log("key layout (${target.name}): ${it.message}") }
            }
        }
        // In Generic.kl and qwerty.kl: ensure HOME, BACK, MENU, and APP_SWITCH are properly mapped
        for (dir in listOf(File(root, "system/usr/keylayout"), File(root, "vendor/usr/keylayout"))) {
            if (!dir.isDirectory) continue
            for (name in listOf("Generic.kl", "qwerty.kl")) {
                val kl = File(dir, name)
                if (kl.isFile) runCatching {
                    var s = kl.readText()
                    var changed = false
                    if (s.contains("key 102   MOVE_HOME")) {
                        s = s.replace("key 102   MOVE_HOME", "key 102   HOME")
                        changed = true
                    } else if (s.contains("key 102 MOVE_HOME")) {
                        s = s.replace("key 102 MOVE_HOME", "key 102 HOME")
                        changed = true
                    }
                    if (!s.contains("APP_SWITCH") && img.api >= 11) {
                        s += "\n# AEmulator navigation recents keys\nkey 580 APP_SWITCH\nkey 187 APP_SWITCH\nkey 254 APP_SWITCH\n"
                        changed = true
                    }
                    if (changed) kl.writeText(s)
                }
            }
        }
    }

    /**
     * Qualcomm builds: QTI permissions, DPM, the location stack, the RIL message tunnel and Dirac audio bind to modem,
     * DSP and data-path daemons that do not exist here; their apps crash in a loop and drag system_server with them.
     */
    private fun parkQcomServices() {
        val parked = File(root, "system/.aemu-parked")
        var n = 0
        // 4.x keeps apps as bare app/Name.apk (+ .odex) instead of app/Name/
        for (rel in QCOM_PARK.flatMap { if (it.endsWith(".xml")) listOf(it) else listOf(it, "$it.apk", "$it.odex") }) {
            val f = File(root, rel)
            if (!f.exists() && !isLink(f)) continue
            parked.mkdirs()
            val dst = File(parked, rel.replace('/', '#'))
            if (dst.exists()) ImageStore.wipe(dst)
            if (f.renameTo(dst)) n++
        }
        if (n > 0) log("Qualcomm: vendor services set aside: $n")
    }

    /** Native arm64 engine: its HALs and libraries are arm64 files from assets/engines/n64-<kernel> (each kernel has its own copy), named "a#b#c" for a/b/c. */
    /** the firmware's libgui checks queued buffers for the handle of Samsung's Exynos gralloc */
    /** some 64-bit HAL of this class is in place (the board's own ones are set aside after import) */
    private fun hasHal(cls: String) = listOf("system/lib64/hw", "vendor/lib64/hw", "system/vendor/lib64/hw")
        .any { d -> File(root, d).list()?.any { it.startsWith("$cls.") && it.endsWith(".so") } == true }

    private fun contains(f: File, text: String): Boolean = runCatching {
        f.isFile && String(f.readBytes(), Charsets.ISO_8859_1).contains(text)
    }.getOrDefault(false)

    private fun exynosLibgui(lib: String): Boolean = runCatching {
        String(File(root, "system/$lib/libgui.so").readBytes(), Charsets.ISO_8859_1).contains("invalid gralloc handle (at %p)")
    }.getOrDefault(false)

    private fun installNative64() {
        // engines/n64-<kernel>: AOSP's own SurfaceFlinger for that release. The vendors' ones (MIUI's blur, Qualcomm's
        // ExSurfaceFlinger) need the GPU's GLES 2 and show no windows on the software renderer. For a Treble release
        // (8.0+) it is the vendor side: the graphics and keymaster HAL services over the plain framebuffer, and the
        // manifest that lists exactly them (a HAL that is listed but not running is waited for without end).
        // engines/n64-gl: SwiftShader, the same for every release.
        var copied = 0
        // the GPU bridge for 32-bit programs (native/glroute): libEGL takes it before the software renderer, and it
        // sends the toolkit's drawing on to that renderer; without the GPU option it is not there at all
        kernel.gpuBridge32?.let { name ->
            val on = img.settings.gpu && File(root, "system/bin/app_process32").isFile
            for ((asset, to) in listOf((if (kernel.gpuBridge32Open) "libGLES_aemu.so" else "libGLES_route.so") to name, "libGLES_split.so" to "libGLES_split.so", "libGLES.so" to "libGLES_bridge.so")) {
                val f = File(root, "system/lib/egl/$to")
                if (on) runCatching {
                    val len = eaSize("engines/kk/$asset")
                    if (isLink(f) || !f.isFile || f.length() != len) {
                        f.delete()
                        eaOpen("engines/kk/$asset").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                        f.setReadable(true, false); f.setExecutable(true, false); copied++
                    }
                } else if (to != name || !isLink(f)) f.delete()
            }
        }
        // 10+ over the 8.1 vendor side: the codec service there is 8.1's, and its minijail takes one @include in a
        // policy; the system image's policy has a second one (code coverage, empty in a release build), and the
        // service gives up on it. That line goes.
        if (kernel.oneIncludePolicy) File(root, "system/etc/seccomp_policy/mediacodec.policy").let { f ->
            runCatching {
                val lines = f.readLines()
                // (11 also names a constant that minijail's table of 8.1 does not have)
                val kept = lines.filterNot { it.startsWith("@include") && "code_coverage" in it }.map { it.replace("MREMAP_MAYMOVE", "1") }
                if (kept != lines) f.writeText(kept.joinToString("\n", postfix = "\n"))
            }
        }
        // A system image with no vendor side of its own (a GSI): libselinux of 8.x opens the platform's and the
        // vendor's context files together once the platform's one is there, and hwservicemanager aborts without
        // the second. The vendor adds no rules here.
        if (kernel.treble) for (n in listOf("hwservice", "file", "property", "seapp", "service")) {
            val vendor = File(root, "vendor/etc/selinux/nonplat_${n}_contexts")
            if (File(root, "system/etc/selinux/plat_${n}_contexts").isFile && !vendor.exists() &&
                !File(root, "vendor/etc/selinux/vendor_${n}_contexts").exists()) runCatching {
                vendor.parentFile?.mkdirs()
                vendor.writeText("")
                vendor.setReadable(true, false)
            }
        }
        // (a firmware with its own vendor side: vendor/build.prop is its file, the engine ships none)
        val ownVendor = kernel.treble && File(root, "vendor/build.prop").isFile
        val layers = ArrayList<String>(kernel.assets)
        if (ownVendor || kernel.treble && kernel.ownVendorUnderGsi) layers += kernel.ownVendorAssets
        if (kernel.treble && !ownVendor) layers += kernel.gsiAssets
        val files = LinkedHashMap<String, String>()         // file name to the asset it comes from: later layers win
        // A layer may be split into arm32/ and arm64/ (see EngineAssets): the files of both go in, and where both have
        // the same path the one for the firmware's architecture is taken (a 32-bit-only firmware: arm32, any other: arm64)
        val guest64 = !isPure32Tree(root)
        for (dir in layers) for ((n, asset) in runCatching { EngineAssets.layerFiles(ctx, dir, guest64) }.getOrNull().orEmpty()) files[n] = asset
        // ...and what such a firmware keeps its own of comes back from where an earlier start put it aside
        if (ownVendor) for (n in kernel.firmwareOwn) {
            val parked = File(root, "system/.aemu-parked/$n")
            val own = File(root, n.replace('#', '/'))
            if (parked.isFile) { own.delete(); if (parked.renameTo(own)) log("${own.name}: the firmware's own file is back") }
            if (own.isFile) files.remove(n)
        }
        if (ownVendor) for (n in kernel.firmwareAside) {
            val own = File(root, n.replace('#', '/'))
            val parked = File(root, "system/.aemu-parked/$n")
            if (own.isFile && !parked.exists()) { parked.parentFile?.mkdirs(); if (own.renameTo(parked)) log("${own.name}: the firmware's own file is set aside") }
        }
        // A firmware with no 64-bit side keeps ITS OWN HAL manifests. The engine's manifest and the setting aside of the
        // fragments exist so that the engine's HAL services (all 64-bit, none of which is installed on such a firmware)
        // take the place of the board's. Here the board's services are the ones that run, and the service manager refuses
        // to register an AIDL service that no manifest declares (addService fails with EX_ILLEGAL_ARGUMENT, "status=-3":
        // boot, drm, gatekeeper, cas, camera provider, memtrack, usb... all abort), and SurfaceFlinger finds no declared
        // graphics mapper/allocator ("Failed to load matching allocator for mapper version 2").
        val keepFirmwareVintf = ownVendor && isPure32Tree(root)
        if (keepFirmwareVintf) {
            for (parked in File(root, "system/.aemu-parked").listFiles().orEmpty()) {
                if (!parked.isFile) continue
                val rel = parked.name.replace('#', '/')
                val vintf = rel == "vendor/etc/vintf/manifest.xml" || rel.startsWith("vendor/etc/vintf/manifest/") ||
                    rel.startsWith("odm/etc/vintf/manifest/")
                if (!vintf) continue
                val own = File(root, rel)
                if (rel == "vendor/etc/vintf/manifest.xml") own.delete() else if (own.exists()) continue
                own.parentFile?.mkdirs()
                if (parked.renameTo(own)) { own.setReadable(true, false); log("${own.name}: the firmware's own HAL manifest is back (32-bit firmware)") }
            }
            files.remove("vendor#etc#vintf#manifest.xml")
        }
        if (ownVendor && !keepFirmwareVintf && kernel.manifestFragmentsAside && "vendor#etc#vintf#manifest.xml" in files) {
            for (f in File(root, "vendor/etc/vintf/manifest").listFiles().orEmpty()) {
                val parked = File(root, "system/.aemu-parked/vendor#etc#vintf#manifest#${f.name}")
                if (kernel.manifestFragmentsKept.any { it in f.name }) continue
                if (f.isFile && !parked.exists()) { parked.parentFile?.mkdirs(); if (f.renameTo(parked)) log("${f.name}: the firmware's own file is set aside") }
            }
        }
        // the odm partition adds fragments of its own for the board's HALs, none of which run here; a declared HAL
        // that never registers is waited for, and one unreadable fragment voids the whole device manifest (ColorOS
        // links network_manifest.xml into /proc/oplusManifest, which only its kernel has)
        if (ownVendor && !keepFirmwareVintf && kernel.odmManifestFragmentsAside && "vendor#etc#vintf#manifest.xml" in files) {
            for (f in File(root, "odm/etc/vintf/manifest").listFiles().orEmpty()) {
                val parked = File(root, "system/.aemu-parked/odm#etc#vintf#manifest#${f.name}")
                val link = java.nio.file.Files.isSymbolicLink(f.toPath())
                if ((f.isFile || link) && !parked.exists()) { parked.parentFile?.mkdirs(); if (f.renameTo(parked)) log("odm ${f.name}: the firmware's own file is set aside") }
            }
        }
        // (a fragment this kernel keeps, set aside by an earlier build of the app, goes back)
        if (ownVendor) for (parked in File(root, "system/.aemu-parked").listFiles().orEmpty()) {
            val name = parked.name.removePrefix("vendor#etc#vintf#manifest#")
            if (name != parked.name && kernel.manifestFragmentsKept.any { it in name }) {
                val own = File(root, "vendor/etc/vintf/manifest/$name")
                if (!own.exists() && parked.renameTo(own)) log("$name: the firmware's own file is back")
            }
        }
        // a file this kernel no longer ships (the kernels part ways: 7.x keeps the firmware's own SurfaceFlinger)
        // goes back from where it was kept aside
        val shipped = files.keys
        val anyKernel = Kernel64.entries.flatMap { k -> listOf("engines/n64-${k.tag}", k.ownVendorAssets).flatMap { runCatching { EngineAssets.layerNames(ctx, it) }.getOrNull().orEmpty() } }.toSet()
        for (parked in File(root, "system/.aemu-parked").listFiles().orEmpty()) {
            if (!parked.isFile || parked.name !in anyKernel || parked.name in shipped) continue
            val dst = File(root, parked.name.replace('#', '/'))
            dst.delete()
            if (parked.renameTo(dst)) log("${dst.name}: the firmware's own file is back")
        }
        // Samsung's libgui (see the gralloc rule below) is not the library AOSP's SurfaceFlinger was built against:
        // the consumer side of the buffer queue is laid out differently and the first queued frame crashes in it.
        // Such firmware keeps its own SurfaceFlinger.
        // (or one that crashed on the engine's build at the first frame: GuestVm.useFirmwareSurfaceFlinger leaves a marker)
        val ownSf = (kernel.exynosHandles && exynosLibgui("lib64")) || File(root, "system/.aemu-parked/firmware-sf").isFile
        // The sound trigger service of the engine only hosts the firmware's own implementation; where there is none (a
        // generic system image) it exits at once, and its manifest entry makes system_server wait for it forever.
        val soundTriggerImpl = File(root, "vendor/lib64/hw").list().orEmpty().any { it.startsWith("android.hardware.soundtrigger@") && it.endsWith("-impl.so") }
        // A firmware with no 64-bit side (Wear OS 9, armeabi-v7a only): the engine's 64-bit programs and libraries have no
        // 64-bit linker namespace or system libraries to run against, and a 64-bit file laid over a 32-bit program of
        // the firmware (SurfaceFlinger, a HAL service) would replace something that works with something that cannot
        // start. Only the engine's 32-bit files (and the ones that are no ELF: jars, manifests, policies) go in.
        val pure32 = isPure32Tree(root)
        if (pure32) log("32-bit firmware: the engine's 64-bit files are not installed")
        // ...so its own 32-bit graphics HAL impls, which the import set aside on the assumption that the engine's arm64
        // ones take over, have to be back: without them the allocator and composer services find no passthrough
        // implementation and SurfaceFlinger aborts ("EGLContext creation failed")
        if (pure32) for (parked in File(root, "system/.aemu-parked").listFiles().orEmpty()) {
            val rel = parked.name.replace('#', '/')
            val dir = rel.substringBeforeLast('/')
            val base = rel.substringAfterLast('/')
            if (!parked.isFile || dir !in setOf("vendor/lib/hw", "system/vendor/lib/hw") || !(isGraphicsHidlImpl(base) || isHidlImpl(base))) continue
            // (what the kernel sets aside on purpose, Qualcomm's secure-world impls, stays aside)
            if (parked.name in kernel.firmwareAside) continue
            val own = File(root, rel)
            if (own.exists()) continue
            own.parentFile?.mkdirs()
            if (parked.renameTo(own)) { own.setReadable(true, false); log("${own.name}: the firmware's own 32-bit file is back") }
        }
        // The 32-bit installd of such a firmware gets ENOSYS from the phone's kernel for every extended-attribute call and
        // gives up on every app's data directory (installd: "Failed before getfilecon ... Function not implemented"),
        // so SettingsProvider has no database and system_server dies. A preloaded library answers those calls.
        if (pure32) {
            val asset = "engines/common/libaemuxattr.so"
            // /system/lib for programs under /system, /vendor/lib for programs under /vendor: the vendor linker namespace
            // (permitted_paths=/odm:/vendor) refuses a library from /system/lib
            runCatching {
                val size = eaSize(asset)
                for (guestPath in listOf(GuestPreload.XATTR, GuestPreload.XATTR_VENDOR)) {
                    val dst = File(root, guestPath.removePrefix("/"))
                    if (dst.isFile && dst.length() == size && sameContent(asset, dst)) continue
                    dst.parentFile?.mkdirs()
                    if (isLink(dst)) Os.remove(dst.absolutePath)
                    eaOpen(asset).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                    dst.setReadable(true, false); dst.setExecutable(true, false)
                    log("xattr: libaemuxattr.so installed at $guestPath (the kernel refuses extended attributes)")
                }
            }.onFailure { log("xattr: libaemuxattr.so missing from the engine set: ${it.message}") }
        }
        fun is64BitElf(asset: String): Boolean = runCatching {
            eaOpen(asset).use { i ->
                val h = ByteArray(5)
                i.read(h) == 5 && h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() && h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte() && h[4] == 2.toByte()
            }
        }.getOrDefault(false)
        for ((n, asset) in files) {
            if (pure32 && is64BitElf(asset)) continue
            if (!soundTriggerImpl && "aemu-soundtrigger" in n) { File(root, n.replace('#', '/')).delete(); continue }
            if (ownSf && (n == "system#bin#surfaceflinger" || n == "system#lib64#libsurfaceflinger.so")) {
                val parked = File(root, "system/.aemu-parked/$n")
                val own = File(root, n.replace('#', '/'))
                if (parked.isFile) { own.delete(); if (parked.renameTo(own)) log("${own.name}: the firmware's own file is back") }
                continue
            }
            // (the 32-bit driver goes next to the 64-bit one: a Treble firmware's libEGL loads drivers in the vendor
            // namespace of the linker, which does not reach /system/lib/egl - "couldn't find an OpenGL ES
            // implementation" in every 32-bit app of MIUI 10)
            val eglDir32 = kernel.eglDir.replace("/lib64/", "/lib/")
            val dst = File(root, n.replace('#', '/').let {
                when {
                    it.startsWith("system/lib64/egl/") -> kernel.eglDir + it.removePrefix("system/lib64/egl")
                    it.startsWith("system/lib/egl/") -> eglDir32 + it.removePrefix("system/lib/egl")
                    else -> it
                }
            })
            val size = runCatching { eaSize(asset) }.getOrDefault(-1L)
            if (dst.isFile && size >= 0 && dst.length() == size && sameContent(asset, dst)) continue
            runCatching {
                dst.parentFile?.mkdirs()
                if (isLink(dst)) Os.remove(dst.absolutePath)
                else if (dst.isFile) {          // the firmware's own file is kept aside
                    val parked = File(root, "system/.aemu-parked/$n")
                    parked.parentFile?.mkdirs()
                    if (!parked.exists()) dst.renameTo(parked)
                }
                eaOpen(asset).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                dst.setReadable(true, false); dst.setExecutable(true, false)
                copied++
            }.onFailure { log("$n: ${it.message}") }
        }
        // SwiftShader's libEGL finds its GLES libraries by their own names, so the name this release's libEGL asks
        // for is a link to them
        if (kernel.eglName != "swiftshader") listOf(kernel.eglDir, "system/lib/egl").flatMap { File(root, it).listFiles().orEmpty().toList() }.filter { it.name.endsWith("_swiftshader.so") }.forEach { f ->
            val link = File(f.parentFile, f.name.replace("_swiftshader.so", "_${kernel.eglName}.so"))
            if (!isLink(link)) { link.delete(); runCatching { Os.symlink(f.name, link.path) } }
        }
        // 7.x libEGL tries ONE library carrying all three APIs (libGLES_<tag>.so, tag "emulation" when qemu.gles is 1)
        // before the separate libEGL_/libGLESv1_CM_/libGLESv2_ ones. A 32-bit firmware's SurfaceFlinger runs on the
        // engine's 32-bit SwiftShader (the separate libraries linked above): a single-file libGLES_<tag>.so left in
        // system/lib/egl (the firmware's own, or the GPU shim of an earlier run on another engine) is loaded instead,
        // has no GLES 2 window config and SurfaceFlinger aborts with "no suitable EGLConfig found, giving up".
        if (kernel.eglName != "swiftshader" && File(root, "system/lib/egl/libGLESv2_swiftshader.so").isFile) {
            val single = File(root, "system/lib/egl/libGLES_${kernel.eglName}.so")
            if (single.isFile || isLink(single)) {
                val parked = File(root, "system/.aemu-parked/system#lib#egl#${single.name}")
                parked.parentFile?.mkdirs()
                if (isLink(single)) Os.remove(single.absolutePath)
                else { parked.delete(); if (!single.renameTo(parked)) single.delete() }
                log("${single.name}: set aside, the engine's 32-bit SwiftShader takes its place")
                copied++
            }
        }
        // 8.0's 32-bit libEGL looks for the "emulation" driver in system/lib/egl only, where the engine's 32-bit
        // SwiftShader is not (it goes with the other EGL drivers to vendor/lib/egl): links there, under SwiftShader's
        // own names too, by which its libEGL opens its GLES libraries
        // (a firmware with no 64-bit side needs the links under SwiftShader's own names on any release: its libEGL opens
        // libGLESv2_swiftshader.so and libGLESv1_CM_swiftshader.so by the fixed directory /system/lib/egl, and with
        // nothing there it has no GLES library of its own: eglCreateContext returns no context and SurfaceFlinger aborts
        // with "EGLContext creation failed")
        if (kernel.emulationDir != null || pure32) File(root, "vendor/lib/egl").listFiles().orEmpty().filter { it.name.endsWith("_swiftshader.so") }.forEach { f ->
            for (n in listOfNotNull(f.name, f.name.replace("_swiftshader.so", "_emulation.so").takeIf { kernel.emulationDir != null })) {
                val link = File(root, "system/lib/egl/$n")
                link.parentFile?.mkdirs()
                if (!isLink(link)) { link.delete(); runCatching { Os.symlink("../../../vendor/lib/egl/${f.name}", link.path) } }
            }
        }
        // 8.0's libEGL loads the "emulation" driver (the GPU bridge) from system/lib64/egl through the sphal namespace
        // (android_load_sphal_library), which a Treble firmware's ld.config.txt restricts to /vendor: dlopen fails
        // ("is not accessible for the namespace sphal"), no driver is found and SurfaceFlinger aborts ("couldn't find
        // an OpenGL ES implementation"). The folder is added to the namespace's permitted paths.
        if (kernel.emulationDir != null) File(root, "system/etc").listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("ld.config") && it.name.endsWith(".txt") }.forEach { f ->
                runCatching {
                    val text = f.readText()
                    var changed = false
                    val out = text.lines().joinToString("\n") { line ->
                        val m = Regex("^(\\s*namespace\\.sphal\\.permitted\\.paths\\s*[+]?=\\s*)(.*)$").matchEntire(line)
                        if (m == null) return@joinToString line
                        val value = m.groupValues[2].trim()
                        val lib = if (value.contains("\${LIB}")) "\${LIB}" else "lib64"
                        val extra = "/system/$lib/egl"
                        if (value.split(':').contains(extra)) line
                        else { changed = true; m.groupValues[1] + value + ":" + extra }
                    }
                    if (changed) { f.writeText(out); log("linker: ${f.name}: /system/lib64/egl added to the sphal namespace"); copied++ }
                }.onFailure { log("linker: ${f.name} not patched: ${it.message}") }
            }
        // 8.0 has no android.hidl.base@1.0.so (the base interface is inside libhidlbase.so until 8.1), but the engine's
        // own vendor libraries (broadcastradio@1.1, camera.device@1.0-impl) are built against 8.1 and name it as a
        // dependency: the linker fails on it in system_server (libandroid_servers) and in the audio HAL, zygote is
        // killed after it. An empty library of that name satisfies the dependency; the symbols come from libhidlbase.so.
        if (kernel.tag == "26") {
            val stub = File(root, "vendor/lib64/android.hidl.base@1.0.so")
            // an earlier build wrote a stub without section headers, which the linker rejects: replace it
            val staleStub = stub.isFile && stub.length() < 1024 && runCatching {
                stub.inputStream().use { ins -> val h = ByteArray(64); ins.read(h) == 64 && h[60] == 0.toByte() && h[61] == 0.toByte() }
            }.getOrDefault(false)
            if ((!stub.exists() || staleStub) && !File(root, "system/lib64/android.hidl.base@1.0.so").exists()) runCatching {
                stub.parentFile?.mkdirs()
                stub.writeBytes(android.util.Base64.decode(HIDL_BASE_STUB, android.util.Base64.DEFAULT))
                stub.setReadable(true, false); stub.setExecutable(true, false)
                log("hidl: empty android.hidl.base@1.0.so added (8.0 has it inside libhidlbase.so)")
                copied++
            }.onFailure { log("hidl: android.hidl.base@1.0.so not added: ${it.message}") }
        }
        // A vendor libEGL that still reads egl.cfg (MediaTek on 5.x: "0 1 mtk") asks for the driver by the tag written
        // there, whatever is in the folder: the file names SwiftShader instead (a link under the vendor's tag would
        // load a second copy of each library on 5.x, and SwiftShader's EGL and GLES would not see each other).
        // 32-bit programs find no driver of that name and stay on the software renderer of the first line.
        if (kernel.eglCfgTags) {
            val cfg = File(root, "system/lib/egl/egl.cfg")
            val rom = File(root, "system/lib/egl/egl.cfg.rom")
            if (cfg.isFile) runCatching {
                val lines = cfg.readLines()
                val out = lines.map { l ->
                    val t = l.trim().split(Regex("\\s+"))
                    if (t.size == 3 && !l.trim().startsWith("#") && t[2] != "android") "${t[0]} ${t[1]} swiftshader" else l
                }
                if (out != lines) {
                    if (!rom.isFile) cfg.copyTo(rom)
                    cfg.writeText(out.joinToString("\n", postfix = "\n"))
                    log("egl.cfg: the vendor's driver tag replaced with SwiftShader")
                }
            }
        }
        // Samsung's ART empties /data/dalvik-cache at every start when the cache holds no copy of the boot image
        // ("PruneDalvikCache : File Not Exist"): every app is then compiled again on every boot, minutes of
        // "Optimising app N of M". A phone has that copy from relocating the image at first boot; here the image
        // stays where it was built for (-Xnorelocate), so the firmware's own files are linked into the cache.
        if (kernel.bootImageInCache) for ((lib, isa) in listOf("lib64" to "arm64", "lib" to "arm")) runCatching {
            if (!contains(File(root, "system/$lib/libart.so"), "PruneDalvikCache : File Not Exist")) return@runCatching
            for (ext in listOf("art", "oat")) {
                val src = File(root, "system/framework/$isa/boot.$ext")
                val dst = File(root, "data/dalvik-cache/$isa/system@framework@boot.$ext")
                if (!src.isFile || (dst.isFile && dst.length() == src.length())) continue
                dst.parentFile?.mkdirs()
                dst.delete()
                runCatching { Os.link(src.absolutePath, dst.absolutePath) }.onFailure { src.copyTo(dst, overwrite = true) }
                dst.setReadable(true, false)
                log("dalvik-cache/$isa: boot.$ext of the firmware linked in")
            }
        }
        // Samsung's libgui on Exynos (TouchWiz) takes every queued buffer for one of its own gralloc and writes into
        // the handle; with the default gralloc's handle it writes through a null pointer and SurfaceFlinger dies on
        // the first frame. Where libgui has that check, the kernel's gralloc of the same buffers and Samsung's
        // handle layout goes in front of the firmware's default one (kept next to it as libgralloc_rom.so).
        // Qualcomm's SurfaceFlinger reads its gralloc's flags out of every layer's handle: the same way, the kernel's
        // gralloc with Qualcomm's handle layout goes in front where the firmware's SurfaceFlinger is that one.
        val qcomSf = kernel.qcomHandles && runCatching {
            String(File(root, "system/lib64/libsurfaceflinger.so").readBytes(), Charsets.ISO_8859_1).contains("ExSurfaceFlinger")
        }.getOrDefault(false)
        for ((on, name) in listOf(kernel.exynosHandles to "exynos", kernel.qcomHandles to "qcom")) if (on) for (lib in listOf("lib64", "lib")) runCatching {
            // (a Treble release keeps its gralloc on the vendor side)
            val side = if (kernel.treble) "vendor" else "system"
            val wrap = File(root, "$side/$lib/libgralloc_$name.so")
            val hw = File(root, "$side/$lib/hw/gralloc.default.so")
            val rom = File(root, "$side/$lib/libgralloc_rom.so")
            val gui = File(root, "system/$lib/libgui.so")
            if (!wrap.isFile || !hw.isFile || !gui.isFile) return@runCatching
            val inPlace = hw.length() == wrap.length() && hw.readBytes().contentEquals(wrap.readBytes())
            val wanted = if (name == "exynos") exynosLibgui(lib) else qcomSf
            if (wanted && !inPlace) {
                if (!rom.isFile) hw.renameTo(rom)
                wrap.copyTo(hw, overwrite = true)
                hw.setReadable(true, false)
                log("$lib: gralloc with ${if (name == "exynos") "Samsung" else "Qualcomm"}'s handle layout in front of the firmware's")
            } else if (!wanted && inPlace && rom.isFile) {
                hw.delete(); rom.renameTo(hw)
            }
        }
        // Qualcomm's 32-bit libc of 5.1 looks for the main thread's stack in /proc/self/task/<tid>/maps, a file qemu-arm
        // does not describe to its guest: the program reads the translator's own stack, cut to 32 bits, and ART
        // aborts ("stack variable above the stack end") in patchoat and in the 32-bit zygote. /proc/self/maps is the
        // same list, and that one qemu-arm answers itself; the name in the library is shortened to it.
        if (kernel.taskMapsIsSelfMaps) {
            val libc = File(root, "system/lib/libc.so")
            if (libc.isFile) runCatching {
                val d = libc.readBytes()
                val a = "/proc/self/task/%d/maps".toByteArray() + 0
                val b = "/proc/self/maps".toByteArray()
                val at = (0..d.size - a.size).firstOrNull { i -> a.indices.all { d[i + it] == a[it] } }
                if (at != null) {
                    a.indices.forEach { d[at + it] = if (it < b.size) b[it] else 0 }
                    libc.writeBytes(d)
                    log("libc.so (32-bit): the main thread's stack is read from /proc/self/maps")
                }
            }
        }
        // mediaserver of a 6.0 arm64 firmware is a 32-bit program (it runs through qemu-arm): camera and sound
        // reach the phone through the same 32-bit HALs as on the other engines
        if (File(root, "system/bin/mediaserver").let { it.isFile && !isElf64(it) }) {
            // Samsung's audio_format_t is 64 bits wide (5.1: "%lld not supported", 6.0: "%llx not valid"): its AudioFlinger takes a format from the HAL in two registers
            val audioHal = if (kernel.wideAudioFormat && contains(File(root, "system/lib/libaudioflinger.so"), "HAL format %ll"))
                "engines/kk/audio.primary.fmt64.so" else "engines/kk/audio.primary.aosp.so"
            // a kernel may bring its own build of the 32-bit camera HAL (7.1: a newer module API for Camera.openLegacy)
            val cameraHal = kernel.assets.reversed().firstNotNullOfOrNull { EngineAssets.layerAsset(ctx, it, "system#lib#hw#camera.default.so", EngineAssets.ARM32) }
                ?: "engines/common/camera.default.so"
            for ((asset, to) in listOf(cameraHal to "system/lib/hw/camera.default.so",
                audioHal to "system/lib/hw/audio.primary.default.so")) {
                val dst = File(root, to)
                val size = runCatching { eaSize(asset) }.getOrDefault(-1L)
                if (dst.isFile && size >= 0 && dst.length() == size && sameContent(asset, dst)) continue
                runCatching {
                    dst.parentFile?.mkdirs()
                    val parked = File(root, "system/.aemu-parked/${to.replace('/', '#')}")
                    parked.parentFile?.mkdirs()
                    if (dst.isFile && !parked.exists()) dst.renameTo(parked)
                    eaOpen(asset).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                    dst.setReadable(true, false); copied++
                }.onFailure { log("$to: ${it.message}") }
            }
        }
        // the Java stubs (bluetooth manager, network, tool runner) are dex code: the same jar as for 32-bit guests
        runCatching {
            // (read whole: a jar is stored compressed in the package, and such an asset has no length to ask for)
            val jar = File(root, "system/framework/aemu-stubs.jar")
            val bytes = eaOpen("engines/kk/aemu-stubs.jar").use { it.readBytes() }
            if (!jar.isFile || !jar.readBytes().contentEquals(bytes)) {
                jar.writeBytes(bytes)
                jar.setReadable(true, false); copied++
            }
        }.onFailure { log("aemu-stubs.jar: ${it.message}") }
        if (copied > 0) log("engine n64 files updated: $copied")
        // qemu-arm (the firmware's 32-bit programs) asks for the lowest mappable address; the phone hides the real file
        // (a static 32-bit program of the firmware, healthd, is linked at 0x8000: with 65536 qemu-arm refuses to load it
        // - "requires virtual address space that is in use" - so for such a tree the file says 32768, the lowest
        // address a phone's kernel allows by default)
        runCatching {
            File(root, "proc/sys/vm").mkdirs()
            File(root, "proc/sys/vm/mmap_min_addr").writeText(if (linksBelow64K(File(root, "sbin/healthd"))) "32768" else "65536")
        }
        runCatching { File(root, "proc/.one/1").mkdirs(); File(root, "proc/.task1").mkdirs(); File(root, "proc/.task1/none").writeText("") }     // what a 32-bit program sees as /proc/self/task
        // the vendor's HALs drive hardware this phone does not have (ION, MDP, the SoC's audio DSP): with them
        // aside, the loader falls back to the *.default ones
        var parked = 0
        for (dir in listOf("system/lib64/hw", "system/vendor/lib64/hw", "system/lib/hw", "system/vendor/lib/hw")) {
            File(root, dir).listFiles()?.forEach { f ->
                val id = f.name.substringBefore('.')
                // (Qualcomm ships its location HAL as gps.default.so: no GPS at all is better than its modem worker)
                if (!f.isFile || (f.name.endsWith(".default.so") && id != "gps") || id !in VENDOR_HALS) return@forEach
                val dst = File(root, "system/.aemu-parked/${f.relativeTo(root).path.replace('/', '#')}")
                dst.parentFile?.mkdirs()
                if (dst.exists()) dst.delete()
                if (f.renameTo(dst)) parked++
            }
        }
        // Treble: the firmware's /vendor keeps its legacy keystore HAL module (keystore.<board>.so, Qualcomm's one over the
        // secure world) next to the keymaster HIDL impl. The impl asks hw_get_module_by_class("keystore") first and takes the
        // board's module by the board properties; opening it fails here, the impl returns nothing ("Could not get
        // passthrough implementation") and keystore waits for the keymaster service for ever. Without a module the impl
        // makes the software keymaster itself (a keystore.default.so stays, it is the neutral one).
        if (kernel.treble) {
            val seen = ArrayList<String>()
            for (dir in listOf("vendor/lib64/hw", "vendor/lib/hw", "system/lib64/hw", "system/lib/hw")) {
                File(root, dir).listFiles()?.forEach { f ->
                    if (!f.isFile || !(f.name.startsWith("keystore.") || f.name.startsWith("android.hardware.keymaster"))) return@forEach
                    seen += "$dir/${f.name}"
                    // (8.0: the system's keystore.default.so is the one module there is, and the 8.1 impl does not make its
                    // software keymaster as long as it finds a module; it fails on this one without a word)
                    if (!f.name.startsWith("keystore.") || (f.name.endsWith(".default.so") && kernel.tag != "26") || isLink(f)) return@forEach
                    val dst = File(root, "system/.aemu-parked/${f.relativeTo(root).path.replace('/', '#')}")
                    dst.parentFile?.mkdirs()
                    if (dst.exists()) dst.delete()
                    if (f.renameTo(dst)) { parked++; log("${f.name}: the firmware's keystore module is set aside") }
                }
            }
            log("keymaster: hw modules seen: ${seen.ifEmpty { listOf("none") }.joinToString(", ")}")
            // a HIDL passthrough impl that cannot be dlopen()ed is skipped without a word ("Could not get passthrough
            // implementation"): say which of its libraries (all the way down) the linker would not find
            for (rel in listOf("vendor/lib64/hw/android.hardware.keymaster@3.0-impl.so", "system/lib64/hw/keystore.default.so",
                "vendor/bin/hw/android.hardware.keymaster@3.0-service", "vendor/lib64/hw/android.hardware.gatekeeper@1.0-impl.so",
                "vendor/lib64/hw/android.hardware.light@2.0-impl.so")) logMissingLibs(rel)
            logUnresolvedSymbols("vendor/lib64/hw/android.hardware.keymaster@3.0-impl.so", "HIDL_FETCH_IKeymasterDevice")
            logUnresolvedSymbols("system/lib64/hw/keystore.default.so")
        }
        // ...and so does what declares that hardware to the framework: system_server takes "the feature is there but
        // its HAL is not" as fatal for the whole group of services it was starting (8.x: the window manager is in it)
        for (dir in listOf("system/etc/permissions", "vendor/etc/permissions")) for (hw in listOf("consumerir", "fingerprint", "nfc", "nfc.hce", "nfc.hcef")) {
            val f = File(root, "$dir/android.hardware.$hw.xml")
            val dst = File(root, "system/.aemu-parked/${f.relativeTo(root).path.replace('/', '#')}")
            // which ones is the kernel's call: where the firmware's own HAL loads, the declaration has to be there
            val absent = hw in kernel.absentFeatures || (hw == "consumerir" && kernel.irFollowsHal && !hasHal("consumerir"))
            if (!absent) { if (dst.isFile && !f.exists()) dst.renameTo(f); continue }
            if (!f.isFile) continue
            dst.parentFile?.mkdirs()
            if (dst.exists()) dst.delete()
            if (f.renameTo(dst)) parked++
        }
        // Bluetooth is declared in the core hardware list along with everything else, so it cannot be parked as a file:
        // it is declared unavailable instead. 10: with the feature there, system_server turns the adapter on and the
        // Bluetooth app dies in a loop on the missing HCI HAL; without it the engine's own manager stub answers.
        File(root, "system/etc/permissions/aemu-absent.xml").let { f ->
            // HDMI-CEC: a Treble firmware that ships a CEC HAL (the Google TV Streamer's MediaTek one needs
            // libmtkcechal.so, which no tree has: "CANNOT LINK EXECUTABLE") makes system_server start
            // HdmiControlService, which then throws a NullPointerException in onStart (HdmiControlService.java:711,
            // mhl controller) and takes the whole of system_server down. The emulator has no CEC line, so the feature
            // is declared unavailable and the service is not started at all.
            val cecHal = listOf("vendor/bin/hw", "system/vendor/bin/hw", "system/bin/hw").any { d ->
                File(root, d).listFiles()?.any { it.name.startsWith("android.hardware.tv.hdmi.cec") } == true
            }
            // Thread (14+, a service of the tethering/connectivity module): ThreadNetworkService opens an IPv6 multicast
            // socket for its TUN interface and the setsockopt answers ENOPROTOOPT, which throws out of
            // ConnectivityServiceInitializer.onBootPhase(500) and ends system_server. There is no Thread radio, so the
            // feature is declared unavailable and the service is never created.
            val thread = File(root, "apex/com.android.tethering").isDirectory
            val un = kernel.absentFeatures.filter { it.startsWith("bluetooth") } +
                (if (cecHal) listOf("hdmi.cec") else emptyList()) + (if (thread) listOf("thread_network") else emptyList())
            if (un.isEmpty()) f.delete()
            else f.writeText("<permissions>" + un.joinToString("") { "<unavailable-feature name=\"android.hardware.$it\" />" } + "</permissions>")
        }
        // Qualcomm location core loads its modem (QMI) back end by name and crashes inside it without a modem;
        // with the back end gone it keeps its built-in do-nothing one
        for (rel in listOf("system/lib64/libloc_api_v02.so", "system/vendor/lib64/libloc_api_v02.so", "system/lib/libloc_api_v02.so", "system/vendor/lib/libloc_api_v02.so")) {
            val f = File(root, rel)
            if (!f.isFile) continue
            val dst = File(root, "system/.aemu-parked/${rel.replace('/', '#')}")
            dst.parentFile?.mkdirs()
            if (f.renameTo(dst)) parked++
        }
        // the vendor's RenderScript driver goes straight to its GPU; without it libRS computes on the CPU
        for (dir in listOf("system/lib64", "system/vendor/lib64", "system/lib", "system/vendor/lib")) {
            File(root, dir).listFiles()?.filter { it.name.startsWith("libRSDriver_") }?.forEach { f ->
                val dst = File(root, "system/.aemu-parked/${f.relativeTo(root).path.replace('/', '#')}")
                dst.parentFile?.mkdirs()
                if (f.renameTo(dst)) parked++
            }
        }
        // GLES 1/2/3 comes from SwiftShader (engines/n64-<api>): libEGL scans egl/ for lib<API>_*.so, so the GPU
        // vendor's drivers step aside. The 32-bit side keeps the stock software renderer under the exact name libGLES.so.
        if (File(root, kernel.eglLib).isFile) {
            for (dir in listOf("system/lib64/egl", "system/vendor/lib64/egl", "system/vendor/lib/egl").filter { !kernel.treble || it != "system/lib64/egl" }) {
                File(root, dir).listFiles()?.forEach { f ->
                    if (!f.isFile || isLink(f) || kernel.isOwnSuffix(f.name) || f.name == "libGLES_android.so") return@forEach
                    val dst = File(root, "system/.aemu-parked/${f.relativeTo(root).path.replace('/', '#')}")
                    dst.parentFile?.mkdirs()
                    if (dst.exists()) dst.delete()
                    if (f.renameTo(dst)) parked++
                }
            }
            val egl32 = File(root, "system/lib/egl")
            // an image that ran on the 4.4 engine before has our GL shim under the renderer's name
            File(egl32, "libGLES_android.so.sw").let { sw ->
                if (sw.isFile) { File(egl32, "libGLES_android.so").delete(); sw.renameTo(File(egl32, "libGLES_android.so")) }
            }
            val bridge32 = img.settings.gpu && kernel.gpuBridge32 != null
            if (File(egl32, "libGLES_android.so").isFile && !bridge32) {
                for (n in listOf("libGLES_aemu.so", "libGLES_bridge.so", "libEGL_aemu.so", "libGLESv1_CM_aemu.so", "libGLESv2_aemu.so", "libGLES.so"))
                    File(egl32, n).let { if (isLink(it)) Os.remove(it.absolutePath) else it.delete() }
                // a kernel that ships SwiftShader for 32-bit programs too: libEGL takes libGLES.so before anything
                // else in the directory, so the software renderer does not get that name
                if (!File(egl32, "libGLESv2_swiftshader.so").isFile) runCatching { Os.symlink("libGLES_android.so", File(egl32, "libGLES.so").path) }
            }
        }
        if (parked > 0) log("vendor HALs set aside: $parked")
        restorePeripheralIo()
        parkSensorsHalOfPure32Treble()
        disableTeeKeyMint()
        dropHalsWithoutService()
    }

    /**
     * KeyMint services that talk to a TEE: MediaTek's android.hardware.security.keymint@3.0-service.tee
     * opens the OP-TEE/Trustonic client device in main(), finds none (TEEC_InitializeContext 0xFFFF0008, "TEE system is
     * not initialized") and aborts with "Could not initialize TeeKeymaster for KeyMint (0)". The firmware's own VINTF
     * manifest (kept as it is on a 32-bit Treble firmware) still declares IKeyMintDevice/default, so keystore2 takes the
     * "genuine KeyMint service" path, gets NAME_NOT_FOUND, panics ("Cannot connect to Keymint") and the framework never
     * gets a keystore: system_server dies in LockSettingsService, no boot.
     *
     * Without a declared KeyMint keystore2 uses its own software KeyMint instead (the km_compat path that the generic
     * images already boot with). So the TEE service goes aside and the declarations of what it registers (KeyMint, shared
     * secret, secure clock) are taken out of every device manifest. Runs on every start: an engine manifest that comes
     * back overwrites the edit and is edited again. There is no TEE here, so nothing is lost by this.
     */
    private fun disableTeeKeyMint() {
        if (!kernel.treble) return
        val teeService = Regex("^android\\.hardware\\.security\\.keymint@[0-9.]+-service(\\..*)?$")
        var parkedBins = 0
        val seenBins = HashSet<String>()
        for (dir in listOf("vendor/bin/hw", "system/vendor/bin/hw", "odm/bin/hw")) {
            File(root, dir).listFiles()?.forEach { f ->
                if (!teeService.matches(f.name) || (!f.isFile && !isLink(f))) return@forEach
                if (!seenBins.add(runCatching { f.canonicalPath }.getOrDefault(f.path))) return@forEach
                val dst = File(root, "system/.aemu-parked/${f.relativeTo(root).path.replace('/', '#')}")
                dst.parentFile?.mkdirs()
                if (dst.exists() || isLink(dst)) dst.delete()
                if (f.renameTo(dst)) { parkedBins++; log("${f.name}: TEE KeyMint service set aside (no TEE here; keystore2 uses its software KeyMint)") }
            }
        }
        // the declaration in the manifests is what keystore2 reads, so it goes whether the program was set aside now or earlier
        val hal = Regex("[ \\t]*<hal\\b[^>]*>.*?</hal>[ \\t]*\\r?\\n?", RegexOption.DOT_MATCHES_ALL)
        val name = Regex("<name>\\s*([^<\\s]+)\\s*</name>")
        val dropNames = setOf("android.hardware.security.keymint", "android.hardware.security.sharedsecret", "android.hardware.security.secureclock")
        val stillThere = listOf("vendor/bin/hw", "system/vendor/bin/hw", "odm/bin/hw").any { d ->
            File(root, d).list().orEmpty().any { teeService.matches(it) } }
        if (stillThere) return   // (a KeyMint program that could not be moved: leave the manifests as they are)
        val files = listOf("vendor/etc/vintf/manifest.xml", "vendor/manifest.xml", "system/vendor/manifest.xml", "system/vendor/etc/vintf/manifest.xml",
            "odm/etc/vintf/manifest.xml").map { File(root, it) } +
            listOf("vendor/etc/vintf/manifest", "system/vendor/etc/vintf/manifest", "odm/etc/vintf/manifest").flatMap { d ->
                File(root, d).listFiles()?.filter { it.name.endsWith(".xml") }.orEmpty()
            }
        val seen = HashSet<String>()
        var edited = 0
        for (f in files) {
            if (!f.isFile || !seen.add(runCatching { f.canonicalPath }.getOrDefault(f.path))) continue
            runCatching {
                val text = f.readText()
                val removed = ArrayList<String>()
                val out = hal.replace(text) { m ->
                    val n = name.find(m.value)?.groupValues?.get(1)
                    if (n != null && n in dropNames) { removed += n; "" } else m.value
                }
                if (removed.isNotEmpty()) {
                    f.setWritable(true)
                    f.writeText(out)
                    edited++
                    log("${f.relativeTo(root).path}: KeyMint declarations dropped (${removed.distinct().joinToString()})")
                }
            }.onFailure { log("${f.name}: KeyMint declarations not checked: ${it.message}") }
        }
        if (parkedBins > 0 || edited > 0) log("keymint: TEE KeyMint disabled (service $parkedBins, manifests $edited)")
    }

    /**
     * A 32-bit-only Treble firmware (8.x over the board's own vendor side) has no way to serve android.hardware.sensors
     * here: the engine's arm64 sensors HAL cannot be installed next to 32-bit programs, and the firmware's own one is a
     * vendor "multihal" (android.hardware.sensors@1.0-impl-mediatek.so) that finds no sensors module once sanitize() has
     * set the board's sensors.<soc>.so aside, so HIDL_FETCH_ISensors("default") returns null, the service exits with 1
     * every time ("required HAL 0 failed, giving up") and system_server's SensorService waits for the HAL that the
     * manifest promises for ever ("Waited one second for android.hardware.sensors@1.0::ISensors/default"): no boot.
     *
     * The service programs go aside; dropHalsWithoutService() then sees a declared HAL with an rc service whose program is
     * not there and takes the entry out of the manifests, so SensorService finds no HAL and runs without sensors.
     * Runs on every start (the manifest comes back with the engine's files and goes again).
     */
    /**
     * Android Things: peripheral_io.<board>.so (the pin map of the board, read by the firmware's peripheralman) was
     * set aside by sanitize() of an earlier version, so an image prepared then has no pins at all ("Unknown I/O
     * name I2C1" / EINT24 / SPI_MO) - it is put back. The board's I2C buses are /dev/i2c-N to the daemon; the phone
     * has no such devices, so they stand as links to the host's /dev/zero (reads give zeros, writes are
     * accepted) and the ioctls of the i2c-dev interface, which qemu-arm refuses, are answered by libaemuxattr.so.
     */
    private fun restorePeripheralIo() = runCatching {
        val parked = File(root, "system/.aemu-parked")
        var back = 0
        parked.listFiles()?.filter { it.isFile && it.name.contains("#lib#hw#peripheral_io.") && it.name.endsWith(".so") }?.forEach { f ->
            val dst = File(root, f.name.replace('#', '/'))
            if (!dst.exists() && dst.parentFile?.isDirectory == true && f.renameTo(dst)) { dst.setReadable(true, false); back++; log("${dst.name}: the board's pin map is back") }
        }
        val hasMap = listOf("system/lib/hw", "vendor/lib/hw", "system/vendor/lib/hw").any { d ->
            File(root, d).list()?.any { it.startsWith("peripheral_io.") && it.endsWith(".so") } == true }
        if (!hasMap) return@runCatching
        File(root, "dev").mkdirs()
        // Every bus is a plain file of its own inside the image. The earlier links to the host's /dev/zero (and then to
        // distinct /dev/* nodes) all pointed at inodes the whole phone shares: peripheralman claims a bus with a file
        // lock, the host kernel answered "held by PID <someone else>" and Android Things' OemService died with
        // "I2C1 is already in use", after which the IoT launcher restarted that default app for ever. A file in the
        // image has an inode nobody else has. Its ioctls (I2C_SLAVE, I2C_RDWR, SMBUS) give ENOTTY, which
        // libaemuxattr.so answers; read() gives zeros (4 KiB of them), write() is accepted.
        // A bus file that is not exactly 4 KiB is rebuilt: the boot animation drives the LED chip over I2C1 in a loop and
        // its writes land in this plain file, which had grown to 1 GiB (peripheralman then looked wedged and was restarted).
        for (n in 0..4) File(root, "dev/i2c-$n").let { l ->
            if (isLink(l) || !l.isFile || l.length() != 4096L) {
                l.delete()
                runCatching { l.writeBytes(ByteArray(4096)) }
            }
            l.setReadable(true, false); l.setWritable(true, false)
        }
        gpioSysfsStandIns()
    }.onFailure { log("peripheral_io: ${it.message}") }

    /**
     * Android Things' peripheralman drives a GPIO through /sys/class/gpio/gpio<N>/{direction,value,edge,active_low} and
     * answers "<name> driver failure" when gpio<N> is not there ("Failed to open /sys/class/gpio/gpio400"). The pin
     * numbers come from the board's pin map (peripheral_io.<board>.so); the earlier stand-in covered only the pins that
     * had been seen failing one by one (411, 438), so every other pin broke the same way when the next app opened it.
     *
     * On the AT1 speaker image that was the launch loop: OemService's DspFirmwareUpgrader opens EINT24 and then EINT13
     * (= gpio400, no node) and throws, leaving EINT24 open; AT1Board.setup() in the same process then gets "EINT24 is
     * already in use by PID <its own>", throws before it creates its StandbyTimer, and the first audio standby callback
     * dies with an NPE (AT1Board.startStandbyTimer). The IoT launcher restarts the default app, which does it all again.
     *
     * Every number the SoC's GPIO controller can have gets a node of its own (plain files in the image: qemu's -L prefix
     * only redirects a path that exists in the tree; reads give the stored value, writes are accepted). A node already
     * there (made by the engine's own stand-in) is never touched. "export"/"unexport" accept writes too.
     */
    private fun gpioSysfsStandIns() {
        val base = File(root, "sys/class/gpio").apply { mkdirs() }
        var d: File? = base
        while (d != null && d != root && d.path.startsWith(root.path)) { d.setReadable(true, false); d.setExecutable(true, false); d = d.parentFile }
        for (n in listOf("export", "unexport")) File(base, n).let { f ->
            if (!f.isFile) runCatching { f.writeText("") }
            f.setReadable(true, false); f.setWritable(true, false)
        }
        var made = 0
        for (pin in 0 until GPIO_STAND_IN_PINS) {
            val dir = File(base, "gpio$pin")
            if (dir.isDirectory && File(dir, "value").isFile && File(dir, "direction").isFile) continue
            runCatching {
                dir.mkdirs()
                dir.setReadable(true, false); dir.setExecutable(true, false); dir.setWritable(true, false)
                for ((n, v) in listOf("direction" to "in", "value" to "0", "edge" to "none", "active_low" to "0")) {
                    File(dir, n).let { f ->
                        if (!f.isFile) f.writeText(v + "\n")
                        f.setReadable(true, false); f.setWritable(true, false)
                    }
                }
                made++
            }
        }
        if (made > 0) log("peripheral_io: GPIO sysfs nodes for pins 0..${GPIO_STAND_IN_PINS - 1} ($made new)")
    }

    private fun parkSensorsHalOfPure32Treble() {
        if (!kernel.treble || !isPure32Tree(root)) return
        var n = 0
        for (dir in listOf("vendor/bin/hw", "system/vendor/bin/hw", "system/bin/hw")) {
            File(root, dir).listFiles()?.forEach { f ->
                if (!f.name.startsWith("android.hardware.sensors@") || "-service" !in f.name) return@forEach
                if (!f.isFile && !isLink(f)) return@forEach
                val dst = File(root, "system/.aemu-parked/${f.relativeTo(root).path.replace('/', '#')}")
                dst.parentFile?.mkdirs()
                if (dst.exists() || isLink(dst)) dst.delete()
                if (f.renameTo(dst)) n++
            }
        }
        if (n > 0) log("sensors: no sensors HAL for a 32-bit Treble firmware, its service is set aside ($n); the guest runs without sensors")
    }

    /**
     * A HAL the vendor manifest declares whose init service cannot start (its rc file names a /bin/hw/ program that
     * is not in the tree) never registers, and everything that asks for it waits for ever: cameraserver logs
     * "Waited one second for android.hardware.camera.provider@2.4::ICameraProvider/legacy/0" and the boot never
     * finishes. The engine's manifest lists the camera provider whether or not the firmware has one, so such an entry
     * is taken out of the manifests; the clients then see no HAL and report no camera.
     *
     * Only an entry with rc evidence goes: its package has a service in an rc file, the program of that service is
     * missing, and no other program of the same package is in the tree (configstore@1.0 and keymaster@3.0 are served by
     * the present 1.1 and "-qti" programs, and passthrough HALs such as the graphics mapper have no service at all).
     * Runs on every start, so a later engine that ships the program gets its entry back with the engine's manifest.
     */
    private fun dropHalsWithoutService() {
        val binDirs = listOf("vendor/bin/hw", "system/vendor/bin/hw", "system/bin/hw")
        val present = binDirs.flatMap { d -> File(root, d).list().orEmpty().toList() }
        val rcDirs = listOf("vendor/etc/init", "system/vendor/etc/init", "system/etc/init", "")
        // package (android.hardware.camera.provider) -> programs its rc services run that are not there
        val missing = HashMap<String, MutableSet<String>>()
        for (d in rcDirs) for (rc in File(root, d).listFiles().orEmpty()) {
            if (!rc.isFile || !rc.name.endsWith(".rc")) continue
            for (raw in runCatching { rc.readLines() }.getOrDefault(emptyList())) {
                val w = raw.trim().split(Regex("\\s+"))
                if (w.size < 3 || w[0] != "service" || "/bin/hw/" !in w[2]) continue
                if (File(root, w[2].removePrefix("/")).exists() || File(root, "system" + w[2]).exists()) continue
                val bin = w[2].substringAfterLast('/')
                val pkg = bin.substringBefore('@').takeIf { '@' in bin } ?: continue
                missing.getOrPut(pkg) { HashSet() }.add(bin)
            }
        }
        if (missing.isEmpty()) return
        // (a board's services are not all named "<package>@<version>-service": the AIDL ones are "<package>-service...",
        // "<package>.service.<vendor>" or "<package>-V1-...", and a package with such a program in the tree is not dead)
        val dead = missing.keys.filter { pkg -> present.none { it.startsWith("$pkg@") || it.startsWith("$pkg-") || it.startsWith("$pkg.") } }.toSet()
        if (dead.isEmpty()) return
        val hal = Regex("[ \\t]*<hal\\b[^>]*>.*?</hal>[ \\t]*\\r?\\n?", RegexOption.DOT_MATCHES_ALL)
        val name = Regex("<name>\\s*([^<\\s]+)\\s*</name>")
        val seen = HashSet<String>()
        val files = listOf("vendor/etc/vintf/manifest.xml", "vendor/manifest.xml", "system/vendor/manifest.xml", "system/vendor/etc/vintf/manifest.xml")
            .map { File(root, it) } +
            listOf("vendor/etc/vintf/manifest", "system/vendor/etc/vintf/manifest").flatMap { d ->
                File(root, d).listFiles()?.filter { it.name.endsWith(".xml") }.orEmpty()
            }
        for (f in files) {
            if (!f.isFile || !seen.add(runCatching { f.canonicalPath }.getOrDefault(f.path))) continue
            runCatching {
                val text = f.readText()
                val removed = ArrayList<String>()
                val out = hal.replace(text) { m ->
                    val n = name.find(m.value)?.groupValues?.get(1)
                    if (n != null && n in dead) { removed += n; "" } else m.value
                }
                if (removed.isNotEmpty()) {
                    f.setWritable(true)
                    f.writeText(out)
                    log("${f.relativeTo(root).path}: HAL without a service dropped: ${removed.distinct().joinToString()}")
                }
            }.onFailure { log("${f.name}: HAL entries not checked: ${it.message}") }
        }
    }

    /** [rel] and every library it names (all the way down) that the usual 64-bit directories have, plus the names none of them has. */
    private fun libClosure(rel: String): Pair<List<File>, List<String>>? {
        val f = File(root, rel)
        if (!f.isFile) return null
        val dirs = listOf("vendor/lib64", "vendor/lib64/hw", "system/lib64", "system/lib64/hw", "system/lib64/vndk", "system/lib64/vndk-sp",
            "system/lib64/vndk-26", "system/lib64/vndk-sp-26", "system/lib64/vndk-27", "system/lib64/vndk-sp-27").map { File(root, it) }
        val seen = HashSet<String>(); val missing = ArrayList<String>(); val files = arrayListOf(f); val queue = ArrayDeque<File>(); queue.add(f)
        var steps = 0
        while (queue.isNotEmpty() && steps++ < 300) {
            val cur = queue.removeFirst()
            for (lib in ElfPatch.needed64(cur).orEmpty()) {
                if (!seen.add(lib)) continue
                val hit = dirs.map { File(it, lib) }.firstOrNull { it.isFile }
                if (hit == null) missing += "$lib (from ${cur.name})" else { queue.add(hit); files += hit }
            }
        }
        return files to missing
    }

    /** Logs the libraries (transitively) that an ELF64 file of the tree names and that none of the usual 64-bit directories has. */
    private fun logMissingLibs(rel: String) {
        val (files, missing) = libClosure(rel) ?: run { log("deps: $rel: not in the tree"); return }
        log("deps: ${files[0].name}: " + if (missing.isEmpty()) "every library found (${files.size - 1})" else "NOT FOUND " + missing.joinToString(", "))
    }

    /** Logs what a dlopen() of [rel] would stop on at load time: a symbol that its own libraries (all of them, with their needs) do not define. */
    private fun logUnresolvedSymbols(rel: String, entry: String? = null) {
        val (files, _) = libClosure(rel) ?: return
        val syms = files.map { it to ElfPatch.dynSyms64(it) }
        val defined = HashSet<String>(); for ((_, s) in syms) if (s != null) defined += s.defined
        val unresolved = ArrayList<String>()
        for ((file, s) in syms) if (s != null) for (u in s.undefined) if (u !in defined) unresolved += "$u (${file.name})"
        val head = files[0].name + (if (entry != null) (if (syms[0].second?.defined?.contains(entry) == true) ": exports $entry" else ": does NOT export $entry") else "")
        log("symbols: $head; " + if (unresolved.isEmpty()) "every symbol resolves" else "UNRESOLVED ${unresolved.size}: " + unresolved.sorted().take(25).joinToString(", "))
    }

    private val VENDOR_HALS = setOf("gralloc", "hwcomposer", "copybit", "lights", "sensors", "audio", "power", "memtrack",
        "camera", "gps", "fingerprint", "gxfingerprint", "consumerir", "bluetooth", "nfc", "vibrator", "keystore", "gatekeeper")

    private fun installCameraHal() {
        val enabled = img.settings.camera && engine == Engine.KK && img.api in 14..25
        // video: the camera app records the camcorder profile's size, which the bridge has to deliver (640x480)
        runCatching {
            if (CamcorderProfiles.configure(root, enabled))
                log(if (enabled) "camera: camcorder profiles set to ${CamcorderProfiles.WIDTH}x${CamcorderProfiles.HEIGHT} (original kept)" else "camera: original camcorder profiles restored")
        }.onFailure { log("camera: camcorder profiles not changed: ${it.message}") }
        val destination = File(root, "system/lib/hw/camera.aemu_host.so")
        if (!enabled) {
            if (CameraHalFallback.configure(root, destination, false)) log("camera: restored previous default HAL slot")
            return
        }
        // Dedicated loader variant: never overwrite a stock camera.default/vendor HAL.
        val asset = "engines/common/camera.aemu_host.so"
        if (img.arch != GuestArch.ARM && eaSize(asset) < 0) { log("camera: no ${img.arch.id} host-camera HAL in this build, the guest has no camera"); return }
        if (destination.isFile && sameContent(asset, destination)) {
            if (CameraHalFallback.configure(root, destination, img.api <= 20)) log("camera: legacy default HAL fallback installed")
            return
        }
        val backup = File(root, "system/.aemu-parked/system#lib#hw#camera.aemu_host.so")
        runCatching {
            if (destination.exists() || isLink(destination)) {
                backup.parentFile?.mkdirs()
                if (!backup.exists() && !destination.renameTo(backup)) error("cannot preserve existing camera HAL")
                if (isLink(destination)) Os.remove(destination.absolutePath)
            }
            destination.parentFile?.mkdirs()
            eaOpen(asset).use { input -> destination.outputStream().use { input.copyTo(it) } }
            destination.setReadable(true, false); destination.setExecutable(true, false)
            log("camera: standard HAL1 host-camera bridge installed")
            if (CameraHalFallback.configure(root, destination, img.api <= 20)) log("camera: legacy default HAL fallback installed")
        }.onFailure { error("camera HAL installation failed: ${it.message}") }
    }

    private fun installSensorHal() {
        if (img.api !in 9..25) return
        val legacyApi = File(root, "system/lib/libsensorservice.so").takeIf { it.isFile }
            ?.let { runCatching { SensorHalApi.controlDataOnly(it.readBytes()) }.getOrDefault(false) } == true
        // Park OEM names too: old libhardware loaders can ignore ro.hardware.sensors.
        val parked = File(root, "system/.aemu-parked").apply { mkdirs() }
        for (directory in listOf("system/lib/hw", "vendor/lib/hw", "system/vendor/lib/hw")) {
            File(root, directory).listFiles()?.filter { it.name.startsWith("sensors.") && it.name.endsWith(".so") }?.forEach { file ->
                val dest = File(parked, (directory + "/" + file.name).replace('/', '#'))
                if (!dest.exists()) check(file.renameTo(dest)) { "Cannot park sensor HAL" } else check(file.delete())
            }
        }
        if (legacyApi || img.sensorsOff) {
            // This SensorService opens the "control" and "data" HAL devices and never checks the result; the stand-in HAL
            // only has "poll", so both pointers stayed uninitialised and system_server died in SensorDevice::SensorDevice().
            // Sensors disabled (setting, or automatic on Nougat+ Wear SDK builds, see SensorHalApi): none for the guest.
            // With no module at all it logs "couldn't load sensors module" and runs without sensors.
            for (name in listOf("sensors.aemu_host.so", "sensors.default.so")) File(root, "system/lib/hw/$name").delete()
            log(if (legacyApi) "motion: this firmware's SensorService needs the control/data sensor HAL, which the engine lacks; guest runs without sensors"
                else if (img.settings.disableSensors == null) "motion: Nougat+ Wear SDK build: libgui's unaligned sensor serialisation crashes under qemu (SIGBUS), guest runs without sensors (Settings > Disable all sensors)"
                else "motion: all sensors disabled in the settings; guest runs without sensors")
            return
        }
        if (img.arch != GuestArch.ARM && eaSize("engines/common/sensors.aemu_host.so") < 0) {
            log("motion: no ${img.arch.id} sensor HAL in this build, the guest has no sensors")
            return
        }
        for (name in listOf("sensors.aemu_host.so", "sensors.default.so")) {
            val file = File(root, "system/lib/hw/$name")
            file.parentFile?.mkdirs()
            eaOpen("engines/common/sensors.aemu_host.so").use { input -> file.outputStream().use { input.copyTo(it) } }
            file.setReadable(true, false)
        }
        log("motion: installed legacy host sensor HAL")
    }

    // Engine assets for THIS firmware's architecture (see EngineAssets.forArch): ARM reads engines/<set>/<file> as before, an
    // x86 firmware reads engines/<set>-x86/<file> and finds nothing where that build has not been added yet
    private fun eaOpen(path: String) = EngineAssets.open(ctx, EngineAssets.forArch(ctx, path, img.arch))
    private fun eaSize(path: String) = EngineAssets.size(ctx, EngineAssets.forArch(ctx, path, img.arch))

    private fun installEngineFiles() {
        // the fake SHM devices (libshmshim.so) keep their allocator state in these files: start every boot from scratch
        File(root, "data/local/tmp").listFiles()?.filter { it.name == "shm_cache.bin" || it.name == "shm_noncache.bin" }?.forEach { it.delete() }
        val copies = when (engine) {
            Engine.KK -> listOf(
                "libashmemshim.so" to "system/lib/libashmemshim.so",
                "libGLES.so" to "system/lib/egl/libGLES_bridge.so",
                "audio.primary.default.so" to "system/lib/hw/audio.primary.default.so",
                "gralloc.default.so" to "system/lib/hw/gralloc.default.so",
                "aemu-stubs.jar" to "system/framework/aemu-stubs.jar",
                "aemu-bt.jar" to "system/framework/aemu-bt.jar",
                "aemu-wifi.jar" to "system/framework/aemu-wifi.jar",
                "aemu-sensorhub.jar" to "system/framework/aemu-sensorhub.jar",
            )
            Engine.GB -> listOf(
                "libashmemshim.so" to "system/lib/libashmemshim.so",
                "libGLES_dhd.so" to "system/lib/egl/libGLES_dhd.so",
                "gralloc.default.so" to "system/lib/hw/gralloc.default.so",
            )
            Engine.N64 -> return installNative64()
        }
        File(root, "system/bin/fbpaint").delete()
        // a static network debugging tool shipped by older versions; nothing runs it
        File(root, "system/bin/netprobe").delete()
        // Widevine L3 aborts on its first session under the translator (6.0: right after "Could not read
        // /data/mediadrm/IDM0/ay64.dat"); up to 6.0 it lives in mediaserver, which takes camera and audio down with it.
        // Without the plugin MediaDrm says Widevine is not supported and players fall back to clear content.
        if (engine == Engine.KK) for (d in listOf("system/vendor/lib/mediadrm", "vendor/lib/mediadrm")) {
            val wv = File(root, "$d/libwvdrmengine.so")
            if (wv.isFile && !isLink(wv)) {
                val parked = File(root, "system/.aemu-parked/${d.replace('/', '#')}#libwvdrmengine.so")
                parked.parentFile?.mkdirs(); parked.delete()
                if (wv.renameTo(parked)) log("Widevine plugin set aside (aborts under the translator)")
            }
        }
        // образы, подготовленные старой версией: вернуть родной gralloc.default из «парковки»
        val parkedGralloc = File(root, "system/.aemu-parked/system#lib#hw#gralloc.default.so")
        if (parkedGralloc.isFile) {
            val g = File(root, "system/lib/hw/gralloc.default.so")
            g.delete(); parkedGralloc.renameTo(g)
        }
        // gralloc.default, который выделяет память через ION (MediaTek и др.), без ядра не работает:
        // устройство выделения получается пустым и SurfaceFlinger падает — ставим gralloc движка
        val romGralloc = File(root, "system/lib/hw/gralloc.default.so")
        // (x86: there is no engine gralloc to put in its place yet, the firmware keeps its own)
        if (engine == Engine.KK && img.arch == GuestArch.ARM && romGralloc.isFile && romGralloc.length() < 2_000_000) {
            val text = runCatching { String(romGralloc.readBytes(), Charsets.ISO_8859_1) }.getOrDefault("")
            if (text.contains("libion.so") || text.contains("ion_alloc")) {
                val parked = File(root, "system/.aemu-parked/system#lib#hw#gralloc.default.so.ion")
                parked.parentFile?.mkdirs()
                if (romGralloc.renameTo(parked)) log("firmware gralloc uses ION, replaced with engine gralloc")
            }
        }
        // Eclair (API 5..7): the Droid firmware gralloc.default blocks forever on its own module mutex (strace: FUTEX_WAIT on an address
        // inside gralloc.default.so, right after mmap of fb0) once fb0 offers a single page, so SurfaceFlinger never finishes readyToRun and
        // system_init never starts the Java services. The engine gralloc (AOSP) handles the single-page case with a plain ashmem buffer.
        val eclairGralloc = img.api in 5..7 && img.arch == GuestArch.ARM
        if (eclairGralloc && romGralloc.isFile) {
            val parked = File(root, "system/.aemu-parked/system#lib#hw#gralloc.default.so.eclair")
            if (!parked.exists()) {
                parked.parentFile?.mkdirs()
                if (runCatching { romGralloc.copyTo(parked) }.isSuccess) log("Eclair: firmware gralloc replaced with engine gralloc")
            }
        }
        // The firmware gralloc that the engine's replaces (kept in the parking dir, so the answer is the same on every boot), and
        // whether the standard engine gralloc would break that firmware: it unmaps buffers its own process allocated while the
        // firmware's gralloc leaves them alone (see GtiGralloc / GrallocProbe). Only then the Eclair-behaviour variant is installed.
        val eclairParkedGralloc = File(root, "system/.aemu-parked/system#lib#hw#gralloc.default.so.eclair")
        val ionParkedGralloc = File(root, "system/.aemu-parked/system#lib#hw#gralloc.default.so.ion")
        val replacedGralloc = listOf(eclairParkedGralloc.takeIf { eclairGralloc }, ionParkedGralloc)
            .firstOrNull { it != null && it.isFile }
            ?.let { runCatching { it.readBytes() }.getOrNull() }
        val replacingFirmwareGralloc = eclairGralloc || ionParkedGralloc.isFile
        val engineGralloc = if (replacedGralloc == null) null
            else runCatching { eaOpen("engines/${engine.id}/gralloc.default.so").use { it.readBytes() } }.getOrNull()
        val grallocVariant = GtiGralloc.applies(replacedGralloc, engineGralloc)
        if (replacedGralloc != null && engineGralloc != null)
            log("gralloc: firmware ${GrallocProbe.verdict(replacedGralloc)}, engine ${GrallocProbe.verdict(engineGralloc)} -> " +
                if (grallocVariant) "Eclair-behaviour variant" else "standard engine gralloc")
        var copied = 0
        var skippedForArch = 0
        // 7.0+ libEGL on a qemu kernel (qemu.gles=1) only tries the "emulation" driver name
        val nougat = if (img.api >= 24 && copies.any { it.second == "system/lib/egl/libGLES_bridge.so" })
            listOf("libGLES_split.so" to "system/lib/egl/libGLES_emulation.so") else emptyList()
        // 7.0+ without the GPU bridge: libEGL would still find the bridge by scanning egl/, so it is not there at all
        val noBridge = img.api >= 24 && !img.settings.gpu
        if (noBridge) {
            for (n in listOf("libGLES_bridge.so", "libGLES_emulation.so")) File(root, "system/lib/egl/$n").delete()
            // a scan skips libGLES_android.so; in "vendor software renderer" mode (qemu.gles=2) libEGL asks for
            // libGLES_swiftshader.so, which here is the stock software renderer
            val sw = File(root, "system/lib/egl/libGLES_swiftshader.so")
            if (!sw.exists() && !isLink(sw) && File(root, "system/lib/egl/libGLES_android.so").isFile)
                runCatching { Os.symlink("libGLES_android.so", sw.path) }
        }
        for ((from, to) in (copies + nougat).filter { !noBridge || !it.second.startsWith("system/lib/egl/libGLES_") } +
            listOf("@libaemushim.so" to "system/lib/libaemushim.so", "@libaemurlimit.so" to GuestPreload.RLIMIT.removePrefix("/"),
                "@lights.default.so" to "system/lib/hw/lights.default.so") +
            // glibc (Google TV) firmware: fake /dev/shm_cache and /dev/shm_noncache so av_settings & co. can start
            (if (GuestPreload.isGlibcFirmware(root)) listOf("@libshmshim.so" to GuestPreload.SHMSHIM.removePrefix("/")) else emptyList())) {
            val dst = File(root, to)
            // gralloc движка — только если в прошивке своего нет
            if (from == "gralloc.default.so" && dst.isFile && !replacingFirmwareGralloc) continue
            // «@» — общий для всех движков файл
            // звуковой HAL стенда разложен под AudioFlinger HTC; остальным 4.2+ — вариант с раскладкой AOSP.
            // У Samsung свой audio_stream_out (лишние слоты) — с ним AOSP-вариант роняет mediaserver,
            // поэтому там остаётся исходный: выход не открывается, система работает без звука.
            // Samsung 4.3 AudioFlinger uses the KitKat slots (verified on I9300 XXUGNJ2: init_check 0x44,
            // open_output_stream 0x6c, stream write 0x40), so only 4.1–4.2 TouchWiz keeps the stand HAL
            // Samsung 4.0 (api 14-15, e.g. GT-P7300) has the ICS device layout (no get_master_volume /
            // set_master_mute slots): the stand HAL's JB layout put a data pointer in a slot AudioFlinger calls
            // right after get_supported_devices (SIGSEGV pc inside libaudioflinger .data), so it takes the ICS HAL
            val samsung = img.skin.contains("TouchWiz", true) || img.skin.contains("Samsung", true)
            val htcLike = img.skin.contains("HTC", true) || (samsung && img.api in 16..17)
            val mtkHw = from == "audio.primary.default.so" && engine == Engine.KK && isMtkHwOnlyAudio(root)
            val name = when {
                // firmware whose gralloc leaves same-process buffers alone but the engine's would unmap them (see GtiGralloc)
                from == "gralloc.default.so" && grallocVariant -> GtiGralloc.ASSET
                mtkHw -> "audio.primary.mtk.so"
                from != "audio.primary.default.so" || htcLike -> from
                // 4.0 has its own audio_hw_device layout; Qualcomm CAF builds add set_fm_volume/open_output_session
                img.api in 14..15 -> when {
                    qcomAudioFlinger() -> "audio.primary.ics-qcom.so"
                    // the +0xc-shifted device layout only when this firmware's AudioFlinger really calls
                    // open_output_stream at +116; stock ICS (+104, e.g. GT-P7300 4.0.4) must keep the plain ICS HAL,
                    // otherwise AudioFlinger calls set_parameters instead and mediaserver dies in readOutputParameters
                    samsung && samsungDeviceSlots() -> "audio.primary.ics-samsung.so"
                    // ICS 4.0.x build with the JB/KK device layout (get_master_volume slot present, e.g. Nexus Q):
                    // open_output_stream is at +108, which the plain ICS HAL leaves as close_output_stream -> SIGSEGV
                    // in mediaserver at AudioFlinger::openOutput. The AOSP-layout HAL matches that slot.
                    openOutputSlotIs(108) -> "audio.primary.aosp.so"
                    else -> "audio.primary.ics.so"
                }
                // Samsung KitKat+ whose AudioFlinger calls open_output_stream at +116 (the +0xc-shifted device layout):
                // the AOSP-layout HAL answers that slot with another method, the policy gets no primary output
                // ("Failed to open primary output", init_check "No such device") and mediaserver then dies in the
                // half-initialised policy (heap corruption in audio_policy.default.so, code 139)
                samsung && img.api >= 18 && samsungDeviceSlots() -> {
                    log("audio: Samsung AudioFlinger calls open_output_stream at +116, using the shifted-layout HAL")
                    "audio.primary.ics-samsung.so"
                }
                directTrackAudio() -> "audio.primary.directtrack.so"
                img.api >= 16 -> "audio.primary.aosp.so"
                else -> from
            }
            val asset = if (name.startsWith("@")) "engines/common/${name.drop(1)}" else "engines/${engine.id}/$name"
            // x86: the helper libraries are ARM binaries until their x86 builds are added; a file that is not there is skipped
            if (img.arch != GuestArch.ARM && eaSize(asset) < 0) { skippedForArch++; continue }
            val size = runCatching { eaSize(asset) }.getOrDefault(-1L)
            // одинаковый размер ещё не значит тот же файл (правки движка на месте, варианты HAL) — сверяем CRC
            if (dst.isFile && size >= 0 && dst.length() == size && sameContent(asset, dst)) continue
            if (mtkHw) runCatching {
                // keep the firmware's own HAL (never one of ours): isMtkAudio keeps working after the swap
                val parked = File(root, "system/.aemu-parked/system#lib#hw#audio.primary.default.so")
                if (!parked.isFile && dst.isFile) { parked.parentFile?.mkdirs(); dst.copyTo(parked) }
                log("audio: MediaTek HAL layout in hw/audio.primary.default.so")
            }
            runCatching {
                dst.parentFile?.mkdirs()
                if (isLink(dst)) Os.remove(dst.absolutePath)
                eaOpen(asset).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                dst.setReadable(true, false)
                dst.setExecutable(true, false)
                copied++
                if (name == "audio.primary.directtrack.so") log("audio: verified CAF direct-track stream ABI selected")
                if (name == GtiGralloc.ASSET) log("gralloc: Eclair-behaviour variant installed (same-process buffers are not re-mapped or unmapped)")
            }.onFailure { log("${from.removePrefix("@")} missing from engine set: ${it.message}") }
        }
        if (skippedForArch > 0)
            log("${img.arch.id}: $skippedForArch engine helper files have no ${img.arch.id} build yet (assets/engines/*${img.arch.assetSuffix}); the guest runs without them")
        // Replicate camera.default.so into vendor/lib/hw and 64-bit libraries for modern cameraserver and apps
        val cam32 = File(root, "system/lib/hw/camera.default.so")
        val vcam32 = File(root, "vendor/lib/hw/camera.default.so")
        if (cam32.isFile && File(root, "vendor/lib/hw").isDirectory && (!vcam32.exists() || vcam32.length() != cam32.length())) {
            runCatching { cam32.copyTo(vcam32, overwrite = true) }
        }
        if (File(root, "system/lib64").isDirectory) {
            val cam64 = File(root, "system/lib64/hw/camera.default.so")
            val vcam64 = File(root, "vendor/lib64/hw/camera.default.so")
            runCatching {
                // a kernel of the native engine may bring its own build of the HAL
                val asset64 = (if (engine == Engine.N64) kernel.assets.reversed() else emptyList())
                    .firstNotNullOfOrNull { EngineAssets.layerAsset(ctx, it, "system#lib64#hw#camera.default.so", EngineAssets.ARM64) }
                    ?: "engines/common/camera.default64.so"
                val size64 = eaSize(asset64)
                if (!cam64.exists() || cam64.length() != size64) {
                    cam64.parentFile?.mkdirs()
                    eaOpen(asset64).use { i -> cam64.outputStream().use { o -> i.copyTo(o) } }
                    cam64.setReadable(true, false)
                }
                if (File(root, "vendor/lib64/hw").isDirectory && (!vcam64.exists() || vcam64.length() != cam64.length())) {
                    cam64.copyTo(vcam64, overwrite = true)
                }
            }
        }
        linkerLibRange()
        // вендорские драйверы RenderScript (Adreno, Mali…) лезут в GPU; без них libRS берёт процессорный
        for (dir in listOf("system/lib", "vendor/lib", "system/vendor/lib")) {
            File(root, dir).listFiles()?.filter { it.name.startsWith("libRSDriver_") }?.forEach { f ->
                val parked = File(root, "system/.aemu-parked/${f.relativeTo(root).path.replace('/', '#')}")
                parked.parentFile?.mkdirs()
                if (f.renameTo(parked)) log("RenderScript: removed vendor driver ${f.name}")
            }
        }
        // netfilter в эмуляторе нет: iptables всегда падает, а netd 4.x (Samsung) считает это фатальным
        // для NetworkManagementService/ConnectivityService — ставим пустую программу, родную прячем
        val trueSize = runCatching { eaSize("engines/common/aemu_true.so") }.getOrDefault(-1L)
        // MIUI: invoke-as runs FirewallService's iptables loop as root; under qemu that shell never sees its
        // children exit, so ConnectivityService (and the whole boot) hangs in CommandLineUtils.waitFor
        val fakes = listOf("bin/iptables", "bin/ip6tables") + if (img.skin.contains("MIUI", true)) listOf("xbin/invoke-as") else emptyList()
        for (rel in fakes) {
            val n = rel.substringAfter('/')
            val f = File(root, "system/$rel")
            if (!f.exists() && !isLink(f) || trueSize < 0 || (!isLink(f) && f.length() == trueSize)) continue
            runCatching {
                val parked = File(root, "system/.aemu-parked/system#${rel.replace('/', '#')}")
                parked.parentFile?.mkdirs()
                if (isLink(f)) Os.remove(f.absolutePath) else if (!parked.exists()) f.renameTo(parked) else f.delete()
                eaOpen("engines/common/aemu_true.so").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                f.setReadable(true, false); f.setExecutable(true, false)
                copied++
            }.onFailure { log("$n not replaced: ${it.message}") }
        }
        // камера 2.3: заглушка вместо вендорской libcamera.so, которая лезет в /dev/msm_camera
        val cam = File(root, "system/lib/libcamera.so")
        val stamp = File(root, "system/lib/libcamera.so.aemu")
        val parkedCam = File(root, "system/.aemu-parked/system#lib#libcamera.so")
        val htc = img.skin.contains("HTC", true)
        // заглушка собрана под интерфейс камеры HTC — у других производителей оставляем родную
        if (!htc && stamp.isFile && parkedCam.isFile) { cam.delete(); parkedCam.renameTo(cam); stamp.delete() }
        if (engine == Engine.GB && htc) {
            if (cam.isFile && !stamp.isFile) {
                runCatching {
                    File(root, "system/.aemu-parked").mkdirs()
                    cam.copyTo(File(root, "system/.aemu-parked/system#lib#libcamera.so"), overwrite = true)
                    eaOpen("engines/gb/libcamera.so").use { i -> cam.outputStream().use { o -> i.copyTo(o) } }
                    stamp.writeText("1")
                }
            }
        }
        // 2.3, Qualcomm: the camera HAL needs the modem (ONCRPC) and quits mediaserver without it — a HAL with no cameras.
        // Only for the HAL_* interface of 2.3 (API 9-10): the stub exports nothing else. Samsung's 2.1/2.2 (and 2.3 with
        // libseccameraadaptor) link libcameraservice against the C symbol openCameraHardware, which the stub lacks, so
        // mediaserver dies at link time ("cannot locate 'openCameraHardware'") — those keep their own libcamera.so.
        fun needsLegacyCameraSymbol(): Boolean = listOf("libcameraservice.so", "libseccameraadaptor.so").any { n ->
            val f = File(root, "system/lib/$n")
            f.isFile && runCatching { String(f.readBytes(), Charsets.ISO_8859_1).contains("openCameraHardware") }.getOrDefault(false)
        }
        val stubOk = img.api in 9..10 && !htc && !needsLegacyCameraSymbol()
        // a stub that an earlier build put on a firmware it does not fit: put the stock library back
        if (!stubOk && !htc && parkedCam.isFile && !stamp.isFile) runCatching {
            val noneSize = eaSize("engines/common/libcamera.none.so")
            if (noneSize > 0 && cam.length() == noneSize) {
                cam.delete(); parkedCam.renameTo(cam)
                log("camera: stock libcamera.so restored (the empty HAL does not fit this firmware)")
                copied++
            }
        }
        if (stubOk && cam.isFile && !parkedCam.exists() && cam.length() > 4096) {
            val text = runCatching { String(cam.readBytes(), Charsets.ISO_8859_1) }.getOrDefault("")
            if (listOf("libcald", "liboemcamera", "oncrpc").any { text.contains(it) }) runCatching {
                File(root, "system/.aemu-parked").mkdirs()
                cam.copyTo(parkedCam, overwrite = true)
                eaOpen("engines/common/libcamera.none.so").use { i -> cam.outputStream().use { o -> i.copyTo(o) } }
                log("camera: the firmware's 2.3 HAL needs the modem, replaced with an empty one (stock kept)")
                copied++
            }
        }
        if (copied > 0) log("engine ${engine.id} files updated: $copied")
    }

    /**
     * The 2.x linker gives libraries that are not prelinked addresses from a fixed range of its own. AOSP's is
     * 0x80000000 - 0x90000000; Sony's 2.3 (Xperia 2011) uses 0x40000000 - 0x50000000, which is exactly where the
     * stand's qemu keeps the guest stack and its own mappings: nothing fits ("cannot map library
     * 'libashmemshim.so'. no vspace available", every service exits with 255). The range is a constant in the
     * linker — base, size, 1 MB step — and is moved to 0x90000000, which Sony's prelink map leaves free.
     */
    private fun linkerLibRange() {
        if (img.api > 10) return
        val f = File(root, "system/bin/linker")
        if (!f.isFile || f.length() > 4_000_000) return
        runCatching {
            val d = f.readBytes()
            val b = java.nio.ByteBuffer.wrap(d).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            var off = 0
            while (off + 12 <= d.size) {
                if (b.getInt(off) == 0x40000000 && b.getInt(off + 4) == 0x10000000 && b.getInt(off + 8) == 0x100000) {
                    b.putInt(off, 0x90000000.toInt())
                    f.writeBytes(d)
                    log("linker: range for non-prelinked libraries moved from 0x40000000 to 0x90000000")
                    break
                }
                off += 4
            }
        }
    }

    /** Направляет загрузчик EGL прошивки на GL-мост (или на программный растеризатор). */
    private fun eglConfig(gpu: Boolean) {
        // x86 Lollipop/Marshmallow firmware without the AOSP software renderer (also covers images imported by older builds)
        X86SoftwareGles.ensure(ctx, root, img.api, log)
        val dir = File(root, "system/lib/egl").apply { mkdirs() }
        val cfg = File(dir, "egl.cfg")
        val cfgRom = File(dir, "egl.cfg.rom")
        // Motorola (e.g. XT910 4.0.4): egl.cfg is a symlink to /sys/egl/egl.cfg, which doesn't exist on the
        // host, so reading/writing through it fails with ENOENT and aborts boot. Replace it with a real file.
        if (isLink(cfg)) runCatching { Os.remove(cfg.absolutePath) }.onFailure { log("egl.cfg: symlink removal failed: ${it.message}"); cfg.delete() }
        if (!cfgRom.isFile && cfg.isFile) cfg.copyTo(cfgRom, overwrite = true)
        when (engine) {
            Engine.N64 -> {}
            Engine.GB -> cfg.writeText(if (gpu) "0 0 dhd\n" else "0 0 android\n")
            Engine.KK -> {
                // сам мост лежит как libGLES_bridge.so; все имена, которые ищут загрузчики EGL (libGLES.so у 4.4,
                // libGLES_<тег>.so по egl.cfg, раздельные libEGL_/libGLESv*_), занимает переходник libGLES_split:
                // он чинит загрузку текстур, сводит раздельные библиотеки в один мост и даёт SurfaceFlinger ES2
                val bridge = File(dir, "libGLES_bridge.so")
                val sw = File(dir, "libGLES_android.so")
                val swOff = File(dir, "libGLES_android.so.sw")
                val names = listOf("libGLES.so", "libGLES_aemu.so", "libGLES_android.so", "libEGL_aemu.so", "libGLESv1_CM_aemu.so", "libGLESv2_aemu.so")
                if (gpu && bridge.isFile) {
                    if (!swOff.isFile && sw.isFile && sw.length() != splitSize() && sw.length() != assetSize(OPEN_GL)) sw.renameTo(swOff)
                    // test switch: a file "glopen" in the image's directory puts the open bridge (native/glbridge,
                    // armv7) on those names; it stands in front of the shim, which keeps the compositor and hwui
                    val open = openGl32()
                    val asset = if (open) OPEN_GL else "engines/kk/libGLES_split.so"
                    val all = names + (if (img.api >= 24) listOf("libGLES_emulation.so") else emptyList())
                    runCatching {
                        for (n in all) installAsset(asset, File(dir, n))
                        val shim = File(dir, "libGLES_split.so")
                        if (open) installAsset("engines/kk/libGLES_split.so", shim) else shim.delete()
                    }.onFailure { log("GL: shim failed to install: ${it.message}") }
                    if (open) log("GL: open bridge in front of the 4.4 engine's one (glopen)")
                    // часть загрузчиков (Samsung 4.3) берут последнюю строку, остальные — строку с impl=1
                    cfg.writeText("0 0 android\n0 1 aemu\n")
                } else {
                    // программная отрисовка: убираем все наши имена, иначе загрузчик 4.4 всё равно найдёт libGLES.so
                    // (x86 5.x/6.0 libEGL ignores egl.cfg and asks for egl/libGLES.so first, then scans for libGLES_<tag>.so, which
                    // never takes libGLES_android.so: the file X86SoftwareGles.ensure put under that name just above has to stay,
                    // or SurfaceFlinger aborts with "couldn't find an OpenGL ES implementation", exit 132)
                    val keepPlain = img.arch != GuestArch.ARM && img.api in 20..23
                    for (n in names) if (n != "libGLES_android.so" && !(keepPlain && n == "libGLES.so")) File(dir, n).delete()
                    if (swOff.isFile) { sw.delete(); swOff.renameTo(sw) }
                    cfg.writeText("0 0 android\n")
                }
            }
        }
    }

    private fun isElf64(f: File): Boolean = runCatching {
        f.inputStream().use { i -> val b = ByteArray(5); i.read(b) == 5 && b[4] == 2.toByte() }
    }.getOrDefault(false)

    private fun sameContent(asset: String, f: File): Boolean = runCatching {
        fun crc(i: java.io.InputStream): Long {
            val c = java.util.zip.CRC32(); val b = ByteArray(1 shl 16)
            while (true) { val n = i.read(b); if (n < 0) break; c.update(b, 0, n) }
            return c.value
        }
        eaOpen(asset).use { crc(it) } == f.inputStream().use { crc(it) }
    }.getOrDefault(false)

    /** Пустые таблицы xt_qtaguid, на которые гостевая прослойка подменяет /proc/net/xt_qtaguid/… */
    /**
     * Images imported from a zip that lost its symlinks have system/bin/sh as a 4-byte text file ("mksh"), so
     * `adb shell` ends in "Error while loading .../system/bin/sh: Exec format error", and so does every toolbox applet
     * (ls, cat, ...) that is a 7-byte file saying "toolbox". Such images are repaired here, once per image
     * (see [FlattenedLinks]); new imports are repaired by Importer.finishTree.
     */
    private fun flattenedLinks() = runCatching {
        val stamp = File(root, ".aemu-flatlinks")
        if (stamp.isFile) return@runCatching
        FlattenedLinks.repair(root, log)
        stamp.writeText("1\n")
    }.onFailure { log("symlinks: flattened link repair failed: ${it.message}") }

    /**
     * `adb shell` and every `sh -c` start /system/bin/sh. qemu stats the host path of the executable, so the link must
     * be relative and resolve inside the tree: a missing sh, a dangling link, or an absolute link (it would resolve
     * against the phone's own /system) ends in "unable to stat file for the executable" and an abort in the guest
     * linker. Relinks it (relative) to the first of mksh/ash/toybox/busybox that really lives inside the tree; a
     * working sh is left alone.
     */
    private fun shellLink() = runCatching {
        val bin = File(root, "system/bin")
        val path = File(bin, "sh").toPath()
        val nofollow = java.nio.file.LinkOption.NOFOLLOW_LINKS
        val rootPath = root.canonicalPath + File.separator
        // canonicalFile resolves absolute links against the phone, so such a target lands outside rootPath and is rejected
        fun inTree(f: File) = runCatching { f.canonicalFile.let { it.isFile && it.path.startsWith(rootPath) } }.getOrDefault(false)
        val isLink = java.nio.file.Files.isSymbolicLink(path)
        if (isLink) {
            val t = java.nio.file.Files.readSymbolicLink(path).toString()
            if (!t.startsWith("/") && inTree(File(bin, "sh"))) return@runCatching
        } else if (java.nio.file.Files.exists(path, nofollow)) {
            // a real file is not ours to replace, unless it is a few bytes of plain text (a flattened link, "mksh")
            val head = runCatching { path.toFile().takeIf { it.length() <= FlattenedLinks.MAX_LEN }?.readBytes() }.getOrNull()
            if (head == null || FlattenedLinks.targetOf(head) == null) return@runCatching
            path.toFile().delete()
        }
        val target = listOf("mksh", "ash", "toybox", "busybox", "../xbin/busybox").firstOrNull { inTree(File(bin, it)) }
        if (target == null) {
            // (pre-1.0 builds have no system/bin/sh by design: the shell is /sbin/sh of the ramdisk)
            if (!LegacyFirmware.isLegacyLayout(paths.root)) log("sh: system/bin/sh is missing and there is no mksh/ash/toybox/busybox to link it to")
            return@runCatching
        }
        if (isLink) java.nio.file.Files.delete(path)
        java.nio.file.Files.createSymbolicLink(path, java.nio.file.Paths.get(target))
        log("sh: system/bin/sh -> $target restored")
    }.onFailure { log("sh: could not repair system/bin/sh: ${it.message}") }

    /**
     * MediaTek bionic (linker, libc) uses `pldw` hints that qemu-user rejects with SIGILL, so every process died in
     * its first memcpy. See [ElfPatch.fixPldHints].
     */
    private fun pldHints() = runCatching {
        var words = 0; var files = 0
        val hit = ArrayList<String>()
        for (dir in listOf("system/bin", "system/xbin", "system/lib", "vendor/lib", "vendor/bin")) {
            File(root, dir).listFiles()?.forEach { f ->
                if (f.isFile && !java.nio.file.Files.isSymbolicLink(f.toPath())) {
                    val n = ElfPatch.fixPldHints(f)
                    if (n > 0) { words += n; files++; hit.add("${f.name}=$n") }
                }
            }
        }
        if (words > 0) log("pld hints: $words word(s) rewritten in $files file(s) (qemu rejects pldw and malformed pld): ${hit.joinToString(", ")}")
    }.onFailure { log("pld hints: failed: ${it.message}") }

    /**
     * MediaTek DRVB: кодеки, DRM, камера и даже debuggerd сверяют «платформу» через демон drvbd, а тот
     * читает efuse через /dev/devmap. На эмуляторе проверка проваливается, каждый клиент 10 с ждёт демон,
     * а модуль DRM затем нарочно прыгает на 0xddeeaadd (drmserver падает, MediaPlayer зависает намертво).
     * Проверка отвечает на вызов подписанным ответом, который считает сама библиотека, — поэтому достаточно,
     * чтобы её внутренние проверки вернули «успех»: ответ получится честный.
     */
    private fun vendorChecks() = runCatching {
        val drvb = File(root, "system/lib/libmtk_drvb.so")
        if (drvb.isFile) {
            val n = ElfPatch.returnZero(drvb, setOf("mtk_drvb_basechk", "platform_init", "platform_advchk", "drvb_ext_input"))
            if (n > 0) log("MediaTek DRVB: platform check disabled ($n func.)")
        }
    }.onFailure { log("MediaTek DRVB: failed: ${it.message}") }

    /**
     * 5.0+/7.x: the guest runs under qemu-user, so libselinux sees the HOST's selinuxfs
     * (/proc/filesystems, /sys/fs/selinux) and is_selinux_enabled() answers 1. servicemanager (and
     * installd, keystore, system_server…) then demands a service_contexts handle and a security context
     * of its own, finds neither, and abort()s (exit 134) before binderd ever gets a context manager.
     * The guest has no policy of its own to load, so report "SELinux disabled" from libselinux:
     * every AOSP caller guards its checks with is_selinux_enabled() > 0 and falls back to plain DAC.
     */
    /**
     * Google TV (Marvell Berlin, e.g. Hisense GX1200V): av_settings and client_auth_service call MV_OSAL_Init(), which
     * starts the OSAL worker tasks and raises their priority with os_set_task_prio() -> pthread_setschedparam()
     * (SCHED_RR/FIFO). The guest is an unprivileged process under qemu-user, so the host refuses it (EPERM) and the
     * caller does MV_ASSERT(ret == 0); t_Assert() then deliberately writes to address 0 (SIGSEGV inside libOSAL.so,
     * av_settings exit 139) and the logs show "MV_OSAL_Init() failed (80004005)". "media.avsettings" is never
     * registered, SurfaceFlinger waits for it forever and the boot never gets past the splash.
     * Real-time priorities mean nothing here, so os_set_task_prio() just reports success.
     */
    private val HDMIRX_NOOPS = setOf(
        "GetBoardVersion", "kg2h_gpio_exit", "kg2h_gpio_set", "kg2h_gpio_enable_irq", "kg2h_gpio_disable_irq",
    )

    /**
     * Entry points of the Avc275x HDMI receiver driver (libkg2h.so): they bring the chip up over i2c/gpio and wait for
     * it to report ready, which it never does here. Skipped (report success) by [hdmiRxGuard].
     */
    private val HDMIRX_INIT = setOf("KG2H_InitDevice", "AvcInitKG2h", "AvcInitHdmiRx", "Avc275xI2CInit")

    private fun berlinOsalGuard() = runCatching {
        if (!GuestPreload.isGlibcFirmware(root)) return@runCatching
        // The Marvell kernel drivers (/dev/galois_cc, /dev/galois_pe_agent, /dev/mvpm) do not exist here:
        //  - MV_CC_DSS_Init() opens /dev/galois_cc and fails, so MV_OSAL_Init() returns E_FAIL (80004005);
        //  - MV_PE_Init() opens /dev/galois_pe_agent and fails, the hotplug handler then cleans up with
        //    MV_PE_Remove(NULL), whose MV_ASSERT(handle != NULL) crashes av_settings (SIGSEGV in t_Assert).
        //  - MV_CC_UDP_Init() -> MV_CC_UDP_Open() opens a private netlink socket (AF_NETLINK, SOCK_RAW, protocol 29)
        //    towards the Galois kernel module; the host refuses it, so MV_OSAL_Init() still fails after SHM init.
        // The OSAL/PE calls that need the drivers are reduced to "success" so av_settings can reach
        // "media.avsettings"; the rest of the Berlin A/V path is not available in the guest anyway.
        val targets = mapOf(
            "libOSAL.so" to setOf("os_set_task_prio", "MV_CC_DSS_Init", "MV_CC_UDP_Init"),
            // every PE (Marvell "player engine") entry point that the hotplug handler of av_settings calls: there is no
            // /dev/galois_pe_agent, so each one just reports success (output buffers stay as the caller left them)
            "libPEAgent.so" to setOf(
                "MV_PE_Init", "MV_PE_Remove", "MV_PE_RegisterEventCallBack", "MV_PE_ClearScreen",
                "MV_PE_VOutSetEnable", "MV_PE_VOutSetInput", "MV_PE_VOutHDMIGetSinkCaps", "MV_PE_VOutGetCPCBResolution",
                "MV_PE_VOutHDMISetVideoFormat", "MV_PE_VOutHDMISetAudioFormat", "MV_PE_VOutSetCPCBResolutionBDEx",
                "MV_PE_VOutHDMISet3DVideoFormat", "MV_PE_VOutHDMILoadHDCPKeys", "MV_PE_VOutHDMISetHDCP",
                "MV_PE_VideoSet3DConvertMode", "MV_PE_VideoSetSSType",
                "MV_PE_AOutGetDigitalOutCaps", "MV_PE_AOutSetVolume", "MV_PE_AOutSetMute", "MV_PE_AOutGetHDMIFormat",
                "MV_PE_AOutSetHDMIFormat", "MV_PE_AOutGetSpdifFormat", "MV_PE_AOutSetSpdifFormat",
            ),
        )
        for ((name, funcs) in targets) for (dir in listOf("system/vendor/lib", "system/lib")) {
            val rel = "$dir/$name"
            val lib = File(root, rel)
            if (!lib.isFile) continue
            val keep = File(root, "system/.aemu-parked/" + rel.replace("/", "#"))
            if (!keep.exists()) { keep.parentFile?.mkdirs(); lib.copyTo(keep) }
            val n = ElfPatch.returnZero(lib, funcs)
            if (n > 0) log("Berlin: $name patched ($n func.: ${funcs.joinToString()})")
        }
        // mediaserver crash loop (exit 139, 12 restarts, media.audio_policy never published): libkg2h's HDMI RX init
        // fails (no /dev/twsi0, no /dev/gpio -> "ROM code status" never passes), the error path calls
        // libHdmiRx GetBoardVersion(), which does fopen("/proc/galois_pe/detail") + fread(buf, 1, 0x400, fp) without
        // checking fp. The proc file belongs to the Galois kernel module, so fp == NULL and libc faults on the stream
        // lock (qemu: PC in libc, r3 = 0, LR = libHdmiRx +0x5d88). The board version is only informational: report 0.
        // GetBoardVersion is a static function: it exists in .symtab only, not in .dynsym.
        // Second crash of the same family: kg2h_gpio_init() fails (no /dev/gpio), then KG2H_InitDevice() -> kg2h_gpio_set()
        // -> ioctl on a dead fd fails -> kg2h_gpio_exit() does fclose(NULL) (qemu: PC in libc, r0 = 0, LR = libHdmiRx
        // +0x3324). The whole GPIO line is a no-op here: set/exit/enable_irq/disable_irq just report success.
        // (kg2h_gpio_poll is left alone: its only caller, kg2h_int_process_task, runs only after a successful gpio init.)
        for (dir in listOf("system/vendor/lib", "system/lib")) {
            val rel = "$dir/libHdmiRx.so"
            val lib = File(root, rel)
            if (!lib.isFile) continue
            val keep = File(root, "system/.aemu-parked/" + rel.replace("/", "#"))
            if (!keep.exists()) { keep.parentFile?.mkdirs(); lib.copyTo(keep) }
            val n = ElfPatch.returnZero(lib, HDMIRX_NOOPS, includeSymtab = true)
            if (n > 0) log("Berlin: $rel patched ($n func.: ${HDMIRX_NOOPS.joinToString()})")
        }
        // AlarmManagerService (system_server) sets the kernel time zone with settimeofday(&tv, &tz) right after
        // /dev/alarm fails to open. The host's seccomp filter does not allow that syscall for an app process and kills
        // the whole guest process with SIGSYS (zygote: "terminated by signal (31)", strace: settimeofday(...,{-180,0})
        // followed by SIGCHLD si_status=31), so system_server dies at "SystemServer: Alarm Manager" on every boot.
        // The guest can never change the host clock anyway: make glibc's settimeofday() report success.
        for (rel in listOf("lib/libc-2.12.2.so")) {
            val libc = File(root, rel)
            if (!libc.isFile) continue
            val keep = File(root, "system/.aemu-parked/" + rel.replace("/", "#"))
            if (!keep.exists()) { keep.parentFile?.mkdirs(); libc.copyTo(keep) }
            val n = ElfPatch.returnZero(libc, setOf("settimeofday", "__settimeofday"))
            if (n > 0) log("Berlin: glibc settimeofday() no longer reaches the host ($n func.)")
        }
        // berlin_avservice (init.rc class early_start) is the one that finishes the A/V engine start-up and then creates
        // this marker; HotplugHandler::Init() in av_settings polls access("/tmp/.PE_AV_Init.done") before it goes on,
        // so without the marker av_settings stays alive but never registers "media.avsettings".
        // /tmp is a symlink to /var/tmp in this image (init.rc mounts a tmpfs on /var at boot and makes /var/tmp):
        // follow the link inside the guest tree, never on the host, and create the directory behind it
        var rel = "tmp"
        for (i in 0 until 8) {
            val f = File(root, rel)
            if (!isLink(f)) break
            val t = Os.readlink(f.absolutePath)
            rel = if (t.startsWith("/")) t.trimStart('/') else File(rel).parent.let { if (it == null) t else "$it/$t" }
        }
        val tmp = File(root, rel).apply { mkdirs() }
        val marker = File(tmp, ".PE_AV_Init.done")
        if (!marker.exists() && marker.createNewFile()) log("Berlin: /tmp/.PE_AV_Init.done created (in /$rel)")
    }.onFailure { log("Berlin: vendor lib patch failed: ${it.message}") }

    /**
     * Google TV 3.2 (Marvell Berlin, Netgear GTV100) with a bionic userland: mediaserver prints "HDMI RX INIT" and then
     * spins forever in the Avc275x HDMI receiver driver ("master_id = 0,,twsi_fp[master_id] = (null)",
     * "Avc275x_i2csend -- write FAIL!!" / "Avc275x_i2crecv -- i2c read FAIL!!", ~700 MB of it in 15 seconds). The chip sits on
     * /dev/twsi0 and /dev/gpio, which do not exist here, so the receiver never answers, MediaPlayerService and
     * AudioPolicyService are never created, "media.audio_policy" is never registered and every client sits in
     * "ServiceManager: Waiting for service media.audio_policy...". berlinOsalGuard() only runs for the glibc build, so
     * this one was never touched. The receiver is not needed in the guest: its i2c transfers and GPIO calls report
     * success at once, so the init finishes instead of retrying. Libraries (and mediaserver itself, in case the driver is
     * linked statically) that mention the driver are patched once per image; the originals are kept in .aemu-parked.
     * The functions found are logged, so a build whose driver is stripped shows up in aemu.log instead of silently hanging.
     */
    private fun hdmiRxGuard() = runCatching {
        val stamp = File(root, ".aemu-hdmirx")
        // v2: the i2c stand-ins alone only silenced the retry loop (nothing printed, mediaserver still never got past
        // the receiver init), so the driver's init entry points are skipped as well; v1 images are redone
        if (stamp.isFile && runCatching { stamp.readText().trim() }.getOrNull() == "2") return@runCatching
        val markers = listOf("Avc275x", "kg2h_gpio", "twsi_fp").map { it.toByteArray(Charsets.ISO_8859_1) }
        fun mentions(d: ByteArray) = markers.any { m -> indexOf(d, m) >= 0 }
        val wanted = { n: String -> n in HDMIRX_NOOPS || n in HDMIRX_INIT || n.startsWith("Avc275x_i2c") }
        val candidates = ArrayList<File>()
        for (dir in listOf("system/lib", "system/vendor/lib", "vendor/lib", "lib"))
            File(root, dir).listFiles()?.filterTo(candidates) { it.isFile && !isLink(it) && it.name.endsWith(".so") }
        for (n in listOf("mediaserver", "av_settings")) File(root, "system/bin/$n").takeIf { it.isFile && !isLink(it) }?.let { candidates += it }
        var patched = 0
        var seen = 0
        for (lib in candidates) {
            if (lib.length() < 4096 || lib.length() > 30L shl 20) continue
            val d = runCatching { lib.readBytes() }.getOrNull() ?: continue
            if (d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte() || !mentions(d)) continue
            seen++
            val rel = lib.relativeTo(root).path
            val found = ElfPatch.symbolsMatching(d, true, wanted)?.keys.orEmpty()
            if (found.isEmpty()) {
                val like = ElfPatch.symbolsMatching(d, true) { Regex("(?i)avc275x|kg2h|hdmi_?rx").containsMatchIn(it) }?.keys.orEmpty()
                log("HDMI RX: $rel mentions the receiver driver but exports none of its i2c/gpio functions" +
                    if (like.isEmpty()) " (stripped)" else "; candidates: ${like.take(80).joinToString()}")
                continue
            }
            val keep = File(root, "system/.aemu-parked/" + rel.replace("/", "#"))
            if (!keep.exists()) { keep.parentFile?.mkdirs(); lib.copyTo(keep) }
            val n = ElfPatch.returnZeroMatching(lib, true, wanted)
            patched += n
            log("HDMI RX: $rel patched ($n of ${found.size} func.: ${found.joinToString()})")
        }
        if (seen > 0 && patched == 0) log("HDMI RX: driver functions already patched or not found in $seen file(s)")
        stamp.writeText("2\n")
    }.onFailure { log("HDMI RX: guard failed: ${it.message}") }

    /**
     * KitKat (Tegra) SurfaceFlinger dereferences the NULL HAL device (hwcomposer is parked) right after
     * "Screen acquired" and dies with SIGSEGV at +0x1e77e (see [ElfPatch.skipNullDeviceHook]).
     */
    private fun surfaceFlingerGuard() = runCatching {
        val lib = File(root, "system/lib/libsurfaceflinger.so")
        if (lib.isFile) {
            val keep = File(root, "system/.aemu-parked/system#lib#libsurfaceflinger.so")
            if (!keep.exists()) { keep.parentFile?.mkdirs(); lib.copyTo(keep) }
            if (ElfPatch.skipNullDeviceHook(lib) > 0) log("libsurfaceflinger: the missing HAL device hook is skipped")
            // Eclair/Froyo: a never-drawn layer (the Launcher's all-apps RenderScript SurfaceView) is cleared to opaque black over the whole home screen
            if (img.api <= 8 && ElfPatch.skipUndrawnLayerClear(lib) > 0) log("libsurfaceflinger: a layer that was never drawn is no longer cleared to black")
        }
    }.onFailure { log("libsurfaceflinger: patch failed: ${it.message}") }

    /**
     * Cupcake..Froyo (API <= 8): libpixelflinger generates its scanline code into malloc()ed heap and runs it after
     * cacheflush(); qemu-user refuses to execute the non-PROT_EXEC page, so the first menu / window animation killed
     * SurfaceFlinger (SIGSEGV with PC in an rw-p mapping) and, with it, system_server and zygote. libaemushim's
     * cacheflush() fixes that, but the linker of those releases has no LD_PRELOAD (added in 2.3), so the shim is never
     * loaded there. Make the libc cacheflush() do the mprotect itself (see [ElfPatch.execCacheflush]).
     */
    private fun executableCodeCache() = runCatching {
        if (img.api > 8) return@runCatching
        val lib = File(root, "system/lib/libc.so")
        if (!lib.isFile) return@runCatching
        val keep = File(root, "system/.aemu-parked/system#lib#libc.so")
        if (!keep.exists()) { keep.parentFile?.mkdirs(); lib.copyTo(keep) }
        if (ElfPatch.execCacheflush(lib) > 0) log("libc: cacheflush() makes generated code executable (no LD_PRELOAD before 2.3)")
    }.onFailure { log("libc: cacheflush patch failed: ${it.message}") }

    /**
     * A failed Surface.unlockCanvasAndPost() throws IllegalArgumentException; on a system_server thread nothing
     * catches it, system_server kills itself and zygote exits (see ElfPatch.unlockAndPostNeverFails).
     */
    private fun surfaceGuard() = runCatching {
        val lib = File(root, "system/lib/libgui.so")
        if (lib.isFile) {
            val keep = File(root, "system/.aemu-parked/system#lib#libgui.so")
            if (!keep.exists()) { keep.parentFile?.mkdirs(); lib.copyTo(keep) }
            if (ElfPatch.unlockAndPostNeverFails(lib) > 0) log("libgui: a failed frame post no longer kills the caller")
        }
    }.onFailure { log("libgui: patch failed: ${it.message}") }

    /**
     * Google Glass (XE): GlassSystemServer creates the DMP gesture detector inside system_server's ServerThread.
     * DMPManager's constructor calls MPLSensor::getInstance() (libinvensense_hal), which cond-waits on MPLSensor::mWait
     * until the Invensense sensors HAL has constructed the MPLSensor singleton. installSensorHal() replaces that
     * HAL with the host one, so nobody ever builds it, the wait has no timeout and boot stops right after
     * "GlassSystemServer: Starting gesture service." (futex on libinvensense_hal's mWait, no further SystemServer steps).
     * Make getInstance() return null at once and report the DMP as unavailable, so Glass uses the software
     * (sensor-based) gesture detectors it already builds; a null MPLSensor is never dereferenced then.
     */
    private fun glassGestureGuard() = runCatching {
        fun patch(name: String, funcs: Set<String>, label: String) {
            val lib = File(root, "system/lib/$name")
            if (!lib.isFile) return
            val keep = File(root, "system/.aemu-parked/system#lib#$name")
            if (!keep.exists()) { keep.parentFile?.mkdirs(); lib.copyTo(keep) }
            val n = ElfPatch.returnZero(lib, funcs)
            if (n > 0) log("$label ($n func.)")
        }
        patch("libinvensense_hal.so", setOf("_ZN9MPLSensor11getInstanceEv"),
            "glass: MPLSensor::getInstance() no longer waits for the Invensense HAL")
        patch("libglassgesture.so",
            setOf("_ZNK5glass10DMPManager22isDMPGesturesSupportedEv", "_ZN5glass10DMPManager27blockUntilGestureRecognizedEPNS_10DMPGestureE"),
            "glass: DMP gestures reported unavailable")
    }.onFailure { log("glass: gesture patch failed: ${it.message}") }

    /**
     * Android 1.x has no real modem here: the framework's SimulatedCommands answers sendSMS with a plain
     * RuntimeException("Unimplemented"), and SMSDispatcher.handleSendComplete casts every send error to CommandException
     * (ClassCastException at SMSDispatcher.java:381), which kills com.android.phone. T-Mobile's MyFaves sends a
     * registration SMS to 453 each time the phone process starts, so the phone crashed, was restarted, MyFaves sent
     * again, and the system gave up with "com.android.phone has crashed too many times". An SMS can never be delivered
     * here anyway, so the apps that send one on their own are set aside (the stock files are kept in system/.aemu-parked).
     */
    private fun parkAutoSmsApps() = runCatching {
        if (img.api > 4) return@runCatching
        val parked = File(root, "system/.aemu-parked").apply { mkdirs() }
        val marker = "com.tmobile.myfaves"
        if (TEMP_UNPARK_MYFAVES) {
            // TEMPORARY experiment: TwLauncher needs the "myfaves" provider (NPE in MyFavesClientStatusReceiver.getStatus
            // without it). Put MyFaves back and do not park it; watch whether com.android.phone crash-loops again.
            var restored = 0
            for (f in parked.listFiles() ?: emptyArray()) {
                val n = f.name
                if (!(n.startsWith("system#app#") || n.startsWith("system#priv-app#"))) continue
                if (!(n.endsWith(".apk") || n.endsWith(".odex"))) continue
                val isMyFaves = n.contains("myfaves", ignoreCase = true) || (n.endsWith(".apk") && runCatching {
                    java.util.zip.ZipFile(f).use { z ->
                        val b = z.getEntry("AndroidManifest.xml")?.let { e -> z.getInputStream(e).use { it.readBytes() } } ?: return@use false
                        val u = marker.toByteArray(Charsets.UTF_16LE); val a = marker.toByteArray()
                        fun has(h: ByteArray, p: ByteArray): Boolean { outer@ for (i in 0..h.size - p.size) { for (k in p.indices) if (h[i + k] != p[k]) continue@outer; return true }; return false }
                        has(b, u) || has(b, a)
                    }
                }.getOrDefault(false))
                if (!isMyFaves) continue
                val dst = File(root, n.replace('#', '/'))
                if (dst.exists()) continue
                if (f.renameTo(dst)) restored++ else log("sms: could not restore ${f.name}")
            }
            if (restored > 0) log("sms: MyFaves restored ($restored file(s)) for the launcher's myfaves provider (temporary)")
            return@runCatching
        }
        val utf16 = marker.toByteArray(Charsets.UTF_16LE)
        var moved = 0
        for (dir in listOf("system/app", "system/priv-app")) {
            val apps = File(root, dir).listFiles { f -> f.isFile && f.name.endsWith(".apk") } ?: continue
            for (apk in apps) {
                val hit = apk.name.contains("myfaves", ignoreCase = true) || runCatching {
                    java.util.zip.ZipFile(apk).use { z ->
                        val e = z.getEntry("AndroidManifest.xml") ?: return@use false
                        val b = z.getInputStream(e).use { it.readBytes() }
                        fun has(h: ByteArray, n: ByteArray): Boolean { outer@ for (i in 0..h.size - n.size) { for (k in n.indices) if (h[i + k] != n[k]) continue@outer; return true }; return false }
                        has(b, utf16) || has(b, marker.toByteArray())
                    }
                }.getOrDefault(false)
                if (!hit) continue
                for (f in listOf(apk, File(apk.path.removeSuffix(".apk") + ".odex"))) {
                    if (!f.isFile) continue
                    val dst = File(parked, f.relativeTo(root).path.replace('/', '#'))
                    if (dst.exists()) dst.delete()
                    if (f.renameTo(dst)) moved++ else log("sms: could not set aside ${f.name}")
                }
                File(root, "data/dalvik-cache/" + apk.relativeTo(root).path.replace('/', '@') + "@classes.dex").delete()
            }
        }
        if (moved > 0) log("sms: MyFaves set aside ($moved file(s)); it sent an SMS at every start and the simulated modem cannot send ones (com.android.phone crash loop)")
    }.onFailure { log("sms: could not set aside the auto-SMS app: ${it.message}") }

    /**
     * Glass (4.0.x): the Glass Bluetooth app (com.google.glass.bluetooth) cannot work in the guest. Its
     * GlassBluetoothService opens an RFCOMM server socket (HandsFreeProfile, channel 13), the guest cannot create
     * AF_BLUETOOTH sockets under the host app (EACCES), the listener stays null and AsyncBluetoothServerSocket's
     * AcceptThread dies with an NPE; the app restarts and crashes again, so "Bluetooth has stopped" covers the UI.
     * Disabling only the service is not enough: CompanionLocationService in the same app then crashes because it
     * cannot bind to it. The APK (and its odex) is set aside in /system/.aemu-parked instead and PackageManager drops
     * the missing system app, like the backup transport in disableStallingPackages().
     */
    private fun glassBluetoothPark() = runCatching {
        if (img.api >= 16) return@runCatching
        val parked = File(root, "system/.aemu-parked").apply { mkdirs() }
        var moved = 0
        for (dir in listOf("system/app", "system/priv-app")) {
            val apk = File(root, "$dir/GlassBluetooth.apk")
            for (f in listOf(apk, File(apk.path.removeSuffix(".apk") + ".odex"))) {
                if (!f.isFile) continue
                val dst = File(parked, f.relativeTo(root).path.replace('/', '#'))
                if (dst.exists()) dst.delete()
                if (f.renameTo(dst)) moved++ else log("glass: could not park ${f.name}")
            }
        }
        if (moved > 0) {
            File(root, "data/dalvik-cache/system@app@GlassBluetooth.apk@classes.dex").delete()
            log("glass: Bluetooth app parked ($moved file(s)); RFCOMM sockets are unavailable in the guest")
        }
    }.onFailure { log("glass: could not park the Bluetooth app: ${it.message}") }

    private fun selinuxOff() = runCatching {
        val lib = File(root, "system/lib/libselinux.so")
        if (lib.isFile) {
            // keep the stock file so that a later delta OTA can still rebuild the stock system image
            val keep = File(root, "system/.aemu-parked/system#lib#libselinux.so")
            if (!keep.exists()) { keep.parentFile?.mkdirs(); lib.copyTo(keep) }
            val n = ElfPatch.returnZero(lib, setOf("is_selinux_enabled", "is_selinux_mls_enabled"))
            if (n > 0) log("SELinux: libselinux reports disabled ($n func.)")
            // A firmware with no 64-bit side runs the firmware's own 32-bit installd. With no SELinux in the guest its
            // restorecon / setfilecon calls end in ENOSYS ("Failed before getfilecon ... Function not implemented",
            // "Failed to restorecon"), installd then removes the app's /data/user_de/0/<pkg> directory again, and the first
            // system provider (SettingsProvider: settings.db, "Data directory doesn't exist") cannot start, so
            // system_server dies and zygote goes with it. There are no labels to apply: the calls succeed and do nothing.
            if (isPure32Tree(root)) {
                val r = ElfPatch.returnZero(lib, setOf("selinux_android_restorecon", "selinux_android_restorecon_pkgdir",
                    "selinux_android_setfilecon", "selinux_android_setcon", "selinux_android_setcontext"))
                if (r > 0) log("SELinux: restorecon/setfilecon of libselinux do nothing ($r func.)")
                xattrNoop()
            }
        }
    }.onFailure { log("SELinux: failed to patch libselinux: ${it.message}") }

    /**
     * The phone's kernel answers every extended-attribute syscall with ENOSYS, and the 32-bit installd of a firmware with
     * no 64-bit side treats that as fatal: "Failed before getfilecon ... Function not implemented", it deletes
     * /data/user_de/0/<pkg> for every package, SettingsProvider has no database directory and system_server dies.
     * LD_PRELOAD of libaemuxattr.so does not reach these programs on the native engine (its runner does not pass the
     * guest environment on), so the guest libc's own wrappers are made to succeed: a get reports a one-byte value, a set
     * does nothing. Nothing in the guest relies on labels or user.* attributes here (SELinux is off).
     */
    private fun xattrNoop() = runCatching {
        val libc = File(root, "system/lib/libc.so")
        if (!libc.isFile) return@runCatching
        // a set does nothing. A get must report a NON-EMPTY value: libselinux turns an empty attribute into errno
        // ENOTSUP ("Operation not supported on transport endpoint") and installd's lgetfilecon() then fails again.
        // One byte is enough: libselinux hands the caller a zero-filled buffer, so the label reads as "".
        val sets = ElfPatch.returnZero(libc, setOf("setxattr", "lsetxattr", "fsetxattr"))
        val gets = ElfPatch.returnValue(libc, setOf("getxattr", "lgetxattr", "fgetxattr"), 1)
        if (sets + gets > 0) log("xattr: libc get/set xattr calls succeed without the kernel (${sets + gets} func.)")
    }.onFailure { log("xattr: failed to patch libc: ${it.message}") }

    private fun qtaguid() = runCatching {
        val d = File(root, "data/.aemu_qtaguid").apply { mkdirs() }
        d.setReadable(true, false); d.setExecutable(true, false)
        val files = mapOf(
            "stats" to "idx iface acct_tag_hex uid_tag_int cnt_set rx_bytes rx_packets tx_bytes tx_packets " +
                "rx_tcp_bytes rx_tcp_packets rx_udp_bytes rx_udp_packets rx_other_bytes rx_other_packets " +
                "tx_tcp_bytes tx_tcp_packets tx_udp_bytes tx_udp_packets tx_other_bytes tx_other_packets\n",
            "iface_stat_fmt" to "ifname total_skb_rx_bytes total_skb_rx_packets total_skb_tx_bytes total_skb_tx_packets\n",
            "iface_stat_all" to "",
        )
        for ((n, text) in files) File(d, n).apply { writeText(text); setReadable(true, false) }
        // the native arm64 engine shows the image's own /proc files to the guest: the same empty tables in place,
        // with the control file whose presence is what "the kernel does traffic accounting" means to the framework
        if (engine == Engine.N64 && kernel.trafficAccounting) {
            val p = File(root, "proc/net/xt_qtaguid").apply { mkdirs() }
            for ((n, text) in files + ("ctrl" to "")) File(p, n).writeText(text)
            File(root, "proc/net/xt_quota").mkdirs()           // netd writes its data-usage alert threshold here
            File(root, "proc/net/xt_quota/globalAlert").writeText("")
        }
        if (engine == Engine.N64) for ((n, text) in kernel.procFiles) File(root, "proc/$n").let {
            it.parentFile?.mkdirs()
            if (n.endsWith(".gz")) {            // (the kernel's configuration: the text, gzipped as the kernel gives it)
                val gz = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { z -> z.write(text.toByteArray()) } }.toByteArray()
                if (!it.isFile || !it.readBytes().contentEquals(gz)) it.writeBytes(gz)
            } else if (!it.isFile || it.readText() != text) it.writeText(text)
        }
    }

    private fun assetSize(a: String): Long = runCatching { eaSize(a) }.getOrDefault(-1L)
    private fun installAsset(a: String, f: File) {
        if (f.isFile && !isLink(f) && f.length() == assetSize(a)) return
        f.delete()
        eaOpen(a).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        f.setReadable(true, false); f.setExecutable(true, false)
    }
    /** the open GLES bridge for the 32-bit engine is asked for (file "glopen" in the image's directory) */
    private fun openGl32(): Boolean = engine == Engine.KK && File(paths.dir, "glopen").isFile &&
        assetSize(OPEN_GL) > 0

    private fun splitSize(): Long = runCatching { eaSize("engines/kk/libGLES_split.so") }.getOrDefault(-1L)

    private fun makeDataDirs() {
        var made = 0
        val rcs = root.listFiles { f -> f.isFile && f.name.endsWith(".rc") }?.map { f ->
            runCatching { f.readText() }.getOrDefault("")
        }.orEmpty()
        val dbdata = dbdataDirs(rcs, img.dirs,
            legacySamsung = img.api < 14 && img.brand.contains("samsung", true) || img.name.contains("samsung", true) && img.api < 14,
            alreadyThere = File(root, "dbdata").exists())
        val want = (DATA_DIRS + img.dirs.map { it.trim().removePrefix("/") } + dbdata).filter { it.isNotEmpty() && !it.startsWith("#") }
        for (d in want.distinct()) {
            // каталоги init в /dev, /sys, /proc, /acct не создаём — это подменяет qemu
            if (d.startsWith("proc") || d.startsWith("sys/") || d == "sys" || d.startsWith("acct")) continue
            val f = File(root, d)
            if (!f.isDirectory && f.mkdirs()) made++
            if (d in dbdata) { f.setReadable(true, false); f.setWritable(true, false); f.setExecutable(true, false) }
        }
        if (made > 0) log("created init directories: $made")
    }

    /**
     * /data and /data/data are made by mkdirs() (host mode 0700) and nothing owns them in the owners table, so the guest
     * sees root:root 0700 and no app uid can walk to /data/data/<pkg>: stat of an existing cache dir fails (getCacheDir
     * logs "Unable to create cache directory"), contacts2.db / gservices.db open with EACCES. Stock Android has both
     * as system:system 0771. Runs every boot so existing trees are repaired.
     */
    private fun fixDataRootAccess() = runCatching {
        val have = if (paths.owners.isFile) paths.owners.readLines().map { it.trim() }.toHashSet() else hashSetOf()
        val sb = StringBuilder()
        for (rel in listOf("data", "data/data")) {
            val f = File(root, rel)
            if (!f.isDirectory || isLink(f)) continue
            Os.chmod(f.absolutePath, 0x1F9) // 0771
            val st = Os.lstat(f.absolutePath)
            val row = String.format("%016x %016x %08x %08x", st.st_dev, st.st_ino, 1000, 1000)
            if (row !in have) sb.append(row).append('\n')
        }
        if (sb.isNotEmpty()) { paths.owners.appendText(sb.toString()); log("data: /data and /data/data set to system 0771") }
    }.onFailure { log("data: could not fix /data access: ${it.message}") }

    /**
     * MIUI on Android 5: /system/framework/miuisdk.jar and miuisystem.jar are links into /data/miui, and both are
     * on the BOOTCLASSPATH. The phone's own init fills /data/miui from /system/app at boot; without the files
     * dex2oat skips the two jars and aborts while building the boot image ("Check failed: method != NULL
     * android.content.MiuiIntent.<init>"), zygote never starts.
     */
    private fun miuiSdkFiles() {
        for ((jar, apk) in listOf("miuisdk" to "system/app/miui/miui.apk", "miuisystem" to "system/app/miuisystem/miuisystem.apk")) {
            val link = File(root, "system/framework/$jar.jar")
            val target = runCatching { Os.readlink(link.absolutePath) }.getOrNull() ?: continue
            if (!target.endsWith("data/miui/$jar.apk")) continue
            val src = File(root, apk).takeIf { it.isFile } ?: File(root, "system/app/${apk.substringAfterLast('/')}").takeIf { it.isFile } ?: continue
            val dst = File(root, "data/miui/$jar.apk")
            if (dst.isFile && dst.length() == src.length()) continue
            runCatching {
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                log("MIUI: /data/miui/$jar.apk restored from /$apk")
            }
        }
    }

    private fun makeUserZeroLink() {
        if (img.api < 14) return // ICS installd creates /data/user/0 too; an absolute link points at the host /data
        // 7.0+: vold prepares the device-encrypted per-user dirs; without it installd cannot create app DE storage
        if (img.api >= 24) for (d in listOf("data", "user_de/0", "misc_ce/0", "misc_de/0", "system_ce/0", "system_de/0", "media/0", "misc/profiles/cur/0", "misc/profiles/ref"))
            File(root, "data/$d").apply { mkdirs() }.let { dir ->
                // every parent up to data/ must stay traversable for app uids as well
                var p: File? = dir
                while (p != null && p != File(root, "data")) { makeTraversable(p); p = p.parentFile }
            }
        val user = File(root, "data/user").apply { mkdirs() }
        // The guest checks permissions from the host mode bits plus the owners table, and nothing owns this directory
        // there (root), while mkdirs() leaves it 0700. Every app then fails to resolve /data/user/0/<pkg> (EACCES):
        // contacts2.db cannot be opened (android.process.acore), gsf cannot create app_sslcache / open gservices.db
        // (com.google.process.gapps) and the dialer dies querying the contacts provider. Runs on every boot so trees
        // created with the old mode are repaired too.
        makeTraversable(user)
        val zero = File(user, "0")
        val cur = runCatching { Os.readlink(zero.absolutePath) }.getOrNull()
        if (cur == "../data") return
        runCatching {
            if (cur != null || zero.exists()) { if (zero.isDirectory && !isLink(zero)) wipe(zero) else zero.delete() }
            Os.symlink("../data", zero.absolutePath)
        }
    }

    private fun makeDevNodes() {
        File(root, "dev/input/event0").let { if (!it.isFile) { it.parentFile?.mkdirs(); it.createNewFile() } }
        File(root, "dev/socket").mkdirs()
        File(root, "dev/graphics").mkdirs()
        // pre-1.0: the compositor opens /dev/fb0, not /dev/graphics/fb0
        if (LegacyFirmware.ensureFbAlias(root)) log("legacy layout: dev/fb0 -> graphics/fb0")
        // a directory in the file's place: open() fails with EISDIR whatever the flags
        if (engine == Engine.N64 && kernel.noQemuPipe) File(root, "dev/qemu_pipe").let { if (!it.isDirectory) { it.delete(); it.mkdirs() } }
        AudioOut(paths, log).makeFifo()
        File(root, "dev/binder").takeIf { it.isDirectory }?.let { wipe(it) }
        File(root, "dev/hwbinder").takeIf { it.isDirectory }?.let { wipe(it) }
        File(root, "dev/vndbinder").takeIf { it.isDirectory }?.let { wipe(it) }
        for (n in listOf("binder", "hwbinder", "vndbinder", "ashmem")) {
            File(root, "dev/$n").let { if (!it.exists()) { it.parentFile?.mkdirs(); it.createNewFile() } }
        }
        File(root, "dev/cpuctl").takeIf { it.exists() }?.let { wipe(it) }
    }

    /**
     * Android 10–17 (API 29+): core libraries and runtimes (Bionic libc/libdl/libm, ART, ICU) are packaged in APEX
     * modules. In non-root container emulation without loopback APEX mounts, the dynamic linker and ART fail immediately
     * unless APEX directories and symlinks are populated.
     */
    /**
     * Android 10+: the runtime (the linker, the C library, ART) and other system modules live in APEX packages, which
     * apexd mounts at /apex/<module name>. A "flattened" package is a plain directory /system/apex/<name>[.debug|...].
     * The directory itself is moved to /apex/<module name> and a link is left where it was: the linker allows a
     * library by its real path, which has to start with /apex/ as under a mount (through a link at /apex it came
     * out as /system/apex/... and nothing could load libc). Packages that are file system images (*.apex) are not
     * unpacked yet.
     */
    private fun fixApex() {
        // (system_ext: where a generic system image keeps the VNDK snapshots of older vendor releases, and flattened
        // copies of the packages it also ships as files)
        val sources = listOf("system/apex", "system/system_ext/apex", "system/product/apex").map { File(root, it) }.filter { it.isDirectory }
        if (sources.isEmpty()) return
        val apexDir = File(root, "apex").apply { mkdirs() }
        // an earlier version of the engine built com.android.runtime and com.android.art here out of links to /system/lib
        // (a real module, moved or unpacked here, has its manifest)
        for (n in listOf("com.android.art", "com.android.runtime")) File(apexDir, n).let {
            if (it.isDirectory && !isLink(it) && !File(it, "apex_manifest.json").exists() && !File(it, "apex_manifest.pb").exists()) wipe(it)
        }
        for (arch in listOf("lib", "lib64")) File(root, "system/$arch/bionic").let { if (isLink(it) && runCatching { Os.readlink(it.path) }.getOrNull() == ".") Os.remove(it.path) }
        apexDir.listFiles()?.filter { isLink(it) && runCatching { Os.readlink(it.path) }.getOrNull()?.startsWith("../system/apex/") == true }?.forEach { Os.remove(it.path) }
        var moved = 0
        var unpacked = 0
        for (dir in sources) {
            val up = "../".repeat(dir.relativeTo(root).path.count { it == '/' } + 1)
            // 11+: packages as files, each a zip with a file system image (see ApexUnpacker)
            if (dir.listFiles()?.any { it.isFile && (it.name.endsWith(".apex") || it.name.endsWith(".capex")) } == true) unpacked += ApexUnpacker.unpackAll(root, dir, log)
            dir.listFiles()?.filter { it.isDirectory && !isLink(it) }?.forEach { pkg ->
                val name = moduleName(pkg)
                val home = File(apexDir, name)
                runCatching {
                    if (home.exists() || isLink(home)) return@forEach      // a second package of the same module: the first one stays
                    if (!pkg.renameTo(home)) error("cannot move")
                    Os.symlink("${up}apex/$name", pkg.path); moved++
                }.onFailure { log("apex $name: ${it.message}") }
            }
        }
        // 12: a generic image's flattened copies share libraries with /system through relative links
        // (lib64/libbase.so -> ../../../../lib64/libbase.so), which a move to /apex leaves pointing nowhere: they
        // are made to point at the same file from the new place (also for a package moved by an earlier version)
        var relinked = 0
        for (dir in sources) dir.listFiles()?.filter { isLink(it) }?.forEach { was ->
            val home = File(apexDir, runCatching { Os.readlink(was.path) }.getOrNull()?.substringAfterLast('/') ?: return@forEach)
            if (!home.isDirectory || isLink(home)) return@forEach
            fun walk(d: File) {
                d.listFiles()?.forEach { f ->
                    if (!isLink(f)) { if (f.isDirectory) walk(f); return@forEach }
                    val t = runCatching { Os.readlink(f.path) }.getOrNull() ?: return@forEach
                    if (t.startsWith("/") || File(d, t).exists()) return@forEach
                    val old = File(File(was.parentFile, home.name + "/" + d.relativeTo(home).path), t).normalize()
                    if (!old.exists() || !old.path.startsWith(root.path + "/")) return@forEach
                    runCatching { Os.remove(f.path); Os.symlink(old.relativeTo(d).path, f.path); relinked++ }
                }
            }
            walk(home)
        }
        // 12+: a module may hold libraries for the other ones (lib64/<library>/<hash>/<library>), which reach them
        // through /apex/sharedlibs - links apexd makes when it mounts such a module
        var shared = 0
        apexDir.listFiles()?.filter { it.isDirectory && !isLink(it) && it.name != "sharedlibs" }?.forEach { mod ->
            for (lib in listOf("lib", "lib64")) File(mod, lib).listFiles()?.filter { it.isDirectory && !isLink(it) && it.name.endsWith(".so") }?.forEach { d ->
                d.listFiles()?.filter { it.isDirectory }?.forEach { hash ->
                    val link = File(apexDir, "sharedlibs/$lib/${d.name}/${hash.name}")
                    if (link.exists() || isLink(link)) return@forEach
                    link.parentFile?.mkdirs()
                    runCatching { Os.symlink("../../../${mod.name}/$lib/${d.name}/${hash.name}", link.path); shared++ }
                }
            }
        }
        if (shared > 0) log("APEX shared libraries linked: $shared")
        if (relinked > 0) log("APEX links to /system set right: $relinked")
        if (moved > 0) log("APEX modules put in place: $moved")
        if (unpacked > 0) log("APEX packages unpacked: $unpacked")
    }

    /** the module a flattened package directory holds: the name in its manifest (json up to 10, protobuf from 11) */
    private fun moduleName(pkg: File): String {
        runCatching { Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(File(pkg, "apex_manifest.json").readText())?.groupValues?.get(1) }.getOrNull()?.let { return it }
        runCatching {
            val pb = File(pkg, "apex_manifest.pb").readBytes()
            if (pb.size > 2 && pb[0].toInt() == 0x0A && pb[1] in 1..127 && 2 + pb[1] <= pb.size) return String(pb, 2, pb[1].toInt(), Charsets.UTF_8)
        }
        return pkg.name.removeSuffix(".debug").removeSuffix(".release")
    }

    /**
     * A system image of 9+ carries the product (and later system_ext) partition inside as /system/product, and its
     * root directory links /product to it. The package manager scans /product/priv-app: without the link SystemUI,
     * the launcher and Settings do not exist for it and the boot never leaves the animation.
     */
    private fun rootLinks() {
        for (n in listOf("product", "system_ext")) {
            val link = File(root, n)
            if (engine == Engine.N64 && kernel.partitionsAtRoot) {
                val inSystem = File(root, "system/$n")
                if (isLink(inSystem) || !inSystem.isDirectory) continue
                if (isLink(link) || link.isDirectory && link.list().isNullOrEmpty()) link.delete()
                if (link.exists() || !inSystem.renameTo(link)) continue
                runCatching { Os.symlink("../$n", inSystem.path); log("/$n is a directory of the root, /system/$n links to it") }
                continue
            }
            if (!File(root, "system/$n").isDirectory || isLink(link)) continue
            // (a system-as-root image brings an empty directory there, the partition's mount point)
            if (link.isDirectory && link.list().isNullOrEmpty()) link.delete()
            if (link.exists()) continue
            runCatching { Os.symlink("system/$n", link.path); log("/$n -> /system/$n") }
        }
    }

    /**
     * Notes for the eBPF stand-in of the kernel (Kernel64.bpfMapNotes): one file per map of the firmware eBPF objects,
     * named as the map is pinned (map_<object>_<map>), holding its type, key size and value size. They are the first
     * three words of each definition in the "maps" section of the object; the symbols of that section name the maps.
     * 17: the section is ".android_maps", a definition carries the path it is pinned at (the note takes that name).
     * An object built for libbpf (*.bpf, 16+) has its maps in ".maps", described in BTF: see [btfMapNotes].
     */
    private fun bpfMapNotes() {
        if (engine != Engine.N64 || !kernel.bpfMapNotes) return
        val dir = File(root, "dev/.aemu-bpf")
        if (dir.isDirectory) return
        val tmp = File(root, "dev/.aemu-bpf.tmp").apply { deleteRecursively(); mkdirs() }
        val objects = (listOf(File(root, "system/etc/bpf")) + (File(root, "apex").listFiles()?.map { File(it, "etc/bpf") } ?: emptyList()))
            .flatMap { d -> d.walkTopDown().maxDepth(3).filter { it.isFile && (it.name.endsWith(".o") || it.name.endsWith(".bpf")) }.toList() }
        var n = 0
        for (o in objects) runCatching {
            val b = java.nio.ByteBuffer.wrap(o.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            if (b.getInt(0) != 0x464c457f || b.get(4).toInt() != 2) return@runCatching
            val shoff = b.getLong(0x28).toInt(); val shnum = b.getShort(0x3c).toInt(); val shstr = b.getShort(0x3e).toInt()
            fun sh(i: Int) = shoff + i * 64
            fun str(tab: Int, off: Int): String {
                var p = b.getLong(sh(tab) + 0x18).toInt() + off
                val sb = StringBuilder()
                while (b.get(p).toInt() != 0) sb.append(b.get(p++).toInt().toChar())
                return sb.toString()
            }
            if (o.name.endsWith(".bpf")) {
                val find = { name: String -> (0 until shnum).firstOrNull { str(shstr, b.getInt(sh(it))) == name } }
                val btf = find(".BTF") ?: return@runCatching
                val sec = find(".maps") ?: return@runCatching
                val symtab = (0 until shnum).firstOrNull { b.getInt(sh(it) + 4) == 2 } ?: return@runCatching
                val strtab = b.getInt(sh(symtab) + 0x28)
                val symOff = b.getLong(sh(symtab) + 0x18).toInt(); val symCount = (b.getLong(sh(symtab) + 0x20) / 24).toInt()
                val names = (0 until symCount).map { symOff + it * 24 }.filter { (b.getShort(it + 6).toInt() and 0xffff) == sec }.map { str(strtab, b.getInt(it)) }.toSet()
                val at = b.getLong(sh(btf) + 0x18).toInt(); val size = b.getLong(sh(btf) + 0x20).toInt()
                for ((name, note) in btfMapNotes(java.nio.ByteBuffer.wrap(b.array(), at, size).slice().order(java.nio.ByteOrder.LITTLE_ENDIAN), names)) {
                    val out = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    note.forEach { out.putInt(it) }
                    File(tmp, "map_${o.name.removeSuffix(".bpf")}_$name").writeBytes(out.array())
                    n++
                }
                return@runCatching
            }
            val maps = (0 until shnum).firstOrNull { str(shstr, b.getInt(sh(it))) == "maps" }
                ?: (0 until shnum).firstOrNull { str(shstr, b.getInt(sh(it))) == ".android_maps" } ?: return@runCatching
            val pinned = str(shstr, b.getInt(sh(maps))) == ".android_maps"
            val symtab = (0 until shnum).firstOrNull { b.getInt(sh(it) + 4) == 2 } ?: return@runCatching
            val strtab = b.getInt(sh(symtab) + 0x28)
            val symOff = b.getLong(sh(symtab) + 0x18).toInt(); val symCount = (b.getLong(sh(symtab) + 0x20) / 24).toInt()
            val mapsOff = b.getLong(sh(maps) + 0x18).toInt(); val mapsSize = b.getLong(sh(maps) + 0x20)
            val obj = o.name.removeSuffix(".o").substringBefore('@')
            for (i in 0 until symCount) {
                val s = symOff + i * 24
                if ((b.getShort(s + 6).toInt() and 0xffff) != maps) continue
                val name = str(strtab, b.getInt(s))
                val at = b.getLong(s + 8)
                if (name.isEmpty() || at + 12 > mapsSize) continue
                val note = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (k in 0 until 3) note.putInt(b.getInt(mapsOff + at.toInt() + k * 4))
                var file = "map_${obj}_$name"
                if (pinned) {       // struct bpf_map_def: 12 words, create_location[n], pin_location[n], name_idx
                    val size = b.getLong(s + 16)
                    val n = ((size - 52) / 2).toInt()
                    if (n <= 0 || at + size > mapsSize) continue
                    val path = StringBuilder()
                    var q = mapsOff + at.toInt() + 48 + n
                    val end = q + n
                    while (q < end && b.get(q).toInt() != 0) path.append(b.get(q++).toInt().toChar())
                    if (path.isNotEmpty()) file = path.substring(path.lastIndexOf('/') + 1)
                }
                File(tmp, file).writeBytes(note.array())
                n++
            }
        }
        if (tmp.renameTo(dir)) log("eBPF: $n map notes from ${objects.size} objects")
    }

    /**
     * Files the firmware lacks, supplied by the person: everything under <app external files>/guest-overlay-<image id>/
     * (and guest-overlay/ for all images) is copied into the guest tree at the same relative path, e.g.
     * guest-overlay-v87x75/vendor/lib/libms12.so. A file is replaced only when its size differs, so a boot costs nothing
     * once they are in place. (The Dolby MS12 libraries of the MediaTek audio HAL are not in every firmware dump: the
     * HAL's linker stops on "libms12.so not found" and audioserver waits for the HAL for ever.)
     */
    private fun guestOverlay() {
        val base = ctx.getExternalFilesDir(null) ?: return
        val rootPath = root.canonicalFile.toPath()
        var n = 0
        for (dir in listOf(File(base, "guest-overlay"), File(base, "guest-overlay-${img.id}"))) {
            if (!dir.isDirectory) continue
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                runCatching {
                    val rel = f.relativeTo(dir).path
                    val dst = File(root, rel).canonicalFile
                    if (!dst.toPath().startsWith(rootPath) || dst == root.canonicalFile) { log("overlay: $rel is outside the tree, skipped"); return@forEach }
                    if (dst.isFile && dst.length() == f.length()) return@forEach
                    dst.parentFile?.mkdirs()
                    f.copyTo(dst, true)
                    dst.setReadable(true, false); dst.setExecutable(true, false)
                    n++
                    log("overlay: $rel (${f.length()} bytes)")
                }.onFailure { log("overlay: ${f.name}: ${it.message}") }
            }
        }
        if (n > 0) log("overlay: $n files added to the guest tree")
    }

    /** The kernel policy version the framework compatibility matrices ask for becomes 0 (Kernel64.kernelPolicyUnread). */
    private fun kernelPolicyUnread() {
        if (engine != Engine.N64 || !kernel.kernelPolicyUnread) return
        val asked = Regex("<kernel-sepolicy-version>\\s*[1-9][0-9]*\\s*</kernel-sepolicy-version>")
        for (part in listOf("system", "system_ext", "product")) {
            for (f in File(root, "$part/etc/vintf").listFiles() ?: continue) {
                if (!f.isFile || !f.name.startsWith("compatibility_matrix") || !f.name.endsWith(".xml")) continue
                val text = runCatching { f.readText() }.getOrNull() ?: continue
                if (!asked.containsMatchIn(text)) continue
                runCatching { f.setWritable(true); f.writeText(asked.replace(text, "<kernel-sepolicy-version>0</kernel-sepolicy-version>")) }
                    .onSuccess { log("build check: ${f.name} no longer asks for a kernel policy version") }
            }
        }
    }

    /**
     * The aconfig flag storage (Kernel64.aconfigStorage), as aconfigd leaves it: /metadata/aconfig/maps/<container>.
     * package.map and .flag.map, /metadata/aconfig/boot/<container>.val and .info, copied from <partition>/etc/aconfig
     * and from each module's etc. A container is a partition or a module, by name.
     */
    private fun aconfigStorage() {
        if (engine != Engine.N64 || !kernel.aconfigStorage) return
        val maps = File(root, "metadata/aconfig/maps").apply { mkdirs() }
        val boot = File(root, "metadata/aconfig/boot").apply { mkdirs() }
        val sources = listOf("system", "system_ext", "product", "vendor").map { it to File(root, "$it/etc/aconfig") } +
            (File(root, "apex").listFiles()?.filter { it.isDirectory && it.name != "sharedlibs" }?.map { it.name to File(it, "etc") } ?: emptyList())
        var n = 0
        for ((container, dir) in sources) {
            val pkg = File(dir, "package.map"); val flag = File(dir, "flag.map"); val value = File(dir, "flag.val")
            if (!pkg.isFile || !flag.isFile || !value.isFile) continue
            val out = File(boot, "$container.val")
            if (out.isFile && out.length() == value.length() && out.lastModified() >= value.lastModified()) continue
            runCatching {
                pkg.copyTo(File(maps, "$container.package.map"), true)
                flag.copyTo(File(maps, "$container.flag.map"), true)
                File(dir, "flag.info").takeIf { it.isFile }?.copyTo(File(boot, "$container.info"), true)
                value.copyTo(out, true)
                for (f in listOf(File(maps, "$container.package.map"), File(maps, "$container.flag.map"), File(boot, "$container.info"), out)) f.setReadable(true, false)
                n++
            }.onFailure { log("aconfig $container: ${it.message}") }
        }
        if (n > 0) log("aconfig: the flag storage of $n containers")
    }

    /**
     * The maps of a libbpf object, from its BTF: a map is a variable of the given [names] whose type is a struct with
     * the members libbpf's macros make - "type", "key_size", "value_size" as pointers to an array of that many
     * elements, "key" and "value" as pointers to the type itself. Returns type, key size, value size per map.
     */
    private fun btfMapNotes(b: java.nio.ByteBuffer, names: Set<String>): Map<String, IntArray> {
        if (b.limit() < 24 || (b.getShort(0).toInt() and 0xffff) != 0xeb9f) return emptyMap()
        val hdr = b.getInt(4); val typeOff = hdr + b.getInt(8); val typeLen = b.getInt(12); val strOff = hdr + b.getInt(16)
        fun str(off: Int): String { val sb = StringBuilder(); var p = strOff + off; while (p < b.limit() && b.get(p).toInt() != 0) sb.append(b.get(p++).toInt().toChar()); return sb.toString() }
        val types = ArrayList<Int>().apply { add(-1) }      // id -> offset; id 0 is void
        var p = typeOff
        while (p + 12 <= typeOff + typeLen) {
            types.add(p)
            val info = b.getInt(p + 4); val vlen = info and 0xffff
            p += 12 + when ((info ushr 24) and 0x1f) { 1, 14, 17 -> 4; 3 -> 12; 4, 5, 15, 19 -> vlen * 12; 6, 13 -> vlen * 8; else -> 0 }
        }
        fun kind(id: Int) = if (id <= 0 || id >= types.size) 0 else (b.getInt(types[id] + 4) ushr 24) and 0x1f
        fun ref(id: Int) = b.getInt(types[id] + 8)
        fun sizeOf(id: Int, depth: Int = 0): Int = when (if (depth > 16) 0 else kind(id)) {
            1, 4, 5, 6, 16, 19 -> ref(id)
            2 -> 8
            3 -> sizeOf(b.getInt(types[id] + 12), depth + 1) * b.getInt(types[id] + 20)
            8, 9, 10, 11, 14, 18 -> sizeOf(ref(id), depth + 1)
            else -> 0
        }
        fun count(id: Int): Int = if (kind(id) == 2 && kind(ref(id)) == 3) b.getInt(types[ref(id)] + 20) else 0
        val out = HashMap<String, IntArray>()
        for (id in 1 until types.size) {
            if (kind(id) != 14) continue
            val name = str(b.getInt(types[id]))
            var t = ref(id)
            while (kind(t) in listOf(8, 9, 10, 11)) t = ref(t)
            if (name !in names || kind(t) != 4) continue
            val note = IntArray(3)
            for (m in 0 until (b.getInt(types[t] + 4) and 0xffff)) {
                val e = types[t] + 12 + m * 12
                val type = b.getInt(e + 4)
                when (str(b.getInt(e))) {
                    "type" -> note[0] = count(type)
                    "key_size" -> note[1] = count(type)
                    "value_size" -> note[2] = count(type)
                    "key" -> if (kind(type) == 2) note[1] = sizeOf(ref(type))
                    "value" -> if (kind(type) == 2) note[2] = sizeOf(ref(type))
                }
            }
            if (note[0] != 0) out[name] = note
        }
        return out
    }

    /** Version (field 2) of an apex_manifest.pb, or 0. */
    private fun apexVersion(f: File): Long {
        val d = runCatching { f.readBytes() }.getOrNull() ?: return 0
        var i = 0
        while (i < d.size) {
            val tag = d[i++].toInt() and 0xff
            var v = 0L; var s = 0
            fun varint(): Long { v = 0; s = 0; while (i < d.size) { val b = d[i++].toInt() and 0xff; v = v or ((b and 0x7f).toLong() shl s); s += 7; if (b < 0x80) break }; return v }
            when (tag and 7) {
                0 -> { val x = varint(); if (tag ushr 3 == 2) return x }
                2 -> { val n = varint().toInt(); i += n }
                else -> return 0
            }
        }
        return 0
    }

    /** True when the image's system library [lib] contains [marker] (a feature test on the firmware itself); no library: true. */
    private fun libKnows(lib: String, marker: String): Boolean {
        val f = listOf("system/lib64/$lib", "system/lib/$lib").map { File(root, it) }.firstOrNull { it.isFile } ?: return true
        val m = marker.toByteArray()
        val d = runCatching { f.readBytes() }.getOrNull() ?: return true
        outer@ for (i in 0..d.size - m.size) {
            for (j in m.indices) if (d[i + j] != m[j]) continue@outer
            return true
        }
        return false
    }

    private fun makeSysNodes() {
        val freq = File(root, "sys/devices/system/cpu/cpu0/cpufreq/stats/time_in_state")
        if (!freq.isFile) {
            freq.parentFile?.mkdirs()
            freq.writeText(listOf(384000, 594000, 810000, 1026000, 1242000, 1512000).joinToString("\n") { "$it 0" } + "\n")
        }
        // Samsung's com.samsung.bootsetting (BootBroadcastReceiver, BOOT_COMPLETED) writes the CPU governor and reads it back;
        // with no scaling_governor node the open fails (FileNotFoundException) and the receiver dies with an NPE at every boot.
        // qemu's -L prefix only redirects a path that exists in the tree, so these are real, writable regular files.
        runCatching {
            val cf = File(root, "sys/devices/system/cpu/cpu0/cpufreq").apply { mkdirs() }
            run { var d: File? = cf; while (d != null && d != root && d.path.startsWith(root.path)) { d.setReadable(true, false); d.setExecutable(true, false); d = d.parentFile } }
            for ((n, v) in listOf(
                "scaling_governor" to "ondemand",
                "scaling_available_governors" to "ondemand performance powersave userspace",
                "scaling_available_frequencies" to "384000 594000 810000 1026000 1242000 1512000",
                "scaling_min_freq" to "384000", "scaling_max_freq" to "1512000", "scaling_cur_freq" to "1512000",
                "cpuinfo_min_freq" to "384000", "cpuinfo_max_freq" to "1512000", "cpuinfo_cur_freq" to "1512000",
            )) {
                File(cf, n).let { f ->
                    if (!f.isFile || f.length() == 0L) f.writeText(v + "\n")
                    f.setReadable(true, false); f.setWritable(true, false)
                }
            }
        }.onFailure { log("sysfs: cannot create cpufreq nodes: ${it.message}") }
        val cpus = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val cpu = File(root, "sys/devices/system/cpu").apply { mkdirs() }
        for ((n, v) in listOf("present" to "0-${cpus - 1}", "possible" to "0-${cpus - 1}", "online" to "0-${cpus - 1}", "offline" to "")) {
            File(cpu, n).let { if (!it.isFile) it.writeText(v + "\n") }
        }
        // Dalvik's NetworkInterface.getByName() reads these files; without them the open falls through to the host's
        // /sys, which answers EACCES. The Nexus Q broker (IPUtils) then finds no loopback address and its thread dies,
        // which restarts the Tungsten apps in a loop.
        val lo = File(root, "sys/class/net/lo").apply { mkdirs() }
        run { var d: File? = lo; while (d != null && d != root && d.path.startsWith(root.path)) { d.setReadable(true, false); d.setExecutable(true, false); d = d.parentFile } }
        for ((n, v) in listOf("ifindex" to "1", "flags" to "0x9", "mtu" to "16436", "type" to "772", "operstate" to "unknown",
            "carrier" to "1", "address" to "00:00:00:00:00:00", "addr_len" to "6", "dev_id" to "0x0")) {
            runCatching {
                File(lo, n).let { f -> if (!f.isFile || f.length() == 0L) f.writeText(v + "\n"); f.setReadable(true, false) }
            }.onFailure { log("sysfs: cannot create /sys/class/net/lo/$n: ${it.message}") }
        }
        // one directory per core: apps size their thread pools by counting cpuN here (MIUI File Manager of 13 saw one
        // core, and its only pool thread waited for the tasks it had queued behind itself)
        for (n in 0 until cpus) File(cpu, "cpu$n/online").let { if (!it.isFile) { it.parentFile?.mkdirs(); it.writeText("1\n") } }
        if (engine == Engine.N64 && kernel.cgroup2Root) {
            File(root, "sys/fs/cgroup").mkdirs()
            for (d in kernel.cgroupUidParents) File(root, "sys/fs/cgroup/$d").mkdirs()
            // libprocessgroup's file: version 1, one controller {version 2, mounted, name[16], path[32]}
            // (15: the record gained max_activation_depth after the flags - 0, the engine has no controllers to switch on
            // in child groups - and netd leaves with "cgroup.rc file has invalid size, expected 68")
            // (early 15 builds, e.g. HyperOS 2, still have the 64-byte record: the reader of the file, libcgrouprc, knows the
            // MaxActivationDepth field only from the later ones on, so the record is sized by that, not by the API level)
            val depth = kernel.cgroupRcDepth && libKnows("libcgrouprc.so", "MaxActivationDepth")
            val b = java.nio.ByteBuffer.allocate(8 + (if (depth) 60 else 56)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.putInt(1).putInt(1).putInt(2).putInt(1)
            if (depth) b.putInt(0)
            b.put("cgroup2".toByteArray().copyOf(16)).put("/sys/fs/cgroup".toByteArray().copyOf(32))
            File(root, "dev/cgroup_info").let { it.mkdirs(); File(it, "cgroup.rc").writeBytes(b.array()) }
        }
        // owner the connectivity module expects on clatd's directory (the runner answers its stat with it):
        // clat from the 2025 module on, root before
        if (engine == Engine.N64) {
            val v = apexVersion(File(root, "apex/com.android.tethering/apex_manifest.pb"))
            File(root, "dev").mkdirs(); File(root, "dev/.aemu-clat-uid").writeText(if (v >= 360000000L) "1029" else "0")
        }
        if (engine == Engine.N64) for ((n, text) in kernel.sysFiles) File(root, "sys/$n").let {
            if (!it.exists()) { it.parentFile?.mkdirs(); it.writeText(text) }
        }
        val base = File(root, "sys/class/power_supply")
        val bat = mapOf(
            "battery/type" to "Battery", "battery/status" to "Full", "battery/health" to "Good",
            "battery/present" to "1", "battery/capacity" to "100", "battery/batt_vol" to "4200",
            "battery/voltage_now" to "4200000", "battery/batt_temp" to "250", "battery/temp" to "250",
            "battery/technology" to "Li-ion", "ac/type" to "Mains", "ac/online" to "1",
            "usb/type" to "USB", "usb/online" to "0",
        )
        for ((rel, v) in bat) File(base, rel).let { if (!it.isFile) { it.parentFile?.mkdirs(); it.writeText(v + "\n") } }
        healthdRelativeSysfs(base)
        personalityNoop()
        hostPathAlias()
        parkNfc()
        mainStackMaps()
        val power = File(root, "sys/power").apply { mkdirs() }
        for (n in listOf("state", "wake_lock", "wake_unlock", "autosleep")) File(power, n).let { if (!it.isFile) it.createNewFile(); it.setWritable(true, false) }
        // узлы питания, которые открывает libhardware_legacy именно этой прошивки (Samsung: dvfslock_ctrl…):
        // если хоть один не откроется, библиотека считает экран выключенным и система не принимает касания
        runCatching {
            val lib = File(root, "system/lib/libhardware_legacy.so")
            if (lib.isFile) {
                val text = String(lib.readBytes(), Charsets.ISO_8859_1)
                for (m in Regex("/sys/(power|android_power)/[a-z_0-9]+").findAll(text)) {
                    val rel = m.value.removePrefix("/")
                    if (rel.contains("wait_for_fb")) continue // handled below
                    File(root, rel).let { f -> if (!f.exists()) { f.parentFile?.mkdirs(); f.createNewFile(); f.setWritable(true, false) } }
                }
            }
        }
        // SurfaceFlinger's DisplayEventThread loops sleep→wake: missing nodes make it spin at 100% CPU (EBADF).
        // wake answers at once; sleep is a FIFO with no writer, so the read blocks forever and the screen stays on.
        // Android 1.x–2.x look in /sys/android_power, later builds in /sys/power: serve both, otherwise SurfaceFlinger
        // logs "Couldn't open /sys/android_power/wait_for_fb_sleep or wait_for_fb_wake" and the screen stays black.
        val androidPower = File(root, "sys/android_power").apply { mkdirs() }
        // nodes of the original power.c, created even when the library scan above found nothing (stripped/odd builds)
        for (n in listOf("acquire_partial_wake_lock", "acquire_full_wake_lock", "release_wake_lock", "request_state"))
            File(androidPower, n).let { f -> runCatching { if (!f.exists()) f.createNewFile(); f.setReadable(true, false); f.setWritable(true, false) } }
        for (dir in listOf(power, androidPower)) runCatching {
            File(dir, "wait_for_fb_wake").let { if (!it.isFile) it.writeText("awake") }
            val sleep = File(dir, "wait_for_fb_sleep")
            if (!sleep.exists()) android.system.Os.mkfifo(sleep.absolutePath, "666".toInt(8))
        }
        // libsuspend with early suspend (4.2/4.3, Samsung's 4.4) waits in autosuspend_enable() for that sleep event
        // under PowerManagerService's lock: the first screen timeout hung system_server. A state node it cannot open
        // for writing makes it fall back to autosleep, which does not wait
        runCatching {
            val lib = File(root, "system/lib/libsuspend.so")
            if (lib.isFile && contains(lib, "wait_for_fb_sleep")) File(power, "state").setWritable(false, false)
        }
        // подсветка экрана: LightsService пишет сюда яркость
        val bl = File(root, "sys/class/leds/lcd-backlight").apply { mkdirs() }
        File(bl, "brightness").let { if (!it.isFile) it.writeText("255\n") }
        File(bl, "max_brightness").let { if (!it.isFile) it.writeText("255\n") }
        // Samsung's flip cover sensor (Galaxy S4/S5...): unreadable means "cover closed" to TouchWiz, and it hides
        // the status bar, the lock screen and recents behind the S View cover screen
        if (img.brand.equals("Samsung", true) || img.skin.contains("TouchWiz", true)) {
            val key = File(root, "sys/class/sec/sec_key").apply { mkdirs() }
            File(key, "hall_detect").let { if (!it.isFile) it.writeText("OPEN\n") }
        }
        lowMemoryKillerNodes()
        procNetNodes()
        javaIfInet6Redirect()
        settingsCpuinfoRedirect()
        launcherProximityGuard()
        libRsUploadGuard()
        tungstenLedNodes()
        tungstenLedInitPatch()
        tungstenLedCountPatch()
        idmeSysNodes()
    }

    /**
     * Amazon (Kindle Fire) firmware: idme.jar is on the boot class path, so every Dalvik process (zygote, and with it
     * app_process services such as aemu-bt and aemu-sensorhub) loads libidme.so. Its init fopen()s
     * /sys/class/mmc_host/mmc0/mmc0:0001/shift for writing and never checks the result: with no such node it goes on to use
     * the NULL FILE* and libc segfaults on `fp->_flags` (SIGSEGV addr 0xc, pc libc+0x19508), so zygote exits with 139 and
     * the system never comes up. qemu's -L prefix only redirects a path that exists in the tree, so give the library that
     * node, and every other /sys path it names, as plain world-writable files.
     */
    private fun idmeSysNodes() = runCatching {
        val libs = IDME_LIBS.map { File(root, it) }.filter { it.isFile }
        if (libs.isEmpty()) return@runCatching // not an Amazon firmware
        val paths = LinkedHashSet<String>().apply { add(IDME_SHIFT_NODE) }
        for (lib in libs) if (lib.length() < 20_000_000) paths += idmeSysPaths(lib.readBytes())
        for (path in idmeNodesToCreate(paths)) runCatching {
            val f = File(root, path.removePrefix("/"))
            if (f.exists()) { f.setReadable(true, false); f.setWritable(true, false); return@runCatching }
            f.parentFile?.let { dir ->
                dir.mkdirs()
                var d: File? = dir
                while (d != null && d != root && d.path.startsWith(root.path)) { d.setReadable(true, false); d.setExecutable(true, false); d = d.parentFile }
            }
            f.writeText("0\n")
            f.setReadable(true, false); f.setWritable(true, false)
            log("idme: placeholder sysfs node $path")
        }.onFailure { log("idme: cannot create $path: ${it.message}") }
    }.onFailure { log("idme: sysfs node scan failed: ${it.message}") }

    /** ARMv7 /proc/cpuinfo in the layout of a Tegra K1 (Cortex-A15) tablet, 4 cores, ending with the board lines. */
    private val GUEST_CPUINFO: String = buildString {
        for (i in 0 until 4) {
            append("processor\t: $i\nmodel name\t: ARMv7 Processor rev 3 (v7l)\nBogoMIPS\t: 38.40\n")
            append("Features\t: swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt vfpd32 lpae evtstrm\n")
            append("CPU implementer\t: 0x41\nCPU architecture: 7\nCPU variant\t: 0x2\nCPU part\t: 0xc0f\nCPU revision\t: 3\n\n")
        }
        append("Hardware\t: Yellowstone\nRevision\t: 0000\nSerial\t\t: 0000000000000000\n")
    }

    /**
     * NetworkInterface.getByName() (Dalvik 4.x) opens /proc/net/if_inet6 for every interface; on this host the open
     * falls through to the real /proc and fails with EACCES, so the Nexus Q broker (IPUtils) gets a SocketException,
     * finds no loopback address and its thread dies (NPE in NetworkSetupSession.reloadNetworkState). BootReceiver
     * also reads /proc/version. Serve both from the tree.
     *
     * qemu's -L prefix only redirects a guest path when the file exists in the tree, so the nodes have to be real
     * regular files under real, traversable directories. A symlink or a directory left at one of these paths would
     * either send the write into the host /proc (EACCES, the guest keeps hitting the host file) or make the lookup
     * miss, so such leftovers are replaced. Modes are set explicitly instead of through File.setReadable(), and every
     * node is read back and logged, so run/aemu.log shows whether the stub is in place.
     */
    private fun procNetNodes() {
        val nodes = linkedMapOf(
            "proc/net/if_inet6" to "00000000000000000000000000000001 01 80 10 80       lo\n",
            "proc/version" to VmSettings.procVersion(img.settings),
            // Chinese "xhl" firmware: SpenService.checkVersion() writes to /proc/versioncheck, and Launcher.onCreate()
            // returns before setupViews() when that check fails, so onResume then dies on a null mWorkspace
            // (NullPointerException at Launcher.onResume, relaunched forever as the only HOME app)
            "proc/versioncheck" to "",
            // MediaTek netd opens the relative path "proc/cpuinfo" (GuestVm starts netd inside the guest root);
            // a missing file made fopen return NULL and netd crashed in feof(NULL) on every start
            "proc/cpuinfo" to GUEST_CPUINFO,
        )
        // nodes the guest writes to as well as reads
        val writable = setOf("proc/versioncheck")
        // directories the guest (a faked, non-owner uid) must be able to traverse: 0755
        for (rel in listOf("proc", "proc/net")) runCatching {
            val d = File(root, rel)
            if (isLink(d) || (d.exists() && !d.isDirectory)) wipe(d)
            if (!d.isDirectory && !d.mkdirs()) error("mkdirs failed")
            Os.chmod(d.path, 0b111_101_101)
        }.onFailure { log("procfs: cannot prepare /$rel: ${it.message}") }
        for ((rel, v) in nodes) runCatching {
            val f = File(root, rel)
            if (isLink(f) || f.isDirectory) wipe(f)
            f.parentFile?.mkdirs()
            if (!f.isFile || (rel !in writable && f.readText() != v)) f.writeText(v)
            // 0644: readable by any guest uid (0666 for the nodes the guest writes)
            Os.chmod(f.path, if (rel in writable) 0b110_110_110 else 0b110_100_100)
            val back = f.readText()
            val mode = Os.stat(f.path).st_mode and 0xfff
            if (rel !in writable && back != v) error("read-back mismatch (${back.length} bytes)")
            log("procfs: /$rel served from the tree (${Integer.toOctalString(mode)}, ${f.length()} bytes)")
        }.onFailure { log("procfs: cannot create /$rel: ${it.message}") }
    }

    /**
     * The real fix for the /proc/net/if_inet6 EACCES. Whatever qemu does with /proc paths, the guest's open of
     * /proc/net/if_inet6 still reached the host's /proc (EACCES) even with a correct tree file in place, so stop
     * depending on /proc: Dalvik's libcore has the path as a string constant, and the tree's /data is served like any
     * other tree file. [DexStringPatch] swaps the constant for /data/.aemu_ifinet, where the loopback line is put.
     * Dalvik (API 20 and below) only; ART has no such patchable constant.
     * Files patched: core.odex when the firmware has it; otherwise the dalvik-cache copy of core.jar's dex (so the
     * jar itself stays stock, which keeps delta OTAs and every other file's dependency data valid; on a deodexed
     * firmware the cache is created by the first boot, so the patch lands from the second boot on).
     * DeltaOta undoes the patch on a copy to match the OTA's source hashes, and this runs again at the next start.
     */
    private fun javaIfInet6Redirect() = runCatching {
        if (img.api > 20) return@runCatching
        val odex = File(root, "system/framework/core.odex").takeIf { it.isFile }
        val targets = if (odex != null) listOf(odex)
        else listOfNotNull(File(root, "data/dalvik-cache/system@framework@core.jar@classes.dex").takeIf { it.isFile })
        if (targets.isEmpty()) {
            if (File(root, "system/framework/core.jar").isFile) log("libcore: no core.odex or dalvik-cache copy yet; if_inet6 redirect follows after the first boot")
            return@runCatching
        }
        val node = File(root, "data/.aemu_ifinet")
        val text = "00000000000000000000000000000001 01 80 10 80       lo\n"
        node.parentFile?.mkdirs()
        if (isLink(node) || node.isDirectory) wipe(node)
        if (!node.isFile || node.readText() != text) node.writeText(text)
        Os.chmod(node.path, 0b110_100_100)
        for (f in targets) {
            val d = f.readBytes()
            when (DexStringPatch.apply(d)) {
                1 -> {
                    f.setWritable(true, true)
                    f.writeBytes(d)
                    log("libcore: ${f.name} now reads the interface list from /data/.aemu_ifinet")
                }
                0 -> {}
                else -> log("libcore: /proc/net/if_inet6 string not found in ${f.name}, left as is")
            }
        }
    }.onFailure { log("libcore: if_inet6 redirect failed: ${it.message}") }

    /**
     * Tango Settings > About: DeviceInfoSettings.getFormattedTNHWRevision() takes a substring after indexOf() over
     * /proc/cpuinfo and dies with StringIndexOutOfBoundsException (index -1) because the host's arm64 cpuinfo has no
     * Hardware/Revision/Serial lines. A cpuinfo file in the tree is not served for /proc (qemu keeps reading the host's
     * file, as with if_inet6), so the path constant inside Settings' dex is swapped for /data/.cpuinf, which holds a
     * Tegra-style cpuinfo ([GUEST_CPUINFO]). Settings only; other processes still see the host's file.
     * Odex when the firmware has one, otherwise the dalvik-cache copy (created by the first boot).
     */
    private fun settingsCpuinfoRedirect() = runCatching {
        if (img.api > 20) return@runCatching
        val targets = listOf(
            "system/priv-app/Settings.odex", "system/app/Settings.odex",
            "data/dalvik-cache/system@priv-app@Settings.apk@classes.dex",
            "data/dalvik-cache/system@app@Settings.apk@classes.dex",
        ).map { File(root, it) }.filter { it.isFile }
        if (targets.isEmpty()) return@runCatching
        val node = File(root, "data/.cpuinf")
        node.parentFile?.mkdirs()
        if (isLink(node) || node.isDirectory) wipe(node)
        if (!node.isFile || node.readText() != GUEST_CPUINFO) node.writeText(GUEST_CPUINFO)
        Os.chmod(node.path, 0b110_100_100)
        for (f in targets) {
            val d = f.readBytes()
            when (DexStringPatch.apply(d, "/proc/cpuinfo", "/data/.cpuinf")) {
                1 -> { f.setWritable(true, true); f.writeBytes(d); log("settings: ${f.name} now reads cpuinfo from /data/.cpuinf") }
                0 -> {}
                else -> log("settings: /proc/cpuinfo string not found in ${f.name}, left as is")
            }
        }
    }.onFailure { log("settings: cpuinfo redirect failed: ${it.message}") }

    /**
     * "xhl" firmware (NDLauncher): Launcher.onCreate() ends with getSensorList(TYPE_PROXIMITY).get(0), and the guest's
     * sensor HAL has no proximity sensor, so the list is empty and the launcher dies with IndexOutOfBoundsException
     * (Launcher.java:765) on every start; the system relaunches it forever as the only HOME app. [LauncherProximityPatch]
     * makes that lookup tolerate an empty list (onResume/onPause already skip a null sensor).
     * Odex when the firmware has one, otherwise the dalvik-cache copy (created by the first boot). Launcher apps whose
     * dex lacks the lookup are not touched, and nothing is logged for them.
     */
    private fun launcherProximityGuard() = runCatching {
        if (img.api > 20) return@runCatching
        val targets = ArrayList<File>()
        for (dir in listOf("system/app", "system/priv-app"))
            File(root, dir).listFiles()?.filterTo(targets) { it.isFile && it.name.endsWith(".odex") && it.name.contains("Launcher", true) }
        File(root, "data/dalvik-cache").listFiles()?.filterTo(targets) {
            it.isFile && it.name.contains("Launcher", true) && it.name.endsWith("@classes.dex")
        }
        for (f in targets) {
            val d = f.readBytes()
            when (LauncherProximityPatch.apply(d)) {
                LauncherProximityPatch.PATCHED -> {
                    f.setWritable(true, true); f.writeBytes(d)
                    log("launcher: ${f.name} no longer fails when the guest has no proximity sensor")
                }
                LauncherProximityPatch.UNEXPECTED -> log("launcher: ${f.name} looks up the proximity sensor, but not in the code this patch knows, left as is")
                else -> {}
            }
        }
    }.onFailure { log("launcher: proximity guard failed: ${it.message}") }

    /**
     * Eclair/Froyo libRS: Allocation::uploadToTexture() aborts (null write, SIGSEGV) when glGenTextures() gives no id, i.e.
     * when no GL context is current yet. The stock Launcher2 (AllAppsView) hits that when its first Surface has no window
     * (setSurface w h 0x0), so the launcher dies on every start and the system relaunches it forever.
     * [LibRsUploadPatch] makes that path return instead, as later AOSP does. Only Nexus One images older than Android 2.3
     * (API 9) are considered; their libRS is searched for the assert (whatever the firmware build), and one without it
     * is left alone and not logged.
     */
    private fun libRsUploadGuard() = runCatching {
        if (img.api >= 9 || !(img.model.contains("Nexus One", true) || img.name.contains("Nexus One", true))) return@runCatching
        for (dir in listOf("system/lib", "vendor/lib", "system/vendor/lib")) {
            val f = File(root, "$dir/libRS.so")
            if (!f.isFile || isLink(f)) continue
            val d = f.readBytes()
            when (LibRsUploadPatch.apply(d)) {
                LibRsUploadPatch.PATCHED -> {
                    f.setWritable(true, true); f.writeBytes(d)
                    log("RenderScript: ${f.name} no longer aborts when a texture is uploaded without a GL context")
                }
                LibRsUploadPatch.UNEXPECTED -> log("RenderScript: ${f.name} has the texture-upload assert, but not in the code this patch knows, left as is")
                else -> {}
            }
        }
    }.onFailure { log("RenderScript: upload guard failed: ${it.message}") }

    /**
     * Nexus Q (Tungsten): LEDService's LEDController.nativeInit() opens the LED ring driver and throws
     * "LEDController nativeInit failed" when it can't, so the service crashes and is restarted every 5 s. The node
     * name lives in the native library, so scan the *led* libraries (system/lib, app-lib, and inside the LED/health
     * APKs) for /dev/ paths and give each a world-writable placeholder file so the open succeeds.
     */
    private fun tungstenLedNodes() = runCatching {
        val re = Regex("/dev/[A-Za-z0-9_./-]{2,40}")
        val found = LinkedHashSet<String>()
        fun scan(bytes: ByteArray) { for (m in re.findAll(String(bytes, Charsets.ISO_8859_1))) found += m.value.trimEnd('.', '/') }
        val libs = ArrayList<File>()
        for (d in listOf("system/lib", "data/app-lib", "data/data/com.google.tungsten.ledservice/lib", "system/app", "system/priv-app")) {
            File(root, d).walkTopDown().maxDepth(4).filter { it.isFile && it.name.contains("led", true) }.forEach { libs += it }
        }
        for (f in libs) {
            if (f.name.endsWith(".so")) scan(f.readBytes())
            else if (f.name.endsWith(".apk")) java.util.zip.ZipFile(f).use { z ->
                for (e in z.entries()) if (e.name.startsWith("lib/armeabi") && e.name.endsWith(".so") && e.name.contains("led", true))
                    scan(z.getInputStream(e).use { it.readBytes() })
            }
        }
        val skip = setOf("/dev/null", "/dev/zero", "/dev/log", "/dev/input", "/dev/socket", "/dev/graphics", "/dev/binder", "/dev/ashmem", "/dev/random", "/dev/urandom", "/dev/tty")
        for (path in found) {
            if (path in skip || path.startsWith("/dev/log/") || path.startsWith("/dev/socket/")) continue
            val f = File(root, path.removePrefix("/"))
            if (f.exists()) continue
            f.parentFile?.mkdirs()
            f.writeBytes(ByteArray(4096))
            f.setReadable(true, false); f.setWritable(true, false)
            log("tungsten: placeholder LED node $path")
        }
        if (found.isNotEmpty()) log("tungsten: /dev paths found in LED libs: ${found.joinToString()}")
    }.onFailure { log("tungsten: LED node scan failed: ${it.message}") }

    /**
     * Nexus Q (Tungsten), the real cause of the LED crash loop: the /dev/leds placeholder makes open() succeed, but
     * TungstenLEDs::InitBuffer() then does ioctl(fd, 0x8001e206, &ledCount) on it. A regular file answers ENOTTY, so
     * InitBuffer returns -11, getInstance() logs "Failed to open handle to led driver or initBuffer", nativeInit throws
     * and the service dies and restarts. There is no LED driver to answer, so patch the Thumb code of InitBuffer:
     *   before: r1 = this+4; r4 = this; r0 = ioctl_wrapper(this, r1)      (bl, 4 bytes)
     *   after : r1 = 32;     r4 = this; this->ledCount(+4) = 32; r0 = 0
     * The rest (malloc of 3*(count+1) bytes for the RGB buffer, success return) stays untouched. 32 is the ring size
     * of the Nexus Q. Later ioctls (commit/set) keep failing with a logged error, which the JNI layer does not throw.
     */
    private fun tungstenLedInitPatch() = runCatching {
        val libs = ArrayList<File>()
        for (d in listOf("system/lib", "data/app-lib", "data/data/com.google.tungsten.ledservice/lib"))
            File(root, d).walkTopDown().maxDepth(4).filter { it.isFile && it.name == "libtungsten_led.so" }.forEach { libs += it }
        val name = "_ZN7android12TungstenLEDs10InitBufferEv"
        for (f in libs) {
            val sym = ElfPatch.symbols(f, setOf(name))?.get(name) ?: continue
            val off = sym and 0xffffffffffL
            fun hw(r: java.io.RandomAccessFile, at: Long): Int { r.seek(at); val a = r.read(); val b = r.read(); return a or (b shl 8) }
            java.io.RandomAccessFile(f, "rw").use { r ->
                if (hw(r, off) != 0xb510) { log("tungsten: InitBuffer prologue unexpected in ${f.name}, not patched"); return@use }
                if (hw(r, off + 2) == 0x2120) return@use // already patched
                // push {r4,lr}; adds r1,r0,#4; mov r4,r0; bl <ioctl wrapper>
                if (hw(r, off + 2) != 0x1d01 || hw(r, off + 4) != 0x4604 || hw(r, off + 6) != 0xf7ff || (hw(r, off + 8) and 0xd000) != 0xd000) {
                    log("tungsten: InitBuffer body unexpected in ${f.name}, not patched"); return@use
                }
                val patch = intArrayOf(0x2120, 0x4604, 0x7121, 0x2000) // movs r1,#32; mov r4,r0; strb r1,[r4,#4]; movs r0,#0
                r.seek(off + 2)
                for (h in patch) { r.write(h and 0xff); r.write(h shr 8) }
                log("tungsten: InitBuffer in ${f.name} no longer needs the LED ioctl (32 LEDs)")
            }
        }
    }.onFailure { log("tungsten: InitBuffer patch failed: ${it.message}") }

    /**
     * Second half of the Nexus Q LED fix. LEDService's JNI nativeInit() calls TungstenLEDs::getLedCount(&n) and
     * returns false (-> "LEDController nativeInit failed", crash loop) when it is non-zero. getLedCount is a thin
     * ioctl(fd, 0x8001e206, out) wrapper, which fails with ENOTTY on the /dev/leds placeholder file. Replace the
     * 8 bytes "ldr r0,[r0]; ldr r1,[pc,#8]; blx ioctl" with "movs r3,#32; strb r3,[r2]; movs r0,#0; nop", so it
     * reports 32 LEDs and success; the null-pointer check and the pop stay as they were.
     */
    private fun tungstenLedCountPatch() = runCatching {
        val libs = ArrayList<File>()
        for (d in listOf("system/lib", "data/app-lib", "data/data/com.google.tungsten.ledservice/lib"))
            File(root, d).walkTopDown().maxDepth(4).filter { it.isFile && it.name == "libtungsten_led.so" }.forEach { libs += it }
        val name = "_ZN7android12TungstenLEDs11getLedCountEPh"
        for (f in libs) {
            val off = (ElfPatch.symbols(f, setOf(name))?.get(name) ?: continue) and 0xffffffffffL
            fun hw(r: java.io.RandomAccessFile, at: Long): Int { r.seek(at); val a = r.read(); val b = r.read(); return a or (b shl 8) }
            java.io.RandomAccessFile(f, "rw").use { r ->
                if (hw(r, off + 12) == 0x2320) return@use // already patched
                // push {r4,lr}; mov r2,r1; cbnz r1; mvn r0,#21; b; ldr r0,[r0]; ldr r1,[pc,#8]; blx ioctl; pop
                if (hw(r, off) != 0xb510 || hw(r, off + 2) != 0x460a || hw(r, off + 4) != 0xb911 ||
                    hw(r, off + 12) != 0x6800 || hw(r, off + 14) != 0x4902 || hw(r, off + 16) != 0xf7ff) {
                    log("tungsten: getLedCount unexpected in ${f.name}, not patched"); return@use
                }
                r.seek(off + 12)
                for (h in intArrayOf(0x2320, 0x7013, 0x2000, 0xbf00)) { r.write(h and 0xff); r.write(h shr 8) }
                log("tungsten: getLedCount in ${f.name} reports 32 LEDs")
            }
        }
    }.onFailure { log("tungsten: getLedCount patch failed: ${it.message}") }

    /**
     * Some vendor ActivityManagers (Spreadtrum KitKat: ProcessList.updateOomLevels) read the kernel's
     * lowmemorykiller tunables and Integer.parseInt() them without checking for an empty read. The guest has
     * no such kernel module, so the read returned "" and system_server died with
     * "NumberFormatException: Invalid int: """, which looked like an endless reboot. Give those files
     * stock-shaped content (six oom_adj levels, minfree in 4 KB pages). Our GuestLmk does the real killing.
     */
    private fun lowMemoryKillerNodes() {
        val nodes = mapOf(
            "sys/module/lowmemorykiller/parameters/adj" to "0,1,2,4,9,15",
            "sys/module/lowmemorykiller/parameters/minfree" to "1536,2048,4096,5120,5632,6144",
            "proc/sys/vm/extra_free_kbytes" to "0",
        )
        for ((rel, v) in nodes) runCatching {
            val f = File(root, rel)
            if (!f.isFile || f.length() == 0L) {
                f.parentFile?.mkdirs()
                f.writeText(v + "\n")
            }
            f.setReadable(true, false)
            f.setWritable(true, false)
        }.onFailure { log("sysfs: cannot create /$rel: ${it.message}") }
    }

    /**
     * Своя таблица монтирования: прошивки (Samsung /efs, /preload…) читают /proc/mounts и, не найдя
     * раздел, пытаются смонтировать его сами — а seccomp телефона убивает за mount(). qemu отдаёт
     * гостю файлы из дерева поверх хостовых, поэтому root/proc/mounts видится как настоящий.
     */
    private fun makeProcMounts() {
        val mounts = LinkedHashMap<String, String>() // точка → строка
        fun add(dev: String, point: String, type: String, opts: String) { mounts.putIfAbsent(point, "$dev $point $type $opts 0 0") }
        add("rootfs", "/", "rootfs", "ro,relatime")
        add("tmpfs", "/dev", "tmpfs", "rw,nosuid,relatime,mode=755")
        add("devpts", "/dev/pts", "devpts", "rw,relatime,mode=600")
        add("proc", "/proc", "proc", "rw,relatime")
        add("sysfs", "/sys", "sysfs", "rw,relatime")
        add("none", "/acct", "cgroup", "rw,relatime,cpuacct")
        add("tmpfs", "/mnt/asec", "tmpfs", "rw,relatime,mode=755,gid=1000")
        add("tmpfs", "/mnt/obb", "tmpfs", "rw,relatime,mode=755,gid=1000")
        add("/dev/block/platform/aemu/by-name/system", "/system", "ext4", "ro,relatime")
        add("/dev/block/platform/aemu/by-name/userdata", "/data", "ext4", "rw,nosuid,nodev,noatime")
        add("/dev/block/platform/aemu/by-name/cache", "/cache", "ext4", "rw,nosuid,nodev,noatime")
        // точки монтирования из init*.rc и fstab прошивки
        val rcs = (root.listFiles()?.filter { it.isFile && (it.name.endsWith(".rc") || it.name.startsWith("fstab")) } ?: emptyList()) +
            listOfNotNull(File(root, "system/etc/vold.fstab").takeIf { it.isFile })
        for (f in rcs) runCatching {
            for (raw in f.readLines()) {
                val t = raw.trim().split(Regex("\\s+"))
                if (t.isEmpty() || t[0].startsWith("#")) continue
                when {
                    t[0] == "mount" && t.size >= 4 -> {
                        val point = t[3]
                        if (point.startsWith("/") && point != "/" && !point.startsWith("/proc") && !point.startsWith("/sys") && !point.startsWith("/dev"))
                            add(t[2].substringBefore('@').ifEmpty { "none" }, point, t[1], if (point == "/system") "ro,relatime" else "rw,relatime")
                    }
                    f.name.startsWith("fstab") && t.size >= 3 && t[0].startsWith("/dev") && t[1].startsWith("/") ->
                        add(t[0], t[1], t[2], "rw,relatime")
                }
            }
        }
        // карта памяти видна «смонтированной»
        add("/dev/block/vold/179:1", img.sdcardPath, "vfat", "rw,dirsync,nosuid,nodev,noexec,relatime,uid=1000,gid=1015,fmask=0702,dmask=0702")
        for (p in mounts.keys) if (p != "/" && !p.startsWith("/proc") && !p.startsWith("/sys") && !p.startsWith("/dev")) File(root, p.trimStart('/')).mkdirs()
        val f = File(root, "proc/mounts")
        f.parentFile?.mkdirs()
        val text = mounts.values.joinToString("\n", postfix = "\n")
        if (f.takeIf { it.isFile }?.readText() != text) f.writeText(text)
    }

    /** DNS fallback for native binaries, web engines and tools: /etc/resolv.conf and /system/etc/resolv.conf */
    /**
     * Old firmwares lack today's root certificates (ISRG, GlobalSign R46…): HTTPS sites failed with
     * CERT_AUTHORITY_INVALID (-202). The phone's own roots are added; the names are the same subject_hash_old.N.
     */
    private fun caCerts() = runCatching {
        val dst = File(root, "system/etc/security/cacerts")
        if (!dst.isDirectory) return@runCatching
        val src = listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts").map(::File).firstOrNull { it.isDirectory } ?: return@runCatching
        val have = dst.listFiles().orEmpty().mapNotNull { runCatching { it.readText() }.getOrNull() }.toHashSet()
        var added = 0
        for (f in src.listFiles().orEmpty()) {
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            if (text in have) continue
            val hash = f.name.substringBefore('.')
            val out = (0..9).map { File(dst, "$hash.$it") }.firstOrNull { !it.exists() } ?: continue
            out.writeText(text); out.setReadable(true, false); have += text; added++
        }
        if (added > 0) log("certificates: $added roots added from the phone")
    }

    private fun resolvConf() {
        val conf = "nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 9.9.9.9\n"
        runCatching {
            val f1 = File(root, "system/etc/resolv.conf")
            f1.parentFile?.mkdirs()
            if (!f1.exists() || f1.length() == 0L) f1.writeText(conf)
            val f2 = File(root, "etc/resolv.conf")
            f2.parentFile?.mkdirs()
            if (!f2.exists() || f2.length() == 0L) f2.writeText(conf)
        }
    }

    /**
     * Звук обслуживает наш HAL (один выход на динамик, 48 кГц), поэтому audio_policy.conf пишем
     * канонический: лишние выходы прошивок (hdmi с dynamic-параметрами, deep_buffer, a2dp, usb)
     * заставляют audio_policy спрашивать у HAL то, чего он не знает, и mediaserver падает.
     */
    private fun audioPolicy() {
        val cur = File(root, "system/etc/audio_policy.conf")
        val rom = File(root, "system/etc/audio_policy.conf.rom")
        if (!cur.isFile && !rom.isFile && img.api < 16) return
        runCatching {
            if (cur.isFile && !rom.isFile) cur.copyTo(rom)
            val text = """
                |# создано AEmulator: единственный модуль — звуковой HAL эмулятора
                |global_configuration {
                |  attached_output_devices AUDIO_DEVICE_OUT_SPEAKER
                |  default_output_device AUDIO_DEVICE_OUT_SPEAKER
                |  attached_input_devices AUDIO_DEVICE_IN_BUILTIN_MIC
                |}
                |
                |audio_hw_modules {
                |  primary {
                |    outputs {
                |      primary {
                |        sampling_rates 48000
                |        channel_masks AUDIO_CHANNEL_OUT_STEREO
                |        formats AUDIO_FORMAT_PCM_16_BIT
                |        devices AUDIO_DEVICE_OUT_SPEAKER
                |        flags AUDIO_OUTPUT_FLAG_PRIMARY
                |      }
                |    }
                |    inputs {
                |      primary {
                |        sampling_rates 8000|16000|44100|48000
                |        channel_masks AUDIO_CHANNEL_IN_MONO|AUDIO_CHANNEL_IN_STEREO
                |        formats AUDIO_FORMAT_PCM_16_BIT
                |        devices AUDIO_DEVICE_IN_BUILTIN_MIC
                |      }
                |    }
                |  }
                |}
                |""".trimMargin()
            if (cur.takeIf { it.isFile }?.readText() != text) cur.writeText(text)
        }.onFailure { log("audio: failed to write audio_policy.conf: ${it.message}") }
        // 8.0+ reads audio_policy_configuration.xml (odm, vendor, then system) and never the .conf: a phone's one
        // (deep buffer, offload, voice ports) makes the policy ask the engine's HAL for streams it cannot open, and
        // audioserver dies on the first sound. The same single output goes to vendor/etc, read before system/etc.
        val xml = File(root, "vendor/etc/audio_policy_configuration.xml")
        if (img.engine == Engine.N64 && kernel.audioPolicyXml && File(root, "system/etc/audio_policy_configuration.xml").isFile && File(root, "vendor/etc").isDirectory) runCatching {
            val own = File(root, "system/.aemu-parked/vendor#etc#audio_policy_configuration.xml")
            if (xml.isFile && !own.exists() && !xml.readText().contains("AEmulator")) { own.parentFile?.mkdirs(); xml.renameTo(own) }
            val text = """
                |<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                |<!-- AEmulator: the one module is the engine's audio HAL -->
                |<audioPolicyConfiguration version="1.0" xmlns:xi="http://www.w3.org/2001/XInclude">
                |    <globalConfiguration speaker_drc_enabled="false"/>
                |    <modules>
                |        <module name="primary" halVersion="2.0">
                |            <attachedDevices>
                |                <item>Speaker</item>
                |                <item>Built-In Mic</item>
                |            </attachedDevices>
                |            <defaultOutputDevice>Speaker</defaultOutputDevice>
                |            <mixPorts>
                |                <mixPort name="primary output" role="source" flags="AUDIO_OUTPUT_FLAG_PRIMARY">
                |                    <profile name="" format="AUDIO_FORMAT_PCM_16_BIT" samplingRates="48000" channelMasks="AUDIO_CHANNEL_OUT_STEREO"/>
                |                </mixPort>
                |                <mixPort name="primary input" role="sink">
                |                    <profile name="" format="AUDIO_FORMAT_PCM_16_BIT" samplingRates="8000,16000,44100,48000" channelMasks="AUDIO_CHANNEL_IN_MONO,AUDIO_CHANNEL_IN_STEREO"/>
                |                </mixPort>
                |            </mixPorts>
                |            <devicePorts>
                |                <devicePort tagName="Speaker" type="AUDIO_DEVICE_OUT_SPEAKER" role="sink"/>
                |                <devicePort tagName="Built-In Mic" type="AUDIO_DEVICE_IN_BUILTIN_MIC" role="source"/>
                |            </devicePorts>
                |            <routes>
                |                <route type="mix" sink="Speaker" sources="primary output"/>
                |                <route type="mix" sink="primary input" sources="Built-In Mic"/>
                |            </routes>
                |        </module>
                |    </modules>
                |    <xi:include href="/system/etc/audio_policy_volumes_drc.xml"/>
                |    <xi:include href="/system/etc/default_volume_tables.xml"/>
                |</audioPolicyConfiguration>
                |""".trimMargin()
            if (xml.takeIf { it.isFile }?.readText() != text) { xml.writeText(text); xml.setReadable(true, false) }
        }.onFailure { log("audio: failed to write audio_policy_configuration.xml: ${it.message}") }
        // AudioFlinger reads vendor/etc/audio_effects.conf before the system one. A vendor's list adds effects for its own
        // amplifier and DSP (Motorola's speaker protection crashed 7.1's audioserver on a null calibration, so AudioFlinger
        // was never published); the engine's HAL is a plain speaker, so the system's AOSP effects are used instead.
        val fx = File(root, "vendor/etc/audio_effects.conf")
        if (img.engine != Engine.N64 && fx.isFile && File(root, "system/etc/audio_effects.conf").isFile) runCatching {
            val own = File(root, "system/.aemu-parked/vendor#etc#audio_effects.conf")
            own.parentFile?.mkdirs()
            if (fx.renameTo(own)) log("audio: vendor audio_effects.conf parked, AOSP effects used")
        }
    }

    /** Кадровый буфер — обычный файл, qemu отвечает на ioctl FBIOGET_* от гостя. */
    private fun makeFb() {
        val s = img.settings
        val (w, h) = if (engine == Engine.GB) 480 to 800 else s.width to s.height
        // двойная буферизация: гость листает страницы через FBIOPAN_DISPLAY
        // qemu reports smem_len = two 32-bit pages (TWRP draws BGRA_8888); mmap past the file end would SIGBUS
        val need = w.toLong() * h * 4 * 2
        val f = paths.fb
        // the geometry file is rewritten every time: 540x960 and 960x540 (a resolution preset turned to landscape) need
        // the same number of bytes, so the size of fb0 alone does not tell that the guest's answer to FBIOGET_VSCREENINFO changed
        val geom = "$w $h\n"
        val geomFile = File(root, "dhd.fbgeom")
        val geomOk = runCatching { geomFile.readText() == geom }.getOrDefault(false)
        if (f.isFile && f.length() == need && geomOk) return
        f.parentFile?.mkdirs()
        RandomAccessFile(f, "rw").use { it.setLength(need) }
        runCatching { geomFile.writeText(geom) }
        log("framebuffer: ${w}x$h")
    }

    /**
     * Владельцы файлов приложений в /data/data: ядро телефона не даёт менять uid, поэтому qemu
     * подставляет их по таблице dhd.owners (dev, ino → uid, gid). Берём uid из packages.xml.
     */
    fun seedOwners() {
        val stamp = File(root, "dhd.owners.seeded")
        val done = if (stamp.isFile) stamp.readLines().filter { it.isNotBlank() }.toMutableSet() else mutableSetOf()
        val pkgXml = File(root, "data/system/packages.xml")
        val dataDir = File(root, "data/data")
        if (!pkgXml.isFile || !dataDir.isDirectory) return
        runCatching {
            val text = pkgXml.readText()
            val sharedUsers = HashMap<String, Int>()
            val suRe = Regex("<shared-user name=\"([^\"]+)\"[^>]*?\\suserId=\"(\\d+)\"")
            for (m in suRe.findAll(text)) {
                sharedUsers[m.groupValues[1]] = m.groupValues[2].toInt()
            }
            val sb = StringBuilder()
            var rows = 0
            var pkgs = 0
            val pkgRe = Regex("<package name=\"([^\"]+)\"[^>]*?>")
            for (m in pkgRe.findAll(text)) {
                val tag = m.value
                val name = m.groupValues[1]
                val uid = Regex("\\suserId=\"(\\d+)\"").find(tag)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("\\ssharedUserId=\"([^\"]+)\"").find(tag)?.groupValues?.get(1)?.let { sharedUsers[it] }
                    ?: continue
                val d = File(dataDir, name)
                if (!d.isDirectory) continue
                val key = "$name $uid"
                if (key in done) continue
                done.add(key)
                pkgs++
                val todo = ArrayDeque<File>().apply { add(d) }
                while (todo.isNotEmpty()) {
                    val f = todo.removeFirst()
                    runCatching {
                        f.setReadable(true, false)
                        f.setWritable(true, false)
                        if (f.isDirectory) f.setExecutable(true, false)
                    }
                    val st = runCatching { Os.lstat(f.absolutePath) }.getOrNull() ?: continue
                    sb.append(String.format("%016x %016x %08x %08x\n", st.st_dev, st.st_ino, uid, uid))
                    rows++
                    if (!OsConstants.S_ISLNK(st.st_mode) && f.isDirectory) f.listFiles()?.forEach { todo.add(it) }
                }
            }
            if (rows > 0) {
                paths.owners.appendText(sb.toString())
                log("file owners restored: $pkgs packages, $rows entries")
            }
            stamp.writeText(done.joinToString("\n", postfix = "\n"))
        }.onFailure { log("owners: ${it.message}") }
    }

    /** Пропустить миграцию PRE_BOOT_COMPLETED (4.x): под эмуляцией она занимает минуты. */
    fun skipPreBoot(props: PropService) {
        if (img.api < 14) return
        val f = File(root, "data/system/called_pre_boots.dat")
        val rel = props.get("ro.build.version.release") ?: img.release
        val code = props.get("ro.build.version.codename") ?: "REL"
        val incr = props.get("ro.build.version.incremental") ?: ""
        val fresh = runCatching {
            DataInputStream(f.inputStream().buffered()).use { i ->
                i.readInt() == 10000 && i.readUTF() == rel && i.readUTF() == code && i.readUTF() == incr
            }
        }.getOrDefault(false)
        if (fresh) return
        runCatching {
            f.parentFile?.mkdirs()
            DataOutputStream(f.outputStream().buffered()).use { o ->
                o.writeInt(10000); o.writeUTF(rel); o.writeUTF(code); o.writeUTF(incr); o.writeInt(0)
            }
        }
    }

    /** Экран не гаснет, данные «включены»: прямо в базе настроек (есть после первой загрузки). */
    private fun hasSonySetupFlow(): Boolean =
        File(root, "system/app/Initial-boot-setup.apk").isFile && File(root, "system/priv-app/SEMCSetupWizard.apk").isFile

    /** Called only before launching guest processes. Keep recovery copies of migrated data. */
    fun restoreSonySetupFlow() {
        if (!hasSonySetupFlow()) return
        val marker = File(paths.bin, "sony-setup-flow-v1")
        if (marker.isFile) return
        val db = File(root, "data/data/com.android.providers.settings/databases/settings.db")
        val restrictions = File(root, "data/system/users/0/package-restrictions.xml")
        if (!db.isFile || !restrictions.isFile) return // fresh imports already use stock defaults
        val prefs = File(root, "data/data/${SonySetupPolicy.PACKAGE}/shared_prefs/SetupWizard.xml")
        runCatching {
            val completed = prefs.isFile && SonySetupPolicy.completed(prefs.readText())
            if (!completed) {
                val xml = restrictions.readText()
                val restored = SonySetupPolicy.restorePackage(xml)
                // Only migrate a package disabled by the old compatibility policy.
                if (xml != restored) {
                    for (suffix in listOf("", "-wal", "-shm")) {
                        val source = File(db.path + suffix)
                        val backup = File(source.path + ".aemu-before-sony-setup-v1")
                        if (source.isFile && !backup.exists()) source.copyTo(backup)
                    }
                    val backup = File(restrictions.path + ".aemu-before-sony-setup-v1")
                    if (!backup.exists()) restrictions.copyTo(backup)
                    SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
                        d.beginTransaction()
                        try {
                            for ((table, name) in listOf("global" to "device_provisioned", "secure" to "user_setup_complete")) {
                                val cv = android.content.ContentValues().apply { put("value", "0") }
                                d.update(table, cv, "name=?", arrayOf(name))
                            }
                            d.setTransactionSuccessful()
                        } finally { d.endTransaction() }
                    }
                    restrictions.writeText(restored)
                    log("setup: restored Sony wizard and incomplete-setup flags; backups retained")
                }
            }
            marker.parentFile?.mkdirs()
            marker.writeText("Sony owns setup completion; migration checked\n")
        }.onFailure { log("setup: Sony flow migration failed: ${it.message}") }
    }

    /**
     * settings.db of the guest: /data/data/... on stock layouts, /dbdata/databases/... on Samsung 2.x/3.x (the
     * framework hardcodes /dbdata there). Null until SettingsProvider has created it (after the first boot).
     */
    private fun settingsDb(): File? = listOf(
        "data/data/com.android.providers.settings/databases/settings.db",
        "dbdata/databases/com.android.providers.settings/settings.db",
    ).map { File(root, it) }.firstOrNull { it.isFile }

    /**
     * "Skip setup wizard" on API 16+: the live helper (SetupCtl) only flips device_provisioned / user_setup_complete after
     * boot. With the wizard disabled and the device still unprovisioned at start-up, Samsung's keyguard never draws and
     * WindowManager logs "performEnableScreen return becase Keyguard ... is not fully drawn!" forever: black screen.
     * Once the helper has run (its state file holds the original values, so "restore" still works), set the flags in
     * settings.db before the system starts. Never called before the first helper run, so the saved originals stay original.
     */
    fun provisionBeforeBoot() {
        if (img.api < 16) return
        if (!File(root, "data/system/aemu-setup-skip.properties").isFile) return
        val db = settingsDb() ?: return
        runCatching {
            SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
                fun put(table: String, name: String, value: String) {
                    val exists = d.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.count > 0 }
                    if (!exists) return
                    val cv = android.content.ContentValues().apply { put("name", name); put("value", value) }
                    if (d.update(table, cv, "name=?", arrayOf(name)) == 0) d.insert(table, null, cv)
                }
                if (img.api >= 17) put("global", "device_provisioned", "1")
                put("secure", "device_provisioned", "1")
                put("secure", "user_setup_complete", "1")
            }
            log("setup: device marked provisioned before boot (setup wizard skipped)")
        }.onFailure { log("setup: pre-boot provisioning failed: ${it.message}") }
    }

    /**
     * Wear: pin the display to 0° before the system starts. A rotation makes WindowManager freeze the screen and take a
     * SurfaceControl.screenshot() for the rotation animation while it holds the ActivityManager lock. Under the GL bridge
     * that capture can block forever (GLConsumer \"doGLFenceWait: error waiting for fence: -25\" precedes it), every
     * system_server thread then waits on the AMS lock, the Watchdog kills system_server and zygote exits with 137.
     * Real watches do not auto-rotate either. Needs an existing settings.db (second and later boots).
     */
    fun lockWearRotation() {
        if (!img.skin.equals("Wear", ignoreCase = true)) return
        val db = settingsDb() ?: return
        runCatching {
            SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
                val hasTable = d.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='system'", null).use { it.count > 0 }
                if (!hasTable) return@use
                fun put(name: String, value: String) {
                    val cv = android.content.ContentValues().apply { put("name", name); put("value", value) }
                    if (d.update("system", cv, "name=?", arrayOf(name)) == 0) d.insert("system", null, cv)
                }
                put("accelerometer_rotation", "0")
                put("user_rotation", "0")
            }
            log("wear: screen rotation locked to 0° (avoids the rotation-animation screenshot that can hang system_server)")
        }.onFailure { log("wear: could not lock the rotation: ${it.message}") }
    }

    fun noScreenSleep() {
val db = settingsDb()
        if (db == null) {
            // Android 6.0+ keeps the settings as XML files instead of a database
            if (img.api >= 23 && kernel.xmlSettings) { xmlSettings(); disableLegacyGoogleLogin() }
            return
        }
        runCatching {
            SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
                fun put(table: String, name: String, value: String) {
                    val exists = runCatching {
                        d.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.count > 0 }
                    }.getOrDefault(false)
                    if (!exists) return
                    val cv = android.content.ContentValues().apply { put("name", name); put("value", value) }
                    if (d.update(table, cv, "name=?", arrayOf(name)) == 0) d.insert(table, null, cv)
                }
                put("system", "screen_off_timeout", "2147483647")
                val t = if (img.api >= 17) "global" else "system"
                put(t, "stay_on_while_plugged_in", "7")
                put(if (img.api >= 17) "global" else "secure", "mobile_data", "1")
                put(if (img.api >= 17) "global" else "secure", "package_verifier_enable", "0")
                put(if (img.api >= 17) "global" else "secure", "install_non_market_apps", "1")
                put("secure", "install_non_market_apps", "1")
                if (img.api >= 17) put("global", "verifier_verify_adb_installs", "0")
                // Samsung 2.x/3.x DeviceStorageMonitorService judges /dbdata by the host's real free space (statfs is not
                // faked there, unlike /data): a phone that is >90% full makes the guest "database low on memory", and the
                // stock messaging stack then stops storing incoming SMS. 1% instead of the default 10%.
                if (File(root, "dbdata").isDirectory) put("secure", "sys_storage_threshold_percentage", "1")
                // Preserve the older-engine behavior; the explicit live-provider option starts at API 16.
                if (img.api < 16 && !hasSonySetupFlow()) {
                    put("secure", "device_provisioned", "1")
                    put("secure", "user_setup_complete", "1")
                }
            }
        }.onFailure { log("settings: database failed to open: ${it.message}") }
        disableLegacyGoogleLogin()
    }

    /** Existing legacy Google sign-in workaround; independent of the setup-skip option. */
    /**
     * Packages that stall boot on legacy images, handled before the system starts (afterBoot() is too late: it only
     * runs once boot has completed). 4.0.x Glass: system_server binds com.google.android.backup's
     * BackupTransportService, the process never answers, ActivityManager reports "ANR in system" ("System is not
     * responding") and sys.boot_completed is never set. Backup is opted out in the guest anyway.
     * The APK (and its odex) is set aside in /system/.aemu-parked; PackageManager then drops the missing system app.
     */
    private fun disableStallingPackages() = runCatching {
        if (img.api >= 16) { log("boot stall guard: skipped (api ${img.api})"); return@runCatching }
        val pkg = "com.google.android.backup"
        val packagesXml = File(root, "data/system/packages.xml")
        val declared = if (packagesXml.isFile) Regex("<package name=\"${Regex.escape(pkg)}\"[^>]*?codePath=\"([^\"]+)\"").find(packagesXml.readText())?.groupValues?.get(1) else null
        val parked = File(root, "system/.aemu-parked").apply { mkdirs() }
        val candidates = LinkedHashSet<File>()
        if (declared != null) candidates += File(root, declared.trimStart('/'))
        for (dir in listOf("system/app", "system/priv-app", "system/vendor/app", "vendor/app"))
            File(root, dir).listFiles()?.filter { it.isFile && it.name.endsWith(".apk") && it.name.contains("Backup", ignoreCase = true) && it.name.contains("Google", ignoreCase = true) }?.let { candidates += it }
        log("boot stall guard: ${pkg} codePath=${declared ?: "unknown (no packages.xml entry)"}, candidates=${candidates.joinToString { it.name }}")
        var moved = 0
        for (apk in candidates) {
            if (!apk.isFile) continue
            for (f in listOf(apk, File(apk.path.removeSuffix(".apk") + ".odex"))) {
                if (!f.isFile) continue
                val dst = File(parked, f.relativeTo(root).path.replace('/', '#'))
                if (dst.exists()) dst.delete()
                if (f.renameTo(dst)) { moved++; log("boot stall guard: parked ${f.name}") } else log("boot stall guard: could not park ${f.name}")
            }
        }
        // Fallback / belt and braces: mark the package disabled for user 0 as well.
        val restrictions = File(root, "data/system/users/0/package-restrictions.xml")
        if (restrictions.isFile && packagesXml.isFile && packagesXml.readText().contains("<package name=\"$pkg\"")) {
            var xml = restrictions.readText()
            val m = Regex("<pkg name=\"${Regex.escape(pkg)}\"([^>]*?)(/?)>").find(xml)
            if (m == null) {
                xml = xml.replace("</package-restrictions>", "    <pkg name=\"$pkg\" enabled=\"2\" />\n</package-restrictions>")
                restrictions.writeText(xml); log("boot stall guard: $pkg marked disabled")
            } else if (!m.groupValues[1].contains("enabled=\"2\"")) {
                val attrs = m.groupValues[1].replace(Regex(" enabled=\"\\d\""), "")
                xml = xml.replaceRange(m.range, "<pkg name=\"$pkg\"$attrs enabled=\"2\"${m.groupValues[2]}>")
                restrictions.writeText(xml); log("boot stall guard: $pkg marked disabled")
            }
        } else log("boot stall guard: no restrictions entry changed (restrictions=${restrictions.isFile}, packages.xml=${packagesXml.isFile})")
        if (moved == 0 && declared == null) log("boot stall guard: $pkg not found in this tree, nothing to do")
    }.onFailure { log("boot stall guard failed: ${it.message}") }

    /**
     * Glass: earlier versions switched off com.google.android.location's NetworkLocationService, which removes the
     * "network" location provider and makes com.google.glass.settings crash at boot (IllegalArgumentException:
     * provider=network). The disabled state sits in packages.xml, so it is cleared here, before system_server reads it.
     */
    private fun glassLocationProvider() = runCatching {
        if (img.api !in 9..16) return@runCatching
        val glass = GlassLocationPolicy.isGlass(img.name, img.model,
            File(root, "system/app/GlassBluetooth.apk").isFile || File(root, "system/.aemu-parked/system#app#GlassBluetooth.apk").isFile)
        if (!glass) return@runCatching
        val f = File(root, "data/system/packages.xml")
        if (!f.isFile) return@runCatching
        GlassLocationPolicy.reenable(f.readText())?.let {
            f.writeText(it)
            log("glass: network location service enabled again (settings needs the network provider)")
        }
    }.onFailure { log("glass: could not re-enable network location: ${it.message}") }

    /**
     * Glass 4.4: selects the Glass remote IME in settings.db so that InputMethodManagerService does not take the
     * "No IME selected" path that throws in its constructor (see [GlassImePolicy]). Needs a settings.db, which the
     * first (failing) boot creates; returns true when a value was written, so the caller can retry the boot.
     */
    fun seedDefaultIme(): Boolean {
        if (img.api < 19) return false
        val installed = listOf("system/priv-app/GlassRemoteIme.apk", "system/app/GlassRemoteIme.apk").any { File(root, it).isFile }
        if (!installed) return false
        val db = File(root, "data/data/com.android.providers.settings/databases/settings.db")
        if (!db.isFile) return false
        return runCatching {
            SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
                val hasTable = d.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='secure'", null).use { it.count > 0 }
                if (!hasTable) return@use false
                fun get(name: String): String? = d.rawQuery("SELECT value FROM secure WHERE name=?", arrayOf(name)).use { if (it.moveToFirst()) it.getString(0) else null }
                fun put(name: String, value: String) {
                    val cv = android.content.ContentValues().apply { put("name", name); put("value", value) }
                    if (d.update("secure", cv, "name=?", arrayOf(name)) == 0) d.insert("secure", null, cv)
                }
                val v = GlassImePolicy.valueToSeed(get("default_input_method"), true) ?: return@use false
                put("default_input_method", v)
                put("enabled_input_methods", GlassImePolicy.enabledWith(get("enabled_input_methods"), v))
                log("glass: default input method set to $v (InputMethodManagerService no longer fails on first boot)")
                true
            }
        }.onFailure { log("glass: could not set the default input method: ${it.message}") }.getOrDefault(false)
    }

    /**
     * 6.0 keeps the settings as XML files, one per table (data/system/users/0/settings_<table>.xml). The same values
     * as for the database: the screen stays on, and the device counts as set up, without which the setup wizard
     * stays the home screen.
     */
    private fun xmlSettings() {
        val values = listOf(
            Triple("system", "screen_off_timeout", "2147483647"),
            Triple("global", "stay_on_while_plugged_in", "7"),
            Triple("global", "package_verifier_enable", "0"),
        ) + (if (img.settings.skipSetupWizard) listOf(
            Triple("global", "device_provisioned", "1"),
            Triple("secure", "user_setup_complete", "1"),
        ) else emptyList()) + listOf(
            Triple("secure", "install_non_market_apps", "1"),
        )
        for ((table, list) in values.groupBy { it.first }) {
            val f = File(root, "data/system/users/0/settings_$table.xml")
            var xml = runCatching { f.readText() }.getOrNull() ?: continue
            if (!xml.contains("</settings>")) continue
            val before = xml
            for ((_, name, value) in list) {
                val m = Regex("(<setting id=\"[0-9]+\" name=\"${Regex.escape(name)}\" value=\")[^\"]*\"").find(xml)
                if (m != null) { xml = xml.replaceRange(m.range, m.groupValues[1] + value + "\""); continue }
                val id = (Regex("<setting id=\"([0-9]+)\"").findAll(xml).maxOfOrNull { it.groupValues[1].toInt() } ?: 0) + 1
                xml = xml.replace("</settings>", "  <setting id=\"$id\" name=\"$name\" value=\"$value\" package=\"android\" />\n</settings>")
            }
            if (xml != before) runCatching { f.writeText(xml) }.onFailure { log("settings_$table.xml: ${it.message}") }
        }
    }

    private fun disableLegacyGoogleLogin() {
        val f = File(root, "data/system/users/0/package-restrictions.xml")
        if (!f.isFile) return
        // packages.xml, а не packages.list: в списке нет пакетов с общим системным uid (мастер Samsung)
        val pkgs = runCatching { File(root, "data/system/packages.xml").readText() }.getOrNull() ?: return
        val legacy = if (img.api < 16) listOf("com.sec.android.app.SecSetupWizard",
            "com.google.android.setupwizard", "com.android.provision", "com.miui.provision",
            "com.htc.setupwizard", "com.sonyericsson.setupwizard", "com.sonymobile.setupwizard") else emptyList()
        val targets = (legacy + "com.google.android.gsf.login").filter {
            pkgs.contains("<package name=\"$it\"") && !(hasSonySetupFlow() && it == SonySetupPolicy.PACKAGE)
        }
        if (targets.isEmpty()) return
        var xml = runCatching { f.readText() }.getOrNull() ?: return
        var changed = false
        for (p in targets) {
            val open = Regex("<pkg name=\"${Regex.escape(p)}\"([^>]*?)(/?)>")
            val m = open.find(xml)
            if (m == null) {
                xml = xml.replace("</package-restrictions>", "    <pkg name=\"$p\" enabled=\"2\" />\n</package-restrictions>")
                changed = true
            } else if (!m.groupValues[1].contains("enabled=\"2\"")) {
                val attrs = m.groupValues[1].replace(Regex(" enabled=\"\\d\""), "")
                xml = xml.replaceRange(m.range, "<pkg name=\"$p\"$attrs enabled=\"2\"${m.groupValues[2]}>")
                changed = true
            }
        }
        if (changed) runCatching { f.writeText(xml); log("legacy Google login disabled: ${targets.joinToString()}") }
    }

    /** a 32-bit non-PIE executable with a loadable segment below 0x10000 (static Android programs start at 0x8000) */
    private fun linksBelow64K(f: File): Boolean = runCatching {
        if (!f.isFile || isLink(f)) return@runCatching false
        val h = f.inputStream().use { i -> ByteArray(4096).also { n -> i.read(n) } }
        val b = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (h[0] != 0x7f.toByte() || h[1] != 'E'.code.toByte() || h[4] != 1.toByte() || b.getShort(16).toInt() != 2) return@runCatching false
        val phoff = b.getInt(28); val phentsize = b.getShort(42).toInt() and 0xffff; val phnum = b.getShort(44).toInt() and 0xffff
        (0 until phnum).any { k ->
            val o = phoff + k * phentsize
            o + 12 <= h.size && b.getInt(o) == 1 && (b.getInt(o + 8).toLong() and 0xffffffffL) < 0x10000L
        }
    }.getOrDefault(false)

    /**
     * Static healthd (5.0+) checks the battery files with faccessat, which the stand's qemu does not map into the
     * guest root: it tests the phone's real /sys and finds no battery. Its path constant becomes the relative
     * "sys/class/power_supply" (resolved from the services' working directory, run/, where sys links to root/sys).
     */
    private fun healthdRelativeSysfs(base: File) {
        if (img.api < 21) return
        runCatching {
            val link = File(paths.bin, "sys")
            paths.bin.mkdirs()
            if (!isLink(link)) { wipe(link); Os.symlink(File(root, "sys").absolutePath, link.path) }
            val hd = File(root, "sbin/healthd").takeIf { it.isFile } ?: return
            val d = hd.readBytes()
            val from = "/sys/class/power_supply".toByteArray()
            val to = "sys/class/power_supply".toByteArray() + 0
            var n = 0
            var i = 0
            while (i <= d.size - from.size) {
                if (d[i] == from[0] && (0 until from.size).all { d[i + it] == from[it] }) { System.arraycopy(to, 0, d, i, to.size); n++; i += from.size } else i++
            }
            if (n > 0) { hd.writeBytes(d); log("healthd: battery sysfs path made relative ($n)") }
        }.onFailure { log("healthd patch failed: ${it.message}") }
        if (!base.isDirectory) log("no fake power_supply")
    }

    /**
     * bionic 6.0+ starts every 32-bit process with personality(PER_LINUX32) and aborts ("error setting PER_LINUX32
     * personality") when it fails — and it does on phones without AArch32 (Snapdragon 8 Elite…), qemu passes the
     * call to the host kernel. The personality syscall stub (mov ip,r7; mov r7,#136; swi 0) gets "mov r0,#0"
     * instead of the swi, in libc.so and in static binaries (healthd, ueventd…). Done once per image.
     */
    private fun personalityNoop() {
        if (img.api < 23) return
        val stamp = File(root, ".aemu-personality")
        if (stamp.isFile && runCatching { stamp.readText().trim().toInt() }.getOrDefault(0) > 0) return
        val files = listOf(File(root, "system/lib/libc.so")) +
            listOf("sbin", "system/bin", "system/xbin").flatMap { File(root, it).listFiles().orEmpty().toList() }
        var n = 0
        for (f in files) {
            if (!f.isFile || isLink(f) || f.length() > 8_000_000 || f.length() < 1024) continue
            runCatching {
                val d = f.readBytes()
                if (d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte()) return@runCatching
                val b = java.nio.ByteBuffer.wrap(d).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                var hit = false
                var i = 0
                while (i + 12 <= d.size) {
                    // mov ip, r7 ; mov r7, #136 (6.0) or ldr r7, [pc, #x] holding 136 (7.x) ; swi #0
                    if (b.getInt(i) == 0xe1a0c007.toInt() && b.getInt(i + 8) == 0xef000000.toInt()) {
                        val w = b.getInt(i + 4)
                        val nr = when {
                            w == 0xe3a07088.toInt() -> 136
                            w and 0xfffff000.toInt() == 0xe59f7000.toInt() ->
                                (i + 4 + 8 + (w and 0xfff)).takeIf { it + 4 <= d.size }?.let { b.getInt(it) } ?: -1
                            else -> -1
                        }
                        if (nr == 136) { b.putInt(i + 8, 0xe3a00000.toInt()); hit = true }   // swi #0 → mov r0, #0
                    }
                    i += 4
                }
                if (hit) { f.writeBytes(d); n++ }
            }
        }
        log("personality(PER_LINUX32) made a no-op in $n files")
        runCatching { stamp.writeText("$n\n") }
    }

    /**
     * 7.0+ linker stats the executable it was started for, by the path from /proc/self/exe. qemu reports the
     * program's host path (<filesDir>/images/<id>/root/system/bin/servicemanager), but every guest path goes
     * through "-L root", so the guest looks for <root><host path> and gets ENOENT ("unable to stat file for the
     * executable", SIGABRT in linker, servicemanager/surfaceflinger die). The host path of root is made to exist
     * inside root as a link back to root. The target is absolute on purpose: /data/user/0 is itself a link to
     * ../data, so the real directory holding the alias is not where its path says, and a relative target would
     * miss. The host kernel resolves the absolute target, qemu does not remap symlink targets. Absolute links
     * are never exported (VmArchive) and the alias is rechecked on every boot.
     */
    /**
     * The alias sits in the guest's own /data/data (the guest's /data/user/0 is a link to it), under the name of a
     * package that does not exist there, and the guest removes it while it boots (the first dex2oat run by installd
     * and every program started after a zygote restart then abort in the linker with "unable to stat file for the
     * executable"). GuestVm calls this while the system runs and before it restarts zygote to put it back.
     */
    fun ensureHostPathAlias() = hostPathAlias()

    private var aliasMade = false

    private fun hostPathAlias() {
        if (img.api < 24) return
        runCatching {
            val hostRoot = root.absoluteFile.toPath().normalize()
            val rel = hostRoot.root.relativize(hostRoot)           // data/user/0/app.aemu.plus/files/images/<id>/root
            if (rel.nameCount < 2) return
            val alias = File(root, rel.toString())
            val target = hostRoot.toString()
            alias.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
            val cur = runCatching { Os.readlink(alias.path) }.getOrNull()
            if (cur == target) return
            if (cur != null || alias.exists()) wipe(alias)
            Os.symlink(target, alias.path)
            log(if (aliasMade) "linker: host path of the guest root alias was removed by the guest, restored" else "linker: host path of the guest root aliased inside it")
            aliasMade = true
        }.onFailure { log("host path alias failed: ${it.message}") }
    }

    /**
     * bionic 6.0 finds the main thread's stack in /proc/self/task/<pid>/maps; qemu only emulates /proc/self/maps,
     * so the guest parses the host's 64-bit map and ART gets random stack bounds ("Check failed: &stack_variable >
     * stack_end", then SIGSEGV in zygote). The format string in libc.so becomes "/proc/self/maps".
     */
    /**
     * 5.0+: the NFC app's own watchdog aborts it while the vendor NFC stack initialises under qemu; that
     * persistent crash loops the whole system. There is no NFC to offer, so the app is moved out of the way.
     */
    private fun parkNfc() {
        // 6.0+ zygote (API 23 too: an x86 6.0.1 zygote logs "Failed open(/system/framework/aemu-owners.jar)" and
        // "Unable to reopen whitelisted descriptors" in system_server's child, then zygote exits 137): the shim
        // shows qemu's /dhd.owners descriptor under this whitelisted name; after fork zygote reopens it by that
        // path, which has to lead back to the same table
        if (img.api >= 23) runCatching {
            val link = File(root, "system/framework/aemu-owners.jar")
            if (!isLink(link)) { link.delete(); Os.symlink(paths.owners.absolutePath, link.path) }
        }
        if (img.api < 21) {
            // 4.4: keep the NFC app (Settings and the framework expect the adapter to exist) and just keep NFC switched off
            if (img.api >= 19) { restoreNfc(); nfcOffByDefault() }
            return
        }
        val parked = File(root, "system/.aemu-parked").apply { mkdirs() }
        for (dir in listOf("system/app", "system/priv-app")) File(root, dir).listFiles()?.filter { it.name.startsWith("Nfc") }?.forEach { f ->
            val dst = File(parked, f.relativeTo(root).path.replace('/', '#'))
            if (f.renameTo(dst)) log("parked ${f.name}")
        }
    }

    /** Puts back NFC files an earlier build of the emulator moved into system/.aemu-parked on a 4.4 image. */
    private fun restoreNfc() = runCatching {
        val parked = File(root, "system/.aemu-parked")
        parked.listFiles()?.filter {
            it.name.startsWith("system#app#Nfc") || it.name.startsWith("system#priv-app#Nfc") ||
                it.name.startsWith("system#etc#permissions#android.hardware.nfc")
        }?.forEach { f ->
            val dst = File(root, f.name.replace('#', '/'))
            if (!dst.exists() && f.renameTo(dst)) log("restored ${dst.name}")
        }
    }.onFailure { log("nfc restore failed: ${it.message}") }

    /**
     * 4.4: there is no NFC chip behind the HAL ("hw_get_module" fails) and NfcService's watchdog aborts the app while it
     * enables the stack, which loops forever. NfcService only enables the stack when its "nfc_on" preference is true, so the
     * preference is forced to false before every boot. The app, the adapter and the feature flags all stay in place.
     */
    private fun nfcOffByDefault() = runCatching {
        val pkgDir = File(root, "data/data/com.android.nfc")
        val prefsDir = File(pkgDir, "shared_prefs")
        val prefs = File(prefsDir, "NfcServicePrefs.xml")
        val fresh = mutableListOf<File>()
        for (d in listOf(pkgDir, prefsDir)) if (!d.isDirectory) { d.mkdirs(); d.setReadable(true, false); d.setWritable(true, false); d.setExecutable(true, false); fresh += d }
        val old = if (prefs.isFile) prefs.readText() else ""
        val flag = Regex("<boolean name=\"nfc_on\" value=\"[a-z]+\"\\s*/>")
        val text = when {
            old.isBlank() || !old.contains("<map") ->
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <boolean name=\"nfc_on\" value=\"false\" />\n    <boolean name=\"first_boot\" value=\"false\" />\n</map>\n"
            flag.containsMatchIn(old) -> old.replace(flag, "<boolean name=\"nfc_on\" value=\"false\" />")
            old.contains("<map />") -> old.replace("<map />", "<map>\n    <boolean name=\"nfc_on\" value=\"false\" />\n</map>")
            else -> old.replace("</map>", "    <boolean name=\"nfc_on\" value=\"false\" />\n</map>")
        }
        if (text != old) {
            if (!prefs.isFile) fresh += prefs
            prefs.writeText(text)
            prefs.setReadable(true, false); prefs.setWritable(true, false)
            log("nfc: NfcService preference nfc_on forced off (no NFC hardware)")
        }
        if (fresh.isNotEmpty()) {
            // new files are root-owned for the guest; hand them to the nfc uid (1027) through the owners table
            val sb = StringBuilder()
            for (f in fresh) {
                val st = Os.lstat(f.absolutePath)
                sb.append(String.format("%016x %016x %08x %08x\n", st.st_dev, st.st_ino, 1027, 1027))
            }
            paths.owners.appendText(sb.toString())
        }
    }.onFailure { log("nfc: could not switch NFC off: ${it.message}") }

    private fun mainStackMaps() {
        if (img.api < 21) return
        val libc = File(root, "system/lib/libc.so")
        runCatching {
            val d = libc.readBytes()
            val from = "/proc/self/task/%d/maps".toByteArray()
            val i = indexOf(d, from)
            if (i < 0) return
            val to = "/proc/self/maps".toByteArray()
            for (k in from.indices) d[i + k] = if (k < to.size) to[k] else 0
            libc.writeBytes(d)
            log("libc: main thread stack read from /proc/self/maps")
        }.onFailure { log("libc maps patch failed: ${it.message}") }
    }

    private fun indexOf(d: ByteArray, p: ByteArray): Int {
        var i = 0
        while (i <= d.size - p.size) {
            if (d[i] == p[0] && (1 until p.size).all { d[i + it] == p[it] }) return i
            i++
        }
        return -1
    }

    private fun isLink(f: File) = runCatching { OsConstants.S_ISLNK(Os.lstat(f.absolutePath).st_mode) }.getOrDefault(false)
    private fun wipe(f: File) = ImageStore.wipe(f)

    companion object {
        /** GPIO numbers 0..511 get a sysfs node (MT8516 pins sit at 387 + EINTn, i.e. EINT13 = 400, EINT24 = 411). */
        private const val GPIO_STAND_IN_PINS = 512
        /** hw_module_t.id that MediaTek's AudioFlinger requests for libaudio.primary.default.so. */
        private const val MTK_HAL_MODULE_ID = "libaudio"

        private const val OPEN_GL = "engines/kk/libGLES_aemu.so"
        /** Мастера первого запуска: Samsung, Google, AOSP, MIUI, HTC, Sony */
        val SETUP_WIZARDS = listOf(
            "com.sec.android.app.SecSetupWizard", "com.google.android.setupwizard", "com.android.provision",
            "com.miui.provision", "com.htc.setupwizard", "com.sonyericsson.setupwizard", "com.sonymobile.setupwizard",
            // вход в Google-аккаунт на 2.x–4.x больше не работает (старые протоколы входа Google закрыл):
            // его экран не рисуется, запрещает «Домой», и «Контакты» при первом запуске запирают в нём систему
            "com.google.android.gsf.login",
            // Samsung 4.4 (Galaxy S5): Quick connect dies without a Bluetooth address, the S View cover service
            // without its cover font; both come back with a "has stopped" dialog over every screen
            "com.samsung.android.sconnect", "com.sec.android.sviewcover",
            // Samsung 6.0 (Galaxy S6): the TV beacon service parses the Bluetooth address at start and dies on an empty one
            "com.samsung.android.beaconmanager",
        )
        /** the entries of [SETUP_WIZARDS] that take over the screen at first boot (the AOSP provisioner finishes by itself) */
        val FIRST_RUN_WIZARDS = listOf("com.sec.android.app.SecSetupWizard", "com.google.android.setupwizard", "com.miui.provision",
            "com.htc.setupwizard", "com.sonyericsson.setupwizard", "com.sonymobile.setupwizard")
        /** Прошивка MediaTek, где AudioFlinger связан с собственной звуковой библиотекой MTK (/dev/eac). */
        fun isMtkAudio(root: File): Boolean {
            if (File(root, "system/lib/libaudio.mtk.so").isFile) return true   // already swapped for our HAL
            if (isMtkHwOnlyAudio(root)) return true
            val f = File(root, "system/lib/libaudio.primary.default.so")
            if (!f.isFile || f.length() > 20_000_000) return false
            return runCatching {
                val text = String(f.readBytes(), Charsets.ISO_8859_1)
                // "AudioMTKHardware" is absent from some MTK 4.4 builds; any MTK audio class or an AudioFlinger
                // that links this very library (stock AOSP's does not) is just as conclusive
                text.contains("AudioMTK") || text.contains("AudioALSAHardware") ||
                    File(root, "system/lib/libaudioflinger.so").let { af ->
                        af.isFile && af.length() < 20_000_000 &&
                            String(af.readBytes(), Charsets.ISO_8859_1).contains("libaudio.primary.default.so")
                    }
            }.getOrDefault(false)
        }

        /**
         * MediaTek platform by any trace (modem/NVRAM daemons, build.prop), not only by the audio HAL string.
         * Some MTK 4.4 builds (e.g. ALSA-based audio) lack "AudioMTKHardware" yet still ship MTK's reworked
         * AudioPolicyService, where the AOSP policy stand-in dereferences a missing output descriptor.
         */
        fun isMtkPlatform(root: File): Boolean = isMtkAudio(root) || mtkTraces(root)

        /** MediaTek by modem/NVRAM daemons or build.prop alone (independent of the audio libraries). */
        fun mtkTraces(root: File): Boolean {
            if (listOf("nvram_daemon", "ccci_mdinit", "muxreport", "gsm0710muxd").any { File(root, "system/bin/$it").isFile }) return true
            return runCatching {
                File(root, "system/build.prop").readText()
                    .contains(Regex("(?m)^(ro\\.mediatek\\.[^=\\s]*=|ro\\.board\\.platform=mt|ro\\.hardware=mt)"))
            }.getOrDefault(false)
        }

        /**
         * MediaTek 4.2+ whose AudioFlinger takes the HAL from the usual system/lib/hw/audio.primary.default.so
         * (no libaudio.primary.default.so in system/lib): the AOSP-layout stand-in HAL crashes its AudioFlinger,
         * so the MediaTek-layout HAL goes into hw/ instead.
         */
        fun isMtkHwOnlyAudio(root: File): Boolean {
            if (File(root, "system/lib/libaudio.primary.default.so").isFile || File(root, "system/lib/libaudio.mtk.so").isFile) return false
            if (!File(root, "system/lib/hw/audio.primary.default.so").isFile) return false
            val sdk = runCatching {
                Regex("(?m)^ro\\.build\\.version\\.sdk=(\\d+)").find(File(root, "system/build.prop").readText())?.groupValues?.get(1)?.toInt()
            }.getOrNull() ?: return false
            return sdk in 17..20 && mtkTraces(root)
        }

        /** The node libidme.so fopen()s for writing at load time (seen in a Kindle Fire firmware's strace right before the SIGSEGV). */
        private const val IDME_SHIFT_NODE = "/sys/class/mmc_host/mmc0/mmc0:0001/shift"
        private val IDME_LIBS = listOf("system/lib/libidme.so", "vendor/lib/libidme.so", "system/vendor/lib/libidme.so")
        private val SYS_PATH = Regex("/sys/[A-Za-z0-9_.:+-]+(?:/[A-Za-z0-9_.:+-]+)*")

        /**
         * The complete /sys paths written out in a native library's string table. A path that a format string finishes
         * ("/sys/foo/%s") or that ends in a slash names a directory, so it is skipped; so are paths shorter than three
         * components (always directories) and anything with a ".." in it.
         */
        internal fun idmeSysPaths(bytes: ByteArray): List<String> {
            val text = String(bytes, Charsets.ISO_8859_1)
            val out = LinkedHashSet<String>()
            for (m in SYS_PATH.findAll(text)) {
                val next = text.getOrNull(m.range.last + 1)
                if (next == '%' || next == '/') continue
                val path = m.value.trimEnd('.')
                if (path.split('/').contains("..")) continue
                if (path.removePrefix("/sys/").split('/').size < 3) continue
                out += path
            }
            return out.toList()
        }

        /** [paths] that are safe to create as plain files: under /sys, and not a directory above another path in the list. */
        internal fun idmeNodesToCreate(paths: Collection<String>): List<String> {
            val all = paths.filter { it.startsWith("/sys/") && !it.endsWith("/") }.distinct()
            return all.filter { p -> all.none { it.startsWith("$p/") } }
        }

        /** r-x for everyone (0755-style): lets guest app uids walk through a directory that no uid owns in the owners table. */
        internal fun makeTraversable(dir: File) {
            if (!dir.isDirectory) return
            dir.setReadable(true, false)
            dir.setExecutable(true, false)
        }

        private val DBDATA_DIRS = listOf("dbdata", "dbdata/databases", "dbdata/system")

        /**
         * Samsung 2.x keeps app databases and the account / sync registries on a separate /dbdata partition
         * (/dbdata/databases/<pkg>/, /dbdata/system/), which init.rc mounts and the framework then uses by
         * absolute path. There is no such partition here, so nothing creates those directories: SettingsProvider fails with
         * "unable to open database file", system_server dies on it and zygote exits. Returns the directories to create
         * when the init scripts ([rcTexts]) or their mkdir lines ([initDirs]) mention /dbdata, else nothing.
         */
        internal fun dbdataDirs(rcTexts: List<String>, initDirs: List<String>, legacySamsung: Boolean = false, alreadyThere: Boolean = false): List<String> =
            // the framework hardcodes /dbdata on Samsung 2.x/3.x even when the ramdisk's init.rc never mentions it (the mount lives in a vendor script)
            if (legacySamsung || alreadyThere || initDirs.any { it.contains("dbdata") } || rcTexts.any { it.contains("/dbdata") }) DBDATA_DIRS else emptyList()

        /** Temporary experiment: restore MyFaves instead of parking it (see parkAutoSmsApps). Set to false to park it again. */
        private const val TEMP_UNPARK_MYFAVES = true
        val QCOM_PARK = listOf(
            "system/etc/permissions/qti_permissions.xml",
            "system/etc/permissions/com.qti.dpmframework.xml",
            "system/priv-app/com.qualcomm.location",
            "system/priv-app/qcrilmsgtunnel",
            "system/priv-app/dpmserviceapp",
            "system/priv-app/DiracAudioControlService",
            "system/app/DiracManager",
        )
        private val LOGS = listOf("main", "system", "radio") // events — канал, его делает EventsSink
        private val DATA_DIRS = listOf(
            "data", "data/app", "data/app-private", "data/app-lib", "data/app-asec", "data/data", "data/dalvik-cache",
            "data/local", "data/local/tmp", "data/misc", "data/misc/keystore", "data/misc/wifi", "data/misc/zoneinfo",
            "data/property", "data/system", "data/anr", "data/backup", "data/lost+found", "data/drm", "data/media",
            "data/resource-cache", "data/security", "data/tombstones", "cache", "cache/download", "cache/lost+found",
            // Dalvik of 2.x-4.x keeps the dex cache of /system jars here (init.rc: mkdir /cache/dalvik-cache); without it the VM
            // finds no bootclasspath entry ("Could not stat dex cache directory") and zygote aborts. Also covers a tree whose ramdisk is missing
            "cache/dalvik-cache",
            // what init.rc makes for the daemons (a system-only image comes without that file; installd leaves without misc/user)
            "data/misc/user", "data/misc/systemkeys", "data/misc/vpn", "data/misc/net", "data/misc/shared_relro", "data/misc/media", "data/misc/gatekeeper",
            "mnt", "mnt/asec", "mnt/obb", "mnt/secure", "mnt/secure/asec", "mnt/shell", "mnt/media_rw", "storage",
        )
    }
}

/** an ELF64 AArch64 shared object with the soname android.hidl.base@1.0.so and no symbols (848 bytes). It has to carry section headers with a valid e_shstrndx: bionic refuses to load an ELF without them ("has invalid e_shstrndx") */
private const val HIDL_BASE_STUB = "f0VMRgIBAQAAAAAAAAAAAAMAtwABAAAAAAAAAAAAAABAAAAAAAAAANABAAAAAAAAAAAAAEAAOAADAEAABgAFAAEAAAAGAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAoAEAAAAAAACgAQAAAAAAAAAQAAAAAAAAAgAAAAYAAAAwAQAAAAAAADABAAAAAAAAMAEAAAAAAABwAAAAAAAAAHAAAAAAAAAACAAAAAAAAABR5XRkBgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAEAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAYW5kcm9pZC5oaWRsLmJhc2VAMS4wLnNvAAAAAAAAAAQAAAAAAAAA6AAAAAAAAAAFAAAAAAAAABABAAAAAAAABgAAAAAAAAD4AAAAAAAAAAoAAAAAAAAAGgAAAAAAAAALAAAAAAAAABgAAAAAAAAADgAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAALmhhc2gALmR5bnN5bQAuZHluc3RyAC5keW5hbWljAC5zaHN0cnRhYgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAUAAAACAAAAAAAAAOgAAAAAAAAA6AAAAAAAAAAQAAAAAAAAAAIAAAAAAAAACAAAAAAAAAAEAAAAAAAAAAcAAAALAAAAAgAAAAAAAAD4AAAAAAAAAPgAAAAAAAAAGAAAAAAAAAADAAAAAQAAAAgAAAAAAAAAGAAAAAAAAAAPAAAAAwAAAAIAAAAAAAAAEAEAAAAAAAAQAQAAAAAAABoAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAFwAAAAYAAAADAAAAAAAAADABAAAAAAAAMAEAAAAAAABwAAAAAAAAAAMAAAAAAAAACAAAAAAAAAAQAAAAAAAAACAAAAADAAAAAAAAAAAAAAAAAAAAAAAAAKABAAAAAAAAKgAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAA="
