package app.aemu.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Modified for AEmulator Plus (2026-11-06): a Dalvik guest (2.x-4.4) whose BOOTCLASSPATH leaves out a jar that its own
 * libandroid_runtime.so registers classes from died in every app_process (zygote, aemu-bt, aemu-sensorhub) with the same
 * SIGSEGV, addr 0x2c, in libdvm right after the boot jars were optimised: a register_android_*() function calls
 * GetStaticMethodID/GetMethodID/GetFieldID with the jclass that its FindClass() returned for a class that is not on the boot
 * class path (it is NULL), and Dalvik reads ClassObject.status (+0x2c) of nothing. Dalvik logs nothing for a class that is
 * not found, and the firmware's init.rc is the only list of jars the VM gets (a vendor rc that names a jar the tree does not
 * have, or one that leaves out a jar the vendor runtime needs, is what happened with "평양 2407", a MediaTek 4.2.2 build whose
 * list ends with am.jar).
 *
 * What this does: collects the class names that libandroid_runtime.so carries as strings (FindClass() arguments are plain
 * "android/os/Foo" constants), checks which of them the BOOTCLASSPATH jars define, and for those that none of them defines
 * looks for a jar in system/framework that does. Such jars are appended to the class path, in the order they are found.
 * A class that no jar defines is left alone (a class that is simply not there is not ours to fix), and so is a tree whose
 * boot jars are odexed (their classes cannot be listed from the jar), where nothing can be concluded.
 * No Android classes here, so it runs on a plain JVM.
 */
object BootClasspathRepair {
    data class Result(
        /** the class path to export: the firmware's, plus the jars that had to be added */
        val bootclasspath: String,
        val added: List<String>,
        /** entries of the firmware's list that are not files in the tree (Dalvik drops them silently) */
        val absent: List<String>,
        /** classes libandroid_runtime or the boot jars refer to that no jar of the tree defines (informational) */
        val undefined: Int,
        /** classes libandroid_runtime names that no boot jar defines but an extra jar does */
        val recovered: List<String>,
        /** why nothing was checked, or null */
        val skipped: String?,
    ) {
        fun report(): String = when {
            skipped != null -> "bootclasspath: not checked ($skipped)"
            added.isEmpty() -> "bootclasspath: ok" + if (absent.isEmpty()) "" else ", not in the tree and dropped by the VM: ${absent.joinToString { it.substringAfterLast('/') }}"
            else -> "bootclasspath: added ${added.joinToString { it.substringAfterLast('/') }} (${recovered.size} class(es) that libandroid_runtime, the boot jars or the system apps use are in " +
                "them, e.g. ${recovered.take(3).joinToString()}); the firmware's init.rc leaves them out" +
                if (absent.isEmpty()) "" else "; not in the tree: ${absent.joinToString { it.substringAfterLast('/') }}"
        }
    }

    private val cache = ConcurrentHashMap<String, Result>()

    /** Dalvik guests only: the tree has libdvm and no ART. */
    fun applies(root: File): Boolean =
        File(root, "system/lib/libdvm.so").isFile && !File(root, "system/lib/libart.so").isFile && File(root, "system/lib/libandroid_runtime.so").isFile

    /** The class path to give the guest; [bootclasspath] unchanged when nothing needs repair. Cached per tree and list. */
    fun repair(root: File, bootclasspath: String): Result =
        cache.getOrPut(root.absolutePath + "|" + bootclasspath) {
            runCatching { compute(root, bootclasspath) }.getOrElse {
                Result(bootclasspath, emptyList(), emptyList(), 0, emptyList(), "failed: ${it.javaClass.simpleName} ${it.message}")
            }
        }

    /** Forget the cached answer: the tree changed (a jar was patched or replaced). */
    fun forget(root: File) { cache.keys.removeAll { it.startsWith(root.absolutePath + "|") } }

