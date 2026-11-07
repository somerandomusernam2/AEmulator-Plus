/* AEmulator Sunset addition, 2026-10-02. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

internal object VmUiPolicy {
    enum class BackAction { CLOSE_LOG, OPEN_MENU, GUEST_BACK }
    fun back(logVisible: Boolean, hideMenu: Boolean) = when {
        logVisible -> BackAction.CLOSE_LOG
        hideMenu -> BackAction.OPEN_MENU
        else -> BackAction.GUEST_BACK
    }
    fun active(state: String) = state in setOf("PREPARING", "BOOTING", "RUNNING")

    /**
     * Keeps the floating menu button on screen. The button rests in the top-right corner of the usable area
     * (offset 0,0) and is dragged from there, so it may only travel left by `area - button` and down by
     * `area - button`; right and up of the resting spot is the screen edge. Returns the offset to use.
     * Sizes are in pixels; an area not measured yet (0) or smaller than the button pins it to its resting spot.
     */
    fun clampMenuOffset(x: Float, y: Float, areaWidth: Int, areaHeight: Int, buttonWidth: Int, buttonHeight: Int): Pair<Float, Float> {
        val maxLeft = (areaWidth - buttonWidth).coerceAtLeast(0).toFloat()
        val maxDown = (areaHeight - buttonHeight).coerceAtLeast(0).toFloat()
        // NaN/∞ from a damaged saved value must not survive: coerceIn would pass NaN through
        val sx = if (x.isFinite()) x else 0f
        val sy = if (y.isFinite()) y else 0f
        return sx.coerceIn(0f - maxLeft, 0f) to sy.coerceIn(0f, maxDown) // 0f - maxLeft: never -0.0f
    }
}
