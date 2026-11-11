package com.w10m.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.PopupWindow
import android.widget.TextView
import kotlin.math.abs

/** Two-page horizontal pager: Start ⇄ All apps, follows the finger like W10M. */
class Pager(context: Context) : ViewGroup(context) {
    var page = 0
        private set
    var enabledPaging = true
    var onPageChanged: ((Int) -> Unit)? = null
    var onScrollFraction: ((Float) -> Unit)? = null

    private val scroller = OverScroller(context, DecelerateInterpolator(2f))
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private var vt: VelocityTracker? = null
    private var downX = 0f; private var downY = 0f; private var lastX = 0f
    private var dragging = false

    override fun onMeasure(w: Int, h: Int) {
        val ws = MeasureSpec.getSize(w); val hs = MeasureSpec.getSize(h)
        for (i in 0 until childCount) getChildAt(i).measure(
            MeasureSpec.makeMeasureSpec(ws, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(hs, MeasureSpec.EXACTLY))
        setMeasuredDimension(ws, hs)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (i in 0 until childCount) getChildAt(i).layout(i * w, 0, (i + 1) * w, b - t)
        if (changed) scrollTo(page * w, 0)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (!enabledPaging) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; dragging = false
                if (!scroller.isFinished) { scroller.abortAnimation(); dragging = true }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX; val dy = e.y - downY
                if (abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) {
                    dragging = true; lastX = e.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
        if (dragging) { vt = vt ?: VelocityTracker.obtain(); vt!!.addMovement(e) }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!enabledPaging) return false
        vt = vt ?: VelocityTracker.obtain(); vt!!.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; lastX = e.x }
            MotionEvent.ACTION_MOVE -> {
                val dx = lastX - e.x; lastX = e.x
                val nx = (scrollX + dx).coerceIn(0f, ((childCount - 1) * width).toFloat())
                scrollTo(nx.toInt(), 0)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt!!.computeCurrentVelocity(1000)
                val v = vt!!.xVelocity
                vt!!.recycle(); vt = null; dragging = false
                val target = when {
                    v < -minFling -> page + 1
                    v > minFling -> page - 1
                    else -> Math.round(scrollX.toFloat() / width)
                }
                snapTo(target.coerceIn(0, childCount - 1))
            }
        }
        return true
    }

    fun snapTo(p: Int, animate: Boolean = true) {
        val old = page
        page = p
        val dx = p * width - scrollX
        if (animate) {
            scroller.startScroll(scrollX, 0, dx, 0, Metro.DUR_SLOW.toInt())
            postInvalidateOnAnimation()
        } else scrollTo(p * width, 0)
        if (old != p) onPageChanged?.invoke(p)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) { scrollTo(scroller.currX, 0); postInvalidateOnAnimation() }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (width > 0) onScrollFraction?.invoke(l.toFloat() / width)
    }
}

/** W10M MenuFlyout: dark #2B2B2B panel, 1 px border, 48 epx touch rows, 15 epx text, 12 epx padding. */
object MetroMenu {
    fun show(anchor: View, palette: Palette, items: List<Pair<String, () -> Unit>>) {
        val c = anchor.context
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(palette.flyout); setStroke(c.dpi(1f), palette.chromeHigh) }
            setPadding(0, c.dpi(8f), 0, c.dpi(8f))
        }
        val pw = PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        for ((label, action) in items) {
            box.addView(TextView(c).apply {
                text = label
                textSize = 15f
                typeface = Metro.regular(c)
                setTextColor(palette.fg)
                gravity = Gravity.CENTER_VERTICAL
                minWidth = c.dpi(200f)
                setPadding(c.dpi(12f), 0, c.dpi(12f), 0)
                isClickable = true
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(if (palette.dark) 0x33FFFFFF else 0x33000000))
                    addState(intArrayOf(), ColorDrawable(0))
                }
                setOnClickListener { pw.dismiss(); action() }
            }, LinearLayout.LayoutParams(-1, c.dpi(48f)))
        }
        pw.elevation = 0f
        pw.setBackgroundDrawable(ColorDrawable(0))
        pw.isOutsideTouchable = true
        pw.animationStyle = android.R.style.Animation_Dialog
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2); anchor.getLocationInWindow(loc)
        val x = loc[0] + c.dpi(Metro.PAGE_MARGIN)
        val screenH = anchor.rootView.height
        var y = loc[1] + anchor.height
        if (y + box.measuredHeight > screenH - c.dpi(48f)) y = loc[1] - box.measuredHeight
        pw.showAtLocation(anchor.rootView, Gravity.TOP or Gravity.START, x, y)
    }
}

/** UWP ToggleSwitch: 44×20 pill, 2 epx border, 10 epx knob; "On"/"Off" text to the right. */
class MetroToggle(context: Context, private val palette: Palette) : View(context) {
    var checked = false
        set(v) { field = v; invalidate() }
    var onChange: ((Boolean) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.sp(15f); typeface = Metro.regular(context) }
    private val r = RectF()

