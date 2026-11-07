package app.aemu.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Adler32

class LauncherProximityPatchTest {
    private val GET = 0; private val IS_EMPTY = 1; private val MARKER = 0

    /** NDLauncher's Launcher.onCreate() tail, as found in the odex (quickened). [first] is the unit the sequence starts at. */
    private fun original(first: Int, iput: Int = 0xf7, target: Int = 0): IntArray {
        val goto = first + 15
        return intArrayOf(
            0x2072, GET, 0x00a6,        // invoke-interface {v6, v10}, List.get(I)
            0x080c,                     // move-result-object v8
            0x081f, 0x008b,             // check-cast v8, Sensor
            0xc800 or iput, 0x0100,     // iput-object-quick v8, v12, +0x100
            0x081a, 4,                  // const-string v8, "Launcher"
            0x091a, MARKER,             // const-string v9, "Create sensors_proximity"
            0x2071, 0x0e05, 0x0098,     // invoke-static {v8, v9}, Log.d
            0x0029, (target - goto) and 0xffff  // goto/16 -> return-void
        )
    }

    private fun expected(first: Int, iput: Int = 0xf7, target: Int = 0): IntArray {
        val o = original(first, iput, target)
        return intArrayOf(
            0x1072, IS_EMPTY, 0x0006,   // invoke-interface {v6}, List.isEmpty()Z
            0x080a,                     // move-result v8
            0x0839, 0x000b,             // if-nez v8, +11  (the goto/16)
            o[0], o[1], o[2], o[3], o[4], o[5], o[6], o[7], // List.get, move-result-object, check-cast, iput-object-quick
            0x0000,                     // nop
            o[15], o[16]                // goto/16
        )
    }

    /** A minimal dex: header, string_ids, one type (List), two methods (List.get, List.isEmpty), strings, then [code]. */
    private fun dex(code: IntArray, marker: String = "Create sensors_proximity"): ByteArray {
        val strings = listOf(marker, "Ljava/util/List;", "get", "isEmpty", "Launcher")
        val data = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        val dataStart = 0x70 + 4 * strings.size + 4 + 16
        for (s in strings) { offsets += dataStart + data.size(); data.write(s.length); data.write(s.toByteArray()); data.write(0) }
        while ((dataStart + data.size()) % 4 != 0) data.write(0)
        val codeStart = dataStart + data.size()
        val b = ByteBuffer.allocate(codeStart + 2 * code.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("dex\n035\u0000".toByteArray())
        b.putInt(32, b.capacity()); b.putInt(36, 0x70); b.putInt(40, 0x12345678)
        b.putInt(56, strings.size); b.putInt(60, 0x70)
        b.putInt(64, 1); b.putInt(68, 0x70 + 4 * strings.size)
        b.putInt(88, 2); b.putInt(92, 0x70 + 4 * strings.size + 4)
        for ((i, o) in offsets.withIndex()) b.putInt(0x70 + 4 * i, o)
        b.putInt(0x70 + 4 * strings.size, 1)                      // type 0 = string 1 ("Ljava/util/List;")
        val m = 0x70 + 4 * strings.size + 4
        b.putShort(m, 0); b.putShort(m + 2, 0); b.putInt(m + 4, 2)  // List.get
        b.putShort(m + 8, 0); b.putShort(m + 10, 0); b.putInt(m + 12, 3) // List.isEmpty
        b.position(dataStart); b.put(data.toByteArray())
        for ((i, w) in code.withIndex()) b.putShort(codeStart + 2 * i, w.toShort())
        fixAdler(b.array(), 0)
        return b.array()
    }

    private fun fixAdler(d: ByteArray, base: Int, end: Int = d.size) {
        val a = Adler32().also { it.update(d, base + 12, end - base - 12) }.value.toInt()
        ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN).putInt(base + 8, a)
    }

