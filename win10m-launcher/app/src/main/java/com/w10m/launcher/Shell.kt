package com.w10m.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Date
import kotlin.math.abs

/**
 * Shared chrome for every screen of the launcher: the W10M status bar (replacing the system one),
 * the Action Center pulled down from it, and the Back / Start / Search key bar.
 *
 * [overlay] screens (Start) draw under the status bar and get their insets through [onInsets];
 * other screens are simply padded.
 */
class Shell(private val a: Activity, val prefs: Prefs, val p: Palette, private val overlay: Boolean = false) {

    val root = Root(a)
    private val holder = FrameLayout(a)
    val status: StatusBarView? = if (prefs.statusBar) StatusBarView(a, p, !overlay) else null
    val nav: NavBar? = if (prefs.navKeys) NavBar(a, p.accent) else null
    val center: ActionCenter? = if (status != null) ActionCenter(a, p) else null
    var onInsets: ((top: Int, bottom: Int, ime: Int) -> Unit)? = null
    var statusHeight = 0
        private set

    init {
        a.window.statusBarColor = 0; a.window.navigationBarColor = 0
        WindowCompat.setDecorFitsSystemWindows(a.window, false)
        root.addView(holder, FrameLayout.LayoutParams(-1, -1))
        center?.let { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        // The status bar stays on top of the Action Center, as on W10M; it goes transparent while the panel is down.
        status?.let { root.addView(it, FrameLayout.LayoutParams(-1, a.dpi(StatusBarView.HEIGHT), Gravity.TOP)) }
        center?.onOpenChanged = { open -> status?.setBackgroundColor(if (open || overlay) 0 else p.bg) }
        nav?.let { NavBar.wire(it, a); root.addView(it, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)) }
        root.setOnApplyWindowInsetsListener { _, ins -> applyInsets(ins); ins }
    }

    fun setContent(v: View) {
        holder.removeAllViews()
        holder.addView(v, FrameLayout.LayoutParams(-1, -1))
        a.setContentView(root)
        val c = WindowCompat.getInsetsController(a.window, root)
        c.isAppearanceLightStatusBars = !p.dark
        c.isAppearanceLightNavigationBars = !p.dark && nav == null
        if (status != null) {
            // The W10M status bar replaces the system one inside the launcher's own screens.
            c.hide(WindowInsetsCompat.Type.statusBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun applyInsets(ins: WindowInsets) {
        val bars = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val ime = ins.getInsets(WindowInsets.Type.ime()).bottom
        statusHeight = if (status != null) maxOf(a.dpi(StatusBarView.HEIGHT), bars.top) else bars.top
        status?.let { s ->
            s.layoutParams.height = statusHeight
            // Keep the icons clear of corner camera cut-outs.
            var l = 0; var r = 0
            ins.displayCutout?.boundingRects?.forEach { rc ->
                if (rc.top > statusHeight) return@forEach
                if (rc.centerX() < root.width / 3) l = maxOf(l, rc.right)
                else if (rc.centerX() > root.width * 2 / 3) r = maxOf(r, root.width - rc.left)
            }
            s.setSidePadding(l, r); s.requestLayout()
        }
        val navTotal = (if (nav != null) a.dpi(NavBar.HEIGHT) else 0) + bars.bottom
        nav?.bottomInset = bars.bottom
        center?.setInsets(statusHeight, navTotal)
        val cb = onInsets
        if (cb != null) cb(statusHeight, navTotal, ime)
        else holder.setPadding(0, if (overlay) 0 else statusHeight, 0, maxOf(navTotal, ime))
    }

    /** Back closes the Action Center first. */
    fun handleBack(): Boolean = center?.takeIf { it.isOpen }?.let { it.close(); true } ?: false

    fun closeCenter() { center?.takeIf { it.isOpen }?.close() }

    /** Root layout: a downward drag that starts on the status bar pulls the Action Center open. */
    @SuppressLint("ViewConstructor")
    inner class Root(c: Context) : FrameLayout(c) {
        private val slop = ViewConfiguration.get(c).scaledTouchSlop
        private var downY = 0f; private var tracking = false; private var pulling = false

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            val ac = center ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.y; pulling = false
                    tracking = !ac.isOpen && e.y <= statusHeight + context.dp(8f)
                }
                MotionEvent.ACTION_MOVE -> if (tracking && e.y - downY > slop) { pulling = true; ac.beginPull(); return true }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val ac = center ?: return false
            if (!pulling) return tracking
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> ac.pullTo(e.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pulling = false; tracking = false; ac.endPull(e.y > height / 5f) }
            }
            return true
        }
    }
}

