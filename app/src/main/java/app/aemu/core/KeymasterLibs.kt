package app.aemu.core

import java.io.File

/**
 * Added for AEmulator Plus (2026-10-25). The software keymaster of a 32-bit-only Treble firmware.
 *
 * The engine's keymaster@3.0 host (engines/n64-<api>/arm32) loads android.hardware.keymaster@3.0-impl.so, which names
 * libsoftkeymasterdevice.so, libkeymaster_portable.so, libkeymaster_messages.so, libkeymaster3device.so and
 * libpuresoftkeymasterdevice.so. A Qualcomm Wear OS 3 firmware talks to the secure world and carries no software
 * keymaster of its own, so some of these are missing from the default linker namespace of a vendor program:
 *
 *   aemu-keymaster-host: dlopen android.hardware.keymaster@3.0-impl.so: library "libsoftkeymasterdevice.so" not found:
 *   needed by /vendor/lib/android.hardware.keymaster@3.0-impl.so in namespace (default)
 *
 * The host exits with 1, keystore never gets a keymaster, "android.security.keystore" is never registered and
 * system_server dies at boot phase 600 (TrustManagerService -> KeyStore.onUserLockedStateChanged on a null
 * IKeystoreService), which makes zygote exit with 137.
 *
 * TreeFixer only checked the 64-bit trees ("deps: 32-bit firmware, the 64-bit library checks do not apply"), so nothing
 * said which library was missing. This does for 32-bit what libClosure() does for 64-bit, brings the keymaster
 * libraries over from wherever the tree has them (system/lib, the VNDK directories) into /vendor/lib, and says whether
 * the set that results can link at all: the symbols of the engine's libraries must be defined by the ones they get.
 *
 * One release, not a mixture (2026-10-28). Bringing over "whatever is missing" library by library produced a set made of
 * two releases whenever the firmware's /vendor/lib already had some of the family (a Qualcomm firmware carries
 * libkeymaster_messages / libkeymaster_portable for its own QSEE keymaster) and the rest came from the platform's
 * system/lib. Those libraries are not independent: libkeymaster_portable calls virtual methods of objects that
 * libsoftkeymasterdevice builds (the context, its key blob maker and random source), so the vtable layout has to be the
 * one both were compiled against. With two releases mixed, SymmetricKeyFactory::ImportKey called "CreateKeyBlob" and the
 * slot it called was the destructor of PureSoftKeymasterContext in the other release's vtable:
 *
 *   #3 libsoftkeymasterdevice.so keymaster::SoftKeymasterEnforcement::~SoftKeymasterEnforcement()
 *   #4 libsoftkeymasterdevice.so non-virtual thunk to keymaster::PureSoftKeymasterContext::~PureSoftKeymasterContext()
 *   #5 libkeymaster_portable.so  keymaster::SymmetricKeyFactory::ImportKey(...)
 *   #6 libkeymaster_portable.so  keymaster::AndroidKeymaster::ImportKey(...)
 *
 * (a destructor running inside an import, then free() of a pointer jemalloc never handed out: SIGSEGV in the HAL, exit 139,
 * keystore2 "DEAD_OBJECT" on importKey, LockSettingsService "Failed to encrypt blob", system_server dies, zygote exits 137).
 * Every family member that the platform also has is now the platform's copy, all of them or none; [plan] says which.
 *
 * No Android classes here, so it can be tested on a plain JVM.
 */
object KeymasterLibs {
    /** Where a vendor program's default linker namespace looks first. A library here needs no copy. */
    private val DEFAULT_DIRS = listOf("vendor/lib", "odm/lib")

    /** Where the tree may have a 32-bit library (the order is the order of preference). */
    private val SEARCH = listOf(
        "vendor/lib", "vendor/lib/hw", "odm/lib", "odm/lib/hw", "system/vendor/lib", "system/vendor/lib/hw",
        "system/lib", "system/lib/hw", "apex/com.android.runtime/lib/bionic", "apex/com.android.runtime/lib",
    )

