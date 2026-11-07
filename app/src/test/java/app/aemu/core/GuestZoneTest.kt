package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.SimpleTimeZone

class GuestZoneTest {
    @get:Rule val tmp = TemporaryFolder()

    private val h = 3_600_000
    private val now = 1_782_864_000_000L                // 2026-07-01 00:00 UTC
    private val nowS = now / 1000

    /** a TZif file of version [v]: transitions (time in s, type) and types (offset in s, dst) in its 32-bit block,
     *  or with [slim] an empty 32-bit block and everything in the 64-bit one */
    private fun tzif(trans: List<Pair<Long, Int>>, types: List<Pair<Int, Boolean>>, slim: Boolean = false): ByteArray {
        val o = ByteArrayOutputStream()
        fun block(tsz: Int, tr: List<Pair<Long, Int>>, ty: List<Pair<Int, Boolean>>) {
            val head = ByteBuffer.allocate(44)
            head.put("TZif".toByteArray()).put('2'.code.toByte()).put(ByteArray(15))
            head.putInt(0).putInt(0).putInt(0).putInt(tr.size).putInt(ty.size).putInt(4)
            o.write(head.array())
            for ((t, _) in tr) o.write(if (tsz == 8) ByteBuffer.allocate(8).putLong(t).array() else ByteBuffer.allocate(4).putInt(t.toInt()).array())
            for ((_, i) in tr) o.write(i)
            for ((off, dst) in ty) { o.write(ByteBuffer.allocate(4).putInt(off).array()); o.write(if (dst) 1 else 0); o.write(0) }
            o.write("XXX\u0000".toByteArray())
        }
        if (slim) block(4, emptyList(), listOf(0 to false)) else block(4, trans, types)
        block(8, trans, types)
        o.write("\n\n".toByteArray())
        return o.toByteArray()
    }

    /** an Android "tzdata" file holding these zones */
    private fun tzdata(zones: Map<String, ByteArray>): ByteArray {
        val indexAt = 24
        val dataAt = indexAt + zones.size * 52
        val head = ByteBuffer.allocate(24)
        head.put("tzdata2013a\u0000".toByteArray()).putInt(indexAt).putInt(dataAt).putInt(dataAt + zones.values.sumOf { it.size })
        val index = ByteBuffer.allocate(zones.size * 52)
        val data = ByteArrayOutputStream()
        for ((name, z) in zones) {
            index.put(name.toByteArray().copyOf(40)).putInt(data.size()).putInt(z.size).putInt(0)
            data.write(z)
        }
        return head.array() + index.array() + data.toByteArray()
    }

    private fun root(zones: Map<String, ByteArray>): File {
        val r = tmp.newFolder()
        File(r, "system/usr/share/zoneinfo").mkdirs()
        File(r, "system/usr/share/zoneinfo/tzdata").writeBytes(tzdata(zones))
        return r
    }

    private val kolkata = tzif(listOf(-764_145_000L to 1), listOf(23_400 to true, 19_800 to false))
    // Iran as an old database has it: +4:30 in summer (the country dropped daylight saving time in 2022)
    private val oldTehran = tzif(listOf(nowS - 86_400 * 100 to 1, nowS - 86_400 * 10 to 0, nowS + 86_400 * 80 to 1),
        listOf(16_200 to true, 12_600 to false))

    @Test fun wholeHoursStayEtcGmt() {
        val r = root(mapOf("Asia/Kolkata" to kolkata))
        assertEquals("Etc/GMT", GuestZone.pick(r, SimpleTimeZone(0, "UTC"), now))
        assertEquals("Etc/GMT-3", GuestZone.pick(r, SimpleTimeZone(3 * h, "Europe/Moscow"), now))
        assertEquals("Etc/GMT+5", GuestZone.pick(r, SimpleTimeZone(-5 * h, "America/Bogota"), now))
    }

    @Test fun halfHourOffsetGetsANamedZone() {
        val r = root(mapOf("Asia/Kolkata" to kolkata))
        assertEquals("Asia/Kolkata", GuestZone.pick(r, SimpleTimeZone(5 * h + h / 2, "Asia/Kolkata"), now))
        // the phone says the old name, the firmware knows the new one
        assertEquals("Asia/Kolkata", GuestZone.pick(r, SimpleTimeZone(5 * h + h / 2, "Asia/Calcutta"), now))
    }

    @Test fun zoneWhoseRulesDifferInTheFirmwareIsNotUsed() {
        val r = root(mapOf("Asia/Tehran" to oldTehran))
        // the firmware would show +4:30: whole hours as before
        assertEquals("Etc/GMT-3", GuestZone.pick(r, SimpleTimeZone(3 * h + h / 2, "Asia/Tehran"), now))
        // in winter its rules agree with the phone
        val winter = now - 86_400_000L * 50
        assertEquals("Asia/Tehran", GuestZone.pick(r, SimpleTimeZone(3 * h + h / 2, "Asia/Tehran"), winter))
    }

    @Test fun noDatabaseOrNoZone() {
        val empty = tmp.newFolder()
        assertEquals("Etc/GMT-5", GuestZone.pick(empty, SimpleTimeZone(5 * h + h / 2, "Asia/Kolkata"), now))
        val r = root(mapOf("Asia/Kolkata" to kolkata))
        assertEquals("Etc/GMT-5", GuestZone.pick(r, SimpleTimeZone(5 * h + 3 * h / 4, "Asia/Kathmandu"), now))
    }

    @Test fun gingerbreadIndexFiles() {
        val r = tmp.newFolder()
        val d = File(r, "system/usr/share/zoneinfo").apply { mkdirs() }
        val idx = ByteBuffer.allocate(52).put("Asia/Kathmandu".toByteArray().copyOf(40))
        val nepal = tzif(listOf(504_901_800L to 1), listOf(19_800 to false, 20_700 to false))
        idx.putInt(0).putInt(nepal.size).putInt(20_700)
        File(d, "zoneinfo.idx").writeBytes(idx.array())
        File(d, "zoneinfo.dat").writeBytes(nepal)
        assertEquals("Asia/Kathmandu", GuestZone.pick(r, SimpleTimeZone(5 * h + 3 * h / 4, "Asia/Katmandu"), now))
    }

    @Test fun slimFileIsReadFromItsSixtyFourBitBlock() {
        val slim = tzif(listOf(-764_145_000L to 1), listOf(23_400 to true, 19_800 to false), slim = true)
        assertEquals(19_800_000, GuestZone.tzifOffset(slim, nowS))
        assertEquals(19_800_000, GuestZone.tzifOffset(kolkata, nowS))
        // before the first transition: the first standard-time type
        assertEquals(19_800_000, GuestZone.tzifOffset(kolkata, -800_000_000L))
    }
}
