package app.aemu.core

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class DeltaOtaBootMatchTest {
    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    private val src = ByteArray(5000) { (it * 31).toByte() }
    private val tgt = ByteArray(4096) { (it * 17).toByte() }
    private val spec = "EMMC:/dev/block/platform/msm_sdcc.1/by-name/boot:${src.size}:${sha1(src)}:${tgt.size}:${sha1(tgt)}"

    private fun match(b: ByteArray) = DeltaOta.matchBootImage(b, spec, sha1(src), sha1(tgt))

    @Test fun exactImageMatches() {
        val m = match(src)!!
        assertFalse(m.isTarget)
        assertArrayEquals(src, m.image)
    }

    @Test fun imagePaddedToPartitionSizeIsCutToTheSizeTheOtaHashes() {
        val m = match(src.copyOf(16384))!!
        assertFalse(m.isTarget)
        assertArrayEquals(src, m.image)
    }

    @Test fun imageWithDroppedPaddingIsPaddedBack() {
        val padded = src.copyOf(src.size + 100)
        val spec2 = "EMMC:/dev/x/boot:${padded.size}:${sha1(padded)}:${tgt.size}:${sha1(tgt)}"
        val m = DeltaOta.matchBootImage(src, spec2, sha1(padded), sha1(tgt))!!
        assertArrayEquals(padded, m.image)
    }

    @Test fun alreadyUpdatedImageIsRecognisedAlsoWhenPadded() {
        val m = match(tgt.copyOf(8192))!!
        assertTrue(m.isTarget)
    }

    @Test fun reallyModifiedImageDoesNotMatch() {
        val bad = src.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertNull(match(bad))
        assertNull(match(bad.copyOf(16384)))
    }
}
