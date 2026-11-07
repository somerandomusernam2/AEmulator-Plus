package app.aemu.update

import org.junit.Assert.*
import org.junit.Test

/** Modified for AEmulator Aster (2026-11-07): the release APK of package app.aemu, never another variant's. */
class UpdateAssetPolicyTest {
    private val aster = "AEmulator-Aster-0.0.0.3.9-aster-app.aemu.apk"

    @Test fun choosesTheApkRegardlessOfAssetOrder() {
        assertEquals(aster, listOf(aster, "notes.txt").firstOrNull { UpdateAssetPolicy.matches(it, "app.aemu") })
        assertEquals(aster, listOf("notes.txt", aster).firstOrNull { UpdateAssetPolicy.matches(it, "app.aemu") })
    }
    @Test fun neverMatchesCloneOrPlusApks() {
        assertFalse(UpdateAssetPolicy.matches("AEmulator-Sunset-0.0.0.3-sunset.30-app.aemu.clone.apk", "app.aemu"))
        assertFalse(UpdateAssetPolicy.matches("AEmulator-Plus-0.0.0.3.9-app.aemu.plus.apk", "app.aemu"))
    }
    @Test fun otherPackagesNeverMatch() {
        assertFalse(UpdateAssetPolicy.matches(aster, "app.aemu.plus"))
        assertFalse(UpdateAssetPolicy.matches(aster, "app.aemu.clone"))
        assertFalse(UpdateAssetPolicy.matches(aster, "other.app"))
    }
    @Test fun matchingIsCaseInsensitiveButRequiresExactPackageSuffix() {
        assertTrue(UpdateAssetPolicy.matches(aster.uppercase(), "app.aemu"))
        assertFalse(UpdateAssetPolicy.matches("$aster.zip", "app.aemu"))
        assertFalse(UpdateAssetPolicy.matches("$aster.asc", "app.aemu"))
        assertFalse(UpdateAssetPolicy.matches("prefix-app.aemu.fake.apk", "app.aemu"))
    }
}
