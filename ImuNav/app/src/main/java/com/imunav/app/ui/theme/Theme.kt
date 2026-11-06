package com.imunav.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Instrument palette shared by the custom gauges (theme independent). */
object Instrument {
    val Sky = Color(0xFF2B6CB0)
    val SkyTop = Color(0xFF1A365D)
    val Ground = Color(0xFF8B5A2B)
    val GroundBottom = Color(0xFF4A2F17)
    val Accent = Color(0xFF4DD0E1)
    val Warn = Color(0xFFFFB74D)
    val Good = Color(0xFF69F0AE)
    val Bad = Color(0xFFFF5252)
    val Gps = Color(0xFFFFB74D)
}

private val Dark = darkColorScheme(
    primary = Color(0xFF4DD0E1),
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF97F0FF),
    secondary = Color(0xFF80CBC4),
    tertiary = Color(0xFFFFB74D),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF0B1220),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1C2740),
    onSurfaceVariant = Color(0xFF9FB0C8),
    surfaceContainer = Color(0xFF131C30),
    surfaceContainerHigh = Color(0xFF18233A),
    surfaceContainerHighest = Color(0xFF1E2A44),
    outline = Color(0xFF3A4A66),
    outlineVariant = Color(0xFF26334D),
)

private val Light = lightColorScheme(
    primary = Color(0xFF006874),
    secondary = Color(0xFF4A6363),
    tertiary = Color(0xFF8B5000),
    background = Color(0xFFF5F8FC),
    surface = Color(0xFFF5F8FC),
    surfaceContainer = Color(0xFFE9EEF5),
    surfaceContainerHigh = Color(0xFFE2E8F0),
    surfaceContainerHighest = Color(0xFFDCE3EC),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
        displayMedium = t.displayMedium.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Tabular figures for values that update many times per second. */
val NumberStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)

@Composable
fun ImuNavTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        typography = AppTypography,
        content = content,
    )
}
