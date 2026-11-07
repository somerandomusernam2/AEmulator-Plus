package app.aemu.core

import java.io.File

/**
 * Added for AEmulator Plus (2026-10-30). The HIDL libraries of the engine's 32-bit composer@2.1 service.
 *
 * The service (native/composer32) dlopen()s android.hardware.graphics.composer@2.1.so by name, and its passthrough impl and
 * android.hardware.graphics.composer@2.1-resources.so name the mapper@2.0/3.0/4.0 and graphics.common libraries. A modern
 * 32-bit-only firmware (Google TV Streamer, Android 14, AIDL composer3) keeps those HIDL interface libraries in /system/lib
 * or its VNDK directories, where the default linker namespace of a vendor program does not look, and the engine's 32-bit layer
 * ships none of them. Result:
 *
 *   composer@2.1-service: android.hardware.graphics.composer@2.1.so: dlopen failed: library "...composer@2.1.so" not found
 *   (service returns from main(), bionic runs its fini array, SIGSEGV at pc 4: exit 139; SurfaceFlinger never gets a composer)
 *
 * Same idea as KeymasterLibs: bring the HIDL interface libraries the impl's closure needs into /vendor/lib, from wherever the
 * tree has them. Only libraries that no directory the vendor namespace searches already has are copied: nothing the firmware
 * ships in /vendor/lib or /odm/lib is ever overwritten. No Android classes here.
 */
object ComposerLibs {
    private val REACH = listOf("odm/lib", "vendor/lib")

    /** HIDL/AIDL interface libraries: generated code, safe to take from the platform when the vendor side lacks them. */
    fun bringable(name: String) = name.startsWith("android.hardware.") || name.startsWith("android.hidl.") ||
        name.startsWith("android.frameworks.") || name.startsWith("android.system.")

    class Result(val impl: File?, val copied: List<String>, val missing: List<String>) {
        val ok get() = impl != null && missing.isEmpty()
    }

    fun complete(root: File, log: (String) -> Unit): Result {
        val impl = listOf("vendor/lib/hw", "system/vendor/lib/hw", "vendor/lib", "system/vendor/lib")
            .map { File(root, "$it/android.hardware.graphics.composer@2.1-impl.so") }.firstOrNull { it.isFile }
        if (impl == null) { log("composer: no 32-bit composer@2.1-impl.so in the tree"); return Result(null, emptyList(), emptyList()) }
        val copied = ArrayList<String>()
        // (a library that comes over names others: look again until a round brings nothing)
        for (round in 0 until 6) {
            val c = KeymasterLibs.closure(root, impl)
            var changed = false
            for (f in c.files.drop(1)) {
                if (!bringable(f.name) || REACH.any { File(root, "$it/${f.name}").isFile }) continue
                val dst = File(root, "vendor/lib/${f.name}")
                runCatching {
                    dst.parentFile?.mkdirs()
                    f.copyTo(dst, overwrite = true)
                    dst.setReadable(true, false); dst.setExecutable(true, false)
                    copied += f.name; changed = true
                    log("composer: ${f.name} brought into /vendor/lib from ${f.parentFile?.relativeTo(root)?.path} (not visible to a vendor program there)")
                }.onFailure { log("composer: ${f.name} could not be put into /vendor/lib: ${it.message}") }
            }
            if (!changed) break
        }
        val c = KeymasterLibs.closure(root, impl)
        val missing = c.missing
        if (missing.isEmpty()) log("composer: every library of ${impl.name} found (${c.files.size - 1})")
        else log("composer: NOT FOUND anywhere in the tree (system/lib, VNDK dirs, vendor/lib): " + missing.joinToString(", ") +
            (if (missing.any { it.startsWith("android.hardware.graphics.composer@2.1.so") })
                " - the 32-bit composer@2.1 service cannot start without its interface library; it has to be added to engines/<kernel>/arm32 " +
                "(vendor#lib#android.hardware.graphics.composer@2.1.so, built from AOSP hardware/interfaces/graphics/composer/2.1)" else ""))
        // the service also dlopens these by name: libc++, libhidlbase, libfmq (the platform's, reachable through the linker config)
        return Result(impl, copied, missing)
    }
}
