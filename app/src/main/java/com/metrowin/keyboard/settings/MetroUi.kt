package com.metrowin.keyboard.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.metrowin.keyboard.KeyboardApp
import com.metrowin.keyboard.R
import com.metrowin.keyboard.theme.Accents
import com.metrowin.keyboard.theme.ColorMath
import com.metrowin.keyboard.ui.Fonts
import com.metrowin.keyboard.ui.dp

/** The pieces of the app's screens that make them look like Windows Phone: a small upper-case app name, a big light title, flat buttons. */
object MetroUi {
    fun accent(c: Context): Int {
        val p = KeyboardApp.of(c).prefs
        return if (p.customAccent != 0) p.customAccent or 0xFF000000.toInt() else Accents.metro[p.accentIndex]
    }

    /** Android 15 draws apps edge to edge: keep the content out from under the system bars and the keyboard. */
    fun applyInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    fun fg(c: Context) = ContextCompat.getColor(c, R.color.metro_fg)
    fun subtle(c: Context) = ContextCompat.getColor(c, R.color.metro_subtle)
    fun tile(c: Context) = ContextCompat.getColor(c, R.color.metro_tile)

    /** The app name and the page title, as the top of a Windows Phone page. */
    fun header(c: Context, title: CharSequence): LinearLayout {
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(c.dp(24f).toInt(), c.dp(20f).toInt(), c.dp(24f).toInt(), c.dp(4f).toInt()) }
        box.addView(label(c, c.getString(R.string.app_name).uppercase(), 13f, subtle(c), Fonts.semibold(c)))
        val t = label(c, title.toString().lowercase(), 40f, fg(c), Fonts.light(c)).apply { id = R.id.header_title }
        t.setPadding(0, c.dp(2f).toInt(), 0, 0)
        box.addView(t)
        return box
    }

    fun label(c: Context, text: CharSequence, sp: Float, color: Int, face: android.graphics.Typeface): TextView =
        TextView(c).apply { this.text = text; textSize = sp; setTextColor(color); typeface = face; includeFontPadding = false }

    /** A flat rectangle button: filled with the accent, or outlined. */
    fun button(c: Context, text: CharSequence, filled: Boolean = true, onClick: () -> Unit): TextView = TextView(c).apply {
        this.text = text; textSize = 16f; typeface = Fonts.semibold(c); gravity = Gravity.CENTER
        val a = accent(c)
        setTextColor(if (filled) ColorMath.onColor(a) else fg(c))
        background = GradientDrawable().apply { if (filled) setColor(a) else { setColor(Color.TRANSPARENT); setStroke(c.dp(2f).toInt(), fg(c)) } }
        setPadding(c.dp(20f).toInt(), c.dp(12f).toInt(), c.dp(20f).toInt(), c.dp(12f).toInt())
        minWidth = c.dp(120f).toInt()
        setOnClickListener { onClick() }
    }

    fun space(c: Context, dp: Int) = View(c).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(dp.toFloat()).toInt()) }
}
