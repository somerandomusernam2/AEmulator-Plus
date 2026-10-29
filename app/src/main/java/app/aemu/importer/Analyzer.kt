/* Modified for AEmulator Plus, 2026-10-23: pre-1.0 firmware (LegacyFirmware): old init.rc, sparse build.prop, /sdcard.
 * GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.importer

import app.aemu.core.GuestService
import app.aemu.core.LegacyFirmware
import app.aemu.core.ImportWarnings
import android.content.Context
import app.aemu.core.Engine
import app.aemu.core.GuestArch
import app.aemu.core.GuestImage
import app.aemu.core.InitPlan
import app.aemu.core.PropArea
import app.aemu.core.VmPaths
import app.aemu.core.VmSettings
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Изучает разложенное дерево прошивки и выводит всё, что нужно для запуска:
 * версию, производителя и оболочку, движок, BOOTCLASSPATH, переменные и каталоги init,
 * план загрузки служб, путь карты памяти, разрешение экрана. Пишет шаблон области свойств.
 */
class Analyzer(private val ctx: Context, private val paths: VmPaths, private val ramdisk: List<BootImage.CpioEntry>?) {
    private val root = paths.root

    fun analyze(id: String, sourceName: String, persistBuildProp: Boolean = true): GuestImage {
        val buildFile = File(root, "system/build.prop")
        val noBuildProp = !buildFile.isFile
        val buildText = if (noBuildProp) synthBuildProp().also { if (persistBuildProp) buildFile.writeText(it) }
            else buildFile.readText(Charsets.UTF_8)
        // (a pre-1.0 build.prop has no version and no product properties: LegacyFirmware fills them, other trees pass unchanged)
        val build = LegacyFirmware.completeBuildProps(PropArea.parseProps(buildText), root)
        // (8.0+ system-as-root and system-only images keep the ramdisk's default.prop as /system/etc/prop.default)
        val deflt = listOf("default.prop", "etc/default.prop", "system/etc/prop.default").map { File(root, it) }.firstOrNull { it.isFile }
            ?.let { PropArea.parseProps(it.readText()) } ?: LinkedHashMap()
        val all = LinkedHashMap<String, String>().apply { putAll(deflt); build.forEach { (k, v) -> if (!(k.startsWith("ro.") && containsKey(k))) put(k, v) } }

        val api = effectiveApi(all["ro.build.version.sdk"]?.toIntOrNull(), all["ro.build.version.codename"], all["ro.build.version.release"])
        val release = all["ro.build.version.release"] ?: "?"
        // who made it: a generic system image (10+, "missi" of Xiaomi) leaves the brand, the model and the skin's
        // version to the product, vendor and odm partitions. Only for the name shown; the rest reads what init reads.
        val ident = LinkedHashMap(all)
        // (at import product and system_ext are still under system/; the skin's own partitions: mi_ext, my_*, vgc)
        val skinParts = root.listFiles()?.filter { it.isDirectory && it.name.matches(Regex("mi_ext|my_[a-z]+|vgc|prism|optics")) }
            ?.flatMap { listOf("${it.name}/etc/build.prop", "${it.name}/build.prop") } ?: emptyList()
        for (n in skinParts + listOf("product/etc/build.prop", "product/build.prop", "system/product/etc/build.prop", "system/product/build.prop",
            "odm/etc/build.prop", "odm/build.prop", "vendor/build.prop",
            "system_ext/etc/build.prop", "system_ext/build.prop", "system/system_ext/etc/build.prop", "system/system_ext/build.prop")) runCatching {
            File(root, n).takeIf { it.isFile }?.let { f -> PropArea.parseProps(f.readText()).forEach { (k, v) -> ident.putIfAbsent(k, v) } }
        }
        fun product(what: String) = ident["ro.product.$what"]
            ?: listOf("product", "odm", "vendor", "system").firstNotNullOfOrNull { ident["ro.product.$it.$what"]?.takeIf { v -> v.isNotEmpty() } }
        val brand = (product("brand") ?: product("manufacturer") ?: "").replaceFirstChar { it.uppercase() }
        val model = product("model") ?: product("device") ?: "Android"
        for (what in listOf("brand", "manufacturer")) product(what)?.let { ident.putIfAbsent("ro.product.$what", it) }
        // form factor first (Glass/Wear/TV), then the skin; both read the merged identity props
        val skin = formFactorSkin(ident, api) ?: skin(ident)
        val abi = all["ro.product.cpu.abi"] ?: "armeabi-v7a"
        // the instruction set comes from the firmware's own ELF files (e_machine), ro.product.cpu.abi is the fallback
        val arch = GuestArch.detect(root, abi)

        // init.rc и его import’ы лежат в корне рамдиска
        // pre-1.0: /etc/init.rc in the old block syntax (env { } startup { } service { }); the Android runtime is one program
        val legacyRcFile = File(root, "etc/init.rc").takeIf { f ->
            f.isFile && !File(root, "init.rc").isFile && runCatching { LegacyFirmware.isLegacyInitRc(f.readText()) }.getOrDefault(false)
        }
        val legacyRc = legacyRcFile?.let { f -> runCatching { LegacyFirmware.parseInitRc(f.readText()) }.getOrNull() }
        val legacy = legacyRc != null || LegacyFirmware.isLegacyLayout(root)
        val rcFiles = if (legacyRcFile != null) listOf(legacyRcFile) else (root.listFiles()?.filter { it.isFile && it.name.endsWith(".rc") }?.sortedBy { if (it.name == "init.rc") 0 else 1 } ?: emptyList()) +
            InitPlan.hwRcFiles(root, all) +
            (File(root, "system/etc/init").listFiles()?.filter { it.name.endsWith(".rc") } ?: emptyList())
        val rc = if (legacyRc != null) InitPlan.Rc(
            services = legacyRc.services.associate { it.name to InitPlan.RcService(it.name, it.argv, disabled = !it.autostart) },
            exports = legacyRc.exports, dirs = emptyList(),
        ) else InitPlan.parse(rcFiles, all)
        // деревья из стендов HTC несут готовые dhd.bootclasspath / dhd.exports / dhd.dirs
        val dhdExports = File(root, "dhd.exports").takeIf { it.isFile }?.readLines()?.mapNotNull { l ->
            val i = l.indexOf('='); if (i > 0) l.substring(0, i).trim() to l.substring(i + 1).trim() else null
        }?.toMap() ?: emptyMap()
        val dhdBcp = File(root, "dhd.bootclasspath").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
        val dhdDirs = File(root, "dhd.dirs").takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter { it.startsWith("/") } ?: emptyList()
        val bcpFromRc = rc.exports["BOOTCLASSPATH"]?.takeIf { it.isNotBlank() }
        // an odexed ROM is only loadable if BOOTCLASSPATH has exactly the jars (in the order) its odex files were built against;
        // if the init script disagrees (low-power/fota variants), trust the odex dependency chain
        val odexBcp = bootclasspathFromOdex()
        val bcpRcOk = bcpFromRc == null || odexBcp == null || odexBcp.split(':').all { it in bcpFromRc.split(':') }
        val bcp = (if (bcpRcOk) bcpFromRc else odexBcp) ?: dhdBcp ?: dhdExports["BOOTCLASSPATH"]
            ?: odexBcp ?: bootclasspathGuess(api)
        val exports = LinkedHashMap<String, String>(dhdExports).apply { putAll(rc.exports); remove("BOOTCLASSPATH") }
        val dirs = (rc.dirs + dhdDirs).filter { it.startsWith("/data") || it.startsWith("/cache") || it.startsWith("/mnt") || it.startsWith("/storage") }.distinct()
        if (api >= 17 && !exports.containsKey("EMULATED_STORAGE_TARGET") && exports["EXTERNAL_STORAGE"]?.startsWith("/storage/emulated") != false) {
            exports.putIfAbsent("EXTERNAL_STORAGE", "/storage/emulated/legacy")
            exports["EMULATED_STORAGE_SOURCE"] = "/mnt/shell/emulated"
            exports["EMULATED_STORAGE_TARGET"] = "/storage/emulated"
        }
        val plan = if (legacy) LegacyFirmware.plan(legacyRc, root)
            else InitPlan.plan(rc, api, root)
        val vols = StorageList.read(root)
        val voldVols = vols.filter { !it.emulated }.map { it.mountPoint }
        val sdcard = if (legacy) "/sdcard" // (the card of the 2007 builds is mounted by init at /sdcard; later releases say /mnt/sdcard)
            else vols.firstOrNull { it.primary && !it.emulated }?.mountPoint ?: exports["EXTERNAL_STORAGE"]?.takeIf { it.startsWith("/") }
            ?: when { api <= 13 -> "/mnt/sdcard"; api <= 16 -> "/storage/sdcard0"; else -> "/storage/emulated/legacy" }

        val warnings = ArrayList<String>()
        if (noBuildProp) warnings += ImportWarnings.NO_BUILD_PROP
        if (rcFiles.isEmpty() && dhdBcp == null && !legacy) warnings += ImportWarnings.NO_BOOT_IMG
        // x86-32 runs (libqemu_i386.so); x86-64, MIPS and anything else the translators cannot run still gets the warning
        if (GuestArch.isUnsupported(root, abi)) warnings += ImportWarnings.notArm(abi)
        if (api >= 21) warnings += ImportWarnings.experimental(release)

        val density = all["ro.sf.lcd_density"]?.toIntOrNull() ?: 240
        val engine = Engine.forApi(api)
        val baseSettings = when {
            // the 2007 framework was built for 320x480 at 160 dpi and has no other layouts
            legacy -> VmSettings(width = 320, height = 480, density = 160, fbHz = 60, touchHz = 60)
            engine == Engine.GB || api < 14 -> VmSettings(width = 480, height = 800, density = 240, fbHz = 60, touchHz = 60)
            // 540×960 — лучший баланс скорости и чёткости под эмуляцией; поменять можно в настройках
            density <= 160 -> VmSettings(width = 480, height = 800, density = 240)
            // a firmware made for a QHD panel (560 dpi and up) may have no layout that fits an hdpi screen: the Galaxy
            // S6 launcher on 5.x gets a zero-wide item at 540×960/240 and dies in a loop; 720×1280/320 is the smallest
            // screen it was checked on
            density >= 560 -> VmSettings(width = 720, height = 1280, density = 320)
            // MIUI's live icons (Calendar, Weather: fancy_icons in the icon theme) have pictures for 440/480 only and
            // draw nothing at 240; its phones are xxhdpi, so it gets the phone's own screen
            skin.startsWith("MIUI") && api >= 21 -> VmSettings(width = 1080, height = 1920, density = 480)
            else -> VmSettings(width = 540, height = 960, density = 240)
        }

        // kernel version of the firmware: read from the boot.img the importer stored (kernel banner -> /proc/version, uname -r)
        val settings = runCatching { File(paths.dir, "boot.img").takeIf { it.isFile }?.readBytes() }.getOrNull()
            ?.let { BootImage.withKernelBanner(baseSettings, it) } ?: baseSettings

        writePropsTemplate(deflt, build, api, engine, rc.setprops)

        return GuestImage(
            id = id,
            // (a pre-1.0 build has no product of its own: "HTC Pre-1.0 build 20645" was only the guesses of LegacyFirmware)
            name = if (legacy) LegacyFirmware.DISPLAY_NAME else
                (if (model.startsWith(brand, true)) model else "$brand $model").trim().ifBlank { "Android $release" } +
                    if (skin != "AOSP" && !model.contains(skin, true)) " · $skin" else "",
            release = release,
            api = api,
            brand = brand,
            model = model,
            skin = skin,
            engine = engine,
            abi = abi,
            arch = arch,
            bootclasspath = bcp,
            exports = exports,
            dirs = dirs,
            services = plan,
            sdcardPath = sdcard,
            volumes = voldVols,
            settings = settings,
            createdAt = System.currentTimeMillis(),
            sourceName = sourceName,
            runtime = if (api >= 21) "art" else "dalvik",
            warnings = warnings,
            profileVersion = VERSION,
            buildId = (all["ro.build.id"] ?: "").trim(),
        )
    }

