/* Added for AEmulator Plus, 2026-11-10: the tab bar's dialogs, shown over the whole screen. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.aemu.R
import app.aemu.core.TabRegistry

/**
 * A dialog of the tab bar in a window of its own: a transparent window over the whole screen, so the dialog is on top of
 * both machines of a split (a dialog of one machine's window would sit under the other one's).
 *
 * For a tab of two machines it asks to split it back into two tabs (nothing is closed); for any other tab it asks to
 * minimize it or shut it down. It goes away by itself when its tab changes in the meantime.
 */
class TabDialogActivity : ComponentActivity() {
    private var tab by mutableStateOf<TabRegistry.Tab?>(null)
    private var splitDialog = false
    private var receiver: BroadcastReceiver? = null

    override fun attachBaseContext(base: Context) = super.attachBaseContext(app.aemu.AppPrefs.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        val first = id?.let { i -> TabRegistry.live(applicationContext).firstOrNull { i in it.ids } }
        if (id == null || first == null) { finish(); return }
        splitDialog = first.isGroup
        tab = first
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { refresh(id) }
        }
        receiver = rx
        val filter = IntentFilter(TabRegistry.ACTION_CHANGED)
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(rx, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(rx, filter)
        setContent { AemuTheme(forceDark = true) { Content() } }
    }

    /** the tab is not what the dialog was about any more (closed, split, or joined): the dialog would be out of date */
    private fun refresh(id: String) {
        val now = TabRegistry.live(applicationContext).firstOrNull { id in it.ids }
        if (now == null || now.isGroup != splitDialog) finish() else tab = now
    }

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        super.onDestroy()
    }

    @androidx.compose.runtime.Composable
    private fun Content() {
        val t = tab ?: return
        val app = applicationContext
        if (splitDialog) {
            AlertDialog(
                onDismissRequest = { finish() },
                title = { Text(stringResource(R.string.tab_split_title)) },
                text = { Text(stringResource(R.string.tab_split_text)) },
                dismissButton = { TextButton(onClick = { finish() }) { Text(stringResource(R.string.cancel)) } },
                confirmButton = {
                    TextButton(onClick = { TabRegistry.separate(app, t.id); finish() }) { Text(stringResource(R.string.tab_split_action)) }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { finish() },
                title = { Text(stringResource(R.string.tab_close_title, t.names[0])) },
                text = { Text(stringResource(R.string.tab_close_text)) },
                confirmButton = {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        TextButton(onClick = { finish() }) { Text(stringResource(R.string.cancel)) }
                        TextButton(
                            onClick = { TabActions.shutdown(app, t.id); finish() },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text(stringResource(R.string.tab_shutdown)) }
                        TextButton(onClick = { TabActions.minimize(app, t.id); finish() }) { Text(stringResource(R.string.tab_minimize)) }
                    }
                },
            )
        }
    }

    companion object {
        private const val EXTRA_ID = "id"

        /** Asks about the tab of machine [id], over everything on screen. */
        fun open(ctx: Context, id: String) {
            val i = Intent(ctx, TabDialogActivity::class.java).putExtra(EXTRA_ID, id)
            if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }
}
