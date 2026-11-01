package com.metrowin.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import com.metrowin.keyboard.theme.ColorMath
import com.metrowin.keyboard.theme.KeyboardColors

enum class Tool(val icon: Icon) { CLIPBOARD(Icon.CLIPBOARD), GIF(Icon.GIF), EMOJI(Icon.EMOJI), MIC(Icon.MIC), SETTINGS(Icon.SETTINGS) }

/** One of the three places of the bar. [best] is the word that space will insert; [literal] is exactly what was typed. */
data class Slot(val text: String, val best: Boolean = false, val literal: Boolean = false, val phrase: Boolean = false)

/**
 * The strip above the keys: three suggestions, or, after the button on the left, the tools (clipboard, GIF, emoji, voice,
 * settings). Also shows a short status text (what the microphone is hearing, the GIF search being typed).
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
    private var pressedIndex = -2
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tools = Tool.entries

    fun setColors(c: KeyboardColors) { colors = c; invalidate() }
    fun setSlots(s: List<Slot?>) { slots = s; if (!toolsOpen) invalidate() }
    fun setStatus(text: String?) { status = text; invalidate() }
    fun setVoiceActive(on: Boolean) { voiceActive = on; invalidate() }
    fun toggleTools() { toolsOpen = !toolsOpen; invalidate() }
    fun closeTools() { if (toolsOpen) { toolsOpen = false; invalidate() } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(46f).toInt())

    private fun buttonWidth() = height.toFloat()

    override fun onDraw(canvas: Canvas) {
        val c = colors ?: return
        canvas.drawColor(c.bar)
        val h = height.toFloat()
        val bw = buttonWidth()
        // Left button: the tools or, when they are open, a way back to the suggestions.
        icon.color = c.subtle
        Icons.draw(canvas, if (toolsOpen) Icon.CLOSE else Icon.GRID, bw / 2f, h / 2f, h * 0.36f, icon)
        val x0 = bw
        val w = width - bw
        val text = status
        if (text != null) {
            drawText(canvas, text, x0 + w / 2f, h / 2f, w - dp(24f), c.text, false, false)
            if (voiceActive) { icon.color = c.accent; Icons.draw(canvas, Icon.MIC, width - dp(26f), h / 2f, h * 0.4f, icon) }
            return
        }
        if (toolsOpen) {
            val cell = w / tools.size
            tools.forEachIndexed { i, t ->
                val cx = x0 + cell * i + cell / 2f
                val active = t == Tool.MIC && voiceActive
                if (pressedIndex == i) { fill.color = ColorMath.withAlpha(c.text, 0x22); canvas.drawRect(x0 + cell * i, 0f, x0 + cell * (i + 1), h, fill) }
                icon.color = if (active) c.accent else c.text
                Icons.draw(canvas, t.icon, cx, h / 2f, h * 0.42f, icon)
            }
            return
        }
        val cell = w / 3f
        for (i in 0 until 3) {
            val s = slots.getOrNull(i) ?: continue
            if (pressedIndex == i) { fill.color = ColorMath.withAlpha(c.text, 0x22); canvas.drawRect(x0 + cell * i, 0f, x0 + cell * (i + 1), h, fill) }
            if (i > 0) { fill.color = ColorMath.withAlpha(c.text, 0x25); canvas.drawRect(x0 + cell * i - 0.5f, h * 0.25f, x0 + cell * i + 0.5f, h * 0.75f, fill) }
            val shown = when { s.literal -> "“${s.text}”"; s.phrase -> s.text + " …"; else -> s.text }
            drawText(canvas, shown, x0 + cell * i + cell / 2f, h / 2f, cell - dp(14f), if (s.best) c.accent.let { if (ColorMath.isDark(c.bar) == ColorMath.isDark(it)) c.text else it } else c.text, s.best, s.phrase)
            if (s.best) { fill.color = c.accent; canvas.drawRect(x0 + cell * i + cell * 0.3f, h - dp(3f), x0 + cell * i + cell * 0.7f, h - dp(1f), fill) }
        }
    }

    private fun drawText(canvas: Canvas, text: String, cx: Float, cy: Float, maxW: Float, color: Int, bold: Boolean, italic: Boolean) {
        paint.color = color
        paint.textSize = height * 0.40f
        paint.typeface = Typeface.create(Typeface.DEFAULT, if (bold && italic) Typeface.BOLD_ITALIC else if (bold) Typeface.BOLD else if (italic) Typeface.ITALIC else Typeface.NORMAL)
        val shown = TextUtils.ellipsize(text, android.text.TextPaint(paint), maxW, TextUtils.TruncateAt.END).toString()
        val m = paint.fontMetrics
        canvas.drawText(shown, cx, cy - (m.ascent + m.descent) / 2f, paint)
    }

    private fun indexAt(x: Float): Int {
        val bw = buttonWidth()
        if (x < bw) return -1
        val w = width - bw
        return if (toolsOpen && status == null) ((x - bw) / (w / tools.size)).toInt().coerceIn(0, tools.size - 1)
        else ((x - bw) / (w / 3f)).toInt().coerceIn(0, 2)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressedIndex = indexAt(e.x); invalidate() }
            MotionEvent.ACTION_MOVE -> { val i = indexAt(e.x); if (i != pressedIndex) { pressedIndex = -2; invalidate() } }
            MotionEvent.ACTION_UP -> {
                val i = indexAt(e.x)
                if (i == pressedIndex) {
                    if (i == -1) toggleTools()
                    else if (status != null) { /* nothing to tap while a status is shown */ }
                    else if (toolsOpen) listener?.onTool(tools[i])
                    else slots.getOrNull(i)?.let { listener?.onSlot(it) }
                }
                pressedIndex = -2; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressedIndex = -2; invalidate() }
        }
        return true
    }
}