    companion object {
        const val VERSION = 18   // 18: ICS developer builds (sdk 13, codename IceCreamSandwich) are API 14; 17: guest architecture (GuestArch) detected from the ELF files

        /**
         * Form-factor tag from ro.build.characteristics: if the line contains "tv" it is Google TV below API 18 and
         * Android TV from API 18 on; if it contains "watch" it is Wear. A line with both is AOSP. A fingerprint containing
         * "oogle/glass" (any case) is Glass OS and wins over the above. Null for
         * phones/tablets, which fall through to [skin].
         */
        internal fun formFactorSkin(p: Map<String, String>, api: Int): String? {
            // "oogle/glass" skips the first letter: the fingerprint may say Google or google
            if (p["ro.build.fingerprint"]?.contains("oogle/glass", ignoreCase = true) == true) return "Glass OS"
            val line = (p["ro.build.characteristics"] ?: return null).lowercase()
            val tv = "tv" in line
            val watch = "watch" in line
            return when {
                tv && watch -> "AOSP"
                watch -> "Wear"
                tv -> if (api < 18) "Google TV" else "Android TV"
                else -> null
            }
        }

        internal val LMK_DEFAULTS = listOf(
            "ro.FOREGROUND_APP_ADJ" to "0", "ro.VISIBLE_APP_ADJ" to "1", "ro.PERCEPTIBLE_APP_ADJ" to "2",
            "ro.HEAVY_WEIGHT_APP_ADJ" to "3", "ro.SECONDARY_SERVER_ADJ" to "4", "ro.BACKUP_APP_ADJ" to "5",
            "ro.HOME_APP_ADJ" to "6", "ro.HIDDEN_APP_MIN_ADJ" to "7", "ro.EMPTY_APP_ADJ" to "15",
            "ro.FOREGROUND_APP_MEM" to "2048", "ro.VISIBLE_APP_MEM" to "3072", "ro.PERCEPTIBLE_APP_MEM" to "4096",
            "ro.HEAVY_WEIGHT_APP_MEM" to "4096", "ro.SECONDARY_SERVER_MEM" to "6144", "ro.BACKUP_APP_MEM" to "6144",
            "ro.HOME_APP_MEM" to "6144", "ro.HIDDEN_APP_MEM" to "7168", "ro.EMPTY_APP_MEM" to "8192",
            // Eclair/Froyo ActivityManagerService also reads these; an empty value ends in NumberFormatException in system_server
            "ro.CONTENT_PROVIDER_ADJ" to "14", "ro.CONTENT_PROVIDER_MEM" to "7680",
        )
        private val NET_DEFAULTS = listOf(
            "net.tcp.buffersize.default" to "4096,87380,110208,4096,16384,110208",
            "net.tcp.buffersize.wifi" to "524288,1048576,2097152,262144,524288,1048576",
            "net.tcp.buffersize.umts" to "4094,87380,110208,4096,16384,110208",
            "net.tcp.buffersize.hspa" to "4094,87380,262144,4096,16384,262144",
            "net.tcp.buffersize.lte" to "524288,1048576,2097152,262144,524288,1048576",
            "net.tcp.buffersize.edge" to "4093,26280,35040,4096,16384,35040",
            "net.tcp.buffersize.gprs" to "4092,8760,11680,4096,8760,11680",
            "net.bt.name" to "Android",
            "net.change" to "net.dns1",
        )

        /**
         * API level the firmware really behaves as. Developer-preview builds (e.g. Wear 2.0 "NVD36F") still report the
         * previous release's `ro.build.version.sdk` (23) and put the next one in `codename` ("N", release "N"), yet ship
         * that release's framework, libEGL and init. Treating them as the old API skips every 7.0+ fix (the
         * libGLES_emulation driver name, user_de dirs, direct zygote...), so SurfaceFlinger aborts without an EGL driver.
         */
        fun effectiveApi(sdk: Int?, codename: String?, release: String?): Int {
            val code = codename?.trim()?.uppercase().orEmpty()
            // ICS pre-release trees (e.g. "Android Full on Stingray" IRK22) still say sdk=13 (Honeycomb) with codename
            // "IceCreamSandwich", but ship the ICS AudioFlinger/audio_hw_device layout and init
            val rel = release?.trim()?.uppercase().orEmpty()
            val preview = when {
                code == "N" -> 24
                code == "NMR1" -> 25
                code == "ICECREAMSANDWICH" || rel == "ICECREAMSANDWICH" -> 14
                else -> null
            }
            if (sdk == null) return preview ?: guessApi(release)
            return if (preview != null && sdk < preview) preview else sdk
        }

        fun guessApi(rel: String?): Int = when {
            rel == null -> 19
            rel.startsWith("0.") -> 1   // pre-1.0 builds (0.9)
            rel.startsWith("1.0") -> 1; rel.startsWith("1.1") -> 2; rel.startsWith("1.5") -> 3; rel.startsWith("1.6") -> 4
            rel.startsWith("2.0") -> 5; rel.startsWith("2.1") -> 7; rel.startsWith("2.2") -> 8; rel.startsWith("2.3") -> 10
            rel.startsWith("3.0") -> 11; rel.startsWith("3.1") -> 12; rel.startsWith("3.2") -> 13
            rel.startsWith("4.0") -> 15; rel.startsWith("4.1") -> 16; rel.startsWith("4.2") -> 17; rel.startsWith("4.3") -> 18
            rel.startsWith("4.4") -> 19; rel.startsWith("5.0") -> 21; rel.startsWith("5.1") -> 22
            rel.startsWith("6") -> 23; rel.startsWith("7.0") || rel == "N" -> 24; rel.startsWith("7") -> 25
            rel.startsWith("8.0") -> 26; rel.startsWith("8.1") -> 27; rel.startsWith("8") -> 26
            rel.startsWith("9") -> 28; rel.startsWith("10") -> 29; rel.startsWith("11") -> 30
            rel.startsWith("12") -> 31; rel.startsWith("13") -> 33; rel.startsWith("14") -> 34
            rel.startsWith("15") -> 35; rel.startsWith("16") -> 36; rel.startsWith("17") -> 37
            else -> 19
        }

        /** Пересчитывает профиль образа новым анализатором, сохраняя настройки пользователя. */
        fun refresh(ctx: Context, img: GuestImage): GuestImage {
            if (img.profileVersion >= VERSION) return img
            val paths = VmPaths(ctx, img.id)
            val fresh = runCatching { Analyzer(ctx, paths, null).analyze(img.id, img.sourceName) }.getOrNull() ?: return img
            val out = fresh.copy(
                name = img.name, settings = img.settings, createdAt = img.createdAt, sizeBytes = img.sizeBytes,
                lastBootMs = img.lastBootMs, bootCount = img.bootCount,
                oneTimeNote = img.oneTimeNote,
            )
            app.aemu.core.ImageStore.save(ctx, out)
            return out
        }
    }

