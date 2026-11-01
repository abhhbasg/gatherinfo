package com.metrowin.keyboard.integration

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.metrowin.keyboard.K
import com.metrowin.keyboard.Prefs
import com.metrowin.keyboard.theme.Accents
import org.json.JSONObject

/**
 * How the keyboard works together with MetroWin Launcher.
 *
 *  - It knows when it is typing inside the launcher (its search box), where it turns off correction and learning: a search for
 *    an app is not a sentence, and nothing typed there should become a "learned" word.
 *  - It can take the launcher's look: from a theme file exported by the launcher (`metrowin-theme`), or from a broadcast the
 *    launcher sends when the user changes its theme (see docs/LAUNCHER_INTEGRATION.md).
 */
object LauncherLink {
    const val LAUNCHER_PACKAGE = "com.metrowin.launcher"
    const val ACTION_APPLY_THEME = "com.metrowin.keyboard.action.APPLY_THEME"
    const val EXTRA_STYLE = "style"       // classic | glass | droid
    const val EXTRA_MODE = "mode"         // dark | light | system
    const val EXTRA_ACCENT = "accent"     // ARGB int
    const val EXTRA_ACCENT_INDEX = "accent_index" // 0..19, the launcher's own list

    fun isLauncher(packageName: String?) = packageName == LAUNCHER_PACKAGE

    fun isInstalled(c: Context): Boolean = runCatching { c.packageManager.getPackageInfo(LAUNCHER_PACKAGE, 0); true }.getOrDefault(false)

    fun launchIntent(c: Context): Intent? = c.packageManager.getLaunchIntentForPackage(LAUNCHER_PACKAGE)

    data class Look(val style: String?, val mode: String?, val accentIndex: Int?, val customAccent: Int?)

    /** Reads a `metrowin-theme` file written by the launcher. Returns null if it is not one (or of a newer version). */
    fun parseThemeFile(json: String): Look? = runCatching {
        val o = JSONObject(json)
        if (o.optString("format") != "metrowin-theme" || o.optInt("version", Int.MAX_VALUE) > 1) return null
        val t = o.getJSONObject("theme")
        Look(
            style = t.optString("themeStyle").takeIf { it in setOf("classic", "glass", "droid") },
            mode = t.optString("themeMode").takeIf { it in setOf("dark", "light", "system") },
            accentIndex = if (t.has("accentIndex")) t.optInt("accentIndex").takeIf { it in Accents.metro.indices } else null,
            customAccent = if (t.has("customAccent")) t.optInt("customAccent").takeIf { it != 0 } else null,
        )
    }.getOrNull()

    /** Stores [look] in the keyboard's settings. A launcher with its own custom colour wins over its colour list. */
    fun apply(p: Prefs, look: Look) {
        val e = p.sp.edit()
        look.style?.let { e.putString(K.THEME_STYLE, it) }
        look.mode?.let { e.putString(K.THEME_MODE, it) }
        when {
            look.customAccent != null -> { e.putInt(K.CUSTOM_ACCENT, look.customAccent or 0xFF000000.toInt()); e.putBoolean(K.DYNAMIC_COLOR, false) }
            look.accentIndex != null -> { e.putInt(K.ACCENT_INDEX, look.accentIndex); e.putInt(K.CUSTOM_ACCENT, 0); e.putBoolean(K.DYNAMIC_COLOR, false) }
        }
        e.apply()
    }

    /** The look sent by a broadcast, or null when there is nothing valid in it. Extras are checked, not trusted. */
    fun fromIntent(i: Intent): Look? {
        val style = i.getStringExtra(EXTRA_STYLE)?.takeIf { it in setOf("classic", "glass", "droid") }
        val mode = i.getStringExtra(EXTRA_MODE)?.takeIf { it in setOf("dark", "light", "system") }
        val index = if (i.hasExtra(EXTRA_ACCENT_INDEX)) i.getIntExtra(EXTRA_ACCENT_INDEX, -1).takeIf { it in Accents.metro.indices } else null
        val custom = if (i.hasExtra(EXTRA_ACCENT)) i.getIntExtra(EXTRA_ACCENT, 0).takeIf { it != 0 } else null
        if (style == null && mode == null && index == null && custom == null) return null
        return Look(style, mode, index, custom)
    }
}
