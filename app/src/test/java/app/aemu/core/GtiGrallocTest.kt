package app.aemu.core

import app.aemu.core.GrallocProbe.Verdict
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Gralloc same-process detection: which binaries are broken, who gets the Eclair variant, and that the variant differs from the KK gralloc only in the two patched functions. */
class GtiGrallocTest {
    private val kk = File("src/main/assets/engines/kk")
    private val gb = File("src/main/assets/engines/gb")
    private fun bytes(dir: File, name: String) = File(dir, name).readBytes()

    @Test fun kitKatGrallocIsDetectedAsBroken() {
        assertEquals(Verdict.UNGUARDED, GrallocProbe.verdict(bytes(kk, "gralloc.default.so")))
    }

    @Test fun eclairBehaviourGrallocsAreNotBroken() {
        assertEquals(Verdict.GUARDED, GrallocProbe.verdict(bytes(gb, "gralloc.default.so")))
        assertEquals(Verdict.GUARDED, GrallocProbe.verdict(bytes(kk, GtiGralloc.ASSET)))
    }

    @Test fun unreadableInputIsUnknown() {
        assertEquals(Verdict.UNKNOWN, GrallocProbe.verdict(ByteArray(0)))
        assertEquals(Verdict.UNKNOWN, GrallocProbe.verdict("not an elf at all, just text".toByteArray()))
        val kkBytes = bytes(kk, "gralloc.default.so")
        assertEquals(Verdict.UNKNOWN, GrallocProbe.verdict(kkBytes.copyOf(0x40)))                 // truncated headers
        val notArm = kkBytes.copyOf().also { it[18] = 3 }                                            // EM_386
        assertEquals(Verdict.UNKNOWN, GrallocProbe.verdict(notArm))
        val noModule = kkBytes.copyOf().also { it[0x2004] = 0 }                                      // HMI tag gone
        assertEquals(Verdict.UNKNOWN, GrallocProbe.verdict(noModule))
    }

    @Test fun variantOnlyWhenEngineGrallocBreaksTheFirmware() {
        val kkStd = bytes(kk, "gralloc.default.so")
        val eclairLike = bytes(gb, "gralloc.default.so")   // a firmware gralloc that leaves same-process buffers alone
        // any firmware with an Eclair-behaviour gralloc, whatever the model
        assertTrue(GtiGralloc.applies(eclairLike, kkStd))
        // firmware gralloc that is itself KK-like: its SurfaceFlinger already copes, keep the standard one
        assertFalse(GtiGralloc.applies(kkStd, kkStd))
        // nothing to compare, or not readable: not proven broken
        assertFalse(GtiGralloc.applies(null, kkStd))
        assertFalse(GtiGralloc.applies(eclairLike, null))
        assertFalse(GtiGralloc.applies("garbage".toByteArray(), kkStd))
        // an engine gralloc that already has the Eclair behaviour needs no replacement
        assertFalse(GtiGralloc.applies(eclairLike, eclairLike))
        assertFalse(GtiGralloc.applies(eclairLike, bytes(kk, GtiGralloc.ASSET)))
    }

    @Test fun variantDiffersOnlyInRegisterAndUnregister() {
        val a = bytes(kk, "gralloc.default.so")
        val b = bytes(kk, GtiGralloc.ASSET)
        assertEquals(a.size, b.size)
        val changed = a.indices.filter { a[it] != b[it] }
        assertTrue(changed.isNotEmpty())
        // gralloc_register_buffer tail (0x1584..0x1593: the log call, now the same-process return + pid helper) and
        // gralloc_unregister_buffer (0x15a0..0x15c1)
        for (i in changed) assertTrue("unexpected change at 0x${i.toString(16)}", i in 0x1584 until 0x1594 || i in 0x15a0 until 0x15c2)
    }
}
