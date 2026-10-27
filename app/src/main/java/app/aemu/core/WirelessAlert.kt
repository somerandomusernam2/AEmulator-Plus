/* AEmulator Plus, 2026-10-25: emulated Wireless Emergency Alerts (CMAS) for the fake modem. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

/**
 * The Wireless Emergency Alert (CMAS) classes the fake modem can broadcast. Each class has a 3GPP message identifier
 * (GSM / UMTS cell broadcast, TS 23.041) and a 3GPP2 service category (CDMA, J-STD-100); the guest's cell broadcast
 * receiver picks the alert's title, colour and sound from them.
 *
 * [severity], [urgency] and [certainty] are the CMAS_Info fields of a CDMA alert (4 bits each, 0xF = unknown).
 */
enum class AlertType(val gsmId: Int, val cdmaCategory: Int, val severity: Int, val urgency: Int, val certainty: Int) {
    /** presidential level alert: the user cannot switch it off */
    PRESIDENTIAL(0x1112, 0x1000, 0xF, 0xF, 0xF),
    /** extreme threat to life and property (immediate, observed) */
    EXTREME(0x1113, 0x1001, 0, 0, 0),
    /** severe threat (immediate, observed) */
    SEVERE(0x1117, 0x1002, 1, 0, 0),
    /** child abduction emergency */
    AMBER(0x111B, 0x1003, 0xF, 0xF, 0xF),
    /** required monthly test */
    TEST(0x111C, 0x1004, 0xF, 0xF, 0xF),
}

/**
 * Builds the GSM / UMTS side of an alert: 3GPP TS 23.041 9.4.1.2 cell broadcast pages, 88 bytes each, the way the
 * modem hands them to the guest as RIL_UNSOL_RESPONSE_NEW_BROADCAST_SMS (the framework's SmsCbHeader and
 * GsmSmsCbMessage read them). The CDMA side is [CdmaSms.cmasParcel].
 *
 * A page is a 6-byte header (serial number, message identifier, data coding scheme, page n of m) and 82 bytes of
 * text: 93 GSM 7-bit characters or 41 UCS-2 ones, padded with carriage returns, which the framework strips.
 * Long texts become several pages (up to [MAX_PAGES]); the framework puts them back together by their serial number.
 */
object WirelessAlert {
    const val MAX_PAGES = 15
    const val GSM7_CHARS = 93
    const val UCS2_CHARS = 41
    /** the longest text that survives in either encoding on every path; the dialog cuts the text field at this */
    const val MAX_TEXT = 600
    const val PDU_SIZE = 88
    private const val HEADER = 6
    /** geographical scope "cell wide, display immediately" (the top two bits of the serial number) */
    private const val GS_CELL_IMMEDIATE = 0
    /** coding group 0000, language 1: GSM 7-bit default alphabet, English */
    private const val DCS_GSM7_EN = 0x01
    /** coding group 0100, general data coding with the UCS-2 alphabet */
    private const val DCS_UCS2 = 0x48
    private const val CR = '\r'

    /** characters whose GSM 03.38 code equals ASCII, so packing needs no lookup table (same set as the fake SMS) */
    private val GSM7_SAFE: Set<Char> = (('A'..'Z') + ('a'..'z') + ('0'..'9') + " \n\r!\"#%&'()*+,-./:;<=>?".toList()).toSet()

    fun isGsm7(text: String): Boolean = text.all { it in GSM7_SAFE }

    /**
     * The pages of one alert. [messageCode] (10 bits, the part of the serial number the network changes for every new
     * alert) must differ from the previous alert's: the guest drops a broadcast whose serial number and identifier it
     * has already shown.
     */
    fun gsmPages(type: AlertType, text: String, messageCode: Int): List<ByteArray> {
        val body = text.ifEmpty { " " }
        val gsm = isGsm7(body)
        val chunks = (if (gsm) body.chunked(GSM7_CHARS) else ucs2Chunks(body)).take(MAX_PAGES)
        val serial = (GS_CELL_IMMEDIATE shl 14) or ((messageCode and 0x3ff) shl 4)   // update number 0
        return chunks.mapIndexed { i, chunk ->
            val pdu = ByteArray(PDU_SIZE)
            pdu[0] = (serial shr 8).toByte()
            pdu[1] = serial.toByte()
            pdu[2] = (type.gsmId shr 8).toByte()
            pdu[3] = type.gsmId.toByte()
            pdu[4] = (if (gsm) DCS_GSM7_EN else DCS_UCS2).toByte()
            pdu[5] = (((i + 1) shl 4) or chunks.size).toByte()
            val content = if (gsm) gsm7Pack(chunk.padEnd(GSM7_CHARS, CR))
                else chunk.padEnd(UCS2_CHARS, CR).toByteArray(Charsets.UTF_16BE)
            content.copyInto(pdu, HEADER)
            pdu
        }
    }

    /** UCS-2 pages of at most [UCS2_CHARS] UTF-16 units, never cutting a surrogate pair in two */
    private fun ucs2Chunks(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val n = if (Character.isHighSurrogate(s[i]) && i + 1 < s.length) 2 else 1
            if (cur.length + n > UCS2_CHARS) { out.add(cur.toString()); cur.setLength(0) }
            cur.append(s, i, i + n)
            i += n
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun gsm7Pack(s: String): ByteArray {
        val out = ByteArray((s.length * 7 + 7) / 8)
        s.forEachIndexed { i, c ->
            val bit = i * 7
            val v = c.code and 0x7f
            val idx = bit / 8
            val sh = bit % 8
            out[idx] = (out[idx].toInt() or (v shl sh)).toByte()
            if (sh > 1) out[idx + 1] = (out[idx + 1].toInt() or (v shr (8 - sh))).toByte()
        }
        return out
    }
}
