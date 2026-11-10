package com.metrowin.keyboard.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

enum class Icon { SHIFT, SHIFT_LOCK, BACKSPACE, ENTER, SEARCH, SEND, ARROW_RIGHT, CHECK, EMOJI, GLOBE, MIC, CLIPBOARD, GIF, SETTINGS, GRID, CLOSE, PIN, PLUS, TRASH, IMAGE, ELLIPSIS, SWAP, HELP, TRANSLATE, COPY, CHEVRON_LEFT, CHEVRON_RIGHT, CHEVRON_DOWN, CHEVRON_UP }

/** The icons of the keyboard, drawn with paths in a -1..1 square so they are crisp at any size and take the colour of the theme. */
object Icons {
    private val path = Path()
    private val rect = RectF()

    /** Draws [icon] centred at ([cx], [cy]), [size] px across, with [paint] (its colour is used; style and stroke are set here). */
    fun draw(c: Canvas, icon: Icon, cx: Float, cy: Float, size: Float, paint: Paint) {
        val s = size / 2f
        val oldStyle = paint.style
        val oldStroke = paint.strokeWidth
        val oldCap = paint.strokeCap
        val oldJoin = paint.strokeJoin
        c.save()
        c.translate(cx, cy)
        c.scale(s, s)
        paint.strokeWidth = (size * 0.09f).coerceAtLeast(1.5f) / s
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        when (icon) {
            Icon.SHIFT, Icon.SHIFT_LOCK -> {
                path.reset()
                path.moveTo(0f, -0.9f); path.lineTo(0.9f, 0.05f); path.lineTo(0.45f, 0.05f); path.lineTo(0.45f, 0.75f)
                path.lineTo(-0.45f, 0.75f); path.lineTo(-0.45f, 0.05f); path.lineTo(-0.9f, 0.05f); path.close()
                paint.style = if (icon == Icon.SHIFT_LOCK) Paint.Style.FILL else Paint.Style.STROKE
                c.drawPath(path, paint)
                if (icon == Icon.SHIFT_LOCK) {
                    paint.style = Paint.Style.FILL
                    c.drawRect(-0.45f, 0.88f, 0.45f, 1.0f, paint)
                }
            }
            Icon.BACKSPACE -> {
                path.reset()
                path.moveTo(-0.35f, -0.7f); path.lineTo(0.95f, -0.7f); path.lineTo(0.95f, 0.7f); path.lineTo(-0.35f, 0.7f); path.lineTo(-0.95f, 0f); path.close()
                paint.style = Paint.Style.STROKE
                c.drawPath(path, paint)
                c.drawLine(0.0f, -0.28f, 0.58f, 0.28f, paint)
                c.drawLine(0.58f, -0.28f, 0.0f, 0.28f, paint)
            }
            Icon.ENTER -> {
                paint.style = Paint.Style.STROKE
                path.reset(); path.moveTo(0.85f, -0.7f); path.lineTo(0.85f, 0.15f); path.lineTo(-0.7f, 0.15f)
                c.drawPath(path, paint)
                path.reset(); path.moveTo(-0.15f, -0.4f); path.lineTo(-0.75f, 0.15f); path.lineTo(-0.15f, 0.7f)
                c.drawPath(path, paint)
            }
            Icon.SEARCH -> {
                paint.style = Paint.Style.STROKE
                c.drawCircle(-0.15f, -0.15f, 0.58f, paint)
                c.drawLine(0.27f, 0.27f, 0.85f, 0.85f, paint)
            }
            Icon.SEND -> {
                paint.style = Paint.Style.FILL
                path.reset(); path.moveTo(-0.9f, -0.8f); path.lineTo(0.95f, 0f); path.lineTo(-0.9f, 0.8f); path.lineTo(-0.5f, 0f); path.close()
                c.drawPath(path, paint)
            }
            Icon.ARROW_RIGHT -> {
                paint.style = Paint.Style.STROKE
                c.drawLine(-0.85f, 0f, 0.8f, 0f, paint)
                path.reset(); path.moveTo(0.2f, -0.6f); path.lineTo(0.85f, 0f); path.lineTo(0.2f, 0.6f)
                c.drawPath(path, paint)
            }
            Icon.CHECK -> {
                paint.style = Paint.Style.STROKE
                path.reset(); path.moveTo(-0.8f, 0.05f); path.lineTo(-0.25f, 0.6f); path.lineTo(0.85f, -0.6f)
                c.drawPath(path, paint)
            }
            Icon.EMOJI -> {
                paint.style = Paint.Style.STROKE
                c.drawCircle(0f, 0f, 0.85f, paint)
                paint.style = Paint.Style.FILL
                c.drawCircle(-0.3f, -0.25f, 0.1f, paint); c.drawCircle(0.3f, -0.25f, 0.1f, paint)
                paint.style = Paint.Style.STROKE
                rect.set(-0.5f, -0.25f, 0.5f, 0.5f)
                c.drawArc(rect, 25f, 130f, false, paint)
            }
            Icon.GLOBE -> {
                paint.style = Paint.Style.STROKE
                c.drawCircle(0f, 0f, 0.85f, paint)
                rect.set(-0.4f, -0.85f, 0.4f, 0.85f); c.drawOval(rect, paint)
                c.drawLine(-0.85f, 0f, 0.85f, 0f, paint)
                c.drawLine(-0.72f, -0.42f, 0.72f, -0.42f, paint); c.drawLine(-0.72f, 0.42f, 0.72f, 0.42f, paint)
            }
            Icon.MIC -> {
                paint.style = Paint.Style.STROKE
                rect.set(-0.3f, -0.9f, 0.3f, 0.2f); c.drawRoundRect(rect, 0.3f, 0.3f, paint)
                rect.set(-0.62f, -0.45f, 0.62f, 0.55f); c.drawArc(rect, 0f, 180f, false, paint)
                c.drawLine(0f, 0.55f, 0f, 0.92f, paint)
            }
            Icon.CLIPBOARD -> {
                paint.style = Paint.Style.STROKE
                rect.set(-0.65f, -0.7f, 0.65f, 0.92f); c.drawRoundRect(rect, 0.15f, 0.15f, paint)
                paint.style = Paint.Style.FILL
                rect.set(-0.32f, -0.92f, 0.32f, -0.5f); c.drawRoundRect(rect, 0.12f, 0.12f, paint)
                paint.style = Paint.Style.STROKE
                c.drawLine(-0.33f, 0.0f, 0.33f, 0.0f, paint); c.drawLine(-0.33f, 0.4f, 0.2f, 0.4f, paint)
            }
            Icon.GIF -> {
                paint.style = Paint.Style.STROKE
                rect.set(-0.95f, -0.6f, 0.95f, 0.6f); c.drawRoundRect(rect, 0.25f, 0.25f, paint)
                paint.style = Paint.Style.FILL
                val old = paint.textSize; val align = paint.textAlign
                paint.textAlign = Paint.Align.CENTER; paint.textSize = 0.62f; paint.isFakeBoldText = true
                c.drawText("GIF", 0f, 0.22f, paint)
                paint.isFakeBoldText = false; paint.textSize = old; paint.textAlign = align
            }
            Icon.SETTINGS -> {
                paint.style = Paint.Style.STROKE
                c.drawCircle(0f, 0f, 0.3f, paint)
                c.drawCircle(0f, 0f, 0.6f, paint)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    c.drawLine((Math.cos(a) * 0.6).toFloat(), (Math.sin(a) * 0.6).toFloat(), (Math.cos(a) * 0.9).toFloat(), (Math.sin(a) * 0.9).toFloat(), paint)
                }
            }
            Icon.GRID -> {
                paint.style = Paint.Style.FILL
                for (x in listOf(-0.5f, 0.5f)) for (y in listOf(-0.5f, 0.5f)) { rect.set(x - 0.3f, y - 0.3f, x + 0.3f, y + 0.3f); c.drawRoundRect(rect, 0.12f, 0.12f, paint) }
            }
            Icon.CLOSE -> {
                paint.style = Paint.Style.STROKE
                c.drawLine(-0.65f, -0.65f, 0.65f, 0.65f, paint); c.drawLine(0.65f, -0.65f, -0.65f, 0.65f, paint)
            }
            Icon.PIN -> {
                paint.style = Paint.Style.STROKE
                path.reset(); path.moveTo(-0.45f, -0.85f); path.lineTo(0.45f, -0.85f); path.lineTo(0.3f, -0.1f); path.lineTo(0.7f, 0.25f)
                path.lineTo(-0.7f, 0.25f); path.lineTo(-0.3f, -0.1f); path.close()
                c.drawPath(path, paint); c.drawLine(0f, 0.25f, 0f, 0.95f, paint)
            }
            Icon.PLUS -> {
                paint.style = Paint.Style.STROKE
                c.drawLine(-0.7f, 0f, 0.7f, 0f, paint); c.drawLine(0f, -0.7f, 0f, 0.7f, paint)
            }
            Icon.TRASH -> {
                paint.style = Paint.Style.STROKE
                c.drawLine(-0.8f, -0.55f, 0.8f, -0.55f, paint); c.drawLine(-0.25f, -0.55f, -0.25f, -0.85f, paint); c.drawLine(0.25f, -0.55f, 0.25f, -0.85f, paint)
                c.drawLine(-0.25f, -0.85f, 0.25f, -0.85f, paint)
                path.reset(); path.moveTo(-0.6f, -0.55f); path.lineTo(-0.5f, 0.9f); path.lineTo(0.5f, 0.9f); path.lineTo(0.6f, -0.55f)
                c.drawPath(path, paint)
            }
            Icon.ELLIPSIS -> {
                // The three dots of the Windows Phone app bar.
                paint.style = Paint.Style.FILL
                for (x in listOf(-0.62f, 0f, 0.62f)) c.drawCircle(x, 0f, 0.19f, paint)
            }
            Icon.SWAP -> {
                paint.style = Paint.Style.STROKE
                c.drawLine(-0.8f, -0.35f, 0.8f, -0.35f, paint)
                path.reset(); path.moveTo(0.3f, -0.8f); path.lineTo(0.8f, -0.35f); path.lineTo(0.3f, 0.1f); c.drawPath(path, paint)
                c.drawLine(0.8f, 0.4f, -0.8f, 0.4f, paint)
                path.reset(); path.moveTo(-0.3f, -0.05f); path.lineTo(-0.8f, 0.4f); path.lineTo(-0.3f, 0.85f); c.drawPath(path, paint)
            }
            Icon.HELP -> {
                paint.style = Paint.Style.STROKE
                c.drawCircle(0f, 0f, 0.85f, paint)
                paint.style = Paint.Style.FILL
                val old = paint.textSize; val align = paint.textAlign
                paint.textAlign = Paint.Align.CENTER; paint.textSize = 1.3f; paint.isFakeBoldText = true
                c.drawText("?", 0f, 0.45f, paint)
                paint.isFakeBoldText = false; paint.textSize = old; paint.textAlign = align
            }
            Icon.TRANSLATE -> {
                // Two speech bubbles, one with an A and one with a second letter: "from one language to another".
                paint.style = Paint.Style.STROKE
                rect.set(-0.95f, -0.85f, 0.25f, 0.25f); c.drawRoundRect(rect, 0.15f, 0.15f, paint)
                rect.set(-0.25f, -0.25f, 0.95f, 0.85f); c.drawRoundRect(rect, 0.15f, 0.15f, paint)
                paint.style = Paint.Style.FILL
                val old = paint.textSize; val align = paint.textAlign
                paint.textAlign = Paint.Align.CENTER; paint.textSize = 0.8f; paint.isFakeBoldText = true
                c.drawText("A", -0.35f, -0.05f, paint); c.drawText("B", 0.35f, 0.65f, paint)
                paint.isFakeBoldText = false; paint.textSize = old; paint.textAlign = align
            }
            Icon.COPY -> {
                paint.style = Paint.Style.STROKE
                rect.set(-0.35f, -0.35f, 0.85f, 0.85f); c.drawRoundRect(rect, 0.12f, 0.12f, paint)
                path.reset(); path.moveTo(-0.55f, 0.35f); path.lineTo(-0.85f, 0.35f); path.lineTo(-0.85f, -0.85f); path.lineTo(0.35f, -0.85f); path.lineTo(0.35f, -0.55f)
                c.drawPath(path, paint)
            }
            Icon.CHEVRON_LEFT -> {
                paint.style = Paint.Style.STROKE
                path.reset(); path.moveTo(0.35f, -0.75f); path.lineTo(-0.4f, 0f); path.lineTo(0.35f, 0.75f); c.drawPath(path, paint)
            }
            Icon.CHEVRON_RIGHT -> {
                paint.style = Paint.Style.STROKE
                path.reset(); path.moveTo(-0.35f, -0.75f); path.lineTo(0.4f, 0f); path.lineTo(-0.35f, 0.75f); c.drawPath(path, paint)
            }
            Icon.CHEVRON_DOWN -> {
                paint.style = Paint.Style.STROKE
                path.reset(); path.moveTo(-0.75f, -0.35f); path.lineTo(0f, 0.4f); path.lineTo(0.75f, -0.35f); c.drawPath(path, paint)
            }
            Icon.CHEVRON_UP -> {
                paint.style = Paint.Style.STROKE
                path.reset(); path.moveTo(-0.75f, 0.35f); path.lineTo(0f, -0.4f); path.lineTo(0.75f, 0.35f); c.drawPath(path, paint)
            }
            Icon.IMAGE -> {
                paint.style = Paint.Style.STROKE
                rect.set(-0.9f, -0.7f, 0.9f, 0.7f); c.drawRoundRect(rect, 0.15f, 0.15f, paint)
                path.reset(); path.moveTo(-0.9f, 0.5f); path.lineTo(-0.3f, -0.1f); path.lineTo(0.1f, 0.3f); path.lineTo(0.4f, 0f); path.lineTo(0.9f, 0.5f)
                c.drawPath(path, paint)
                c.drawCircle(0.45f, -0.35f, 0.12f, paint)
            }
        }
        c.restore()
        paint.style = oldStyle; paint.strokeWidth = oldStroke; paint.strokeCap = oldCap; paint.strokeJoin = oldJoin
    }
}
