/* AEmulator Super Plus, 2026-10-23: CDMA SMS for the fake modem.
 * Modified for AEmulator Plus, 2026-10-25: CDMA wireless emergency alerts (CMAS). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.TimeZone

/**
 * An incoming SMS the way a CDMA modem hands it over: RIL_UNSOL_RESPONSE_CDMA_NEW_SMS carries a RIL_CDMA_SMS_Message
 * (what android.telephony.cdma's SmsMessage.newFromParcel reads), whose bearer data is a 3GPP2 C.S0015 (IS-637)
 * SMS-DELIVER: message identifier, user data and a time stamp sub-parameter.
 * Every "byte" of that parcel takes four bytes (Parcel::write of a uint8_t pads to the word).
 */
internal object CdmaSms {
    /** SmsEnvelope.TELESERVICE_WMT: a plain text message */
    private const val TELESERVICE_WMT = 0x1002
    private const val SUBPARAM_MESSAGE_ID = 0
    private const val SUBPARAM_USER_DATA = 1
    private const val SUBPARAM_TIME_STAMP = 3
    private const val MSG_TYPE_DELIVER = 1
    private const val ENC_7BIT_ASCII = 2
    private const val ENC_UNICODE_16 = 4
    private const val ENC_OCTET = 0
    /** SmsEnvelope.TELESERVICE_NOT_SET */
    private const val TELESERVICE_NONE = 0
    private const val SUBPARAM_PRIORITY = 8
    private const val SUBPARAM_LANGUAGE = 0x0d
    private const val PRIORITY_EMERGENCY = 3
    private const val LANGUAGE_ENGLISH = 1
    private const val CMAS_PROTOCOL_VERSION = 0
    private const val CMAS_RECORD_TEXT = 0
    private const val CMAS_RECORD_INFO = 1
    /** a CMAE record length is one byte: these keep the text record, the type 1 record and the user data inside it */
    const val CMAS_MAX_ASCII = 200
    const val CMAS_MAX_UNICODE = 90

