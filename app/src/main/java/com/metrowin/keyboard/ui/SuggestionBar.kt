package com.metrowin.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import com.metrowin.keyboard.theme.ColorMath
import com.metrowin.keyboard.theme.KeyboardColors

/** What the toolbox offers. The same list feeds the quick button setting. */
enum class Tool(val code: String, val icon: Icon) {
    CLIPBOARD("clipboard", Icon.CLIPBOARD), TRANSLATE("translate", Icon.TRANSLATE), GIF("gif", Icon.GIF), EMOJI("emoji", Icon.EMOJI),
    MIC("mic", Icon.MIC), HELP("help", Icon.HELP), SETTINGS("settings", Icon.SETTINGS);

    companion object { fun from(code: String?) = entries.firstOrNull { it.code == code } ?: CLIPBOARD }
}

/** One of the three places of the bar. [best] is the word that space will insert; [literal] is exactly what was typed. */
data class Slot(val text: String, val best: Boolean = false, val literal: Boolean = false, val phrase: Boolean = false, val emoji: Boolean = false)

/**
 * The strip above the keys, laid out like an app bar: the three dots on the left open the toolbox, the middle holds three
 * suggestions (or the tools, when the toolbox is open), and on the right a quick button goes straight to one tool (the clipboard
 * by default), with a dot when something new was copied. Also shows a short status text (a GIF search, "listening"). With more than
 * three slots (emoji found by ":dog", the Chinese words for the Pinyin typed) the middle is a row that slides sideways.
 *
 * Windows Phone's bar has no buttons: only the words, and for Chinese an arrow at the end that opens all of them. That is the
 * default ([setButtons] false); the toolbox then opens by holding the smiley key and fills the whole bar.
 */
class SuggestionBar(context: Context) : View(context) {
    interface Listener {
        fun onSlot(slot: Slot)
        fun onTool(tool: Tool)
        /** The arrow at the end of a row of Chinese words: show all of them. */
        fun onExpand() {}
    }

    var listener: Listener? = null
    private var colors: KeyboardColors? = null
    private var slots: List<Slot?> = listOf(null, null, null)
    private var row = false
    private var rowEdges: FloatArray? = null
    private var toolsOpen = false
    private var buttons = false
    private var expandable = false
    private var status: String? = null
    private var voiceActive = false
    private var quick = Tool.CLIPBOARD
    private var badge = false
    private var pressedIndex = NONE
    private var scroll = 0f
    private var scrollAtDown = 0f
    private var downX = 0f
    private var dragging = false
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
    // The tool the quick button already offers is not repeated in the toolbox.
    private val tools get() = if (buttons) Tool.entries.filter { it != quick } else Tool.entries

