package com.w10m.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.text.InputType
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Messaging in the W10M layout: conversation list (avatar, name, preview, time) with a bottom command bar,
 * and a thread view with notched bubbles (outgoing in the accent colour on the right) and a compose box.
 * Reads and sends plain SMS through the system provider; it does not need to be the default SMS app.
 */
class MessagingActivity : Activity() {

    private class Msg(val thread: Long, val address: String, val body: String, val date: Long, val out: Boolean, val read: Boolean)

    private lateinit var prefs: Prefs
    private lateinit var p: Palette
    private lateinit var shell: Shell
    private lateinit var listPage: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var threadPage: LinearLayout
    private lateinit var threadTitle: TextView
    private lateinit var recipient: EditText
    private lateinit var bubbles: LinearLayout
    private lateinit var bubbleScroll: ScrollView
    private lateinit var compose: EditText
    private var threads: Map<Long, List<Msg>> = emptyMap()
    private var openThread: Long? = null
    private var openAddress: String? = null
    private val names = HashMap<String, String>()
    private val ui = Handler(Looper.getMainLooper())
    private val observer = object : ContentObserver(ui) { override fun onChange(selfChange: Boolean) { load() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this); p = prefs.palette()
        shell = Shell(this, prefs, p)
        val root = FrameLayout(this).apply { setBackgroundColor(p.bg) }
        buildList(); buildThread()
        root.addView(listPage, FrameLayout.LayoutParams(-1, -1))
        root.addView(threadPage, FrameLayout.LayoutParams(-1, -1))
        threadPage.visibility = View.GONE
        shell.setContent(root)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); handleIntent(intent) }

    private fun handleIntent(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme != "sms" && d.scheme != "smsto") return
        val addr = d.schemeSpecificPart.substringBefore('?')
        showThread(threads.entries.firstOrNull { same(it.value.first().address, addr) }?.key, addr)
        i.getStringExtra("sms_body")?.let { compose.setText(it) }
    }

    override fun onStart() {
        super.onStart()
        if (has(Manifest.permission.READ_SMS)) try { contentResolver.registerContentObserver(Uri.parse("content://sms"), true, observer) } catch (_: Exception) {}
        load()
    }

    override fun onStop() { super.onStop(); try { contentResolver.unregisterContentObserver(observer) } catch (_: Exception) {} }

    private fun has(perm: String) = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun same(a: String, b: String): Boolean {
        val x = a.filter { it.isDigit() }; val y = b.filter { it.isDigit() }
        return if (x.length >= 7 && y.length >= 7) x.takeLast(7) == y.takeLast(7) else a == b
    }

    // ---------------------------------------------------------------- data

    private fun load() {
        if (!has(Manifest.permission.READ_SMS)) { fillList(); return }
        val all = ArrayList<Msg>()
        try {
            contentResolver.query(Uri.parse("content://sms"), arrayOf("thread_id", "address", "body", "date", "type", "read"),
                null, null, "date DESC")?.use { c ->
                while (c.moveToNext() && all.size < 3000) {
                    all += Msg(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3), c.getInt(4) != 1, c.getInt(5) == 1)
                }
            }
        } catch (_: Exception) {}
        threads = all.groupBy { it.thread }   // insertion order = newest thread first
        fillList()
        if (threadPage.visibility == View.VISIBLE) {
            if (openThread == null) openThread = threads.entries.firstOrNull { same(it.value.first().address, openAddress.orEmpty()) }?.key
            fillThread()
        }
    }

    private fun nameOf(address: String): String {
        names[address]?.let { return it }
        var n = address
        if (has(Manifest.permission.READ_CONTACTS) && address.isNotEmpty()) try {
            contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) n = c.getString(0) ?: address }
        } catch (_: Exception) {}
        names[address] = n
        return n
    }

    // ---------------------------------------------------------------- conversation list

    private fun pageTitle(text: String) = TextView(this).apply {
        this.text = text; textSize = 15f; typeface = Metro.semibold(context); setTextColor(p.fg); isAllCaps = true
        setPadding(dpi(Metro.PAGE_MARGIN), dpi(10f), dpi(Metro.PAGE_MARGIN), dpi(10f))
    }

    private fun commandBar(vararg buttons: View) = LinearLayout(this).apply {
        setBackgroundColor(p.chromeMedium); gravity = Gravity.CENTER_HORIZONTAL
        buttons.forEach { addView(it, LinearLayout.LayoutParams(dpi(72f), -1)) }
    }

    private fun buildList() {
        listPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listPage.addView(pageTitle(getString(R.string.messaging)))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listPage.addView(ScrollView(this).apply { addView(listBox); overScrollMode = View.OVER_SCROLL_NEVER }, LinearLayout.LayoutParams(-1, 0, 1f))
        // Command bar: "+" new message.
        listPage.addView(commandBar(GlyphButton(this, p.fg) { c, pt, cx, cy, u ->
            c.drawLine(cx - 8 * u, cy, cx + 8 * u, cy, pt); c.drawLine(cx, cy - 8 * u, cx, cy + 8 * u, pt)
        }.apply { setOnClickListener { showThread(null, null) } }), LinearLayout.LayoutParams(-1, dpi(48f)))
    }

    private fun fillList() {
        listBox.removeAllViews()
        val m = dpi(Metro.PAGE_MARGIN)
        if (!has(Manifest.permission.READ_SMS)) {
            listBox.setPadding(m, dpi(12f), m, 0)
            listBox.addView(TextView(this).apply {
                text = getString(R.string.sms_permission); textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fgMedium)
            })
            listBox.addView(metroButton(this, p, getString(R.string.grant)) {
                requestPermissions(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS), 1)
            }, LinearLayout.LayoutParams(-2, dpi(32f)).apply { topMargin = dpi(12f) })
            return
        }
        listBox.setPadding(0, 0, 0, 0)
        if (threads.isEmpty()) listBox.addView(TextView(this).apply {
            text = getString(R.string.no_messages); textSize = 15f; typeface = Metro.regular(context); setTextColor(p.fgMedium)
            setPadding(m, dpi(12f), m, 0)
        })
        for ((id, msgs) in threads) {
            val last = msgs.first()
            listBox.addView(ThreadRow(nameOf(last.address), last.body, last.date, msgs.any { !it.read && !it.out }).apply {
                setOnClickListener { showThread(id, last.address) }
            }, LinearLayout.LayoutParams(-1, dpi(68f)))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (has(Manifest.permission.READ_SMS)) try { contentResolver.registerContentObserver(Uri.parse("content://sms"), true, observer) } catch (_: Exception) {}
        names.clear(); load()
    }

    /** Row: 40 epx accent circle with a person glyph, name 20 SemiLight, one-line preview (accent when unread), time on the right. */
    private inner class ThreadRow(val name: String, val preview: String, val date: Long, val unread: Boolean) : View(this) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val gp = Paint(Paint.ANTI_ALIAS_FLAG)
        private val np = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(20f); typeface = Metro.semilight(context) }
        private val pp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(15f); typeface = Metro.regular(context) }
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(12f); typeface = Metro.regular(context); textAlign = Paint.Align.RIGHT }
        init { isClickable = true }
        override fun onDraw(c: Canvas) {
            val m = dp(Metro.PAGE_MARGIN); val r = dp(20f); val cy = height / 2f
            fill.color = p.accent
            c.drawCircle(m + r, cy, r, fill)
            IconPack.glyph("people")?.draw(c, m + r - dp(11f), cy - dp(11f), dp(22f), gp)
            val tx = m + 2 * r + dp(12f)
            tp.color = p.fgMedium
            val time = DateUtils.getRelativeTimeSpanString(date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_ALL).toString()
            c.drawText(time, width - m, dp(12f) - tp.ascent(), tp)
            np.color = p.fg
            val n = TextUtils.ellipsize(name, np, width - tx - m - tp.measureText(time) - dp(8f), TextUtils.TruncateAt.END)
            c.drawText(n, 0, n.length, tx, dp(8f) - np.ascent(), np)
            pp.color = if (unread) p.accent else p.fgMedium
            val line = TextUtils.ellipsize(preview.replace('\n', ' '), pp, width - tx - m, TextUtils.TruncateAt.END)
            c.drawText(line, 0, line.length, tx, dp(36f) - pp.ascent(), pp)
        }
    }

    // ---------------------------------------------------------------- thread

    private fun textBox(hint: Int) = EditText(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Metro.regular(context)
        setHint(hint); setTextColor(p.fg); setHintTextColor(p.fgMedium)
        setPadding(dpi(10f), dpi(5f), dpi(10f), dpi(5f)); includeFontPadding = false; minHeight = dpi(32f)
        background = GradientDrawable().apply { setColor(if (p.dark) 0x66000000 else 0x66FFFFFF); setStroke(dpi(2f), p.inputBorder) }
        setOnFocusChangeListener { v, f ->
            (v.background as GradientDrawable).apply { setColor(if (f) Color.WHITE else if (p.dark) 0x66000000 else 0x66FFFFFF); setStroke(dpi(2f), if (f) p.accent else p.inputBorder) }
            setTextColor(if (f) Color.BLACK else p.fg); setHintTextColor(if (f) 0x99000000.toInt() else p.fgMedium)
        }
    }

    private fun buildThread() {
        val m = dpi(Metro.PAGE_MARGIN)
        threadPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg) }
        threadTitle = pageTitle("")
        threadPage.addView(threadTitle)
        recipient = textBox(R.string.sms_to).apply { inputType = InputType.TYPE_CLASS_PHONE; isSingleLine = true }
        threadPage.addView(recipient, LinearLayout.LayoutParams(-1, -2).apply { setMargins(m, 0, m, dpi(8f)) })
        bubbles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(m, dpi(4f), m, dpi(8f)) }
        bubbleScroll = ScrollView(this).apply { addView(bubbles); overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true }
        threadPage.addView(bubbleScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        bubbleScroll.addOnLayoutChangeListener { _, _, _, _, b, _, _, _, ob -> if (b != ob) bubbleScroll.post { bubbleScroll.fullScroll(View.FOCUS_DOWN) } }
        // Compose row: text box + send key.
        val row = LinearLayout(this).apply { setBackgroundColor(p.chromeMedium); gravity = Gravity.BOTTOM; setPadding(m, dpi(8f), 0, dpi(8f)) }
        compose = textBox(R.string.sms_hint).apply { maxLines = 4; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        row.addView(compose, LinearLayout.LayoutParams(0, -2, 1f))
        val gp = Paint(Paint.ANTI_ALIAS_FLAG)
        row.addView(GlyphButton(this, p.fg) { c, _, cx, cy, u -> IconPack.glyph("send")?.draw(c, cx - 10 * u, cy - 10 * u, 20 * u, gp, p.fg) }
            .apply { setOnClickListener { send() } }, LinearLayout.LayoutParams(dpi(48f), dpi(32f)))
        threadPage.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun showThread(id: Long?, address: String?) {
        openThread = id; openAddress = address
        recipient.visibility = if (address == null) View.VISIBLE else View.GONE
        recipient.setText("")
        threadTitle.text = if (address == null) getString(R.string.new_message) else nameOf(address)
        compose.setText("")
        listPage.visibility = View.GONE; threadPage.visibility = View.VISIBLE
        fillThread()
        threadPage.post { Motion.drillIn(threadPage) }
        // Mark-as-read needs the default-SMS role, so unread state follows the system app.
    }

    private fun fillThread() {
        bubbles.removeAllViews()
        val msgs = threads[openThread]?.take(300)?.reversed() ?: emptyList()
        // Push short threads to the bottom, next to the compose box.
        bubbles.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        for (msg in msgs) addBubble(msg.body, msg.date, msg.out)
        bubbleScroll.post { bubbleScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addBubble(body: String, date: Long, out: Boolean) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = BubbleDrawable(if (out) p.accent else if (p.dark) 0xFF333333.toInt() else 0xFFDDDDDD.toInt(), out, dp(8f))
            setPadding(dpi(10f), dpi(if (out) 8f else 16f), dpi(10f), dpi(if (out) 16f else 8f))
        }
        val fg = if (out || p.dark) Color.WHITE else Color.BLACK
        box.addView(TextView(this).apply { text = body; textSize = 15f; typeface = Metro.regular(context); setTextColor(fg); setTextIsSelectable(true) })
        box.addView(TextView(this).apply {
            text = DateUtils.formatDateTime(context, date, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL)
            textSize = 12f; typeface = Metro.regular(context); setTextColor((fg and 0x00FFFFFF) or 0x99000000.toInt())
        })
        bubbles.addView(box, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = if (out) Gravity.END else Gravity.START
            topMargin = dpi(6f)
            if (out) marginStart = dpi(56f) else marginEnd = dpi(56f)
        })
    }

    /** Flat rectangle with a small triangular notch: top-left for incoming, bottom-right for outgoing. */
    private class BubbleDrawable(color: Int, private val out: Boolean, private val notch: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val path = Path()
        override fun draw(c: Canvas) {
            val b = bounds; val l = b.left.toFloat(); val t = b.top.toFloat(); val r = b.right.toFloat(); val bt = b.bottom.toFloat()
            path.reset()
            if (out) {
                path.addRect(l, t, r, bt - notch, Path.Direction.CW)
                path.moveTo(r - 2.5f * notch, bt - notch); path.lineTo(r - notch, bt - notch); path.lineTo(r - notch, bt); path.close()
            } else {
                path.addRect(l, t + notch, r, bt, Path.Direction.CW)
                path.moveTo(l + notch, t + notch); path.lineTo(l + notch, t); path.lineTo(l + 2.5f * notch, t + notch); path.close()
            }
            c.drawPath(path, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    // ---------------------------------------------------------------- send

    private fun send() {
        val body = compose.text.toString()
        val to = openAddress ?: recipient.text.toString().trim()
        if (body.isBlank() || to.isEmpty()) return
        if (!has(Manifest.permission.SEND_SMS)) { requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 2); return }
        try {
            @Suppress("DEPRECATION")
            val sm = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            sm.sendMultipartTextMessage(to, null, sm.divideMessage(body), null, null)
        } catch (_: Exception) { return }
        compose.setText("")
        if (openAddress == null) { openAddress = to; recipient.visibility = View.GONE; threadTitle.text = nameOf(to) }
        addBubble(body, System.currentTimeMillis(), true)
        // The system writes the sent message to the provider; pick it up from there.
        ui.postDelayed({ load() }, 1500)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            shell.handleBack() -> Unit
            threadPage.visibility == View.VISIBLE -> {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(compose.windowToken, 0)
                Motion.drillOut(threadPage) { threadPage.visibility = View.GONE }
                listPage.visibility = View.VISIBLE; Motion.drillBackIn(listPage)
            }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }
}