    /** Minimal build.prop for trees that came without one; version is inferred from framework/lib layout. */
    private fun synthBuildProp(): String {
        fun has(path: String) = File(root, path).exists()
        val (api, rel) = when {
            has("system/javalib") -> LegacyFirmware.PRE_RELEASE_API to LegacyFirmware.PRE_RELEASE_RELEASE
            has("system/apex") || has("apex") -> 29 to "10"
            has("system/bin/hwservicemanager") || has("system/etc/vintf") -> 26 to "8.0"
            has("system/framework/arm64") || has("system/lib64") -> if (has("system/etc/init")) 24 to "7.0" else 23 to "6.0"
            has("system/framework/arm/boot.oat") || has("system/framework/arm") -> if (has("system/lib/libandroid_runtime.so") && has("system/bin/fingerprintd")) 23 to "6.0" else 21 to "5.0"
            has("system/framework/core-libart.jar") -> 19 to "4.4"
            has("system/lib/libGLESv3.so") || has("system/bin/sdcard") -> 18 to "4.3"
            has("system/lib/libskia.so") && has("system/framework/android.policy.jar") && has("system/lib/libhwui.so") -> 16 to "4.1"
            has("system/framework/core.jar") -> 10 to "2.3"
            else -> 10 to "2.3"
        }
        return "# generated by AEmulator: firmware had no build.prop\nro.build.version.sdk=$api\nro.build.version.release=$rel\n" +
            "ro.product.model=Android\nro.product.brand=generic\nro.product.cpu.abi=armeabi-v7a\n"
    }

