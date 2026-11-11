package com.w10m.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.icu.text.TimeZoneNames
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Alarms & Clock in the W10M layout: a four-item pivot (alarm · world clock · timer · stopwatch)
 * with a bottom command bar. Alarms ring through AlarmManager even when the app is closed.
 */
class ClockActivity : Activity() {

    private lateinit var p: Palette
    private lateinit var prefs: Prefs
    private lateinit var shell: Shell
    private lateinit var pivot: PivotHeader
    private lateinit var body: FrameLayout
    private lateinit var bar: LinearLayout
    private var tab = 0
    private val ui = Handler(Looper.getMainLooper())
    private val clockPrefs by lazy { getSharedPreferences("w10m_clock", MODE_PRIVATE) }
    private var editor: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this); p = prefs.palette()
        shell = Shell(this, prefs, p)
        val root = FrameLayout(this).apply { setBackgroundColor(p.bg) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(pageTitle(this, p, getString(R.string.clock)))
        pivot = PivotHeader(this, p, listOf(getString(R.string.alarm), getString(R.string.world_clock), getString(R.string.timer), getString(R.string.stopwatch))) { show(it) }
        col.addView(android.widget.HorizontalScrollView(this).apply { addView(pivot); isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER })
        body = FrameLayout(this)
        col.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        bar = LinearLayout(this).apply { setBackgroundColor(p.chromeMedium); gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(bar, LinearLayout.LayoutParams(-1, dpi(48f)))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        shell.setContent(root)
        show(clockPrefs.getInt("tab", 0))
        // Alarms ring through a full-screen notification, which needs notification permission on Android 13+.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    private val tick = object : Runnable {
        override fun run() { onTick(); ui.postDelayed(this, if (tab == 3 && swRunning) 30 else 500) }
    }
    private var onTick: () -> Unit = {}

    private fun show(i: Int) {
        tab = i; pivot.selected = i
        clockPrefs.edit().putInt("tab", i).apply()
        body.removeAllViews(); bar.removeAllViews(); onTick = {}
        val page = when (i) { 0 -> alarmsPage(); 1 -> worldPage(); 2 -> timerPage(); else -> stopwatchPage() }
        body.addView(page, FrameLayout.LayoutParams(-1, -1))
        Motion.pageRefresh(page)
        ui.removeCallbacks(tick); ui.post(tick)
    }

    private fun barButton(glyph: (Canvas, Paint, Float, Float, Float) -> Unit, tap: () -> Unit) =
        bar.addView(GlyphButton(this, p.fg, glyph).apply { setOnClickListener { tap() } }, LinearLayout.LayoutParams(dpi(72f), -1))

    private val plusGlyph: (Canvas, Paint, Float, Float, Float) -> Unit = { c, pt, cx, cy, u ->
        c.drawLine(cx - 8 * u, cy, cx + 8 * u, cy, pt); c.drawLine(cx, cy - 8 * u, cx, cy + 8 * u, pt)
    }

    private fun label(t: String, size: Float, tf: android.graphics.Typeface, color: Int) = TextView(this).apply {
        text = t; textSize = size; typeface = tf; setTextColor(color); includeFontPadding = false
    }

    // ================================================================ alarms

    private fun dayNames(): List<String> {
        val loc = Locale.getDefault()
        val s = DateFormatSymbols.getInstance(loc).shortWeekdays
        return (1..7).map { s[it] }
    }

    private fun daysText(d: Int): String = when (d) {
        0 -> getString(R.string.only_once)
        0b1111111 -> getString(R.string.every_day)
        0b0111110 -> getString(R.string.weekdays)
        else -> dayNames().filterIndexed { i, _ -> d and (1 shl i) != 0 }.joinToString(", ")
    }

    private fun timeText(h: Int, m: Int): String {
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m) }
        return DateFormat.getTimeFormat(this).format(c.time)
    }

    private fun alarmsPage(): View {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dpi(12f)) }
        val m = dpi(Metro.PAGE_MARGIN)
        val alarms = Alarms.load(this).sortedWith(compareBy({ it.hour }, { it.minute }))
        if (!Alarms.canSchedule(this)) list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(m, dpi(4f), m, dpi(12f))
            addView(label(getString(R.string.exact_alarm_needed), 15f, Metro.regular(context), p.fgMedium))
            addView(metroButton(context, p, getString(R.string.grant)) {
                try { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:$packageName"))) } catch (_: Exception) {}
            }, LinearLayout.LayoutParams(-2, dpi(32f)).apply { topMargin = dpi(8f) })
        })
        if (alarms.isEmpty()) list.addView(label(getString(R.string.no_alarms), 15f, Metro.regular(this), p.fgMedium).apply { setPadding(m, dpi(4f), m, 0) })
        for (a in alarms) {
            // Row: time 34 Light (60 % when off), name + repeat 12 underneath, toggle on the right.
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(m, dpi(8f), m, dpi(8f)) }
            val fg = if (a.on) p.fg else p.fgMedium
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(timeText(a.hour, a.minute), 34f, Metro.light(context), fg))
                addView(label(listOf(a.name, daysText(a.days)).filter { it.isNotEmpty() }.joinToString(" · "), 12f, Metro.regular(context), p.fgMedium)
                    .apply { setPadding(0, dpi(4f), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(MetroToggle(this, p).apply {
                checked = a.on
                onChange = { on -> val l = Alarms.load(context); l.firstOrNull { it.id == a.id }?.on = on; Alarms.save(context, l); show(0) }
            }, LinearLayout.LayoutParams(dpi(96f), -2))
            row.setOnClickListener { editAlarm(a) }
            list.addView(row)
        }
        barButton(plusGlyph) { editAlarm(null) }
        return ScrollView(this).apply { addView(list); overScrollMode = View.OVER_SCROLL_NEVER }
    }

    /** New / edit alarm page: looping time selector, name, repeat day squares, save / delete / cancel. */
    private fun editAlarm(a: Alarm?) {
        val m = dpi(Metro.PAGE_MARGIN)
        val now = Calendar.getInstance()
        val h24 = DateFormat.is24HourFormat(this)
        var hour = a?.hour ?: now.get(Calendar.HOUR_OF_DAY)
        var minute = a?.minute ?: 0
        var days = a?.days ?: 0
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg); isClickable = true }
        page.addView(pageTitle(this, p, getString(if (a == null) R.string.new_alarm else R.string.edit_alarm)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(m, 0, m, dpi(16f)) }

        val pickers = LinearLayout(this)
        val hours = if (h24) (0..23).map { "%02d".format(it) } else (1..12).map { it.toString() }
        val hourSel = LoopingSelector(this, p, hours)
        val minSel = LoopingSelector(this, p, (0..59).map { "%02d".format(it) })
        val ampm = DateFormatSymbols.getInstance().amPmStrings.toList()
        val apSel = if (h24) null else LoopingSelector(this, p, ampm, loop = false)
        fun sync() {
            minute = minSel.value
            hour = if (h24) hourSel.value else (hourSel.value + 1) % 12 + (if ((apSel?.value ?: 0) == 1) 12 else 0)
        }
        hourSel.value = if (h24) hour else (hour + 11) % 12
        minSel.value = minute
        apSel?.value = if (hour >= 12) 1 else 0
        hourSel.onChange = { sync() }; minSel.onChange = { sync() }; apSel?.onChange = { sync() }
        pickers.addView(hourSel, LinearLayout.LayoutParams(0, -2, 1f))
        pickers.addView(minSel, LinearLayout.LayoutParams(0, -2, 1f))
        apSel?.let { pickers.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        col.addView(pickers, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(16f) })

        col.addView(label(getString(R.string.alarm_name), 15f, Metro.regular(this), p.fg).apply { setPadding(0, 0, 0, dpi(8f)) })
        val name = EditText(this).apply {
            setText(a?.name ?: ""); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Metro.regular(context)
            setTextColor(p.fg); setHint(R.string.alarm); setHintTextColor(p.fgMedium); isSingleLine = true
            setPadding(dpi(10f), 0, dpi(10f), 0)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(if (p.dark) 0x66000000 else 0x66FFFFFF); setStroke(dpi(2f), p.inputBorder) }
        }
        col.addView(name, LinearLayout.LayoutParams(-1, dpi(32f)))

        col.addView(label(getString(R.string.repeats), 15f, Metro.regular(this), p.fg).apply { setPadding(0, dpi(20f), 0, dpi(8f)) })
        val dayRow = LinearLayout(this)
        val names = dayNames()
        val first = Calendar.getInstance().firstDayOfWeek
        for (k in 0..6) {
            val d = (first - 1 + k) % 7   // bit index
            val box = TextView(this).apply {
                text = names[d].take(if (Locale.getDefault().language == "zh") 1 else 2)
                textSize = 15f; typeface = Metro.regular(context); gravity = Gravity.CENTER
            }
            fun paint() {
                val on = days and (1 shl d) != 0
                box.setBackgroundColor(if (on) p.accent else p.chromeMedium)
                box.setTextColor(if (on) Color.WHITE else p.fg)
            }
            paint()
            box.setOnClickListener { days = days xor (1 shl d); paint() }
            dayRow.addView(box, LinearLayout.LayoutParams(0, dpi(40f), 1f).apply { if (k < 6) marginEnd = dpi(4f) })
        }
        col.addView(dayRow)
        page.addView(ScrollView(this).apply { addView(col); overScrollMode = View.OVER_SCROLL_NEVER }, LinearLayout.LayoutParams(-1, 0, 1f))

        val edBar = LinearLayout(this).apply { setBackgroundColor(p.chromeMedium); gravity = Gravity.CENTER_HORIZONTAL }
        fun edButton(glyph: (Canvas, Paint, Float, Float, Float) -> Unit, tap: () -> Unit) =
            edBar.addView(GlyphButton(this, p.fg, glyph).apply { setOnClickListener { tap() } }, LinearLayout.LayoutParams(dpi(72f), -1))
        edButton({ c, pt, cx, cy, u -> c.drawLine(cx - 8 * u, cy, cx - 2 * u, cy + 6 * u, pt); c.drawLine(cx - 2 * u, cy + 6 * u, cx + 9 * u, cy - 6 * u, pt) }) {
            sync()
            val list = Alarms.load(this)
            val target = list.firstOrNull { it.id == a?.id }
            if (target != null) { target.hour = hour; target.minute = minute; target.name = name.text.toString(); target.days = days; target.on = true }
            else list += Alarm(Alarms.newId(this), hour, minute, name.text.toString(), days, true)
            Alarms.save(this, list)
            closeEditor(); show(0)
        }
        if (a != null) edButton({ c, pt, cx, cy, u ->
            c.drawRect(cx - 6 * u, cy - 5 * u, cx + 6 * u, cy + 9 * u, pt); c.drawLine(cx - 9 * u, cy - 5 * u, cx + 9 * u, cy - 5 * u, pt)
            c.drawLine(cx - 3 * u, cy - 8 * u, cx + 3 * u, cy - 8 * u, pt)
        }) { Alarms.delete(this, a); closeEditor(); show(0) }
        edButton({ c, pt, cx, cy, u -> c.drawLine(cx - 7 * u, cy - 7 * u, cx + 7 * u, cy + 7 * u, pt); c.drawLine(cx + 7 * u, cy - 7 * u, cx - 7 * u, cy + 7 * u, pt) }) { closeEditor() }
        page.addView(edBar, LinearLayout.LayoutParams(-1, dpi(48f)))

        (body.parent.parent as FrameLayout).addView(page, FrameLayout.LayoutParams(-1, -1))
        editor = page
        page.post { Motion.drillIn(page) }
    }

    private fun closeEditor() {
        val e = editor ?: return
        editor = null
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(e.windowToken, 0)
        Motion.drillOut(e) { (e.parent as? FrameLayout)?.removeView(e) }
    }

    // ================================================================ world clock

    private fun zones(): MutableList<String> =
        clockPrefs.getString("zones", null)?.split(',')?.filter { it.isNotEmpty() }?.toMutableList()
            ?: mutableListOf(TimeZone.getDefault().id)

    private fun cityName(id: String): String {
        val n = try { TimeZoneNames.getInstance(Locale.getDefault()).getExemplarLocationName(id) } catch (_: Exception) { null }
        return n ?: id.substringAfterLast('/').replace('_', ' ')
    }

    private fun offsetText(id: String): String {
        val now = Instant.now()
        val diffMin = (ZoneId.of(id).rules.getOffset(now).totalSeconds - ZoneId.systemDefault().rules.getOffset(now).totalSeconds) / 60
        if (diffMin == 0) return getString(R.string.same_time)
        val h = kotlin.math.abs(diffMin) / 60; val mm = kotlin.math.abs(diffMin) % 60
        val amount = if (mm == 0) "$h" else "$h:%02d".format(mm)
        return getString(if (diffMin > 0) R.string.hours_ahead else R.string.hours_behind, amount)
    }

    private fun worldPage(): View {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val m = dpi(Metro.PAGE_MARGIN)
        val rows = ArrayList<Pair<String, Pair<TextView, TextView>>>()
        for (id in zones()) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(m, dpi(8f), m, dpi(8f)) }
            val day = label("", 12f, Metro.regular(this), p.fgMedium)
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(cityName(id), 20f, Metro.semilight(context), p.fg))
                addView(day.apply { setPadding(0, dpi(2f), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val time = label("", 34f, Metro.light(this), p.fg)
            row.addView(time)
            row.setOnLongClickListener {
                MetroMenu.show(row, p, listOf(getString(R.string.delete) to {
                    clockPrefs.edit().putString("zones", zones().filter { it != id }.joinToString(",")).apply(); show(1)
                })); true
            }
            list.addView(row)
            rows += id to (time to day)
        }
        onTick = {
            val now = System.currentTimeMillis()
            val loc = Locale.getDefault()
            for ((id, v) in rows) {
                val tz = TimeZone.getTimeZone(id)
                val f = DateFormat.getTimeFormat(this).apply { timeZone = tz }
                v.first.text = f.format(now)
                val dayFmt = java.text.SimpleDateFormat(DateFormat.getBestDateTimePattern(loc, "EEEMMMd"), loc).apply { timeZone = tz }
                v.second.text = dayFmt.format(now) + " · " + offsetText(id)
            }
        }
        barButton(plusGlyph) { pickZone() }
        return ScrollView(this).apply { addView(list); overScrollMode = View.OVER_SCROLL_NEVER }
    }

    /** City picker: search box over every time zone, localized city names. */
    private fun pickZone() {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg); isClickable = true }
        page.addView(pageTitle(this, p, getString(R.string.add_city)))
        val search = MetroSearchBox(this, p)
        page.addView(search, LinearLayout.LayoutParams(-1, dpi(32f)).apply { val m = dpi(Metro.PAGE_MARGIN); setMargins(m, 0, m, dpi(8f)) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(ScrollView(this).apply { addView(list); overScrollMode = View.OVER_SCROLL_NEVER }, LinearLayout.LayoutParams(-1, 0, 1f))
        val all = ZoneId.getAvailableZoneIds().filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
            .map { it to cityName(it) }.distinctBy { it.second }.sortedBy { it.second }
        fun fill(q: String) {
            list.removeAllViews()
            all.filter { q.isEmpty() || it.second.contains(q, true) || it.first.contains(q, true) }.take(80).forEach { (id, n) ->
                list.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; val m = dpi(Metro.PAGE_MARGIN); setPadding(m, dpi(8f), m, dpi(8f))
                    addView(label(n, 20f, Metro.semilight(context), p.fg))
                    addView(label(id.replace('_', ' ') + " · " + offsetText(id), 12f, Metro.regular(context), p.fgMedium))
                    setOnClickListener {
                        val z = zones(); if (id !in z) z += id
                        clockPrefs.edit().putString("zones", z.joinToString(",")).apply()
                        closeEditor(); show(1)
                    }
                })
            }
        }
        search.edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = fill(s.toString().trim())
        })
        fill("")
        (body.parent.parent as FrameLayout).addView(page, FrameLayout.LayoutParams(-1, -1))
        editor = page
        page.post { Motion.drillIn(page) }
    }

    // ================================================================ timer

    private fun timerPage(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val endAt = clockPrefs.getLong("timer_end", 0L)
        val left = clockPrefs.getLong("timer_left", 0L)       // remaining when paused
        val total = clockPrefs.getLong("timer_total", 5 * 60_000L)
        val running = endAt > System.currentTimeMillis()
        val ring = TimerRing(this)
        col.addView(ring, LinearLayout.LayoutParams(dpi(260f), dpi(260f)).apply { topMargin = dpi(12f) })

        if (!running && left == 0L) {
            // Idle: set the duration with three looping columns (h · min · s) under the ring.
            ring.visibility = View.GONE
            val pickers = LinearLayout(this).apply { setPadding(dpi(Metro.PAGE_MARGIN), dpi(24f), dpi(Metro.PAGE_MARGIN), 0) }
            val hs = LoopingSelector(this, p, (0..23).map { "%02d".format(it) })
            val ms = LoopingSelector(this, p, (0..59).map { "%02d".format(it) })
            val ss = LoopingSelector(this, p, (0..59).map { "%02d".format(it) })
            hs.value = (total / 3_600_000).toInt(); ms.value = (total / 60_000 % 60).toInt(); ss.value = (total / 1000 % 60).toInt()
            listOf(hs, ms, ss).forEach { pickers.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
            val heads = LinearLayout(this).apply { setPadding(dpi(Metro.PAGE_MARGIN), dpi(8f), dpi(Metro.PAGE_MARGIN), 0) }
            listOf(R.string.hours_short, R.string.minutes_short, R.string.seconds_short).forEach {
                heads.addView(label(getString(it), 12f, Metro.regular(this), p.fgMedium).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            col.addView(heads, LinearLayout.LayoutParams(-1, -2)); col.addView(pickers, LinearLayout.LayoutParams(-1, -2))
            barButton(playGlyph(false)) {
                val t = (hs.value * 3600L + ms.value * 60L + ss.value) * 1000L
                if (t <= 0) return@barButton
                val end = System.currentTimeMillis() + t
                clockPrefs.edit().putLong("timer_total", t).putLong("timer_end", end).putLong("timer_left", 0).apply()
                Alarms.scheduleTimer(this, end); show(2)
            }
            return col
        }
        onTick = {
            val now = System.currentTimeMillis()
            val rem = if (running) (endAt - now).coerceAtLeast(0) else left
            ring.fraction = rem.toFloat() / total; ring.text = clockText(rem + 999); ring.invalidate()
            if (running && rem <= 0) show(2)
        }
        barButton(playGlyph(running)) {
            val now = System.currentTimeMillis()
            if (running) { clockPrefs.edit().putLong("timer_left", endAt - now).putLong("timer_end", 0).apply(); Alarms.cancelTimer(this) }
            else { val end = now + left; clockPrefs.edit().putLong("timer_end", end).putLong("timer_left", 0).apply(); Alarms.scheduleTimer(this, end) }
            show(2)
        }
        barButton(resetGlyph) {
            clockPrefs.edit().putLong("timer_end", 0).putLong("timer_left", 0).apply(); Alarms.cancelTimer(this); show(2)
        }
        return col
    }

    private fun playGlyph(pause: Boolean): (Canvas, Paint, Float, Float, Float) -> Unit = { c, pt, cx, cy, u ->
        if (pause) { c.drawLine(cx - 4 * u, cy - 8 * u, cx - 4 * u, cy + 8 * u, pt); c.drawLine(cx + 4 * u, cy - 8 * u, cx + 4 * u, cy + 8 * u, pt) }
        else { val path = Path(); path.moveTo(cx - 5 * u, cy - 8 * u); path.lineTo(cx + 8 * u, cy); path.lineTo(cx - 5 * u, cy + 8 * u); path.close(); c.drawPath(path, pt) }
    }

    private val resetGlyph: (Canvas, Paint, Float, Float, Float) -> Unit = { c, pt, cx, cy, u ->
        c.drawArc(cx - 8 * u, cy - 8 * u, cx + 8 * u, cy + 8 * u, -60f, 300f, false, pt)
        c.drawLine(cx + 4 * u, cy - 10 * u, cx + 4 * u, cy - 6.9f * u, pt); c.drawLine(cx + 4 * u, cy - 6.9f * u, cx + 7.5f * u, cy - 6.9f * u, pt)
    }

    /** Timer ring: 2 epx track, accent arc for the remaining time, remaining time 46 Light in the middle. */
    private inner class TimerRing(c: Context) : View(c) {
        var fraction = 1f
        var text = ""
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = c.dp(2f) }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = c.dp(6f); strokeCap = Paint.Cap.BUTT }
        private val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.sp(46f); typeface = Metro.light(c); textAlign = Paint.Align.CENTER }
        private val r = RectF()
        override fun onDraw(cv: Canvas) {
            val pad = dp(8f); r.set(pad, pad, width - pad, height - pad)
            track.color = p.chromeHigh; cv.drawOval(r, track)
            arc.color = p.accent; cv.drawArc(r, -90f, 360f * fraction.coerceIn(0f, 1f), false, arc)
            tp.color = p.fg; cv.drawText(text, width / 2f, height / 2f - (tp.ascent() + tp.descent()) / 2, tp)
        }
    }

    // ================================================================ stopwatch

    private var swRunning = false

    private fun stopwatchPage(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val startedAt = clockPrefs.getLong("sw_start", 0L)      // elapsedRealtime when (re)started, 0 = stopped
        val acc = clockPrefs.getLong("sw_acc", 0L)              // time banked before the last start
        val laps = clockPrefs.getString("sw_laps", "")!!.split(',').filter { it.isNotEmpty() }.map { it.toLong() }
        swRunning = startedAt > 0
        fun elapsed() = acc + if (swRunning) SystemClock.elapsedRealtime() - startedAt else 0L
        // Big readout with unit captions centred under each digit group, as in the Windows clock (00:00:00.00 · hr min sec).
        val big = Readout(this)
        col.addView(big, LinearLayout.LayoutParams(-1, dpi(96f)).apply { topMargin = dpi(16f) })
        val lapList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val m = dpi(Metro.PAGE_MARGIN)
        var prev = 0L
        val rows = laps.mapIndexed { i, t -> val split = t - prev; prev = t; Triple(i + 1, split, t) }.reversed()
        val best = rows.minOfOrNull { it.second }; val worst = rows.maxOfOrNull { it.second }
        for ((n, split, t) in rows) lapList.addView(LinearLayout(this).apply {
            setPadding(m, dpi(8f), m, dpi(8f))
            val c = if (rows.size > 1 && split == best) p.accent else if (rows.size > 1 && split == worst) p.fgMedium else p.fg
            addView(label(getString(R.string.lap_n, n), 15f, Metro.regular(context), c), LinearLayout.LayoutParams(0, -2, 1f))
            addView(label(swText(split), 15f, Metro.regular(context), c), LinearLayout.LayoutParams(0, -2, 1f))
            addView(label(swText(t), 15f, Metro.regular(context), p.fgMedium).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        col.addView(ScrollView(this).apply { addView(lapList); overScrollMode = View.OVER_SCROLL_NEVER }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dpi(24f) })
        onTick = { big.text = swText(elapsed()); big.invalidate() }
        barButton(playGlyph(swRunning)) {
            if (swRunning) clockPrefs.edit().putLong("sw_acc", elapsed()).putLong("sw_start", 0).apply()
            else clockPrefs.edit().putLong("sw_start", SystemClock.elapsedRealtime()).apply()
            show(3)
        }
        // Lap: a flag.
        barButton({ c, pt, cx, cy, u ->
            c.drawLine(cx - 6 * u, cy - 9 * u, cx - 6 * u, cy + 9 * u, pt)
            val path = Path(); path.moveTo(cx - 6 * u, cy - 9 * u); path.lineTo(cx + 8 * u, cy - 9 * u); path.lineTo(cx + 5 * u, cy - 5 * u)
            path.lineTo(cx + 8 * u, cy - 1 * u); path.lineTo(cx - 6 * u, cy - 1 * u); c.drawPath(path, pt)
        }) { if (swRunning) { clockPrefs.edit().putString("sw_laps", (laps + elapsed()).joinToString(",")).apply(); show(3) } }
        barButton(resetGlyph) { clockPrefs.edit().putLong("sw_acc", 0).putLong("sw_start", 0).putString("sw_laps", "").apply(); show(3) }
        return col
    }

    /** "00:00:00.00" in 54 Light, captions 12 at 60 % centred under the hour, minute and second pairs. */
    private inner class Readout(c: Context) : View(c) {
        var text = "00:00:00.00"
        private val big = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.sp(54f); typeface = Metro.light(c) }
        private val cap = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.sp(12f); typeface = Metro.regular(c); textAlign = Paint.Align.CENTER }
        override fun onDraw(cv: Canvas) {
            big.color = p.fg; cap.color = p.fgMedium
            val x0 = (width - big.measureText(text)) / 2
            val base = dp(8f) - big.ascent()
            cv.drawText(text, x0, base, big)
            val labels = listOf(R.string.hours_short, R.string.minutes_short, R.string.seconds_short)
            for (k in 0..2) {
                val start = k * 3
                val cx = x0 + big.measureText(text, 0, start) + big.measureText(text, start, start + 2) / 2
                cv.drawText(getString(labels[k]), cx, base + big.descent() + dp(4f) - cap.ascent(), cap)
            }
        }
    }

    private fun swText(ms: Long): String {
        val cs = ms / 10
        return "%02d:%02d:%02d.%02d".format(cs / 360000, cs / 6000 % 60, cs / 100 % 60, cs % 100)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            shell.handleBack() -> Unit
            editor != null -> closeEditor()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }
}

