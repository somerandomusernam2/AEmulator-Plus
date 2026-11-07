/* AEmulator Plus addition. GPL-3.0; see LICENSE. */
package app.aemu.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RecoveryKindTest {
    private fun tree(block: (File) -> Unit): File = java.nio.file.Files.createTempDirectory("rec").toFile().also(block)

    @Test fun cwmIsRecognisedByItsIconEvenWithoutCwmInDefaultProp() {
        val d = tree { File(it, "res/images").mkdirs(); File(it, "res/images/icon_clockwork.png").createNewFile()
            File(it, "default.prop").writeText("ro.rommanager.developerid=cyanogenmod\n") }
        assertEquals(RecoveryImage.Kind.CWM, RecoveryImage.kindOf(d))
    }

    @Test fun cwmIsRecognisedByTheBannerInTheBinary() {
        val d = tree { File(it, "sbin").mkdirs(); File(it, "sbin/recovery").writeBytes("xx CWM-based Recovery v6.0.3.7 yy".toByteArray()) }
        assertEquals(RecoveryImage.Kind.CWM, RecoveryImage.kindOf(d))
    }

    @Test fun twrpAndOrangeFox() {
        assertEquals(RecoveryImage.Kind.TWRP, RecoveryImage.kindOf(tree { File(it, "default.prop").writeText("ro.twrp.version=3.7.0\n") }))
        assertEquals(RecoveryImage.Kind.ORANGEFOX, RecoveryImage.kindOf(tree { File(it, "sbin").mkdirs(); File(it, "sbin/orangefox.sh").createNewFile() }))
    }

    @Test fun anythingElseIsStock() {
        val d = tree { File(it, "sbin").mkdirs(); File(it, "sbin/recovery").writeBytes(byteArrayOf(0x7f, 'E'.code.toByte())) }
        assertEquals(RecoveryImage.Kind.STOCK, RecoveryImage.kindOf(d))
    }

    @Test fun recoveryButtonsParseToVolumePowerBack() {
        assertEquals(listOf(NavButton.VOLUME_UP, NavButton.VOLUME_DOWN, NavButton.POWER, NavButton.BACK),
            NavControls.parse(NavControls.RECOVERY_BUTTONS))
    }
}