    fun setColors(c: KeyboardColors) { colors = c; invalidate() }
    /** [row]: show the slots as a row that slides sideways, each as wide as its text, instead of three places. */
    fun setSlots(s: List<Slot?>, row: Boolean = s.size > 3, expandable: Boolean = false) {
        if (s != slots) scroll = 0f
        slots = s; this.row = row; this.expandable = expandable; rowEdges = null
        if (!toolsOpen) invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { rowEdges = null }
    internal fun slotsForTest() = slots
    fun setStatus(text: String?) { status = text; invalidate() }
    fun setVoiceActive(on: Boolean) { voiceActive = on; invalidate() }
    fun setQuickTool(t: Tool) { if (quick != t) { quick = t; invalidate() } }
    fun setBadge(on: Boolean) { if (badge != on) { badge = on; invalidate() } }
    /** The three dots and the quick button; without them the bar is the Windows Phone one. */
    fun setButtons(show: Boolean) { if (buttons != show) { buttons = show; rowEdges = null; invalidate() } }
    val toolboxOpen get() = toolsOpen
    fun toggleTools() { toolsOpen = !toolsOpen; invalidate() }
    fun closeTools() { if (toolsOpen) { toolsOpen = false; invalidate() } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(46f).toInt())

    private fun buttonWidth() = height * 1.1f
    private fun leftWidth() = if (buttons) buttonWidth() else 0f
    private fun rightWidth() = when { buttons -> buttonWidth(); expandable && scrolling() -> buttonWidth() * 0.9f; else -> 0f }

    override fun onDraw(canvas: Canvas) {
        val c = colors ?: return
        canvas.drawColor(c.bar)
        val h = height.toFloat()
        val bw = buttonWidth()
        val qx = width - rightWidth()
        if (buttons) {
            if (pressedIndex == LEFT) highlight(canvas, c, 0f, bw)
            icon.color = if (toolsOpen) c.accent else c.subtle
            Icons.draw(canvas, Icon.ELLIPSIS, bw / 2f, h / 2f, h * 0.42f, icon)
            // The quick button, drawn where the Windows Phone app bar keeps its main action.
            if (pressedIndex == QUICK) highlight(canvas, c, qx, width.toFloat())
            icon.color = c.text
            Icons.draw(canvas, quick.icon, qx + bw / 2f, h / 2f, h * 0.42f, icon)
            if (badge) { fill.color = c.accent; canvas.drawCircle(qx + bw / 2f + h * 0.2f, h / 2f - h * 0.2f, h * 0.075f, fill) }
        } else if (rightWidth() > 0f) {
            if (pressedIndex == EXPAND) highlight(canvas, c, qx, width.toFloat())
            icon.color = c.text
            Icons.draw(canvas, Icon.CHEVRON_DOWN, qx + rightWidth() / 2f, h / 2f, h * 0.36f, icon)
        }

        val x0 = leftWidth()
        val w = width - leftWidth() - rightWidth()
        val text = status
        if (text != null) {
            drawText(canvas, text, x0 + w / 2f, h / 2f, w - PAD, c.text, false, false)
            if (voiceActive && buttons) { icon.color = c.accent; Icons.draw(canvas, Icon.MIC, qx + bw / 2f, h / 2f, h * 0.42f, icon) }
            return
        }
        if (toolsOpen) {
            val cell = w / tools.size
            tools.forEachIndexed { i, t ->
                if (pressedIndex == i) highlight(canvas, c, x0 + cell * i, x0 + cell * (i + 1))
                icon.color = if (t == Tool.MIC && voiceActive) c.accent else c.text
                Icons.draw(canvas, t.icon, x0 + cell * i + cell / 2f, h / 2f, h * 0.4f, icon)
            }
            return
        }
        if (scrolling()) { drawRow(canvas, c, x0, w); return }
        val e = edges(w)
        for (i in 0 until 3) {
            val s = slots.getOrNull(i) ?: continue
            if (pressedIndex == i) highlight(canvas, c, x0 + e[i], x0 + e[i + 1])
            // The word space will insert is in the accent colour, unless that colour would not read on this bar.
            val readable = ColorMath.luminance(c.accent).let { kotlin.math.abs(it - ColorMath.luminance(c.bar)) > 0.12 }
            drawText(canvas, shown(s), x0 + (e[i] + e[i + 1]) / 2f, h / 2f, e[i + 1] - e[i] - PAD, if (s.best && readable) c.accent else c.text, s.best, s.phrase)
        }
    }

    private fun scrolling() = row && !toolsOpen && status == null

    private fun rowStyle(s: Slot?) {
        paint.typeface = if (s?.best == true) Fonts.semibold(context) else Fonts.regular(context)
        paint.textSkewX = 0f
        paint.textSize = height * (if (s?.emoji == true) 0.52f else 0.40f)
    }

    /** Left edges of the cells of the row, plus the right end: an emoji takes a fifth of the width, a word what it needs. */
    private fun rowEdges(w: Float): FloatArray {
        rowEdges?.let { if (it.size == slots.size + 1) return it }
        val e = FloatArray(slots.size + 1)
        slots.forEachIndexed { i, s ->
            val cell = when {
                s == null -> 0f
                s.emoji -> w / 5f
                else -> { rowStyle(s); maxOf(w / 7f, paint.measureText(s.text) + PAD * 1.6f) }
            }
            e[i + 1] = e[i] + cell
        }
        rowEdges = e
        return e
    }

    private fun maxScroll(w: Float) = maxOf(0f, rowEdges(w).last() - w)

    /** The slots as a row that slides sideways. */
    private fun drawRow(canvas: Canvas, c: KeyboardColors, x0: Float, w: Float) {
        val e = rowEdges(w)
        val readable = ColorMath.luminance(c.accent).let { kotlin.math.abs(it - ColorMath.luminance(c.bar)) > 0.12 }
        canvas.save()
        canvas.clipRect(x0, 0f, x0 + w, height.toFloat())
        slots.forEachIndexed { i, s ->
            val left = x0 + e[i] - scroll
            val right = x0 + e[i + 1] - scroll
            if (s == null || right < x0 || left > x0 + w) return@forEachIndexed
            if (pressedIndex == i) highlight(canvas, c, left, right)
            rowStyle(s)
            paint.color = if (s.best && readable) c.accent else c.text
            val m = paint.fontMetrics
            canvas.drawText(s.text, (left + right) / 2f, height / 2f - (m.ascent + m.descent) / 2f, paint)
        }
        canvas.restore()
    }

    private fun shown(s: Slot) = when { s.literal -> "“${s.text}”"; s.phrase -> s.text + " …"; else -> s.text }

    private fun style(bold: Boolean, italic: Boolean) {
        paint.textSize = height * 0.38f
        paint.typeface = if (bold) Fonts.semibold(context) else if (italic) Fonts.light(context) else Fonts.regular(context)
        paint.textSkewX = if (italic) -0.18f else 0f
    }

    /**
     * Left edges of the three cells, plus the right end, over a width of [w]. Equal thirds while every text fits in one; otherwise the
     * room is shared in proportion to the texts, so that a long word is not cut.
     */
    private fun edges(w: Float): FloatArray {
        val want = FloatArray(3) { i ->
            slots.getOrNull(i)?.let { style(it.best, it.phrase); paint.measureText(shown(it)) + PAD } ?: 0f
        }
        val e = FloatArray(4)
        if (want.all { it <= w / 3f }) { for (i in 1..3) e[i] = w / 3f * i; return e }
        val share = FloatArray(3) { if (slots.getOrNull(it) == null) 0f else maxOf(want[it], w / 6f) }
        val k = w / share.sum()
        for (i in 0 until 3) e[i + 1] = e[i] + share[i] * k
        return e
    }

    private fun highlight(canvas: Canvas, c: KeyboardColors, left: Float, right: Float) {
        fill.color = ColorMath.withAlpha(c.text, 0x22)
        canvas.drawRect(left, 0f, right, height.toFloat(), fill)
    }

    private fun drawText(canvas: Canvas, text: String, cx: Float, cy: Float, maxW: Float, color: Int, bold: Boolean, italic: Boolean) {
        paint.color = color
        style(bold, italic)
        // A text that is too wide is made smaller; only below a size that still reads is it cut.
        val full = paint.measureText(text)
        if (full > maxW) paint.textSize = maxOf(paint.textSize * maxW / full, height * 0.38f * MIN_SCALE)
        val shown = TextUtils.ellipsize(text, TextPaint(paint), maxW, TextUtils.TruncateAt.END).toString()
        val m = paint.fontMetrics
        canvas.drawText(shown, cx, cy - (m.ascent + m.descent) / 2f, paint)
        paint.textSkewX = 0f
    }

    private fun indexAt(x: Float): Int {
        val bw = leftWidth()
        if (x < bw) return LEFT
        if (rightWidth() > 0f && x > width - rightWidth()) return if (buttons) QUICK else EXPAND
        val w = width - bw - rightWidth()
        if (scrolling()) {
            val e = rowEdges(w)
            val at = x - bw + scroll
            return (slots.indices.firstOrNull { at < e[it + 1] } ?: slots.size - 1).coerceAtLeast(0)
        }
        if (toolsOpen && status == null) return ((x - bw) / (w / tools.size)).toInt().coerceIn(0, tools.size - 1)
        val e = edges(w)
        return (0..2).firstOrNull { x - bw < e[it + 1] } ?: 2
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressedIndex = indexAt(e.x); downX = e.x; scrollAtDown = scroll; dragging = false; invalidate() }
            MotionEvent.ACTION_MOVE -> {
                if (scrolling() && (dragging || (pressedIndex >= 0 && kotlin.math.abs(e.x - downX) > slop))) {
                    dragging = true; pressedIndex = NONE
                    scroll = (scrollAtDown - (e.x - downX)).coerceIn(0f, maxScroll(width - leftWidth() - rightWidth()))
                    invalidate()
                } else if (indexAt(e.x) != pressedIndex) { pressedIndex = NONE; invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                val i = indexAt(e.x)
                if (!dragging && i == pressedIndex) when {
                    i == LEFT -> toggleTools()
                    i == QUICK -> listener?.onTool(quick)
                    i == EXPAND -> listener?.onExpand()
                    status != null -> {}
                    toolsOpen -> listener?.onTool(tools[i])
                    else -> slots.getOrNull(i)?.let { listener?.onSlot(it) }
                }
                pressedIndex = NONE; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressedIndex = NONE; invalidate() }
        }
        return true
    }

    private val PAD get() = dp(12f)

    private companion object { const val NONE = -9; const val LEFT = -1; const val QUICK = -2; const val EXPAND = -3; const val MIN_SCALE = 0.55f }
}
