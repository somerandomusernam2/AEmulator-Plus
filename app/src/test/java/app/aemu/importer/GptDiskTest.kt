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

    @Test fun rejectsNonGpt() {
        assertFalse(GptDisk.probe(ByteArray(8192)))
        assertNull(GptDisk.headSize(ByteArray(8192), 8192))
    }
}