/**
 * Ringing alarm / finished timer, shown over the lock screen: accent band with the alarm glyph,
 * the time in 46 Light, the alarm name, and Snooze / Dismiss buttons. Plays the default alarm sound.
 */
class AlarmRingActivity : Activity() {
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = Prefs(this); val p = prefs.palette()
        val id = intent.getIntExtra(Alarms.EXTRA_ID, 0)
        val timer = intent.getBooleanExtra(Alarms.EXTRA_TIMER, false)
        val name = if (timer) getString(R.string.timer_done) else Alarms.load(this).firstOrNull { it.id == id }?.name?.ifEmpty { null } ?: getString(R.string.alarm)
        val m = dpi(Metro.PAGE_MARGIN)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg); setPadding(0, dpi(48f), 0, dpi(24f)) }
        root.addView(object : View(this) {
            val gp = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                c.drawColor(p.accent)
                IconPack.glyph("clock")?.draw(c, width / 2f - dp(24f), height / 2f - dp(24f), dp(48f), gp)
            }
        }, LinearLayout.LayoutParams(-1, dpi(120f)))
        root.addView(TextView(this).apply {
            text = DateFormat.getTimeFormat(context).format(System.currentTimeMillis()); textSize = 46f; typeface = Metro.light(context)
            setTextColor(p.fg); setPadding(m, dpi(24f), m, 0)
        })
        root.addView(TextView(this).apply { text = name; textSize = 20f; typeface = Metro.semilight(context); setTextColor(p.fg); setPadding(m, dpi(4f), m, 0) })
        root.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        val buttons = LinearLayout(this).apply { setPadding(m, 0, m, 0) }
        if (!timer) buttons.addView(metroButton(this, p, getString(R.string.snooze)) { Alarms.snooze(this, id, 10); finishRing() },
            LinearLayout.LayoutParams(0, dpi(40f), 1f).apply { marginEnd = dpi(8f) })
        buttons.addView(metroButton(this, p, getString(R.string.dismiss)) { finishRing() }, LinearLayout.LayoutParams(0, dpi(40f), 1f))
        root.addView(buttons)
        setContentView(root)

        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ringtone = try {
            RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        } catch (_: Exception) { null }
        vibrator = (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.also {
            try { it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 600), 0)) } catch (_: Exception) {}
        }
    }

    private fun finishRing() {
        ringtone?.stop(); vibrator?.cancel()
        getSystemService(NotificationManager::class.java).cancel(AlarmReceiver.NOTE_ID)
        if (intent.getBooleanExtra(Alarms.EXTRA_TIMER, false))
            getSharedPreferences("w10m_clock", MODE_PRIVATE).edit().putLong("timer_end", 0).putLong("timer_left", 0).apply()
        finish()
    }

    override fun onDestroy() { super.onDestroy(); ringtone?.stop(); vibrator?.cancel() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = finishRing()
}