    /** Only skins proven by properties or files; the device brand alone says nothing (it may be an AOSP/custom ROM). */
    private fun skin(p: Map<String, String>): String {
        val man = (p["ro.product.manufacturer"] ?: p["ro.product.brand"] ?: "").lowercase()
        fun has(path: String) = File(root, path).exists()
        // A brand name alone proves nothing (a custom ROM keeps the donor's ro.product.brand), so a vendor skin that
        // is guessed from the maker needs a sign in the build fingerprint: its brand segment ("Infinix/X657/...").
        val fpBrand = (p["ro.build.fingerprint"] ?: "").substringBefore('/').lowercase()
        val api = p["ro.build.version.sdk"]?.toIntOrNull() ?: guessApi(p["ro.build.version.release"])
        return when {
            // HyperOS keeps the ro.miui.* props of MIUI, so it is checked first
            p.containsKey("ro.mi.os.version.name") || p.containsKey("ro.mi.os.version.code") -> "HyperOS " + (p["ro.mi.os.version.name"] ?: "")
            // Infinix: 4.x plain AOSP, 5.x XUI, 6.0+ XOS; Tecno: 4.x–5.x AOSP, 6.0+ HiOS
            fpBrand.contains("infinix") -> when { api >= 23 -> "XOS"; api >= 21 -> "XUI"; else -> "AOSP" }
            fpBrand.contains("tecno") -> if (api >= 23) "HiOS" else "AOSP"
            // ro.build.version.oneui is a number: 60100 = One UI 6.1
            p.containsKey("ro.build.version.oneui") || p.containsKey("ro.build.version.sep") ->
                "One UI " + (p["ro.build.version.oneui"]?.toIntOrNull()?.let { "${it / 10000}.${it / 100 % 100}" } ?: "")
            p.containsKey("ro.build.version.realmeui") -> "realme UI " + (p["ro.build.version.realmeui"] ?: "").removePrefix("V")
            p.containsKey("ro.build.version.oplusrom") -> "ColorOS " + (p["ro.build.version.oplusrom"] ?: "").removePrefix("V")
            p.containsKey("ro.vivo.os.version") || p.containsKey("ro.vivo.os.name") ->
                p["ro.vivo.os.build.display.id"]?.takeIf { it.isNotBlank() }
                    ?: ((if (p["ro.vivo.os.name"]?.contains("Funtouch", true) == true) "Funtouch OS " else "OriginOS ") + (p["ro.vivo.os.version"] ?: ""))
            p.containsKey("ro.miui.ui.version.name") || p.containsKey("ro.miui.ui.version.code") || has("system/app/MiuiHome.apk") || has("system/app/MiuiHome") -> "MIUI " + (p["ro.miui.ui.version.name"] ?: "")
            p.containsKey("ro.cm.version") -> "CyanogenMod ${p["ro.cm.version"]?.substringBefore('-')}"
            p.containsKey("ro.lineage.version") -> "LineageOS"
            p["ro.build.display.id"]?.contains("Flyme", true) == true -> "Flyme"
            p.containsKey("ro.build.version.emui") -> "EMUI"
            p.containsKey("ro.build.version.opporom") -> "ColorOS"
            (p["ro.product.brand"] ?: "").lowercase() == "google" || p["ro.build.tags"]?.contains("release-keys") == true && man.contains("samsung") && !has("system/framework/twframework.jar") && !has("system/app/TwLauncher.apk") && !has("system/app/SecLauncher2.apk") -> "AOSP"
            has("system/framework/twframework.jar") || has("system/app/TwLauncher.apk") || has("system/app/TwLauncher") || has("system/app/SecLauncher2.apk") -> "TouchWiz"
            has("system/framework/com.htc.framework.jar") || has("system/framework/HTCExtension.jar") -> "HTC Sense"
            else -> "AOSP"
        }.trim()
    }

