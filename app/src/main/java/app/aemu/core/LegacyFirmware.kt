/* Modified for AEmulator Plus, 2026-10-23: pre-1.0 firmware (HTC "Sooner" 2007 builds, SDK-emulator packages).
 * GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

/**
 * Pre-1.0 Android: the 2007-2008 internal builds (m3 / m5 era) of the HTC "Sooner" and Dream prototypes, and the community
 * "Android 1.0 build 20645" emulator packages (lib/images/system.img + ramdisk.img + kernel-qemu) that carry one.
 *
 * They differ from every later release in four ways, and each of them used to end the import or the boot:
 *
 *  1. The system partition keeps its Java libraries in /javalib (core.jar, ext.jar, framework.jar, framework-res.apk), not in
 *     /framework. Everything else here looks for system/framework, and boot stopped with "firmware tree missing".
 *  2. The system partition is mounted at /android, not /system: init.rc says /android/bin/..., and the ELF interpreter of every
 *     program is "/android/bin/linker". Under a host directory used as the guest root that is a link, android -> system.
 *  3. The ramdisk is a bare gzip'd cpio (ramdisk.img, no "ANDROID!" header), and the old init reads /etc/init.rc in a block syntax
 *     (env { } / startup { } / service { }) with no "on boot" triggers, so there is no /init.rc and no zygote service. The whole
 *     Android runtime is one program, /android/bin/runtime.
 *  4. build.prop is the output of an old buildinfo.sh: no ro.build.version.*, no ro.product.*, some values empty. The Analyzer's
 *     fallback for that is "Android 4.4", which is wrong by seven years.
 *
 * Everything here is plain JVM code (no Android classes), so it is covered by ordinary unit tests.
 */
object LegacyFirmware {

    /** Android 1.0 was released on 2008-09-23; an older build date with no version properties is a pre-release. */
    private const val ANDROID_1_0_UTC = 1222128000L

    /** The API level a pre-1.0 build is run as: the closest one the rest of the app knows (1.0). */
    const val PRE_RELEASE_API = 1
    const val PRE_RELEASE_RELEASE = "0.9"

    /**
     * libqemu_kk.so with one instruction changed (instruction-fetch permission test: PAGE_EXEC -> PAGE_READ|PAGE_EXEC in
     * get_page_addr_code_hostp), i.e. the kernel's READ_IMPLIES_EXEC for guests without PT_GNU_STACK. Pre-1.0 PixelFlinger
     * generates scanline code into the heap and jumps there; with the stock translator surfaceflinger / the runtime died
     * with SIGSEGV (si_code=2, PC in an rw- page) and the service exited with code 139. Used ONLY for pre-1.0 images.
     */
    /** The directory the pre-1.0 media scanner opens when ACTION_BOOT_COMPLETED reaches it (see GuestVm.watchLegacyBoot). */
    const val BOOT_SCAN_DIR = "system/media"

    const val PRE_RELEASE_TRANSLATOR = "libqemu_arm_pre1.0.so"

    /** True for an image the importer recorded as a pre-1.0 build (release "0.9", see [PRE_RELEASE_RELEASE]). */
    fun isPreRelease(release: String): Boolean = release.startsWith("0.")

    /** What such an image is called in the list. The maker and the build number are not part of it: it is "an Android before 1.0". */
    const val DISPLAY_NAME = "Android Pre-1.0"

    /**
     * The name under which the VM supervises the one program that brings the system up (/android/bin/runtime). It is the role
     * of zygote in every later release: the GL bridge is raised before it, its exit during boot is the boot failure, a restart
     * of the system restarts it. The program itself has nothing to do with app_process.
     */
    const val SYSTEM_SERVICE = "zygote"

    // ------------------------------------------------------------------------------------------------------ ramdisk

    private fun norm(n: String) = n.removePrefix("./").trimStart('/')

    /**
     * True if the ramdisk entries hold an init script: /init.rc (every release since 1.0), or /etc/init.rc, where the
     * pre-1.0 init reads it. (Android 1.0 itself moved it to /init.rc.)
     */
    fun hasInitScript(names: Collection<String>): Boolean = names.any { val n = norm(it); n == "init.rc" || n == "etc/init.rc" }

