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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = Prefs(this); p = prefs.palette()
        window.statusBarColor = 0; window.navigationBarColor = 0

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
        val nav = if (prefs.navKeys) NavBar(this, p.accent).also { NavBar.wire(it, this); column.addView(it, LinearLayout.LayoutParams(-1, -2)) } else null

        buildMenu()
        buildTabsOverlay()
        setContentView(frame)
        WindowCompat.getInsetsController(window, frame).apply {
            isAppearanceLightStatusBars = !p.dark; isAppearanceLightNavigationBars = !p.dark && nav == null
        }
        frame.setOnApplyWindowInsetsListener { _, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars())
            val ime = ins.getInsets(WindowInsets.Type.ime())
            topInset = b.top
            // With the keyboard up the bar rides on top of it (and the nav keys are covered, as on W10M).
            val bottom = if (ime.bottom > 0) ime.bottom - (nav?.let { dpi(NavBar.HEIGHT) + b.bottom } ?: 0) else if (nav == null) b.bottom else 0
            column.setPadding(0, b.top, 0, bottom.coerceAtLeast(0))
            nav?.bottomInset = b.bottom
            tabsOverlay.setPadding(0, b.top, 0, b.bottom)
            ins
        }
        newTab(intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString)
        pages.requestFocus()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.let { newTab(it) }
    }

    override fun onDestroy() { super.onDestroy(); tabs.forEach { it.web.destroy() } }

    // ---------------------------------------------------------------- chrome

    private fun buildStartPage(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(p.bg)
        setPadding(dpi(Metro.PAGE_MARGIN), dpi(72f), dpi(Metro.PAGE_MARGIN), 0)
        addView(TextView(context).apply {
            text = getString(R.string.where_next); textSize = 34f; typeface = Metro.light(context); setTextColor(p.fg); includeFontPadding = false
        })
        addView(TextView(context).apply {
            text = getString(R.string.where_next_hint); textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fgMedium)
            setPadding(0, dpi(12f), 0, 0)
        })
        setOnClickListener { focusAddress() }
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
            else -> "https://www.bing.com/search?q=" + URLEncoder.encode(t, "UTF-8")
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
        frame.addView(menu, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
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
        menuItem(getString(R.string.forward), w?.canGoForward() == true) { w?.goForward() }
        menuItem(getString(R.string.new_tab)) { newTab(null) }
        menuItem(getString(R.string.share), url != null) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
        }
        menuItem(getString(if (desktop) R.string.mobile_site else R.string.desktop_site)) { setDesktop(!desktop) }
        menuItem(getString(R.string.pin_page), url != null) { prefs.pinWeb(url!!, w.title?.takeIf { it.isNotBlank() } ?: displayUrl(url)) }
        // Sit right above the command bar (and the nav keys): the bar starts where the page area ends.
        val column = pages.parent as View
        (menu.layoutParams as FrameLayout.LayoutParams).bottomMargin =
            (frame.height - column.top - pages.bottom - dpi(2f)).coerceAtLeast(0)
        menu.requestLayout()
        scrim.visibility = View.VISIBLE; menu.visibility = View.VISIBLE
        menu.translationY = dp(120f); menu.alpha = 0f
        menu.animate().translationY(0f).alpha(1f).setDuration(Metro.DUR_FAST).withEndAction(null)
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
        }
        w.setDownloadListener { u, _, _, _, _ ->
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
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
            tabsOverlay.visibility == View.VISIBLE -> hideTabs()
            menu.visibility == View.VISIBLE -> toggleMenu()
            address.hasFocus() -> blurAddress()
            w != null && w.canGoBack() -> w.goBack()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }
}
