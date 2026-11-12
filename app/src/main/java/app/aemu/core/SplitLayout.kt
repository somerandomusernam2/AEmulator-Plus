/* Added for AEmulator Plus, 2026-11-09: two machines on one tab (split screen). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

/** Geometry of a tab that holds two machines. Sides are 'T'op, 'B'ottom, 'L'eft and 'R'ight. */
object SplitLayout {
    const val MATCH = -1 // ViewGroup.LayoutParams.MATCH_PARENT

    fun isSide(c: Char) = c == 'T' || c == 'B' || c == 'L' || c == 'R'

    fun opposite(pos: Char): Char = when (pos) { 'T' -> 'B'; 'B' -> 'T'; 'L' -> 'R'; else -> 'L' }

    /**
     * The side of a w x h area that a point (x, y) is nearest to, like snapping a window to an edge: only when the point
     * is within the outer [edge] fraction of the area. The middle of the area is no side (null: nothing is dropped there).
     */
    fun zoneAt(x: Float, y: Float, w: Float, h: Float, edge: Float = 0.3f): Char? {
        if (w <= 0f || h <= 0f) return null
        val left = x / w
        val right = 1f - left
        val top = y / h
        val bottom = 1f - top
        val nearest = minOf(left, right, top, bottom)
        if (nearest > edge) return null
        return when (nearest) { top -> 'T'; bottom -> 'B'; left -> 'L'; else -> 'R' }
    }

    /**
     * Size in pixels of the window that holds the half of a w x h screen on side [pos] (null: the whole screen).
     * A [gap] is left between the two halves. [MATCH] means "as big as the screen" in that direction.
     */
    fun paneSize(pos: Char?, w: Int, h: Int, gap: Int): Pair<Int, Int> = when (pos) {
        'T', 'B' -> MATCH to (h / 2 - gap / 2)
        'L', 'R' -> (w / 2 - gap / 2) to MATCH
        else -> MATCH to MATCH
    }

    /** The part of a w x h area that the half on side [pos] covers, as left, top, right, bottom. */
    fun paneRect(pos: Char, w: Float, h: Float): FloatArray = when (pos) {
        'T' -> floatArrayOf(0f, 0f, w, h / 2)
        'B' -> floatArrayOf(0f, h / 2, w, h)
        'L' -> floatArrayOf(0f, 0f, w / 2, h)
        else -> floatArrayOf(w / 2, 0f, w, h)
    }
}
