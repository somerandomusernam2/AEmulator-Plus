/* AEmulator Plus addition, 2026-11-08. GPL-3.0. */
package app.aemu.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import app.aemu.core.DisplayGeometry
import app.aemu.core.DisplayGeometry.Corner
import app.aemu.core.DisplayGeometry.Rect

/**
 * Resize mode for the guest screen: an outline with a grip in each corner, dragged like the corner of a window.
 * The overlay covers the same area as the guest box's parent and only takes touches that start on a grip, so while it
 * is shown everything else still reaches the guest. All coordinates are in that parent's space.
 */
@SuppressLint("ViewConstructor")
internal class ScreenResizeOverlay(context: Context) : View(context) {
    /** where the guest screen is now, and the area it may never leave */
    var screen = Rect(1, 1, 0, 0)
    var area = Rect(1, 1, 0, 0)
    var minWidth = 1
    var minHeight = 1
    /** called while dragging with the new rectangle, and once with [finished] = true when the finger lifts */
    var onResize: (Rect, Boolean) -> Unit = { _, _ -> }

    private val dp = resources.displayMetrics.density
    private val hit = 44 * dp
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = 0xffffffff.toInt() }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * dp; color = 0x99000000.toInt() }
    private val grip = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5 * dp; strokeCap = Paint.Cap.ROUND; color = 0xffffffff.toInt() }
    private val gripShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8 * dp; strokeCap = Paint.Cap.ROUND; color = 0x99000000.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffffffff.toInt(); textSize = 13 * dp; textAlign = Paint.Align.CENTER; setShadowLayer(3 * dp, 0f, 0f, 0xff000000.toInt()) }

    private var dragging: Corner? = null
    private var pointer = -1
    private var grabDx = 0f
    private var grabDy = 0f

    private fun cornerPoint(c: Corner): Pair<Float, Float> = when (c) {
        Corner.TOP_LEFT -> screen.left.toFloat() to screen.top.toFloat()
        Corner.TOP_RIGHT -> (screen.left + screen.width).toFloat() to screen.top.toFloat()
        Corner.BOTTOM_LEFT -> screen.left.toFloat() to (screen.top + screen.height).toFloat()
        Corner.BOTTOM_RIGHT -> (screen.left + screen.width).toFloat() to (screen.top + screen.height).toFloat()
    }

    private fun cornerAt(x: Float, y: Float): Corner? =
        Corner.entries.map { it to cornerPoint(it) }
            .map { (c, p) -> c to Math.hypot((x - p.first).toDouble(), (y - p.second).toDouble()) }
            .filter { it.second <= hit }.minByOrNull { it.second }?.first

    override fun onDraw(c: Canvas) {
        val l = screen.left.toFloat(); val t = screen.top.toFloat()
        val r = (screen.left + screen.width).toFloat(); val b = (screen.top + screen.height).toFloat()
        // drawn just inside the screen so the outline is never clipped at the edge of the window
        val i = 1 * dp
        c.drawRect(l + i, t + i, r - i, b - i, shadow)
        c.drawRect(l + i, t + i, r - i, b - i, outline)
        val g = 26 * dp
        val p = 6 * dp
        for (corner in Corner.entries) {
            val (cx, cy) = cornerPoint(corner)
            val sx = if (corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT) 1f else -1f
            val sy = if (corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT) 1f else -1f
            val x0 = cx + sx * p; val y0 = cy + sy * p
            for (paint in listOf(gripShadow, grip)) {
                c.drawLine(x0, y0, x0 + sx * g, y0, paint)
                c.drawLine(x0, y0, x0, y0 + sy * g, paint)
            }
        }
        c.drawText("${screen.width} × ${screen.height}", (l + r) / 2, (t + b) / 2 + label.textSize / 3, label)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val corner = cornerAt(e.x, e.y) ?: return false
                dragging = corner
                pointer = e.getPointerId(0)
                val p = cornerPoint(corner)
                grabDx = p.first - e.x; grabDy = p.second - e.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val corner = dragging ?: return false
                val i = e.findPointerIndex(pointer)
                if (i < 0) return true
                val next = DisplayGeometry.dragCorner(area, screen, corner,
                    Math.round(e.getX(i) + grabDx), Math.round(e.getY(i) + grabDy), minWidth, minHeight)
                if (next != screen) { screen = next; invalidate(); onResize(next, false) }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging == null) return false
                dragging = null
                onResize(screen, true)
                invalidate()
                return true
            }
        }
        return dragging != null
    }
}
