package app.aemu.core

/**
 * Google Glass (XE, 4.0.x) asks for the "network" location provider at boot: com.google.glass.settings'
 * NewTimeZoneService calls LocationManager.requestLocationUpdates("network", ...) and the guest's
 * LocationManagerService throws IllegalArgumentException("provider=network") when that provider does not exist,
 * killing com.google.glass.settings ("Settings has stopped") on every boot.
 *
 * The provider is missing because disableBrokenComponents() had switched off com.google.android.location's
 * NetworkLocationService (a workaround for the Play services/Maps TelephonyRegistry crash on 2.2-4.1 phones).
 * On 4.0.x LocationProviderProxy.createAndBind() returns null when the service cannot be bound, so the provider is
 * never registered. Glass depends on it, so it stays enabled there.
 */
object GlassLocationPolicy {
    const val LOCATION_PACKAGE = "com.google.android.location"

    /** the NetworkLocationService components of the system location package, as listed in disableBrokenComponents() */
    val LOCATION_COMPONENTS = listOf(
        "com.google.android.location.NetworkLocationService",
        "com.google.android.location.internal.server.NetworkLocationService",
    )

    fun isGlass(name: String, model: String, hasGlassApk: Boolean): Boolean =
        hasGlassApk || name.contains("glass", ignoreCase = true) || model.contains("glass", ignoreCase = true)

    /** components "pkg/cls" that disableBrokenComponents() must not switch off for this image */
    fun keepEnabled(component: String, glass: Boolean): Boolean =
        glass && component.startsWith("$LOCATION_PACKAGE/")

    /**
     * Removes the NetworkLocationService entries from the <disabled-components> list of [LOCATION_PACKAGE] in a
     * (text) packages.xml. Returns the new text, or null when nothing had to change.
     */
    fun reenable(packagesXml: String): String? {
        val start = Regex("<package name=\"${Regex.escape(LOCATION_PACKAGE)}\"[^>]*>").find(packagesXml) ?: return null
        val end = packagesXml.indexOf("</package>", start.range.last)
        if (end < 0) return null
        val block = packagesXml.substring(start.range.last + 1, end)
        var changed = block
        for (c in LOCATION_COMPONENTS)
            changed = changed.replace(Regex("[ \\t]*<item name=\"${Regex.escape(c)}\"\\s*/>[ \\t]*\\r?\\n?"), "")
        changed = changed.replace(Regex("[ \\t]*<disabled-components>\\s*</disabled-components>[ \\t]*\\r?\\n?"), "")
        if (changed == block) return null
        return packagesXml.substring(0, start.range.last + 1) + changed + packagesXml.substring(end)
    }
}
