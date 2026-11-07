/* AEmulator Plus addition, 2026-10-29. GPL-3.0; see LICENSE. */
package app.aemu.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CcBootImageTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun header(res: Long, comps: Int, begin: Long, size: Long): ByteArray {
        val b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        b.put("art\n".toByteArray()).put("109\u0000".toByteArray())
        b.putInt(res.toInt()).putInt(comps).putInt(begin.toInt()).putInt(size.toInt())
        return b.array()
    }

    @Test fun imageHeaderOfTheAw2aFirmware() {
        // boot.art of AW2A.240903.001: 15 components at 0x70000000
        val h = CcBootImage.parseImageHeader(header(0x24b4000, 15, 0x70000000, 0x23630c))!!
        assertEquals(15, h.components); assertEquals(0x70000000L, h.begin); assertEquals(0x24b4000L, h.reservation)
        assertNull(CcBootImage.parseImageHeader(ByteArray(64)))
        assertNull(CcBootImage.parseImageHeader(ByteArray(8)))
    }

    @Test fun jarListAndFlagProbe() {
        assertEquals(listOf("/a.jar", "/b.jar"), CcBootImage.jarList(" /a.jar::/b.jar:"))
        assertTrue(CcBootImage.jarList(null).isEmpty())
        assertTrue(CcBootImage.supports(null, "--x")); assertTrue(CcBootImage.supports("usage --x=<y>", "--x"))
        assertFalse(CcBootImage.supports("usage --y", "--x"))
    }

    @Test fun commandLine() {
        val a = CcBootImage.primaryArgs("/apex/com.android.art/bin/dex2oat32", listOf("/apex/a.jar", "/system/b.jar"),
            "/system/framework/arm", 0x70000000L, "cortex-a73", null, "/system/etc/preloaded-classes", 2, "usage --multi-image --image-classes=")
        assertTrue("--dex-file=/system/b.jar" in a && "--dex-location=/system/b.jar" in a)
        assertTrue("--image=/system/framework/arm/boot.art" in a && "--oat-file=/system/framework/arm/boot.oat" in a)
        assertTrue("--base=0x70000000" in a)
        assertTrue("--instruction-set-variant=cortex-a73" in a && "--instruction-set-features=default" in a)
        assertTrue("--multi-image" in a && "--image-classes=/system/etc/preloaded-classes" in a)
        assertFalse("--no-generate-debug-info" in a)   // the help text does not name it
        assertTrue("-j2" !in a)                         // nor -j
    }

    @Test fun failureIsRememberedPerImageAndAlgo() {
        val fp = "123:abc"
        assertTrue(CcBootImage.markerMatches(CcBootImage.markerText(fp, "boom"), fp))
        assertFalse(CcBootImage.markerMatches(CcBootImage.markerText(fp, "boom"), "124:abc"))
        assertFalse(CcBootImage.markerMatches(CcBootImage.markerText(fp, "boom").replace("algo=${CcBootImage.ALGO}", "algo=0"), fp))
        assertFalse(CcBootImage.markerMatches("", null))
    }

    @Test fun bootFileNames() {
        for (n in listOf("boot.art", "boot.oat", "boot.vdex", "boot-framework.art", "boot-core-libart.oat")) assertTrue(n, CcBootImage.bootFileName(n))
        for (n in listOf("bootanimation.zip", "boot.art.bak", "services.art", "xboot.art")) assertFalse(n, CcBootImage.bootFileName(n))
    }

    @Test fun verifyRejectsIncompleteOutput() {
        val dir = tmp.newFolder("fw")
        assertEquals("boot.art was not written", CcBootImage.verify(tmp.root, dir, 2, 0x70000000L, 0))
        assertEquals("dex2oat exit 1", CcBootImage.verify(tmp.root, dir, 2, 0x70000000L, 1))
        File(dir, "boot.art").writeBytes(header(0x1000, 3, 0x70000000, 100).copyOf(200))
        File(dir, "boot.oat").writeBytes(ByteArray(10) { 1 }); File(dir, "boot.vdex").writeBytes(ByteArray(10) { 1 })
        assertEquals("boot.art holds 3 components, expected 2", CcBootImage.verify(tmp.root, dir, 2, 0x70000000L, 0))
        File(dir, "boot.art").writeBytes(header(0x1000, 2, 0x70000000, 100).copyOf(200))
        assertEquals("only 0 of 1 component images were written", CcBootImage.verify(tmp.root, dir, 2, 0x70000000L, 0))
    }

    // ---- the boot image built from scratch for a firmware that ships none (2026-11-01) ----

    private fun mark(root: File, text: String) = File(root, "data/.aemu-art-bootimage").apply { parentFile!!.mkdirs() }.writeText(text)
    private fun ourOat(root: File) = File(root, "system/framework/arm64/boot.oat").apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(8) { 1 })

    @Test fun scratchPlanBuildsWhenThereIsNoImageAtAll() {
        assertEquals("build", CcBootImage.scratchPlan(tmp.root, "sig"))
    }

    @Test fun scratchPlanLeavesTheFirmwaresOwnImageToRebuild() {
        ourOat(tmp.root) // an image in the firmware's directory, no mark of ours
        assertEquals("keep", CcBootImage.scratchPlan(tmp.root, "sig"))
        assertFalse(CcBootImage.ownsImage(tmp.root))
    }

    @Test fun scratchPlanKeepsOursUntilABootJarChanges() {
        ourOat(tmp.root)
        mark(tmp.root, CcBootImage.scratchMark("sig", "ok"))
        assertEquals("keep", CcBootImage.scratchPlan(tmp.root, "sig"))
        assertTrue(CcBootImage.ownsImage(tmp.root))
        assertEquals("build", CcBootImage.scratchPlan(tmp.root, "other-jars"))
    }

    @Test fun scratchPlanDoesNotRetryTheSameFailure() {
        mark(tmp.root, CcBootImage.scratchMark("sig", "failed", "boom"))
        assertEquals("failed", CcBootImage.scratchPlan(tmp.root, "sig"))
        assertEquals("build", CcBootImage.scratchPlan(tmp.root, "changed"))
        assertFalse(CcBootImage.ownsImage(tmp.root))
    }

    @Test fun ownImageIsNotRebuiltForTheOldFirmwaresReadBarrierState() {
        ourOat(tmp.root)
        mark(tmp.root, CcBootImage.scratchMark("sig", "ok"))
        assertFalse(CcBootImage.needed(tmp.root, 36))
    }

    @Test fun scratchImageIsNotJudgedByItsReadBarrierKey() {
        val dir = tmp.newFolder("arm64")
        File(dir, "boot.art").writeBytes(header(0x1000, 2, 0x70000000, 100).copyOf(200))
        File(dir, "boot.oat").writeBytes(ByteArray(10) { 1 }); File(dir, "boot.vdex").writeBytes(ByteArray(10) { 1 })
        File(dir, "boot-framework.art").writeBytes(ByteArray(10) { 1 })
        assertEquals("the new boot.oat still has no read barrier", CcBootImage.verify(tmp.root, dir, 2, 0x70000000L, 0))
        assertNull(CcBootImage.verify(tmp.root, dir, 2, 0x70000000L, 0, requireCc = false))
    }

    @Test fun primaryArgsFor64BitRuntime() {
        val a = CcBootImage.primaryArgs("/apex/com.android.art/bin/dex2oat64", listOf("/apex/a.jar"), "/system/framework/arm64", 0x70000000L,
            "cortex-a53", "a53,crc", null, 2, null, "arm64")
        assertTrue(a.contains("--instruction-set=arm64"))
        assertTrue(a.contains("--image=/system/framework/arm64/boot.art"))
        assertTrue(a.contains("--base=0x70000000"))
        assertFalse(a.contains("--instruction-set=arm"))
    }
}