    class Closure(val files: List<File>, val missing: List<String>)

    class Result(
        /** the impl that was checked; null when the tree has none */
        val impl: File?,
        /** libraries copied into vendor/lib */
        val copied: List<String>,
        /** libraries no directory of the tree has, as "name (from file)" */
        val missing: List<String>,
        /** keymaster symbols that nothing in the closure defines, as "symbol (file)" */
        val unresolved: List<String>,
        /** libraries of /vendor/lib (or /odm/lib) that were of another release than the rest of the family and were overwritten */
        val replaced: List<String> = emptyList(),
    ) {
        val ok get() = impl != null && missing.isEmpty() && unresolved.isEmpty()
    }

    /** The VNDK directories of a 32-bit tree: system/lib/vndk*, and the unpacked VNDK APEXes. */
    fun vndkDirs(root: File): List<String> {
        val out = ArrayList<String>()
        File(root, "system/lib").listFiles()?.filter { it.isDirectory && it.name.startsWith("vndk") }?.sortedBy { it.name }
            ?.forEach { out += "system/lib/${it.name}" }
        File(root, "apex").listFiles()?.filter { it.isDirectory && it.name.startsWith("com.android.vndk") }?.sortedBy { it.name }
            ?.forEach { out += "apex/${it.name}/lib" }
        return out
    }

    /**
     * The libraries this brings over (the keymaster ones only: a copy of anything else could shadow the firmware's own).
     * That is the family of AOSP's system/keymaster: libkeymaster_messages / libkeymaster_portable / libkeymaster3device,
     * libsoftkeymasterdevice, libpuresoftkeymasterdevice and libsoft_attestation_cert. The last one was missing from the
     * list (it is neither "libkeymaster*" nor one of the two names), so it stayed in system/lib, where the default
     * namespace of a vendor program does not look, and the HAL died in dlopen with
     *   library "libsoft_attestation_cert.so" not found: needed by /vendor/lib/libsoftkeymasterdevice.so in namespace (default)
     * while the check above it said "every library found": found in the tree, not reachable from where the HAL runs.
     */
    fun bringable(name: String) = name.startsWith("libkeymaster") || name.startsWith("libsoft") || name.startsWith("libpuresoft")

    /**
     * Two copies of keymaster::PureSoftKeymasterContext in one process (2026-10-29).
     *
     * libpuresoftkeymasterdevice.so (what libkeymaster3device builds its context from: PureSoftKeymasterContext())
     * and libsoftkeymasterdevice.so (PureSoftKeymasterContext(keymaster_security_level_t), one more member) both define
     * the class, its vtable and its destructors with default visibility. android.hardware.keymaster@3.0-impl.so names
     * libsoftkeymasterdevice.so BEFORE libpuresoftkeymasterdevice.so, so the global symbol lookup binds the vtable and the
     * destructors to the soft copy while the object is built and sized by the pure copy. Result, with every library of
     * the set being of one release (the check above says so, and it was right):
     *
     *   #3 libsoftkeymasterdevice.so  ~SoftKeymasterEnforcement()      <- wrong copy, wrong layout
     *   #5 libkeymaster_portable.so   SymmetricKeyFactory::ImportKey   -> free() of a pointer jemalloc never gave out
     *
     * The impl imports nothing that only libsoftkeymasterdevice defines (checked in [implNeedsSoftDevice]), so its
     * DT_NEEDED entry for it only drags the second definition in. The entry is pointed at libkeymaster_portable.so (already
     * loaded, 24 characters fit into the 25 of the old name), so only one PureSoftKeymasterContext exists.
     */
    private const val SOFT = "libsoftkeymasterdevice.so"
    private const val SOFT_REPLACEMENT = "libkeymaster_portable.so"

