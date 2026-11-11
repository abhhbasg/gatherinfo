package com.w10m.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import androidx.core.view.WindowCompat
import java.io.File

class MainActivity : Activity(), StartGrid.Host, AppListPage.Host {

    private lateinit var prefs: Prefs
    private lateinit var look: TileLook
    private lateinit var root: FrameLayout
    private lateinit var wallpaperView: ImageView
    private lateinit var pager: Pager
    private lateinit var scroll: ScrollView
    private lateinit var grid: StartGrid
    private lateinit var appList: AppListPage
    private var apps: Map<String, AppEntry> = emptyMap()
    private var appOrder: List<AppEntry> = emptyList()
    private val ui = Handler(Looper.getMainLooper())
    private var pendingTurnstileIn = false
    private var appliedSignature = ""
    private var nav: NavBar? = null
    private var navShown = false
    private var tilesStamp = 0
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = Prefs(this)
        IconPack.load(this)
        look = TileLook(prefs.palette(), prefs.background, prefs.transparency, prefs.monoIcons,
            iconPack = prefs.iconPack, brandColors = prefs.brandColors)
        tilesStamp = prefs.tilesStamp

        root = FrameLayout(this)
        wallpaperView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(wallpaperView, FrameLayout.LayoutParams(-1, -1))

        grid = StartGrid(this, look).also { it.host = this }
        scroll = object : ScrollView(this) {
            override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
                super.onScrollChanged(l, t, oldl, oldt); grid.onScrolled(t)
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            addView(grid)
        }
        // Long-press on empty Start space opens Start settings.
        grid.setOnLongClickListener { if (!grid.editing) openSettings(); true }

        appList = AppListPage(this, look.palette, look).also { it.host = this }

