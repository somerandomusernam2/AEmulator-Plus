/* AEmulator Sunset addition, 2026-10-03. GPL-3.0. */
package app.aemu.core

import java.io.File

/** A fixed guest screen size, or [HOST] (follow the phone's own screen). */
internal data class ResolutionPreset(val width: Int, val height: Int) {
    val isHost get() = this == HOST

    val label get() = if (isHost) "" else "${width}×${height}"

    fun selected(settings: VmSettings) = if (isHost) settings.hostResolution
        else !settings.hostResolution && settings.width == width && settings.height == height

    /**
     * [firmwareDensity] is the firmware's own ro.sf.lcd_density (see [buildPropDensity]); when the firmware has none,
     * the density already in the settings stays.
     */
    fun apply(settings: VmSettings, firmwareDensity: Int? = null) = if (isHost) settings.copy(hostResolution = true)
        else settings.copy(width = width, height = height, density = firmwareDensity ?: settings.density, hostResolution = false)

    companion object {
        val HOST = ResolutionPreset(0, 0)

        private val PORTRAIT = listOf(
            280 to 280, 320 to 320, 360 to 360, 320 to 480, 480 to 800, 540 to 960, 720 to 1280, 720 to 1560,
            768 to 1280, 1080 to 1920, 1536 to 2048, 1080 to 2160, 1080 to 2340, 1080 to 2400, 1200 to 2560,
            1440 to 2560, 1800 to 2560, 1440 to 2960, 1440 to 3200,
        ).map { ResolutionPreset(it.first, it.second) }

        /** The portrait sizes, then the same sizes turned to landscape (square ones are not repeated), then Host. */
        val entries: List<ResolutionPreset> = PORTRAIT +
            PORTRAIT.filter { it.width != it.height }.map { ResolutionPreset(it.height, it.width) } + HOST

        private val DENSITY_FILES = listOf(
            "system/build.prop", "build.prop", "vendor/build.prop", "odm/etc/build.prop", "product/etc/build.prop",
            "system/default.prop", "default.prop",
        )

        /** ro.sf.lcd_density out of build.prop text, null when absent or not a positive number. */
        fun densityOf(text: String): Int? = PropArea.parseProps(text)["ro.sf.lcd_density"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }

        /** The firmware's own density: the first build.prop (system, vendor, ...) of the guest tree [root] that sets it. */
        fun buildPropDensity(root: File): Int? = DENSITY_FILES.firstNotNullOfOrNull { name ->
            File(root, name).takeIf { it.isFile }?.let { f -> runCatching { densityOf(f.readText()) }.getOrNull() }
        }
    }
}
