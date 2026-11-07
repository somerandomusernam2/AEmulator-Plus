package app.aemu.update

import org.junit.Assert.*
import org.junit.Test

class UpdateAssetPolicyTest {
    private val plus = "AEmulator-Plus-0.0.0.2.1-app.aemu.plus.apk"

    @Test fun choosesPlusApkRegardlessOfAssetOrder() {
        assertEquals(plus, listOf(plus, "notes.txt").firstOrNull { UpdateAssetPolicy.matches(it, "app.aemu.plus") })
        assertEquals(plus, listOf("notes.txt", plus).firstOrNull { UpdateAssetPolicy.matches(it, "app.aemu.plus") })
    }
    @Test fun neverMatchesOldSunsetOrCloneApks() {
        assertFalse(UpdateAssetPolicy.matches("AEmulator-Sunset-0.0.0.3-sunset.30-app.aemu.apk", "app.aemu.plus"))
        assertFalse(UpdateAssetPolicy.matches("AEmulator-Sunset-0.0.0.3-sunset.30-app.aemu.clone.apk", "app.aemu.plus"))
    }
    @Test fun otherPackagesNeverMatch() {
        assertFalse(UpdateAssetPolicy.matches(plus, "app.aemu"))
        assertFalse(UpdateAssetPolicy.matches(plus, "app.aemu.clone"))
        assertFalse(UpdateAssetPolicy.matches(plus, "other.app"))
    }
    @Test fun matchingIsCaseInsensitiveButRequiresExactPackageSuffix() {
        assertTrue(UpdateAssetPolicy.matches(plus.uppercase(), "app.aemu.plus"))
        assertFalse(UpdateAssetPolicy.matches("$plus.zip", "app.aemu.plus"))
        assertFalse(UpdateAssetPolicy.matches("$plus.asc", "app.aemu.plus"))
        assertFalse(UpdateAssetPolicy.matches("prefix-app.aemu.plus.fake.apk", "app.aemu.plus"))
    }
}
