/* AEmulator Super Plus, 2026-10-23: CDMA modem emulation. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File
import java.security.MessageDigest

/**
 * CDMA mode of the fake modem ([RilStub]).
 *
 * Whether it is on: [VmSettings.cdma] when the user chose, otherwise what the firmware says - a firmware with
 * telephony.lteOnCdmaDevice=1 (Nexus 6P, Verizon models...) is a CDMA phone to the framework no matter what the modem
 * reports (RIL v6: GsmCdmaPhone.phoneObjectUpdater always switches such a build to the CDMA phone), so against a GSM-only
 * modem its telephony ran in a half-working state. Any other firmware starts as a GSM phone and gets CDMA only when asked.
 *
 * Two flavours, told apart by the firmware's property:
 *  - LTE on CDMA: voice is 1xRTT, data is [NetworkType] (1x / EV-DO / eHRPD / LTE); the USIM carries the subscription,
 *    the framework falls back to it when the card has no CSIM app (that is what real LTE-on-CDMA phones with a SIM do too).
 *  - CDMA only: one technology for voice and data, the subscription comes from the modem's NV (MDN / MIN / SID / NID),
 *    no card is needed to register.
 */
object CdmaMode {
    const val LTE_ON_CDMA_PROP = "telephony.lteOnCdmaDevice"
    /** the rild-socket RIL: Android 2.3 .. 7.1; 8.0+ talks HIDL IRadio, which the stub does not speak */
    const val FIRST_API = 9
    const val LAST_API = 25

    fun supported(api: Int) = api in FIRST_API..LAST_API

    /** What a boot is going to do; computed once per VM start so the modem and the guest's properties agree. */
    data class Plan(
        /** the CDMA modem is on */
        val cdma: Boolean,
        /** what the guest will see in telephony.lteOnCdmaDevice (only with [cdma]) */
        val lteOnCdma: Boolean,
        /** what the firmware itself declares */
        val firmwareLteOnCdma: Boolean,
    )

    val OFF = Plan(cdma = false, lteOnCdma = false, firmwareLteOnCdma = false)

    /** properties the guest may not change while CDMA is off */
    fun pinnedProps(plan: Plan, api: Int): Set<String> = if (supported(api) && !plan.cdma) setOf(LTE_ON_CDMA_PROP) else emptySet()

    fun plan(s: VmSettings, api: Int, paths: VmPaths): Plan {
        if (!supported(api)) return OFF
        val fw = firmwareLteOnCdma(paths)
        if (!s.radio) return Plan(cdma = false, lteOnCdma = false, firmwareLteOnCdma = fw)
        val on = s.cdma ?: fw
        return Plan(cdma = on, lteOnCdma = on && fw, firmwareLteOnCdma = fw)
    }

    private val PROP_FILES = listOf(
        "default.prop", "system/default.prop", "system/build.prop", "system/vendor/build.prop", "vendor/build.prop",
        "odm/etc/build.prop", "product/build.prop",
    )

    /**
     * A property of the firmware as the guest would see it before our own overrides: the property template made at
     * import (build.prop and the init.rc setprops), else the build.prop files themselves.
     */
    fun firmwareProp(paths: VmPaths, key: String): String? {
        runCatching {
            if (paths.propsTemplate.isFile) PropArea.open(paths.propsTemplate)?.use { a -> a.get(key)?.let { return it } }
        }
        var found: String? = null
        for (n in PROP_FILES) runCatching {
            val f = File(paths.root, n)
            if (f.isFile) PropArea.parseProps(f.readText(Charsets.UTF_8))[key]?.let { found = it }
        }
        return found
    }

    /** telephony.lteOnCdmaDevice == 1 in the firmware */
    fun firmwareLteOnCdma(paths: VmPaths): Boolean = firmwareProp(paths, LTE_ON_CDMA_PROP)?.trim() == "1"

    /**
     * Properties that make the framework agree with the modem, applied on top of the firmware's own.
     *  - CDMA off: telephony.lteOnCdmaDevice is forced to 0 (the framework would otherwise make a CDMA phone of an
 *    LTE-on-CDMA build); GuestVm also pins it so the guest's own init scripts cannot set it back to 1.
     *  - CDMA on a firmware without it: a plain CDMA phone - preferred mode CDMA/EV-DO, subscription from NV, and the
     *    home operator (the framework takes the APN list's operator from there when there is no SIM record).
     */
    fun propOverrides(plan: Plan, api: Int, homeNumeric: String, homeName: String, fw: (String) -> String?): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        if (!supported(api)) return m
        if (!plan.cdma) {
            // unconditional: whatever sets it (build.prop, a vendor init script at runtime), CDMA off means 0
            m[LTE_ON_CDMA_PROP] = "0"
            return m
        }
        if (plan.lteOnCdma) return m
        m["ro.telephony.default_network"] = "4"        // RILConstants.NETWORK_MODE_CDMA (CDMA / EV-DO auto)
        m["ro.telephony.default_cdma_sub"] = "1"       // CDMA_SUBSCRIPTION_NV
        if (fw("ro.cdma.home.operator.numeric").isNullOrBlank()) m["ro.cdma.home.operator.numeric"] = homeNumeric
        if (fw("ro.cdma.home.operator.alpha").isNullOrBlank()) m["ro.cdma.home.operator.alpha"] = homeName
        return m
    }

    /** MEID: 14 hex digits - manufacturer code A0 (the unassigned MEID range) and the IMEI's first twelve digits */
    fun meid(imei: String): String = "A0" + imei.filter(Char::isDigit).padEnd(12, '0').take(12)

    /** pseudo-ESN of a MEID, as 3GPP2 C.S0072 derives it: 0x80 and the low 24 bits of SHA-1(MEID) */
    fun esn(meid: String): String {
        val raw = ByteArray(meid.length / 2) { meid.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val h = MessageDigest.getInstance("SHA-1").digest(raw)
        return "80" + h.takeLast(3).joinToString("") { "%02X".format(it.toInt() and 0xff) }
    }
}