    /** Порядок BOOTCLASSPATH из зависимостей odex (стоковые прошивки «одексированы»). */
    private fun bootclasspathFromOdex(): String? {
        // odex приложения зависит от всего BOOTCLASSPATH, а odex jar фреймворка — только от предшественников
        val apps = listOf("system/app", "system/priv-app").flatMap { d ->
            File(root, d).walkTopDown().maxDepth(2).filter { it.isFile && it.name.endsWith(".odex") }.take(6).toList()
        }
        var best: List<String> = emptyList()
        for (f in apps) {
            val deps = runCatching { odexDeps(f.readBytes()) }.getOrNull() ?: continue
            val jars = deps.filter { it.endsWith(".jar") }
            if (jars.size > best.size) best = jars
        }
        if (best.size >= 3) return best.joinToString(":")
        // иначе — самая длинная цепочка среди jar фреймворка
        File(root, "system/framework").listFiles()?.filter { it.name.endsWith(".odex") }?.forEach { f ->
            val jars = runCatching { odexDeps(f.readBytes()) }.getOrNull()?.filter { it.endsWith(".jar") } ?: return@forEach
            val self = "/system/framework/" + f.name.removeSuffix(".odex") + ".jar"
            val chain = jars + self
            if (chain.size > best.size) best = chain
        }
        return if (best.size >= 3) best.joinToString(":") else null
    }

