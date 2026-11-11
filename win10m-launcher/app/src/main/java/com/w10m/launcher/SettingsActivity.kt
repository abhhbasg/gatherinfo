package com.w10m.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowCompat
import java.io.File

/** Settings > Personalisation > Start, laid out the W10M way (uppercase page title, stacked controls, 12 epx margins). */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var p: Palette

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = Prefs(this)
        p = prefs.palette()
        window.statusBarColor = 0; window.navigationBarColor = 0
        build()
    }

    private fun build() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val m = dpi(Metro.PAGE_MARGIN)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(p.bg); addView(col); isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        setContentView(scroll)
        WindowCompat.getInsetsController(window, scroll).apply {
            isAppearanceLightStatusBars = !p.dark; isAppearanceLightNavigationBars = !p.dark
        }
        scroll.setOnApplyWindowInsetsListener { _, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars())
            col.setPadding(m, b.top + dpi(12f), m, b.bottom + dpi(24f)); ins
        }

        col += text(getString(R.string.settings_title).uppercase(), 15f, Metro.semibold(this), p.fg)
        col += text(getString(R.string.settings_start), 34f, Metro.light(this), p.fg).withTopMargin(4f)

        section(col, R.string.theme)
        col += combo(listOf(getString(R.string.dark), getString(R.string.light)), if (prefs.dark) 0 else 1) {
            prefs.dark = it == 0; recreate()
        }

        section(col, R.string.background)
        val bgNames = listOf(getString(R.string.bg_solid), getString(R.string.bg_behind), getString(R.string.bg_on_tiles))
        col += combo(bgNames, prefs.background.ordinal) {
            prefs.background = Background.entries[it]
            if (prefs.background != Background.SOLID && !File(filesDir, "wallpaper").exists()) pickImage()
        }
        col += button(getString(R.string.choose_picture)) { pickImage() }.withTopMargin(12f)

        section(col, R.string.transparency)
        col += MetroSlider(this, p).apply { value = prefs.transparency; onChange = { prefs.transparency = it } }

        section(col, R.string.show_more_tiles)
        col += MetroToggle(this, p).apply { checked = prefs.moreTiles; onChange = { prefs.moreTiles = it } }

        section(col, R.string.mono_icons)
        col += MetroToggle(this, p).apply { checked = prefs.monoIcons; onChange = { prefs.monoIcons = it } }

        section(col, R.string.accent)
        col += AccentGrid()

        section(col, R.string.badges)
        col += button(getString(R.string.grant_badges)) { go(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

        section(col, R.string.default_home)
        col += button(getString(R.string.set_default_home)) { go(Intent(Settings.ACTION_HOME_SETTINGS)) }
    }

    private fun go(i: Intent) = try { startActivity(i) } catch (_: Exception) {}

    private fun pickImage() {
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        @Suppress("DEPRECATION") try { startActivityForResult(i, REQ_IMAGE) } catch (_: Exception) {}
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK || uri == null) return
        try {
            contentResolver.openInputStream(uri)?.use { input -> File(filesDir, "wallpaper").outputStream().use { input.copyTo(it) } }
            if (prefs.background == Background.SOLID) prefs.background = Background.BEHIND_TILES
            recreate()
        } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- building blocks

    private operator fun LinearLayout.plusAssign(v: View) = addView(v)

    private fun View.withTopMargin(dp: Float): View {
        layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(dp) }
        return this
    }

    private fun text(s: String, size: Float, tf: android.graphics.Typeface, color: Int) = TextView(this).apply {
        text = s; textSize = size; typeface = tf; setTextColor(color); includeFontPadding = false
    }

    private fun section(col: LinearLayout, res: Int) {
        col += text(getString(res), 15f, Metro.regular(this), p.fg).withTopMargin(24f).also { it.setPadding(0, 0, 0, dpi(8f)) }
    }

    /** UWP Button: 32 high, ChromeMedium-ish translucent fill, 12 epx side padding. */
    private fun button(label: String, onClick: () -> Unit): View = TextView(this).apply {
        text = label; textSize = 15f; typeface = Metro.regular(this@SettingsActivity); setTextColor(p.fg)
        gravity = Gravity.CENTER
        setPadding(dpi(12f), 0, dpi(12f), 0)
        background = GradientDrawable().apply { setColor(if (p.dark) 0x33FFFFFF else 0x33000000) }
        minHeight = dpi(32f); height = dpi(32f)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(32f))
        setOnClickListener { onClick() }
    }

    /** UWP ComboBox: 32 high, 2 epx border, chevron on the right; opens a MenuFlyout. */
    private fun combo(options: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        var sel = selected
        val v = object : View(this) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(15f); typeface = Metro.regular(context); color = p.fg }
            val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.3f); color = p.fg }
            override fun onDraw(c: Canvas) {
                c.drawText(options[sel], dp(10f), height / 2f - (tp.ascent() + tp.descent()) / 2, tp)
                val cx = width - dp(18f); val cy = height / 2f
                c.drawLine(cx - dp(5f), cy - dp(2.5f), cx, cy + dp(2.5f), lp)
                c.drawLine(cx, cy + dp(2.5f), cx + dp(5f), cy - dp(2.5f), lp)
            }
        }
        v.background = GradientDrawable().apply { setColor(if (p.dark) 0x66000000 else 0x66FFFFFF); setStroke(dpi(2f), p.inputBorder) }
        v.layoutParams = LinearLayout.LayoutParams(-1, dpi(32f))
        v.isClickable = true
        v.setOnClickListener {
            MetroMenu.show(v, p, options.mapIndexed { i, s -> s to { sel = i; v.invalidate(); onPick(i) } })
        }
        return v
    }

    /** 48-colour accent picker, 6 per row, 4 epx gaps; the current colour gets a 2 epx outline. */
    private inner class AccentGrid : View(this) {
        private val fill = Paint()
        private val ring = Paint().apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
        private val cols = 6
        private val gap = dp(4f)
        private fun size() = (width - (cols - 1) * gap) / cols

        init {
            isClickable = true
            setOnTouchListener { _, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_UP) {
                    val s = size()
                    val i = (e.y / (s + gap)).toInt() * cols + (e.x / (s + gap)).toInt()
                    Metro.ACCENTS.getOrNull(i)?.let { prefs.accent = it; recreate() }
                }
                true
            }
        }

        override fun onMeasure(w: Int, h: Int) {
            val ww = MeasureSpec.getSize(w)
            val s = (ww - (cols - 1) * gap) / cols
            val rows = (Metro.ACCENTS.size + cols - 1) / cols
            setMeasuredDimension(ww, (rows * s + (rows - 1) * gap).toInt())
        }

        override fun onDraw(c: Canvas) {
            val s = size()
            Metro.ACCENTS.forEachIndexed { i, col ->
                val x = (i % cols) * (s + gap); val y = (i / cols) * (s + gap)
                fill.color = col
                c.drawRect(x, y, x + s, y + s, fill)
                if (col == prefs.accent) {
                    ring.color = if (p.dark) Color.WHITE else Color.BLACK
                    c.drawRect(x + dp(1f), y + dp(1f), x + s - dp(1f), y + s - dp(1f), ring)
                }
            }
        }
    }

    companion object { private const val REQ_IMAGE = 7 }
}
