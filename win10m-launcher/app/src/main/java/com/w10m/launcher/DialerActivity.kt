package com.w10m.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowCompat

/** Phone app in the W10M layout: icon pivot (history / dial pad), number display, flat keypad, accent Call key. */
class DialerActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var p: Palette
    private lateinit var tabs: TabHeader
    private lateinit var history: LinearLayout
    private lateinit var historyScroll: ScrollView
    private lateinit var dialPage: LinearLayout
    private lateinit var numberView: NumberDisplay
    private var number = ""
    private var tone: ToneGenerator? = null
    private var pendingCall: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = Prefs(this); p = prefs.palette()
        window.statusBarColor = 0; window.navigationBarColor = 0

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg) }
        tabs = TabHeader(this)
        root.addView(tabs, LinearLayout.LayoutParams(-1, dpi(48f)))

        val content = FrameLayout(this)
        history = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        historyScroll = ScrollView(this).apply { addView(history); overScrollMode = View.OVER_SCROLL_NEVER }
        content.addView(historyScroll, FrameLayout.LayoutParams(-1, -1))

        dialPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        numberView = NumberDisplay(this)
        dialPage.addView(numberView, LinearLayout.LayoutParams(-1, 0, 1f))
        dialPage.addView(DialPad(this), LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(dialPage, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        val nav = if (prefs.navKeys) NavBar(this, p.accent).also { NavBar.wire(it, this); root.addView(it, LinearLayout.LayoutParams(-1, -2)) } else null
        setContentView(root)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = !p.dark; isAppearanceLightNavigationBars = !p.dark && nav == null
        }
        root.setOnApplyWindowInsetsListener { _, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars())
            root.setPadding(0, b.top, 0, if (nav == null) b.bottom else 0)
            nav?.bottomInset = b.bottom
            ins
        }

        intent?.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart?.let { number = it }
        numberView.invalidate()
        showTab(if (number.isEmpty() && has(Manifest.permission.READ_CALL_LOG)) 0 else 1)
        try { tone = ToneGenerator(AudioManager.STREAM_DTMF, 60) } catch (_: Exception) {}
    }

    override fun onDestroy() { super.onDestroy(); tone?.release() }

    override fun onResume() { super.onResume(); loadHistory() }

    private fun has(perm: String) = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun showTab(i: Int) {
        tabs.selected = i; tabs.invalidate()
        historyScroll.visibility = if (i == 0) View.VISIBLE else View.GONE
        dialPage.visibility = if (i == 1) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- calling

    private fun call(num: String) {
        if (num.isEmpty()) return
        if (has(Manifest.permission.CALL_PHONE)) {
            try { startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", num, null))); return } catch (_: Exception) {}
        } else { pendingCall = num; requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQ_CALL); return }
        handOff(num)
    }

    /** Without the call permission, hand the number to the system phone app. */
    private fun handOff(num: String) {
        val i = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", num, null))
        (getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage
            ?.takeIf { it != packageName }?.let { i.setPackage(it) }
        try { startActivity(i) } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_CALL -> pendingCall?.let { if (ok) call(it) else handOff(it) }.also { pendingCall = null }
            REQ_LOG -> loadHistory()
        }
    }

    // ---------------------------------------------------------------- history

    private fun loadHistory() {
        history.removeAllViews()
        val m = dpi(Metro.PAGE_MARGIN)
        if (!has(Manifest.permission.READ_CALL_LOG)) {
            history.setPadding(m, dpi(24f), m, 0)
            history.addView(TextView(this).apply {
                text = getString(R.string.history_permission); textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fgMedium)
            })
            history.addView(metroButton(this, p, getString(R.string.grant)) {
                requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG), REQ_LOG)
            }, LinearLayout.LayoutParams(-2, dpi(32f)).apply { topMargin = dpi(12f) })
            return
        }
        history.setPadding(0, dpi(8f), 0, dpi(8f))
        try {
            contentResolver.query(CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null, null, CallLog.Calls.DATE + " DESC")?.use { c ->
                var n = 0
                while (c.moveToNext() && n++ < 100) {
                    val num = c.getString(0).orEmpty(); val name = c.getString(1)
                    val type = c.getInt(2); val date = c.getLong(3)
                    history.addView(HistoryRow(this, name ?: num.ifEmpty { getString(R.string.unknown_number) }, type, date).apply {
                        setOnClickListener { call(num) }
                    }, LinearLayout.LayoutParams(-1, dpi(60f)))
                }
                if (n == 0) history.addView(TextView(this).apply {
                    text = getString(R.string.no_history); textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fgMedium)
                    setPadding(m, dpi(16f), m, 0)
                })
            }
        } catch (_: Exception) {}
    }

    private inner class HistoryRow(c: Context, val name: String, val type: Int, val date: Long) : View(c) {
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.sp(20f); typeface = Metro.semilight(c) }
        private val sp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.sp(12f); typeface = Metro.regular(c) }
        init { isClickable = true }
        override fun onDraw(cv: Canvas) {
            val m = dp(Metro.PAGE_MARGIN)
            val missed = type == CallLog.Calls.MISSED_TYPE || type == CallLog.Calls.REJECTED_TYPE
            tp.color = if (missed) p.accent else p.fg
            val t = TextUtils.ellipsize(name, tp, width - 2 * m, TextUtils.TruncateAt.END)
            cv.drawText(t, 0, t.length, m, dp(8f) - tp.ascent(), tp)
            val kind = getString(when (type) {
                CallLog.Calls.OUTGOING_TYPE -> R.string.call_outgoing
                CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> R.string.call_missed
                else -> R.string.call_incoming
            })
            val whenText = DateUtils.getRelativeTimeSpanString(date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            sp.color = p.fgMedium
            cv.drawText("$kind · $whenText", m, dp(36f) - sp.ascent(), sp)
        }
    }

    // ---------------------------------------------------------------- pivot header

    /** Icon pivot: selected tab is full white with a 2 epx accent underline, others are 60 %. */
    private inner class TabHeader(c: Context) : View(c) {
        var selected = 1
        private val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }
        private val fp = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        init { setBackgroundColor(p.chromeMedium) }
        override fun onDraw(cv: Canvas) {
            val half = width / 2f; val u = dp(1f); val cy = height / 2f
            for (i in 0..1) {
                val cx = half * i + half / 2
                val col = if (i == selected) p.fg else p.fgMedium
                lp.color = col; fp.color = col; lp.strokeWidth = dp(1.3f)
                if (i == 0) { // history: clock
                    cv.drawCircle(cx, cy, 9 * u, lp)
                    path.reset(); path.moveTo(cx, cy - 5 * u); path.lineTo(cx, cy); path.lineTo(cx + 4 * u, cy + 2.5f * u)
                    cv.drawPath(path, lp)
                } else { // dial pad: 3x3 + 1 dots
                    for (r in 0..3) for (k in 0..2) {
                        if (r == 3 && k != 1) continue
                        cv.drawRect(cx + (k - 1) * 6.5f * u - 1.5f * u, cy + (r - 1.5f) * 6.5f * u - 1.5f * u,
                            cx + (k - 1) * 6.5f * u + 1.5f * u, cy + (r - 1.5f) * 6.5f * u + 1.5f * u, fp)
                    }
                }
                if (i == selected) { fp.color = p.accent; cv.drawRect(half * i, height - 2 * u, half * (i + 1), height.toFloat(), fp) }
            }
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) showTab(if (e.x < width / 2f) 0 else 1)
            return true
        }
    }

    // ---------------------------------------------------------------- number numberView

    private inner class NumberDisplay(c: Context) : View(c) {
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Metro.light(c); textAlign = Paint.Align.CENTER }
        private val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = c.dp(1.3f) }
        private val path = Path()
        private var downOnDelete = false
        private val clear = Runnable { number = ""; invalidate() }

        override fun onDraw(cv: Canvas) {
            if (number.isEmpty()) return
            val m = dp(Metro.PAGE_MARGIN); val delW = dp(48f)
            val room = width - 2 * m - 2 * delW
            var size = sp(40f)
            tp.textSize = size
            // Shrink long numbers to fit, as the W10M dialer does.
            val tw = tp.measureText(number)
            if (tw > room) { size = (size * room / tw).coerceAtLeast(sp(18f)); tp.textSize = size }
            tp.color = p.fg
            val base = height - dp(28f)
            val t = TextUtils.ellipsize(number, tp, room, TextUtils.TruncateAt.START)
            cv.drawText(t, 0, t.length, width / 2f, base, tp)
            // Backspace glyph: pentagon pointing left with an x.
            val u = dp(1f); val cx = width - m - delW / 2; val cy = base - sp(40f) * 0.35f
            lp.color = p.fg
            path.reset()
            path.moveTo(cx - 11 * u, cy); path.lineTo(cx - 4 * u, cy - 7 * u); path.lineTo(cx + 11 * u, cy - 7 * u)
            path.lineTo(cx + 11 * u, cy + 7 * u); path.lineTo(cx - 4 * u, cy + 7 * u); path.close()
            path.moveTo(cx, cy - 3 * u); path.lineTo(cx + 6 * u, cy + 3 * u)
            path.moveTo(cx + 6 * u, cy - 3 * u); path.lineTo(cx, cy + 3 * u)
            cv.drawPath(path, lp)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val onDelete = e.x > width - dp(Metro.PAGE_MARGIN) - dp(56f)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downOnDelete = onDelete; if (onDelete) postDelayed(clear, 600) }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(clear)
                    if (downOnDelete && onDelete && number.isNotEmpty()) { number = number.dropLast(1); invalidate() }
                }
                MotionEvent.ACTION_CANCEL -> removeCallbacks(clear)
            }
            return true
        }
    }

    // ---------------------------------------------------------------- keypad

    /** 3x4 keys + a Call / Save row. Keys are flat ChromeMedium rectangles with 2 epx gaps; pressed = accent. */
    private inner class DialPad(c: Context) : View(c) {
        private val keys = listOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO",
            "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "*" to "", "0" to "+", "#" to "")
        private val tones = intArrayOf(ToneGenerator.TONE_DTMF_1, ToneGenerator.TONE_DTMF_2, ToneGenerator.TONE_DTMF_3,
            ToneGenerator.TONE_DTMF_4, ToneGenerator.TONE_DTMF_5, ToneGenerator.TONE_DTMF_6, ToneGenerator.TONE_DTMF_7,
            ToneGenerator.TONE_DTMF_8, ToneGenerator.TONE_DTMF_9, ToneGenerator.TONE_DTMF_S, ToneGenerator.TONE_DTMF_0, ToneGenerator.TONE_DTMF_P)
        private val keyH = c.dp(58f)
        private val gap = c.dp(2f)
        private val fill = Paint()
        private val digit = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.sp(28f); typeface = Metro.semilight(c); textAlign = Paint.Align.CENTER }
        private val letters = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.sp(10f); typeface = Metro.regular(c); textAlign = Paint.Align.CENTER; letterSpacing = 0.1f }
        private val action = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.sp(15f); typeface = Metro.regular(c) }
        private val gp = Paint(Paint.ANTI_ALIAS_FLAG)
        private var pressedKey = -1
        private var longFired = false
        private val plus = Runnable {
            if (pressedKey == 10) { longFired = true; number += "+"; numberView.invalidate(); performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
        }

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (5 * keyH + 5 * gap).toInt())

        private fun colW() = (width - 2 * gap) / 3f

        override fun onDraw(cv: Canvas) {
            val cw = colW()
            keys.forEachIndexed { i, (d, l) ->
                val x = (i % 3) * (cw + gap); val y = gap + (i / 3) * (keyH + gap)
                fill.color = if (i == pressedKey) p.accent else p.chromeMedium
                cv.drawRect(x, y, x + cw, y + keyH, fill)
                digit.color = if (i == pressedKey) Color.WHITE else p.fg
                val hasSub = l.isNotEmpty()
                val cy = y + keyH / 2 - (if (hasSub) dp(6f) else 0f)
                cv.drawText(d, x + cw / 2, cy - (digit.ascent() + digit.descent()) / 2, digit)
                if (hasSub) {
                    letters.color = if (i == pressedKey) Color.WHITE else p.fgMedium
                    cv.drawText(l, x + cw / 2, y + keyH - dp(9f), letters)
                }
            }
            // Call (accent, two columns) + Save.
            val y = gap + 4 * (keyH + gap)
            val callW = 2 * cw + gap
            fill.color = if (pressedKey == 12) darken(p.accent) else p.accent
            cv.drawRect(0f, y, callW, y + keyH, fill)
            val label = getString(R.string.call)
            action.color = Color.WHITE; action.textAlign = Paint.Align.LEFT
            val gs = dp(20f); val tw = action.measureText(label)
            val start = (callW - gs - dp(8f) - tw) / 2
            IconPack.glyph("phone")?.draw(cv, start, y + (keyH - gs) / 2, gs, gp)
            cv.drawText(label, start + gs + dp(8f), y + keyH / 2 - (action.ascent() + action.descent()) / 2, action)
            fill.color = if (pressedKey == 13) p.accent else p.chromeMedium
            cv.drawRect(callW + gap, y, width.toFloat(), y + keyH, fill)
            action.color = if (pressedKey == 13) Color.WHITE else p.fg; action.textAlign = Paint.Align.CENTER
            cv.drawText(getString(R.string.save), callW + gap + cw / 2, y + keyH / 2 - (action.ascent() + action.descent()) / 2, action)
        }

        private fun darken(c: Int) = Color.rgb((Color.red(c) * 0.8f).toInt(), (Color.green(c) * 0.8f).toInt(), (Color.blue(c) * 0.8f).toInt())

        private fun keyAt(x: Float, y: Float): Int {
            val row = ((y - gap) / (keyH + gap)).toInt().coerceIn(0, 4)
            val col = (x / (colW() + gap)).toInt().coerceIn(0, 2)
            return if (row < 4) row * 3 + col else if (col < 2) 12 else 13
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressedKey = keyAt(e.x, e.y); longFired = false
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (pressedKey < 12) try { tone?.startTone(tones[pressedKey], 120) } catch (_: Exception) {}
                    if (pressedKey == 10) postDelayed(plus, 500)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(plus)
                    val k = pressedKey; pressedKey = -1; invalidate()
                    if (k != keyAt(e.x, e.y) || longFired) return true
                    when (k) {
                        12 -> call(number)
                        13 -> if (number.isNotEmpty()) try {
                            startActivity(Intent(Intent.ACTION_INSERT_OR_EDIT).setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                                .putExtra(ContactsContract.Intents.Insert.PHONE, number))
                        } catch (_: Exception) {}
                        else -> { number += keys[k].first; numberView.invalidate() }
                    }
                }
                MotionEvent.ACTION_CANCEL -> { removeCallbacks(plus); pressedKey = -1; invalidate() }
            }
            return true
        }
    }

    companion object { private const val REQ_CALL = 1; private const val REQ_LOG = 2 }
}