        pager = Pager(this).apply {
            addView(scroll); addView(appList)
            onPageChanged = { p -> if (p == 0) appList.hideKeyboard() }
        }
        root.addView(pager, FrameLayout.LayoutParams(-1, -1))
        navShown = prefs.navKeys
        if (navShown) nav = NavBar(this, look.palette.accent).also { n ->
            @Suppress("DEPRECATION")
            n.onBack = { onBackPressed() }
            n.onStart = { goStart() }
            n.onSearch = { openSearch() }
            root.addView(n, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM))
        }
        setContentView(root)

        root.setOnApplyWindowInsetsListener { _, ins ->
            val bars = ins.getInsets(WindowInsets.Type.systemBars())
            val ime = ins.getInsets(WindowInsets.Type.ime())
            nav?.bottomInset = bars.bottom
            val bottom = bars.bottom + if (nav != null) dpi(NavBar.HEIGHT) else 0
            grid.topInset = bars.top; grid.bottomInset = bottom; grid.requestLayout()
            appList.setInsets(bars.top, maxOf(bottom, ime.bottom))
            ins
        }

        BadgeService.listener = { applyBadges() }
        reloadApps()
        applyLook(force = true)
        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        })
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_DATE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED)
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(pkgReceiver); unregisterReceiver(timeReceiver)
        BadgeService.listener = null
        ui.removeCallbacksAndMessages(null)
    }

    // ---------------------------------------------------------------- data

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { reloadApps() }
    }
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { grid.tileViews.filter { it.spec.kind == TileKind.CALENDAR }.forEach { it.invalidate() } }
    }

    private fun reloadApps() {
        appOrder = AppRepo.load(this)
        apps = appOrder.associateBy { it.key }
        appList.setApps(appOrder)
        reloadTiles()
    }

    private fun reloadTiles() {
        val specs = (prefs.loadTiles() ?: AppRepo.defaultTiles(this, appOrder).also { prefs.saveTiles(it) })
            .filter { it.kind != TileKind.APP || apps.containsKey(it.component) }
        buildTiles(specs)
    }

    private fun buildTiles(specs: List<TileSpec>) {
        ui.removeCallbacksAndMessages(FLIP_TOKEN)
        val views = specs.map { s -> TileView(this, s, look).also { it.app = s.component?.let { k -> apps[k] } } }
        grid.setTiles(views)
        views.forEach { scheduleLive(it) }
        applyBadges()
    }

    /** Every tile gets a randomised 5–10 s tick; tiles with live content slide to their next face. */
    private fun scheduleLive(t: TileView) {
        ui.postAtTime({ if (t.parent != null) { if (resumed && !grid.editing) t.advance(); scheduleLive(t) } },
            FLIP_TOKEN, android.os.SystemClock.uptimeMillis() + t.nextLiveDelay())
    }

    private fun applyBadges() {
        val c = BadgeService.counts; val n = BadgeService.notes
        grid.tileViews.forEach { t ->
            val pkg = t.app?.component?.packageName
            t.badge = pkg?.let { c[it] } ?: 0
            t.note = pkg?.let { n[it] }
        }
    }

    /** Photos live tile: load the newest few pictures as thumbnails once the photos permission is granted. */
    private fun loadPhotos() {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
        else android.Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        Thread {
            val out = ArrayList<Bitmap>()
            try {
                val base = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                contentResolver.query(base, arrayOf(android.provider.MediaStore.Images.Media._ID), null, null,
                    android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC")?.use { c ->
                    while (c.moveToNext() && out.size < 6) try {
                        out += contentResolver.loadThumbnail(android.content.ContentUris.withAppendedId(base, c.getLong(0)),
                            android.util.Size(640, 640), null)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            Live.photos = out
        }.start()
    }

    override fun onTilesChanged(tiles: List<TileSpec>) = prefs.saveTiles(tiles)

    override fun onEditModeChanged(editing: Boolean) { pager.enabledPaging = !editing }

    // ---------------------------------------------------------------- look

    private fun applyLook(force: Boolean = false) {
        val sig = "${prefs.dark}${prefs.accent}${prefs.background}${prefs.transparency}${prefs.moreTiles}${prefs.monoIcons}" +
            "${prefs.iconPack}${prefs.brandColors}${wallpaperFile().lastModified()}"
        if (!force && sig == appliedSignature) return
        appliedSignature = sig
        val p = prefs.palette()
        look.palette = p; look.background = prefs.background; look.transparency = prefs.transparency; look.monoIcons = prefs.monoIcons
        look.iconPack = prefs.iconPack; look.brandColors = prefs.brandColors
        nav?.setAccent(p.accent)
        grid.columns = if (prefs.moreTiles) Metro.COLUMNS_MORE else Metro.COLUMNS_DEFAULT
        window.statusBarColor = 0; window.navigationBarColor = 0
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = !p.dark; isAppearanceLightNavigationBars = !p.dark
        }
        val wp = loadWallpaper()
        when {
            look.background == Background.BEHIND_TILES && wp != null -> { wallpaperView.setImageBitmap(wp); root.setBackgroundColor(p.bg) }
            else -> { wallpaperView.setImageDrawable(null); root.setBackgroundColor(p.bg) }
        }
        look.wallpaper = if (look.background == Background.ON_TILES) wp?.let { coverBitmap(it) } else null
        appList.applyPalette(p)
        grid.tileViews.forEach { it.invalidate() }
        grid.post { grid.onScrolled(scroll.scrollY) }
    }

    fun wallpaperFile() = File(filesDir, "wallpaper")

    private fun loadWallpaper(): Bitmap? {
        val f = wallpaperFile()
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        val dm = resources.displayMetrics
        var s = 1
        while (o.outWidth / (s * 2) >= dm.widthPixels && o.outHeight / (s * 2) >= dm.heightPixels) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    /** Scale to cover the screen, 25% taller for the parallax travel of picture-on-tiles. */
    private fun coverBitmap(src: Bitmap): Bitmap {
        val dm = resources.displayMetrics
        val tw = dm.widthPixels; val th = (dm.heightPixels * 1.25f).toInt()
        val sc = maxOf(tw / src.width.toFloat(), th / src.height.toFloat())
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val sw = (tw / sc).toInt(); val sh = (th / sc).toInt()
        val sx = (src.width - sw) / 2; val sy = (src.height - sh) / 2
        Canvas(out).drawBitmap(src, Rect(sx, sy, sx + sw, sy + sh), Rect(0, 0, tw, th), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return out
    }

    // ---------------------------------------------------------------- lifecycle / navigation

    override fun onPause() { super.onPause(); resumed = false }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (navShown != prefs.navKeys) { recreate(); return }
        if (tilesStamp != prefs.tilesStamp) { tilesStamp = prefs.tilesStamp; reloadTiles() }
        loadPhotos()
        applyLook()
        applyBadges()
        if (pendingTurnstileIn) {
            pendingTurnstileIn = false
            grid.turnstile(false, null, scroll.scrollY, scroll.scrollY + scroll.height) { grid.resetTransforms() }
        } else grid.resetTransforms()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(NavBar.EXTRA_SEARCH, false) == true) openSearch() else goStart()
    }

    /** Pressing Start while on Start: leave edit mode, return to the tiles, then scroll to top. */
    private fun goStart() {
        when {
            grid.editing -> grid.exitEdit()
            pager.page != 0 -> { appList.resetState(); pager.snapTo(0) }
            else -> scroll.smoothScrollTo(0, 0)
        }
    }

    /** The Search key jumps to the app list with the search box focused. */
    private fun openSearch() {
        if (grid.editing) grid.exitEdit()
        pager.snapTo(1)
        appList.post { appList.focusSearch() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            grid.editing -> grid.exitEdit()
            pager.page == 1 && appList.handleBack() -> Unit
            pager.page == 1 -> pager.snapTo(0)
            else -> Unit // the Start screen is home: back does nothing
        }
    }

    override fun onTileClick(t: TileView) {
        val intent = when (t.spec.kind) {
            TileKind.APP -> t.app?.let { launchIntent(it.component) }
            TileKind.CALENDAR -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
            TileKind.WEB -> Intent(Intent.ACTION_VIEW, Uri.parse(t.spec.component), this, BrowserActivity::class.java)
        } ?: return
        pager.enabledPaging = false
        grid.turnstile(true, t, scroll.scrollY, scroll.scrollY + scroll.height) {
            pager.enabledPaging = true
            if (start(intent)) { pendingTurnstileIn = true; @Suppress("DEPRECATION") overridePendingTransition(R.anim.metro_enter, 0) }
            else grid.resetTransforms()
        }
    }

    override fun onAppClick(a: AppEntry, from: View) {
        if (start(launchIntent(a.component))) @Suppress("DEPRECATION") overridePendingTransition(R.anim.metro_enter, 0)
    }

    override fun onAppLongClick(a: AppEntry, from: View) {
        val pinned = grid.specs.any { it.component == a.key }
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (!pinned) items += getString(R.string.pin_to_start) to { pin(a) }
        items += getString(R.string.app_info) to {
            start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.component.packageName, null)))
            Unit
        }
        items += getString(R.string.uninstall) to {
            start(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", a.component.packageName, null)))
            Unit
        }
        MetroMenu.show(from, look.palette, items)
    }

    private fun pin(a: AppEntry) {
        val s = TileSpec("a:${a.key}", TileKind.APP, a.key, TileSize.MEDIUM)
        grid.appendTile(TileView(this, s, look).also { it.app = a })
        applyBadges()
        // W10M jumps back to Start and shows the newly pinned tile.
        pager.snapTo(0)
        grid.post { scroll.smoothScrollTo(0, grid.height) }
    }

    private fun openSettings() { start(Intent(this, SettingsActivity::class.java)) }

    private fun launchIntent(cn: ComponentName) = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    private fun start(i: Intent): Boolean = try {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) { false }

    companion object { private val FLIP_TOKEN = Any() }
}
