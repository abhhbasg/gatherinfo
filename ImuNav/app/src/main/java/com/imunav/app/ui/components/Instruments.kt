package com.imunav.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.imunav.app.ui.theme.Instrument
import com.imunav.app.ui.theme.NumberStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private fun DrawScope.centeredText(
    tm: TextMeasurer, text: String, center: Offset, style: TextStyle,
) {
    val layout = tm.measure(text, style)
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

/** Artificial horizon: sky/ground ball rotated by roll and shifted by pitch. */
@Composable
fun AttitudeIndicator(roll: Double, pitch: Double, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val ring = MaterialTheme.colorScheme.outline
    Canvas(modifier.aspectRatio(1f)) {
        val r = min(size.width, size.height) / 2f
        val c = center
        val pxPerDeg = r / 45f
        val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r * 0.94f)) }
        clipPath(circle) {
            rotate(-roll.toFloat(), c) {
                translate(top = (pitch * pxPerDeg).toFloat()) {
                    val big = r * 4
                    drawRect(
                        Brush.verticalGradient(listOf(Instrument.SkyTop, Instrument.Sky), c.y - big, c.y),
                        topLeft = Offset(c.x - big, c.y - big), size = Size(big * 2, big),
                    )
                    drawRect(
                        Brush.verticalGradient(listOf(Instrument.Ground, Instrument.GroundBottom), c.y, c.y + big),
                        topLeft = Offset(c.x - big, c.y), size = Size(big * 2, big),
                    )
                    drawLine(Color.White, Offset(c.x - big, c.y), Offset(c.x + big, c.y), strokeWidth = 2.5f)
                    // Pitch ladder.
                    val ladder = TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    for (deg in -80..80 step 10) {
                        if (deg == 0) continue
                        val y = c.y - deg * pxPerDeg
                        val half = if (deg % 20 == 0) r * 0.28f else r * 0.15f
                        drawLine(Color.White.copy(alpha = 0.85f), Offset(c.x - half, y), Offset(c.x + half, y), strokeWidth = 2f)
                        if (deg % 20 == 0) {
                            centeredText(tm, abs(deg).toString(), Offset(c.x - half - 16.sp.toPx(), y), ladder)
                            centeredText(tm, abs(deg).toString(), Offset(c.x + half + 16.sp.toPx(), y), ladder)
                        }
                    }
                }
            }
        }
        // Roll scale (fixed) and pointer (rotates with roll).
        val scaleR = r * 0.94f
        for (a in listOf(-60, -45, -30, -20, -10, 0, 10, 20, 30, 45, 60)) {
            val rad = Math.toRadians(a.toDouble() - 90)
            val len = if (a % 30 == 0) r * 0.1f else r * 0.06f
            val o = Offset(c.x + (scaleR * cos(rad)).toFloat(), c.y + (scaleR * sin(rad)).toFloat())
            val i = Offset(c.x + ((scaleR - len) * cos(rad)).toFloat(), c.y + ((scaleR - len) * sin(rad)).toFloat())
            drawLine(Color.White, o, i, strokeWidth = 2f)
        }
        rotate(-roll.toFloat(), c) {
            val tip = Offset(c.x, c.y - scaleR + r * 0.11f)
            val p = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(tip.x - r * 0.05f, tip.y + r * 0.08f)
                lineTo(tip.x + r * 0.05f, tip.y + r * 0.08f)
                close()
            }
            drawPath(p, Instrument.Warn)
        }
        // Fixed aircraft symbol.
        val w = r * 0.42f
        val sym = Path().apply {
            moveTo(c.x - w, c.y); lineTo(c.x - w * 0.35f, c.y)
            lineTo(c.x, c.y + r * 0.1f); lineTo(c.x + w * 0.35f, c.y); lineTo(c.x + w, c.y)
        }
        drawPath(sym, Color.Black.copy(alpha = 0.4f), style = Stroke(9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(sym, Instrument.Warn, style = Stroke(6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(Instrument.Warn, 5f, c)
        drawCircle(ring, r * 0.97f, c, style = Stroke(r * 0.06f))
    }
}

/** Compass rose rotating under a fixed lubber line. */
@Composable
fun CompassDial(heading: Double, course: Double?, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val fg = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val face = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.aspectRatio(1f)) {
        val r = min(size.width, size.height) / 2f
        val c = center
        drawCircle(face, r, c)
        rotate(-heading.toFloat(), c) {
            for (deg in 0 until 360 step 5) {
                val rad = Math.toRadians(deg.toDouble() - 90)
                val len = when {
                    deg % 90 == 0 -> r * 0.16f
                    deg % 30 == 0 -> r * 0.12f
                    deg % 10 == 0 -> r * 0.08f
                    else -> r * 0.04f
                }
                val o = Offset(c.x + (r * 0.95f * cos(rad)).toFloat(), c.y + (r * 0.95f * sin(rad)).toFloat())
                val i = Offset(c.x + ((r * 0.95f - len) * cos(rad)).toFloat(), c.y + ((r * 0.95f - len) * sin(rad)).toFloat())
                drawLine(if (deg % 30 == 0) fg else dim, o, i, strokeWidth = if (deg % 30 == 0) 3f else 1.5f)
            }
            val labels = mapOf(0 to "N", 90 to "E", 180 to "S", 270 to "W")
            for (deg in 0 until 360 step 30) {
                val rad = Math.toRadians(deg.toDouble() - 90)
                val p = Offset(c.x + (r * 0.64f * cos(rad)).toFloat(), c.y + (r * 0.64f * sin(rad)).toFloat())
                val label = labels[deg] ?: (deg / 10).toString()
                val style = TextStyle(
                    color = if (deg == 0) Instrument.Bad else if (labels.containsKey(deg)) fg else dim,
                    fontSize = if (labels.containsKey(deg)) 16.sp else 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                rotate(deg.toFloat(), p) { centeredText(tm, label, p, style) }
            }
            // Course-over-ground bug.
            if (course != null) {
                rotate(course.toFloat(), c) {
                    drawCircle(Instrument.Good, r * 0.045f, Offset(c.x, c.y - r * 0.83f))
                }
            }
        }
        val lubber = Path().apply {
            moveTo(c.x, c.y - r * 0.98f)
            lineTo(c.x - r * 0.07f, c.y - r * 0.80f)
            lineTo(c.x + r * 0.07f, c.y - r * 0.80f)
            close()
        }
        drawPath(lubber, Instrument.Warn)
        centeredText(
            tm, "${heading.roundToInt() % 360}°", c,
            NumberStyle.copy(color = fg, fontSize = 22.sp, fontWeight = FontWeight.Bold),
        )
    }
}

/** 240° arc speed gauge with the value in the middle. */
@Composable
fun SpeedGauge(speedKmh: Double, maxKmh: Double, modifier: Modifier = Modifier, label: String = "km/h") {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val primary = MaterialTheme.colorScheme.primary
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = size.minDimension * 0.08f
            val inset = stroke / 2 + 4f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val tl = Offset(inset, inset)
            drawArc(track, 150f, 240f, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val frac = (speedKmh / maxKmh).coerceIn(0.0, 1.0).toFloat()
            if (frac > 0.002f) {
                drawArc(
                    Brush.sweepGradient(listOf(primary, Instrument.Good, Instrument.Warn, primary)),
                    150f, 240f * frac, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (speedKmh < 10) "%.1f".format(speedKmh) else speedKmh.roundToInt().toString(),
                style = NumberStyle.copy(fontSize = 44.sp, fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
