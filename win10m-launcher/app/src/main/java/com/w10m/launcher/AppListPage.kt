package com.w10m.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.icu.text.AlphabeticIndex
import android.text.Editable
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

/** "All apps" page: search box, "Recently added", letter groups and the letter jump grid. */
@SuppressLint("ViewConstructor")
class AppListPage(context: Context, private var palette: Palette, private val monoIcons: () -> Boolean) : FrameLayout(context) {

    interface Host {
        fun onAppClick(a: AppEntry, from: View)
        fun onAppLongClick(a: AppEntry, from: View)
    }

    var host: Host? = null
    private var apps: List<AppEntry> = emptyList()
    private val search = MetroSearchBox(context, palette)
    private val list = RecyclerView(context)
    private val adapter = Adapter()
    private val jump = JumpGrid(context)
    private var topInset = 0

    private sealed class Item {
        class Header(val label: String) : Item()
        class App(val app: AppEntry) : Item()
    }

    private var items: List<Item> = emptyList()
    private val index = AlphabeticIndex<AppEntry>(Locale.getDefault()).addLabels(Locale.ENGLISH)
        .setMaxLabelCount(300)
    private val recentLabel = context.getString(R.string.recently_added)

    init {
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        list.itemAnimator = null
        list.clipToPadding = false
        addView(list, LayoutParams(-1, -1))
        addView(search, LayoutParams(-1, context.dpi(32f)).apply {
            val m = context.dpi(Metro.PAGE_MARGIN); leftMargin = m; rightMargin = m
        })
        addView(jump, LayoutParams(-1, -1))
        jump.visibility = GONE
        search.edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = rebuild()
        })
        applyPalette(palette)
    }

    fun applyPalette(p: Palette) {
        palette = p; search.palette = p; search.restyle()
        jump.invalidate(); adapter.notifyDataSetChanged()
    }

    fun setInsets(top: Int, bottom: Int) {
        topInset = top
        (search.layoutParams as LayoutParams).topMargin = top + context.dpi(16f)
        search.requestLayout()
        list.setPadding(0, top + context.dpi(16f + 32f + 12f), 0, bottom + context.dpi(24f))
        jump.setPadding(0, top, 0, bottom)
    }

    fun setApps(a: List<AppEntry>) { apps = a; rebuild() }

    fun resetState() {
        if (search.edit.text.isNotEmpty()) search.edit.setText("")
        hideKeyboard()
        jump.visibility = GONE
        list.scrollToPosition(0)
    }

    /** Back: close jump grid or clear search first. */
    fun handleBack(): Boolean {
        if (jump.visibility == VISIBLE) { jump.hide(); return true }
        if (search.edit.text.isNotEmpty()) { search.edit.setText(""); hideKeyboard(); return true }
        return false
    }

    fun hideKeyboard() {
        search.edit.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
    }

    private fun bucketOf(label: String): String {
        val imm = index.buildImmutableIndex()
        val b = imm.getBucket(imm.getBucketIndex(label))
        return if (b.labelType == AlphabeticIndex.Bucket.LabelType.NORMAL) b.label else "#"
    }

    private fun rebuild() {
        val q = search.edit.text.toString().trim()
        val out = ArrayList<Item>()
        if (q.isNotEmpty()) {
            apps.filter { it.label.contains(q, ignoreCase = true) }.forEach { out += Item.App(it) }
        } else {
            val now = System.currentTimeMillis()
            val recent = apps.filter { now - it.installTime < 3L * 24 * 3600 * 1000 }.sortedByDescending { it.installTime }.take(3)
            if (recent.isNotEmpty()) {
                out += Item.Header(recentLabel); recent.forEach { out += Item.App(it) }
            }
            // Group by AlphabeticIndex bucket — this also maps Chinese names to pinyin initials.
            val groups = LinkedHashMap<String, MutableList<AppEntry>>()
            val sorted = apps.map { it to bucketOf(it.label) }
            val order = jumpLabels()
            for (l in order) groups[l] = mutableListOf()
            for ((a, b) in sorted) groups.getOrPut(b) { mutableListOf() } += a
            for ((l, g) in groups) if (g.isNotEmpty()) { out += Item.Header(l); g.forEach { out += Item.App(it) } }
        }
        items = out
        adapter.notifyDataSetChanged()
    }

    private fun jumpLabels(): List<String> {
        val imm = index.buildImmutableIndex()
        val l = ArrayList<String>(); l += "#"
        for (b in imm) if (b.labelType == AlphabeticIndex.Bucket.LabelType.NORMAL && b.label !in l) l += b.label
        return l
    }

    private fun scrollToHeader(label: String) {
        val i = items.indexOfFirst { it is Item.Header && it.label == label }
        if (i >= 0) (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, list.paddingTop)
    }

    // ---------------------------------------------------------------- adapter

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = if (items[position] is Item.Header) 0 else 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = if (viewType == 0) HeaderRow(context) else AppRow(context)
            v.layoutParams = RecyclerView.LayoutParams(-1, context.dpi(if (viewType == 0) Metro.LIST_HEADER_H else Metro.LIST_ROW_H))
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            when (val it = items[position]) {
                is Item.Header -> (h.itemView as HeaderRow).apply {
                    label = it.label
                    setOnClickListener { _ -> if (it.label != recentLabel) { hideKeyboard(); jump.show(jumpLabels(), items) } }
                    invalidate()
                }
                is Item.App -> (h.itemView as AppRow).apply {
                    app = it.app
                    setOnClickListener { v -> host?.onAppClick(it.app, v) }
                    setOnLongClickListener { v -> host?.onAppLongClick(it.app, v); true }
                    invalidate()
                }
            }
        }
    }

    private inner class HeaderRow(c: Context) : View(c) {
        var label = ""
        private val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.sp(20f); typeface = Metro.regular(c) }
        private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.sp(15f); typeface = Metro.regular(c) }
        init { isClickable = true; background = pressedBg(c) }
        override fun onDraw(cv: Canvas) {
            val x = context.dp(Metro.PAGE_MARGIN)
            val tp = if (label == recentLabel) small else p
            tp.color = palette.fg
            cv.drawText(label, x, height / 2f - (tp.ascent() + tp.descent()) / 2, tp)
        }
    }

    private inner class AppRow(c: Context) : View(c) {
        var app: AppEntry? = null
        private val tile = Paint()
        private val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.sp(15f); typeface = Metro.regular(c) }
        private val white = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        init { isClickable = true; isLongClickable = true; background = pressedBg(c) }
        override fun onDraw(cv: Canvas) {
            val a = app ?: return
            val m = context.dp(Metro.PAGE_MARGIN); val s = context.dp(Metro.LIST_ICON)
            val top = (height - s) / 2
            tile.color = palette.accent
            cv.drawRect(m, top, m + s, top + s, tile)
            val mono = if (monoIcons()) a.mono else null
            val isz = if (mono != null) s * 0.9f else s * 0.75f
            val ix = m + (s - isz) / 2; val iy = top + (s - isz) / 2
            val d = mono ?: a.icon
            d.setBounds(ix.toInt(), iy.toInt(), (ix + isz).toInt(), (iy + isz).toInt())
            d.colorFilter = if (mono != null) white else null
            d.draw(cv)
            p.color = palette.fg
            val tx = m + s + context.dp(12f)
            val t = TextUtils.ellipsize(a.label, p, width - tx - m, TextUtils.TruncateAt.END)
            cv.drawText(t, 0, t.length, tx, height / 2f - (p.ascent() + p.descent()) / 2, p)
        }
    }

    private fun pressedBg(c: Context): android.graphics.drawable.Drawable {
        val tv = TypedValue()
        c.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return c.getDrawable(tv.resourceId)!!
    }

    // ---------------------------------------------------------------- jump grid

    private inner class JumpGrid(c: Context) : View(c) {
        private var labels: List<String> = emptyList()
        private var active: Set<String> = emptySet()
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.sp(34f); typeface = Metro.light(c); textAlign = Paint.Align.LEFT }
        private val fill = Paint()
        private val cols = 4
        private val gap = c.dp(6f)
        private var scroll = 0f
        private var lastY = 0f
        private var moved = false

        fun show(l: List<String>, its: List<Item>) {
            labels = l; active = its.filterIsInstance<Item.Header>().map { it.label }.toSet()
            scroll = 0f; visibility = VISIBLE
            alpha = 0f; scaleX = 1.1f; scaleY = 1.1f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Metro.DUR_FAST).setListener(null)
        }

        fun hide() {
            animate().alpha(0f).scaleX(1.1f).scaleY(1.1f).setDuration(Metro.DUR_FAST).withEndAction { visibility = GONE }
        }

        private fun cellSize() = (width - 2 * context.dp(Metro.PAGE_MARGIN) - (cols - 1) * gap) / cols

        override fun onDraw(cv: Canvas) {
            cv.drawColor(if (palette.dark) 0xE6000000.toInt() else 0xE6FFFFFF.toInt())
            val s = cellSize(); val m = context.dp(Metro.PAGE_MARGIN)
            val top0 = paddingTop + context.dp(24f) - scroll
            labels.forEachIndexed { i, l ->
                val x = m + (i % cols) * (s + gap); val y = top0 + (i / cols) * (s + gap)
                val on = l in active
                fill.color = if (on) palette.accent else palette.chromeMedium
                cv.drawRect(x, y, x + s, y + s, fill)
                tp.color = if (on) Color.WHITE else palette.fgLow
                // Letter sits bottom-left inside the square, like a tiny tile.
                cv.drawText(l, x + context.dp(8f), y + s - context.dp(6f) - tp.descent(), tp)
            }
        }

        private fun maxScroll(): Float {
            val s = cellSize(); val rows = (labels.size + cols - 1) / cols
            return (paddingTop + context.dp(24f) + rows * (s + gap) + paddingBottom + context.dp(24f) - height).coerceAtLeast(0f)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastY = e.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = lastY - e.y
                    if (moved || kotlin.math.abs(dy) > context.dp(8f)) {
                        moved = true; scroll = (scroll + dy).coerceIn(0f, maxScroll()); lastY = e.y; invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    val s = cellSize(); val m = context.dp(Metro.PAGE_MARGIN)
                    val col = ((e.x - m) / (s + gap)).toInt()
                    val row = ((e.y - (paddingTop + context.dp(24f) - scroll)) / (s + gap)).toInt()
                    val i = row * cols + col
                    val l = labels.getOrNull(i)
                    if (col in 0 until cols && l != null && l in active) { scrollToHeader(l); hide() }
                    else if (l == null) hide()
                }
            }
            return true
        }
    }
}

