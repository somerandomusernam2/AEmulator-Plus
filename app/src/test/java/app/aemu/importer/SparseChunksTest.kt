package app.aemu.importer

import org.junit.Assert.*
import org.junit.Test

class SparseChunksTest {
    private fun sel(vararg n: String) = SparseChunks.select(n.toList())

    @Test fun everyVariantIsRecognised() {
        val stems = listOf("system.img_sparsechunk", "system.img_sparsechunk.", "system_sparsechunk", "system_sparsechunk.",
            "system.img.", "system.img", "system.", "system_")
        for (st in stems) for (first in 0..1) {
            val names = (first..first + 11).map { "$st$it" }.shuffled()
            assertEquals("$st from $first", (first..first + 11).map { "$st$it" }, SparseChunks.select(names))
        }
    }

    @Test fun startsAtZeroOrOneAndSortsNumerically() {
        assertEquals(listOf("system.img.1", "system.img.2", "system.img.10"), sel("system.img.10", "system.img.2", "system.img.1"))
        assertEquals(listOf("system_0", "system_1", "system_2"), sel("system_2", "system_0", "system_1"))
    }

    @Test fun caseFoldersAndNoise() {
        assertEquals(listOf("fw/SYSTEM.IMG_SPARSECHUNK1", "fw/SYSTEM.IMG_SPARSECHUNK2"),
            sel("fw/SYSTEM.IMG_SPARSECHUNK2", "fw/SYSTEM.IMG_SPARSECHUNK1", "fw/boot.img", "fw/system.img", "fw/vendor.img_sparsechunk.0"))
        assertTrue(sel("system.img", "system", "system.img.ext4", "system_image.img", "vendor_0", "mysystem.0").isEmpty())
        assertNull(SparseChunks.indexOf("system.img"))
        assertEquals(3, SparseChunks.indexOf("a\\b\\system_sparsechunk.3"))
    }

    @Test fun schemesDoNotMixAndLargestSetWins() {
        assertEquals(listOf("system.img_sparsechunk.0", "system.img_sparsechunk.1", "system.img_sparsechunk.2"),
            sel("system.0", "system.img_sparsechunk.2", "system.img_sparsechunk.0", "system.img_sparsechunk.1"))
    }
}
