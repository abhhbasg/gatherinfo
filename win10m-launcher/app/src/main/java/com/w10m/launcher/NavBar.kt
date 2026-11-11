package com.w10m.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * The Windows Phone on-screen navigation bar: Back, Start, Search. 48 epx tall, black, white line glyphs;
 * the pressed key lights up in the accent colour. Extends under the system gesture area via [bottomInset].
 */
@SuppressLint("ViewConstructor")
class NavBar(context: Context, private var accent: Int) : View(context) {

    var onBack: (() -> Unit)? = null
    var onStart: (() -> Unit)? = null
    var onSearch: (() -> Unit)? = null
    /** Long press on Back: the task switcher. */
    var onBackLong: (() -> Unit)? = null
    private var longFired = false
    private val longPress = Runnable {
        if (pressedKey == 0) { longFired = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onBackLong?.invoke() }
    }
    var bottomInset = 0
        set(v) { if (field != v) { field = v; requestLayout() } }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE; strokeJoin = Paint.Join.MITER
    }
    private val path = Path()
    private var pressedKey = -1

    init { setBackgroundColor(Color.BLACK) }

    fun setAccent(a: Int) { accent = a; invalidate() }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), context.dpi(HEIGHT) + bottomInset)

    override fun onDraw(c: Canvas) {
        val barH = context.dp(HEIGHT)
        val third = width / 3f
        val u = context.dp(1f)
        p.strokeWidth = context.dp(1.4f)
        for (k in 0..2) {
            val cx = third * k + third / 2; val cy = barH / 2
            p.color = if (k == pressedKey) accent else Color.WHITE
            path.reset()
            when (k) {
                0 -> { // Back: left arrow
                    path.moveTo(cx + 9 * u, cy); path.lineTo(cx - 9 * u, cy)
                    path.moveTo(cx - 2 * u, cy - 7 * u); path.lineTo(cx - 9 * u, cy); path.lineTo(cx - 2 * u, cy + 7 * u)
                }
                1 -> { // Start: four-pane window drawn in perspective (narrow on the left, wide on the right), filled
                    fun pane(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
                        path.moveTo(cx + x0 * u, cy + y0 * u); path.lineTo(cx + x1 * u, cy + y1 * u)
                        path.lineTo(cx + x2 * u, cy + y2 * u); path.lineTo(cx + x3 * u, cy + y3 * u); path.close()
                    }
                    pane(-9f, -6.3f, -1.2f, -7.47f, -1.2f, -0.7f, -9f, -0.7f)
                    pane(0.2f, -7.68f, 9f, -9f, 9f, -0.7f, 0.2f, -0.7f)
                    pane(-9f, 0.7f, -1.2f, 0.7f, -1.2f, 7.47f, -9f, 6.3f)
                    pane(0.2f, 0.7f, 9f, 0.7f, 9f, 9f, 0.2f, 7.68f)
                }
                else -> { // Search: magnifier, handle to the bottom-left
                    path.addCircle(cx + 2.5f * u, cy - 2.5f * u, 6.5f * u, Path.Direction.CW)
                    path.moveTo(cx - 2.2f * u, cy + 2.2f * u); path.lineTo(cx - 9 * u, cy + 9 * u)
                }
            }
            p.style = if (k == 1) Paint.Style.FILL else Paint.Style.STROKE
            c.drawPath(path, p)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val k = (e.x / (width / 3f)).toInt().coerceIn(0, 2)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedKey = k; longFired = false; performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); invalidate()
                if (k == 0) postDelayed(longPress, 500)
            }
            MotionEvent.ACTION_MOVE -> if (k != pressedKey || e.y < 0) { pressedKey = -1; invalidate() }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val hit = if (longFired) -1 else pressedKey; pressedKey = -1; invalidate()
                when (hit) { 0 -> onBack?.invoke(); 1 -> onStart?.invoke(); 2 -> onSearch?.invoke() }
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); pressedKey = -1; invalidate() }
        }
        return true
    }

    companion object {
        const val HEIGHT = 48f
        const val EXTRA_SEARCH = "w10m.search"

        /** Default wiring for secondary screens: Back = back, Start = Start screen, Search = app search. */
        fun wire(bar: NavBar, a: Activity) {
            @Suppress("DEPRECATION")
            bar.onBack = { a.onBackPressed() }
            bar.onBackLong = { KeysService.recents(a) }
            bar.onStart = { a.startActivity(Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            bar.onSearch = {
                a.startActivity(Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_SEARCH, true))
            }
        }
    }
}
