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
data class Slot(val text: String, val best: Boolean = false, val literal: Boolean = false, val phrase: Boolean = false)

/**
 * The strip above the keys, laid out like an app bar: the three dots on the left open the toolbox, the middle holds three
 * suggestions (or the tools, when the toolbox is open), and on the right a quick button goes straight to one tool (the clipboard
 * by default), with a dot when something new was copied. Also shows a short status text (what the microphone hears, a GIF search).
 */
class SuggestionBar(context: Context) : View(context) {
    interface Listener {
        fun onSlot(slot: Slot)
        fun onTool(tool: Tool)
    }

    var listener: Listener? = null
    private var colors: KeyboardColors? = null
    private var slots: List<Slot?> = listOf(null, null, null)
    private var toolsOpen = false
    private var status: String? = null
    private var voiceActive = false
    private var quick = Tool.CLIPBOARD
    private var badge = false
    private var pressedIndex = NONE
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tools = Tool.entries

    fun setColors(c: KeyboardColors) { colors = c; invalidate() }
    fun setSlots(s: List<Slot?>) { slots = s; if (!toolsOpen) invalidate() }
    fun setStatus(text: String?) { status = text; invalidate() }
    fun setVoiceActive(on: Boolean) { voiceActive = on; invalidate() }
    fun setQuickTool(t: Tool) { if (quick != t) { quick = t; invalidate() } }
    fun setBadge(on: Boolean) { if (badge != on) { badge = on; invalidate() } }
    fun toggleTools() { toolsOpen = !toolsOpen; invalidate() }
    fun closeTools() { if (toolsOpen) { toolsOpen = false; invalidate() } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(46f).toInt())

    private fun buttonWidth() = height * 1.1f

    override fun onDraw(canvas: Canvas) {
        val c = colors ?: return
        canvas.drawColor(c.bar)
        val h = height.toFloat()
        val bw = buttonWidth()
        if (pressedIndex == LEFT) highlight(canvas, c, 0f, bw)
        icon.color = if (toolsOpen) c.accent else c.subtle
        Icons.draw(canvas, Icon.ELLIPSIS, bw / 2f, h / 2f, h * 0.42f, icon)

        // The quick button, drawn where the Windows Phone app bar keeps its main action.
        val qx = width - bw
        if (pressedIndex == QUICK) highlight(canvas, c, qx, width.toFloat())
        icon.color = c.text
        Icons.draw(canvas, quick.icon, qx + bw / 2f, h / 2f, h * 0.42f, icon)
        if (badge) { fill.color = c.accent; canvas.drawCircle(qx + bw / 2f + h * 0.2f, h / 2f - h * 0.2f, h * 0.075f, fill) }

        val x0 = bw
        val w = width - 2 * bw
        val text = status
        if (text != null) {
            drawText(canvas, text, x0 + w / 2f, h / 2f, w - dp(12f), c.text, false, false)
            if (voiceActive) { icon.color = c.accent; Icons.draw(canvas, Icon.MIC, qx + bw / 2f, h / 2f, h * 0.42f, icon) }
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
        val cell = w / 3f
        for (i in 0 until 3) {
            val s = slots.getOrNull(i) ?: continue
            if (pressedIndex == i) highlight(canvas, c, x0 + cell * i, x0 + cell * (i + 1))
            val shown = when { s.literal -> "“${s.text}”"; s.phrase -> s.text + " …"; else -> s.text }
            // The word space will insert is in the accent colour, unless that colour would not read on this bar.
            val readable = ColorMath.luminance(c.accent).let { kotlin.math.abs(it - ColorMath.luminance(c.bar)) > 0.12 }
            drawText(canvas, shown, x0 + cell * i + cell / 2f, h / 2f, cell - dp(12f), if (s.best && readable) c.accent else c.text, s.best, s.phrase)
        }
    }

    private fun highlight(canvas: Canvas, c: KeyboardColors, left: Float, right: Float) {
        fill.color = ColorMath.withAlpha(c.text, 0x22)
        canvas.drawRect(left, 0f, right, height.toFloat(), fill)
    }

    private fun drawText(canvas: Canvas, text: String, cx: Float, cy: Float, maxW: Float, color: Int, bold: Boolean, italic: Boolean) {
        paint.color = color
        paint.textSize = height * 0.38f
        paint.typeface = if (bold) Fonts.semibold(context) else if (italic) Fonts.light(context) else Fonts.regular(context)
        paint.textSkewX = if (italic) -0.18f else 0f
        val shown = TextUtils.ellipsize(text, TextPaint(paint), maxW, TextUtils.TruncateAt.END).toString()
        val m = paint.fontMetrics
        canvas.drawText(shown, cx, cy - (m.ascent + m.descent) / 2f, paint)
        paint.textSkewX = 0f
    }

    private fun indexAt(x: Float): Int {
        val bw = buttonWidth()
        if (x < bw) return LEFT
        if (x > width - bw) return QUICK
        val w = width - 2 * bw
        return if (toolsOpen && status == null) ((x - bw) / (w / tools.size)).toInt().coerceIn(0, tools.size - 1)
        else ((x - bw) / (w / 3f)).toInt().coerceIn(0, 2)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressedIndex = indexAt(e.x); invalidate() }
            MotionEvent.ACTION_MOVE -> { if (indexAt(e.x) != pressedIndex) { pressedIndex = NONE; invalidate() } }
            MotionEvent.ACTION_UP -> {
                val i = indexAt(e.x)
                if (i == pressedIndex) when {
                    i == LEFT -> toggleTools()
                    i == QUICK -> listener?.onTool(quick)
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

    private companion object { const val NONE = -9; const val LEFT = -1; const val QUICK = -2 }
}
