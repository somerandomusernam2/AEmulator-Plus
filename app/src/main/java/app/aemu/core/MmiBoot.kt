/* Added 2026-10-04: native boot of framework-less factory/MMI/FTM builds. GPL-3.0; see LICENSE. */
package app.aemu.core

import java.io.File

/**
 * Factory-test (MMI / FFBM / FTM) builds ship a normal-looking /system partition, but without the Java
 * framework, apps or zygote: their init only starts a native test program (ZTE: `fastmmi` → /system/bin/mmi)
 * that draws straight into fb0 through minui and reads /dev/input. The regular boot plan (servicemanager →
 * surfaceflinger → zygote) cannot work for them, so they are started like the offline-charging class:
 * a single native service, no binder, no framework.
 */
internal object MmiBoot {
    /** Properties of a factory-mode boot, in case the vendor binary or its scripts look at them. */
    val properties = mapOf("ro.bootmode" to "ffbm-01", "ro.boot.bootmode" to "ffbm-01", "ro.factorytest" to "1")

    /**
     * True when the tree has no Java framework. Do NOT test for the system/framework directory itself:
     * TreeFixer.fixup() always creates it (it drops aemu-setup.jar there), so after the first launch the
     * directory exists even on a framework-less image.
     */
    fun frameworkless(root: File): Boolean =
        !File(root, "system/framework/framework.jar").isFile && !File(root, "system/framework/services.jar").isFile

    private val NAMES = listOf("fastmmi", "mmi", "ftm", "ftm_mode")

    private fun rcFiles(root: File): List<File> =
        listOf(root, File(root, "system/etc/init")).flatMap { dir ->
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".rc") }?.sortedBy { it.name } ?: emptyList()
        }

    private fun exists(root: File, path: String) = path.startsWith("/") && File(root, path.trimStart('/')).isFile

    /** The native test UI service of the firmware, or null if the tree has none. */
    fun service(root: File): GuestService? {
        val rc = InitPlan.parse(rcFiles(root))
        val found = NAMES.firstNotNullOfOrNull { rc.services[it] }
            ?: rc.services.values.firstOrNull { it.argv.firstOrNull()?.substringAfterLast('/') == "mmi" }
        if (found != null && found.argv.isNotEmpty() && exists(root, found.argv.first()))
            return GuestService("fastmmi", found.argv, found.sockets.toMap())
        // init scripts were not imported (system partition only): the binary alone is enough
        for (p in listOf("/system/bin/mmi", "/sbin/mmi", "/system/bin/fastmmi"))
            if (exists(root, p)) return GuestService("fastmmi", listOf(p))
        return null
    }

    /**
     * Other services the test program may start with `ctl.start`. Only plain guest binaries from the
     * usual directories: everything else in init.rc is hardware setup the host does not need.
     */
    fun controllable(root: File): Map<String, GuestService> {
        val rc = InitPlan.parse(rcFiles(root))
        val dirs = listOf("/system/bin/", "/system/xbin/", "/vendor/bin/", "/sbin/")
        return rc.services.values.filter { s ->
            s.argv.isNotEmpty() && dirs.any { s.argv.first().startsWith(it) } && exists(root, s.argv.first()) &&
                s.name !in setOf("zygote", "surfaceflinger", "vold", "ueventd", "adbd", "debuggerd", "logd", "healthd")
        }.associate { it.name to GuestService(it.name, it.argv, it.sockets.toMap(), optional = true) }
    }
}
