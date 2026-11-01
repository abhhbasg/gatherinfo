package com.metrowin.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
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
}
