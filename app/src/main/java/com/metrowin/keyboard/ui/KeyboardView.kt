package com.metrowin.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.metrowin.keyboard.engine.SwipePoint
import com.metrowin.keyboard.layout.Key
import com.metrowin.keyboard.layout.KeyType
import com.metrowin.keyboard.layout.KeyboardPage
import com.metrowin.keyboard.theme.ColorMath
import com.metrowin.keyboard.theme.KeyboardColors
import com.metrowin.keyboard.theme.ThemeStyle
import kotlin.math.abs
import kotlin.math.hypot

/** What the keyboard view tells the input method. */
interface KeyboardListener {
    /** A key was pressed (finger down). Sound and vibration. */
    fun onPress(key: Key)

    /** A character key was released: [text] already has the case of the shift state. */
    fun onText(key: Key, text: String)

    /** A function key (shift, backspace, enter, space, symbols, emoji, language). Backspace comes here again for each repeat. */
    fun onFunction(key: Key, repeat: Boolean)

    /** The user picked a character from the long-press bar. */
    fun onPopupText(text: String)

    /** The finger drew a word over the letters. */
    fun onSwipe(path: List<SwipePoint>)

    /** The finger slid over the space bar: move the cursor by [steps] characters. */
    fun onCursorMove(steps: Int)

    /** Shift was held down: lock capitals. */
    fun onShiftLongPress()
}

/**
 * The keys. One custom view that lays the rows out, draws them in the theme, and turns touches into key presses, long-press
 * bars, space-bar cursor sliding and swipe typing. Several fingers can type at once.
 */
class KeyboardView(context: Context) : View(context) {
    var listener: KeyboardListener? = null

    private var page: KeyboardPage? = null
    private var colors: KeyboardColors? = null
    private var shift = 0                        // 0 off, 1 next letter, 2 caps lock
    private var languageName = ""
    private var enterIcon: Icon = Icon.ENTER
    private var enterLabel: String? = null
    private var backgroundBitmap: Bitmap? = null
    private var bgDim = 55

    private var heightPct = 100
    private var gapDp = 3f
    private var widthPct = 100
    private var align = "center"
    private var longPressMs = 200L
    private var longPressEnabled = true
    private var swipeEnabled = true
    private var spaceCursorEnabled = true
    private var showHints = true

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val trail = Path()

    private class Pointer(val id: Int, val startKey: Key, val downX: Float, val downY: Float) {
        var key: Key? = startKey
        var popupKey: Key? = null
        var popupIndex = 0
        var swiping = false
        var cursorMoved = false
        var cursorAnchorX = downX
        var longFired = false
        var repeat: Runnable? = null
        val path = ArrayList<SwipePoint>()
    }

    private val pointers = HashMap<Int, Pointer>()
    private var fadingTrail: List<SwipePoint>? = null
    private var fadeStart = 0L

    // --- configuration ----------------------------------------------------------------------------------------------

    fun setPage(p: KeyboardPage) { page = p; layoutKeys(); requestLayout(); invalidate() }
    fun setColors(c: KeyboardColors) { colors = c; invalidate() }
    fun setShift(state: Int) { if (shift != state) { shift = state; invalidate() } }
    fun setLanguageName(name: String) { languageName = name; invalidate() }
    fun setEnterAction(icon: Icon, label: String?) { enterIcon = icon; enterLabel = label; invalidate() }
    fun setBackgroundImage(b: Bitmap?, dim: Int) { backgroundBitmap = b; bgDim = dim; invalidate() }
    fun setBehavior(longPress: Boolean, longPressMs: Int, swipe: Boolean, spaceCursor: Boolean, hints: Boolean) {
        this.longPressEnabled = longPress; this.longPressMs = longPressMs.toLong(); this.swipeEnabled = swipe
        this.spaceCursorEnabled = spaceCursor; this.showHints = hints; invalidate()
    }
    fun setMetrics(heightPct: Int, gapDp: Int, widthPct: Int, align: String) {
        if (this.heightPct == heightPct && this.gapDp == gapDp.toFloat() && this.widthPct == widthPct && this.align == align) return
        this.heightPct = heightPct; this.gapDp = gapDp.toFloat(); this.widthPct = widthPct; this.align = align
        requestLayout(); layoutKeys(); invalidate()
    }

    /** Centres of the letter keys in view coordinates, for the swipe decoder; and the width of a key. */
    fun letterCenters(): Map<Char, SwipePoint> {
        val out = HashMap<Char, SwipePoint>()
        page?.keys?.forEach { k -> if (k.type == KeyType.CHAR && k.label.length == 1 && k.label[0].isLetter()) out[k.label[0].lowercaseChar()] = SwipePoint(k.centerX, k.centerY) }
        return out
    }

