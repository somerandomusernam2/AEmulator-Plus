package app.aemu.importer

import org.junit.Assert.*
import org.junit.Test

class AbLayoutTest {
    private fun f(name: String, text: String = "") = BootImage.CpioEntry(name, 0x8000 or 0x1ed, text.toByteArray())

    @Test fun recoveryOnlyRamdiskIsDetected() {
        val rd = listOf(f("init"), f("init.rc", "on init\n  start recovery\nservice recovery /sbin/recovery\n"), f("sbin/recovery"), f("sbin/healthd"))
        assertTrue(AbLayout.isRecoveryRamdisk(rd))
    }

    @Test fun normalRamdiskIsNotRecovery() {
        assertFalse(AbLayout.isRecoveryRamdisk(listOf(f("init"), f("init.rc", "service zygote /system/bin/app_process"), f("sbin/healthd"))))
        // recovery binary present, but the init.rc really boots Android
        assertFalse(AbLayout.isRecoveryRamdisk(listOf(f("init.rc", "service zygote /system/bin/app_process"), f("sbin/recovery"))))
    }

    @Test fun rootFilesTheEmulatorDoesNotUse() {
        assertTrue(AbLayout.skipRootFile("init"))
        assertTrue(AbLayout.skipRootFile("sbin/adbd"))
        assertTrue(AbLayout.skipRootFile("sbin/ueventd"))
        assertFalse(AbLayout.skipRootFile("init.rc"))
        assertFalse(AbLayout.skipRootFile("sbin/healthd"))
        assertFalse(AbLayout.skipRootFile("system/bin/init"))
    }
}
