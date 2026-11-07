/* AEmulator Sunset addition, 2026-10-10. GPL-3.0. */
package app.aemu.core

/**
 * Chinese "xhl" firmware (NDLauncher, com.android.launcher2.Launcher): the end of onCreate() is
 *
 *     mSensorManager = getSystemService("sensor");
 *     mProximity = mSensorManager.getSensorList(Sensor.TYPE_PROXIMITY).get(0);     // Launcher.java:765
 *     Log.d("Launcher", "Create sensors_proximity");
 *
 * The guest's sensor HAL (sensors.aemu_host) offers an accelerometer, a magnetometer and a gyroscope, no proximity
 * sensor, so the list is empty, get(0) throws IndexOutOfBoundsException, the activity dies in onCreate and the
 * system relaunches it forever (it is the only HOME app). onResume()/onPause() already skip a null mProximity.
 *
 * The odex is edited in place, in Dalvik bytecode, to the same effect as
 *
 *     List l = mSensorManager.getSensorList(8);
 *     if (!l.isEmpty()) mProximity = (Sensor) l.get(0);
 *
 * The 15 code units from `invoke-interface List.get` up to the `Log.d` call are rewritten in the same space (the
 * "Create sensors_proximity" log line goes away), the code after them is untouched:
 *
 *     invoke-interface {vL}, List.isEmpty()Z      invoke-interface {vL, vI}, List.get(I)   (as before)
 *     move-result vX                              move-result-object vX                    (as before)
 *     if-nez vX, :done                            check-cast vX, Sensor                    (as before)
 *                                                 iput-object-quick vX, this, <proximity field>  (as before)
 *                                                 nop
 *     :done  goto/16 -> return-void               (as before)
 *
 * Nothing is hard-coded to one build: the method/string indices are looked up in the dex tables and the code has to
 * match instruction by instruction (also on a plain, non-quickened dex), otherwise the file is left alone. Like
 * [DexStringPatch] it keeps the dex adler32 valid and leaves the SHA-1 signature alone. Dalvik (API 20 and below) only.
 */
internal object LauncherProximityPatch {
    const val PATCHED = 1
    const val ALREADY = 0
    /** Not a dex, or not the launcher with the proximity lookup. */
    const val ABSENT = -1
    /** The marker string is there, the code around it is not what this patch knows. */
    const val UNEXPECTED = -2

    private const val MARKER = "Create sensors_proximity"
    private const val LIST = "Ljava/util/List;"
    private const val REGION = 15 // code units rewritten: invoke-interface get .. invoke-static Log.d
    private const val SPAN = 17   // the same plus the goto/16 (2 units) that follows

    fun apply(d: ByteArray): Int {
        val r = DexStringPatch.region(d) ?: return ABSENT
        val t = Tables.parse(d, r.first, r.second) ?: return ABSENT
        val marker = t.stringIndex(MARKER)
        if (marker < 0) return ABSENT
        val get = t.methodIndex(LIST, "get")
        val isEmpty = t.methodIndex(LIST, "isEmpty")
        if (marker > 0xffff || get < 0 || isEmpty < 0) return UNEXPECTED
        val units = (r.second - r.first) / 2
        val w: (Int) -> Int = { i -> (d[r.first + 2 * i].toInt() and 0xff) or ((d[r.first + 2 * i + 1].toInt() and 0xff) shl 8) }
        for (s in 0..units - SPAN) {
            if (w(s) == 0x1072 && w(s + 1) == isEmpty && patchedAt(w, s, get)) return ALREADY
            if (w(s + 11) != marker || !originalAt(w, s, get, units)) continue
            val valid = DexStringPatch.adler(d, r) == u32(d, r.first + 8)
            val list = w(s + 2) and 0xf
            val x = w(s + 3) ushr 8
            val old = IntArray(8) { w(s + it) } // invoke-interface get (3), move-result-object, check-cast (2), iput-object-quick (2)
            fun put(i: Int, v: Int) { d[r.first + 2 * (s + i)] = v.toByte(); d[r.first + 2 * (s + i) + 1] = (v ushr 8).toByte() }
            put(0, 0x1072); put(1, isEmpty); put(2, list)           // invoke-interface {vL}, List.isEmpty()Z
            put(3, (x shl 8) or 0x0a)                                // move-result vX
            put(4, (x shl 8) or 0x39); put(5, REGION - 4)            // if-nez vX, :done (the goto/16 at s + REGION)
            put(6, old[0]); put(7, old[1]); put(8, old[2])           // invoke-interface {vL, vI}, List.get(I)
            put(9, old[3]); put(10, old[4]); put(11, old[5])         // move-result-object vX; check-cast vX, Sensor
            put(12, old[6]); put(13, old[7])                         // iput-object-quick vX, this, <proximity field>
            put(14, 0)                                               // nop
            if (valid) {
                val ck = DexStringPatch.adler(d, r)
                for (k in 0..3) d[r.first + 8 + k] = (ck ushr (8 * k)).toByte()
            }
            return PATCHED
        }
        return UNEXPECTED
    }

