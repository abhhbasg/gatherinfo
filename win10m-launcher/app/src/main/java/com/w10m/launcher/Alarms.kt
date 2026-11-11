package com.w10m.launcher

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.text.TextPaint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.Scroller
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** One alarm. [days] is a bit set over Calendar.SUNDAY..SATURDAY (bit = day - 1); 0 = only once. */
class Alarm(val id: Int, var hour: Int, var minute: Int, var name: String, var days: Int, var on: Boolean) {
    fun toJson() = JSONObject().put("id", id).put("h", hour).put("m", minute).put("n", name).put("d", days).put("on", on)

    /** Next time this alarm rings, after [from]. */
    fun next(from: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply { timeInMillis = from; set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        for (i in 0..7) {
            if (c.timeInMillis > from && (days == 0 || days and (1 shl (c.get(Calendar.DAY_OF_WEEK) - 1)) != 0)) return c.timeInMillis
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
        return c.timeInMillis
    }

    companion object {
        fun fromJson(o: JSONObject) = Alarm(o.getInt("id"), o.getInt("h"), o.getInt("m"), o.optString("n"), o.optInt("d"), o.optBoolean("on", true))
    }
}

/** Alarm storage and scheduling through AlarmManager.setAlarmClock (shows in the system's next-alarm indicator). */
object Alarms {
    const val EXTRA_ID = "alarm_id"
    const val EXTRA_TIMER = "timer"
    const val CHANNEL = "alarms"
    private const val TIMER_ID = -1

    private fun prefs(c: Context) = c.getSharedPreferences("w10m_alarms", Context.MODE_PRIVATE)

    fun load(c: Context): MutableList<Alarm> = try {
        val a = JSONArray(prefs(c).getString("list", "[]"))
        MutableList(a.length()) { Alarm.fromJson(a.getJSONObject(it)) }
    } catch (_: Exception) { mutableListOf() }

    fun save(c: Context, list: List<Alarm>) {
        val a = JSONArray(); list.forEach { a.put(it.toJson()) }
        prefs(c).edit().putString("list", a.toString()).apply()
        list.forEach { schedule(c, it) }
    }

    fun newId(c: Context) = (load(c).maxOfOrNull { it.id } ?: 0) + 1

    private fun pending(c: Context, id: Int, timer: Boolean = false): PendingIntent =
        PendingIntent.getBroadcast(c, id, Intent(c, AlarmReceiver::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_TIMER, timer),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun showIntent(c: Context) = PendingIntent.getActivity(c, 0, Intent(c, ClockActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun am(c: Context) = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** True when the system lets this app set exact alarms (Android 12+ asks the user once). */
    fun canSchedule(c: Context) = Build.VERSION.SDK_INT < 31 || am(c).canScheduleExactAlarms()

    fun schedule(c: Context, a: Alarm) {
        val pi = pending(c, a.id)
        am(c).cancel(pi)
        if (!a.on || !canSchedule(c)) return
        try { am(c).setAlarmClock(AlarmManager.AlarmClockInfo(a.next(), showIntent(c)), pi) } catch (_: SecurityException) {}
    }

    fun delete(c: Context, a: Alarm) {
        am(c).cancel(pending(c, a.id))
        save(c, load(c).filter { it.id != a.id })
    }

    fun rescheduleAll(c: Context) = load(c).forEach { schedule(c, it) }

    fun scheduleTimer(c: Context, at: Long) {
        if (!canSchedule(c)) return
        try { am(c).setAlarmClock(AlarmManager.AlarmClockInfo(at, showIntent(c)), pending(c, TIMER_ID, true)) } catch (_: SecurityException) {}
    }

    fun cancelTimer(c: Context) = am(c).cancel(pending(c, TIMER_ID, true))

    fun snooze(c: Context, id: Int, minutes: Int) {
        if (!canSchedule(c)) return
        try {
            am(c).setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + minutes * 60_000L, showIntent(c)), pending(c, id))
        } catch (_: SecurityException) {}
    }

    fun ensureChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(
            NotificationChannel(CHANNEL, c.getString(R.string.clock), NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
    }
}

/** Alarm fired: post a full-screen notification that opens the ringing screen. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getIntExtra(Alarms.EXTRA_ID, 0)
        val timer = i.getBooleanExtra(Alarms.EXTRA_TIMER, false)
        if (!timer) {
            val list = Alarms.load(c)
            list.firstOrNull { it.id == id }?.let { a -> if (a.days == 0) a.on = false }
            Alarms.save(c, list)   // reschedules repeating alarms for their next day
        }
        Alarms.ensureChannel(c)
        val ring = Intent(c, AlarmRingActivity::class.java).putExtra(Alarms.EXTRA_ID, id).putExtra(Alarms.EXTRA_TIMER, timer)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        val pi = PendingIntent.getActivity(c, id + 1000, ring, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = if (timer) c.getString(R.string.timer_done) else Alarms.load(c).firstOrNull { it.id == id }?.name?.ifEmpty { null } ?: c.getString(R.string.alarm)
        val n = android.app.Notification.Builder(c, Alarms.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setCategory(android.app.Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        try { c.getSystemService(NotificationManager::class.java).notify(NOTE_ID, n) } catch (_: SecurityException) {}
        // If the screen is on and we may start activities, show the ringing screen directly too.
        try { c.startActivity(ring) } catch (_: Exception) {}
    }

    companion object { const val NOTE_ID = 7001 }
}

/** Alarms survive reboots and app updates. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = Alarms.rescheduleAll(c)
}

/**
 * W10M LoopingSelector (the time-picker column): items 54 epx tall, the centred one inside an accent
 * box with white text, others in the foreground colour at 60 %; drag or fling to spin, snaps to an item.
 */
@SuppressLint("ViewConstructor")
class LoopingSelector(context: Context, private val p: Palette, private val items: List<String>, private val loop: Boolean = true) : View(context) {
    var onChange: ((Int) -> Unit)? = null
    private val itemH = context.dp(54f)
    private var offset = 0f   // scroll position in px; item index = offset / itemH
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.sp(28f); typeface = Metro.semilight(context); textAlign = Paint.Align.CENTER }
    private val fill = Paint()
    private val scroller = Scroller(context, Motion.DECELERATE)
    private var vt: VelocityTracker? = null
    private var lastY = 0f

    var value: Int
        get() = Math.floorMod(Math.round(offset / itemH), items.size)
        set(v) { offset = v * itemH; invalidate() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (itemH * 5).toInt())

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        fill.color = p.accent
        c.drawRect(context.dp(2f), cy - itemH / 2 + context.dp(2f), width - context.dp(2f), cy + itemH / 2 - context.dp(2f), fill)
        val first = Math.floor(((offset - cy) / itemH).toDouble()).toInt() - 1
        val last = first + (height / itemH).toInt() + 3
        for (i in first..last) {
            if (!loop && (i < 0 || i >= items.size)) continue
            val y = cy + i * itemH - offset
            val centre = kotlin.math.abs(y - cy) < itemH / 2
            tp.color = if (centre) android.graphics.Color.WHITE else p.fgMedium
            c.drawText(items[Math.floorMod(i, items.size)], width / 2f, y - (tp.ascent() + tp.descent()) / 2, tp)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        vt = vt ?: VelocityTracker.obtain(); vt!!.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { scroller.forceFinished(true); lastY = e.y; parent.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> { offset += lastY - e.y; lastY = e.y; clamp(); invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt!!.computeCurrentVelocity(1000); val v = vt!!.yVelocity; vt!!.recycle(); vt = null
                if (kotlin.math.abs(v) > 300) {
                    scroller.fling(0, offset.toInt(), 0, -v.toInt(), 0, 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2)
                    // Land exactly on an item.
                    val target = Math.round(scroller.finalY / itemH) * itemH
                    scroller.finalY = target.toInt().let { if (!loop) it.coerceIn(0, ((items.size - 1) * itemH).toInt()) else it }
                } else {
                    // A tap above or below the centre moves one step that way; a drag snaps to the nearest item.
                    val tap = e.actionMasked == MotionEvent.ACTION_UP && kotlin.math.abs(e.y - height / 2f) > itemH / 2 && scroller.isFinished
                    var target = Math.round(offset / itemH) * itemH
                    if (tap && kotlin.math.abs(target - offset) < 4) target += Math.round((e.y - height / 2f) / itemH) * itemH
                    if (!loop) target = target.coerceIn(0f, (items.size - 1) * itemH)
                    scroller.startScroll(0, offset.toInt(), 0, (target - offset).toInt(), Metro.DUR_NORMAL.toInt())
                }
                postInvalidateOnAnimation()
            }
        }
        return true
    }

    private fun clamp() { if (!loop) offset = offset.coerceIn(-itemH / 2, (items.size - 0.5f) * itemH) }

    private var lastValue = -1
    override fun computeScroll() {
        if (scroller.computeScrollOffset()) { offset = scroller.currY.toFloat(); invalidate(); postInvalidateOnAnimation() }
        else if (value != lastValue) { lastValue = value; onChange?.invoke(value) }
    }
}
