/* Modified for AEmulator Plus, 2026-11-01: Android 8 - 17. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.stub;

/** The registered network of the emulated Wi-Fi, whichever NetworkAgent flavour the guest's framework has. */
interface WifiLink {
    /** Tells ConnectivityService the network is gone; a link cannot come back afterwards, a new one is registered instead. */
    void disconnect();
}
