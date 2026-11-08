package app.aemu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aemu.R
import app.aemu.core.KeyboardLatch
import app.aemu.core.Keypad
import app.aemu.core.PadKey
import app.aemu.core.VKey
import app.aemu.core.VirtualKeyboardLayout
import app.aemu.core.VmTheme
import kotlin.math.roundToInt

/** A key of the navigation bar that opens one of the floating input windows; lit while its window is open. */
@Composable
internal fun ExtraNavButton(icon: ImageVector, label: String, active: Boolean, theme: VmTheme, iconRotation: Float, onClick: () -> Unit) {
    val press = Color(theme.pressColor)
    val ink = theme.iconColor?.let { Color(it) } ?: LocalContentColor.current
    val click by rememberUpdatedState(onClick)
    Box(
        Modifier.size(48.dp * theme.iconScale)
            .background(if (active) press else Color.Transparent, RoundedCornerShape(8.dp))
            .semantics { contentDescription = label; role = Role.Button }
            .pointerInput(Unit) { detectTapGestures(onTap = { click() }) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(24.dp * theme.iconScale).rotate(iconRotation), tint = ink)
    }
}

internal val KeyboardIcon: ImageVector get() = Icons.Rounded.Keyboard
internal val KeypadIcon: ImageVector get() = Icons.Rounded.Dialpad

/** last position and size of each floating window (by title), so it comes back as the person left it */
private val lastWindowRect = mutableMapOf<String, FloatArray>()

/**
 * Frame shared by the floating windows: draggable by its title bar, resizable by dragging any corner (width and height
 * are free, the keys share the space), half transparent (the opacity slider is in the title bar too), and it swallows
 * the touches that land on it so they never reach the guest screen below. It always stays completely on screen.
 */
@Composable
private fun FloatingWindow(
    title: String, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, startX: Float, startY: Float,
    onClose: () -> Unit, content: @Composable ColumnScope.(alpha: Float) -> Unit,
) {
    val cfg = LocalConfiguration.current
    val density = LocalDensity.current
    val screenW = with(density) { cfg.screenWidthDp.dp.toPx() }
    val screenH = with(density) { cfg.screenHeightDp.dp.toPx() }
    val margin = with(density) { 12.dp.toPx() }
    val initW = minOf(with(density) { width.toPx() }, screenW - margin).coerceAtLeast(1f)
    val initH = minOf(with(density) { height.toPx() }, screenH - margin).coerceAtLeast(1f)
    val minW = minOf(with(density) { 150.dp.toPx() }, initW)
    val minH = minOf(with(density) { 130.dp.toPx() }, initH)
    val saved = remember { lastWindowRect[title] }
    var w by remember { mutableFloatStateOf(saved?.get(2) ?: initW) }
    var h by remember { mutableFloatStateOf(saved?.get(3) ?: initH) }
    var offset by remember { mutableStateOf(Offset(saved?.get(0) ?: (startX * screenW), saved?.get(1) ?: (startY * screenH))) }
    var alpha by remember { mutableFloatStateOf(0.6f) }
    // keep it inside the screen now and whenever the screen changes (rotation)
    fun fit() {
        w = w.coerceIn(minOf(minW, screenW), screenW); h = h.coerceIn(minOf(minH, screenH), screenH)
        offset = Offset(offset.x.coerceIn(0f, screenW - w), offset.y.coerceIn(0f, screenH - h))
        lastWindowRect[title] = floatArrayOf(offset.x, offset.y, w, h)
    }
    LaunchedEffect(screenW, screenH) { fit() }
    fun dragCorner(dx: Float, dy: Float, left: Boolean, top: Boolean) {
        var l = offset.x; var t = offset.y; var r = l + w; var b = t + h
        if (left) l = (l + dx).coerceIn(0f, r - minW) else r = (r + dx).coerceIn(l + minW, screenW)
        if (top) t = (t + dy).coerceIn(0f, b - minH) else b = (b + dy).coerceIn(t + minH, screenH)
        offset = Offset(l, t); w = r - l; h = b - t
        lastWindowRect[title] = floatArrayOf(offset.x, offset.y, w, h)
    }
    @Composable fun BoxScope.Grip(align: Alignment, left: Boolean, top: Boolean) {
        Box(
            Modifier.align(align).size(26.dp).pointerInput(screenW, screenH) {
                detectDragGestures { change, drag -> change.consume(); dragCorner(drag.x, drag.y, left, top) }
            },
            contentAlignment = align,
        ) { Box(Modifier.padding(3.dp).size(9.dp).background(Color.White.copy(alpha = 0.6f), RoundedCornerShape(3.dp))) }
    }
    Box(
        Modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .size(with(density) { w.toDp() }, with(density) { h.toDp() })
            // an overlay with a pointer handler is the only hit target here: touches stop at it
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Final).changes.forEach { it.consume() } } },
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(Color(0xFF15171C).copy(alpha = alpha), RoundedCornerShape(14.dp))
                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                .padding(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().height(32.dp).pointerInput(screenW, screenH) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        offset = Offset(
                            (offset.x + drag.x).coerceIn(0f, (screenW - w).coerceAtLeast(0f)),
                            (offset.y + drag.y).coerceIn(0f, (screenH - h).coerceAtLeast(0f)),
                        )
                        lastWindowRect[title] = floatArrayOf(offset.x, offset.y, w, h)
                    }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.DragHandle, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(horizontal = 6.dp).size(18.dp))
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
                Slider(
                    alpha, { alpha = it.coerceIn(0.25f, 1f) }, valueRange = 0.25f..1f,
                    modifier = Modifier.width(72.dp).semantics { contentDescription = "Opacity" },
                )
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.vi_close), tint = Color.White)
                }
            }
            content(alpha)
        }
        Grip(Alignment.TopStart, left = true, top = true)
        Grip(Alignment.TopEnd, left = false, top = true)
        Grip(Alignment.BottomStart, left = true, top = false)
        Grip(Alignment.BottomEnd, left = false, top = false)
    }
}

