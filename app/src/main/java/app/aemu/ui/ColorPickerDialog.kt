package app.aemu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import app.aemu.R

private val Presets = listOf(
    0xFFF4511E, 0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5E35B1, 0xFF3949AB,
    0xFF1E88E5, 0xFF00ACC1, 0xFF00897B, 0xFF43A047, 0xFFFDD835, 0xFF6D4C41,
).map { Color(it) }

private fun hsvToColor(h: Float, s: Float, v: Float) = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))

/** Simple HSV color picker: preset swatches, hue/saturation/brightness sliders and a hex field. */
@Composable
fun ColorPickerDialog(initial: Color, onDismiss: () -> Unit, onReset: () -> Unit, onApply: (Color) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgbInt(), it) } }
    var h by remember { mutableStateOf(hsv[0]) }
    var s by remember { mutableStateOf(hsv[1]) }
    var v by remember { mutableStateOf(hsv[2]) }
    var hex by remember { mutableStateOf(hexOf(hsvToColor(hsv[0], hsv[1], hsv[2]))) }
    val color = hsvToColor(h, s, v)

    fun setFrom(c: Color) {
        val a = FloatArray(3); android.graphics.Color.colorToHSV(c.toArgbInt(), a)
        h = a[0]; s = a[1]; v = a[2]; hex = hexOf(c)
    }
    fun update(nh: Float = h, ns: Float = s, nv: Float = v) { h = nh; s = ns; v = nv; hex = hexOf(hsvToColor(nh, ns, nv)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.as_accent_pick)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(color))
                // presets
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Presets.chunked(6).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            row.forEach { c ->
                                Box(Modifier.size(32.dp).clip(CircleShape).background(c)
                                    .border(2.dp, if (c == color) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                                    .clickable { setFrom(c) })
                            }
                        }
                    }
                }
                Text(stringResource(R.string.as_accent_hue), style = MaterialTheme.typography.labelMedium)
                GradientSlider(h, 0f, 360f, (0..6).map { hsvToColor(it * 60f, 1f, 1f) }) { update(nh = it) }
                Text(stringResource(R.string.as_accent_sat), style = MaterialTheme.typography.labelMedium)
                GradientSlider(s, 0f, 1f, listOf(hsvToColor(h, 0f, v), hsvToColor(h, 1f, v))) { update(ns = it) }
                Text(stringResource(R.string.as_accent_val), style = MaterialTheme.typography.labelMedium)
                GradientSlider(v, 0f, 1f, listOf(Color.Black, hsvToColor(h, s, 1f))) { update(nv = it) }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { t ->
                        val clean = t.uppercase().filter { it == '#' || it in "0123456789ABCDEF" }.take(7)
                        hex = clean
                        parseHex(clean)?.let { c ->
                            val a = FloatArray(3); android.graphics.Color.colorToHSV(c.toArgbInt(), a)
                            h = a[0]; s = a[1]; v = a[2]
                        }
                    },
                    label = { Text(stringResource(R.string.as_accent_hex)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onApply(color) }) { Text(stringResource(R.string.as_accent_apply)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text(stringResource(R.string.as_accent_reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun GradientSlider(value: Float, min: Float, max: Float, colors: List<Color>, onChange: (Float) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(10.dp).clip(CircleShape)
            .background(Brush.horizontalGradient(colors)))
        Slider(
            value = value, onValueChange = onChange, valueRange = min..max,
            colors = androidx.compose.material3.SliderDefaults.colors(
                activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent,
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
        )
    }
}

private fun Color.toArgbInt(): Int = this.toArgb()
private fun hexOf(c: Color) = "#%06X".format(c.toArgbInt() and 0xFFFFFF)
private fun parseHex(t: String): Color? {
    val d = t.removePrefix("#")
    if (d.length != 6) return null
    return d.toIntOrNull(16)?.let { Color(0xFF000000.toInt() or it) }
}