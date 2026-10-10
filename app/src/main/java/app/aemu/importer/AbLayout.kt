/* A/B + system-as-root support (Android 7.0 and newer, e.g. Android Things). GPL-3.0; see LICENSE. */
package app.aemu.importer

/**
 * Android 7+ A/B devices with `ro.build.system_root_image=true` (Android Things, Pixel 1, ...):
 *  - the system partition is the root file system: init, init*.rc, sepolicy, default.prop and sbin/ sit in its top level,
 *    the real /system is a folder inside it;
 *  - boot holds the kernel and the RECOVERY ramdisk only (there is no recovery partition).
 */
internal object AbLayout {
    /** Top level has `init` and a `system/` folder with build.prop or framework/: the partition is the root file system. */
    fun isSystemAsRoot(fs: Ext4Reader): Boolean = runCatching {
        fs.lookup("init")?.isFile == true &&
            fs.lookup("system")?.isDir == true &&
            (fs.lookup("system/build.prop") != null || fs.lookup("system/framework") != null)
    }.getOrDefault(false)

    /** A ramdisk with sbin/recovery whose init.rc starts no zygote: it can only boot recovery, never Android. */
    fun isRecoveryRamdisk(rd: List<BootImage.CpioEntry>): Boolean {
        if (rd.none { it.name == "sbin/recovery" }) return false
        val rc = rd.firstOrNull { it.name == "init.rc" } ?: return true
        return !String(rc.data, Charsets.ISO_8859_1).contains("zygote")
    }

    /**
     * Files of a system-as-root top level that the ramdisk import path (Importer.finishTree) leaves out as well:
     * the emulator starts the services itself, so init, adbd and ueventd are not used.
     */
    fun skipRootFile(path: String): Boolean = path == "init" || path == "sbin/adbd" || path.startsWith("sbin/ueventd")
}
