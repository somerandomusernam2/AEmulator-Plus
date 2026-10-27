package app.aemu.importer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class GptDiskTest {
    private fun disk(vararg parts: Triple<String, Long, Long>): ByteArray {
        val b = ByteArray(512 * 2 + 128 * 128)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        "EFI PART".toByteArray().copyInto(b, 512)
        bb.putLong(512 + 72, 2); bb.putInt(512 + 80, 128); bb.putInt(512 + 84, 128)
        parts.forEachIndexed { i, (n, s, e) ->
            val o = 1024 + i * 128
            b[o] = 1
            bb.putLong(o + 32, s); bb.putLong(o + 40, e)
            n.toByteArray(Charsets.UTF_16LE).copyInto(b, o + 56)
        }
        return b
    }

    @Test fun parsesAndPicks() {
        val d = disk(Triple("sbl1", 1024, 3071), Triple("boot", 128000, 172999), Triple("recovery", 173000, 217999), Triple("system", 220000, 744287))
        assertTrue(GptDisk.probe(d.copyOf(1100)))
        assertEquals(d.size, GptDisk.headSize(d.copyOf(1100), 1100))
        val picks = GptDisk.pick(GptDisk.parse(d)!!)
        assertEquals(listOf(GptDisk.Kind.BOOT, GptDisk.Kind.RECOVERY, GptDisk.Kind.SYSTEM), picks.map { it.first })
        assertEquals(128000L * 512, picks[0].second.start)
        assertEquals(524288L * 512, picks[2].second.size)
    }

    @Test fun picksAbSlotAAndGapps() {
        val d = disk(Triple("uboot_a", 100, 199), Triple("boot_a", 200, 299), Triple("boot_b", 300, 399),
            Triple("system_a", 400, 999), Triple("system_b", 1000, 1599), Triple("oem_a", 1600, 1699),
            Triple("gapps_a", 1700, 2299), Triple("gapps_b", 2300, 2899), Triple("userdata", 2900, 9999))
        val picks = GptDisk.pick(GptDisk.parse(d)!!)
        assertEquals(listOf(GptDisk.Kind.BOOT, GptDisk.Kind.OEM, GptDisk.Kind.SYSTEM, GptDisk.Kind.GAPPS), picks.map { it.first })
        assertEquals(listOf("boot_a", "oem_a", "system_a", "gapps_a"), picks.map { it.second.name })
    }

    /** Android Things on a Raspberry Pi 3 (iot_rpi3.img): the HAL services and impls live in vendor_a, not in system_a. */
    @Test fun picksVendorAfterSystem() {
        val d = disk(Triple("rpiboot", 40, 131111), Triple("boot_a", 135208, 200743), Triple("boot_b", 200744, 266279),
            Triple("system_a", 266280, 1314855), Triple("system_b", 1314856, 2363431), Triple("misc", 2363688, 2365735),
            Triple("vendor_a", 2365736, 2496807), Triple("vendor_b", 2496808, 2627879), Triple("oem_a", 2742568, 3791143),
            Triple("userdata", 4839720, 8388567))
        val picks = GptDisk.pick(GptDisk.parse(d)!!)
        assertEquals(listOf(GptDisk.Kind.BOOT, GptDisk.Kind.OEM, GptDisk.Kind.SYSTEM, GptDisk.Kind.VENDOR), picks.map { it.first })
        assertEquals(listOf("boot_a", "oem_a", "system_a", "vendor_a"), picks.map { it.second.name })
        assertEquals(2365736L * 512, picks[3].second.start)
        assertEquals(131072L * 512, picks[3].second.size)
    }

    @Test fun rejectsNonGpt() {
        assertFalse(GptDisk.probe(ByteArray(8192)))
        assertNull(GptDisk.headSize(ByteArray(8192), 8192))
    }
}
