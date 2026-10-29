/* AEmulator Plus addition, 2026-10-28. GPL-3.0; see LICENSE. */
package app.aemu.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibArtReadBarrierPatchTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    // ldr r1,[r1] ; ldrb r4,[r1] ; cmp r4,r0 ; bne.w <+0x2a8>   (the bytes of AW2A.240903.001's libart)
    private val check = b(0x09, 0x68, 0x0c, 0x78, 0x84, 0x42, 0x40, 0xf0, 0x54, 0x81)
    private val nop = b(0xaf, 0xf3, 0x00, 0x80)

    private fun lib(vararg parts: ByteArray): ByteArray =
        b(0x7f, 0x45, 0x4c, 0x46) + ByteArray(64) + parts.fold(ByteArray(0)) { a, p -> a + ByteArray(32) + p } + ByteArray(32)

    @Test fun branchIsNoppedAndNothingElseChanges() {
        val d = lib(check)
        val before = d.copyOf()
        assertTrue(LibArtReadBarrierPatch.patchBytes(d))
        assertTrue(LibArtReadBarrierPatch.isPatched(d))
        val diff = d.indices.filter { d[it] != before[it] }
        assertTrue(diff.isNotEmpty() && diff.size <= 4)
        // a second run does nothing
        assertFalse(LibArtReadBarrierPatch.patchBytes(d))
    }

    @Test fun negativeBranchOffsetIsMatchedToo() {
        assertTrue(LibArtReadBarrierPatch.patchBytes(lib(b(0x09, 0x68, 0x0c, 0x78, 0x84, 0x42, 0x40, 0xf4, 0x01, 0x80))))
    }

    @Test fun twoPlacesOrNoneIsNotPatched() {
        assertFalse(LibArtReadBarrierPatch.patchBytes(lib(check, check)))
        assertFalse(LibArtReadBarrierPatch.patchBytes(lib(b(0x09, 0x68, 0x0c, 0x78, 0x84, 0x42, 0x41, 0xf0, 0x54, 0x81))))
        assertFalse(LibArtReadBarrierPatch.patchBytes(lib()))
    }

    @Test fun installPatchesTheFileOnceAndSaysSo() {
        val root = tmp.newFolder("root")
        val f = File(root, "apex/com.android.art/lib/libart.so").also { it.parentFile!!.mkdirs() }
        // (install() leaves files under 1 MiB alone: pad like a real library)
        f.writeBytes(lib(check) + ByteArray(1 shl 20))
        assertFalse(LibArtReadBarrierPatch.isInstalled(root))
        assertEquals(1, LibArtReadBarrierPatch.install(root))
        assertTrue(LibArtReadBarrierPatch.isInstalled(root))
        assertEquals(0, LibArtReadBarrierPatch.install(root))
    }

    @Test fun interpretOnlyOptionIsAddedOnce() {
        assertEquals("-Xint", LibArtReadBarrierPatch.withInterpretOnly(null))
        assertEquals("-Xnorelocate -Xint", LibArtReadBarrierPatch.withInterpretOnly("-Xnorelocate"))
        assertEquals("-Xnorelocate -Xint", LibArtReadBarrierPatch.withInterpretOnly("-Xnorelocate -Xint"))
    }
}