    fun keyWidth(): Float = page?.keys?.firstOrNull { it.type == KeyType.CHAR }?.width ?: dp(36f)

    // --- layout -----------------------------------------------------------------------------------------------------

    private fun baseRowHeight(): Float {
        val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        return dp(if (land) 40f else 52f) * heightPct / 100f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rows = page?.rows?.size ?: 4
        val gap = dp(gapDp)
        val h = (rows * baseRowHeight() + (rows + 1) * gap + dp(2f)).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { layoutKeys() }

    private fun layoutKeys() {
        val p = page ?: return
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val gap = dp(gapDp)
        val contentW = w * widthPct / 100f
        val offset = when (align) { "left" -> 0f; "right" -> w - contentW; else -> (w - contentW) / 2f }
        val rowH = (h - gap * (p.rows.size + 1) - dp(2f)) / p.rows.size
        p.rows.forEachIndexed { r, row ->
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            val unit = (contentW - gap * (row.size + 1)) / total
            var x = offset + gap
            val top = gap + r * (rowH + gap)
            for (k in row) {
                k.left = x; k.right = x + unit * k.weight; k.top = top; k.bottom = top + rowH
                x = k.right + gap
            }
        }
    }

    // --- drawing ----------------------------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val c = colors ?: return
        val p = page ?: return
        drawBackground(canvas, c)
        val corner = dp(c.cornerDp)
        for (k in p.keys) drawKey(canvas, k, c, corner)
        drawPopup(canvas, c)
        drawTrail(canvas, c)
    }

