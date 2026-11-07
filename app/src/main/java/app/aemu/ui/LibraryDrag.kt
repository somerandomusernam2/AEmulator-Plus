/* Added for AEmulator Plus, 2026-11-01: press-and-hold drag to rearrange the library. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** What is being dragged and where the finger is, in the coordinates of the list. */
@Stable
internal class LibraryDragState {
    /** Key of the dragged row (a ROM id or a folder id), null when nothing is dragged. */
    var key by mutableStateOf<String?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    /** Finger position minus the item's layout offset at the moment of the grab, so the item stays under the finger. */
    var grab by mutableStateOf(Offset.Zero)
    /** A folder under the finger that the ROM will be dropped into when the finger lifts. */
    var hoverFolder by mutableStateOf<String?>(null)
}

/** A visible item of the list with its rectangle in list coordinates. */
internal class DragItem(val key: Any, val index: Int, val rect: Rect)

/** What the drag code needs from a LazyColumn or a LazyVerticalGrid. */
internal class DragHost(
    val visible: () -> List<DragItem>,
    /** Layout offset of a visible item (the same convention as before [LibraryDragState.grab] was taken). */
    val topLeft: (Any) -> Offset?,
    val scrollBy: (Float) -> Unit,
    val viewportHeight: () -> Float,
)

internal fun LazyListState.dragHost() = DragHost(
    visible = {
        val info = layoutInfo
        val pad = info.beforeContentPadding
        val w = info.viewportSize.width.toFloat()
        info.visibleItemsInfo.map {
            DragItem(it.key, it.index, Rect(0f, (it.offset + pad).toFloat(), w, (it.offset + it.size + pad).toFloat()))
        }
    },
    topLeft = { k -> layoutInfo.visibleItemsInfo.firstOrNull { it.key == k }?.let { Offset(0f, it.offset.toFloat()) } },
    scrollBy = { d -> dispatchRawDelta(d) },
    viewportHeight = { layoutInfo.viewportSize.height.toFloat() },
)

internal fun LazyGridState.dragHost() = DragHost(
    visible = {
        val info = layoutInfo
        val pad = info.beforeContentPadding
        info.visibleItemsInfo.map {
            DragItem(it.key, it.index, Rect(
                it.offset.x.toFloat(), (it.offset.y + pad).toFloat(),
                (it.offset.x + it.size.width).toFloat(), (it.offset.y + it.size.height + pad).toFloat()))
        }
    },
    topLeft = { k -> layoutInfo.visibleItemsInfo.firstOrNull { it.key == k }?.let { Offset(it.offset.x.toFloat(), it.offset.y.toFloat()) } },
    scrollBy = { d -> dispatchRawDelta(d) },
    viewportHeight = { layoutInfo.viewportSize.height.toFloat() },
)

/**
 * Rearranges the library while a row is dragged. The arrangement changes live (the other rows slide out of the way);
 * dropping on the middle of a folder header puts the ROM into that folder, dropping near the header's top edge puts it
 * in front of the folder. It is written to disk when the finger lifts.
 *
 * [noDragBottomPx]: a press-and-hold on this much of the bottom of a ROM card is left to the card (the classic card's
 * Start button starts in low-power mode on a long press).
 */
