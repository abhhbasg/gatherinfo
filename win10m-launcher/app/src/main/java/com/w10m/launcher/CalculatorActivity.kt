package com.w10m.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout

/**
 * Calculator in the W10M standard-mode layout: mode title, expression line and large right-aligned
 * result, then a 6×4 flat key grid (function keys on top, digits darker and bolder, operators on the right).
 */
class CalculatorActivity : Activity() {

    private lateinit var p: Palette
    private lateinit var shell: Shell
    private val engine = CalcEngine()
    private lateinit var screen: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this); p = prefs.palette()
        shell = Shell(this, prefs, p)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.chromeMedium) }
        screen = Display(this)
        root.addView(screen, LinearLayout.LayoutParams(-1, 0, 2f))
        root.addView(Pad(this), LinearLayout.LayoutParams(-1, 0, 5f))
        shell.setContent(root)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (!shell.handleBack()) @Suppress("DEPRECATION") super.onBackPressed() }

    private inner class Display(c: Context) : View(c) {
        private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.sp(20f); typeface = Metro.semibold(c) }
        private val expr = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.sp(15f); typeface = Metro.regular(c); textAlign = Paint.Align.RIGHT }
        private val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Metro.light(c); textAlign = Paint.Align.RIGHT }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = c.dp(1.3f) }

        override fun onDraw(cv: Canvas) {
            val m = dp(Metro.PAGE_MARGIN); val u = dp(1f)
            // Hamburger + mode name.
            line.color = p.fg
            for (i in -1..1) cv.drawLine(m, dp(24f) + i * 5 * u, m + 16 * u, dp(24f) + i * 5 * u, line)
            title.color = p.fg
            cv.drawText(getString(R.string.calc_standard), m + dp(32f), dp(24f) - (title.ascent() + title.descent()) / 2, title)
            // Expression above the result.
            expr.color = p.fgMedium
            cv.drawText(engine.expression, width - m, height - dp(76f), expr)
            val text = if (engine.error) getString(R.string.calc_error) else CalcEngine.format(engine.entry)
            var size = sp(if (engine.error) 28f else 54f)
            big.textSize = size
            val room = width - 2 * m
            // Long results shrink to fit, as on W10M.
            val tw = big.measureText(text)
            if (tw > room) { size = (size * room / tw).coerceAtLeast(sp(16f)); big.textSize = size }
            big.color = p.fg
            val t = TextUtils.ellipsize(text, big, room, TextUtils.TruncateAt.START)
            cv.drawText(t, 0, t.length, width - m, height - dp(16f), big)
        }
    }

    private inner class Pad(c: Context) : View(c) {
        private val keys = listOf(
            "%", "√", "x²", "¹/x",
            "CE", "C", "⌫", "÷",
            "7", "8", "9", "×",
            "4", "5", "6", "−",
            "1", "2", "3", "+",
            "±", "0", ".", "=",
        )
        private val gap = c.dp(2f)
        private val fill = Paint()
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = c.dp(1.3f) }
        private var pressedKey = -1

        private fun isDigit(k: String) = k.length == 1 && (k[0].isDigit() || k == ".")
        private fun kw() = (width - 3 * gap) / 4
        private fun kh() = (height - 6 * gap) / 6

        override fun onDraw(cv: Canvas) {
            val w = kw(); val h = kh()
            keys.forEachIndexed { i, k ->
                val x = (i % 4) * (w + gap); val y = gap + (i / 4) * (h + gap)
                val digit = isDigit(k) || k == "±"
                fill.color = when {
                    i == pressedKey -> if (digit) p.chromeHigh else p.accent
                    digit -> if (p.dark) 0xFF060606.toInt() else 0xFFFAFAFA.toInt()
                    else -> if (p.dark) 0xFF131313.toInt() else 0xFFF0F0F0.toInt()
                }
                cv.drawRect(x, y, x + w, y + h, fill)
                val fg = if (i == pressedKey && !digit) Color.WHITE else p.fg
                val cx = x + w / 2; val cy = y + h / 2
                if (k == "⌫") {
                    // Backspace glyph: pentagon pointing left with an x.
                    val u = dp(1f); lp.color = fg
                    val path = android.graphics.Path()
                    path.moveTo(cx - 11 * u, cy); path.lineTo(cx - 4 * u, cy - 7 * u); path.lineTo(cx + 11 * u, cy - 7 * u)
                    path.lineTo(cx + 11 * u, cy + 7 * u); path.lineTo(cx - 4 * u, cy + 7 * u); path.close()
                    path.moveTo(cx, cy - 3 * u); path.lineTo(cx + 6 * u, cy + 3 * u)
                    path.moveTo(cx + 6 * u, cy - 3 * u); path.lineTo(cx, cy + 3 * u)
                    cv.drawPath(path, lp)
                } else {
                    tp.color = fg
                    tp.typeface = if (digit) Metro.semibold(context) else Metro.regular(context)
                    tp.textSize = sp(if (digit) 22f else if (k.length > 1) 16f else 22f)
                    cv.drawText(k, cx, cy - (tp.ascent() + tp.descent()) / 2, tp)
                }
            }
        }

        private fun keyAt(x: Float, y: Float) =
            ((y - gap) / (kh() + gap)).toInt().coerceIn(0, 5) * 4 + (x / (kw() + gap)).toInt().coerceIn(0, 3)

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pressedKey = keyAt(e.x, e.y); performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); invalidate() }
                MotionEvent.ACTION_UP -> {
                    val k = pressedKey; pressedKey = -1; invalidate()
                    if (k == keyAt(e.x, e.y)) press(keys[k])
                }
                MotionEvent.ACTION_CANCEL -> { pressedKey = -1; invalidate() }
            }
            return true
        }

        private fun press(k: String) {
            when (k) {
                "%" -> engine.percent(); "√" -> engine.sqrt(); "x²" -> engine.square(); "¹/x" -> engine.reciprocal()
                "CE" -> engine.clearEntry(); "C" -> engine.clear(); "⌫" -> engine.backspace()
                "÷", "×", "−", "+" -> engine.operator(k[0])
                "±" -> engine.negate(); "." -> engine.dot(); "=" -> engine.equals()
                else -> engine.digit(k[0])
            }
            screen.invalidate()
        }
    }
}