    private fun drawBackground(canvas: Canvas, c: KeyboardColors) {
        canvas.drawColor(c.background)
        val bmp = backgroundBitmap ?: return
        val scale = maxOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
        val dw = bmp.width * scale; val dh = bmp.height * scale
        rect.set((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
        canvas.drawBitmap(bmp, null, rect, bgPaint)
        // The dim keeps the keys readable over any picture.
        canvas.drawColor(ColorMath.withAlpha(if (c.dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), bgDim * 255 / 100))
    }

    private fun isPressed(k: Key): Boolean = pointers.values.any { it.key === k && it.popupKey == null && !it.swiping }

    private fun drawKey(canvas: Canvas, k: Key, c: KeyboardColors, corner: Float) {
        val pressed = isPressed(k)
        val isEnter = k.type == KeyType.ENTER
        val shiftOn = k.type == KeyType.SHIFT && shift != 0
        val fill = when {
            pressed -> c.keyPressed
            isEnter || shiftOn && c.style == ThemeStyle.CLASSIC -> c.accent
            k.special -> c.special
            else -> c.key
        }
        rect.set(k.left, k.top, k.right, k.bottom)
        keyPaint.color = fill
        canvas.drawRoundRect(rect, corner, corner, keyPaint)
        if (c.strokeDp > 0f && !pressed) {
            strokePaint.color = c.stroke; strokePaint.strokeWidth = dp(c.strokeDp)
            canvas.drawRoundRect(rect, corner, corner, strokePaint)
        }
        val fg = when {
            isEnter || (pressed && c.style != ThemeStyle.CLASSIC) -> if (isEnter) c.onAccent else ColorMath.onColor(fill)
            shiftOn && c.style == ThemeStyle.CLASSIC -> c.onAccent
            pressed -> c.onAccent
            else -> c.text
        }
        iconPaint.color = fg
        val cx = k.centerX; val cy = k.centerY
        val iconSize = minOf(k.height, k.width) * 0.46f
        when (k.type) {
            KeyType.SHIFT -> Icons.draw(canvas, if (shift == 2) Icon.SHIFT_LOCK else Icon.SHIFT, cx, cy, iconSize, iconPaint.apply { color = if (shiftOn && c.style != ThemeStyle.CLASSIC) c.accent else fg })
            KeyType.BACKSPACE -> Icons.draw(canvas, Icon.BACKSPACE, cx, cy, iconSize, iconPaint)
            KeyType.ENTER -> if (enterLabel != null) drawLabel(canvas, enterLabel!!, cx, cy, k.height * 0.30f, fg, true) else Icons.draw(canvas, enterIcon, cx, cy, iconSize, iconPaint)
            KeyType.EMOJI -> Icons.draw(canvas, Icon.EMOJI, cx, cy, iconSize, iconPaint)
            KeyType.LANGUAGE -> Icons.draw(canvas, Icon.GLOBE, cx, cy, iconSize, iconPaint)
            KeyType.SPACE -> if (languageName.isNotEmpty()) drawLabel(canvas, languageName, cx, cy, k.height * 0.26f, ColorMath.withAlpha(c.text, 0x90), false)
            KeyType.SYMBOLS, KeyType.SYMBOLS_PAGE -> drawLabel(canvas, k.label, cx, cy, k.height * 0.30f, fg, false)
            KeyType.CHAR -> {
                val label = if (shift != 0) k.label.uppercase() else k.label
                drawLabel(canvas, label, cx, cy, k.height * (if (label.length > 1) 0.30f else 0.42f), fg, false)
                if (showHints && k.hint != null) {
                    hintPaint.color = c.subtle; hintPaint.textSize = k.height * 0.22f
                    canvas.drawText(k.hint, k.right - dp(5f), k.top + k.height * 0.28f, hintPaint)
                }
            }
        }
    }

    private fun drawLabel(canvas: Canvas, text: String, cx: Float, cy: Float, size: Float, color: Int, bold: Boolean) {
        textPaint.color = color; textPaint.textSize = size
        textPaint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val m = textPaint.fontMetrics
        canvas.drawText(text, cx, cy - (m.ascent + m.descent) / 2f, textPaint)
    }

    // --- long-press bar ---------------------------------------------------------------------------------------------

    private fun popupGeometry(p: Pointer): FloatArray? {
        val k = p.popupKey ?: return null
        val items = k.popups.size
        val itemW = maxOf(k.width, dp(40f))
        val total = itemW * items
        val reversed = k.centerX > width * 0.6f
        var left = if (reversed) k.centerX + itemW / 2f - total else k.centerX - itemW / 2f
        left = left.coerceIn(0f, maxOf(0f, width - total))
        val h = k.height * 1.05f
        val top = maxOf(0f, k.top - h - dp(2f))
        return floatArrayOf(left, top, itemW, h, if (reversed) 1f else 0f)
    }

    private fun drawPopup(canvas: Canvas, c: KeyboardColors) {
        for (p in pointers.values) {
            val k = p.popupKey ?: continue
            val g = popupGeometry(p) ?: continue
            val (left, top, itemW, h) = listOf(g[0], g[1], g[2], g[3])
            val reversed = g[4] == 1f
            val total = itemW * k.popups.size
            rect.set(left, top, left + total, top + h)
            keyPaint.color = c.popup
            val corner = dp(maxOf(c.cornerDp, 6f))
            canvas.drawRoundRect(rect, corner, corner, keyPaint)
            k.popups.forEachIndexed { i, s ->
                val x = if (reversed) left + total - (i + 1) * itemW else left + i * itemW
                if (i == p.popupIndex) {
                    rect.set(x + dp(2f), top + dp(2f), x + itemW - dp(2f), top + h - dp(2f))
                    keyPaint.color = c.accent
                    canvas.drawRoundRect(rect, corner, corner, keyPaint)
                }
                val shown = if (shift != 0) s.uppercase() else s
                drawLabel(canvas, shown, x + itemW / 2f, top + h / 2f, h * 0.45f, if (i == p.popupIndex) c.onAccent else c.text, false)
            }
        }
    }

    private fun drawTrail(canvas: Canvas, c: KeyboardColors) {
        val live = pointers.values.firstOrNull { it.swiping }?.path
        val pts = live ?: fadingTrail ?: return
        var alpha = 0xC0
        if (live == null) {
            val t = (SystemClock.uptimeMillis() - fadeStart) / 280f
            if (t >= 1f) { fadingTrail = null; return }
            alpha = (0xC0 * (1f - t)).toInt()
            postInvalidateOnAnimation()
        }
        if (pts.size < 2) return
        trail.reset()
        trail.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) trail.lineTo(pts[i].x, pts[i].y)
        trailPaint.color = ColorMath.withAlpha(c.accent, alpha)
        trailPaint.strokeWidth = dp(7f)
        canvas.drawPath(trail, trailPaint)
    }

    // --- touch ------------------------------------------------------------------------------------------------------

    private fun keyAt(x: Float, y: Float): Key? {
        val keys = page?.keys ?: return null
        var best: Key? = null
        var bestD = Float.MAX_VALUE
        for (k in keys) {
            val d = k.distanceSq(x, y)
            if (d == 0f) return k
            if (d < bestD) { bestD = d; best = k }
        }
        // A touch far outside the keyboard is not a key press.
        return if (bestD <= dp(14f) * dp(14f)) best else null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                up(e.getPointerId(i), e.getX(i), e.getY(i), cancel = false)
            }
            MotionEvent.ACTION_CANCEL -> for (id in pointers.keys.toList()) up(id, 0f, 0f, cancel = true)
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        val key = keyAt(x, y) ?: return
        val p = Pointer(id, key, x, y)
        p.path += SwipePoint(x, y)
        pointers[id] = p
        listener?.onPress(key)
        when (key.type) {
            KeyType.BACKSPACE -> {
                listener?.onFunction(key, false)
                scheduleRepeat(p)
            }
            else -> if (canLongPress(key)) postDelayed(longPressRunnable(p), longPressMs)
        }
        invalidate()
    }

