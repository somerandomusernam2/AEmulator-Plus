/* AEmulator Plus, 2026-10-25: emulated Wireless Emergency Alerts. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Reads the alerts back the way the guest framework does (SmsCbHeader + GsmSmsCbMessage, cdma BearerData). */
class WirelessAlertTest {
    private val now = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, Calendar.OCTOBER, 25, 12, 34, 56) }

    // ---- GSM / UMTS cell broadcast ----

    private fun septets(b: ByteArray, from: Int, count: Int): String {
        val sb = StringBuilder()
        for (i in 0 until count) {
            val bit = i * 7
            val idx = from + bit / 8
            val sh = bit % 8
            var v = (b[idx].toInt() and 0xff) shr sh
            if (sh > 1) v = v or ((b[idx + 1].toInt() and 0xff) shl (8 - sh))
            sb.append((v and 0x7f).toChar())
        }
        return sb.toString()
    }

    /** page text as the framework reads it: decode, then drop the trailing carriage returns */
    private fun pageText(pdu: ByteArray): String {
        val dcs = pdu[4].toInt() and 0xff
        val s = if (dcs == 0x01) septets(pdu, 6, (pdu.size - 6) * 8 / 7)
            else String(pdu, 6, pdu.size - 6, Charsets.UTF_16BE)
        return s.trimEnd('\r')
    }

    @Test fun singlePageHeaderFollowsTs23041() {
        val pages = WirelessAlert.gsmPages(AlertType.PRESIDENTIAL, "Hello", 0x2a5)
        assertEquals(1, pages.size)
        val p = pages[0]
        assertEquals(88, p.size)
        val serial = ((p[0].toInt() and 0xff) shl 8) or (p[1].toInt() and 0xff)
        assertEquals(0, serial shr 14)                    // cell wide, immediate
        assertEquals(0x2a5, (serial shr 4) and 0x3ff)     // message code
        assertEquals(0, serial and 0xf)                   // update number
        assertEquals(0x1112, ((p[2].toInt() and 0xff) shl 8) or (p[3].toInt() and 0xff))
        assertEquals(0x01, p[4].toInt())
        assertEquals(0x11, p[5].toInt() and 0xff)         // page 1 of 1
        assertEquals("Hello", pageText(p))
    }

    @Test fun everyClassHasItsOwnIdentifierInTheCmasRange() {
        val ids = AlertType.values().map { it.gsmId }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it in 0x1112..0x111E })
        assertEquals(0x1112, AlertType.PRESIDENTIAL.gsmId)
        assertEquals(0x111B, AlertType.AMBER.gsmId)
    }

    @Test fun fullGsm7PageNeedsNoPaddingAndSurvivesRoundTrip() {
        val text = "A".repeat(93)
        val pages = WirelessAlert.gsmPages(AlertType.EXTREME, text, 1)
        assertEquals(1, pages.size)
        assertEquals(text, pageText(pages[0]))
    }

    @Test fun longTextBecomesPagesWithTheSameSerial() {
        val text = (1..250).joinToString("") { ('a' + it % 26).toString() }
        val pages = WirelessAlert.gsmPages(AlertType.SEVERE, text, 7)
        assertEquals(3, pages.size)
        pages.forEachIndexed { i, p ->
            assertEquals(((i + 1) shl 4) or 3, p[5].toInt() and 0xff)
            assertEquals(pages[0][0], p[0]); assertEquals(pages[0][1], p[1])
        }
        assertEquals(text, pages.joinToString("") { pageText(it) })
    }

    @Test fun nonGsmTextUsesUcs2AndKeepsSurrogatePairsTogether() {
        val text = "Тревога " + "😀".repeat(30)
        val pages = WirelessAlert.gsmPages(AlertType.AMBER, text, 9)
        assertTrue(pages.all { it[4].toInt() and 0xff == 0x48 && it.size == 88 })
        assertTrue(pages.size > 1)
        assertEquals(text, pages.joinToString("") { pageText(it) })
    }

    @Test fun textBeyondFifteenPagesIsCut() {
        val pages = WirelessAlert.gsmPages(AlertType.TEST, "x".repeat(5000), 3)
        assertEquals(WirelessAlert.MAX_PAGES, pages.size)
        assertEquals(15, pages[0][5].toInt() and 0x0f)
    }

    @Test fun differentMessageCodesGiveDifferentSerialNumbers() {
        val a = WirelessAlert.gsmPages(AlertType.TEST, "same", 10)[0]
        val b = WirelessAlert.gsmPages(AlertType.TEST, "same", 11)[0]
        assertNotEquals(a[1], b[1])
    }

    @Test fun emptyTextStillMakesAValidPage() {
        val pages = WirelessAlert.gsmPages(AlertType.TEST, "", 1)
        assertEquals(1, pages.size)
        assertEquals(88, pages[0].size)
    }

    // ---- CDMA ----

    private class In(val b: ByteArray) {
        var pos = 0
        fun read(bits: Int): Int { var v = 0; repeat(bits) { v = (v shl 1) or ((b[pos / 8].toInt() shr (7 - pos % 8)) and 1); pos++ }; return v }
        fun left() = b.size * 8 - pos
    }

    private class Decoded(var id: Int = -1, var priority: Int = -1, var language: Int = -1, var text: String = "",
                          var severity: Int = -1, var urgency: Int = -1, var certainty: Int = -1, var hasTime: Boolean = false)

    /** BearerData.decode with decodeCmasUserData */
    private fun decode(bearer: ByteArray): Decoded {
        val d = Decoded()
        var i = 0
        var ud: ByteArray? = null
        while (i < bearer.size) {
            val id = bearer[i].toInt() and 0xff
            val len = bearer[i + 1].toInt() and 0xff
            val body = bearer.copyOfRange(i + 2, i + 2 + len)
            i += 2 + len
            val s = In(body)
            when (id) {
                0 -> { assertEquals(1, s.read(4)); d.id = s.read(16) }
                1 -> {
                    assertEquals(0, s.read(5)); s.read(8)           // encoding octet, number of bytes
                    val bits = len * 8 - 13
                    val arr = ByteArray((bits + 7) / 8)
                    for (k in arr.indices) { val take = minOf(8, bits - k * 8); arr[k] = (s.read(take) shl (8 - take)).toByte() }
                    ud = arr
                }
                3 -> d.hasTime = true
                8 -> d.priority = s.read(2)
                0x0d -> d.language = s.read(8)
            }
        }
        val c = In(ud!!)
        assertEquals(0, c.read(8))                                   // protocol version
        while (c.left() >= 16) {
            val type = c.read(8)
            val len = c.read(8)
            if (type == 0) {
                val enc = c.read(5)
                val sb = StringBuilder()
                if (enc == 2) repeat((len * 8 - 5) / 7) { val ch = c.read(7); sb.append(if (ch == 0) ' ' else ch.toChar()) }
                else repeat((len - 1) / 2) { sb.append(c.read(16).toChar()) }
                c.pos += len * 8 - 5 - (if (enc == 2) (len * 8 - 5) / 7 * 7 else (len - 1) / 2 * 16)
                d.text = sb.toString()
            } else if (type == 1) {
                c.read(8); c.read(8)
                d.severity = c.read(4); d.urgency = c.read(4); d.certainty = c.read(4)
                c.pos += len * 8 - 28
            } else c.pos += len * 8
        }
        return d
    }

    @Test fun cdmaAlertCarriesTextAndEmergencyPriority() {
        val d = decode(CdmaSms.cmasBearerData(AlertType.EXTREME, "Tornado warning", 0x1234, now))
        assertEquals(0x1234, d.id)
        assertEquals("Tornado warning", d.text.trimEnd())
        assertEquals(3, d.priority)                    // emergency
        assertEquals(1, d.language)                    // English
        assertTrue(d.hasTime)
        assertEquals(Triple(0, 0, 0), Triple(d.severity, d.urgency, d.certainty))
    }

    @Test fun cdmaUnicodeTextRoundTrips() {
        val d = decode(CdmaSms.cmasBearerData(AlertType.AMBER, "Внимание! Тревога", 5, now))
        assertEquals("Внимание! Тревога", d.text)
    }

    @Test fun cdmaEveryTextLengthRoundTrips() {
        for (n in 1..CdmaSms.CMAS_MAX_ASCII) {
            val text = "z".repeat(n)
            assertEquals("length $n", text, decode(CdmaSms.cmasBearerData(AlertType.SEVERE, text, n, now)).text.trimEnd())
        }
    }

    @Test fun cdmaParcelIsABroadcastWithTheServiceCategory() {
        val p = java.nio.ByteBuffer.wrap(CdmaSms.cmasParcel(AlertType.AMBER, "x", 1, now)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, p.getInt())                    // teleservice not set
        assertEquals(1, p.getInt())                    // service present: broadcast
        assertEquals(0x1003, p.getInt())               // child abduction emergency
    }
}
