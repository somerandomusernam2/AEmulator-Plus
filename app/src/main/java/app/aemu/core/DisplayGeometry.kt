/* AEmulator Sunset addition, 2026-10-03. GPL-3.0. */
package app.aemu.core

/** Angles are display quarter-turns; bottom remains the device's natural bottom. */
internal object DisplayGeometry {
    enum class Edge { BOTTOM, RIGHT, TOP, LEFT }
    fun edge(turns: Int) = Edge.entries[Math.floorMod(turns, 4)]
    data class Rect(val width: Int, val height: Int, val left: Int, val top: Int)
    fun fit(width: Int, height: Int, guestWidth: Int, guestHeight: Int, controls: Int, turns: Int, overlay: Boolean): Rect {
        val amount = if (overlay) 0 else controls.coerceAtLeast(0)
        val edge = edge(turns)
        val aw = (width - if (edge == Edge.LEFT || edge == Edge.RIGHT) amount else 0).coerceAtLeast(1)
        val ah = (height - if (edge == Edge.TOP || edge == Edge.BOTTOM) amount else 0).coerceAtLeast(1)
        val scale = minOf(aw.toFloat() / guestWidth, ah.toFloat() / guestHeight)
        val w = (guestWidth * scale).toInt().coerceAtLeast(1)
        val h = (guestHeight * scale).toInt().coerceAtLeast(1)
        return Rect(w, h, (aw - w) / 2 + if (edge == Edge.LEFT) amount else 0,
            (ah - h) / 2 + if (edge == Edge.TOP) amount else 0)
    }

    /** The part of the window the guest screen may occupy: everything except the strip of controls (unless they overlay it). */
    fun available(width: Int, height: Int, controls: Int, turns: Int, overlay: Boolean): Rect {
        val amount = if (overlay) 0 else controls.coerceAtLeast(0)
        val edge = edge(turns)
        val aw = (width - if (edge == Edge.LEFT || edge == Edge.RIGHT) amount else 0).coerceAtLeast(1)
        val ah = (height - if (edge == Edge.TOP || edge == Edge.BOTTOM) amount else 0).coerceAtLeast(1)
        return Rect(aw, ah, if (edge == Edge.LEFT) amount else 0, if (edge == Edge.TOP) amount else 0)
    }

    /** A person-chosen screen rectangle as fractions of [available], so it survives rotation and a changing control strip. */
    data class Frac(val left: Float, val top: Float, val width: Float, val height: Float)
    enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    fun fractions(area: Rect, r: Rect) = Frac((r.left - area.left).toFloat() / area.width, (r.top - area.top).toFloat() / area.height,
        r.width.toFloat() / area.width, r.height.toFloat() / area.height)

    /** [f] in pixels, never smaller than the minimum and always completely inside [area]. */
    fun place(area: Rect, f: Frac, minW: Int, minH: Int): Rect {
        val mw = minW.coerceIn(1, area.width); val mh = minH.coerceIn(1, area.height)
        val w = Math.round(f.width * area.width).coerceIn(mw, area.width)
        val h = Math.round(f.height * area.height).coerceIn(mh, area.height)
        val l = (area.left + Math.round(f.left * area.width)).coerceIn(area.left, area.left + area.width - w)
        val t = (area.top + Math.round(f.top * area.height)).coerceIn(area.top, area.top + area.height - h)
        return Rect(w, h, l, t)
    }

    /** Dragging [corner] to (x, y): the opposite corner stays put, the result stays inside [area] and above the minimum size. */
    fun dragCorner(area: Rect, cur: Rect, corner: Corner, x: Int, y: Int, minW: Int, minH: Int): Rect {
        val mw = minW.coerceIn(1, area.width); val mh = minH.coerceIn(1, area.height)
        val aRight = area.left + area.width; val aBottom = area.top + area.height
        var l = cur.left; var t = cur.top; var r = cur.left + cur.width; var b = cur.top + cur.height
        when (corner) {
            Corner.TOP_LEFT, Corner.BOTTOM_LEFT -> l = x.coerceIn(area.left, r - mw)
            else -> r = x.coerceIn(l + mw, aRight)
        }
        when (corner) {
            Corner.TOP_LEFT, Corner.TOP_RIGHT -> t = y.coerceIn(area.top, b - mh)
            else -> b = y.coerceIn(t + mh, aBottom)
        }
        return Rect(r - l, b - t, l, t)
    }

    fun natural(width: Int, height: Int, turns: Int): Pair<Int, Int> = if (turns % 2 == 0) width to height else height to width
}