@Stable
internal class LibraryDragController(
    val drag: LibraryDragState,
    val host: DragHost,
    private val arranger: LibraryArranger,
    private val noDragBottomPx: Float = 0f,
) {
    /** The item at [p]; on a grid the one in that row that is nearest sideways. */
    private fun hit(items: List<DragItem>, p: Offset): DragItem? =
        items.filter { p.y >= it.rect.top && p.y < it.rect.bottom }.minByOrNull {
            val r = it.rect
            if (p.x < r.left) r.left - p.x else if (p.x > r.right) p.x - r.right else 0f
        }

    /**
     * The visible rows, or null when the list has not caught up with the arrangement yet (right after a move the
     * measured items still show the old order, and acting on them would move the row back and forth).
     */
    private fun freshVisible(rows: List<LibRow>): List<DragItem>? {
        val keys: Set<Any> = rows.map { it.key }.toSet()
        val vis = host.visible().filter { it.key in keys }.sortedBy { it.index }
        if (vis.isEmpty()) return vis
        val p = rows.indexOfFirst { it.key == vis[0].key }
        if (p < 0 || p + vis.size > rows.size) return null
        for (i in vis.indices) if (rows[p + i].key != vis[i].key) return null
        return vis
    }

    fun start(pos: Offset): Boolean {
        val layout = arranger.layout()
        val vis = freshVisible(arranger.rows()) ?: return false
        val h = hit(vis, pos) ?: return false
        val key = h.key as? String ?: return false
        if (noDragBottomPx > 0f && !layout.isFolder(key) && h.rect.bottom - pos.y < noDragBottomPx) return false
        val tl = host.topLeft(key) ?: return false
        drag.hoverFolder = null
        drag.grab = pos - tl
        drag.pointer = pos
        drag.key = key
        return true
    }

    fun move(pos: Offset) {
        drag.pointer = pos
        evaluate()
    }

    fun evaluate() {
        val dk = drag.key ?: return
        val layout = arranger.layout()
        val rows = arranger.rows(collapse = if (layout.isFolder(dk)) dk else null)
        val vis = freshVisible(rows) ?: return
        drag.hoverFolder = null
        val h = hit(vis, drag.pointer) ?: return
        val hk = h.key as? String ?: return
        if (hk == dk) return
        val frac = (drag.pointer.y - h.rect.top) / h.rect.height.coerceAtLeast(1f)

        if (layout.isFolder(dk)) {
            // a folder moves as one block among the top-level entries
            val target = layout.entryOf(hk) ?: return
            if (target == dk) return
            val after = layout.top.indexOf(dk) < layout.top.indexOf(target)
            arranger.update(layout.placeFolder(dk, target, after))
            return
        }
        if (layout.isFolder(hk)) {
            if (frac < 0.28f) arranger.update(layout.placeRomBeforeFolder(dk, hk))
            else if (layout.folderOf(dk) != hk) drag.hoverFolder = hk
            return
        }
        val sameBox = layout.folderOf(dk) == layout.folderOf(hk)
        val after = if (sameBox) layout.indexIn(dk) < layout.indexIn(hk) else frac > 0.5f
        arranger.update(layout.placeRom(dk, hk, after))
    }

    fun finish() {
        val dk = drag.key
        val into = drag.hoverFolder
        if (dk != null && into != null) arranger.update(arranger.layout().moveRomToFolder(dk, into))
        drag.hoverFolder = null
        drag.key = null
        if (dk != null) arranger.commit()
    }
}

/**
 * Press and hold a row, then move the finger to drag it. Watches the touches before the cards do (so the card under
 * the finger does not also take the release as a tap), but only takes over once the press has been held for the long
 * press time without moving; plain taps and scrolling are untouched.
 */
internal fun Modifier.libraryDragGesture(controller: LibraryDragController, onPickedUp: () -> Unit): Modifier =
    pointerInput(controller) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val abandoned = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var stop = false
                while (!stop) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val c = ev.changes.firstOrNull { it.id == down.id }
                    stop = c == null || c.changedToUpIgnoreConsumed() || c.isConsumed ||
                        (c.position - down.position).getDistance() > viewConfiguration.touchSlop
                }
                true
            }
            if (abandoned != null) return@awaitEachGesture
            if (!controller.start(down.position)) return@awaitEachGesture
            onPickedUp()
            try {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val c = ev.changes.firstOrNull { it.id == down.id }
                    ev.changes.forEach { it.consume() }
                    if (c == null || c.changedToUpIgnoreConsumed()) break
                    controller.move(c.position)
                }
            } finally {
                controller.finish()
            }
        }
    }

/** Scrolls the list while a row is held near its top or bottom edge. Call once next to the list. */
@Composable
internal fun LibraryDragAutoScroll(controller: LibraryDragController) {
    val active = controller.drag.key != null
    val edge = with(LocalDensity.current) { 72.dp.toPx() }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (controller.drag.key != null) {
            val h = controller.host.viewportHeight()
            val y = controller.drag.pointer.y
            val d = when {
                y < edge -> -(((edge - y) / edge).coerceAtMost(1f)) * 26f
                y > h - edge -> (((y - (h - edge)) / edge).coerceAtMost(1f)) * 26f
                else -> 0f
            }
            if (d != 0f) controller.host.scrollBy(d)
            withFrameNanos { }
            if (d != 0f) controller.evaluate()
        }
    }
}

/**
 * The look of the row being dragged: it follows the finger, rises above the others and grows a little.
 * [lockX] keeps it in its column (the classic list only moves up and down). Do not combine with animateItem():
 * the dragged row is placed by the finger, not by the list.
 */
internal fun Modifier.libraryDragVisual(controller: LibraryDragController, key: String, lockX: Boolean): Modifier {
    if (controller.drag.key != key) return this
    return this.zIndex(10f).graphicsLayer {
        val tl = controller.host.topLeft(key)
        if (tl != null) {
            translationY = controller.drag.pointer.y - controller.drag.grab.y - tl.y
            if (!lockX) translationX = controller.drag.pointer.x - controller.drag.grab.x - tl.x
        }
        scaleX = 1.04f
        scaleY = 1.04f
    }
}
