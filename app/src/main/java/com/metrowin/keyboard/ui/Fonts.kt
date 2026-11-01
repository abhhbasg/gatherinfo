package com.metrowin.keyboard.ui

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.metrowin.keyboard.R

/** Open Sans (SIL OFL), the stand-in for Segoe WP, which is proprietary: light for the big things, regular and semibold for the small. */
object Fonts {
    @Volatile private var loaded: Triple<Typeface, Typeface, Typeface>? = null

    private fun load(c: Context): Triple<Typeface, Typeface, Typeface> = loaded ?: synchronized(this) {
        loaded ?: Triple(
            ResourcesCompat.getFont(c, R.font.opensans_light) ?: Typeface.DEFAULT,
            ResourcesCompat.getFont(c, R.font.opensans_regular) ?: Typeface.DEFAULT,
            ResourcesCompat.getFont(c, R.font.opensans_semibold) ?: Typeface.DEFAULT_BOLD,
        ).also { loaded = it }
    }

    fun light(c: Context) = load(c).first
    fun regular(c: Context) = load(c).second
    fun semibold(c: Context) = load(c).third
}
