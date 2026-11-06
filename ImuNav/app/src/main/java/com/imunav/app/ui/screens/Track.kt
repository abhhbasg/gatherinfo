package com.imunav.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.imunav.app.Tracks
import com.imunav.app.ui.components.SectionCard
import com.imunav.app.ui.components.KeyValueRow
import com.imunav.app.ui.components.fmt
import com.imunav.app.ui.theme.Instrument
import com.imunav.fusion.NavState
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Picks a "nice" grid spacing (1/2/5 × 10ⁿ) close to [raw] metres. */
private fun niceStep(raw: Double): Double {
    val exp = floor(log10(raw))
    val base = 10.0.pow(exp)
    val f = raw / base
    return base * when {
        f < 1.5 -> 1.0
        f < 3.5 -> 2.0
        f < 7.5 -> 5.0
        else -> 10.0
    }
}

@Composable
fun TrackScreen(state: NavState, tracks: Tracks, contentPadding: PaddingValues, onClear: () -> Unit) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val tm = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary

    Column(
        Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, p, z, _ ->
                            zoom = (zoom * z).coerceIn(0.05f, 50f)
                            pan += p
                        }
                    },
            ) {
                val pts = tracks.fused + tracks.gps
                val cur = state.positionEnu
                var minE = cur.x; var maxE = cur.x; var minN = cur.y; var maxN = cur.y
                for (p in pts) {
                    if (p.e < minE) minE = p.e; if (p.e > maxE) maxE = p.e
                    if (p.n < minN) minN = p.n; if (p.n > maxN) maxN = p.n
                }
                val span = max(max(maxE - minE, maxN - minN), 20.0) * 1.2
                val scale = (size.minDimension / span * zoom).toFloat() // px per metre
                val cE = (minE + maxE) / 2; val cN = (minN + maxN) / 2
                fun toPx(e: Double, n: Double) = Offset(
                    center.x + ((e - cE) * scale).toFloat() + pan.x,
                    center.y - ((n - cN) * scale).toFloat() + pan.y,
                )

                // Metric grid.
                val step = niceStep(80.0 / scale)
                val stepPx = (step * scale).toFloat()
                val origin = toPx(0.0, 0.0)
                var x = origin.x % stepPx; if (x < 0) x += stepPx
                while (x < size.width) { drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f); x += stepPx }
                var y = origin.y % stepPx; if (y < 0) y += stepPx
                while (y < size.height) { drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f); y += stepPx }
                val scaleText = if (step >= 1000) "${(step / 1000).fmt(step < 2000)} km" else "${step.toInt().coerceAtLeast(1)} m"
                drawText(tm, "▏格网 $scaleText", Offset(16f, size.height - 48f), TextStyle(color = label, fontSize = 12.sp))
                drawText(tm, "N ↑", Offset(size.width - 64f, 20f), TextStyle(color = label, fontSize = 13.sp))

                // GPS fixes.
                for (p in tracks.gps) drawCircle(Instrument.Gps.copy(alpha = 0.8f), 4f, toPx(p.e, p.n))

                // Fused trajectory.
                if (tracks.fused.size > 1) {
                    val path = Path()
                    tracks.fused.forEachIndexed { i, p ->
                        val o = toPx(p.e, p.n)
                        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                    }
                    path.lineTo(toPx(cur.x, cur.y).x, toPx(cur.x, cur.y).y)
                    drawPath(path, primary, style = Stroke(6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }

                // Current position, 1σ uncertainty and heading arrow.
                val me = toPx(cur.x, cur.y)
                drawCircle(primary.copy(alpha = 0.15f), max((state.horizontalStd * scale).toFloat(), 10f), me)
                rotate(state.euler.heading.toFloat(), me) {
                    val arrow = Path().apply {
                        moveTo(me.x, me.y - 26f)
                        lineTo(me.x - 15f, me.y + 16f)
                        lineTo(me.x, me.y + 8f)
                        lineTo(me.x + 15f, me.y + 16f)
                        close()
                    }
                    drawPath(arrow, Color.White)
                    drawPath(arrow, primary, style = Stroke(3f))
                }
            }

            Row(Modifier.align(Alignment.TopStart).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick = { zoom = 1f; pan = Offset.Zero }) {
                    Icon(Icons.Rounded.CenterFocusStrong, "居中")
                }
                FilledTonalIconButton(onClick = onClear) { Icon(Icons.Rounded.DeleteSweep, "清除轨迹") }
            }
            Column(Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 12.dp), horizontalAlignment = Alignment.End) {
                Legend("融合轨迹", primary)
                Legend("GPS 定位点", Instrument.Gps)
            }
        }

        SectionCard(title = "轨迹统计") {
            KeyValueRow("里程", if (state.distance >= 1000) "${(state.distance / 1000).fmt(2)} km" else "${state.distance.fmt(0)} m")
            KeyValueRow("相对起点 (东 / 北)", "${state.positionEnu.x.fmt(1)} / ${state.positionEnu.y.fmt(1)} m")
            KeyValueRow("水平 1σ 不确定度", "±${state.horizontalStd.fmt(1)} m")
            KeyValueRow("GPS 定位点 / 融合点", "${tracks.gps.size} / ${tracks.fused.size}")
        }
        Spacer(Modifier.size(4.dp))
    }
}

private fun Double.fmt(fractional: Boolean) = if (fractional) fmt(1) else fmt(0)

@Composable
private fun Legend(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
