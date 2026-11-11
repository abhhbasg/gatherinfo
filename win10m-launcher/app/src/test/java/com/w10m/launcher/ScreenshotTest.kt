package com.w10m.launcher

import android.app.Activity
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * Renders the Start screen, edit mode and the app list off-screen at 1080x2340 (360x780 dp @ 3x)
 * so the layout can be compared against W10M reference screenshots. Output: the build/screens folder
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class ScreenshotTest {

    private val out = File(System.getProperty("user.dir"), "build/screens").apply { mkdirs() }

    /** Stand-in white glyph so the tiles look like W10M monochrome icons. */
    private fun glyph(a: Activity, shape: Int): Drawable {
        val s = 108
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f }
        when (shape % 4) {
            0 -> c.drawCircle(54f, 54f, 16f, p)
            1 -> c.drawRect(38f, 40f, 70f, 68f, p)
            2 -> { c.drawLine(38f, 70f, 54f, 38f, p); c.drawLine(54f, 38f, 70f, 70f, p); c.drawLine(38f, 70f, 70f, 70f, p) }
            else -> { c.drawRoundRect(42f, 36f, 66f, 72f, 4f, 4f, p); c.drawLine(50f, 66f, 58f, 66f, p) }
        }
        return BitmapDrawable(a.resources, b)
    }

    private val names = listOf("电话", "人脉", "消息", "Edge", "相机", "设置", "照片", "Groove 音乐", "Outlook 邮件",
        "地图", "天气", "商店", "OneDrive", "闹钟和时钟", "计算器", "Xbox")

    private fun render(v: View, name: String) {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

    private fun startScreen(name: String, columns: Int, edit: Boolean, accent: Int = Metro.DEFAULT_ACCENT, dark: Boolean = true) {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val look = TileLook(Palette(dark, accent), Background.SOLID, 0, true)
        val apps = names.mapIndexed { i, n -> AppEntry(ComponentName("p$i", "c$i"), n, glyph(a, i), glyph(a, i), 0L) }
        val sizes = listOf(TileSize.MEDIUM, TileSize.MEDIUM, TileSize.MEDIUM, TileSize.MEDIUM, TileSize.SMALL, TileSize.SMALL,
            TileSize.WIDE, TileSize.MEDIUM, TileSize.MEDIUM, TileSize.SMALL, TileSize.SMALL, TileSize.SMALL, TileSize.SMALL, TileSize.MEDIUM, TileSize.MEDIUM)
        val grid = StartGrid(a, look)
        grid.columns = columns
        grid.topInset = a.dpi(24f)
        val tiles = ArrayList<TileView>()
        sizes.forEachIndexed { i, s ->
            tiles += TileView(a, TileSpec("t$i", TileKind.APP, apps[i].key, s), look).also { it.app = apps[i]; if (i == 2) it.badge = 3 }
        }
        tiles.add(4, TileView(a, TileSpec("cal", TileKind.CALENDAR, null, TileSize.WIDE), look))
        grid.setTiles(tiles)
        val root = FrameLayout(a).apply { setBackgroundColor(look.palette.bg); addView(grid) }
        a.setContentView(root)
        idle()
        if (edit) { grid.enterEdit(tiles[1]); idle() }
        render(root, name)
    }

    @Test fun start6() = startScreen("start_6col", Metro.COLUMNS_DEFAULT, false)
    @Test fun start8() = startScreen("start_8col", Metro.COLUMNS_MORE, false, 0xFFE81123.toInt())
    @Test fun startEdit() = startScreen("start_edit", Metro.COLUMNS_DEFAULT, true)
    @Test fun startLight() = startScreen("start_light", Metro.COLUMNS_DEFAULT, false, 0xFF10893E.toInt(), false)

    @Test fun appList() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val p = Palette(true, Metro.DEFAULT_ACCENT)
        val page = AppListPage(a, p) { true }
        val en = listOf("Alarms & Clock", "Calculator", "Camera", "Edge", "Groove Music", "Maps", "Messaging", "OneDrive", "Outlook Mail", "People", "Phone", "Photos")
        page.setApps(en.mapIndexed { i, n -> AppEntry(ComponentName("p$i", "c$i"), n, glyph(a, i), glyph(a, i), if (i < 2) System.currentTimeMillis() else 0L) })
        page.setInsets(a.dpi(24f), 0)
        val root = FrameLayout(a).apply { setBackgroundColor(Color.BLACK); addView(page) }
        a.setContentView(root)
        idle()
        render(root, "app_list")
    }
}
