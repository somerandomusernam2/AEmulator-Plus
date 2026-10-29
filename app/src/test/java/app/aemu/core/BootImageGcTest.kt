/* AEmulator Plus addition, 2026-10-28. GPL-3.0; see LICENSE. */
package app.aemu.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BootImageGcTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A minimal "oat file": padding, an oat magic, then a key-value store like dex2oat writes. */
    private fun oat(vararg kv: Pair<String, String>): ByteArray {
        val store = kv.joinToString("") { "${it.first}\u0000${it.second}\u0000" }
        return ByteArray(0x1000) + "oat\n225\u0000".toByteArray() + ByteArray(40) + store.toByteArray() + ByteArray(64)
    }

    @Test fun readsFalseAndTrue() {
        assertEquals(false, BootImageGc.parseReadBarrier(oat("compiler-filter" to "speed", "concurrent-copying" to "false", "pic" to "true")))
        assertEquals(true, BootImageGc.parseReadBarrier(oat("concurrent-copying" to "true")))
    }

    @Test fun missingKeyIsNull() {
        assertNull(BootImageGc.parseReadBarrier(oat("pic" to "true")))
        assertNull(BootImageGc.parseReadBarrier(ByteArray(0)))
    }

    @Test fun keyInsideAnotherKeyOrValueIsIgnored() {
        // "x-concurrent-copying" is another key; the same text inside a command line value is not an entry either
        assertNull(BootImageGc.parseReadBarrier(oat("x-concurrent-copying" to "false")))
        assertNull(BootImageGc.parseReadBarrier(oat("dex2oat-cmdline" to "--foo concurrent-copying=false")))
    }

    @Test fun findsTheBootOatInTheTree() {
        val root = tmp.newFolder("root")
        assertFalse(BootImageGc.bootImageIsNonCc(root))
        File(root, "system/framework/arm").mkdirs()
        File(root, "system/framework/arm/boot.oat").writeBytes(oat("concurrent-copying" to "false"))
        assertTrue(BootImageGc.bootImageIsNonCc(root))
        File(root, "system/framework/arm/boot.oat").writeBytes(oat("concurrent-copying" to "true"))
        assertFalse(BootImageGc.bootImageIsNonCc(root))
    }
}
