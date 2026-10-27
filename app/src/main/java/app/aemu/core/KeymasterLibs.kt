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

    /**
     * Makes the keymaster libraries of the tree reachable from /vendor/lib and reports what is still wrong.
     * Never throws; every outcome is logged with a "keymaster:" prefix.
     */
    fun complete(root: File, log: (String) -> Unit): Result {
        val impl = listOf("vendor/lib/android.hardware.keymaster@3.0-impl.so", "vendor/lib/hw/android.hardware.keymaster@3.0-impl.so")
            .map { File(root, it) }.firstOrNull { it.isFile }
        if (impl == null) {
            log("keymaster: the tree has no 32-bit android.hardware.keymaster@3.0-impl.so")
            return Result(null, emptyList(), emptyList(), emptyList())
        }
        val copied = ArrayList<String>()
        // (a library that comes over may name others: look again until a round copies nothing)
        for (round in 0 until 4) {
            var changed = false
            for (f in closure(root, impl).files.drop(1)) {
                if (!bringable(f.name) || DEFAULT_DIRS.any { File(root, "$it/${f.name}").isFile }) continue
                val dst = File(root, "vendor/lib/${f.name}")
                runCatching {
                    dst.parentFile?.mkdirs()
                    f.copyTo(dst, overwrite = false)
                    dst.setReadable(true, false); dst.setExecutable(true, false)
                    copied += f.name; changed = true
                    log("keymaster: ${f.name} brought into /vendor/lib from ${f.parentFile?.relativeTo(root)?.path ?: "?"} (the vendor's linker namespace does not see it there)")
                }.onFailure { log("keymaster: ${f.name} could not be copied into /vendor/lib: ${it.message}") }
            }
            if (!changed) break
        }
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
        val r = Result(impl, copied, c.missing, unresolved)
        if (r.ok) log("keymaster: the 32-bit software keymaster can be loaded (libraries found, keymaster symbols resolve)")
        return r
    }
}
