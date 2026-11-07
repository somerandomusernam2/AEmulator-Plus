/* Modified for AEmulator Plus, 2026-11-04: opened from the running machine's menu; stays open and applies live. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.aemu.R
import app.aemu.core.GuestImage
import app.aemu.core.ImageStore
import app.aemu.core.ThermalSysfs
import app.aemu.core.VmPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Edit of the guest's /sys/devices/virtual/thermal/thermal_zone17/temp from the running machine's menu. The value is kept
 * in the machine's settings (so it survives reboots) and written to the guest's sysfs at once, so a running guest sees it
 * on its next read. The dialog stays open after a change, so the temperature can be adjusted again and again (slider or
 * typed value) while the guest runs.
 */
@Composable
fun ThermalDialog(img: GuestImage, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(img.settings) }
    var served by remember { mutableStateOf<Int?>(null) }
    var host by remember { mutableStateOf<Int?>(null) }
    var text by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }

    fun fill(fresh: app.aemu.core.VmSettings, now: Int?, h: Int?) {
        settings = fresh; served = now; host = h
        text = ((fresh.thermalTemp ?: ThermalSysfs.effective(fresh) ?: now ?: h)?.let { "%.1f".format(java.util.Locale.ROOT, it / 1000.0) }) ?: ""
    }

    LaunchedEffect(img.id) {
        withContext(Dispatchers.IO) {
            val fresh = ImageStore.get(ctx, img.id)?.settings ?: img.settings
            val now = ThermalSysfs.current(VmPaths(ctx, img.id).root)
            val h = ThermalSysfs.hostTemp()
            withContext(Dispatchers.Main) { fill(fresh, now, h) }
        }
    }

    // writes the value (null = back to the default) and refreshes what the dialog shows; the dialog stays open
    fun save(newTemp: Int?) = scope.launch(Dispatchers.IO) {
        val updated = ImageStore.update(ctx, img.id) { it.copy(settings = it.settings.copy(thermalTemp = newTemp)) }
        val root = VmPaths(ctx, img.id).root
        if (updated != null && root.isDirectory) ThermalSysfs.apply(root, updated.settings)
        val now = ThermalSysfs.current(root)
        val h = ThermalSysfs.hostTemp()
        withContext(Dispatchers.Main) { fill(updated?.settings ?: settings, now, h); bad = false }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.thermal_title)) },
        text = {
            Column {
                Text(ThermalSysfs.GUEST_PATH, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.thermal_mode,
                    stringResource(if (settings.thermalPassthrough) R.string.thermal_mode_host else R.string.thermal_mode_cool)))
                served?.let { Text(stringResource(R.string.thermal_guest_now, ThermalSysfs.format(it))) }
                host?.let { Text(stringResource(R.string.thermal_host_now, ThermalSysfs.format(it))) }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; bad = false },
                    label = { Text(stringResource(R.string.thermal_value)) },
                    singleLine = true,
                    isError = bad,
                    supportingText = { Text(stringResource(if (bad) R.string.thermal_invalid else R.string.thermal_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                // quick live adjustment: dragging shows the value, letting go applies it to the running guest
                val sliderC = (ThermalSysfs.parseInput(text) ?: ThermalSysfs.COOL_MILLI_C) / 1000f
                Slider(
                    value = sliderC.coerceIn(0f, 100f),
                    onValueChange = { text = "%.1f".format(java.util.Locale.ROOT, it); bad = false },
                    onValueChangeFinished = { ThermalSysfs.parseInput(text)?.let { save(it) } },
                    valueRange = 0f..100f,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = ThermalSysfs.parseInput(text)
                if (v == null) bad = true else save(v)
            }) { Text(stringResource(R.string.thermal_apply)) }
        },
        dismissButton = {
            Column {
                TextButton(onClick = { save(null) }) { Text(stringResource(R.string.thermal_reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
    )
}
