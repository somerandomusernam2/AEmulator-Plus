package app.aemu.importer

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class PartitionNamesTest {
    @Test fun signedSystemWithAndWithoutExtension() {
        for (n in listOf("system_signed", "system_signed.img", "SYSTEM_SIGNED", "system.img", "factoryfs.img")) assertTrue(n, PartitionNames.isSystem(n))
        for (n in listOf("system", "system_signed.xml", "cdrom_signed", "system_signed.bak")) assertFalse(n, PartitionNames.isSystem(n))
    }

    @Test fun signedBootWithAndWithoutExtension() {
        for (n in listOf("boot.img", "boot_signed", "boot_signed.img", "Boot_Signed")) assertTrue(n, PartitionNames.isBoot(n))
        for (n in listOf("boot", "bootloader.img", "boot_signed.bin", "recovery_signed")) assertFalse(n, PartitionNames.isBoot(n))
    }

    @Test fun signedRecoveryWithAndWithoutExtension() {
        for (n in listOf("recovery.img", "recovery_signed", "recovery_signed.img", "recovery.img.lz4", "recovery_signed.lz4", "recovery.emmc.win"))
            assertTrue(n, PartitionNames.isRecovery(n))
        // a bare "recovery" is the /sbin/recovery binary of a ramdisk dump, not an image
        for (n in listOf("recovery", "recovery-from-boot.p", "boot_signed")) assertFalse(n, PartitionNames.isRecovery(n))
    }

    @Test fun oemImageNames() {
        for (n in listOf("oem.img", "oem.ext4.img", "oem_signed", "oem_signed.img", "OEM.IMG", "oem.rfs")) assertTrue(n, PartitionNames.isOem(n))
        // not an image: a folder / fastboot word, other partitions, other files
        for (n in listOf("oem", "oem.xml", "oemsbl.img", "system.img", "oem_config.bin")) assertFalse(n, PartitionNames.isOem(n))
    }
}
