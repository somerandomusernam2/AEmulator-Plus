/* AEmulator Sunset addition. GPL-3.0; see LICENSE. */
package app.aemu.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** block_image_verify: the transfer list is checked against an image without changing it. */
class BlockOtaVerifyTest {
    private val blk = BlockOta.BLOCK

    private fun image(blocks: Int): File = File.createTempFile("verify-", ".img").also { f ->
        f.deleteOnExit()
        f.writeBytes(ByteArray(blocks * blk) { (it * 31 + it / blk).toByte() })
    }

    private fun sha(f: File, first: Int, count: Int): String {
        val b = f.readBytes().copyOfRange(first * blk, (first + count) * blk)
        return SystemLayout.hex(MessageDigest.getInstance("SHA-1").digest(b))
    }

    private fun work() = File.createTempFile("verify-work", "").let { it.delete(); it.mkdirs(); it.deleteOnExit(); it }

    /** version 4: stash blocks 0-1, read them back from the image and from the stash, free the stash */
    private fun list(h: String, extra: String = "") = """
        4
        8
        1
        2
        stash $h 2,0,2
        move $h 2,4,6 2 2,0,2
        move $h 2,6,8 2 - $h:2,0,2
        free $h
        $extra
    """.trimIndent()

    @Test fun `a matching image verifies and is left untouched`() {
        val img = image(8)
        val before = img.readBytes()
        BlockOta.verify(img, BlockOta.parse(list(sha(img, 0, 2))), 0, work()) {}
        assertArrayEquals(before, img.readBytes())
    }

    @Test fun `a changed source block is reported`() {
        val img = image(8)
        val h = sha(img, 0, 2)
        img.writeBytes(img.readBytes().also { it[5] = (it[5] + 1).toByte() })
        try {
            BlockOta.verify(img, BlockOta.parse(list(h)), 0, work()) {}
            fail("expected an OtaException")
        } catch (e: OtaException) {
            assertTrue(e.message!!, e.message!!.contains("differ"))
        }
    }

    @Test fun `a patch outside the patch data is reported`() {
        val img = image(8)
        val h = sha(img, 0, 2)
        val text = list(h, "bsdiff 0 100 $h $h 2,2,4 2 2,0,2")
        BlockOta.verify(img, BlockOta.parse(text), 100, work()) {}
        try {
            BlockOta.verify(img, BlockOta.parse(text), 50, work()) {}
            fail("expected an OtaException")
        } catch (e: OtaException) {
            assertTrue(e.message!!, e.message!!.contains("patch data"))
        }
    }

    @Test fun `a command that is not understood is refused`() {
        val img = image(8)
        try {
            BlockOta.verify(img, BlockOta.parse(list(sha(img, 0, 2), "frobnicate 2,0,1")), 0, work()) {}
            fail("expected an OtaException")
        } catch (e: OtaException) {
            assertTrue(e.message!!, e.message!!.contains("frobnicate"))
        }
    }
}
