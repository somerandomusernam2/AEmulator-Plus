package app.aemu.core

/**
 * Added for AEmulator Plus (2026-11-09). How eagerly the host-side low memory killer ([GuestLmk]) stops cached guest
 * processes. It is independent of the "Low memory mode" switch (`ro.config.low_ram`), which stays as it was: that switch
 * only moves the thresholds below to their low-RAM column.
 *
 * Why it exists: an Android TV image with Google Play services keeps 11-15 cached processes. With the single fixed rule
 * the killer stopped one of them every ~5 s although the phone had 2.5-2.9 GB free; the activity manager started them
 * again, providers vanished under their clients and system_server spent its time on restarts.
 *
 * [MEDIUM] is exactly the rule that was hard-coded before, so an existing VM behaves as it did until the mode is changed.
 *
 * The values (MB are the phone's MemAvailable, "cached" are guest processes with oom_adj >= [GuestLmk.CACHED]):
 *  - [minHostMb] / [minHostLowRamMb]: below this free memory the killer may act at all.
 *  - [tightFactor]: between minHost and minHost * tightFactor it acts only when the guest holds more than the allowed
 *    number of cached processes, or its processes use more than their RAM budget. 1.0 = no such window: the killer acts
 *    only when the phone itself runs short.
 *  - [maxCached] / [maxCachedLowRam]: cached processes tolerated inside that window (with a RAM size chosen for the
 *    guest, ramMb / 128 within 2..[maxCached] is used instead).
 *  - [keepCached]: this many cached processes are spared unless free memory is below [hardHostMb].
 *  - [budgetFactor]: the guest is over its budget when its processes' RSS (shared zygote pages counted in each, so an
 *    upper bound) is over chosen RAM * this.
 *  - [intervalMs] / [pauseMs]: how often the killer looks, and how long it waits after a kill.
 */
enum class MemoryMode(
    val id: String,
    val minHostMb: Long,
    val minHostLowRamMb: Long,
    val tightFactor: Double,
    val maxCached: Int,
    val maxCachedLowRam: Int,
    val keepCached: Int,
    val hardHostMb: Long,
    val budgetFactor: Double,
    val intervalMs: Long,
    val pauseMs: Long,
) {
    /** Only when the phone is nearly out of memory; a guest with many cached processes is left alone. */
    LIGHT("light", 800, 1500, 1.0, 16, 8, 8, 250, 3.0, 4000, 8000),

    /** The rule used before this setting existed. */
    MEDIUM("medium", 1500, 2500, 2.0, 10, 4, 3, 400, 2.0, 2000, 3000),

    /** Keeps few cached processes and looks often; frees memory early for a phone that has little to spare. */
    AGGRESSIVE("aggressive", 3000, 4500, 2.0, 6, 3, 2, 800, 1.5, 1000, 1500);

    /**
     * Whether one cached process should be killed now.
     * [avail] is the phone's MemAvailable in MB, [cachedCount] the cached guest processes, [ramMb] the RAM chosen for
     * the guest (0 = not set) and [rssSumMb] the summed RSS of the guest's processes (only read when [ramMb] > 0).
     */
    fun shouldKill(lowRam: Boolean, ramMb: Int, avail: Long, cachedCount: Int, rssSumMb: Long): Boolean {
        val minHost = if (lowRam) minHostLowRamMb else minHostMb
        val cachedLimit = if (ramMb > 0) (ramMb / 128).coerceIn(2, maxCached) else if (lowRam) maxCachedLowRam else maxCached
        val overBudget = ramMb > 0 && rssSumMb > ramMb * budgetFactor
        val tight = avail < minHost * tightFactor
        // With plenty free, killing a cached service only makes the activity manager start it again.
        if (avail >= minHost && !(tight && (cachedCount > cachedLimit || overBudget))) return false
        // Soft pressure with only a few cached processes: those are spared; only a real shortage justifies a kill.
        if (cachedCount <= keepCached && avail >= hardHostMb && !overBudget) return false
        return true
    }

    companion object {
        val DEFAULT = MEDIUM

        /** The mode saved under [id]; anything unknown is [DEFAULT]. */
        fun parse(id: String?): MemoryMode = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DEFAULT
    }
}