    /** true when [impl] undefined-imports a symbol that libsoftkeymasterdevice defines and no other library of the closure does. */
    private fun implNeedsSoftDevice(root: File, impl: File): Boolean {
        val c = closure(root, impl)
        val soft = c.files.firstOrNull { it.name == SOFT } ?: return false
        val implSyms = ElfPatch.dynSyms32(impl) ?: return true
        val softDefs = ElfPatch.dynSyms32(soft)?.defined ?: return true
        val others = HashSet<String>()
        for (f in c.files) if (f != impl && f != soft) ElfPatch.dynSyms32(f)?.let { others += it.defined }
        return implSyms.undefined.any { it in softDefs && it !in others }
    }

    /** Makes the impl load ONE PureSoftKeymasterContext. Returns true when the impl no longer names libsoftkeymasterdevice. */
    fun dropDuplicateSoftDevice(root: File, impl: File, log: (String) -> Unit): Boolean {
        val names = ElfPatch.needed32(impl).orEmpty()
        if (SOFT !in names) return true
        if (!names.contains("libpuresoftkeymasterdevice.so")) return false      // (nothing else defines the class: keep it)
        if (implNeedsSoftDevice(root, impl)) {
            log("keymaster: ${impl.name} imports symbols only $SOFT defines, DT_NEEDED left as it is")
            return false
        }
        val n = runCatching {
            impl.setWritable(true, true)
            ElfPatch.redirectNeeded(impl, SOFT, SOFT_REPLACEMENT)
        }.getOrDefault(0)
        runCatching { impl.setReadable(true, false) }
        log(if (n > 0) "keymaster: ${impl.name}: DT_NEEDED $SOFT -> $SOFT_REPLACEMENT (libpuresoftkeymasterdevice and libsoftkeymasterdevice " +
            "both define PureSoftKeymasterContext; one copy only, else importKey frees through the wrong vtable, exit 139)"
        else "keymaster: ${impl.name}: DT_NEEDED $SOFT could not be redirected")
        return n > 0
    }

    private fun keymasterSymbol(s: String) = s.contains("8keymaster") || s.contains("9keymaster")

    /** [start] and every library it names (all the way down) that the 32-bit directories of the tree have, plus the names none has. */
    fun closure(root: File, start: File): Closure {
        val dirs = (SEARCH + vndkDirs(root)).map { File(root, it) }
        val seen = HashSet<String>()
        val missing = ArrayList<String>()
        val files = arrayListOf(start)
        val queue = ArrayDeque<File>()
        queue.add(start)
        var steps = 0
        while (queue.isNotEmpty() && steps++ < 300) {
            val cur = queue.removeFirst()
            for (lib in ElfPatch.needed32(cur).orEmpty()) {
                if (!seen.add(lib)) continue
                val hit = dirs.map { File(it, lib) }.firstOrNull { it.isFile }
                if (hit == null) missing += "$lib (from ${cur.name})" else { queue.add(hit); files += hit }
            }
        }
        return Closure(files, missing)
    }

    /** Where the vendor linker namespace looks for a library, in the order it looks. */
    private val REACH = listOf("odm/lib", "vendor/lib")

    /** The platform's own directories: the release every other library of the system partition was built together with. */
    private fun platformDirs(root: File) = (listOf("system/lib") + vndkDirs(root)).map { File(root, it) }

    /**
     * One member of the keymaster family as the tree has it. [reachable] is the SHA-1 of the copy a vendor program would
     * load (null: it is in no directory the vendor namespace searches), [platform] the SHA-1 of the platform's copy (null:
     * the platform has none).
     */
    class Member(val name: String, val reachable: String?, val platform: String?)

    class Plan(
        /** names to take from the platform's directories into the vendor namespace (absent there, or of another release) */
        val take: List<String>,
        /** the subset of [take] that overwrites a copy the firmware had */
        val overwrite: List<String>,
        /** what the tree looked like, for the log */
        val verdict: String,
    )

