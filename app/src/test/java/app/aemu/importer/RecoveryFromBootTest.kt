package app.aemu.importer

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class RecoveryFromBootTest {
    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    // install-recovery.sh of the Galaxy Nexus 4.0.4 (IMM76B) OTA
    private val script = """
        #!/system/bin/sh
        if ! applypatch -c EMMC:/dev/block/platform/omap/omap_hsmmc.0/by-name/recovery:4587520:00e42d77b6fc18a18ca7978639047e3ede867d78; then
          log -t recovery "Installing new recovery image"
          applypatch EMMC:/dev/block/platform/omap/omap_hsmmc.0/by-name/boot:4247552:bcb4a0ddf8671af23c50f62de4d44e52ac18402c EMMC:/dev/block/platform/omap/omap_hsmmc.0/by-name/recovery 00e42d77b6fc18a18ca7978639047e3ede867d78 4587520 bcb4a0ddf8671af23c50f62de4d44e52ac18402c:/system/recovery-from-boot.p
        else
          log -t recovery "Recovery image already installed"
        fi
    """.trimIndent()

    @Test fun parsesTheApplypatchCallAndNotTheCheck() {
        val p = RecoveryFromBoot.parse(script)!!
        assertEquals("EMMC:/dev/block/platform/omap/omap_hsmmc.0/by-name/boot:4247552:bcb4a0ddf8671af23c50f62de4d44e52ac18402c", p.sourceSpec)
        assertEquals("00e42d77b6fc18a18ca7978639047e3ede867d78", p.targetSha)
        assertEquals(4587520L, p.targetSize)
        assertEquals(listOf("bcb4a0ddf8671af23c50f62de4d44e52ac18402c" to "/system/recovery-from-boot.p"), p.sources)
        assertNull(p.bonusPath)
    }

    @Test fun parsesBonusAndLineContinuations() {
        val s = "applypatch --bonus /system/etc/recovery-resource.dat \\\n" +
            "    EMMC:/dev/block/bootdevice/by-name/boot:9437184:${"a".repeat(40)} \\\n" +
            "    EMMC:/dev/block/bootdevice/by-name/recovery ${"b".repeat(40)} 10485760 ${"a".repeat(40)}:/system/recovery-from-boot.p\n"
        val p = RecoveryFromBoot.parse(s)!!
        assertEquals("/system/etc/recovery-resource.dat", p.bonusPath)
        assertEquals(10485760L, p.targetSize)
        assertEquals("b".repeat(40), p.targetSha)
    }

    @Test fun scriptWithoutApplypatchHasNoPlan() {
        assertNull(RecoveryFromBoot.parse("#!/system/bin/sh\nexit 0\n"))
        assertNull(RecoveryFromBoot.parse("applypatch -c EMMC:/dev/x/recovery:4587520:${"c".repeat(40)}"))
    }

    // ---- a bsdiff patch that adds 1 to every byte of the source ----------------------------------------------

    private fun offtout(v: Long): ByteArray = ByteArray(8) { (v ushr (8 * it)).toByte() }
    private fun bz2(b: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        BZip2CompressorOutputStream(o).use { it.write(b) }
        return o.toByteArray()
    }
    private fun addOnePatch(n: Int): ByteArray {
        val ctrl = bz2(offtout(n.toLong()) + offtout(0) + offtout(0))
        val diff = bz2(ByteArray(n) { 1 })
        val extra = bz2(ByteArray(0))
        return "BSDIFF40".toByteArray() + offtout(ctrl.size.toLong()) + offtout(diff.size.toLong()) + offtout(n.toLong()) + ctrl + diff + extra
    }

    private val boot = ByteArray(6000) { (it * 7).toByte() }
    private val recovery = ByteArray(boot.size) { (boot[it] + 1).toByte() }
    private val patch = addOnePatch(boot.size)
    private fun scriptFor(src: ByteArray, tgt: ByteArray) =
        "applypatch EMMC:/dev/x/boot:${src.size}:${sha1(src)} EMMC:/dev/x/recovery ${sha1(tgt)} ${tgt.size} ${sha1(src)}:/system/recovery-from-boot.p"

    @Test fun rebuildsTheRecoveryAndChecksItsHash() {
        val log = ArrayList<String>()
        val out = RecoveryFromBoot.rebuild(boot, scriptFor(boot, recovery), patch, null) { log += it }
        assertArrayEquals(recovery, out)
        assertTrue(log.any { it.contains("hash verified") })
    }

    @Test fun bootImagePaddedToThePartitionSizeStillFits() {
        val out = RecoveryFromBoot.rebuild(boot.copyOf(boot.size + 4096), scriptFor(boot, recovery), patch, null) {}
        assertArrayEquals(recovery, out)
    }

    @Test fun aBootImageTheScriptDoesNotNameIsRefused() {
        val other = boot.copyOf().also { it[100] = (it[100] + 1).toByte() }
        val log = ArrayList<String>()
        assertNull(RecoveryFromBoot.rebuild(other, scriptFor(boot, recovery), patch, null) { log += it })
        assertTrue(log.any { it.contains("different boot image") })
    }

    @Test fun withoutAScriptTheResultMustBeARecovery() {
        // the patched bytes are no Android image, so nothing proves they are a recovery
        assertNull(RecoveryFromBoot.rebuild(boot, null, patch, null) {})
    }

    @Test fun aCorruptPatchIsSkippedNotThrown() {
        assertNull(RecoveryFromBoot.rebuild(boot, scriptFor(boot, recovery), "IMGDIFF2".toByteArray() + ByteArray(8), null) {})
    }

    @Test fun fileNames() {
        assertTrue(RecoveryFromBoot.isPatchFile("recovery-from-boot.p"))
        assertTrue(RecoveryFromBoot.isScriptFile("install-recovery.sh"))
        assertTrue(RecoveryFromBoot.isBonusFile("recovery-resource.dat"))
        assertFalse(RecoveryFromBoot.isPatchFile("recovery.img"))
    }
}
