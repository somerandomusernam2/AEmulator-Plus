package app.aemu.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GyroLockTest {
    @Test fun turnsMapToFixedOrientations() {
        assertNull(GyroLock.turns(GyroLock.DISABLED))
        assertEquals(0, GyroLock.turns(GyroLock.DEG_0))
        assertEquals(2, GyroLock.turns(GyroLock.DEG_180))
    }
    @Test fun unknownModesFallBackToDisabled() {
        assertEquals(GyroLock.DISABLED, GyroLock.sanitize(-1))
        assertEquals(GyroLock.DISABLED, GyroLock.sanitize(7))
        assertEquals(GyroLock.DEG_180, GyroLock.sanitize(2))
        assertEquals(GyroLock.NO_GYRO, GyroLock.sanitize(3))
        assertEquals(GyroLock.DISABLED, GyroLock.sanitize(4))
    }
    @Test fun noGyroKeepsRealOrientation() {
        assertNull(GyroLock.turns(GyroLock.NO_GYRO))
        assertEquals(true, GyroLock.removesGyro(GyroLock.NO_GYRO))
        assertEquals(false, GyroLock.removesGyro(GyroLock.DEG_0))
    }
    @Test fun lockedGravityIsUprightOrInverted() {
        assertArrayEquals(floatArrayOf(0f, 9.80665f, 0f), ManualMotion.gravity(GyroLock.turns(GyroLock.DEG_0)!!), 0f)
        assertArrayEquals(floatArrayOf(0f, -9.80665f, 0f), ManualMotion.gravity(GyroLock.turns(GyroLock.DEG_180)!!), 0f)
    }
    @Test fun settingsRoundTrip() {
        val s = VmSettings(motionSensors = true, gyroLock = GyroLock.DEG_180)
        assertEquals(GyroLock.DEG_180, VmSettings.fromJson(s.toJson()).gyroLock)
        assertEquals(GyroLock.DISABLED, VmSettings.fromJson(null).gyroLock)
    }
}