    private fun canLongPress(key: Key) = when (key.type) {
        KeyType.CHAR -> longPressEnabled && key.popups.isNotEmpty()
        KeyType.SHIFT -> true
        else -> false
    }

    private fun longPressRunnable(p: Pointer) = object : Runnable {
        override fun run() {
            if (pointers[p.id] !== p || p.swiping || p.cursorMoved) return
            val k = p.key ?: return
            if (k !== p.startKey) return
            p.longFired = true
            if (k.type == KeyType.SHIFT) { listener?.onShiftLongPress(); return }
            p.popupKey = k; p.popupIndex = 0
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }

    private fun scheduleRepeat(p: Pointer) {
        var delay = maxOf(longPressMs, 320L)
        val r = object : Runnable {
            override fun run() {
                if (pointers[p.id] !== p) return
                listener?.onFunction(p.startKey, true)
                delay = (delay * 0.85).toLong().coerceAtLeast(45L)
                postDelayed(this, if (delay < 120L) 45L else delay)
            }
        }
        postDelayed(r, delay)
        p.repeat = r
    }

    private fun move(id: Int, x: Float, y: Float) {
        val p = pointers[id] ?: return
        p.path += SwipePoint(x, y)
        val start = p.startKey

        if (p.popupKey != null) {
            val g = popupGeometry(p) ?: return
            val idx = if (g[4] == 1f) ((g[0] + g[2] * p.popupKey!!.popups.size - x) / g[2]).toInt() else ((x - g[0]) / g[2]).toInt()
            val n = p.popupKey!!.popups.size
            val clamped = idx.coerceIn(0, n - 1)
            if (clamped != p.popupIndex) { p.popupIndex = clamped; invalidate() }
            return
        }
        if (p.swiping) { invalidate(); return }

        if (start.type == KeyType.SPACE && spaceCursorEnabled) {
            val step = dp(14f)
            val dx = x - p.cursorAnchorX
            if (abs(dx) >= step) {
                val steps = (dx / step).toInt()
                p.cursorAnchorX += steps * step
                p.cursorMoved = true
                listener?.onCursorMove(steps)
            }
            return
        }

        val travelled = hypot(x - p.downX, y - p.downY)
        if (swipeEnabled && start.type == KeyType.CHAR && start.label.length == 1 && start.label[0].isLetter() && !p.longFired &&
            travelled > maxOf(start.width, dp(34f)) * 0.95f
        ) {
            p.swiping = true
            p.key = null
            invalidate()
            return
        }
        // Slide to another key: the highlight follows the finger and the key under it at release is the one typed.
        val under = keyAt(x, y)
        if (under !== p.key && start.type != KeyType.BACKSPACE) { p.key = under; invalidate() }
    }

    private fun up(id: Int, x: Float, y: Float, cancel: Boolean) {
        val p = pointers.remove(id) ?: return
        p.repeat?.let { removeCallbacks(it) }
        if (!cancel) {
            when {
                p.popupKey != null -> {
                    val k = p.popupKey!!
                    k.popups.getOrNull(p.popupIndex)?.let { listener?.onPopupText(if (shift != 0) it.uppercase() else it) }
                }
                p.swiping -> {
                    p.path += SwipePoint(x, y)
                    fadingTrail = p.path.toList(); fadeStart = SystemClock.uptimeMillis()
                    listener?.onSwipe(p.path)
                }
                p.cursorMoved || p.longFired -> {}
                p.startKey.type == KeyType.BACKSPACE -> {}
                else -> {
                    val k = p.key ?: keyAt(x, y)
                    if (k != null) {
                        if (k.type == KeyType.CHAR) listener?.onText(k, if (shift != 0) k.label.uppercase() else k.label)
                        else listener?.onFunction(k, false)
                    }
                }
            }
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pointers.clear()
    }
}