    /**
     * invoke-interface {vL, vI}, List.get; move-result-object vX; check-cast vX, T; iput-object[-quick] vX, vO, f;
     * const-string vA, "Launcher"; const-string vB, MARKER; invoke-static {vA, vB}, Log.d; goto/16 -> return-void.
     * [w] reads code unit i of the dex, [s] is the first unit of the sequence.
     */
    private fun originalAt(w: (Int) -> Int, s: Int, get: Int, units: Int): Boolean {
        val x = w(s + 3) ushr 8
        val target = s + REGION + w(s + REGION + 1).toShort() // goto/16: opcode at s + REGION, offset in the next unit
        val iput = w(s + 6)
        return w(s) == 0x2072 && w(s + 1) == get &&
            (w(s + 3) and 0xff) == 0x0c &&
            (w(s + 4) and 0xff) == 0x1f && (w(s + 4) ushr 8) == x &&
            x < 16 && ((iput and 0xff) == 0xf7 || (iput and 0xff) == 0x5b) && ((iput ushr 8) and 0xf) == x &&
            (w(s + 8) and 0xff) == 0x1a && (w(s + 10) and 0xff) == 0x1a &&
            w(s + 12) == 0x2071 && w(s + REGION) == 0x0029 &&
            target in 0 until units && (w(target) and 0xff).let { it == 0x0e || it == 0xf1 } // return-void[-barrier]
    }

    /** What [apply] writes: isEmpty, move-result vX, if-nez vX +11, then List.get, move-result-object vX, check-cast vX. */
    private fun patchedAt(w: (Int) -> Int, s: Int, get: Int): Boolean {
        val x = w(s + 3) ushr 8
        return (w(s + 3) and 0xff) == 0x0a && w(s + 4) == ((x shl 8) or 0x39) && w(s + 5) == REGION - 4 &&
            w(s + 6) == 0x2072 && w(s + 7) == get && (w(s + 9) and 0xff) == 0x0c && (w(s + 9) ushr 8) == x &&
            (w(s + 10) and 0xff) == 0x1f && w(s + REGION) == 0x0029 && w(s + 14) == 0
    }

    private fun u32(d: ByteArray, o: Int) =
        (d[o].toInt() and 0xff) or ((d[o + 1].toInt() and 0xff) shl 8) or ((d[o + 2].toInt() and 0xff) shl 16) or ((d[o + 3].toInt() and 0xff) shl 24)

    /** The few dex tables needed to turn names into indices: string_ids, type_ids and method_ids. */
    private class Tables(val d: ByteArray, val base: Int, val strings: Int, val stringsOff: Int, val types: Int, val typesOff: Int,
                         val methods: Int, val methodsOff: Int) {
        companion object {
            fun parse(d: ByteArray, base: Int, end: Int): Tables? {
                if (end - base < 112) return null
                fun at(o: Int) = u32(d, base + o)
                val t = Tables(d, base, at(0x38), at(0x3c), at(0x40), at(0x44), at(0x58), at(0x5c))
                val len = end - base
                fun ok(n: Int, off: Int, size: Int) = n in 0..len && off in 0..len && off.toLong() + n.toLong() * size <= len
                return if (ok(t.strings, t.stringsOff, 4) && ok(t.types, t.typesOff, 4) && ok(t.methods, t.methodsOff, 8)) t else null
            }
        }

        private fun stringEquals(i: Int, s: String): Boolean {
            var p = base + u32(d, base + stringsOff + 4 * i)
            if (p < base || p >= d.size) return false
            while (p < d.size && d[p].toInt() and 0x80 != 0) p++ // uleb128 length (the names looked up are < 128 chars)
            p++
            if (p + s.length >= d.size) return false
            for (k in s.indices) if (d[p + k].toInt() != s[k].toInt()) return false
            return d[p + s.length].toInt() == 0
        }

        fun stringIndex(s: String): Int {
            for (i in 0 until strings) if (stringEquals(i, s)) return i
            return -1
        }

        /** Index of the only method id named [name] in class [cls]; -1 when there is none or it is overloaded. */
        fun methodIndex(cls: String, name: String): Int {
            val descriptor = stringIndex(cls); val nameIdx = stringIndex(name)
            if (descriptor < 0 || nameIdx < 0) return -1
            var type = -1
            for (i in 0 until types) if (u32(d, base + typesOff + 4 * i) == descriptor) { type = i; break }
            if (type < 0) return -1
            var found = -1
            for (i in 0 until methods) {
                val e = base + methodsOff + 8 * i
                val cl = (d[e].toInt() and 0xff) or ((d[e + 1].toInt() and 0xff) shl 8)
                if (cl == type && u32(d, e + 4) == nameIdx) { if (found >= 0) return -1; found = i }
            }
            return found
        }
    }
}
