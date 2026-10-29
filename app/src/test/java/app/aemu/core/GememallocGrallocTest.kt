package app.aemu.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GememallocGrallocTest {
    @Test fun broadcomGrallocIsRecognised() {
        val elf = "\u007fELF....gralloc: open(/dev/gememalloc)++\u0000".toByteArray(Charsets.ISO_8859_1)
        assertTrue(TreeFixer.usesGememalloc(elf))
    }

    @Test fun otherGrallocsAreLeftAlone() {
        assertFalse(TreeFixer.usesGememalloc("\u007fELF....gralloc: /dev/graphics/fb0".toByteArray(Charsets.ISO_8859_1)))
        assertFalse(TreeFixer.usesGememalloc(null))
    }
}