    init { isClickable = true; setOnClickListener { checked = !checked; onChange?.invoke(checked) } }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.dpi(40f))

    override fun onDraw(c: Canvas) {
        val w = context.dp(44f); val h = context.dp(20f); val top = (height - h) / 2
        r.set(context.dp(1f), top + context.dp(1f), w - context.dp(1f), top + h - context.dp(1f))
        if (checked) {
            p.style = Paint.Style.FILL; p.color = palette.accent
            r.set(0f, top, w, top + h); c.drawRoundRect(r, h / 2, h / 2, p)
            p.color = android.graphics.Color.WHITE
            c.drawCircle(w - h / 2, top + h / 2, context.dp(5f), p)
        } else {
            p.style = Paint.Style.STROKE; p.strokeWidth = context.dp(2f); p.color = palette.fg
            c.drawRoundRect(r, h / 2, h / 2, p)
            p.style = Paint.Style.FILL
            c.drawCircle(h / 2, top + h / 2, context.dp(5f), p)
        }
        tp.color = palette.fg
        c.drawText(context.getString(if (checked) R.string.on else R.string.off), w + context.dp(12f),
            height / 2f - (tp.ascent() + tp.descent()) / 2, tp)
    }
}

/** UWP Slider: 2 epx track (accent fill on the left), 8×24 thumb. */
class MetroSlider(context: Context, private val palette: Palette) : View(context) {
    var value = 0
        set(v) { field = v.coerceIn(0, 100); invalidate() }
    var onChange: ((Int) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.dpi(44f))

    override fun onDraw(c: Canvas) {
        val tw = context.dp(8f); val th = context.dp(24f); val cy = height / 2f
        val x = tw / 2 + (width - tw) * value / 100f
        p.color = palette.chromeHigh; c.drawRect(x, cy - context.dp(1f), width.toFloat(), cy + context.dp(1f), p)
        p.color = palette.accent; c.drawRect(0f, cy - context.dp(1f), x, cy + context.dp(1f), p)
        p.color = palette.fg
        c.drawRoundRect(x - tw / 2, cy - th / 2, x + tw / 2, cy + th / 2, context.dp(4f), context.dp(4f), p)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent.requestDisallowInterceptTouchEvent(true)
        val tw = context.dp(8f)
        value = (((e.x - tw / 2) / (width - tw)) * 100).toInt()
        if (e.actionMasked == MotionEvent.ACTION_UP) onChange?.invoke(value)
        return true
    }
}

/** UWP Button: 32 epx high, translucent fill, 12 epx side padding, 15 epx text. */
fun metroButton(c: Context, p: Palette, label: String, onClick: () -> Unit): TextView = TextView(c).apply {
    text = label; textSize = 15f; typeface = Metro.regular(c); setTextColor(p.fg)
    gravity = Gravity.CENTER
    setPadding(c.dpi(12f), 0, c.dpi(12f), 0)
    background = GradientDrawable().apply { setColor(if (p.dark) 0x33FFFFFF else 0x33000000) }
    minHeight = c.dpi(32f)
    setOnClickListener { onClick() }
}

/** 48 epx line-glyph button; [glyph] draws around (cx, cy) with u = 1 epx. Pressed state lights the background. */
@SuppressLint("ViewConstructor")
class GlyphButton(context: Context, var color: Int, private val glyph: (Canvas, Paint, Float, Float, Float) -> Unit) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }
    init {
        isClickable = true
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(0x33808080))
            addState(intArrayOf(), ColorDrawable(0))
        }
    }
    override fun onDraw(c: Canvas) {
        p.color = color; p.strokeWidth = context.dp(1.3f); p.style = Paint.Style.STROKE
        glyph(c, p, width / 2f, height / 2f, context.dp(1f))
    }
}

/** UWP Pivot header: 24 epx SemiLight items; the selected one at full strength, the rest at 60 %. Tap to switch. */
@SuppressLint("ViewConstructor")
class PivotHeader(context: Context, private val p: Palette, private val items: List<String>, var onSelect: (Int) -> Unit) : LinearLayout(context) {
    var selected = 0
        set(v) { field = v; for (i in 0 until childCount) (getChildAt(i) as TextView).setTextColor(if (i == v) p.fg else p.fgMedium) }

    init {
        setPadding(context.dpi(Metro.PAGE_MARGIN), context.dpi(4f), 0, context.dpi(8f))
        items.forEachIndexed { i, t ->
            addView(TextView(context).apply {
                text = t; textSize = 24f; typeface = Metro.semilight(context); isSingleLine = true
                setPadding(0, 0, context.dpi(20f), 0)
                setOnClickListener { selected = i; onSelect(i) }
            })
        }
        selected = 0
    }
}

/** Uppercase 15 epx SemiBold page title, as W10M apps show above their content. */
fun pageTitle(c: Context, p: Palette, text: String) = TextView(c).apply {
    this.text = text; textSize = 15f; typeface = Metro.semibold(c); setTextColor(p.fg); isAllCaps = true
    setPadding(c.dpi(Metro.PAGE_MARGIN), c.dpi(10f), c.dpi(Metro.PAGE_MARGIN), c.dpi(6f))
}

/** Bottom command bar with centred 72 epx glyph buttons. */
fun commandBar(c: Context, p: Palette, vararg buttons: View) = LinearLayout(c).apply {
    setBackgroundColor(p.chromeMedium); gravity = Gravity.CENTER_HORIZONTAL
    buttons.forEach { addView(it, LinearLayout.LayoutParams(c.dpi(72f), -1)) }
}

/** Formats milliseconds as m:ss, or h:mm:ss past an hour. */
fun clockText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
