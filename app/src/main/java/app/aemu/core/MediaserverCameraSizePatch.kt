/* Modified for AEmulator Plus, 2026-10-30. GPL-3.0. */
package app.aemu.core

import java.security.MessageDigest

/**
 * Samsung SC-02C (TouchWiz, Android 4.0.3, build IML74K): /system/bin/mediaserver dies at start with SIGBUS in
 * RefBase::incStrong (guest log: `ldrex` on 0xb7, called from Parcel::writeObject <- Parcel::writeStrongBinder <-
 * BpServiceManager::addService), right after "CameraService started" and before media.camera is registered.
 *
 * Cause: mediaserver allocates the service with `operator new(0x58)`, but the CameraService in this firmware's
 * libcameraservice.so is 0x60 bytes: its vtable puts the virtual base RefBase at offset 0x58 (vbase offset 88 in
 * _ZTVN7android13CameraServiceE, RefBase is 8 bytes). The constructor therefore writes RefBase (vptr at 0x58, mRefs at
 * 0x5c) past the end of the 88-byte block, and the allocator's next chunk header ends up where mRefs lives: mRefs reads
 * back 0xb3 (a chunk size with its flag bits), incStrong adds 4 to it and the unaligned `ldrex` faults. The other two
 * services mediaserver builds match their libraries exactly (AudioFlinger 0x144, AudioPolicyService 0x58), so this is
 * the only size that is off. The two files simply disagree; a real device survives because of how its heap happens to be
 * laid out, qemu-user's guest heap does not.
 *
 * The fix is one instruction in mediaserver, Thumb `movs r0, #0x58` (the argument of that operator new) -> `movs r0, #0x60`,
 * at file offset 0xc2e (virtual address 0x8c2e).
 *
 * Tied to this one build on purpose: nothing is written unless the SHA-256 of mediaserver (with the two bytes at the patch
 * site put back to their stock value, so an already patched file is still recognised) AND the SHA-256 of libcameraservice.so
 * are exactly those of the firmware above. Any other build, or a differently modified file, is left alone.
 */
internal object MediaserverCameraSizePatch {
    const val PATCHED = 1
    const val ALREADY = 0
    /** Not this build (a hash differs) or the patch site does not hold what it must. Nothing was changed. */
    const val ABSENT = -1

    const val OFFSET = 0xc2e        // file offset == virtual address 0x8c2e - 0x8000 (first PT_LOAD maps file offset 0)
    const val STOCK = 0x58          // movs r0, #0x58 = 58 20
    const val FIXED = 0x60          // movs r0, #0x60 = 60 20
    private const val MOVS_R0_HIGH = 0x20

    /** SHA-256 of the stock /system/bin/mediaserver of Samsung SC-02C IML74K (5572 bytes) */
    const val MEDIASERVER_SHA256 = "d0bd50a2254fe32ea10814992fe0b6d03862d71db796c552a6405364ebb42bca"
    /** SHA-256 of that firmware's /system/lib/libcameraservice.so (59604 bytes) */
    const val CAMERASERVICE_SHA256 = "f614effdc091118a8f6791a9f853dbd6395c73574acc3cbcec11f4ba06b99e54"

    /**
     * Patches [mediaserver] in place when it and [cameraLib] are the pinned build. [mediaserverSha] and [cameraLibSha] are
     * parameters for the tests only.
     */
    fun apply(
        mediaserver: ByteArray,
        cameraLib: ByteArray,
        mediaserverSha: String = MEDIASERVER_SHA256,
        cameraLibSha: String = CAMERASERVICE_SHA256,
    ): Int {
        if (mediaserver.size <= OFFSET + 1) return ABSENT
        if (!sha256(cameraLib).equals(cameraLibSha, ignoreCase = true)) return ABSENT
        val cur = mediaserver[OFFSET].toInt() and 0xff
        if ((mediaserver[OFFSET + 1].toInt() and 0xff) != MOVS_R0_HIGH || (cur != STOCK && cur != FIXED)) return ABSENT
        val stock = mediaserver.copyOf().also { it[OFFSET] = STOCK.toByte() }
        if (!sha256(stock).equals(mediaserverSha, ignoreCase = true)) return ABSENT
        if (cur == FIXED) return ALREADY
        mediaserver[OFFSET] = FIXED.toByte()
        return PATCHED
    }

    private fun sha256(d: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(d).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