    /** The old block syntax: env { }, startup { }, service { } sections, and no "on <trigger>" lines. */
    fun isLegacyInitRc(text: String): Boolean {
        val lines = text.lineSequence().map { stripComment(it).trim() }.filter { it.isNotEmpty() }.toList()
        val block = lines.any { Regex("^(startup|service|env)\\s*\\{$").matches(it) }
        val trigger = lines.any { Regex("^on\\s+\\S+").containsMatchIn(it) }
        return block && !trigger
    }

    // ------------------------------------------------------------------------------------------------------ old init.rc

    /** One entry of a startup { } or service { } section. */
    data class Entry(val name: String, val argv: List<String>, val autostart: Boolean, val console: Boolean)

    class Rc(val exports: Map<String, String>, val startup: List<Entry>, val services: List<Entry>)

    private fun stripComment(raw: String): String {
        val t = raw.trimStart()
        if (t.startsWith("#")) return ""
        // "0 -t 0      # no modifiers": a comment after whitespace (a '#' inside a value is not preceded by blank space here)
        val i = Regex("\\s#").find(raw)?.range?.first ?: return raw
        return raw.substring(0, i)
    }

    private fun unquote(v: String): String = v.trim().let { if (it.length >= 2 && it.first() == '"' && it.last() == '"') it.substring(1, it.length - 1) else it }

    /**
     * Parses the old init.rc. Section nesting is tracked by braces:
     *
     *     service {                       env { NAME value }
     *         runtime {                   startup { entry { exec ... args.N ... args { N value } autostart 0 } }
     *             exec /android/bin/runtime
     *         }
     *     }
     *
     * `exec` is argv[0]; `args.N value` and the lines of an `args { N value }` block are argv[1..] in the order of N;
     * `autostart 0` keeps an entry from running at boot (the default is to run it); `console yes` marks the console shell.
     */
    fun parseInitRc(text: String): Rc {
        val exports = LinkedHashMap<String, String>()
        val startup = ArrayList<Entry>()
        val services = ArrayList<Entry>()
        val stack = ArrayList<String>()
        var exec: String? = null
        var args = java.util.TreeMap<Int, String>()
        var autostart = true
        var console = false

        fun finish(section: String, name: String) {
            val bin = exec
            if (bin != null) {
                val e = Entry(name, listOf(bin) + args.values, autostart, console)
                if (section == "startup") startup.add(e) else if (section == "service") services.add(e)
            }
            exec = null; args = java.util.TreeMap(); autostart = true; console = false
        }

        for (raw in text.lineSequence()) {
            val ln = stripComment(raw).trim()
            if (ln.isEmpty()) continue
            if (ln.endsWith("{")) { stack.add(ln.removeSuffix("{").trim()); continue }
            if (ln == "}") {
                if (stack.isEmpty()) continue
                // leaving an entry of startup / service (depth 2): it is complete
                if (stack.size == 2 && (stack[0] == "startup" || stack[0] == "service")) finish(stack[0], stack[1])
                stack.removeAt(stack.size - 1)
                continue
            }
            val key = ln.substringBefore(' ').substringBefore('\t')
            val value = ln.substring(key.length).trim()
            when {
                stack.size == 1 && stack[0] == "env" -> if (value.isNotEmpty()) exports[key] = unquote(value)
                stack.size >= 2 && (stack[0] == "startup" || stack[0] == "service") -> when {
                    // inside an `args { }` block (depth 3) every line is "<index> <value...>"
                    stack.size == 3 && stack[2] == "args" -> key.toIntOrNull()?.let { args[it] = unquote(value) }
                    stack.size != 2 -> {}                       // some other nested block (reboot_keys' own args { }): not argv of this entry
                    key == "exec" -> exec = value
                    key.startsWith("args.") -> key.removePrefix("args.").toIntOrNull()?.let { args[it] = unquote(value) }
                    key == "autostart" -> autostart = value != "0"
                    key == "console" -> console = value.equals("yes", true) || value == "1"
                }
            }
        }
        return Rc(exports, startup, services)
    }

    /** Maps a path of the guest (/android/..., /system/..., /sbin/...) to the file on the host. */
    fun hostFile(root: File, guestPath: String): File =
        if (guestPath.startsWith("/android/")) File(root, "system/" + guestPath.removePrefix("/android/"))
        else File(root, guestPath.removePrefix("/"))

