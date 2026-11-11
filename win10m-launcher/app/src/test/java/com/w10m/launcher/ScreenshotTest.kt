package com.w10m.launcher

import android.app.Activity
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertNotNull
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
 * Renders the launcher's screens off-screen at 1080x1920 (360x640 dp @ 3x, the W10M reference size)
 * so the layout can be checked against the spec. Output goes to the build/screens folder.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
class ScreenshotTest {

    private val out = File(System.getProperty("user.dir"), "build/screens").apply { mkdirs() }

    private fun render(v: View, name: String) {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

    private fun app(a: Activity, pkg: String, cls: String, label: String, recent: Boolean = false) =
        AppEntry(ComponentName(pkg, cls), label, ColorDrawable(Color.GRAY), null, if (recent) System.currentTimeMillis() else 0L,
            IconPack.forApp(a, pkg, cls))

    private fun sampleApps(a: Activity) = listOf(
        app(a, a.packageName, "x.DialerActivity", "电话"),
        app(a, "com.android.contacts", "c", "人脉"),
        app(a, "com.android.mms", "c", "消息"),
        app(a, a.packageName, "x.BrowserActivity", "浏览器"),
        app(a, "com.android.camera", "c", "相机"),
        app(a, "com.android.settings", "c", "设置"),
        app(a, "com.android.gallery3d", "c", "照片"),
        app(a, "com.tencent.mm", "c", "微信"),
        app(a, "com.eg.android.AlipayGphone", "c", "支付宝"),
        app(a, "com.android.deskclock", "c", "闹钟和时钟"),
        app(a, "com.android.calculator2", "c", "计算器"),
        app(a, "com.jingdong.app.mall", "c", "京东"),
        app(a, "com.android.email", "c", "邮件"),
        app(a, "tv.danmaku.bili", "c", "哔哩哔哩"),
        app(a, "com.netease.cloudmusic", "c", "网易云音乐"),
        app(a, "com.sina.weibo", "c", "微博"),
        app(a, "com.spotify.music", "c", "Spotify"),
        app(a, "com.whatsapp", "c", "WhatsApp"),
        app(a, "org.telegram.messenger", "c", "Telegram"),
        app(a, "com.autonavi.minimap", "c", "高德地图"),
        app(a, "com.android.vending", "c", "Play 商店"),
        app(a, "com.xingin.xhs", "c", "小红书"),
        app(a, "com.zhihu.android", "c", "知乎"),
        app(a, "com.ss.android.ugc.aweme", "c", "抖音"),
    )

    private fun startScreen(name: String, columns: Int, edit: Boolean = false, accent: Int = Metro.DEFAULT_ACCENT,
                            dark: Boolean = true, nav: Boolean = true, live: Boolean = false, center: Boolean = false) {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val look = TileLook(Palette(dark, accent), Background.SOLID, 0, true)
        val apps = sampleApps(a)
        val sizes = listOf(TileSize.MEDIUM, TileSize.MEDIUM, TileSize.MEDIUM, TileSize.MEDIUM, TileSize.SMALL, TileSize.SMALL,
            TileSize.WIDE, TileSize.MEDIUM, TileSize.MEDIUM, TileSize.SMALL, TileSize.SMALL, TileSize.SMALL, TileSize.SMALL,
            TileSize.MEDIUM, TileSize.MEDIUM, TileSize.MEDIUM)
        val grid = StartGrid(a, look)
        grid.columns = columns
        grid.topInset = a.dpi(24f)
        grid.bottomInset = if (nav) a.dpi(NavBar.HEIGHT) else 0
        val tiles = ArrayList<TileView>()
        sizes.forEachIndexed { i, s ->
            tiles += TileView(a, TileSpec("t$i", TileKind.APP, apps[i].key, s), look).also { it.app = apps[i] }
        }
        tiles[2].badge = 3
        tiles.add(4, TileView(a, TileSpec("cal", TileKind.CALENDAR, null, TileSize.WIDE), look))
        grid.setTiles(tiles)
        val content = FrameLayout(a).apply { setBackgroundColor(look.palette.bg); addView(grid) }
        Prefs(a).apply { navKeys = nav; this.dark = dark; this.accent = accent }
        val shell = Shell(a, Prefs(a), look.palette, overlay = true)
        shell.setContent(content)
        val root = shell.root
        idle()
        if (live) {
            // Messaging and WeChat tiles slide to their notification face.
            tiles[2].note = Note("李雷", "晚上七点老地方见，别忘了带上相机。", 0)
            tiles[8].note = Note("家庭群", "妈妈: 周末回家吃饭吗？", 0); tiles[8].badge = 2
            tiles[2].advance(); tiles[8].advance(); idle()
        }
        if (edit) { grid.enterEdit(tiles[1]); idle() }
        if (center) {
            BadgeService.connected = true
            BadgeService.all = listOf(
                Note("李雷", "晚上七点老地方见，别忘了带上相机。", System.currentTimeMillis(), "k1", "com.android.mms"),
                Note("家庭群", "妈妈: 周末回家吃饭吗？", System.currentTimeMillis(), "k2", "com.tencent.mm"),
                Note("系统更新", "新版本已下载完成，点按以安装。", System.currentTimeMillis(), "k3", "com.android.settings"),
            )
            shell.center!!.setInsets(a.dpi(24f), a.dpi(NavBar.HEIGHT))
            shell.center!!.open(); idle()
        }
        render(root, name)
        BadgeService.all = emptyList()
    }

    @Test fun start6() = startScreen("start_6col", Metro.COLUMNS_DEFAULT)
    @Test fun start8() = startScreen("start_8col", Metro.COLUMNS_MORE, accent = 0xFFE81123.toInt(), nav = false)
    @Test fun startEdit() = startScreen("start_edit", Metro.COLUMNS_DEFAULT, edit = true)
    @Test fun startLight() = startScreen("start_light", Metro.COLUMNS_DEFAULT, accent = 0xFF10893E.toInt(), dark = false)
    @Test fun startLive() = startScreen("start_live", Metro.COLUMNS_DEFAULT, live = true)
    @Test fun actionCenter() = startScreen("action_center", Metro.COLUMNS_DEFAULT, center = true)

    @Test fun messaging() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_SMS)
        val now = System.currentTimeMillis()
        val cursor = org.robolectric.fakes.RoboCursor().apply {
            setColumnNames(listOf("thread_id", "address", "body", "date", "type", "read"))
            setResults(arrayOf(
                arrayOf<Any>(1L, "13800138000", "好的，那就周六上午十点，在地铁站 B 口见。", now - 60_000, 1, 0),
                arrayOf<Any>(1L, "13800138000", "周六上午可以吗？我十点以后都有空。", now - 120_000, 2, 1),
                arrayOf<Any>(1L, "13800138000", "这周末要不要一起去爬山？", now - 300_000, 1, 1),
                arrayOf<Any>(2L, "10086", "您本月已使用流量 12.5GB，剩余 7.5GB。", now - 3_600_000, 1, 1),
                arrayOf<Any>(3L, "95588", "您尾号 1234 的账户收入 5,000.00 元。", now - 86_400_000, 1, 1),
            ))
        }
        shadowOf(app.contentResolver).setCursor(android.net.Uri.parse("content://sms"), cursor)
        val a = Robolectric.buildActivity(MessagingActivity::class.java).setup().get()
        idle()
        val content = a.findViewById<View>(android.R.id.content)
        render(content, "messaging_list")
        // Open the first conversation.
        fun firstClickable(v: View): View? {
            if (v.javaClass.simpleName == "ThreadRow") return v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) firstClickable(v.getChildAt(i))?.let { return it }
            return null
        }
        cursor.moveToPosition(-1)
        firstClickable(content)!!.performClick(); idle()
        render(content, "messaging_thread")
    }

    @Test fun appList() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val p = Palette(true, Metro.DEFAULT_ACCENT)
        val page = AppListPage(a, p, TileLook(p, Background.SOLID, 0, true))
        page.setApps(sampleApps(a).sortedBy { it.label })
        page.setInsets(a.dpi(24f), a.dpi(NavBar.HEIGHT))
        val content = FrameLayout(a).apply { setBackgroundColor(Color.BLACK); addView(page) }
        val shell = Shell(a, Prefs(a), p, overlay = true)
        shell.setContent(content)
        val root = shell.root
        idle()
        render(root, "app_list")
    }

    @Test fun dialer() {
        val a = Robolectric.buildActivity(DialerActivity::class.java).setup().get()
        idle()
        render(a.findViewById(android.R.id.content), "dialer")
    }

    @Test fun browser() {
        val a = Robolectric.buildActivity(BrowserActivity::class.java).setup().get()
        idle()
        render(a.findViewById(android.R.id.content), "browser")
    }

    @Test fun everyMappedIconParses() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        a.assets.open("icons/map.tsv").bufferedReader().readLines().filter { it.isNotBlank() }.forEach { l ->
            val pkg = l.split('\t')[0]
            val g = IconPack.forApp(a, pkg, "x")
            assertNotNull(pkg, g)
            g!!.draw(Canvas(Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)), 0f, 0f, 48f, android.graphics.Paint())
        }
    }
}
