package com.metrowin.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.metrowin.keyboard.theme.ColorMath
import com.metrowin.keyboard.theme.KeyboardColors

/**
 * All the Chinese words for the Pinyin typed, in the place of the keys: rows of cells as wide as their words, that slide up and
 * down. The arrow at the top right folds it back to the bar, as the arrow at the end of the bar opened it.
 */
class CandidatePanel(context: Context) : View(context) {
    var onPick: ((Int) -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private var colors: KeyboardColors? = null
    private var words: List<String> = emptyList()
    private var cells: List<FloatArray> = emptyList()      // left, top, right, bottom of each word
    private var laidFor = 0
    private var scroll = 0f
    private var downY = 0f
    private var downX = 0f
    private var scrollAtDown = 0f
    private var dragging = false
    private var pressed = NONE
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setColors(c: KeyboardColors) { colors = c; invalidate() }

    fun show(list: List<String>) {
        words = list; laidFor = 0; scroll = 0f; pressed = NONE
        invalidate()
    }

    internal fun wordsForTest() = words

    private val rowH get() = dp(46f)
    private val closeW get() = dp(52f)

    private fun layoutCells() {
        if (laidFor == width && cells.size == words.size) return
        paint.textSize = rowH * 0.40f
        paint.typeface = Fonts.regular(context)
        val out = ArrayList<FloatArray>(words.size)
        val minW = width / 6f
        var x = 0f; var y = 0f
        for (w in words) {
            // The first row leaves room for the arrow that folds the panel.
            val limit = if (y == 0f) width - closeW else width.toFloat()
            val cw = maxOf(minW, paint.measureText(w) + dp(24f)).coerceAtMost(width.toFloat())
            if (x > 0f && x + cw > limit) { x = 0f; y += rowH }
            out += floatArrayOf(x, y, x + cw, y + rowH)
            x += cw
        }
        cells = out; laidFor = width
    }

    private fun maxScroll() = maxOf(0f, (cells.lastOrNull()?.get(3) ?: 0f) - height)

    override fun onDraw(canvas: Canvas) {
        val c = colors ?: return
        canvas.drawColor(c.background)
        layoutCells()
        paint.textSize = rowH * 0.40f
        paint.typeface = Fonts.regular(context)
        val m = paint.fontMetrics
        fill.color = ColorMath.withAlpha(c.text, 0x18)
        cells.forEachIndexed { i, r ->
            val top = r[1] - scroll
            if (top + rowH < 0f || top > height) return@forEachIndexed
            if (i == pressed) { fill.color = ColorMath.withAlpha(c.accent, 0xC0); canvas.drawRect(r[0], top, r[2], top + rowH, fill) }
            // A hairline under each row, as the lists of Windows Phone have.
            fill.color = ColorMath.withAlpha(c.text, 0x18)
            canvas.drawRect(r[0], top + rowH - dp(1f), r[2], top + rowH, fill)
            paint.color = if (i == pressed) c.onAccent else if (i == 0) c.accent else c.text
            canvas.drawText(words[i], (r[0] + r[2]) / 2f, top + rowH / 2f - (m.ascent + m.descent) / 2f, paint)
        }
        // The arrow that folds the panel, always at the top right.
        val cx = width - closeW
        fill.color = if (pressed == CLOSE) ColorMath.withAlpha(c.text, 0x30) else c.background
        canvas.drawRect(cx, 0f, width.toFloat(), rowH, fill)
        Icons.draw(canvas, Icon.CHEVRON_UP, cx + closeW / 2f, rowH / 2f, rowH * 0.36f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.text })
    }

    private fun indexAt(x: Float, y: Float): Int {
        if (x >= width - closeW && y < rowH) return CLOSE
        val at = y + scroll
        return cells.indexOfFirst { x >= it[0] && x < it[2] && at >= it[1] && at < it[3] }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; scrollAtDown = scroll; dragging = false; pressed = indexAt(e.x, e.y); invalidate() }
            MotionEvent.ACTION_MOVE -> if (dragging || kotlin.math.abs(e.y - downY) > slop) {
                dragging = true; pressed = NONE
                scroll = (scrollAtDown - (e.y - downY)).coerceIn(0f, maxScroll())
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val i = indexAt(e.x, e.y)
                if (!dragging && i == pressed) when {
                    i == CLOSE -> onClose?.invoke()
                    i >= 0 -> onPick?.invoke(i)
                }
                pressed = NONE; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = NONE; invalidate() }
        }
        return true
    }

    private companion object { const val NONE = -9; const val CLOSE = -2 }
}