/** One key: held in the guest from touch-down to release, several at once for chords. */
@Composable
private fun KeyCap(
    label: String, sub: String = "", modifier: Modifier, lit: Boolean = false,
    onDown: () -> Unit, onUp: () -> Unit, fontSize: Int = 13,
) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val up by rememberUpdatedState(onUp)
    DisposableEffect(Unit) { onDispose { if (pressed) up() } }
    Box(
        modifier
            .background(if (pressed || lit) Color(0xFF4F7CFF).copy(alpha = 0.9f) else Color.White.copy(alpha = 0.16f), RoundedCornerShape(7.dp))
            .semantics { contentDescription = label; role = Role.Button }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onDown()
                    try { tryAwaitRelease() } finally { pressed = false; up() }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Color.White, fontSize = fontSize.sp, maxLines = 1)
            if (sub.isNotEmpty()) Text(sub, color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp, maxLines = 1)
        }
    }
}

/** Half-transparent keyboard whose keys are real key events of the guest's keyboard device. */
@Composable
internal fun VirtualKeyboardWindow(send: (Int, Boolean) -> Unit, rows: List<List<VKey>> = VirtualKeyboardLayout.rows, onClose: () -> Unit) {
    val latch = remember { KeyboardLatch(send) }
    var latched by remember { mutableStateOf(emptySet<Int>()) }
    DisposableEffect(Unit) { onDispose { latch.releaseAll() } }
    val shift = app.aemu.core.VirtualKeys.SHIFT in latched
    FloatingWindow(stringResource(R.string.vi_keyboard), 420.dp, (rows.size * 37 + 48).dp, 0.02f, 0.45f, onClose) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f).padding(vertical = 1.5.dp)) {
                row.forEach { key -> KeyboardKey(key, shift, key.code in latched, latch) { latched = latch.latchedModifiers.toSet() } }
            }
        }
    }
}

@Composable
private fun RowScope.KeyboardKey(key: VKey, shift: Boolean, lit: Boolean, latch: KeyboardLatch, changed: () -> Unit) {
    val shown = if (shift && key.shifted != null) key.shifted else key.label
    KeyCap(
        shown, modifier = Modifier.weight(key.weight).padding(horizontal = 1.5.dp).fillMaxHeight(), lit = lit,
        onDown = { if (key.isModifier) { latch.tapModifier(key.code); changed() } else latch.down(key.code) },
        onUp = { if (!key.isModifier) { latch.up(key.code); changed() } },
        fontSize = if (shown.length > 2) 10 else 13,
    )
}

/** Feature-phone keypad: soft keys, call/home/end, a navigation cross and the number block. Touch stays on. */
@Composable
internal fun KeypadWindow(send: (Int, Boolean) -> Unit, homeCode: Int, onClose: () -> Unit) {
    val held = remember { mutableSetOf<Int>() }
    DisposableEffect(Unit) { onDispose { held.toList().forEach { send(it, false) }; held.clear() } }
    fun down(k: PadKey) { if (held.add(k.code)) send(k.code, true) }
    fun up(k: PadKey) { if (held.remove(k.code)) send(k.code, false) }
    val home = remember(homeCode) { PadKey("Home", homeCode) }
    @Composable fun RowScope.Cap(k: PadKey) =
        KeyCap(k.label, k.sub, Modifier.weight(1f).padding(2.dp).fillMaxHeight(), onDown = { down(k) }, onUp = { up(k) }, fontSize = 15)
    @Composable fun ColumnScope.PadRow(content: @Composable RowScope.() -> Unit) = Row(Modifier.fillMaxWidth().weight(1f), content = content)
    FloatingWindow(stringResource(R.string.vi_keypad_title), 220.dp, 460.dp, 0.55f, 0.2f, onClose) {
        PadRow { Cap(Keypad.softLeft); Cap(Keypad.softRight) }
        PadRow { Spacer(Modifier.weight(1f)); Cap(Keypad.up); Spacer(Modifier.weight(1f)) }
        PadRow { Cap(Keypad.left); Cap(Keypad.ok); Cap(Keypad.right) }
        PadRow { Spacer(Modifier.weight(1f)); Cap(Keypad.down); Spacer(Modifier.weight(1f)) }
        PadRow { Cap(Keypad.call); Cap(home); Cap(Keypad.end) }
        Keypad.digits.chunked(3).forEach { r -> PadRow { r.forEach { Cap(it) } } }
    }
}
