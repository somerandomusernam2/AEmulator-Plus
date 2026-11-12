/* Added for AEmulator Plus, 2026-11-09: dragging a tab onto the screen to put two machines on one tab. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import android.content.ClipData
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.view.DragEvent
import android.view.View
import app.aemu.core.SplitLayout

/** What is dragged: the machine of a tab. */
internal class DragTab(val id: String) {
    companion object {
        const val LABEL = "aemu-tab"

        /** Starts dragging the tab of machine [id]; the shadow is a small chip with its name. */
        fun start(view: View, id: String, name: String, background: Int, foreground: Int) {
            val d = view.resources.displayMetrics.density
            val w = (150 * d).toInt()
            val h = (40 * d).toInt()
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
            val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = foreground; textSize = 14 * d; textAlign = Paint.Align.CENTER }
            val shadow = object : View.DragShadowBuilder(view) {
                override fun onProvideShadowMetrics(size: Point, touch: Point) { size.set(w, h); touch.set(w / 2, h / 2) }
                override fun onDrawShadow(canvas: Canvas) {
                    canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 12 * d, 12 * d, bg)
                    canvas.drawText(name, w / 2f, h / 2f - (fg.descent() + fg.ascent()) / 2f, fg)
                }
            }
            view.startDragAndDrop(ClipData.newPlainText(LABEL, id), shadow, DragTab(id), View.DRAG_FLAG_OPAQUE)
        }
    }
}

/**
 * Covers the machine's window while a tab is dragged. Like snapping a window to an edge: near the top, bottom, left or
 * right edge a preview shows the half the dragged machine will take, and dropping there splits the screen. It does not
 * take touches when nothing is being dragged.
 */
internal class SplitDropView(
    context: Context,
    private val onSplit: (id: String, pos: Char) -> Unit,
) : View(context) {
    /** off while this window is already half of a split */
    var accepting = true

    private var dragging = false
    private var zone: Char? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x553D8BFF }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC8CB8FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3 * resources.displayMetrics.density
    }

    init { isClickable = false; isFocusable = false }

    override fun onDragEvent(e: DragEvent): Boolean = when (e.action) {
        DragEvent.ACTION_DRAG_STARTED -> {
            dragging = accepting && e.localState is DragTab
            zone = null
            invalidate()
            dragging
        }
        DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> { setZone(SplitLayout.zoneAt(e.x, e.y, width.toFloat(), height.toFloat())); true }
        DragEvent.ACTION_DRAG_EXITED -> { setZone(null); true }
        DragEvent.ACTION_DROP -> {
            val id = (e.localState as? DragTab)?.id
            val z = SplitLayout.zoneAt(e.x, e.y, width.toFloat(), height.toFloat())
            zone = null
            if (id != null && z != null) { onSplit(id, z); true } else false
        }
        DragEvent.ACTION_DRAG_ENDED -> { dragging = false; zone = null; invalidate(); true }
        else -> false
    }

    private fun setZone(z: Char?) { if (z != zone) { zone = z; invalidate() } }

    override fun onDraw(canvas: Canvas) {
        val z = zone ?: return
        if (!dragging) return
        val r = SplitLayout.paneRect(z, width.toFloat(), height.toFloat())
        val rect = RectF(r[0], r[1], r[2], r[3]).apply { inset(4f, 4f) }
        canvas.drawRoundRect(rect, 16f, 16f, fill)
        canvas.drawRoundRect(rect, 16f, 16f, stroke)
    }
}
