package app.aemu.importer

import app.aemu.core.DeltaOta
import app.aemu.core.ImgPatch
import java.security.MessageDigest

/**
 * Stock OTAs and system images carry no recovery.img. They ship `recovery-from-boot.p` (an imgdiff / bsdiff patch) and an
 * `install-recovery.sh` that rebuilds the recovery partition on the device by patching the boot partition:
 *
 *     applypatch [--bonus /system/etc/recovery-resource.dat] EMMC:<boot dev>:<size>:<sha1> EMMC:<recovery dev> \
 *         <recovery sha1> <recovery size> <boot sha1>:/system/recovery-from-boot.p
 *
 * This does the same on the files: boot image + patch -> recovery image, checked against the hashes of the script.
 */
object RecoveryFromBoot {
    /** What install-recovery.sh asks applypatch to do. */
    class Plan(
        /** "EMMC:<device>:<size>:<sha1>[:<size>:<sha1>...]" (or MTD:), the partition the patch is applied to */
        val sourceSpec: String,
        val targetSha: String,
        val targetSize: Long,
        /** (sha1 of the boot image this patch is for, guest path of the patch) */
        val sources: List<Pair<String, String>>,
        val bonusPath: String?,
    )

    private val APPLY = Regex(
        "applypatch[ \\t]+(?:--bonus[ \\t]+(\\S+)[ \\t]+)?(\\S+)[ \\t]+(\\S+)[ \\t]+([0-9a-fA-F]{40})[ \\t]+(\\d+)[ \\t]+((?:[0-9a-fA-F]{40}:\\S+[ \\t]*)+)")

    /** The applypatch call of an install-recovery.sh that writes a patched image (not the `applypatch -c` check); null if none. */
    fun parse(script: String): Plan? {
        val text = script.replace("\r", "").replace("\\\n", " ")
        for (m in APPLY.findAll(text)) {
            val sources = m.groupValues[6].trim().split(Regex("[ \\t]+")).mapNotNull { s ->
                val i = s.indexOf(':')
                if (i == 40) s.substring(0, 40).lowercase() to s.substring(41) else null
            }
            val size = m.groupValues[5].toLongOrNull() ?: continue
            if (sources.isEmpty()) continue
            return Plan(m.groupValues[2], m.groupValues[4].lowercase(), size, sources, m.groupValues[1].takeIf { it.isNotEmpty() })
        }
        return null
    }

    private fun sha1(b: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun hasRecoveryBinary(img: ByteArray): Boolean =
        runCatching { BootImage.ramdisk(img)?.any { it.name.removePrefix("./") == "sbin/recovery" } == true }.getOrDefault(false)

    /**
     * Patches [boot] into the recovery image. [script] is the firmware's install-recovery.sh when there is one: it names the
     * boot image the patch fits and the hash the result must have. Without it the patch is tried as it is and the result is
     * accepted if it is an image whose ramdisk holds /sbin/recovery. [bonus] is recovery-resource.dat (`--bonus`, Android 5+).
     * Returns null, with the reason in [log], when the boot image does not fit or the result is not a recovery.
     */
    fun rebuild(boot: ByteArray, script: String?, patch: ByteArray, bonus: ByteArray?, log: (String) -> Unit): ByteArray? {
        val plan = script?.let { parse(it) }
        var source = boot
        if (plan != null) {
            // applypatch hashes the first <size> bytes of the partition: the stored boot image may be longer or shorter than that
            val fit = plan.sources.firstNotNullOfOrNull { (srcSha, _) -> DeltaOta.matchBootImage(boot, plan.sourceSpec, srcSha, plan.targetSha) }
            if (fit == null) { log("recovery: recovery-from-boot.p is for a different boot image than the one in this firmware, skipped"); return null }
            if (fit.isTarget) { log("recovery: the boot image already is the recovery image"); return fit.image }
            source = fit.image
        }
        val out = try {
            ImgPatch.apply(source, patch, if (plan?.bonusPath != null) bonus else null)
        } catch (e: OutOfMemoryError) {
            log("recovery: not enough memory to rebuild the recovery from boot"); return null
        } catch (e: Exception) {
            log("recovery: recovery-from-boot.p cannot be applied (${e.message ?: e.javaClass.simpleName}), skipped"); return null
        }
        val verified = plan != null && out.size.toLong() == plan.targetSize && sha1(out) == plan.targetSha
        if (!verified && !hasRecoveryBinary(out)) { log("recovery: the patched boot image is not a recovery, skipped"); return null }
        log("recovery: rebuilt from boot + recovery-from-boot.p, ${out.size shr 10} KB" + if (verified) ", hash verified" else
            if (plan != null) ", hash differs from install-recovery.sh (accepted: it holds /sbin/recovery)" else "")
        return out
    }

    /** Names the importer picks out of an OTA zip (anywhere in a "recovery/" folder, which the updater-script extracts to /system). */
    fun isPatchFile(base: String) = base.equals("recovery-from-boot.p", true)
    fun isScriptFile(base: String) = base.equals("install-recovery.sh", true)
    fun isBonusFile(base: String) = base.equals("recovery-resource.dat", true)
}