    /**
     * What the guest runs instead of init: the Android runtime (/android/bin/runtime) and nothing else. It brings up the service
     * manager, the system server and the applications itself; the rest of the old init.rc are daemons of the phone's own
     * hardware (RIL, Bluetooth, USB gadget, GSM modem, shell), which the host stands in for or which cannot run here.
     *
     * Without an init.rc (a bare system.img) the runtime is started with its own defaults.
     */
    fun services(rc: Rc?, root: File): List<Entry> {
        val declared = rc?.services?.firstOrNull { it.name == "runtime" && it.autostart }
        val entry = declared ?: Entry("runtime", listOf("/android/bin/runtime"), true, false)
        return if (hostFile(root, entry.argv.first()).isFile) listOf(entry) else emptyList()
    }

    /**
     * [services] as the VM's own service list (see [SYSTEM_SERVICE]). Empty if the program is not in the tree.
     * The sockets are none: the runtime of these builds opens its own.
     */
    fun plan(rc: Rc?, root: File): List<GuestService> =
        services(rc, root).map { GuestService(SYSTEM_SERVICE, it.argv, restart = true) }

    /**
     * The services of a pre-1.0 tree, read from its own /etc/init.rc, or null if the tree is not one (the caller then
     * plans as for every other release). The VM asks this at every boot: the generic planner invents a zygote for a tree with
     * no *.rc in the root, which on this firmware starts app_process with arguments it does not know, in place of the runtime.
     */
    fun bootPlan(root: File): List<GuestService>? {
        if (!isLegacyLayout(root)) return null
        val rc = File(root, "etc/init.rc").takeIf { it.isFile && !File(root, "init.rc").isFile }
            ?.let { f -> runCatching { f.readText() }.getOrNull() }
            ?.takeIf { isLegacyInitRc(it) }
            ?.let { runCatching { parseInitRc(it) }.getOrNull() }
        return plan(rc, root)
    }

    // ------------------------------------------------------------------------------------------------------ layout

    /** "framework" or "javalib": the directory of the Java libraries, or null if the tree has neither. */
    fun frameworkDirName(root: File): String? = when {
        File(root, "system/framework").isDirectory -> "framework"
        File(root, "system/javalib").isDirectory -> "javalib"
        else -> null
    }

    /**
     * A tree whose programs are linked against /android: system/bin/runtime is there and its interpreter (the PT_INTERP string at
     * the start of the ELF file) is /android/bin/linker.
     */
    fun isLegacyLayout(root: File): Boolean {
        val runtime = File(root, "system/bin/runtime")
        if (!runtime.isFile) return false
        val head = runCatching { runtime.inputStream().use { i -> ByteArray(4096).let { b -> b.copyOf(maxOf(0, i.read(b))) } } }.getOrNull() ?: return false
        return String(head, Charsets.ISO_8859_1).contains("/android/bin/linker")
    }

    /**
     * Pre-1.0 builds that have no system/bin/runtime (an app_process / zygote layout, e.g. HTC build 20645) still have
     * "/android/bin/linker" as the ELF interpreter, and without the root link android -> system qemu stops with
     * "Could not open '/android/bin/linker'" (exit 255). Checked on the first program that is there.
     */
    fun linksAgainstAndroidDir(root: File): Boolean {
        for (rel in listOf("system/bin/app_process", "system/bin/servicemanager", "system/bin/surfaceflinger", "system/bin/runtime", "system/bin/toolbox")) {
            val f = File(root, rel)
            if (!f.isFile) continue
            val head = runCatching { f.inputStream().use { i -> ByteArray(4096).let { b -> b.copyOf(maxOf(0, i.read(b))) } } }.getOrNull() ?: continue
            return String(head, Charsets.ISO_8859_1).contains("/android/bin/linker")
        }
        return false
    }

