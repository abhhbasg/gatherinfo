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

    @Test fun calculator() {
        val a = Robolectric.buildActivity(CalculatorActivity::class.java).setup().get()
        idle()
        render(a.findViewById(android.R.id.content), "calculator")
    }

    @Test fun calendar() {
        val a = Robolectric.buildActivity(CalendarActivity::class.java).setup().get()
        idle()
        render(a.findViewById(android.R.id.content), "calendar")
    }

    @Test fun people() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_CONTACTS)
        val cursor = org.robolectric.fakes.RoboCursor().apply {
            setColumnNames(listOf("_id", "display_name", "photo_thumb_uri"))
            setResults(listOf("Alice Chen", "陈晨", "Bob Miller", "韩梅梅", "李雷", "刘洋", "Mom", "王芳", "张伟", "赵敏")
                .mapIndexed { i, n -> arrayOf<Any?>(i.toLong(), n, null) }.toTypedArray())
        }
        shadowOf(app.contentResolver).setCursor(android.provider.ContactsContract.Contacts.CONTENT_URI, cursor)
        val a = Robolectric.buildActivity(PeopleActivity::class.java).setup().get()
        idle()
        render(a.findViewById(android.R.id.content), "people")
    }

    @Test fun recorder() {
        val a = Robolectric.buildActivity(RecorderActivity::class.java).setup().get()
        idle()
        render(a.findViewById(android.R.id.content), "recorder")
    }

    @Test fun music() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_MEDIA_AUDIO)
        val cursor = org.robolectric.fakes.RoboCursor().apply {
            setColumnNames(listOf("_id", "title", "artist", "album", "album_id", "duration"))
            setResults(arrayOf(
                arrayOf<Any>(1L, "晴天", "周杰伦", "叶惠美", 1L, 269_000L),
                arrayOf<Any>(2L, "稻香", "周杰伦", "魔杰座", 2L, 223_000L),
                arrayOf<Any>(3L, "Viva La Vida", "Coldplay", "Viva La Vida", 3L, 242_000L),
                arrayOf<Any>(4L, "Yellow", "Coldplay", "Parachutes", 4L, 266_000L),
                arrayOf<Any>(5L, "后来", "刘若英", "我等你", 5L, 341_000L),
                arrayOf<Any>(6L, "Shape of You", "Ed Sheeran", "÷", 6L, 233_000L),
            ))
        }
        shadowOf(app.contentResolver).setCursor(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor)
        val a = Robolectric.buildActivity(MusicActivity::class.java).setup().get()
        idle()
        render(a.findViewById(android.R.id.content), "music")
    }

    @Test fun systemSettings() {
        val a = Robolectric.buildActivity(SystemSettingsActivity::class.java).setup().get()
        idle()
        render(a.findViewById(android.R.id.content), "settings")
    }

    private fun clockTab(tab: Int, name: String, setup: (android.content.Context) -> Unit = {}) {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        app.getSharedPreferences("w10m_clock", 0).edit().putInt("tab", tab).commit()
        setup(app)
        val a = Robolectric.buildActivity(ClockActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        render(a.findViewById(android.R.id.content), name)
    }

    @Test fun clockAlarms() = clockTab(0, "clock_alarms") { c ->
        c.getSharedPreferences("w10m_alarms", 0).edit().putString("list", org.json.JSONArray().apply {
            put(Alarm(1, 6, 30, "起床", 0b0111110, true).toJson())
            put(Alarm(2, 8, 0, "", 0b1000001, false).toJson())
            put(Alarm(3, 22, 45, "吃药", 0b1111111, true).toJson())
        }.toString()).commit()
    }
    @Test fun clockWorld() = clockTab(1, "clock_world") { c ->
        c.getSharedPreferences("w10m_clock", 0).edit().putString("zones", "Asia/Shanghai,Europe/London,America/New_York,Asia/Tokyo").commit()
    }
    @Test fun clockTimer() = clockTab(2, "clock_timer") { c ->
        c.getSharedPreferences("w10m_clock", 0).edit().putLong("timer_total", 300_000).putLong("timer_left", 187_000).putLong("timer_end", 0).commit()
    }
    @Test fun clockStopwatch() = clockTab(3, "clock_stopwatch") { c ->
        c.getSharedPreferences("w10m_clock", 0).edit().putLong("sw_acc", 83_450).putLong("sw_start", 0).putString("sw_laps", "21300,44120,63900").commit()
    }

    @Test fun alarmEditor() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        app.getSharedPreferences("w10m_clock", 0).edit().putInt("tab", 0).commit()
        val a = Robolectric.buildActivity(ClockActivity::class.java).setup().get()
        idle()
        // Tap "+" in the command bar.
        fun find(v: View): View? {
            if (v is GlyphButton) return v
            if (v is android.view.ViewGroup) for (i in v.childCount - 1 downTo 0) find(v.getChildAt(i))?.let { return it }
            return null
        }
        find(a.findViewById(android.R.id.content))!!.performClick(); idle()
        render(a.findViewById(android.R.id.content), "clock_new_alarm")
    }

    @Test fun photos() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
        val day = 86_400_000L; val now = System.currentTimeMillis()
        val cursor = org.robolectric.fakes.RoboCursor().apply {
            setColumnNames(listOf("_id", "date_added", "datetaken", "bucket_display_name"))
            setResults((0 until 14).map { i -> arrayOf<Any>(i.toLong(), 0L, now - i * 9 * day, if (i % 3 == 0) "Camera" else "Screenshots") }.toTypedArray())
        }
        shadowOf(app.contentResolver).setCursor(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor)
        val a = Robolectric.buildActivity(PhotosActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        render(a.findViewById(android.R.id.content), "photos")
    }

    @Test fun startBottomAndDrag() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val look = TileLook(Palette(true, Metro.DEFAULT_ACCENT), Background.SOLID, 0, true)
        val apps = sampleApps(a)
        val grid = StartGrid(a, look)
        grid.topInset = a.dpi(24f); grid.bottomInset = a.dpi(NavBar.HEIGHT)
        val tiles = apps.take(8).mapIndexed { i, x -> TileView(a, TileSpec("t$i", TileKind.APP, x.key, if (i % 3 == 2) TileSize.SMALL else TileSize.MEDIUM), look).also { it.app = x } }
        grid.setTiles(tiles)
        val content = FrameLayout(a).apply { setBackgroundColor(Color.BLACK); addView(grid) }
        val shell = Shell(a, Prefs(a), look.palette, overlay = true)
        shell.setContent(content)
        idle()
        // Edit mode with the selected tile mid-drag: lifted 1.05× and leaning into its motion.
        grid.enterEdit(tiles[4]); idle()
        tiles[4].apply { scaleX = 1.05f; scaleY = 1.05f; rotationY = 12f; rotationX = -6f; translationX = a.dp(40f); translationY = -a.dp(30f) }
        render(shell.root, "start_drag")
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