    private fun adlerOk(d: ByteArray, base: Int = 0, end: Int = d.size): Boolean {
        val a = Adler32().also { it.update(d, base + 12, end - base - 12) }.value.toInt()
        return ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN).getInt(base + 8) == a
    }

    private fun words(d: ByteArray, from: Int, count: Int): IntArray {
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(count) { b.getShort(from + 2 * it).toInt() and 0xffff }
    }

    /** return-void, nop, then the sequence (so that it does not start at unit 0 and the goto points backwards). */
    private fun method(iput: Int = 0xf7): IntArray = intArrayOf(0x000e, 0x0000) + original(2, iput, 0)
    private fun codeStart(d: ByteArray) = d.size - 2 * method().size

    @Test fun guardsTheListLookup() {
        val d = dex(method())
        val before = d.copyOf()
        assertEquals(LauncherProximityPatch.PATCHED, LauncherProximityPatch.apply(d))
        assertArrayEquals(intArrayOf(0x000e, 0x0000) + expected(2), words(d, codeStart(d), method().size))
        assertTrue(adlerOk(d))
        // nothing but the code and the checksum changed
        val changed = d.indices.filter { d[it] != before[it] }
        assertTrue(changed.all { it in 8..11 || it >= codeStart(d) })
    }

    @Test fun secondRunChangesNothing() {
        val d = dex(method())
        assertEquals(LauncherProximityPatch.PATCHED, LauncherProximityPatch.apply(d))
        val once = d.copyOf()
        assertEquals(LauncherProximityPatch.ALREADY, LauncherProximityPatch.apply(d))
        assertArrayEquals(once, d)
    }

    @Test fun plainDexUsesIputObjectInsteadOfTheQuickForm() {
        val d = dex(method(iput = 0x5b))
        assertEquals(LauncherProximityPatch.PATCHED, LauncherProximityPatch.apply(d))
        assertArrayEquals(intArrayOf(0x000e, 0x0000) + expected(2, iput = 0x5b), words(d, codeStart(d), method().size))
    }

    @Test fun worksInsideAnOdex() {
        val inner = dex(method())
        val odex = ByteBuffer.allocate(40 + inner.size + 16).order(ByteOrder.LITTLE_ENDIAN)
        odex.put("dey\n036\u0000".toByteArray()); odex.putInt(8, 40); odex.putInt(12, inner.size)
        odex.position(40); odex.put(inner)
        val d = odex.array()
        assertEquals(LauncherProximityPatch.PATCHED, LauncherProximityPatch.apply(d))
        assertTrue(adlerOk(d, 40, 40 + inner.size))
        assertArrayEquals(intArrayOf(0x000e, 0x0000) + expected(2), words(d, 40 + inner.size - 2 * method().size, method().size))
        assertEquals(LauncherProximityPatch.ALREADY, LauncherProximityPatch.apply(d))
    }

    @Test fun invalidChecksumStaysInvalid() {
        val d = dex(method())
        d[8] = (d[8] + 1).toByte() // the dex was already broken before; do not "repair" it
        val sum = d.copyOfRange(8, 12)
        assertEquals(LauncherProximityPatch.PATCHED, LauncherProximityPatch.apply(d))
        assertArrayEquals(sum, d.copyOfRange(8, 12))
    }

    @Test fun otherDexFilesAreLeftAlone() {
        val d = dex(method(), marker = "Something else entirely!!")
        val before = d.copyOf()
        assertEquals(LauncherProximityPatch.ABSENT, LauncherProximityPatch.apply(d))
        assertArrayEquals(before, d)
    }

    @Test fun notADexIsAbsent() {
        assertEquals(LauncherProximityPatch.ABSENT, LauncherProximityPatch.apply(ByteArray(0)))
        assertEquals(LauncherProximityPatch.ABSENT, LauncherProximityPatch.apply(ByteArray(4096)))
    }

    @Test fun unknownCodeAroundTheMarkerIsReportedAndKept() {
        val d = dex(intArrayOf(0x000e, 0x0000) + IntArray(17) { 0 }) // marker string present in the table, no lookup code
        val before = d.copyOf()
        assertEquals(LauncherProximityPatch.UNEXPECTED, LauncherProximityPatch.apply(d))
        assertArrayEquals(before, d)
    }

    @Test fun gotoThatDoesNotLeadToReturnIsRefused() {
        val code = method()
        code[0] = 0x0000 // the goto target is now a nop, not return-void
        val d = dex(code)
        val before = d.copyOf()
        assertEquals(LauncherProximityPatch.UNEXPECTED, LauncherProximityPatch.apply(d))
        assertArrayEquals(before, d)
    }
}
