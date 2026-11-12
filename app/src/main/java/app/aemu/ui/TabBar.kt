/* Added for AEmulator Plus, 2026-11-09: the tab bar of the one-window multi-VM interface; a tab may hold two machines. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.aemu.R
import app.aemu.core.Keeper
import app.aemu.core.TabRegistry
import app.aemu.core.VmSlots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Moving between the windows (library and machines) of the one app task. */
internal object TabNav {
    private fun prepare(ctx: Context, intent: Intent): Intent {
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    @Suppress("DEPRECATION")
    private fun noTransition(ctx: Context) { (ctx as? Activity)?.overridePendingTransition(0, 0) }

    /** Brings the window of [intent] to the front of the app's task (a new one is created when there is none). */
    fun show(ctx: Context, intent: Intent) {
        ctx.startActivity(prepare(ctx, intent))
        noTransition(ctx)
    }

    fun openHome(ctx: Context) = show(ctx, Intent(ctx, MainActivity::class.java))

    /**
     * Opens a tab. A tab of two machines is two windows, the base one first and the overlay on top of it, each half of the
     * screen (see VmActivity.applyPane).
     */
    fun openTab(ctx: Context, tab: TabRegistry.Tab) {
        val intents = tab.ids.mapNotNull { id ->
            val slot = VmSlots.slotOf(ctx, id) ?: return@mapNotNull null
            prepare(ctx, VmActivity.intentFor(ctx, slot, id, overlay = tab.isOverlay(id)))
        }
        when (intents.size) {
            0 -> return
            1 -> ctx.startActivity(intents[0])
            else -> ctx.startActivities(intents.toTypedArray())
        }
        noTransition(ctx)
    }

    /** the tab that machine [id] is in (a tab of its own when it has none) */
    fun openVm(ctx: Context, id: String) = openTab(ctx, TabRegistry.entryOf(ctx, id) ?: TabRegistry.Tab(listOf(id)))

    /**
     * The tab to show when machine [closing] goes: the other machine of its tab (alone, full screen), else the tab to the
     * left, else the one to the right; null is the library.
     */
    fun neighbor(ctx: Context, closing: String): TabRegistry.Tab? {
        val all = TabRegistry.entries(ctx)
        val i = all.indexOfFirst { closing in it.ids }
        all.getOrNull(i)?.otherThan(closing)?.let { return TabRegistry.Tab(listOf(it)) }
        val snap = VmSlots.snapshot(ctx)
        val others = all.filterIndexed { idx, t -> idx != i && (!snap.reliable || t.ids.any { snap.slotOf(it) != null }) }
        if (others.isEmpty()) return null
        return all.take(maxOf(i, 0)).lastOrNull { it in others } ?: others.first()
    }

    fun go(ctx: Context, tab: TabRegistry.Tab?) { if (tab == null) openHome(ctx) else openTab(ctx, tab) }
}

/** What the close dialog's answers do to a machine that is not this window's own. */
internal object TabActions {
    /** the machine leaves its tab, it keeps running */
    fun minimize(ctx: Context, id: String) = TabRegistry.close(ctx, id)

    /** the machine leaves its tab and is turned off (in its own process) */
    fun shutdown(ctx: Context, id: String) {
        val slot = VmSlots.slotOf(ctx, id)
        TabRegistry.close(ctx, id)
        if (slot != null) Keeper.requestShutdown(ctx, slot)
    }
}

/** The open tabs, refreshed once a second while the window is on screen. */
@Composable
internal fun rememberVmTabs(): State<List<TabRegistry.Tab>> {
    val ctx = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf<List<TabRegistry.Tab>>(emptyList()) }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                state.value = withContext(Dispatchers.IO) { runCatching { TabRegistry.live(ctx) }.getOrDefault(state.value) }
                delay(1000)
            }
        }
    }
    return state
}

