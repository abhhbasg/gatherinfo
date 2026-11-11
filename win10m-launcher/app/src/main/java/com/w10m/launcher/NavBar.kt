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
                1 -> { // Start: four hollow panes (original glyph, not the Windows logo)
                    val g = 1.5f * u; val s = 7.5f * u
                    path.addRect(cx - g - s, cy - g - s, cx - g, cy - g, Path.Direction.CW)
                    path.addRect(cx + g, cy - g - s, cx + g + s, cy - g, Path.Direction.CW)
                    path.addRect(cx - g - s, cy + g, cx - g, cy + g + s, Path.Direction.CW)
                    path.addRect(cx + g, cy + g, cx + g + s, cy + g + s, Path.Direction.CW)
                }
                else -> { // Search: magnifier, handle to the bottom-left
                    path.addCircle(cx + 2.5f * u, cy - 2.5f * u, 6.5f * u, Path.Direction.CW)
                    path.moveTo(cx - 2.2f * u, cy + 2.2f * u); path.lineTo(cx - 9 * u, cy + 9 * u)
                }
            }
            c.drawPath(path, p)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val k = (e.x / (width / 3f)).toInt().coerceIn(0, 2)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressedKey = k; performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); invalidate() }
            MotionEvent.ACTION_MOVE -> if (k != pressedKey || e.y < 0) { pressedKey = -1; invalidate() }
            MotionEvent.ACTION_UP -> {
                val hit = pressedKey; pressedKey = -1; invalidate()
                when (hit) { 0 -> onBack?.invoke(); 1 -> onStart?.invoke(); 2 -> onSearch?.invoke() }
            }
            MotionEvent.ACTION_CANCEL -> { pressedKey = -1; invalidate() }
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
            bar.onStart = { a.startActivity(Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            bar.onSearch = {
                a.startActivity(Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_SEARCH, true))
            }
        }
    }
}
