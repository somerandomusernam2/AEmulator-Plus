package app.aemu.core

import java.io.File

/**
 * Added for AEmulator Plus (2026-11-03). The engine's 32-bit audio HAL host finds the HAL implementation by its bare name.
 *
 * The host (engines/n64-<api>/arm32, native/halhost/aemu-hal-host.c) does what the AOSP service main() does, but it
 * dlopen()s the implementation libraries by BARE name:
 *
 *   android.hardware.audio@2.0-impl.so            (host installed as ...audio@2.0-service)
 *   android.hardware.audio@2.0-impl-mediatek.so   (host installed as ...audio@2.0-service-mediatek)
 *   android.hardware.audio.effect@2.0-impl.so
 *   android.hardware.soundtrigger@2.0-impl.so
 *
 * A vendor program's default linker namespace searches /vendor/lib (and /odm/lib) and nothing below them, while a board
 * keeps its HIDL implementations in /vendor/lib/hw (the stock service loads them by that full path). So on a board
 * whose implementation sits in /vendor/lib/hw the host died in its first dlopen:
 *
 *   aemu-audio-host: dlopen android.hardware.audio@2.0-impl.so: dlopen failed: library "android.hardware.audio@2.0-impl.so" not found
 *   aemu-audio-host: required HAL 0 failed to load, giving up              (exit code 1, restarted 12 times)
 *   audioserver: Waited one second for android.hardware.audio@2.0::IDevicesFactory/default. Waiting another...
 *   AudioService: Audioserver died.                                          (media.audio_policy never registers)
 *
 * Two things were wrong on a MediaTek 8.x board (gobo / MT6737M, API 27):
 *
 *  1. the implementation was not reachable by its bare name (this object copies it into /vendor/lib);
 *  2. the board's rc (android.hardware.audio@2.0-service-mediatek.rc) and the engine's rc (...-service.rc) both define
 *     "service audio-hal-2-0", and the later definition wins (InitPlan.parse: services[name] = ...). Files are read in name
 *     order and "-mediatek.rc" sorts before ".rc", so the engine's PLAIN host (which looks for the non-MediaTek name the
 *     board does not have) overrode the host that looks for the MediaTek name the board does have ([preferredHostRc]).
 *
 * No Android classes here, so it can be tested on a plain JVM.
 */
object AudioImplLibs {
    const val IMPL_PLAIN = "android.hardware.audio@2.0-impl.so"
    const val IMPL_MEDIATEK = "android.hardware.audio@2.0-impl-mediatek.so"

    /** the engine's plain host rc and the one of the board's MediaTek service: both define "service audio-hal-2-0" */
    const val RC_PLAIN = "android.hardware.audio@2.0-service.rc"
    const val RC_MEDIATEK = "android.hardware.audio@2.0-service-mediatek.rc"

    /** every implementation library the engine's host dlopen()s by bare name */
    val HOST_IMPLS = listOf(
        IMPL_PLAIN,
        IMPL_MEDIATEK,
        "android.hardware.audio.effect@2.0-impl.so",
        "android.hardware.soundtrigger@2.0-impl.so",
    )

    /** where a vendor program's default linker namespace looks (the order is the order it looks in) */
    private val REACH = listOf("vendor/lib", "odm/lib")

    /** where a board keeps its 32-bit HIDL implementations, in the order of preference */
    private val HW_DIRS = listOf("vendor/lib/hw", "odm/lib/hw", "system/vendor/lib/hw", "system/vendor/lib")

    class Result(
        /** implementation libraries copied into vendor/lib, as the path they were copied from */
        val copied: List<String>,
        /** implementation libraries the host needs that were already in a directory the namespace searches */
        val alreadyReachable: List<String>,
        /** implementation libraries the host would ask for and the tree has nowhere */
        val absent: List<String>,
        /** libraries the copied (or already reachable) audio impl names and no directory of the tree has, as "name (from file)" */
        val unresolved: List<String>,
    )

    fun reachable(root: File, name: String): File? = REACH.map { File(root, "$it/$name") }.firstOrNull { it.isFile }

    private fun anywhere(root: File, name: String): File? = HW_DIRS.map { File(root, "$it/$name") }.firstOrNull { it.isFile }

