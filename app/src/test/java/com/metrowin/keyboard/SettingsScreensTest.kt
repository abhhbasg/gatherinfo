package com.metrowin.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.metrowin.keyboard.settings.SettingsActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Opens every screen of the settings: a screen that crashes when opened (a bad preference file) is caught here, not on the phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreensTest {
    private val screens = listOf(
        "Languages", "Appearance", "Size", "Typing", "Suggestions", "Feedback", "Clipboard", "Gif", "Translate", "Voice", "Launcher", "Backup", "Update", "About", "Projects",
    ).map { "com.metrowin.keyboard.settings.${it}Fragment" }

    @Test fun calibrationScreenOpens() {
        val a = Robolectric.buildActivity(com.metrowin.keyboard.settings.CalibrationActivity::class.java).setup().get()
        assertNotNull(a.findViewById<View>(R.id.header_title))
        a.finish()
    }

    @Test fun everyScreenOpens() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val out = File("build/shots").apply { mkdirs() }
        for (name in screens) {
            val fragment = activity.supportFragmentManager.fragmentFactory.instantiate(activity.classLoader, name)
            activity.supportFragmentManager.beginTransaction().replace(R.id.settings_container, fragment).commitNow()
            // Most screens are preference lists; the projects page is its own layout.
            if (fragment is PreferenceFragmentCompat) {
                val list = fragment.listView
                assertNotNull(name, list)
                assertTrue("$name has rows", list.adapter!!.itemCount > 0)
            } else assertNotNull(name, fragment.view)
            // Lay the list out and draw it, so a row that fails to bind shows up too.
            val root = activity.findViewById<View>(R.id.settings_root)
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 2000)
            val bmp = Bitmap.createBitmap(1080, 2000, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "settings-${name.substringAfterLast('.')}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
    }

    @Test fun theGifScreenShowsOnlyWhatTheChosenServiceUses() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<KeyboardApp>()
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        fun open(source: String): PreferenceFragmentCompat {
            app.prefs.putString(K.GIF_SOURCE, source)
            val f = activity.supportFragmentManager.fragmentFactory.instantiate(activity.classLoader, "com.metrowin.keyboard.settings.GifFragment") as PreferenceFragmentCompat
            activity.supportFragmentManager.beginTransaction().replace(R.id.settings_container, f).commitNow()
            return f
        }
        fun visible(f: PreferenceFragmentCompat, key: String) = f.findPreference<Preference>(key)!!.isVisible
        for (s in listOf("giphy", "tenor", "klipy", "imgur")) {
            val f = open(s)
            assertTrue("$s button", visible(f, "gif_get_key")); assertTrue("$s key", visible(f, "gif_api_key")); assertTrue("$s rating", visible(f, "gif_rating"))
            assertFalse("$s folder", visible(f, "gif_folder_pick"))
        }
        open("folder").let { assertFalse(visible(it, "gif_get_key")); assertFalse(visible(it, "gif_api_key")); assertFalse(visible(it, "gif_rating")); assertTrue(visible(it, "gif_folder_pick")) }
        open("custom").let { assertFalse(visible(it, "gif_get_key")); assertTrue(visible(it, "gif_rating")); assertTrue(visible(it, "gif_custom_url")) }
    }
}
