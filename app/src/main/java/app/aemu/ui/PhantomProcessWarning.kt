/* AEmulator Plus addition, 2026-11-08: startup warning for the Android 12+ phantom process killer. GPL-3.0; see NOTICE.md. */
package app.aemu.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aemu.AppPrefs
import app.aemu.R

/**
 * Detection of Android's phantom process monitor ("Disable child process restrictions" in Developer Options).
 *
 * The toggle is AOSP's PhantomProcessPreferenceController, which calls FeatureFlagUtils.setEnabled() with the
 * flag [SETTING]. That writes the system property persist.sys.fflag.override.[SETTING] (not Settings.Global).
 * FeatureFlagUtils.isEnabled() resolves it as: Settings.Global -> persist.sys.fflag.override.* ->
 * sys.fflag.override.* -> sys.fflag.* -> default (true = monitor on). The toggle being ON means the flag is "false".
 */
internal object PhantomProcessPolicy {
    const val SETTING = "settings_enable_monitor_phantom_procs"
    const val PERSIST_OVERRIDE_PROP = "persist.sys.fflag.override.$SETTING"
    const val OVERRIDE_PROP = "sys.fflag.override.$SETTING"
    const val DEFAULT_PROP = "sys.fflag.$SETTING"

    /** The monitor only exists on Android 12 (API 31) and newer. */
    fun applies(sdk: Int): Boolean = sdk >= Build.VERSION_CODES.S

    /** Mirrors FeatureFlagUtils.isEnabled(): first non-empty source wins, parsed like Boolean.parseBoolean. */
    fun monitorEnabled(global: String?, persistOverride: String?, override: String?, default: String?): Boolean {
        val v = listOf(global, persistOverride, override, default).firstOrNull { !it.isNullOrEmpty() }
            ?: return true
        return v.equals("true", ignoreCase = true)
    }

    fun shouldWarn(sdk: Int, global: String?, persistOverride: String?, override: String?, default: String?): Boolean =
        applies(sdk) && monitorEnabled(global, persistOverride, override, default)

    private fun sysProp(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, key) as? String
    }.getOrNull()

    fun shouldWarn(ctx: Context): Boolean {
        if (!applies(Build.VERSION.SDK_INT)) return false
        val global = runCatching { Settings.Global.getString(ctx.contentResolver, SETTING) }.getOrNull()
        val persist = sysProp(PERSIST_OVERRIDE_PROP)
        val override = sysProp(OVERRIDE_PROP)
        val default = sysProp(DEFAULT_PROP)
        android.util.Log.i("PhantomProcess", "global=$global persist=$persist override=$override default=$default")
        return shouldWarn(Build.VERSION.SDK_INT, global, persist, override, default)
    }

    fun openDeveloperOptions(ctx: Context) {
        fun launch(action: String) = runCatching {
            val intent = Intent(action)
            if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }.isSuccess
        // Developer options may be hidden until enabled; fall back to the main Settings screen.
        if (!launch(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) launch(Settings.ACTION_SETTINGS)
    }
}

/** Shown on every startup (until "Never remind again") when the phantom process killer is active. */
@Composable
internal fun PhantomProcessWarning() {
    val ctx = LocalContext.current
    val warn = remember {
        !AppPrefs.phantomWarningDisabled(ctx) && PhantomProcessPolicy.shouldWarn(ctx)
    }
    // Survives rotation; a fresh app start shows it again.
    var show by rememberSaveable { mutableStateOf(warn) }
    if (!show || !warn) return

    AlertDialog(
        onDismissRequest = { show = false },
        icon = {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = stringResource(R.string.phantom_title),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.phantom_msg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(20.dp))
                // Primary action: larger, lighter container.
                Button(
                    onClick = { PhantomProcessPolicy.openDeveloperOptions(ctx) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Text(
                        text = stringResource(R.string.phantom_open_dev),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(8.dp))
                // Secondary actions: smaller, darker containers.
                val darker = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { show = false },
                        modifier = Modifier.weight(1f),
                        colors = darker,
                        contentPadding = ButtonDefaults.ContentPadding
                    ) {
                        Text(
                            text = stringResource(R.string.phantom_ignore),
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center
                        )
                    }
                    Button(
                        onClick = {
                            AppPrefs.setPhantomWarningDisabled(ctx, true)
                            show = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = darker
                    ) {
                        Text(
                            text = stringResource(R.string.phantom_never),
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        },
        confirmButton = {}
    )
}
