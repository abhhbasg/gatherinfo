package com.w10m.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Built-in browser laid out like the W10M browser: page on top, command bar at the bottom
 * (tabs · address box with refresh · "…"), overflow menu sliding up from the bar, tab grid overlay.
 */
class BrowserActivity : Activity() {

    private class Tab(val web: WebView) { var title = ""; var thumb: Bitmap? = null }

    private lateinit var prefs: Prefs
    private lateinit var p: Palette
    private lateinit var frame: FrameLayout
    private lateinit var pages: FrameLayout
    private lateinit var startPage: View
    private lateinit var address: EditText
    private lateinit var progress: View
    private lateinit var refresh: GlyphButton
    private lateinit var menu: LinearLayout
    private lateinit var scrim: View
    private lateinit var tabsOverlay: LinearLayout
    private lateinit var tabsGrid: LinearLayout
    private var topInset = 0
    private val tabs = ArrayList<Tab>()
    private var currentTab = -1
    private var loading = false
    private var desktop = false
    private lateinit var shell: Shell
    private lateinit var findBar: LinearLayout
    private lateinit var findBox: EditText
    private var sheet: View? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: android.webkit.ValueCallback<Array<Uri>>? = null

    private class Site(val title: String, val url: String)
    private val engines = linkedMapOf(
        "bing" to ("必应 Bing" to "https://www.bing.com/search?q="),
        "baidu" to ("百度" to "https://www.baidu.com/s?wd="),
        "google" to ("Google" to "https://www.google.com/search?q="),
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this); p = prefs.palette()
        shell = Shell(this, prefs, p)

