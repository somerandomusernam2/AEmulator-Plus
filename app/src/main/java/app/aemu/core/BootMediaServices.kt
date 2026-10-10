/* AEmulator Sunset addition, 2026-10-03. GPL-3.0. */
package app.aemu.core

import java.io.File

/** Optional firmware boot media only. Never auto-restart or run charging services. */
internal object BootMediaServices {
    private val names = setOf("bootanim", "bootanimation", "samsungani", "playsound", LEGACY_LOGO)

    /**
     * Samsung 2.x (Galaxy S, Froyo/Gingerbread-era firmware) has no bootanimation.zip. Its init.rc starts one
     * oneshot service, `playlogos1`, which waits for SurfaceFlinger, paints bootsamsung.qmg / bootsamsungloop.qmg
     * straight into /dev/graphics/fb0, plays /system/etc/PowerOn.wav through MediaPlayer and quits by itself once
     * the framework sets dev.bootcomplete=1. InitPlan skips oneshot services, so it never ran here.
     */
    const val LEGACY_LOGO = "playlogos1"

    /** Marker file in the VM's bin directory: its presence turns the legacy boot logo/sound off. */
    const val LEGACY_LOGO_OFF = "no-bootlogo"

    /**
     * The `playlogos1` service for firmware that uses it, or null (other API levels, binary missing, or an init.rc
     * that does not mention it). Without any readable init.rc the binary alone decides.
     */
    fun legacyLogo(root: File, api: Int): GuestService? {
        if (api !in 1..13) return null
        val rcs = rcFiles(root)
        if (rcs.isNotEmpty() && InitPlan.parse(rcs).services[LEGACY_LOGO] == null) return null
        return resolve(root, LEGACY_LOGO)
    }

    /** [plan] with the legacy boot logo placed right before zygote (after mediaserver); unchanged when not applicable. */
    fun withLegacyLogo(plan: List<GuestService>, root: File, api: Int): List<GuestService> {
        if (plan.any { it.name == LEGACY_LOGO }) return plan
        val logo = legacyLogo(root, api) ?: return plan
        val z = plan.indexOfFirst { it.name == "zygote" }.let { if (it < 0) plan.size else it }
        return plan.toMutableList().also { it.add(z, logo) }
    }

    fun rcFiles(root: File): List<File> = listOf(root, File(root, "system/etc/init"))
        .flatMap { it.listFiles()?.filter { f -> f.isFile && f.name.endsWith(".rc") } ?: emptyList() }
        .filterNot { it.name.startsWith("init.charging") || it.name.startsWith("init.recovery") || it.name.startsWith("lpm") }
        .sortedBy { it.name }
    fun resolve(root: File, name: String): GuestService? {
        if (name !in names) return null
        val rc = InitPlan.parse(rcFiles(root))
        val aliases = if (name in setOf("bootanim", "bootanimation")) listOf("bootanim", "samsungani", "bootanimation") else listOf(name)
        val service = aliases.firstNotNullOfOrNull { rc.services[it] }
        val argv = service?.argv ?: when (name) {
            "playsound" -> listOf("/system/bin/playsound")
            "samsungani" -> listOf("/system/bin/samsungani")
            LEGACY_LOGO -> listOf("/system/bin/$LEGACY_LOGO")
            else -> listOf(if (File(root, "system/bin/samsungani").isFile) "/system/bin/samsungani" else "/system/bin/bootanimation")
        }
        if (argv.isEmpty() || !argv[0].startsWith('/')) return null
        val binary = File(root, argv[0].trimStart('/'))
        if (!binary.isFile || !binary.canonicalPath.startsWith(root.canonicalPath + File.separator)) return null
        return GuestService(if (name == "playsound" || name == LEGACY_LOGO) name else "bootanim", argv,
            service?.sockets?.toMap() ?: emptyMap(), optional = true)
    }
    /** True for Samsung TouchWiz firmware (same skin test TreeFixer uses). */
    fun isTouchWiz(skin: String) = skin.contains("TouchWiz", ignoreCase = true)

    /**
     * Boot media to launch proactively on TouchWiz: whichever of samsungani / playsound exist in /system/bin.
     * Anything absent is silently left out; a non-TouchWiz firmware gets nothing.
     */
    fun touchWizBootMedia(root: File, skin: String): List<String> =
        if (!isTouchWiz(skin)) emptyList()
        else listOf("samsungani", "playsound").filter { File(root, "system/bin/$it").isFile }

    fun triggers(root: File, property: String, value: String): List<Pair<Boolean, String>> {
        // Deliberately not a general init trigger interpreter.
        if (property != "service.bootanim.exit") return emptyList()
        val result = ArrayList<Pair<Boolean, String>>()
        for (file in rcFiles(root)) {
            var matching = false
            for (raw in file.readLines()) {
                val tokens = raw.substringBefore('#').trim().split(Regex("\\s+"))
                if (tokens[0] == "on") matching = tokens.size == 2 && tokens[1] == "property:$property=$value"
                else if (tokens[0] == "service") matching = false
                else if (matching && tokens.size == 2 && tokens[0] in setOf("start", "stop") && tokens[1] in names)
                    result += (tokens[0] == "start") to tokens[1]
            }
        }
        return result.distinct()
    }
}