/**
 * W10M status bar: signal bars, Wi-Fi fan, airplane / vibrate indicators on the left;
 * battery and clock on the right. 24 epx tall, 12 epx text, drawn in the theme's foreground colour.
 */
@SuppressLint("ViewConstructor")
class StatusBarView(context: Context, private val p: Palette, opaque: Boolean) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.sp(12f); typeface = Metro.regular(context) }
    private val path = Path()
    private val rect = RectF()
    private var padL = 0; private var padR = 0
    private var battery = 100; private var charging = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == Intent.ACTION_BATTERY_CHANGED) {
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                battery = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100 / scale
                val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            }
            invalidate()
        }
    }

    init { if (opaque) setBackgroundColor(p.bg) }

    fun setSidePadding(l: Int, r: Int) { padL = l; padR = r; invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION); addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION); addAction("android.net.conn.CONNECTIVITY_CHANGE")
        }
        try { context.registerReceiver(receiver, f) } catch (_: Exception) {}
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    private fun airplane() = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    private fun cellLevel(): Int = try {
        if (Build.VERSION.SDK_INT >= 28) (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).signalStrength?.level ?: -1 else -1
    } catch (_: Exception) { -1 }

    /** Wi-Fi level 0..4, or -1 when Wi-Fi is not the active network. */
    private fun wifiLevel(): Int = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) -1 else {
            @Suppress("DEPRECATION")
            val rssi = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo.rssi
            @Suppress("DEPRECATION") WifiManager.calculateSignalLevel(rssi, 5)
        }
    } catch (_: Exception) { -1 }

    override fun onDraw(c: Canvas) {
        val u = context.dp(1f)
        val barH = context.dp(HEIGHT)
        val cy = height - barH / 2            // content sits in the bottom 24 epx when a cut-out makes the bar taller
        val fg = p.fg
        val dim = (fg and 0x00FFFFFF) or 0x55000000
        var x = padL + 10 * u

        if (airplane()) {
            IconPack.glyph("plane")?.draw(c, x, cy - 6 * u, 12 * u, line, fg); x += 18 * u
        } else {
            // Signal: five rising bars, the first [level+1] lit.
            val lvl = cellLevel()
            for (i in 0..4) {
                val h = (3 + i * 2) * u
                fill.color = if (lvl >= 0 && i <= lvl) fg else dim
                c.drawRect(x + i * 3 * u, cy + 5.5f * u - h, x + i * 3 * u + 2 * u, cy + 5.5f * u, fill)
            }
            x += 21 * u
        }
        val wl = wifiLevel()
        if (wl >= 0) {
            // Wi-Fi: a 90° fan of three arcs above a dot.
            line.strokeWidth = 1.5f * u
            val bx = x + 7 * u; val by = cy + 5 * u
            fill.color = fg; c.drawCircle(bx, by - 1 * u, 1.2f * u, fill)
            for (i in 1..3) {
                val r = (1 + i * 3.2f) * u
                line.color = if (wl >= i) fg else dim
                rect.set(bx - r, by - 1 * u - r, bx + r, by - 1 * u + r)
                c.drawArc(rect, -135f, 90f, false, line)
            }
            x += 20 * u
        }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            // Vibrate / silent: a small phone outline with motion ticks (vibrate) or a slash (silent).
            line.color = fg; line.strokeWidth = 1.2f * u
            c.drawRect(x + 3 * u, cy - 5 * u, x + 9 * u, cy + 5 * u, line)
            if (am.ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
                c.drawLine(x + 0.5f * u, cy - 2 * u, x + 0.5f * u, cy + 2 * u, line)
                c.drawLine(x + 11.5f * u, cy - 2 * u, x + 11.5f * u, cy + 2 * u, line)
            } else c.drawLine(x, cy + 6 * u, x + 12 * u, cy - 6 * u, line)
        }

        // Right side: clock, then battery to its left.
        tp.color = fg; tp.textAlign = Paint.Align.RIGHT
        val time = DateFormat.getTimeFormat(context).format(Date())
        val right = width - padR - 10 * u
        c.drawText(time, right, cy - (tp.ascent() + tp.descent()) / 2, tp)
        val bw = 20 * u; val bh = 9 * u
        val bx = right - tp.measureText(time) - 8 * u - bw
        line.color = fg; line.strokeWidth = 1 * u
        c.drawRect(bx, cy - bh / 2, bx + bw - 2 * u, cy + bh / 2, line)
        fill.color = fg
        c.drawRect(bx + bw - 2 * u, cy - 2 * u, bx + bw, cy + 2 * u, fill)
        val inner = (bw - 2 * u - 4 * u) * battery / 100f
        c.drawRect(bx + 2 * u, cy - bh / 2 + 2 * u, bx + 2 * u + inner, cy + bh / 2 - 2 * u, fill)
        if (charging) {
            // Charging: a small plug-style bolt to the left of the battery.
            path.reset()
            path.moveTo(bx - 4 * u, cy - 5 * u); path.lineTo(bx - 8 * u, cy + 0.5f * u); path.lineTo(bx - 5.5f * u, cy + 0.5f * u)
            path.lineTo(bx - 7 * u, cy + 5 * u); path.lineTo(bx - 3 * u, cy - 0.5f * u); path.lineTo(bx - 5.5f * u, cy - 0.5f * u); path.close()
            c.drawPath(path, fill)
        }
    }

    companion object { const val HEIGHT = 24f }
}

