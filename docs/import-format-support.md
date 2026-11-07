# Firmware import format coverage

AEmulator Plus imports firmware into the guest filesystem; it is not a general-purpose file manager. The importer now handles these archive/container paths:

- ZIP and nested ZIP firmware bundles.
- 7z, including sequential/solid archives. Entries are read through `SevenZFile.read()` while the current entry is active, then nested images/archives are processed after the archive pass.
- RAR bundles through Junrar, with path traversal checks and selective extraction of firmware-relevant entries.
- TAR, TAR.MD5, TWRP `.win`, gzip, xz, and bzip2 streams.
- Android OTA `system.new.dat` / `system.new.dat.br` plus transfer lists, Android sparse images and Motorola sparse chunks.
- ext2/3/4 images, YAFFS2 images, rootfs trees, boot/recovery images, and CPIO newc/crc/odc archives.
- ISO 9660 CD images of Android-x86 and its descendants (`android-x86-*.iso`; see below).
- Stock recovery without a recovery image: OTAs and system images that ship `recovery-from-boot.p` (+ `install-recovery.sh`, and `recovery-resource.dat` on Android 5+) get their recovery rebuilt from the firmware's boot image, checked against the hash in the script.

## Android-x86 ISO

An Android-x86 live CD is an ISO 9660 image whose useful content is two files (`importer/Iso9660.kt` reads the image in place, nothing is copied):

| In the ISO | Used as |
| --- | --- |
| `ramdisk.img` (gzip'ed cpio: `init`, `init*.rc`, `sbin/`, `fstab.android_x86` ...) | the VM's ramdisk, like the ramdisk of a `boot.img` |
| `system.sfs` (a SquashFS holding one file, `system.img`, an ext4 image) | `system.img` is cut out of the SquashFS and imported as the system partition |
| `system.img` (a few builds ship it plain instead of `system.sfs`) | imported directly (sparse images are accepted) |
| `kernel`, `initrd.img`, `install.img`, `isolinux/`, `efi/` | not used: the guest's programs run under the translator, not on the bzImage |

Files in a sub-folder of the CD are found too; the copy nearest to the root wins. File names come from Rock Ridge when the
image has it, otherwise from the ISO 9660 identifier (`;1` and a trailing dot removed, case ignored when matching).

Disk use during the import: the SquashFS is copied to the cache (about the size of `system.sfs`), `system.img` is written next to it,
the SquashFS is deleted at once, and `system.img` is deleted when its files are in the guest tree. For `android-x86-5.1-rc1` that
is roughly 0.4 GB + 0.9 GB at the peak, and 0.9 GB left afterwards.

Not covered: `system.efs` (the EROFS variant of newer releases; the ISO is rejected with "no Android system found" when it is the
only system file), x86-64 builds (reported as "not ARM" by the analyzer, see x86-guest.md), and no kernel banner: the `kernel` of the ISO is a
bzImage, not stored as `boot.img`, so `/proc/version` keeps its default text.

Several scripts in the supplied companion archive are standalone desktop tools for vendor-specific or encrypted formats (for example PAC, SBF, NBH, LG BIN, TOT, OFP, and OPS). They depend on Python, external utilities, device/vendor-specific parsing, or decryption keys and have **not** been ported into the Android importer by this change. They are not silently treated as supported. SquashFS and RFS images are converted by the Android importer (see firmware-tools-port.md).

The app build could not be run in this environment because Gradle's distribution download was blocked by unavailable network access; validate with `bash gradlew :app:assembleDebug` in an environment with the configured Gradle distribution and Maven dependencies available.
