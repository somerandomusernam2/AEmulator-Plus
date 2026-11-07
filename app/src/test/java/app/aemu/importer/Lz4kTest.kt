package app.aemu.importer

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class Lz4kTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Blocks taken from a MediaTek UBIFS system image (type 3 data nodes): a whole page of one repeated byte. */
    @Test fun repeatedByteBlocks() {
        val ff = Lz4k.decompress(hex("f9ffff3f3f000000"), 0, 8, 4096)
        assertEquals(4096, ff.size)
        assertTrue(ff.all { it == 0xFF.toByte() })
        val b80 = Lz4k.decompress(hex("01fcff3f3f000000"), 0, 8, 4096)
        assertTrue(b80.all { it == 0x80.toByte() })
    }

    @Test fun otherBlockVariantIsRejected() {
        assertThrows(IOException::class.java) { Lz4k.decompress(hex("00112233"), 0, 4, 16) }
    }

    @Test fun truncatedInputFailsInsteadOfLooping() {
        assertThrows(IOException::class.java) { Lz4k.decompress(hex("01"), 0, 1, 4096) }
    }
}
