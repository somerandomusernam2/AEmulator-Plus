package app.aemu.importer

/**
 * File names under which a firmware package carries the three partitions the importer consumes.
 * Motorola fastboot-XML packages (e.g. SPYDER / RAZR) name them `system_signed`, `boot_signed` and
 * `recovery_signed` with no extension at all; HTC S-ON ones use `boot_signed.img` / `recovery_signed.img`.
 * All of these are ordinary partition images (the signed ones only differ by a signature in front of or behind
 * the real data, which BootImage / MotoImage / the ext reader already cope with).
 */
internal object PartitionNames {
    private val SYSTEM = Regex("(?i)system(\\.ext4)?\\.img(\\.ext4)?|system_image\\.img|system\\.raw\\.img|system\\.rfs|factoryfs(\\.img|\\.rfs)?|system_signed(\\.img)?")
    private val BOOT = Regex("(?i)boot(\\.img|_signed(\\.img)?)")
    private val OEM = Regex("(?i)oem(\\.ext4)?\\.img(\\.ext4)?|oem_signed(\\.img)?|oem\\.rfs")
    private val RECOVERY = Regex("(?i)recovery(\\.img|_signed(\\.img)?)(\\.lz4)?|recovery\\.(emmc|mmc)\\.win")

    /** Image names that always mean the system partition (RFS dumps call it factoryfs). */
    fun isSystem(base: String) = SYSTEM.matches(base)
    /** The optional /oem partition (never boot-critical). A bare "oem" is a folder or a fastboot command, not an image. */
    fun isOem(base: String) = OEM.matches(base)
    fun isBoot(base: String) = BOOT.matches(base)
    /** recovery image (optionally lz4-framed, optionally signed) or a TWRP raw recovery.*.win backup. */
    fun isRecovery(base: String) = RECOVERY.matches(base)
}
