package app.aemu.core

/**
 * Samsung Eclair (2.1) on the GT-I5500 / GT-I5800 / GT-I9000 crashes in SurfaceFlinger right after the first window
 * (SetupWizard): SIGSEGV on a read at the exact base of a 480x800/320x480 RGBA buffer that was munmap()ed a moment earlier,
 * then system_server dies and zygote exits with 137.
 *
 * Cause: Eclair always installs the engine's gralloc (TreeFixer, `eclairGralloc`), and the KK one differs from the
 * Eclair/Gingerbread one in how it treats a buffer that is registered by the process that allocated it. SurfaceFlinger
 * lives inside system_server there, so that is every buffer:
 *   Eclair/GB   gralloc_register_buffer / gralloc_unregister_buffer: `if (hnd->pid != getpid()) map/unmap`, otherwise nothing
 *   KK          register: logs "Registering a buffer in the process that created it" and maps AGAIN, overwriting hnd->base;
 *               unregister: munmap(hnd->base), no pid test -> the mapping SurfaceFlinger is drawing from disappears
 *
 * engines/kk/gralloc.default_gti5500.so is the KK gralloc with exactly those two functions changed back to the
 * Eclair behaviour (same-process register/unregister are no-ops); everything else, and the behaviour for other
 * processes, is byte-identical. See tools/patch_gralloc_gti5500.py.
 *
 * Which firmware needs it is not decided by model number but by looking at the gralloc binaries themselves
 * ([GrallocProbe]): the variant is used when the gralloc the engine would install has the KK behaviour and the firmware
 * gralloc it replaces has the Eclair/GB one, i.e. the firmware's SurfaceFlinger was written for a gralloc that leaves
 * same-process buffers alone. Any other combination keeps the standard engine gralloc.
 */
object GtiGralloc {
    const val ASSET = "gralloc.default_gti5500.so"

    /**
     * [firmware] is the gralloc.default.so the engine's gralloc replaces (null: there was none), [engine] the standard
     * engine gralloc that would be installed. True only when [engine] unmaps same-process buffers and [firmware] does not;
     * an unreadable binary on either side counts as "not broken".
     */
    fun applies(firmware: ByteArray?, engine: ByteArray?): Boolean =
        firmware != null && engine != null &&
            GrallocProbe.verdict(engine) == GrallocProbe.Verdict.UNGUARDED &&
            GrallocProbe.verdict(firmware) == GrallocProbe.Verdict.GUARDED
}
