/* Modified for AEmulator Super Plus, 2026-10-23: CDMA radio technologies. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

/**
 * Radio technology the fake modem reports to the guest: the values of ServiceState.RIL_RADIO_TECHNOLOGY_*, which is
 * what the status bar and TelephonyManager.getNetworkType() are derived from.
 *
 * Two families: GSM (2G ... LTE, the default modem) and CDMA (1xRTT, EV-DO, eHRPD; plus LTE on an LTE-on-CDMA device),
 * see [CdmaMode]. One stored value serves both; the modem maps what its family cannot report to a sensible neighbour.
 * Default is 2G: not every firmware copes with a 3G/4G registration state.
 */
object NetworkType {
    const val GPRS = 1
    const val EDGE = 2
    const val UMTS = 3
    const val ONE_X_RTT = 6
    const val EVDO_0 = 7
    const val EVDO_A = 8
    const val HSDPA = 9
    const val HSUPA = 10
    const val HSPA = 11
    const val EVDO_B = 12
    const val EHRPD = 13
    const val LTE = 14
    const val HSPAP = 15
    const val DEFAULT = EDGE
    const val DEFAULT_CDMA = ONE_X_RTT

    /**
     * [minApi]: HSPA+, eHRPD and LTE exist in the framework since 4.0; 2.3 throws on those values.
     * [gsm] / [cdma]: the families that offer it (LTE is in both, but a CDMA modem reports it only on an LTE-on-CDMA device).
     */
    data class Entry(val tech: Int, val label: String, val minApi: Int, val gsm: Boolean = true, val cdma: Boolean = false)

    val ALL = listOf(
        Entry(GPRS, "2G · GPRS", 9),
        Entry(EDGE, "2G · EDGE", 9),
        Entry(UMTS, "3G · UMTS", 9),
        Entry(HSDPA, "3G · HSDPA", 9),
        Entry(HSUPA, "3G · HSUPA", 9),
        Entry(HSPA, "3G · HSPA", 9),
        Entry(HSPAP, "3.5G · HSPA+", 14),
        Entry(ONE_X_RTT, "2G · 1xRTT", 9, gsm = false, cdma = true),
        Entry(EVDO_0, "3G · EV-DO rev. 0", 9, gsm = false, cdma = true),
        Entry(EVDO_A, "3G · EV-DO rev. A", 9, gsm = false, cdma = true),
        Entry(EVDO_B, "3G · EV-DO rev. B", 9, gsm = false, cdma = true),
        Entry(EHRPD, "3.5G · eHRPD", 14, gsm = false, cdma = true),
        Entry(LTE, "4G · LTE", 14, gsm = true, cdma = true),
    )

    /** What the picker offers: the GSM list, or with [cdma] the CDMA one (LTE only when [lteOnCdma]). */
    fun available(api: Int, cdma: Boolean = false, lteOnCdma: Boolean = false): List<Entry> =
        ALL.filter { api >= it.minApi && if (cdma) it.cdma && (it.tech != LTE || lteOnCdma) else it.gsm }

    /** For persisted settings: a known value stays whatever the family, an unknown one → default. */
    fun normalize(tech: Int): Int = if (ALL.any { it.tech == tech }) tech else DEFAULT

    /**
     * The value the modem of this family and firmware can actually report.
     * GSM: unknown or CDMA-only → default; a GSM value the firmware's API level cannot represent → HSPA.
     * CDMA: unknown or GSM-only → 1xRTT; one the API level or the device cannot represent → EV-DO rev. A (eHRPD when LTE is not there).
     */
    fun sanitize(tech: Int, api: Int = Int.MAX_VALUE, cdma: Boolean = false, lteOnCdma: Boolean = false): Int {
        val e = ALL.firstOrNull { it.tech == tech }
        if (!cdma) return when {
            e == null || !e.gsm -> DEFAULT
            available(api).any { it.tech == tech } -> tech
            else -> HSPA
        }
        return when {
            e == null || !e.cdma -> DEFAULT_CDMA
            available(api, true, lteOnCdma).any { it.tech == tech } -> tech
            tech == LTE && api >= 14 -> EHRPD
            else -> EVDO_A
        }
    }

    fun label(tech: Int): String = ALL.firstOrNull { it.tech == tech }?.label ?: tech.toString()
}