/**
 * The tab bar, in the app's Material 3 style: a small library chip (icon only), then one input chip per tab. A tab of one
 * machine is named with its first 10 characters, a tab of two machines "2 tabs". The selected tab uses the secondary
 * container colour, like the app's other chips.
 *
 * [current] is the machine this window shows (null in the library). Closing a tab asks: minimize or shut down.
 * [onCloseCurrent] handles the window's own machine (true = shut down); the others are closed from here.
 * With [allowSplitDrag] a tab of one machine can be pressed, held and dragged onto the screen to share it.
 * The close button of a tab of two machines splits it back into two tabs (nothing is closed). That question, and any question
 * asked from a window that is half of a split ([inSplit]), is a window of its own over the whole screen (TabDialogActivity):
 * a dialog of one half would be under the other.
 */
@Composable
internal fun VmTabStrip(
    tabs: List<TabRegistry.Tab>,
    current: String?,
    topInset: Boolean,
    modifier: Modifier = Modifier,
    allowSplitDrag: Boolean = false,
    inSplit: Boolean = false,
    onCloseCurrent: ((shutdown: Boolean) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val cs = MaterialTheme.colorScheme
    var closing by remember { mutableStateOf<TabRegistry.Tab?>(null) }

    Surface(modifier.fillMaxWidth(), color = cs.surfaceContainer) {
        Column(if (topInset) Modifier.statusBarsPadding() else Modifier) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp).animateContentSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // the library: an icon only, kept small
                FilterChip(
                    selected = current == null,
                    onClick = { if (current != null) TabNav.openHome(ctx) },
                    label = { Icon(Icons.Rounded.Home, stringResource(R.string.tab_home), Modifier.size(FilterChipDefaults.IconSize)) },
                )
                for (tab in tabs) {
                    val selected = current != null && current in tab.ids
                    val dragName = tab.names[0]
                    val dragBg = cs.secondaryContainer.toArgb()
                    val dragFg = cs.onSecondaryContainer.toArgb()
                    val dragModifier = if (allowSplitDrag && !tab.isGroup && !selected) {
                        // press, hold and drag onto the screen: the tab's machine goes next to this window's
                        Modifier.pointerInput(tab.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { DragTab.start(view, tab.id, TabRegistry.shortName(dragName), dragBg, dragFg) },
                                onDrag = { _, _ -> },
                                onDragEnd = {},
                                onDragCancel = {},
                            )
                        }
                    } else Modifier
                    InputChip(
                        selected = selected,
                        onClick = { if (!selected) TabNav.openTab(ctx, tab) },
                        modifier = dragModifier,
                        label = {
                            Text(
                                if (tab.isGroup) stringResource(R.string.tab_group_title) else TabRegistry.shortName(tab.names[0]),
                                maxLines = 1, softWrap = false,
                            )
                        },
                        trailingIcon = {
                            // the chip itself switches to the tab; only this icon closes it
                            Box(
                                Modifier.size(24.dp).clip(CircleShape)
                                    .clickable { if (tab.isGroup || inSplit) TabDialogActivity.open(ctx, tab.id) else closing = tab },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Close, stringResource(if (tab.isGroup) R.string.tab_split_action else R.string.tab_close), Modifier.size(InputChipDefaults.IconSize))
                            }
                        },
                    )
                }
            }
            HorizontalDivider(color = cs.outlineVariant)
        }
    }

    closing?.let { tab ->
        fun answer(shutdown: Boolean) {
            closing = null
            val app = ctx.applicationContext
            // the other machines first, then this window's own (it moves on to another tab)
            for (id in tab.ids) {
                if (id == current && onCloseCurrent != null) continue
                if (shutdown) TabActions.shutdown(app, id) else TabActions.minimize(app, id)
            }
            if (current != null && current in tab.ids) onCloseCurrent?.invoke(shutdown)
        }
        AlertDialog(
            onDismissRequest = { closing = null },
            title = { Text(stringResource(R.string.tab_close_title, tab.names[0])) },
            text = { Text(stringResource(R.string.tab_close_text)) },
            confirmButton = {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = { closing = null }) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = { answer(true) }, colors = ButtonDefaults.textButtonColors(contentColor = cs.error)) {
                        Text(stringResource(R.string.tab_shutdown))
                    }
                    TextButton(onClick = { answer(false) }) {
                        Text(stringResource(R.string.tab_minimize))
                    }
                }
            },
        )
    }
}
