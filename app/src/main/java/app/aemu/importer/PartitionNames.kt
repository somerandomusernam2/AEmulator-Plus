package app.aemu.importer

/**
 * File names under which a firmware package carries the three partitions the importer consumes.
 * Motorola fastboot-XML packages (e.g. SPYDER / RAZR) name them `system_signed`, `boot_signed` and
 * `recovery_signed` with no extension at all; HTC S-ON ones use `boot_signed.img` / `recovery_signed.img`.
 * All of these are ordinary partition images (the signed ones only differ by a signature in front of or behind
 * the real data, which BootImage / MotoImage / the ext reader already cope with).
 * Per-partition dumps of A/B devices (Android Things on MT8516: `boot_a.bin`, `system_a.bin`, `oem_a.bin`,
 * `vendor_a.bin`) are accepted for the active slot "_a" only; the "_b" copies are the inactive slot and are ignored.
 */
internal object PartitionNames {
    private val SYSTEM = Regex("(?i)system(\\.ext4)?\\.img(\\.ext4)?|system_image\\.img|system\\.raw\\.img|system\\.rfs|factoryfs(\\.img|\\.rfs)?|system_signed(\\.img)?|system_a\\.(img|bin)")
    private val BOOT = Regex("(?i)boot(\\.img|_signed(\\.img)?)|boot_a\\.(img|bin)")
    private val OEM = Regex("(?i)oem(\\.ext4)?\\.img(\\.ext4)?|oem_signed(\\.img)?|oem\\.rfs|oem_a\\.(img|bin)")
    private val VENDOR = Regex("(?i)vendor(\\.ext4)?\\.img|vendor_a\\.(img|bin)")
    private val RECOVERY = Regex("(?i)recovery(\\.img|_signed(\\.img)?)(\\.lz4)?|recovery\\.(emmc|mmc)\\.win")

    /** Image names that always mean the system partition (RFS dumps call it factoryfs). */
    fun isSystem(base: String) = SYSTEM.matches(base)
    /** The optional /oem partition (never boot-critical). A bare "oem" is a folder or a fastboot command, not an image. */
    fun isOem(base: String) = OEM.matches(base)
    /** The optional /vendor partition (A/B and Treble devices). Only a real image name counts, never a bare "vendor" folder. */
    fun isVendor(base: String) = VENDOR.matches(base)
    fun isBoot(base: String) = BOOT.matches(base)
    /** recovery image (optionally lz4-framed, optionally signed) or a TWRP raw recovery.*.win backup. */
    fun isRecovery(base: String) = RECOVERY.matches(base)

    /**
     * Older Samsung Odin packages (e.g. GT-S5303 .tar.md5) name every tar member "<image>.md5" - "system.img.md5",
     * "boot.img.md5", "recovery.img.md5" - with the payload being the plain image. Strip that suffix so the
     * partition matchers above see the real name. Nested "*.tar.md5" archives keep their suffix.
     */
    fun stripOdinMd5(base: String): String =
        if (base.endsWith(".md5", true) && !base.endsWith(".tar.md5", true) && base.length > 4) base.dropLast(4) else base

    /**
     * A zip of a raw /system partition (the contents of system.img, not a "system/" folder inside it):
     * build.prop and a bin/ directory sit at the zip root. Everything in it is then placed under system/.
     */
    fun isRawSystemDump(names: Collection<String>): Boolean {
        var buildProp = false
        var bin = false
        for (raw in names) {
            val n = raw.replace('\\', '/').trimStart('/')
            if (n.startsWith("__MACOSX/")) continue
            if (n == "build.prop") buildProp = true
            else if (n == "bin" || n.startsWith("bin/")) bin = true
            if (buildProp && bin) return true
        }
        return false
    }
}