    /**
     * Makes every implementation library the tree has reachable by its bare name from /vendor/lib. The firmware's file stays
     * where it is (a copy goes to vendor/lib), nothing already in /vendor/lib or /odm/lib is overwritten. Never throws.
     */
    fun expose(root: File, log: (String) -> Unit): Result {
        val copied = ArrayList<String>()
        val already = ArrayList<String>()
        val absent = ArrayList<String>()
        val unresolved = ArrayList<String>()
        for (name in HOST_IMPLS) {
            val hit = reachable(root, name)
            if (hit != null) { already += name; continue }
            val src = anywhere(root, name)
            if (src == null) { absent += name; continue }
            val dst = File(root, "vendor/lib/$name")
            val ok = runCatching {
                dst.parentFile?.mkdirs()
                if (dst.exists() || java.nio.file.Files.isSymbolicLink(dst.toPath())) dst.delete()
                src.copyTo(dst, overwrite = true)
                dst.setReadable(true, false); dst.setExecutable(true, false)
                true
            }.getOrElse { log("audio: $name could not be put into /vendor/lib: ${it.message}"); false }
            if (ok) copied += "${src.relativeTo(root).path}"
        }
        // the closure of the audio implementation that the host loads first: does everything it names exist somewhere?
        val first = reachable(root, IMPL_MEDIATEK) ?: reachable(root, IMPL_PLAIN)
        if (first != null) runCatching { unresolved += KeymasterLibs.closure(root, first).missing }
            .onFailure { log("audio: library closure of ${first.name} could not be read: ${it.message}") }
        if (copied.isNotEmpty()) log("audio: HAL implementation reachable by bare name, copied into /vendor/lib: ${copied.joinToString()} (the engine's audio host dlopen()s it without a directory)")
        if (HOST_IMPLS.take(2).all { it in absent })
            log("audio: the tree has no android.hardware.audio@2.0 implementation (neither -impl.so nor -impl-mediatek.so), the audio host cannot start")
        if (unresolved.isNotEmpty()) log("audio: ${first?.name} names libraries no directory of the tree has: ${unresolved.joinToString()}")
        return Result(copied, already, absent, unresolved)
    }

    /**
     * Which of the two rcs that define "service audio-hal-2-0" has to give way, given what the tree has.
     *
     * The host of the plain rc dlopen()s [IMPL_PLAIN], the host of the MediaTek rc [IMPL_MEDIATEK]. The plain one is what
     * wins today (it sorts last), so it only has to give way when the board has the MediaTek implementation and not the
     * plain one, and the MediaTek service and rc are there to take over.
     *
     * @return the name of the rc to set aside, or null when nothing has to move
     */
    fun rcToSetAside(
        hasPlainImpl: Boolean, hasMediatekImpl: Boolean,
        hasPlainRc: Boolean, hasMediatekRc: Boolean, hasMediatekService: Boolean,
    ): String? =
        if (hasMediatekImpl && !hasPlainImpl && hasPlainRc && hasMediatekRc && hasMediatekService) RC_PLAIN else null

    /** [rcToSetAside] for the tree: the rc files to park (the same rc can be in vendor/ and in system/vendor/), empty when none */
    fun rcsToPark(root: File): List<File> {
        val rcDirs = listOf("vendor/etc/init", "system/vendor/etc/init", "odm/etc/init")
        fun rcs(name: String) = rcDirs.map { File(root, "$it/$name") }.filter { it.isFile }
        val binDirs = listOf("vendor/bin/hw", "system/vendor/bin/hw", "odm/bin/hw")
        val mtkService = binDirs.any { File(root, "$it/android.hardware.audio@2.0-service-mediatek").isFile }
        val aside = rcToSetAside(
            hasPlainImpl = (anywhere(root, IMPL_PLAIN) ?: reachable(root, IMPL_PLAIN)) != null,
            hasMediatekImpl = (anywhere(root, IMPL_MEDIATEK) ?: reachable(root, IMPL_MEDIATEK)) != null,
            hasPlainRc = rcs(RC_PLAIN).isNotEmpty(), hasMediatekRc = rcs(RC_MEDIATEK).isNotEmpty(),
            hasMediatekService = mtkService,
        )
        return if (aside == RC_PLAIN) rcs(RC_PLAIN) else emptyList()
    }
}
