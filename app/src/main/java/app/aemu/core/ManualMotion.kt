/* AEmulator Sunset addition, 2026-10-03. GPL-3.0. */
package app.aemu.core

/** Stationary gravity in natural-device axes; guest Android decides whether to rotate. */
internal object ManualMotion {
    fun gravity(turns: Int): FloatArray = when (Math.floorMod(turns, 4)) {
        0 -> floatArrayOf(0f, 9.80665f, 0f)
        1 -> floatArrayOf(9.80665f, 0f, 0f)
        2 -> floatArrayOf(0f, -9.80665f, 0f)
        else -> floatArrayOf(-9.80665f, 0f, 0f)
    }
}

/** Fixed-orientation mode for host motion sensors: gravity is pinned to 0° or 180° and the gyroscope reads zero. */
object GyroLock {
    const val DISABLED = 0
    const val DEG_0 = 1
    const val DEG_180 = 2
    /** Gyroscope hidden from the guest entirely (for buggy host gyros); accelerometer and magnetometer stay real. */
    const val NO_GYRO = 3
    fun sanitize(mode: Int): Int = if (mode in DISABLED..NO_GYRO) mode else DISABLED
    fun removesGyro(mode: Int): Boolean = mode == NO_GYRO
    /** Quarter-turns for [ManualMotion.gravity], or null when the lock is off. */
    fun turns(mode: Int): Int? = when (mode) { DEG_0 -> 0; DEG_180 -> 2; else -> null }
}