    fun deliverParcel(from: String, body: String, messageId: Int, now: Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))): ByteArray {
        val out = ByteArrayOutputStream()
        fun int(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())

        val num = from.trim()
        // the address travels as 4-bit DTMF digits, which the framework turns back into ASCII; letters cannot be carried
        val digits = num.filter { it.isDigit() || it == '*' || it == '#' }.take(20).ifEmpty { "0" }
        int(TELESERVICE_WMT)
        int(0)                              // not a broadcast service
        int(0)                              // service category
        int(0)                              // digit mode: 4-bit DTMF
        int(0)                              // number mode: not a data network address
        int(if (num.startsWith("+")) 1 else 0)   // number type: international (the framework puts the + back) / unknown
        int(1)                              // numbering plan: ISDN / telephony
        int(digits.length)
        for (c in digits) int(dtmf(c))
        int(0); int(0); int(0)              // no subaddress: type, odd, digits
        val bearer = bearerData(body, messageId, now)
        int(bearer.size)
        for (b in bearer) int(b.toInt() and 0xff)
        return out.toByteArray()
    }

    private fun dtmf(c: Char): Int = when (c) { in '1'..'9' -> c - '0'; '0' -> 10; '*' -> 11; '#' -> 12; else -> 10 }

    fun bearerData(body: String, messageId: Int, now: Calendar): ByteArray {
        val b = ByteArrayOutputStream()
        // message identifier: type, 16-bit id, no user data header
        b.write(SUBPARAM_MESSAGE_ID); b.write(3)
        b.write(Bits().put(MSG_TYPE_DELIVER, 4).put(messageId and 0xffff, 16).put(0, 1).put(0, 3).bytes())
        // user data: encoding, number of characters, the characters (7-bit ASCII when the text allows it, UCS-2 otherwise)
        val text = body.ifEmpty { " " }
        val ascii = text.all { it.code in 0x20..0x7e || it == '\n' }
        val chars = text.take(if (ascii) 160 else 70)
        val ud = Bits().put(if (ascii) ENC_7BIT_ASCII else ENC_UNICODE_16, 5).put(chars.length, 8)
        for (c in chars) if (ascii) ud.put(c.code, 7) else ud.put(c.code, 16)
        val udBytes = ud.bytes()
        b.write(SUBPARAM_USER_DATA); b.write(udBytes.size); b.write(udBytes)
        b.write(timeStamp(now))
        return b.toByteArray()
    }

    /** message centre time stamp sub-parameter: year, month, day, hour, minute, second, BCD */
    private fun timeStamp(now: Calendar): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(SUBPARAM_TIME_STAMP); b.write(6)
        for (v in intArrayOf(now.get(Calendar.YEAR) % 100, now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND))) b.write(((v / 10) shl 4) or (v % 10))
        return b.toByteArray()
    }

    /**
     * A Wireless Emergency Alert the way a CDMA modem hands it over: the same RIL_UNSOL_RESPONSE_CDMA_NEW_SMS parcel, but
     * flagged as a broadcast whose service category is the alert class (see [AlertType.cdmaCategory]), with no address. The
     * framework turns it into an SmsCbMessage (SmsMessage.parseBroadcastSms) for the cell broadcast receiver.
     */
    fun cmasParcel(type: AlertType, body: String, messageId: Int, now: Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))): ByteArray {
        val out = ByteArrayOutputStream()
        fun int(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())

        int(TELESERVICE_NONE)               // a broadcast is told apart by its service category
        int(1)                              // a broadcast service is present
        int(type.cdmaCategory)
        int(0); int(0); int(0); int(1)      // no address: digit mode, number mode, number type, numbering plan
        int(0)                              // no digits
        int(0); int(0); int(0)              // no subaddress: type, odd, digits
        val bearer = cmasBearerData(type, body, messageId, now)
        int(bearer.size)
        for (b in bearer) int(b.toInt() and 0xff)
        return out.toByteArray()
    }

    /**
     * Bearer data of an alert: message identifier, user data (encoding "octet" holding the CMAE record set of J-STD-100:
     * protocol version 0, a type 0 record with the alert text, a type 1 record with severity / urgency / certainty), time
     * stamp, priority "emergency" (what makes the framework treat it as an emergency message) and language English.
     */
    fun cmasBearerData(type: AlertType, body: String, messageId: Int, now: Calendar): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(SUBPARAM_MESSAGE_ID); b.write(3)
        b.write(Bits().put(MSG_TYPE_DELIVER, 4).put(messageId and 0xffff, 16).put(0, 1).put(0, 3).bytes())

        val text = body.ifEmpty { " " }
        val ascii = text.all { it.code in 0x20..0x7e || it == '\n' }
        var chars = text.take(if (ascii) CMAS_MAX_ASCII else CMAS_MAX_UNICODE)
        if (!ascii && chars.isNotEmpty() && Character.isHighSurrogate(chars.last())) chars = chars.dropLast(1)
        val charBits = if (ascii) 7 else 16
        val recordBits = 5 + chars.length * charBits               // encoding, then the characters
        val recordLen = (recordBits + 7) / 8                       // the record length counts whole bytes
        val cmae = Bits().put(CMAS_PROTOCOL_VERSION, 8)
        cmae.put(CMAS_RECORD_TEXT, 8).put(recordLen, 8).put(if (ascii) ENC_7BIT_ASCII else ENC_UNICODE_16, 5)
        for (c in chars) cmae.put(c.code, charBits)
        cmae.put(0, recordLen * 8 - recordBits)
        cmae.put(CMAS_RECORD_INFO, 8).put(4, 8)                    // category and response type stay "unknown"
        cmae.put(0xff, 8).put(0xff, 8).put(type.severity, 4).put(type.urgency, 4).put(type.certainty, 4).put(0, 4)
        val cmaeBytes = cmae.bytes()
        val ud = Bits().put(ENC_OCTET, 5).put(cmaeBytes.size, 8)
        for (x in cmaeBytes) ud.put(x.toInt() and 0xff, 8)
        val udBytes = ud.bytes()
        b.write(SUBPARAM_USER_DATA); b.write(udBytes.size); b.write(udBytes)

        b.write(timeStamp(now))
        b.write(SUBPARAM_PRIORITY); b.write(1); b.write(Bits().put(PRIORITY_EMERGENCY, 2).put(0, 6).bytes())
        b.write(SUBPARAM_LANGUAGE); b.write(1); b.write(LANGUAGE_ENGLISH)
        return b.toByteArray()
    }

    /** MSB-first bit packer (BitwiseOutputStream of the framework) */
    private class Bits {
        private val out = ByteArrayOutputStream()
        private var cur = 0
        private var n = 0
        fun put(value: Int, bits: Int): Bits {
            for (i in bits - 1 downTo 0) {
                cur = (cur shl 1) or ((value ushr i) and 1)
                if (++n == 8) { out.write(cur); cur = 0; n = 0 }
            }
            return this
        }
        fun bytes(): ByteArray { if (n > 0) { out.write(cur shl (8 - n)); cur = 0; n = 0 }; return out.toByteArray() }
    }
}