    private fun compute(root: File, bootclasspath: String): Result {
        fun unchanged(why: String) = Result(bootclasspath, emptyList(), emptyList(), 0, emptyList(), why)
        if (bootclasspath.isBlank()) return unchanged("empty list")
        if (!applies(root)) return unchanged("not a Dalvik tree")

        val entries = bootclasspath.split(':').filter { it.isNotBlank() }
        val files = entries.map { it to File(root, it.removePrefix("/")) }
        val absent = files.filter { !it.second.isFile }.map { it.first }

        // what the boot jars define
        val defined = HashSet<String>()
        val unreadable = ArrayList<String>()
        val bootDex = ArrayList<ByteArray>()
        for ((name, f) in files) {
            if (!f.isFile) continue
            val bytes = dexOf(f)
            if (bytes == null) unreadable += name.substringAfterLast('/') else { defined += dexClasses(bytes); bootDex += bytes }
        }
        // an odexed jar has its code in a .odex next to it: what it defines cannot be told, so "missing" would be a guess
        if (unreadable.isNotEmpty()) return Result(bootclasspath, emptyList(), absent, 0, emptyList(), "no classes.dex in ${unreadable.joinToString()} (odexed)")

        val referenced = referencedClasses(File(root, "system/lib/libandroid_runtime.so"))
        val missing = referenced.filter { it !in defined }
        // second source: classes that the boot jars themselves refer to (type_ids) and no boot jar defines. A vendor
        // services.jar / framework.jar that uses mediatek-framework.jar or gionee-common.jar fails at its first use
        // (system_server: NoClassDefFoundError in PowerManagerService) when the jar is not on the path.
        val unresolved = HashSet<String>()
        for (b in bootDex) for (t in typeRefs(b)) if (t !in defined) unresolved += t
        unresolved.removeAll { it.startsWith("Ljava/") || it.startsWith("Ljavax/") }

        // jars of the framework directory that are not on the path
        val onPath = entries.toSet()
        val extras = File(root, "system/framework").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jar") && "/system/framework/${it.name}" !in onPath }
            ?.sortedBy { it.name } ?: emptyList()

        val remaining = missing.toMutableSet()
        val added = ArrayList<String>()
        val recovered = ArrayList<String>()
        for (jar in extras) {
            // the engine's own jars (aemu-*.jar) are loaded by their own services, never by the boot class path
            if (jar.name.startsWith("aemu-")) continue
            val set = classesOf(jar) ?: continue
            val hit = remaining.filter { it in set }
            val refd = unresolved.filter { it in set }
            if (hit.isEmpty() && refd.isEmpty()) continue
            added += "/system/framework/${jar.name}"
            recovered += (hit + refd).distinct().map { it.removePrefix("L").removeSuffix(";") }
            remaining.removeAll(hit.toSet())
            unresolved.removeAll(refd.toSet())
        }
        // third source: classes that the installed system apps use and only an unlisted jar defines. The firmware's phone and
        // contacts apps (Gionee: gionee/telephony/GnTelephonyManager) fail in their first static initialiser, the phone service
        // never comes up and the boot waits for it. Jars that the firmware itself declares as shared libraries
        // (<library file=...> in system/etc/permissions, loaded per app through uses-library, e.g. javax.obex.jar) are left alone.
        val appNeeds = appUnresolved(root, defined, extras.filter { !it.name.startsWith("aemu-") && "/system/framework/${it.name}" !in added }, libraryJars(root))
        for ((jarPath, classes) in appNeeds) {
            if (jarPath in added) continue
            added += jarPath
            recovered += classes.take(5).map { it.removePrefix("L").removeSuffix(";") }
        }
        val list = if (added.isEmpty()) bootclasspath else bootclasspath.trimEnd(':') + ":" + added.joinToString(":")
        return Result(list, added, absent, remaining.size + unresolved.size, recovered, null)
    }

    /** The classes.dex of [jar]; null when the jar has none (odexed, or not a jar). */
    internal fun dexOf(jar: File): ByteArray? = runCatching {
        ZipFile(jar).use { z ->
            val e = z.getEntry("classes.dex")
            if (e == null) null else z.getInputStream(e).use { it.readBytes() }
        }
    }.getOrNull()

