package com.metrowin.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import com.metrowin.keyboard.translate.Translator
import com.metrowin.keyboard.ui.Slot
import com.metrowin.keyboard.ui.SuggestionBar
import com.metrowin.keyboard.ui.TranslatePanel
import com.metrowin.keyboard.ui.TutorialPanel
import com.metrowin.keyboard.tutorial.Tutorial
import androidx.test.core.app.ApplicationProvider
import com.metrowin.keyboard.layout.KeyboardBuilder
import com.metrowin.keyboard.theme.KeyboardTheme
import com.metrowin.keyboard.ui.KeyboardView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Draws the keyboard in each look to a PNG (build/shots), so a change of look can be seen, and checks it draws at all. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShotTest {
    @Test fun drawsEveryLook() {
        val app = ApplicationProvider.getApplicationContext<KeyboardApp>()
        val out = File("build/shots").apply { mkdirs() }
        for (style in listOf("classic", "glass", "droid")) for (mode in listOf("dark", "light")) {
            app.prefs.sp.edit().clear().putString(K.THEME_STYLE, style).putString(K.THEME_MODE, mode).commit()
            val kb = KeyboardView(app)
            kb.setColors(KeyboardTheme.resolve(app, app.prefs, false, null))
            kb.setPage(KeyboardBuilder.letters(app.layouts.get("it")!!, true, true, true))
            kb.setLanguageName("Italiano")
            kb.setMetrics(100, 3, 100, "center")
            kb.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            kb.layout(0, 0, 1080, kb.measuredHeight)
            val bmp = Bitmap.createBitmap(1080, kb.measuredHeight, Bitmap.Config.ARGB_8888)
            kb.draw(Canvas(bmp))
            File(out, "$style-$mode.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(bmp.getPixel(5, 5) != 0 || bmp.getPixel(540, 100) != 0)
        }
    }

    private fun render(v: View, w: Int, h: Int, file: File) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun drawsTheWholeKeyboardAndPanels() {
        val app = ApplicationProvider.getApplicationContext<KeyboardApp>()
        val out = File("build/shots").apply { mkdirs() }
        for (style in listOf("classic", "glass", "droid")) {
            app.prefs.sp.edit().clear().putString(K.THEME_STYLE, style).putString(K.THEME_MODE, "dark").commit()
            val c = KeyboardTheme.resolve(app, app.prefs, true, null)
            val kb = KeyboardView(app)
            kb.setColors(c)
            kb.setPage(KeyboardBuilder.letters(app.layouts.get("it")!!, false, true, true))
            kb.setLanguageName("Italiano"); kb.setShift(0)
            val bar = SuggestionBar(app).also { it.setColors(c); it.setSlots(listOf(Slot("caio", literal = true), Slot("ciao", best = true), Slot("cairo"))); it.setBadge(true) }
            val col = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
            col.addView(bar, LinearLayout.LayoutParams(1080, 150))
            col.addView(kb, LinearLayout.LayoutParams(1080, 900))
            col.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1050, View.MeasureSpec.EXACTLY))
            col.layout(0, 0, 1080, 1050)
            // Finger down on "g": the colour preview appears above it.
            val g = kb.letterCenters().getValue('g')
            val t = SystemClock.uptimeMillis()
            kb.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, g.x, g.y, 0))
            val bmp = Bitmap.createBitmap(1080, 1050, Bitmap.Config.ARGB_8888)
            col.draw(Canvas(bmp))
            File(out, "full-$style.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        app.prefs.sp.edit().clear().putString(K.THEME_STYLE, "classic").commit()
        val c = KeyboardTheme.resolve(app, app.prefs, true, null)
        // Chinese: the row of words for the Pinyin typed, and the two pages of symbols.
        val zh = app.layouts.get("zh")!!
        val pages = mapOf(
            "zh-pinyin" to KeyboardBuilder.letters(zh, false, false, true),
            "zh-symbols-1" to KeyboardBuilder.symbols(com.metrowin.keyboard.layout.Page.SYMBOLS_1, false, zh),
            "symbols-1" to KeyboardBuilder.symbols(com.metrowin.keyboard.layout.Page.SYMBOLS_1, false, app.layouts.get("en")),
            "symbols-2" to KeyboardBuilder.symbols(com.metrowin.keyboard.layout.Page.SYMBOLS_2, false, app.layouts.get("en")),
        )
        for ((name, page) in pages) {
            val kb = KeyboardView(app)
            kb.setColors(c); kb.setPage(page); kb.setLanguageName(zh.name)
            val bar = SuggestionBar(app).also { it.setColors(c) }
            bar.setSlots(listOf("你好吗", "你好", "你", "尼", "泥", "拟", "逆", "妮", "腻").mapIndexed { i, w -> Slot(w, best = i == 0) }, row = true)
            val col = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
            col.addView(bar, LinearLayout.LayoutParams(1080, 150))
            col.addView(kb, LinearLayout.LayoutParams(1080, 780))
            render(col, 1080, 930, File(out, "$name.png"))
        }
        val tut = TutorialPanel(app).also { it.setColors(c); it.open(Tutorial.startIndex(1)) }
        render(tut, 1080, 760, File(out, "panel-tutorial.png"))
        val tr = TranslatePanel(app, Translator(app.prefs), app.prefs).also { it.setColors(c) }
        render(tr, 1080, 760, File(out, "panel-translate.png"))
    }
}
