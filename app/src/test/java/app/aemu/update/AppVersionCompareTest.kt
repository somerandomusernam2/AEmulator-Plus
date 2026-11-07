/* AEmulator Plus addition, 2026-10-04. GPL-3.0; see LICENSE. */
package app.aemu.update

import org.junit.Assert.*
import org.junit.Test

/** Plus versions are <AEmulator base>.<Plus release>, e.g. 0.0.0.2.1 (tag v0.0.0.2.1). */
class AppVersionCompareTest {
    private fun newer(latest: String, current: String) = AppUpdateManager.isNewerVersion(latest, current)

    @Test fun nextPlusReleaseIsNewer() {
        assertTrue(newer("v0.0.0.2.2", "0.0.0.2.1"))
        assertTrue(newer("v0.0.0.2.10", "0.0.0.2.9"))   // numeric, not text, comparison
    }
    @Test fun sameOrOlderIsNotNewer() {
        assertFalse(newer("v0.0.0.2.1", "0.0.0.2.1"))
        assertFalse(newer("v0.0.0.2.1", "0.0.0.2.2"))
        assertFalse(newer("v0.0.0.2", "0.0.0.2.1"))
    }
    @Test fun newBaseVersionBeatsAnyOlderPlusRelease() {
        assertTrue(newer("v0.0.0.3.1", "0.0.0.2.99"))
        assertFalse(newer("v0.0.0.2.99", "0.0.0.3.1"))
    }
}