        frame = FrameLayout(this).apply { setBackgroundColor(p.bg) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(column, FrameLayout.LayoutParams(-1, -1))

        // The page area holds focus at rest so the address box only lights up when tapped.
        pages = FrameLayout(this).apply { isFocusable = true; isFocusableInTouchMode = true }
        startPage = buildStartPage()
        pages.addView(startPage, FrameLayout.LayoutParams(-1, -1))
        column.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))

        progress = View(this).apply { setBackgroundColor(p.accent); pivotX = 0f; scaleX = 0f }
        column.addView(FrameLayout(this).apply {
            setBackgroundColor(p.chromeMedium)
            addView(progress, FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(-1, dpi(2f)))
        column.addView(buildBar(), LinearLayout.LayoutParams(-1, dpi(48f)))
        buildMenu()
        buildTabsOverlay()
        buildFindBar()
        shell.setContent(frame)
        newTab(intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString)
        pages.requestFocus()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.let { newTab(it) }
    }

    override fun onDestroy() { super.onDestroy(); tabs.forEach { it.web.destroy() } }

    // ---------------------------------------------------------------- chrome

    private lateinit var startFavs: LinearLayout

    private fun buildStartPage(): View = ScrollView(this).apply {
        setBackgroundColor(p.bg); overScrollMode = View.OVER_SCROLL_NEVER
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dpi(48f), 0, dpi(24f)) }
        val m = dpi(Metro.PAGE_MARGIN)
        col.addView(TextView(context).apply {
            text = getString(R.string.where_next); textSize = 34f; typeface = Metro.light(context); setTextColor(p.fg); includeFontPadding = false
            setPadding(m, 0, m, 0)
        })
        col.addView(TextView(context).apply {
            text = getString(R.string.where_next_hint); textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fgMedium)
            setPadding(m, dpi(12f), m, dpi(16f)); setOnClickListener { focusAddress() }
        })
        startFavs = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(startFavs)
        addView(col)
    }

    /** Favourites listed on the new-tab page. */
    private fun fillStartFavs() {
        startFavs.removeAllViews()
        val favs = loadSites("favs")
        if (favs.isEmpty()) return
        startFavs.addView(sectionLabel(getString(R.string.favorites)))
        favs.forEach { s -> startFavs.addView(siteRow(s, { go(s.url) }, null), LinearLayout.LayoutParams(-1, dpi(52f))) }
    }

    // ---------------------------------------------------------------- favourites / history store

    private fun loadSites(key: String): MutableList<Site> = try {
        val a = JSONArray(prefs.str("browser_$key", "[]"))
        MutableList(a.length()) { a.getJSONObject(it).let { o -> Site(o.optString("t"), o.getString("u")) } }
    } catch (_: Exception) { mutableListOf() }

    private fun saveSites(key: String, l: List<Site>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("t", it.title).put("u", it.url)) }
        prefs.putStr("browser_$key", a.toString())
    }

    private fun recordHistory(url: String?, title: String?) {
        if (url == null || !url.startsWith("http")) return
        val h = loadSites("history")
        h.removeAll { it.url == url }
        h.add(0, Site(title?.takeIf { it.isNotBlank() } ?: displayUrl(url), url))
        saveSites("history", h.take(200))
    }

    private fun sectionLabel(t: String) = TextView(this).apply {
        text = t; textSize = 15f; typeface = Metro.semibold(context); setTextColor(p.fg)
        setPadding(dpi(Metro.PAGE_MARGIN), dpi(8f), dpi(Metro.PAGE_MARGIN), dpi(4f))
    }

    private fun siteRow(s: Site, tap: () -> Unit, longTap: (() -> Unit)?): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dpi(Metro.PAGE_MARGIN), 0, dpi(Metro.PAGE_MARGIN), 0)
        addView(TextView(context).apply {
            text = s.title; textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fg); isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        addView(TextView(context).apply {
            text = s.url; textSize = 12f; typeface = Metro.regular(context); setTextColor(p.fgMedium); isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        setOnClickListener { tap() }
        if (longTap != null) setOnLongClickListener { longTap(); true }
    }

    private fun buildBar(): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(p.chromeMedium) }
        // Tabs: two stacked page outlines.
        bar.addView(GlyphButton(this, p.fg) { c, pt, cx, cy, u ->
            c.drawRect(cx - 8 * u, cy - 4 * u, cx + 4 * u, cy + 8 * u, pt)
            c.drawLine(cx - 4 * u, cy - 4 * u, cx - 4 * u, cy - 8 * u, pt)
            c.drawLine(cx - 4 * u, cy - 8 * u, cx + 8 * u, cy - 8 * u, pt)
            c.drawLine(cx + 8 * u, cy - 8 * u, cx + 8 * u, cy + 4 * u, pt)
            c.drawLine(cx + 8 * u, cy + 4 * u, cx + 4 * u, cy + 4 * u, pt)
        }.apply { setOnClickListener { showTabs() } }, LinearLayout.LayoutParams(dpi(48f), -1))

        val box = FrameLayout(this)
        address = EditText(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Metro.regular(context)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setHint(R.string.address_hint)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(10f), 0, dpi(36f), 0)
            includeFontPadding = false
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, _, _ -> go(text.toString()); true }
            setOnFocusChangeListener { _, f -> styleAddress(f) }
        }
        box.addView(address, FrameLayout.LayoutParams(-1, dpi(32f), Gravity.CENTER_VERTICAL))
        // Refresh (circular arrow) / stop (x) inside the right edge of the address box.
        refresh = GlyphButton(this, p.fg) { c, pt, cx, cy, u ->
            if (loading) {
                c.drawLine(cx - 5 * u, cy - 5 * u, cx + 5 * u, cy + 5 * u, pt); c.drawLine(cx + 5 * u, cy - 5 * u, cx - 5 * u, cy + 5 * u, pt)
            } else {
                c.drawArc(cx - 6 * u, cy - 6 * u, cx + 6 * u, cy + 6 * u, -50f, 310f, false, pt)
                c.drawLine(cx + 6.5f * u, cy - 7 * u, cx + 6.5f * u, cy - 2.5f * u, pt)
                c.drawLine(cx + 6.5f * u, cy - 2.5f * u, cx + 2 * u, cy - 2.5f * u, pt)
            }
        }.apply { setOnClickListener { tab()?.web?.let { if (loading) it.stopLoading() else it.reload() } } }
        box.addView(refresh, FrameLayout.LayoutParams(dpi(36f), -1, Gravity.END))
        bar.addView(box, LinearLayout.LayoutParams(0, -1, 1f))

        // "…" more.
        bar.addView(GlyphButton(this, p.fg) { c, pt, cx, cy, u ->
            pt.style = Paint.Style.FILL
            for (i in -1..1) c.drawRect(cx + i * 6 * u - 1.2f * u, cy - 1.2f * u, cx + i * 6 * u + 1.2f * u, cy + 1.2f * u, pt)
        }.apply { setOnClickListener { toggleMenu() } }, LinearLayout.LayoutParams(dpi(48f), -1))
        styleAddress(false)
        return bar
    }

    private fun styleAddress(focused: Boolean) {
        address.background = GradientDrawable().apply {
            setColor(if (focused) Color.WHITE else if (p.dark) 0x1FFFFFFF else 0x14000000)
            if (focused) setStroke(dpi(2f), p.accent)
        }
        address.setTextColor(if (focused) Color.BLACK else p.fg)
        address.setHintTextColor(if (focused) 0x99000000.toInt() else p.fgMedium)
        refresh.color = if (focused) Color.BLACK else p.fg; refresh.invalidate()
        val url = tab()?.web?.url
        if (focused) { address.setText(url.orEmpty()); address.selectAll() } else address.setText(displayUrl(url))
    }

    /** At rest the box shows just the host, like the W10M address bar. */
    private fun displayUrl(url: String?): String {
        if (url.isNullOrEmpty() || url == "about:blank") return ""
        return Uri.parse(url).host?.removePrefix("www.") ?: url
    }

    private fun focusAddress() {
        address.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(address, 0)
    }

    private fun blurAddress() {
        pages.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
    }

    private fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> (engines[prefs.str("engine", "bing")] ?: engines.getValue("bing")).second + URLEncoder.encode(t, "UTF-8")
        }
        blurAddress()
        tab()?.web?.loadUrl(url)
        startPage.visibility = View.GONE
    }

    // ---------------------------------------------------------------- overflow menu

    private fun buildMenu() {
        scrim = View(this).apply { visibility = View.GONE; setOnClickListener { toggleMenu() } }
        frame.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(p.flyout); visibility = View.GONE
            setPadding(0, dpi(4f), 0, dpi(4f))
        }
        // The overflow sits right above the 48 epx command bar and its 2 epx progress line.
        frame.addView(menu, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dpi(50f) })
    }

    private fun menuItem(label: String, enabled: Boolean = true, action: () -> Unit) {
        menu.addView(TextView(this).apply {
            text = label; textSize = 15f; typeface = Metro.regular(context)
            setTextColor(if (enabled) p.fg else p.fgLow)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(Metro.PAGE_MARGIN), 0, dpi(Metro.PAGE_MARGIN), 0)
            if (enabled) setOnClickListener { toggleMenu(); action() }
        }, LinearLayout.LayoutParams(-1, dpi(44f)))
    }

    /** Icon + caption key for the top row of the overflow (forward / hub / add favourite). */
    private fun menuKey(glyph: String, label: String, enabled: Boolean, action: () -> Unit): View {
        val gp = Paint(Paint.ANTI_ALIAS_FLAG)
        val col = if (enabled) p.fg else p.fgLow
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(GlyphButton(context, col) { c, pt, cx, cy, u ->
                if (glyph == "forward") {
                    c.drawLine(cx - 8 * u, cy, cx + 8 * u, cy, pt)
                    c.drawLine(cx + 2 * u, cy - 6 * u, cx + 8 * u, cy, pt); c.drawLine(cx + 8 * u, cy, cx + 2 * u, cy + 6 * u, pt)
                } else IconPack.glyph(glyph)?.draw(c, cx - 10 * u, cy - 10 * u, 20 * u, gp, col)
            }.apply { isClickable = false }, LinearLayout.LayoutParams(dpi(48f), dpi(36f)))
            addView(TextView(context).apply { text = label; textSize = 12f; typeface = Metro.regular(context); setTextColor(col); gravity = Gravity.CENTER })
            if (enabled) setOnClickListener { toggleMenu(); action() }
        }
    }

    private fun toggleMenu() {
        if (menu.visibility == View.VISIBLE) {
            scrim.visibility = View.GONE
            menu.animate().translationY(menu.height.toFloat()).setDuration(Metro.DUR_FAST).withEndAction { menu.visibility = View.GONE }
            return
        }
        blurAddress()
        menu.removeAllViews()
        val w = tab()?.web
        val url = w?.url?.takeIf { it.startsWith("http") }
        val isFav = url != null && loadSites("favs").any { it.url == url }
        val keys = LinearLayout(this).apply { setPadding(0, dpi(4f), 0, dpi(8f)) }
        keys.addView(menuKey("forward", getString(R.string.forward), w?.canGoForward() == true) { w?.goForward() }, LinearLayout.LayoutParams(0, -2, 1f))
        keys.addView(menuKey("history", getString(R.string.hub), true) { showHub(0) }, LinearLayout.LayoutParams(0, -2, 1f))
        keys.addView(menuKey("star", getString(if (isFav) R.string.remove_favorite else R.string.add_favorite), url != null) {
            val f = loadSites("favs")
            if (isFav) f.removeAll { it.url == url } else f.add(Site(w!!.title?.takeIf { it.isNotBlank() } ?: displayUrl(url), url!!))
            saveSites("favs", f); fillStartFavs()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        menu.addView(keys)
        menuItem(getString(R.string.new_tab)) { newTab(null) }
        menuItem(getString(R.string.find_on_page), url != null) { showFind() }
        menuItem(getString(if (desktop) R.string.mobile_site else R.string.desktop_site)) { setDesktop(!desktop) }
        menuItem(getString(R.string.share), url != null) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
        }
        menuItem(getString(R.string.pin_page), url != null) { prefs.pinWeb(url!!, w!!.title?.takeIf { it.isNotBlank() } ?: displayUrl(url)) }
        menuItem(getString(R.string.browser_settings)) { showSettings() }
        scrim.visibility = View.VISIBLE; menu.visibility = View.VISIBLE
        menu.translationY = dp(120f); menu.alpha = 0f
        menu.animate().translationY(0f).alpha(1f).setDuration(Metro.DUR_FAST).withEndAction(null)
    }

    // ---------------------------------------------------------------- find on page

    private fun buildFindBar() {
        findBar = LinearLayout(this).apply { setBackgroundColor(p.chromeMedium); gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; isClickable = true }
        findBox = EditText(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Metro.regular(context); isSingleLine = true
            setHint(R.string.find_on_page); setTextColor(Color.BLACK); setHintTextColor(0x99000000.toInt())
            setPadding(dpi(10f), 0, dpi(10f), 0); includeFontPadding = false
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(dpi(2f), p.accent) }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { tab()?.web?.findAllAsync(s.toString()) }
            })
            setOnEditorActionListener { _, _, _ -> tab()?.web?.findNext(true); true }
        }
        findBar.addView(findBox, LinearLayout.LayoutParams(0, dpi(32f), 1f).apply { marginStart = dpi(8f) })
        fun arrow(up: Boolean) = GlyphButton(this, p.fg) { c, pt, cx, cy, u ->
            val d = if (up) -1 else 1
            c.drawLine(cx - 6 * u, cy - 3 * u * d, cx, cy + 3 * u * d, pt); c.drawLine(cx, cy + 3 * u * d, cx + 6 * u, cy - 3 * u * d, pt)
        }.apply { setOnClickListener { tab()?.web?.findNext(!up) } }
        findBar.addView(arrow(true), LinearLayout.LayoutParams(dpi(40f), -1))
        findBar.addView(arrow(false), LinearLayout.LayoutParams(dpi(40f), -1))
        findBar.addView(GlyphButton(this, p.fg) { c, pt, cx, cy, u ->
            c.drawLine(cx - 5 * u, cy - 5 * u, cx + 5 * u, cy + 5 * u, pt); c.drawLine(cx + 5 * u, cy - 5 * u, cx - 5 * u, cy + 5 * u, pt)
        }.apply { setOnClickListener { hideFind() } }, LinearLayout.LayoutParams(dpi(40f), -1))
        frame.addView(findBar, FrameLayout.LayoutParams(-1, dpi(48f), Gravity.BOTTOM))
    }

    private fun showFind() {
        findBar.visibility = View.VISIBLE; findBox.setText(""); findBox.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(findBox, 0)
    }

    private fun hideFind() {
        tab()?.web?.clearMatches(); findBar.visibility = View.GONE
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(findBox.windowToken, 0)
        pages.requestFocus()
    }

    // ---------------------------------------------------------------- hub (favourites / history) and settings

    private fun openSheet(v: View) {
        closeSheet()
        sheet = v
        frame.addView(v, FrameLayout.LayoutParams(-1, -1))
        v.alpha = 0f; v.animate().alpha(1f).setDuration(Metro.DUR_FAST)
    }

    private fun closeSheet() { sheet?.let { frame.removeView(it) }; sheet = null }

    private fun sheetBase(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg); isClickable = true }

    private fun showHub(tabIndex: Int) {
        blurAddress()
        val base = sheetBase()
        val m = dpi(Metro.PAGE_MARGIN)
        // Pivot header: selected item full strength, the other at 60 %.
        val pivot = LinearLayout(this).apply { setPadding(m, dpi(8f), m, dpi(8f)) }
        listOf(R.string.favorites, R.string.history).forEachIndexed { i, res ->
            pivot.addView(TextView(this).apply {
                text = getString(res); textSize = 24f; typeface = Metro.semilight(context)
                setTextColor(if (i == tabIndex) p.fg else p.fgMedium); setPadding(0, 0, dpi(20f), 0)
                setOnClickListener { showHub(i) }
            })
        }
        base.addView(pivot)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val key = if (tabIndex == 0) "favs" else "history"
        val sites = loadSites(key)
        if (sites.isEmpty()) list.addView(TextView(this).apply {
            text = getString(if (tabIndex == 0) R.string.no_favorites else R.string.no_history_web); textSize = 15f
            typeface = Metro.regular(context); setTextColor(p.fgMedium); setPadding(m, dpi(8f), m, 0)
        })
        sites.forEach { s ->
            lateinit var row: View
            row = siteRow(s, { closeSheet(); go(s.url) }) {
                MetroMenu.show(row, p, listOf(
                    getString(R.string.open_new_tab) to { closeSheet(); newTab(s.url) },
                    getString(R.string.delete) to { saveSites(key, loadSites(key).filter { it.url != s.url }); fillStartFavs(); showHub(tabIndex) },
                ))
            }
            list.addView(row, LinearLayout.LayoutParams(-1, dpi(52f)))
        }
        base.addView(ScrollView(this).apply { addView(list); overScrollMode = View.OVER_SCROLL_NEVER }, LinearLayout.LayoutParams(-1, 0, 1f))
        if (tabIndex == 1 && sites.isNotEmpty()) base.addView(metroButton(this, p, getString(R.string.clear_history)) {
            saveSites("history", emptyList()); showHub(1)
        }, LinearLayout.LayoutParams(-2, dpi(32f)).apply { setMargins(m, dpi(8f), m, dpi(12f)) })
        openSheet(base)
    }

    private fun showSettings() {
        val base = sheetBase()
        val m = dpi(Metro.PAGE_MARGIN)
        base.setPadding(m, dpi(12f), m, 0)
        base.addView(TextView(this).apply { text = getString(R.string.browser_settings); textSize = 34f; typeface = Metro.light(context); setTextColor(p.fg); includeFontPadding = false })
        fun label(res: Int) = base.addView(TextView(this).apply {
            text = getString(res); textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fg); setPadding(0, dpi(24f), 0, dpi(8f))
        })
        label(R.string.search_engine)
        val current = prefs.str("engine", "bing")
        engines.forEach { (id, e) ->
            base.addView(TextView(this).apply {
                text = (if (id == current) "● " else "○ ") + e.first; textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fg)
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { prefs.putStr("engine", id); showSettings() }
            }, LinearLayout.LayoutParams(-1, dpi(40f)))
        }
        label(R.string.clear_data)
        base.addView(metroButton(this, p, getString(R.string.clear_data_button)) {
            saveSites("history", emptyList())
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.WebStorage.getInstance().deleteAllData()
            tabs.forEach { it.web.clearCache(true); it.web.clearHistory(); it.web.clearFormData() }
            android.widget.Toast.makeText(this, R.string.cleared, android.widget.Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(-2, dpi(32f)))
        openSheet(base)
    }

    // ---------------------------------------------------------------- downloads, long-press menu, uploads, full-screen video

    private fun download(url: String, ua: String?, disposition: String?, mime: String?) {
        try {
            val name = android.webkit.URLUtil.guessFileName(url, disposition, mime)
            val req = android.app.DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mime); if (ua != null) addRequestHeader("User-Agent", ua)
                android.webkit.CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setTitle(name)
                setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager).enqueue(req)
            android.widget.Toast.makeText(this, getString(R.string.downloading, name), android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            // e.g. data:/blob: URLs or no storage access on old Android: let another app try.
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

    private fun linkMenu(w: WebView): Boolean {
        val hit = w.hitTestResult
        val items = ArrayList<Pair<String, () -> Unit>>()
        val isImage = hit.type == WebView.HitTestResult.IMAGE_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        val isLink = hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE
        val target = hit.extra ?: return false
        if (isLink) {
            items += getString(R.string.open_new_tab) to { newTab(target) }
            items += getString(R.string.copy_link) to {
                (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("url", target))
            }
            items += getString(R.string.share) to {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, target), null))
            }
        }
        if (isImage) {
            items += getString(R.string.open_image) to { newTab(target) }
            items += getString(R.string.save_image) to { download(target, w.settings.userAgentString, null, null) }
        }
        if (items.isEmpty()) return false
        MetroMenu.show(pages, p, items)
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data)); fileCallback = null
        }
    }

    private fun exitFullscreen() {
        customView?.let { frame.removeView(it) }; customView = null
        customCallback?.onCustomViewHidden(); customCallback = null
    }

    private fun setDesktop(on: Boolean) {
        desktop = on
        tabs.forEach { applyUa(it.web) }
        tab()?.web?.reload()
    }

    private fun applyUa(w: WebView) {
        w.settings.userAgentString = if (desktop)
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36" else null
        w.settings.useWideViewPort = true
        w.settings.loadWithOverviewMode = true
    }

    // ---------------------------------------------------------------- tabs

    private fun tab() = tabs.getOrNull(currentTab)

    @SuppressLint("SetJavaScriptEnabled")
    private fun newTab(url: String?) {
        val w = WebView(this)
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            builtInZoomControls = true; displayZoomControls = false
            setSupportMultipleWindows(false)
        }
        applyUa(w)
        w.setBackgroundColor(p.bg)
        val t = Tab(w)
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "http" || u.scheme == "https" || u.scheme == "about") return false
                // tel:, mailto:, market:, app deep links… hand over to the system.
                try { startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (tab() === t) { loading = true; refresh.invalidate(); if (!address.hasFocus()) address.setText(displayUrl(url)) }
            }
            override fun onPageFinished(view: WebView, url: String?) {
                recordHistory(url, view.title)
                if (tab() === t) { loading = false; refresh.invalidate(); if (!address.hasFocus()) address.setText(displayUrl(url)) }
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (tab() !== t) return
                progress.animate().cancel()
                if (newProgress >= 100) progress.animate().scaleX(1f).setDuration(120).withEndAction { progress.scaleX = 0f }
                else progress.scaleX = newProgress / 100f
            }
            override fun onReceivedTitle(view: WebView, title: String?) { t.title = title.orEmpty() }
            override fun onShowFileChooser(view: WebView, cb: android.webkit.ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null); fileCallback = cb
                return try { @Suppress("DEPRECATION") startActivityForResult(params.createIntent(), REQ_FILE); true }
                catch (_: Exception) { fileCallback = null; false }
            }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                exitFullscreen()
                customView = view; customCallback = callback
                view.setBackgroundColor(Color.BLACK)
                frame.addView(view, FrameLayout.LayoutParams(-1, -1))
            }
            override fun onHideCustomView() = exitFullscreen()
        }
        w.setDownloadListener { u, ua, disposition, mime, _ -> download(u, ua, disposition, mime) }
        w.setOnLongClickListener { linkMenu(w) }
        tabs += t
        pages.addView(w, 0, FrameLayout.LayoutParams(-1, -1))
        select(tabs.size - 1)
        if (url != null) { startPage.visibility = View.GONE; w.loadUrl(url) }
    }

    private fun select(i: Int) {
        currentTab = i
        tabs.forEachIndexed { k, t -> t.web.visibility = if (k == i) View.VISIBLE else View.GONE }
        val url = tab()?.web?.url
        startPage.visibility = if (url.isNullOrEmpty() || url == "about:blank") View.VISIBLE else View.GONE
        if (startPage.visibility == View.VISIBLE) fillStartFavs()
        loading = false; progress.scaleX = 0f; refresh.invalidate()
        if (!address.hasFocus()) address.setText(displayUrl(url))
    }

    private fun closeTab(i: Int) {
        val t = tabs.removeAt(i)
        pages.removeView(t.web); t.web.destroy()
        if (tabs.isEmpty()) { newTab(null); hideTabs(); return }
        select((if (i <= currentTab) currentTab - 1 else currentTab).coerceIn(0, tabs.size - 1))
        fillTabs()
    }

    private fun buildTabsOverlay() {
        tabsOverlay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg); visibility = View.GONE; isClickable = true }
        tabsGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(8f), dpi(8f), dpi(8f), dpi(8f)) }
        tabsOverlay.addView(ScrollView(this).apply { addView(tabsGrid); overScrollMode = View.OVER_SCROLL_NEVER }, LinearLayout.LayoutParams(-1, 0, 1f))
        // Bottom bar with the "+" new tab button.
        tabsOverlay.addView(FrameLayout(this).apply {
            setBackgroundColor(p.chromeMedium)
            addView(GlyphButton(context, p.fg) { c, pt, cx, cy, u ->
                c.drawLine(cx - 8 * u, cy, cx + 8 * u, cy, pt); c.drawLine(cx, cy - 8 * u, cx, cy + 8 * u, pt)
            }.apply { setOnClickListener { newTab(null); hideTabs() } }, FrameLayout.LayoutParams(dpi(96f), -1, Gravity.CENTER))
        }, LinearLayout.LayoutParams(-1, dpi(48f)))
        frame.addView(tabsOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showTabs() {
        blurAddress()
        tab()?.let { t ->
            val w = t.web
            if (w.width > 0 && w.height > 0) try {
                val b = Bitmap.createBitmap(w.width / 3, w.height / 3, Bitmap.Config.RGB_565)
                val c = Canvas(b); c.scale(1 / 3f, 1 / 3f); c.translate(-w.scrollX.toFloat(), -w.scrollY.toFloat())
                (if (startPage.visibility == View.VISIBLE) startPage else w).draw(c)
                t.thumb = b
            } catch (_: Throwable) {}
        }
        fillTabs()
        tabsOverlay.visibility = View.VISIBLE
        tabsOverlay.alpha = 0f
        tabsOverlay.animate().alpha(1f).setDuration(Metro.DUR_FAST).withEndAction(null)
    }

    private fun hideTabs() {
        tabsOverlay.animate().alpha(0f).setDuration(Metro.DUR_FAST).withEndAction { tabsOverlay.visibility = View.GONE }
    }

    /** Two-column grid of tab cards: title strip with a close x, page thumbnail below; currentTab tab outlined in accent. */
    private fun fillTabs() {
        tabsGrid.removeAllViews()
        var row: LinearLayout? = null
        tabs.forEachIndexed { i, t ->
            if (i % 2 == 0) row = LinearLayout(this).also { tabsGrid.addView(it, LinearLayout.LayoutParams(-1, dpi(200f))) }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(p.chromeMedium); if (i == currentTab) setStroke(dpi(2f), p.accent) }
                setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(2f))
                setOnClickListener { select(i); hideTabs() }
            }
            val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(TextView(this).apply {
                text = t.title.ifEmpty { displayUrl(t.web.url).ifEmpty { getString(R.string.new_tab) } }
                textSize = 12f; typeface = Metro.regular(context); setTextColor(p.fg); isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dpi(8f), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(GlyphButton(this, p.fg) { c, pt, cx, cy, u ->
                c.drawLine(cx - 4 * u, cy - 4 * u, cx + 4 * u, cy + 4 * u, pt); c.drawLine(cx + 4 * u, cy - 4 * u, cx - 4 * u, cy + 4 * u, pt)
            }.apply { setOnClickListener { closeTab(i) } }, LinearLayout.LayoutParams(dpi(32f), dpi(32f)))
            card.addView(head, LinearLayout.LayoutParams(-1, dpi(32f)))
            card.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.MATRIX; setBackgroundColor(p.bg); t.thumb?.let { setImageBitmap(it) }
                addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
                    val b = t.thumb ?: return@addOnLayoutChangeListener
                    (v as ImageView).imageMatrix = android.graphics.Matrix().apply { val s = (r - l) / b.width.toFloat(); setScale(s, s) }
                }
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            row!!.addView(card, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dpi(4f), dpi(4f), dpi(4f), dpi(4f)) })
        }
        if (tabs.size % 2 == 1) row?.addView(View(this), LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dpi(4f), dpi(4f), dpi(4f), dpi(4f)) })
    }

    // ---------------------------------------------------------------- back

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = tab()?.web
        when {
            shell.handleBack() -> Unit
            customView != null -> exitFullscreen()
            sheet != null -> closeSheet()
            findBar.visibility == View.VISIBLE -> hideFind()
            tabsOverlay.visibility == View.VISIBLE -> hideTabs()
            menu.visibility == View.VISIBLE -> toggleMenu()
            address.hasFocus() -> blurAddress()
            w != null && w.canGoBack() -> w.goBack()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    companion object { private const val REQ_FILE = 11 }
}