/** W10M TextBox: 32 epx tall, 2 epx border, placeholder, magnifier glyph on the right. */
@SuppressLint("ViewConstructor")
class MetroSearchBox(context: Context, var palette: Palette) : FrameLayout(context) {
    val edit = EditText(context)
    private val glyph = object : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.dp(1.3f) }
        private val path = Path()
        override fun onDraw(c: Canvas) {
            p.color = palette.fgMedium
            val u = width / 32f
            // MDL2 "Search": circle with handle to the bottom-left.
            c.drawCircle(18 * u, 14 * u, 6 * u, p)
            path.reset(); path.moveTo(13.8f * u, 18.2f * u); path.lineTo(8 * u, 24 * u)
            c.drawPath(path, p)
        }
    }

    init {
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        edit.typeface = Metro.regular(context)
        edit.isSingleLine = true
        edit.setHint(R.string.search)
        edit.gravity = Gravity.CENTER_VERTICAL
        edit.setPadding(context.dpi(10f), 0, context.dpi(32f), 0)
        edit.background = null
        edit.includeFontPadding = false
        addView(edit, LayoutParams(-1, -1))
        addView(glyph, LayoutParams(context.dpi(32f), -1, Gravity.END))
        edit.setOnFocusChangeListener { _, _ -> restyle() }
        restyle()
    }

    fun restyle() {
        val focused = edit.hasFocus()
        background = GradientDrawable().apply {
            // UWP TextBox: translucent fill at rest, opaque white when focused.
            setColor(if (focused) Color.WHITE else if (palette.dark) 0x66000000 else 0x66FFFFFF)
            setStroke(context.dpi(2f), if (focused) palette.accent else palette.inputBorder)
        }
        edit.setTextColor(if (focused) Color.BLACK else palette.fg)
        edit.setHintTextColor(if (focused) 0x99000000.toInt() else palette.fgMedium)
        glyph.invalidate()
    }
}