    /** Разбор секции зависимостей odex (формат dey 036). */
    private fun odexDeps(b: ByteArray): List<String> {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        if (String(b, 0, 4) != "dey\n") return emptyList()
        val depsOff = bb.getInt(16)
        val depsLen = bb.getInt(20)
        if (depsOff <= 0 || depsOff + depsLen > b.size) return emptyList()
        var p = depsOff + 12 // modWhen, crc, dalvikBuild
        val count = bb.getInt(p); p += 4
        val out = ArrayList<String>()
        repeat(count) {
            val len = bb.getInt(p); p += 4
            out.add(depToJar(String(b, p, len - 1))); p += len
            p += 20 // SHA-1
        }
        return out
    }

    /** /system/framework/core.odex или /data/dalvik-cache/system@framework@core.jar@classes.dex → …/core.jar */
    private fun depToJar(d: String): String = when {
        d.contains("@classes.dex") -> "/" + d.substringAfterLast('/').removeSuffix("@classes.dex").replace('@', '/')
        d.endsWith(".odex") -> d.removeSuffix(".odex") + ".jar"
        else -> d
    }

    /** Эвристика: AOSP-порядок + вендорские jar, которых нет в списке приложений. */
    private fun bootclasspathGuess(api: Int): String {
        val fw = File(root, "system/framework")
        val names = fw.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val aosp = when {
            api <= 10 -> listOf("core.jar", "bouncycastle.jar", "ext.jar", "framework.jar", "android.policy.jar", "services.jar", "core-junit.jar")
            api <= 18 -> listOf("core.jar", "core-junit.jar", "bouncycastle.jar", "ext.jar", "framework.jar", "framework2.jar", "telephony-common.jar", "voip-common.jar", "mms-common.jar", "android.policy.jar", "services.jar", "apache-xml.jar", "webviewchromium.jar")
            api <= 20 -> listOf("core.jar", "conscrypt.jar", "okhttp.jar", "core-junit.jar", "bouncycastle.jar", "ext.jar", "framework.jar", "framework2.jar", "telephony-common.jar", "voip-common.jar", "mms-common.jar", "android.policy.jar", "services.jar", "apache-xml.jar", "webviewchromium.jar")
            api in 21..25 -> listOf("core-libart.jar", "conscrypt.jar", "okhttp.jar", "core-junit.jar", "bouncycastle.jar", "ext.jar", "framework.jar", "telephony-common.jar", "voip-common.jar", "ims-common.jar", "mms-common.jar", "android.policy.jar", "apache-xml.jar")
            else -> listOf("core-oj.jar", "core-libart.jar", "conscrypt.jar", "okhttp.jar", "bouncycastle.jar", "apache-xml.jar", "ext.jar", "framework.jar", "telephony-common.jar", "voip-common.jar", "ims-common.jar", "android.hidl.base-V1.0-java.jar", "android.hidl.manager-V1.0-java.jar")
        }.filter { it in names }
        val vendorHints = listOf("twframework", "secframework", "sec_", "com.htc.", "HTC", "com.motorola", "com.lge", "lge", "semc", "com.sonyericsson", "miui", "com.qualcomm.qcrilhook", "qcom.fmradio", "framework-miui", "framework-ext", "cm.jar")
        val vendor = names.filter { n -> n.endsWith(".jar") && n !in aosp && vendorHints.any { n.startsWith(it) || n.contains(it) } && n != "services.jar" }
        return (aosp + vendor).joinToString(":") { "/system/framework/$it" }
    }

