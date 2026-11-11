package com.w10m.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.provider.CalendarContract
import android.text.TextPaint
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Calendar in the W10M layout: month title, weekday row, six-week grid (today filled with the accent,
 * selected day outlined, days with events underlined), and the selected day's agenda below.
 * Shows the system calendars; creating and opening events is handed to the system calendar editor.
 */
class CalendarActivity : Activity() {

    private class Event(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val color: Int, val where: String)

    private lateinit var p: Palette
    private lateinit var shell: Shell
    private lateinit var monthTitle: TextView
    private lateinit var grid: MonthView
    private lateinit var dayTitle: TextView
    private lateinit var agenda: LinearLayout
    private val month: Calendar = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); clearTime() }
    private val selected: Calendar = Calendar.getInstance().apply { clearTime() }
    private var events: List<Event> = emptyList()

    private fun Calendar.clearTime() { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this); p = prefs.palette()
        shell = Shell(this, prefs, p)
        val m = dpi(Metro.PAGE_MARGIN)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg) }
        monthTitle = TextView(this).apply {
            textSize = 24f; typeface = Metro.semilight(context); setTextColor(p.fg); setPadding(m, dpi(8f), m, dpi(8f))
            setOnClickListener { goToday() }
        }
        root.addView(monthTitle)
        grid = MonthView(this)
        root.addView(grid, LinearLayout.LayoutParams(-1, -2))
        dayTitle = TextView(this).apply { textSize = 15f; typeface = Metro.semibold(context); setTextColor(p.fg); setPadding(m, dpi(12f), m, dpi(4f)) }
        root.addView(dayTitle)
        agenda = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(agenda); overScrollMode = View.OVER_SCROLL_NEVER }, LinearLayout.LayoutParams(-1, 0, 1f))
        // Command bar: today, new event.
        val gp = Paint(Paint.ANTI_ALIAS_FLAG)
        root.addView(LinearLayout(this).apply {
            setBackgroundColor(p.chromeMedium); gravity = Gravity.CENTER_HORIZONTAL
            addView(GlyphButton(context, p.fg) { c, _, cx, cy, u -> IconPack.glyph("calendar")?.draw(c, cx - 10 * u, cy - 10 * u, 20 * u, gp, p.fg) }
                .apply { setOnClickListener { goToday() } }, LinearLayout.LayoutParams(dpi(72f), -1))
            addView(GlyphButton(context, p.fg) { c, pt, cx, cy, u ->
                c.drawLine(cx - 8 * u, cy, cx + 8 * u, cy, pt); c.drawLine(cx, cy - 8 * u, cx, cy + 8 * u, pt)
            }.apply {
                setOnClickListener {
                    val start = selected.timeInMillis + 9 * 3600_000L
                    try {
                        startActivity(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 3600_000L))
                    } catch (_: Exception) {}
                }
            }, LinearLayout.LayoutParams(dpi(72f), -1))
        }, LinearLayout.LayoutParams(-1, dpi(48f)))
        shell.setContent(root)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun has() = checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = refresh()

    private fun goToday() {
        val now = Calendar.getInstance()
        month.set(now.get(Calendar.YEAR), now.get(Calendar.MONTH), 1)
        selected.set(now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH))
        refresh()
    }

    private fun shiftMonth(d: Int) {
        month.add(Calendar.MONTH, d)
        selected.timeInMillis = month.timeInMillis
        refresh()
    }

    /** First day shown in the six-week grid. */
    private fun gridStart(): Calendar = (month.clone() as Calendar).apply {
        val shift = (get(Calendar.DAY_OF_WEEK) - firstDayOfWeek + 7) % 7
        add(Calendar.DAY_OF_MONTH, -shift)
    }

    private fun refresh() {
        val loc = Locale.getDefault()
        monthTitle.text = SimpleDateFormat(DateFormat.getBestDateTimePattern(loc, "yMMMM"), loc).format(month.time)
        events = emptyList()
        if (has()) try {
            val from = gridStart().timeInMillis; val to = from + 42 * DateUtils.DAY_IN_MILLIS
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let { ContentUris.appendId(it, from); ContentUris.appendId(it, to); it.build() }
            val out = ArrayList<Event>()
            contentResolver.query(uri, arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR,
                CalendarContract.Instances.EVENT_LOCATION), null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                while (c.moveToNext()) out += Event(c.getLong(0), c.getString(1).orEmpty(), c.getLong(2), c.getLong(3), c.getInt(4) == 1,
                    c.getInt(5) or 0xFF000000.toInt(), c.getString(6).orEmpty())
            }
            events = out
        } catch (_: Exception) {}
        grid.invalidate()
        fillAgenda()
    }

    private fun eventsOn(dayStart: Long): List<Event> {
        val dayEnd = dayStart + DateUtils.DAY_IN_MILLIS
        return events.filter { e ->
            if (e.allDay) {
                // All-day instances are stored in UTC; compare by calendar date.
                val off = java.util.TimeZone.getDefault().getOffset(dayStart)
                e.begin < dayEnd + off && e.end > dayStart + off
            } else e.begin < dayEnd && e.end >= dayStart
        }
    }

    private fun fillAgenda() {
        val loc = Locale.getDefault()
        dayTitle.text = SimpleDateFormat(DateFormat.getBestDateTimePattern(loc, "MMMMdEEEE"), loc).format(selected.time)
        agenda.removeAllViews()
        val m = dpi(Metro.PAGE_MARGIN)
        if (!has()) {
            agenda.addView(TextView(this).apply {
                text = getString(R.string.calendar_permission); textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fgMedium)
                setPadding(m, dpi(4f), m, 0)
            })
            agenda.addView(metroButton(this, p, getString(R.string.grant)) { requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 1) },
                LinearLayout.LayoutParams(-2, dpi(32f)).apply { setMargins(m, dpi(12f), m, 0) })
            return
        }
        val day = eventsOn(selected.timeInMillis)
        if (day.isEmpty()) agenda.addView(TextView(this).apply {
            text = getString(R.string.no_events); textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fgMedium); setPadding(m, dpi(4f), m, 0)
        })
        val tf = DateFormat.getTimeFormat(this)
        for (e in day) agenda.addView(LinearLayout(this).apply {
            setPadding(m, dpi(6f), m, dpi(6f))
            addView(View(context).apply { setBackgroundColor(e.color) }, LinearLayout.LayoutParams(dpi(4f), -1))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dpi(10f), 0, 0, 0)
                addView(TextView(context).apply { text = e.title.ifEmpty { getString(R.string.no_title) }; textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fg) })
                addView(TextView(context).apply {
                    val time = if (e.allDay) getString(R.string.all_day) else tf.format(Date(e.begin)) + " – " + tf.format(Date(e.end))
                    text = if (e.where.isEmpty()) time else "$time · ${e.where}"
                    textSize = 12f; typeface = Metro.regular(context); setTextColor(p.fgMedium)
                })
            })
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id))
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.begin).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end))
                } catch (_: Exception) {}
            }
        })
    }

    /** Weekday header + 6×7 day grid. Swipe left / right to change month. */
    private inner class MonthView(c: Context) : View(c) {
        private val head = c.dp(24f)
        private val cellH = c.dp(40f)
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Metro.regular(c) }
        private val fill = Paint()
        private val ring = Paint().apply { style = Paint.Style.STROKE; strokeWidth = c.dp(2f) }
        private var downX = 0f

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (head + 6 * cellH).toInt())

        override fun onDraw(cv: Canvas) {
            val m = dp(Metro.PAGE_MARGIN); val cw = (width - 2 * m) / 7
            val day = gridStart()
            val today = Calendar.getInstance().apply { clearTime() }.timeInMillis
            // Two-letter weekday heads (su mo tu …) in English, single characters (日 一 二 …) in Chinese.
            val loc = Locale.getDefault()
            tp.textSize = sp(12f); tp.color = p.fgMedium
            val wd = day.clone() as Calendar
            for (i in 0..6) {
                val dow = java.time.DayOfWeek.of((wd.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1)
                val name = if (loc.language == "zh") dow.getDisplayName(java.time.format.TextStyle.NARROW_STANDALONE, loc)
                    else dow.getDisplayName(java.time.format.TextStyle.SHORT, loc).take(2).lowercase(loc)
                cv.drawText(name, m + cw * i + cw / 2, head / 2 - (tp.ascent() + tp.descent()) / 2, tp); wd.add(Calendar.DAY_OF_MONTH, 1)
            }
            tp.textSize = sp(15f)
            for (i in 0 until 42) {
                val x = m + (i % 7) * cw; val y = head + (i / 7) * cellH
                val t = day.timeInMillis
                val inMonth = day.get(Calendar.MONTH) == month.get(Calendar.MONTH)
                val isToday = t == today
                if (isToday) { fill.color = p.accent; cv.drawRect(x + dp(2f), y + dp(2f), x + cw - dp(2f), y + cellH - dp(2f), fill) }
                if (t == selected.timeInMillis && !isToday) { ring.color = p.accent; cv.drawRect(x + dp(3f), y + dp(3f), x + cw - dp(3f), y + cellH - dp(3f), ring) }
                tp.color = if (isToday) Color.WHITE else if (inMonth) p.fg else p.fgLow
                cv.drawText(day.get(Calendar.DAY_OF_MONTH).toString(), x + cw / 2, y + cellH / 2 - (tp.ascent() + tp.descent()) / 2, tp)
                if (eventsOn(t).isNotEmpty()) {
                    fill.color = if (isToday) Color.WHITE else p.accent
                    cv.drawRect(x + cw / 2 - dp(6f), y + cellH - dp(8f), x + cw / 2 + dp(6f), y + cellH - dp(6f), fill)
                }
                day.add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> downX = e.x
                MotionEvent.ACTION_UP -> {
                    val dx = e.x - downX
                    if (abs(dx) > dp(48f)) { shiftMonth(if (dx < 0) 1 else -1); return true }
                    if (e.y < head) return true
                    val m = dp(Metro.PAGE_MARGIN); val cw = (width - 2 * m) / 7
                    val i = ((e.y - head) / cellH).toInt().coerceIn(0, 5) * 7 + ((e.x - m) / cw).toInt().coerceIn(0, 6)
                    val d = gridStart().apply { add(Calendar.DAY_OF_MONTH, i) }
                    selected.timeInMillis = d.timeInMillis
                    if (d.get(Calendar.MONTH) != month.get(Calendar.MONTH)) { month.set(d.get(Calendar.YEAR), d.get(Calendar.MONTH), 1) }
                    refresh()
                }
            }
            return true
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (!shell.handleBack()) @Suppress("DEPRECATION") super.onBackPressed() }
}
