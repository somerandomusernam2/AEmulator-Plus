package app.aemu.core

import org.junit.Assert.*
import org.junit.Test

class LegacyLogoPatchTest {
    private fun hex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()
    // playlogos1 of Samsung GT-I9000 JPF: the /dev/tty0 + KDSETMODE helper
    private val original = "00 28 04 46 03 da fe f7 a6 ee 64 25 08 e0 44 f6 3a 31 01 22 fe f7 ec ed 05 46 20 46"

    @Test fun makesBothPathsReturnZero() {
        val d = hex("aa bb $original cc dd")
        assertEquals(1, LegacyLogoPatch.patch(d))
        assertArrayEquals(hex("aa bb 00 28 04 46 03 da fe f7 a6 ee 00 25 08 e0 44 f6 3a 31 01 22 fe f7 ec ed 00 25 20 46 cc dd"), d)
    }

    @Test fun secondRunAndOtherBinariesAreLeftAlone() {
        val d = hex(original)
        LegacyLogoPatch.patch(d)
        assertEquals(0, LegacyLogoPatch.patch(d))
        assertEquals(0, LegacyLogoPatch.patch(ByteArray(64)))
    }
}