    /**
     * Which members have to be the platform's copy so that the whole family is of one release.
     *
     *  - nothing differs from the platform's copy: nothing to overwrite (absent ones are only brought);
     *  - every member the platform also has differs, and none was brought from it: the firmware's own set, built together,
     *    stays as it is;
     *  - some are the platform's and some are not (an earlier start brought only the missing ones over, or the firmware
     *    has part of the family): a mixture of releases; every member that has a platform copy becomes that copy.
     */
    fun plan(members: List<Member>): Plan {
        val absent = members.filter { it.reachable == null && it.platform != null }.map { it.name }
        val same = members.filter { it.reachable != null && it.platform != null && it.reachable == it.platform }.map { it.name }
        val differ = members.filter { it.reachable != null && it.platform != null && it.reachable != it.platform }.map { it.name }
        return when {
            differ.isEmpty() -> Plan(absent, emptyList(),
                if (absent.isEmpty()) "one release (the platform's)" else "one release (the platform's; ${absent.size} brought over)")
            same.isEmpty() && absent.isEmpty() -> Plan(emptyList(), emptyList(), "one release (the firmware's own set)")
            else -> Plan(absent + differ, differ,
                "MIXED releases: ${same.size + absent.size} of the platform's (${(same + absent).joinToString()}), " +
                    "${differ.size} of the firmware's (${differ.joinToString()})")
        }
    }

    private fun sha1(f: File): String = java.security.MessageDigest.getInstance("SHA-1").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    /** The family members the library closure of [impl] names (those found anywhere, and those found nowhere). */
    private fun familyNames(root: File, impl: File): List<String> {
        val c = closure(root, impl)
        return (c.files.drop(1).map { it.name } + c.missing.map { it.substringBefore(' ') }).filter { bringable(it) }.distinct().sorted()
    }

    private fun members(root: File, names: List<String>): List<Pair<Member, Pair<File?, File?>>> {
        val platform = platformDirs(root)
        return names.map { n ->
            val reach = REACH.map { File(root, "$it/$n") }.firstOrNull { it.isFile }
            val plat = platform.map { File(it, n) }.firstOrNull { it.isFile }
            Member(n, reach?.let { sha1(it) }, plat?.let { sha1(it) }) to (reach to plat)
        }
    }

