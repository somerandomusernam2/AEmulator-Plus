/* Added for AEmulator Plus, 2026-11-04. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

/**
 * What the "Guest RAM" setting (VmSettings.ramMb) can hold: 0 = automatic (what phones of that Android version shipped
 * with), [HOST] = all of the host's RAM, any other positive number = that many MB (the slider's presets or a custom
 * amount typed by the user).
 */
object RamChoice {
    const val AUTO = 0
    const val HOST = -1

    /** The fixed steps of the slider between "automatic" and "host / max". */
    val PRESETS_MB = listOf(256, 512, 768, 1024, 1536, 2048, 3072, 4096, 6144, 8192, 12288)

    /** Smallest amount the custom box accepts. */
    const val CUSTOM_MIN_MB = 64

    /** The host's total RAM in MB. */
    fun hostMb(): Int = runCatching {
        java.io.File("/proc/meminfo").useLines { l -> l.first { it.startsWith("MemTotal:") } }
            .split(Regex("\\s+"))[1].toLong() / 1024
    }.getOrDefault(8192L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

    /** The setting as a number of MB: [HOST] becomes the host's RAM, everything else is returned as it is (0 = automatic). */
    fun resolve(chosen: Int): Int = if (chosen == HOST) hostMb() else chosen
}
