package app.aemu.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.TimeZone

/**
 * The time zone the guest is given (persist.sys.timezone).
 *
 * A firmware's time-zone database is as old as the firmware, so the guest gets the phone's current offset as a fixed
 * Etc/GMT zone rather than the phone's zone name: a zone whose rules changed since would show the wrong time. Those
 * zones only exist for whole hours, though, and the offset of India (+5:30), Iran, Nepal, Myanmar, Afghanistan,
 * central Australia or Newfoundland came out 15 to 45 minutes off. For such an offset a named zone is used instead -
 * the phone's own, or one that has had that offset for decades - but only when the firmware's own database gives it
 * exactly the phone's offset right now; otherwise the whole hours as before.
 */
object GuestZone {
    fun pick(root: File, tz: TimeZone = TimeZone.getDefault(), now: Long = System.currentTimeMillis()): String {
        val off = tz.getOffset(now)
        if (off % 3_600_000 == 0) return etc(off)
        val candidates = (listOf(tz.id) + FIXED[off / 60_000].orEmpty()).distinct()
        val db = runCatching { Db.open(root) }.getOrNull() ?: return etc(off)
        return candidates.firstOrNull { name -> db.offsetAt(name, now) == off } ?: etc(off)
    }

    /** whole hours, the sign as POSIX has it (Etc/GMT-3 is three hours east of Greenwich) */
    fun etc(offMs: Int): String {
        val h = offMs / 3_600_000
        return if (h == 0) "Etc/GMT" else "Etc/GMT" + (if (h > 0) "-$h" else "+${-h}")
    }

    /** zones (old and new names) that keep one offset all year and have kept it since long before any firmware */
    private val FIXED = mapOf(
        330 to listOf("Asia/Kolkata", "Asia/Calcutta"),
        345 to listOf("Asia/Kathmandu", "Asia/Katmandu"),
        390 to listOf("Asia/Yangon", "Asia/Rangoon", "Indian/Cocos"),
        270 to listOf("Asia/Kabul"),
        570 to listOf("Australia/Darwin"),
        525 to listOf("Australia/Eucla"),
        -570 to listOf("Pacific/Marquesas"),
    )

    /**
     * The firmware's database: one file with an index of 52-byte entries (40-byte name, start, length, raw offset)
     * pointing at a TZif file per zone - "tzdata" (4.0+; in a module from 10 on, and updates in /data) or
     * zoneinfo.idx/zoneinfo.dat (2.3).
     */
    class Db private constructor(private val data: File, private val dataStart: Long, private val index: Map<String, Pair<Int, Int>>) {
        /** the offset the guest computes for [name] at [now], or null when it does not have the zone */
        fun offsetAt(name: String, now: Long): Int? {
            val (start, len) = index[name] ?: return null
            if (len !in 44..1_000_000) return null
            val b = RandomAccessFile(data, "r").use { r -> ByteArray(len).also { r.seek(dataStart + start); r.readFully(it) } }
            return tzifOffset(b, now / 1000)
        }

        companion object {
            private val TZDATA = listOf("data/misc/zoneinfo/current/tzdata", "apex/com.android.tzdata/etc/tz/tzdata",
                "apex/com.android.runtime/etc/tz/tzdata", "system/usr/share/zoneinfo/tzdata")

            fun open(root: File): Db? {
                for (rel in TZDATA) {
                    val f = File(root, rel)
                    if (!f.isFile || f.length() < 24) continue
                    val head = RandomAccessFile(f, "r").use { r -> ByteArray(24).also { r.readFully(it) } }
                    if (!String(head, 0, 6, Charsets.ISO_8859_1).startsWith("tzdata")) continue
                    val bb = ByteBuffer.wrap(head, 12, 12)
                    val indexAt = bb.int; val dataAt = bb.int
                    if (indexAt < 24 || dataAt < indexAt || dataAt > f.length()) continue
                    val idx = RandomAccessFile(f, "r").use { r -> ByteArray(dataAt - indexAt).also { r.seek(indexAt.toLong()); r.readFully(it) } }
                    return Db(f, dataAt.toLong(), entries(idx))
                }
                val idx = File(root, "system/usr/share/zoneinfo/zoneinfo.idx")
                val dat = File(root, "system/usr/share/zoneinfo/zoneinfo.dat")
                if (idx.isFile && dat.isFile && idx.length() < 1_000_000) return Db(dat, 0, entries(idx.readBytes()))
                return null
            }

            private fun entries(b: ByteArray): Map<String, Pair<Int, Int>> {
                val m = HashMap<String, Pair<Int, Int>>()
                val bb = ByteBuffer.wrap(b)
                var i = 0
                while (i + 52 <= b.size) {
                    var n = 0
                    while (n < 40 && b[i + n] != 0.toByte()) n++
                    val name = String(b, i, n, Charsets.US_ASCII)
                    m[name] = bb.getInt(i + 40) to bb.getInt(i + 44)
                    i += 52
                }
                return m
            }
        }
    }

    /**
     * UTC offset in ms at [t] (seconds) by a TZif file: of the last transition at or before it, or of the first
     * standard-time type before the first one. The 32-bit block is what older libcore reads; a file that keeps its
     * transitions only in the 64-bit block (zic -b slim) is read there.
     */
    fun tzifOffset(b: ByteArray, t: Long): Int? {
        if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "TZif") return null
        val bb = ByteBuffer.wrap(b)
        fun counts(at: Int) = IntArray(6) { bb.getInt(at + 20 + it * 4) }
        val c1 = counts(0)
        val v1Len = c1[3] * 5 + c1[4] * 6 + c1[5] + c1[2] * 8 + c1[1] + c1[0]
        val v2At = 44 + v1Len
        val useV2 = b[4] >= '2'.code.toByte() && c1[3] == 0 && v2At + 44 <= b.size && String(b, v2At, 4, Charsets.US_ASCII) == "TZif"
        val head = if (useV2) v2At else 0
        val c = if (useV2) counts(v2At) else c1
        val tsz = if (useV2) 8 else 4
        val timecnt = c[3]; val typecnt = c[4]
        if (typecnt !in 1..256 || timecnt < 0) return null
        val times = head + 44
        val idx = times + timecnt * tsz
        val types = idx + timecnt
        if (types + typecnt * 6 > b.size) return null
        fun time(i: Int): Long = if (tsz == 8) bb.getLong(times + i * 8) else bb.getInt(times + i * 4).toLong()
        var type = -1
        for (i in 0 until timecnt) if (time(i) <= t) type = b[idx + i].toInt() and 0xff else break
        if (type < 0) type = (0 until typecnt).firstOrNull { b[types + it * 6 + 4] == 0.toByte() } ?: 0
        if (type >= typecnt) return null
        return bb.getInt(types + type * 6) * 1000
    }
}