    /**
     * Makes the keymaster libraries of the tree reachable from /vendor/lib, all of one release, and reports what is still wrong.
     * Never throws; every outcome is logged with a "keymaster:" prefix. A firmware file that is overwritten is kept once in
     * system/.aemu-parked/<path with # for />.
     */
    fun complete(root: File, log: (String) -> Unit): Result {
        val impl = listOf("vendor/lib/android.hardware.keymaster@3.0-impl.so", "vendor/lib/hw/android.hardware.keymaster@3.0-impl.so")
            .map { File(root, it) }.firstOrNull { it.isFile }
        if (impl == null) {
            log("keymaster: the tree has no 32-bit android.hardware.keymaster@3.0-impl.so")
            return Result(null, emptyList(), emptyList(), emptyList())
        }
        val copied = ArrayList<String>()
        val replaced = ArrayList<String>()
        // (a library that comes over may name others: look again until a round changes nothing)
        for (round in 0 until 4) {
            val ms = members(root, familyNames(root, impl))
            val p = plan(ms.map { it.first })
            if (round == 0) {
                for ((m, files) in ms) log("keymaster: ${m.name}: " + when {
                    files.first == null && files.second == null -> "in no directory of the tree"
                    files.first == null -> "not in /vendor/lib, the platform has it (${files.second!!.parentFile?.relativeTo(root)?.path})"
                    files.second == null -> "${files.first!!.parentFile?.relativeTo(root)?.path} only (${m.reachable!!.take(8)})"
                    m.reachable == m.platform -> "${files.first!!.parentFile?.relativeTo(root)?.path} is the platform's copy (${m.reachable!!.take(8)})"
                    else -> "${files.first!!.parentFile?.relativeTo(root)?.path} ${m.reachable!!.take(8)} differs from the platform's ${m.platform!!.take(8)}"
                })
                log("keymaster: ${p.verdict}")
            }
            if (p.take.isEmpty()) break
            var changed = false
            for (n in p.take) {
                val (_, files) = ms.first { it.first.name == n }
                val src = files.second ?: continue
                val dst = files.first ?: File(root, "vendor/lib/$n")
                runCatching {
                    if (dst.isFile) {
                        val park = File(root, "system/.aemu-parked/${dst.relativeTo(root).path.replace('/', '#')}")
                        if (!park.exists()) { park.parentFile?.mkdirs(); dst.copyTo(park) }
                    }
                    dst.parentFile?.mkdirs()
                    if (dst.exists()) dst.setWritable(true, true)
                    src.copyTo(dst, overwrite = true)
                    dst.setReadable(true, false); dst.setExecutable(true, false)
                    changed = true
                    if (n in p.overwrite) { replaced += n; log("keymaster: $n of /${dst.parentFile?.relativeTo(root)?.path} replaced by the platform's ${src.parentFile?.relativeTo(root)?.path}/$n (the firmware's is kept in system/.aemu-parked)") }
                    else { copied += n; log("keymaster: $n brought into /${dst.parentFile?.relativeTo(root)?.path} from ${src.parentFile?.relativeTo(root)?.path}") }
                }.onFailure { log("keymaster: $n could not be put into /vendor/lib: ${it.message}") }
            }
            if (!changed) break
        }
        dropDuplicateSoftDevice(root, impl, log)
        val c = closure(root, impl)
        // (while a keymaster library is still missing every symbol it would define is "unresolved": that says nothing)
        val kmMissing = c.missing.any { bringable(it.substringBefore(' ')) }
        val unresolved = ArrayList<String>()
        if (!kmMissing) {
            val syms = c.files.map { it to ElfPatch.dynSyms32(it) }
            val defined = HashSet<String>()
            for ((_, s) in syms) if (s != null) defined += s.defined
            for ((file, s) in syms) if (s != null) for (u in s.undefined.sorted()) if (keymasterSymbol(u) && u !in defined) unresolved += "$u (${file.name})"
        }
        log("keymaster: ${impl.name}: " + if (c.missing.isEmpty()) "every library found (${c.files.size - 1})" else "NOT FOUND " + c.missing.joinToString(", "))
        // (what the HAL loads from outside the directories a vendor program searches first: the first thing to read when
        // dlopen still says "not found" although every library was "found")
        val outside = c.files.drop(1).filter { f -> DEFAULT_DIRS.none { File(root, "$it/${f.name}").isFile } }
        if (outside.isNotEmpty()) log("keymaster: loaded from outside /vendor/lib (the linker config decides whether the vendor namespace sees them): " +
            outside.take(40).joinToString(", ") { it.name + " [" + (it.parentFile?.relativeTo(root)?.path ?: "?") + "]" })
        if (unresolved.isNotEmpty())
            log("keymaster: ${unresolved.size} keymaster symbols have no definition in the libraries the HAL would load (${c.files.joinToString(", ") { it.name }}); " +
                "the keymaster libraries of the firmware are not the release the engine's keymaster was built against. First: " + unresolved.take(6).joinToString("; "))
        if (kmMissing) log("keymaster: the tree has no 32-bit " + c.missing.map { it.substringBefore(' ') }.filter { bringable(it) }.joinToString(", ") +
            " anywhere (system/lib, the VNDK directories, vendor/lib): the HAL cannot load until they are in the tree")
        val r = Result(impl, copied, c.missing, unresolved, replaced)
        if (r.ok) log("keymaster: the 32-bit software keymaster can be loaded (libraries found, keymaster symbols resolve)")
        return r
    }
}
