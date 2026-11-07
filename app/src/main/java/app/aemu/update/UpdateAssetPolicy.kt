package app.aemu.update

/** Sunset modification, 2026-10-02: never cross-install distribution variants.
 * Modified for AEmulator Plus, 2026-10-04: single package, app.aemu.plus; no clone. GPL-3.0; see NOTICE.md. */
object UpdateAssetPolicy {
    // Modified for AEmulator Aster (2026-11-07): app.aemu (release assets "...-app.aemu.apk")
    const val PACKAGE = "app.aemu"

    fun matches(name: String, applicationId: String): Boolean =
        applicationId == PACKAGE && name.endsWith("-$PACKAGE.apk", ignoreCase = true)
}
