package app.aemu.core

import org.junit.Assert.*
import org.junit.Test

class DexCpuinfoSwapTest {
    private fun blob(s: String) = byteArrayOf(0, 13) + s.toByteArray() + byteArrayOf(0, 1, 2)

    @Test fun swapsWholeStringOnly() {
        val d = blob("/proc/cpuinfo")
        assertEquals(1, DexStringPatch.apply(d, "/proc/cpuinfo", "/data/.cpuinf"))
        assertEquals("/data/.cpuinf", String(d, 2, 13))
        assertEquals(0, DexStringPatch.apply(d, "/proc/cpuinfo", "/data/.cpuinf"))
    }

    @Test fun missingStringIsReported() = assertEquals(-1, DexStringPatch.apply(ByteArray(64), "/proc/cpuinfo", "/data/.cpuinf"))

    @Test(expected = IllegalArgumentException::class) fun lengthMustMatch() { DexStringPatch.apply(ByteArray(64), "/proc/cpuinfo", "/x") }
}