/**
 * W10M Action Center: quick-action tiles on top (one row, expandable), then notifications grouped by app
 * with "Clear all". Slides down over the screen; the nav keys stay visible below it.
 */
@SuppressLint("ViewConstructor")
class ActionCenter(context: Context, private val p: Palette) : FrameLayout(context) {

    var isOpen = false
        private set(v) { if (field != v) { field = v; onOpenChanged?.invoke(v) } }
    var onOpenChanged: ((Boolean) -> Unit)? = null
    private val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.chromeMedium); isClickable = true }
    private val header = TextView(context)
    private val quick = QuickActions(context)
    private val expand = TextView(context)
    private val clearAll = TextView(context)
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var bottomInset = 0
    private val refresh: () -> Unit = { if (isOpen) fill() }

    init {
        visibility = GONE
        val m = context.dpi(Metro.PAGE_MARGIN)
        header.apply { textSize = 12f; typeface = Metro.regular(context); setTextColor(p.fgMedium); setPadding(m, context.dpi(6f), m, context.dpi(6f)) }
        panel.addView(header, LinearLayout.LayoutParams(-1, -2))
        panel.addView(quick, LinearLayout.LayoutParams(-1, -2))
        val links = FrameLayout(context)
        expand.apply {
            textSize = 12f; typeface = Metro.regular(context); setTextColor(p.fgMedium); setPadding(m, context.dpi(8f), m, context.dpi(8f))
            setOnClickListener { quick.expanded = !quick.expanded; text = context.getString(if (quick.expanded) R.string.collapse else R.string.expand) }
            text = context.getString(R.string.expand)
        }
        clearAll.apply {
            textSize = 12f; typeface = Metro.regular(context); setTextColor(p.fgMedium); setPadding(m, context.dpi(8f), m, context.dpi(8f))
            text = context.getString(R.string.clear_all)
            setOnClickListener { BadgeService.dismissAll() }
        }
        links.addView(clearAll, LayoutParams(-2, -2, Gravity.START))
        links.addView(expand, LayoutParams(-2, -2, Gravity.END))
        panel.addView(links, LinearLayout.LayoutParams(-1, -2))
        panel.addView(ScrollView(context).apply { addView(list); overScrollMode = OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false },
            LinearLayout.LayoutParams(-1, 0, 1f))
        // Bottom grip: drag or tap it to close.
        val grip = object : View(context) {
            private val gp = Paint().apply { color = p.fgLow }
            override fun onDraw(c: Canvas) {
                val w = context.dp(40f); val h = context.dp(2f)
                c.drawRect((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2, gp)
            }
        }
        var gy = 0f
        grip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> gy = e.rawY
                MotionEvent.ACTION_MOVE -> panel.translationY = (e.rawY - gy).coerceAtMost(0f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    if (abs(e.rawY - gy) < context.dp(8f) || panel.translationY < -panel.height / 6f) close() else settle(true)
            }
            true
        }
        panel.addView(grip, LinearLayout.LayoutParams(-1, context.dpi(24f)))
        addView(panel, LayoutParams(-1, -1))
    }

    fun setInsets(top: Int, bottom: Int) {
        bottomInset = bottom
        panel.setPadding(0, top, 0, 0)
        (panel.layoutParams as LayoutParams).bottomMargin = bottom
        panel.requestLayout()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); BadgeService.addListener(refresh) }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); BadgeService.removeListener(refresh) }

    // ---------------------------------------------------------------- open / close

    fun beginPull() {
        fill(); quick.refreshState()
        visibility = VISIBLE; isOpen = true
        panel.animate().cancel()
        panel.translationY = -height.toFloat()
    }

    fun pullTo(y: Float) { panel.translationY = (y - (height - bottomInset)).coerceAtMost(0f) }

    fun endPull(open: Boolean) = settle(open)

    fun open() { beginPull(); settle(true) }

    fun close() = settle(false)

    private fun settle(open: Boolean) {
        panel.animate().translationY(if (open) 0f else -height.toFloat()).setDuration(Metro.DUR_NORMAL)
            .setInterpolator(DecelerateInterpolator(2f)).withEndAction { if (!open) { visibility = GONE; isOpen = false } }
    }

    // ---------------------------------------------------------------- notifications

    private fun fill() {
        header.text = DateFormat.getLongDateFormat(context).format(Date())
        list.removeAllViews()
        val all = BadgeService.all
        clearAll.visibility = if (all.any { it.clearable }) VISIBLE else INVISIBLE
        val m = context.dpi(Metro.PAGE_MARGIN)
        if (all.isEmpty()) {
            list.addView(TextView(context).apply {
                text = context.getString(if (BadgeService.connected) R.string.no_notifications else R.string.notifications_need_access)
                textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fgMedium)
                setPadding(m, context.dpi(24f), m, 0)
                if (!BadgeService.connected) setOnClickListener {
                    try { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                }
            })
            return
        }
        val pm = context.packageManager
        for ((pkg, notes) in all.groupBy { it.pkg }) {
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
            // Group header: app name, semibold 15.
            list.addView(TextView(context).apply {
                text = label; textSize = 15f; typeface = Metro.semibold(context); setTextColor(p.fg)
                setPadding(m, context.dpi(12f), m, context.dpi(4f)); isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
            })
            for (n in notes) list.addView(NoteRow(n), LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** One notification: title 15, body 15 at 60 %, time 12. Swipe right to dismiss, tap to open. */
    private inner class NoteRow(val n: Note) : LinearLayout(context) {
        private var downX = 0f; private var dragging = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop

        init {
            orientation = VERTICAL
            val m = context.dpi(Metro.PAGE_MARGIN)
            setPadding(m, context.dpi(6f), m, context.dpi(8f))
            if (n.title.isNotEmpty()) addView(TextView(context).apply {
                text = n.title; textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fg); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
            if (n.text.isNotEmpty()) addView(TextView(context).apply {
                text = n.text; textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fgMedium); maxLines = 3; ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(context).apply {
                text = DateFormat.getTimeFormat(context).format(Date(n.time)); textSize = 12f; typeface = Metro.regular(context); setTextColor(p.fgMedium)
            })
        }

        override fun onInterceptTouchEvent(e: MotionEvent) = true

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    if (!dragging && dx > slop && n.clearable) { dragging = true; parent.requestDisallowInterceptTouchEvent(true) }
                    if (dragging) { translationX = dx.coerceAtLeast(0f); alpha = 1f - (translationX / width).coerceIn(0f, 1f) }
                }
                MotionEvent.ACTION_UP -> when {
                    dragging && translationX > width / 3f ->
                        animate().translationX(width.toFloat()).alpha(0f).setDuration(Metro.DUR_FAST).withEndAction { BadgeService.dismiss(n.key) }
                    dragging -> animate().translationX(0f).alpha(1f).setDuration(Metro.DUR_FAST)
                    else -> { try { n.intent?.send() } catch (_: Exception) {}; close() }
                }
                MotionEvent.ACTION_CANCEL -> animate().translationX(0f).alpha(1f).setDuration(Metro.DUR_FAST)
            }
            return true
        }
    }

    // ---------------------------------------------------------------- quick actions

    private class Action(val glyph: String, val label: Int, val state: () -> Boolean, val detail: (() -> String)? = null, val tap: () -> Unit)

    /** Four quick-action tiles per row: accent when on, grey when off; glyph top-left, label bottom-left. */
    private inner class QuickActions(c: Context) : View(c) {
        var expanded = false
            set(v) { field = v; requestLayout(); invalidate() }
        private val gap = c.dp(4f)
        private val tileH = c.dp(56f)
        private val fill = Paint()
        private val gp = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.sp(12f); typeface = Metro.regular(c) }
        private var torchOn = false
        private var pressedIdx = -1
        private val cam = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        private val torchCb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) { torchOn = enabled; invalidate() }
        }

        private fun go(action: String) {
            try { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            close()
        }

        private fun canWrite() = Settings.System.canWrite(context)
        private fun askWrite() {
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
            close()
        }
        private fun brightness() = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)

        private val actions = listOf(
            Action("wifi", R.string.qa_wifi, {
                try { (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled } catch (_: Exception) { false }
            }) { go(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS) },
            Action("bluetooth", R.string.qa_bluetooth, {
                try { (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true } catch (_: Exception) { false }
            }) { go(Settings.ACTION_BLUETOOTH_SETTINGS) },
            Action("flashlight", R.string.qa_flashlight, { torchOn }) {
                try {
                    val id = cam.cameraIdList.firstOrNull { cam.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                    if (id != null) cam.setTorchMode(id, !torchOn)
                } catch (_: Exception) {}
            },
            Action("settings", R.string.qa_settings, { false }) {
                try { context.startActivity(SystemSettingsActivity.intent(context)) } catch (_: Exception) {}
                close()
            },
            // Brightness steps 25 → 50 → 75 → 100 %, like the W10M quick action.
            Action("weather", R.string.qa_brightness, { true }, { "${Math.round(brightness() / 255f * 4) * 25}%" }) {
                if (!canWrite()) askWrite() else {
                    val step = (Math.round(brightness() / 255f * 4) % 4) + 1
                    Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (255 * step / 4f).toInt())
                    invalidate()
                }
            },
            Action("plane", R.string.qa_airplane, {
                Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            }) { go(Settings.ACTION_AIRPLANE_MODE_SETTINGS) },
            Action("map", R.string.qa_location, {
                try { Build.VERSION.SDK_INT >= 28 && (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled } catch (_: Exception) { false }
            }) { go(Settings.ACTION_LOCATION_SOURCE_SETTINGS) },
            Action("rotate", R.string.qa_rotation, {
                Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 0
            }) {
                if (!canWrite()) askWrite() else {
                    val on = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
                    Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1 - on); invalidate()
                }
            },
        )

        fun refreshState() = invalidate()

        override fun onAttachedToWindow() { super.onAttachedToWindow(); try { cam.registerTorchCallback(torchCb, null) } catch (_: Exception) {} }
        override fun onDetachedFromWindow() { super.onDetachedFromWindow(); try { cam.unregisterTorchCallback(torchCb) } catch (_: Exception) {} }

        private fun rows() = if (expanded) (actions.size + 3) / 4 else 1
        private fun tileW() = (width - 5 * gap) / 4

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (rows() * (tileH + gap) + gap).toInt())

        override fun onDraw(c: Canvas) {
            val tw = tileW()
            for (i in 0 until minOf(actions.size, rows() * 4)) {
                val a = actions[i]
                val x = gap + (i % 4) * (tw + gap); val y = gap + (i / 4) * (tileH + gap)
                val on = a.state()
                fill.color = if (i == pressedIdx) p.chromeHigh else if (on) p.accent else if (p.dark) 0xFF333333.toInt() else 0xFFCCCCCC.toInt()
                c.drawRect(x, y, x + tw, y + tileH, fill)
                val fg = if (on || p.dark) Color.WHITE else Color.BLACK
                IconPack.glyph(a.glyph)?.draw(c, x + context.dp(8f), y + context.dp(8f), context.dp(18f), gp, fg)
                tp.color = fg
                val label = a.detail?.invoke() ?: context.getString(a.label)
                val t = TextUtils.ellipsize(label, tp, tw - context.dp(12f), TextUtils.TruncateAt.END)
                c.drawText(t, 0, t.length, x + context.dp(8f), y + tileH - context.dp(6f) - tp.descent(), tp)
            }
        }

        private fun indexAt(x: Float, y: Float): Int {
            val col = ((x - gap) / (tileW() + gap)).toInt().coerceIn(0, 3)
            val row = ((y - gap) / (tileH + gap)).toInt().coerceIn(0, rows() - 1)
            return (row * 4 + col).takeIf { it < actions.size } ?: -1
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pressedIdx = indexAt(e.x, e.y); invalidate() }
                MotionEvent.ACTION_UP -> {
                    val i = pressedIdx; pressedIdx = -1; invalidate()
                    if (i >= 0 && i == indexAt(e.x, e.y)) { actions[i].tap(); postDelayed({ invalidate() }, 400) }
                }
                MotionEvent.ACTION_CANCEL -> { pressedIdx = -1; invalidate() }
            }
            return true
        }
    }
}