    /** Шаблон области свойств: default.prop → build.prop → наши умолчания (ro.* — «первый побеждает»). */
    private fun writePropsTemplate(deflt: Map<String, String>, build: Map<String, String>, api: Int, engine: Engine, rcProps: Map<String, String>) {
        val base = LinkedHashMap<String, String>()
        fun add(k: String, v: String) { if (!(k.startsWith("ro.") && base.containsKey(k))) base[k] = v }
        // то, что обычно выставляет init из командной строки ядра
        add("ro.secure", "0")
        add("ro.adb.secure", "0")
        add("ro.debuggable", "0")
        add("ro.serialno", "0123456789abcdef")
        add("ro.bootmode", "unknown")
        add("ro.baseband", "unknown")
        add("ro.bootloader", "unknown")
        add("ro.carrier", "unknown")
        add("ro.revision", "0")
        add("ro.factorytest", "0")
        add("ro.setupwizard.mode", "DISABLED")
        add("ro.kernel.android.checkjni", "0")
        add("ro.opengles.version", if (engine == Engine.KK) "131072" else "131072")
        add("keyguard.no_require_sim", "1")
        add("ro.radio.noril", "false")
        add("dalvik.vm.stack-trace-file", "/data/anr/traces.txt")
        for ((k, v) in deflt) add(k, v)
        for ((k, v) in build) add(k, v)
        for ((k, v) in rcProps) add(k, v)
        // то, что init.rc 2.x–4.0 выставляет для ActivityManager и сети: без этого system_server падает
        if (api <= 15) for ((k, v) in LMK_DEFAULTS) add(k, v)
        for ((k, v) in NET_DEFAULTS) add(k, v)
        val sysDefault = File(root, "system/default.prop")
        if (sysDefault.isFile) PropArea.parseProps(sysDefault.readText()).forEach { (k, v) -> add(k, v) }
        // ro.sf.lcd_density и ro.kernel.qemu выставляются при каждом запуске из настроек образа
        base.remove("ro.sf.lcd_density")
        base.remove("ro.kernel.qemu")
        base.remove("ro.kernel.qemu.gles")
        base["persist.sys.usb.config"] = "none"
        base["DEVICE_PROVISIONED"] = "1"
        if (!base.containsKey("dalvik.vm.heapsize")) base["dalvik.vm.heapsize"] = if (api >= 14) "256m" else "64m"
        PropArea.create(paths.propsTemplate, api).use { a ->
            var dropped = 0
            for ((k, v) in base) if (!a.put(k, v)) dropped++
            if (dropped > 0) android.util.Log.w("aemu", "props dropped: $dropped")
        }
    }
}