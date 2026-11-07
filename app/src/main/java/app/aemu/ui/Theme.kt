package app.aemu.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Modified for AEmulator Sunset on 2026-09-30: sunset-orange Material 3 palette.
private val Light = lightColorScheme(
    primary = Color(0xFFF4511E), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCF), onPrimaryContainer = Color(0xFF3B0900),
    secondary = Color(0xFF77574D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBCF), onSecondaryContainer = Color(0xFF2C150F),
    tertiary = Color(0xFF6D5D2F), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF7E2A7), onTertiaryContainer = Color(0xFF231B00),
    background = Color(0xFFFFF8F5), surface = Color(0xFFFFF8F5),
    surfaceContainer = Color(0xFFF8ECE7), surfaceContainerHigh = Color(0xFFF2E6E1),
    surfaceContainerHighest = Color(0xFFECE0DB), surfaceContainerLow = Color(0xFFFFF1EC),
    error = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB59F), onPrimary = Color(0xFF5F1600),
    primaryContainer = Color(0xFF862200), onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFE7BDB0), onSecondary = Color(0xFF442A22),
    secondaryContainer = Color(0xFF5D4037), onSecondaryContainer = Color(0xFFFFDBCF),
    tertiary = Color(0xFFDAC68D), onTertiary = Color(0xFF3B2F05),
    tertiaryContainer = Color(0xFF534619), onTertiaryContainer = Color(0xFFF7E2A7),
    background = Color(0xFF18120F), surface = Color(0xFF18120F),
    surfaceContainer = Color(0xFF251D1A), surfaceContainerHigh = Color(0xFF302825),
    surfaceContainerHighest = Color(0xFF3B322F), surfaceContainerLow = Color(0xFF211A17),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val base = Typography()
private val AppType = base.copy(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

private fun hsl(h: Float, s: Float, l: Float) =
    Color(androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))))

private fun onColor(c: Color) = if (c.luminance() > 0.5f) Color(0xFF1B1B1B) else Color.White

/** Builds a full Material 3 scheme from a single seed color picked by the user. */
fun schemeFromSeed(seed: Color, dark: Boolean): androidx.compose.material3.ColorScheme {
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(seed.toArgb(), hsl)
    val h = hsl[0]
    val s = hsl[1].coerceIn(0.25f, 1f)
    val ns = (s * 0.3f).coerceAtMost(0.16f) // neutral tint for surfaces
    return if (!dark) {
        val primary = seed
        lightColorScheme(
            primary = primary, onPrimary = onColor(primary),
            primaryContainer = hsl(h, s, 0.90f), onPrimaryContainer = hsl(h, s, 0.12f),
            secondary = hsl(h, s * 0.28f, 0.38f), onSecondary = Color.White,
            secondaryContainer = hsl(h, s * 0.45f, 0.90f), onSecondaryContainer = hsl(h, s * 0.4f, 0.12f),
            tertiary = hsl(h + 60f, s * 0.3f, 0.38f), onTertiary = Color.White,
            tertiaryContainer = hsl(h + 60f, s * 0.5f, 0.88f), onTertiaryContainer = hsl(h + 60f, s * 0.4f, 0.12f),
            background = hsl(h, ns, 0.98f), surface = hsl(h, ns, 0.98f),
            surfaceContainerLow = hsl(h, ns, 0.96f), surfaceContainer = hsl(h, ns, 0.94f),
            surfaceContainerHigh = hsl(h, ns, 0.92f), surfaceContainerHighest = hsl(h, ns, 0.90f),
            error = Color(0xFFBA1A1A),
        )
    } else {
        val primary = hsl(h, s, 0.78f)
        darkColorScheme(
            primary = primary, onPrimary = hsl(h, s, 0.16f),
            primaryContainer = hsl(h, s, 0.26f), onPrimaryContainer = hsl(h, s, 0.90f),
            secondary = hsl(h, s * 0.4f, 0.80f), onSecondary = hsl(h, s * 0.3f, 0.16f),
            secondaryContainer = hsl(h, s * 0.3f, 0.26f), onSecondaryContainer = hsl(h, s * 0.45f, 0.90f),
            tertiary = hsl(h + 60f, s * 0.4f, 0.78f), onTertiary = hsl(h + 60f, s * 0.3f, 0.16f),
            tertiaryContainer = hsl(h + 60f, s * 0.3f, 0.26f), onTertiaryContainer = hsl(h + 60f, s * 0.5f, 0.88f),
            background = hsl(h, ns, 0.08f), surface = hsl(h, ns, 0.08f),
            surfaceContainerLow = hsl(h, ns, 0.10f), surfaceContainer = hsl(h, ns, 0.13f),
            surfaceContainerHigh = hsl(h, ns, 0.17f), surfaceContainerHighest = hsl(h, ns, 0.21f),
        )
    }
}

@Composable
fun AemuTheme(forceDark: Boolean? = null, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    // the main screen stays alive while the settings screen changes the theme: follow the stored value
    val rev = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(ctx) {
        val sp = app.aemu.AppPrefs.prefs(ctx)
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> rev.intValue++ }
        sp.registerOnSharedPreferenceChangeListener(l)
        onDispose { sp.unregisterOnSharedPreferenceChangeListener(l) }
    }
    rev.intValue
    val dark = forceDark ?: when (app.aemu.AppPrefs.theme(ctx)) {
        app.aemu.AppPrefs.THEME_LIGHT -> false
        app.aemu.AppPrefs.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }
    val accent = app.aemu.AppPrefs.accentColor(ctx)
    val scheme = when {
        accent != 0 -> schemeFromSeed(Color(accent), dark)
        Build.VERSION.SDK_INT >= 31 && app.aemu.AppPrefs.dynamicColor(ctx) -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        shapes = AppShapes,
        typography = AppType,
        content = content,
    )
}

val Mono = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