    private fun exists(f: File) = Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)

    /**
     * A directory that holds no file, no link and nothing else but (nested) directories: the empty mount point a ramdisk
     * carries for the system partition (the 2007 ramdisk has /android). Anything unreadable counts as not empty.
     */
    private fun isEmptyMountPoint(f: File): Boolean {
        if (!Files.isDirectory(f.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        val kids = f.listFiles() ?: return false
        return kids.all { isEmptyMountPoint(it) }
    }

    /**
     * Makes the tree reachable under the names the rest of the app and the old programs use:
     *  - system/framework -> javalib   (when there is a javalib and no framework)
     *  - android -> system             (at the root, for a tree whose programs are linked against /android)
     * Existing files, links and directories with something in them are never replaced; an empty directory (a mount point from the
     * ramdisk) is. Returns what it made, for the log.
     */
    fun ensureLayout(root: File, log: (String) -> Unit = {}): List<String> {
        val made = ArrayList<String>()
        fun link(at: File, target: String, what: String) {
            // The ramdisk of these builds ships /android as an empty directory (the mount point of the system partition); a
            // directory under a host root is no mount point, so it has to give way to the link. One with anything in it, a
            // file or a link of any kind, is somebody's and stays.
            if (exists(at) && !(isEmptyMountPoint(at) && at.deleteRecursively())) return
            runCatching {
                at.parentFile?.mkdirs()
                Files.createSymbolicLink(at.toPath(), Paths.get(target))
                made.add(what)
            }.onFailure { log("legacy layout: cannot link $what (${it.message})") }
        }
        if (File(root, "system/javalib").isDirectory) link(File(root, "system/framework"), "javalib", "system/framework -> javalib")
        if (isLegacyLayout(root) || linksAgainstAndroidDir(root)) link(File(root, "android"), "system", "android -> system")
        made.forEach { log("legacy layout: $it") }
        return made
    }

    /**
     * Pre-1.0 SurfaceFlinger opens /dev/fb0 (then /dev/fb/0); the framebuffer file of the host is dev/graphics/fb0, the name of
     * every later release. Without the alias the open fails with ENOENT, nothing answers the geometry ioctls and the screen stays
     * black while the system boots behind it. The translator recognises the framebuffer by where the descriptor leads
     * (a path ending in dev/graphics/fb0), so a link to that file is the same device.
     * Only for a pre-1.0 tree; anything that is already at dev/fb0 is left as it is. True if the link was made.
     */
    fun ensureFbAlias(root: File): Boolean {
        if (!isLegacyLayout(root)) return false
        val at = File(root, "dev/fb0")
        if (exists(at)) return false
        return runCatching {
            at.parentFile?.mkdirs()
            Files.createSymbolicLink(at.toPath(), Paths.get("graphics/fb0"))
            true
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------------------------------------------ build.prop

    /**
     * Fills in what an old buildinfo.sh did not write. Only for a pre-release build: no ro.build.version.sdk and no
     * ro.build.version.release, and either the 2007 layout (javalib + runtime) or a build date before Android 1.0. A modern
     * build.prop that merely lacks the version is left alone (the Analyzer has its own guesses for those).
     *
     * Values that are present and not blank are kept as they are; empty ones (ro.build.type=) count as missing.
     */
    fun completeBuildProps(props: Map<String, String>, root: File): LinkedHashMap<String, String> {
        val out = LinkedHashMap(props)
        if (!isPreRelease(props, root)) return out
        fun put(k: String, v: String) { if (out[k].isNullOrBlank()) out[k] = v }
        val id = props["ro.build.id"].orEmpty().trim()
        // "htc-20645.0.8.0.0": the maker, then the build number
        val maker = Regex("^([A-Za-z]+)[-_]").find(id)?.groupValues?.get(1)?.uppercase().orEmpty().ifEmpty { "generic" }
        val number = Regex("(\\d{3,})").find(id)?.groupValues?.get(1)
        put("ro.build.version.sdk", PRE_RELEASE_API.toString())
        put("ro.build.version.release", PRE_RELEASE_RELEASE)
        number?.let { put("ro.build.version.incremental", it) }
        put("ro.build.type", "eng")
        put("ro.product.brand", maker)
        put("ro.product.manufacturer", maker)
        put("ro.product.model", if (number != null) "Pre-1.0 build $number" else "Pre-1.0 prototype")
        put("ro.product.name", "generic")
        put("ro.product.device", "generic")
        put("ro.build.product", "generic")
        return out
    }

    fun isPreRelease(props: Map<String, String>, root: File): Boolean {
        if (!props["ro.build.version.sdk"].isNullOrBlank() || !props["ro.build.version.release"].isNullOrBlank()) return false
        val utc = props["ro.build.date.utc"]?.trim()?.toLongOrNull()
        val oldDate = utc != null && utc in 1 until ANDROID_1_0_UTC
        return oldDate || isLegacyLayout(root) || File(root, "system/javalib").isDirectory   // (no later release has a system/javalib)
    }
}