    /** Descriptors ("Landroid/os/Binder;") of the classes in the classes.dex of [jar]; null when the jar has none. */
    internal fun classesOf(jar: File): Set<String>? = dexOf(jar)?.let { dexClasses(it) }

    /** Class descriptors that the dex mentions in its type_ids (no arrays, no primitives). */
    internal fun typeRefs(dex: ByteArray): Set<String> {
        val x = DexTools.Dex(dex)
        val out = HashSet<String>()
        for (i in 0 until x.typeIds) { val t = x.type(i); if (t.startsWith("L")) out += t }
        return out
    }

    internal fun dexClasses(dex: ByteArray): Set<String> {
        val x = DexTools.Dex(dex)
        val out = HashSet<String>(x.classDefs * 2)
        for (c in 0 until x.classDefs) out += x.type(DexTools.u4(dex, x.classDefsOff + 32 * c))
        return out
    }

    /** Jars the firmware declares as shared libraries (the .xml files in system/etc/permissions, <library name=".." file="..">). */
    internal fun libraryJars(root: File): Set<String> {
        val out = HashSet<String>()
        val re = Regex("""<library\s[^>]*file\s*=\s*"([^"]+)"""")
        File(root, "system/etc/permissions").listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }?.forEach { f ->
            runCatching { re.findAll(f.readText()).forEach { out += it.groupValues[1] } }
        }
        return out
    }

    /**
     * Extra jar (guest path) -> classes of it that a system app refers to, defines nowhere in its own dex files and no boot jar
     * defines. Only jars in [extras] that are not shared libraries are looked at.
     */
    internal fun appUnresolved(root: File, bootDefined: Set<String>, extras: List<File>, libraries: Set<String>): LinkedHashMap<String, MutableList<String>> {
        val owner = HashMap<String, String>()
        for (jar in extras) {
            val path = "/system/framework/${jar.name}"
            if (path in libraries) continue
            val set = classesOf(jar) ?: continue
            for (c in set) if (c !in bootDefined) owner.putIfAbsent(c, path)
        }
        val out = LinkedHashMap<String, MutableList<String>>()
        if (owner.isEmpty()) return out
        val apks = listOf("system/app", "system/priv-app", "vendor/app").flatMap { d ->
            File(root, d).listFiles()?.filter { it.isFile && it.name.endsWith(".apk") }?.sortedBy { it.name } ?: emptyList()
        }
        for (apk in apks) {
            val dexes = runCatching {
                ZipFile(apk).use { z ->
                    z.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }
                        .map { e -> z.getInputStream(e).use { it.readBytes() } }.toList()
                }
            }.getOrNull() ?: continue
            if (dexes.isEmpty()) continue
            val own = HashSet<String>()
            for (b in dexes) own += dexClasses(b)
            for (b in dexes) for (t in typeRefs(b)) {
                val jar = owner[t] ?: continue
                if (t in own) continue
                out.getOrPut(jar) { ArrayList() }.let { if (it.size < 50 && t !in it) it += t }
            }
        }
        return out
    }

    private val NAME = Regex("^(android|com|java|javax|dalvik|libcore|org|junit|sun)/[A-Za-z0-9_$]+(/[A-Za-z0-9_$]+)+$")

    /**
     * The class names libandroid_runtime.so carries as whole strings, as descriptors. FindClass() takes exactly this form
     * ("android/graphics/Bitmap"); signatures ("(Landroid/os/Parcel;)V") and file paths are not whole matches and are skipped.
     */
    internal fun referencedClasses(lib: File): Set<String> {
        val d = lib.readBytes()
        val out = LinkedHashSet<String>()
        var start = -1
        for (i in 0..d.size) {
            val b = if (i < d.size) d[i].toInt() and 0xff else 0
            val printable = b in 0x21..0x7e
            if (printable) { if (start < 0) start = i; continue }
            if (start >= 0 && i - start in 9..160) {
                val s = String(d, start, i - start, Charsets.ISO_8859_1)
                if (NAME.matches(s) && !s.endsWith(".so") && !s.endsWith(".jar")) out += "L$s;"
            }
            start = -1
        }
        return out
    }
}
