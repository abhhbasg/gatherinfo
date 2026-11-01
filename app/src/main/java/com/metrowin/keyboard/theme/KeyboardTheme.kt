package com.metrowin.keyboard.theme

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.metrowin.keyboard.Prefs

enum class ThemeStyle(val code: String) {
    /** Flat Windows Phone: square tiles, the accent on what is pressed. */
    CLASSIC("classic"),

    /** Frosted, rounded glass with light rims. */
    GLASS("glass"),

    /** Material You: tonal surfaces (the wallpaper's colours on Android 12+), rounded keys, a filled action key. */
    DROID("droid");

    companion object { fun from(code: String?) = entries.firstOrNull { it.code == code } ?: CLASSIC }
}

/** Everything the views need to paint the keyboard, resolved from the settings. Plain data, so it is cheap to compare. */
data class KeyboardColors(
    val style: ThemeStyle,
    val dark: Boolean,
    val background: Int,
    val bar: Int,
    val key: Int,
    val keyPressed: Int,
    val special: Int,
    val text: Int,
    val subtle: Int,
    val accent: Int,
    val onAccent: Int,
    val stroke: Int,
    val popup: Int,
    val cornerDp: Float,
    val strokeDp: Float,
    val translucent: Boolean,
)

object KeyboardTheme {
    /**
     * @param adaptive the colour sampled from the app under the keyboard (see ScreenColorService), or null
     * @param systemDark whether Android is in dark mode
     * @param pictureAccent the accent taken from the user's picture, or 0
     */
    fun resolve(context: Context, p: Prefs, systemDark: Boolean, adaptive: Int?, pictureAccent: Int = 0): KeyboardColors {
        val style = ThemeStyle.from(p.themeStyle)
        var dark = when (p.themeMode) { "dark" -> true; "light" -> false; else -> systemDark }
        val system = if (style == ThemeStyle.DROID && p.dynamicColor && p.customAccent == 0 && pictureAccent == 0) SystemTones.read(context) else null

        val accent = when {
            p.customAccent != 0 -> p.customAccent or 0xFF000000.toInt()
            pictureAccent != 0 -> pictureAccent
            system != null -> if (dark) system.accent200 else system.accent600
            else -> Accents.metro[p.accentIndex]
        }

        var bg = when {
            style == ThemeStyle.DROID && system != null -> if (dark) system.neutral900 else system.neutral50
            style == ThemeStyle.DROID -> if (dark) ColorMath.mix(0xFF101114.toInt(), accent, 0.10f) else ColorMath.mix(0xFFFBFBFD.toInt(), accent, 0.06f)
            style == ThemeStyle.GLASS -> if (dark) ColorMath.mix(0xFF0A1020.toInt(), accent, 0.22f) else ColorMath.mix(0xFFEAF1FA.toInt(), accent, 0.12f)
            else -> if (dark) (if (p.oledBlack) 0xFF000000.toInt() else 0xFF1B1B1B.toInt()) else 0xFFD6D6D6.toInt()
        }
        if (p.oledBlack && dark && style != ThemeStyle.CLASSIC) bg = ColorMath.darken(bg, 0.85f)
        if (adaptive != null) {
            // Follow the app under the keyboard: its colour, eased towards the theme so the keys keep their contrast.
            val tinted = ColorMath.mix(adaptive, if (ColorMath.isDark(adaptive)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), 0.12f)
            bg = tinted
            dark = ColorMath.isDark(tinted)
        }

        val text = ColorMath.onColor(bg)
        val subtle = ColorMath.withAlpha(text, 0xB0)
        val onAccent = ColorMath.onColor(accent)
        return when (style) {
            ThemeStyle.CLASSIC -> KeyboardColors(
                style, dark, bg, ColorMath.mix(bg, text, 0.04f),
                key = ColorMath.mix(bg, text, if (dark) 0.14f else 0.0f).let { if (dark) it else 0xFFF2F2F2.toInt() },
                keyPressed = accent, special = ColorMath.mix(bg, text, if (dark) 0.07f else 0.10f),
                text = text, subtle = subtle, accent = accent, onAccent = onAccent, stroke = 0,
                popup = ColorMath.mix(bg, text, 0.22f), cornerDp = 1f, strokeDp = 0f, translucent = false,
            )
            ThemeStyle.GLASS -> KeyboardColors(
                style, dark, ColorMath.withAlpha(bg, if (p.imageBackground) 0xB0 else 0xE6), ColorMath.withAlpha(bg, 0x30),
                key = ColorMath.withAlpha(text, if (dark) 0x26 else 0x30),
                keyPressed = ColorMath.withAlpha(accent, 0xC0), special = ColorMath.withAlpha(text, 0x14),
                text = text, subtle = subtle, accent = accent, onAccent = onAccent, stroke = ColorMath.withAlpha(text, 0x48),
                popup = ColorMath.withAlpha(ColorMath.mix(bg, text, 0.12f), 0xF2), cornerDp = 14f, strokeDp = 1f, translucent = true,
            )
            ThemeStyle.DROID -> {
                val keyColor = system?.let { if (dark) it.neutral800 else it.neutral0 } ?: ColorMath.mix(bg, text, if (dark) 0.10f else 0.0f).let { if (dark) it else ColorMath.lighten(bg, 0.6f) }
                val specialColor = system?.let { if (dark) it.accent2_700 else it.accent2_100 } ?: ColorMath.mix(bg, accent, if (dark) 0.28f else 0.22f)
                KeyboardColors(
                    style, dark, bg, bg, keyColor, ColorMath.mix(keyColor, accent, 0.35f), specialColor,
                    text, subtle, accent, onAccent, 0, ColorMath.mix(bg, text, 0.16f), cornerDp = 16f, strokeDp = 0f, translucent = false,
                )
            }
        }
    }

    fun isSystemDark(context: Context) =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}

/** The tonal colours Android 12+ makes from the wallpaper, or null on an older phone. */
class SystemTones(
    val accent200: Int, val accent600: Int, val accent2_100: Int, val accent2_700: Int,
    val neutral0: Int, val neutral50: Int, val neutral800: Int, val neutral900: Int,
) {
    companion object {
        fun read(c: Context): SystemTones? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
            return runCatching {
                SystemTones(
                    c.getColor(android.R.color.system_accent1_200), c.getColor(android.R.color.system_accent1_600),
                    c.getColor(android.R.color.system_accent2_100), c.getColor(android.R.color.system_accent2_700),
                    c.getColor(android.R.color.system_neutral1_0), c.getColor(android.R.color.system_neutral1_50),
                    c.getColor(android.R.color.system_neutral1_800), c.getColor(android.R.color.system_neutral1_900),
                )
            }.getOrNull()
        }
    }
}
