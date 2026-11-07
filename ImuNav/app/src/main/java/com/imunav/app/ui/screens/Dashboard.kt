package com.imunav.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Height
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.imunav.app.sensors.GnssInfo
import com.imunav.app.ui.components.AttitudeIndicator
import com.imunav.app.ui.components.CompassDial
import com.imunav.app.ui.components.MetricTile
import com.imunav.app.ui.components.SectionCard
import com.imunav.app.ui.components.SpeedGauge
import com.imunav.app.ui.components.StatusPill
import com.imunav.app.ui.components.fmt
import com.imunav.app.ui.theme.Instrument
import com.imunav.fusion.MotionModel
import com.imunav.fusion.NavMode
import com.imunav.fusion.NavState
import kotlin.math.sqrt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    state: NavState,
    gnss: GnssInfo,
    contentPadding: PaddingValues,
    onRequestLocation: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusRow(state, gnss)
        if (!gnss.permission) {
            SectionCard(title = "未授予定位权限") {
                Text(
                    "当前仅使用 IMU 惯性推算。授予定位权限后，可在有 GPS 时自动校正位置、速度与传感器零偏。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onRequestLocation) { Text("授予定位权限") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(Modifier.weight(1f), title = "速度") {
                SpeedGauge(state.speed * 3.6, if (state.speed * 3.6 > 60) 200.0 else 60.0, Modifier.fillMaxWidth())
            }
            SectionCard(Modifier.weight(1f), title = "航向") {
                CompassDial(
                    heading = state.euler.heading,
                    course = if (state.speed > 1.0) state.course else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SectionCard(title = "姿态") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AttitudeIndicator(state.euler.roll, state.euler.pitch, Modifier.weight(1.2f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigAngle("横滚 Roll", state.euler.roll)
                    BigAngle("俯仰 Pitch", state.euler.pitch)
                    BigAngle("航向 Yaw", state.euler.heading)
                }
            }
        }

        val tileMod = Modifier.fillMaxWidth(0.485f)
        val a = state.linearAccelWorld
        FlowRow(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 2,
            modifier = Modifier.fillMaxWidth(),
        ) {
            MetricTile("垂直速度", state.verticalSpeed.fmt(2), "m/s", tileMod, Icons.Rounded.SwapVert)
            MetricTile("线加速度", (sqrt(a.x * a.x + a.y * a.y + a.z * a.z) / 9.80665).fmt(2), "g", tileMod, Icons.Rounded.Vibration)
            MetricTile("相对高度", state.positionEnu.z.fmt(1), "m", tileMod, Icons.Rounded.Height)
            MetricTile("对地航迹", state.course.fmt(0), "°", tileMod, Icons.Rounded.NorthEast)
            MetricTile("位置精度", "±" + state.horizontalStd.fmt(1), "m", tileMod, Icons.Rounded.MyLocation, accent = accuracyColor(state.horizontalStd))
            MetricTile("里程", if (state.distance >= 1000) (state.distance / 1000).fmt(2) else state.distance.fmt(0), if (state.distance >= 1000) "km" else "m", tileMod, Icons.Rounded.Straighten)
            MetricTile("步数", state.steps.toString(), "步", tileMod, Icons.AutoMirrored.Rounded.DirectionsWalk)
            MetricTile("步频 / 步长", "${state.cadenceHz.fmt(1)}/${state.stepLength.fmt(2)}", "Hz/m", tileMod, Icons.Rounded.Timeline)
            MetricTile("东向速度", state.velocityEnu.x.fmt(2), "m/s", tileMod, Icons.Rounded.Speed)
            MetricTile("北向速度", state.velocityEnu.y.fmt(2), "m/s", tileMod, Icons.Rounded.Speed)
        }

        state.geo?.let { g ->
            SectionCard(title = "融合位置") {
                Text(
                    "%.6f°, %.6f°   海拔 %.1f m".format(g.lat, g.lon, g.alt),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusRow(state: NavState, gnss: GnssInfo) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state.mode) {
            NavMode.INITIALIZING -> StatusPill("初始化中 · 请保持静止", Instrument.Warn)
            NavMode.GPS_AIDED -> StatusPill("GPS 融合校正", Instrument.Good, icon = Icons.Rounded.SatelliteAlt)
            NavMode.INERTIAL -> {
                val since = state.secondsSinceGps
                StatusPill(
                    if (since == null) "纯惯性推算" else "惯性推算 · 失锁 ${since.toInt()} s",
                    Instrument.Warn,
                    icon = Icons.Rounded.Explore,
                )
            }
        }
        when {
            state.pdrActive && state.walking -> StatusPill("计步推算 PDR", Instrument.Good, icon = Icons.AutoMirrored.Rounded.DirectionsWalk)
            state.pdrActive -> StatusPill("停步 · 速度归零", MaterialTheme.colorScheme.secondary, icon = Icons.AutoMirrored.Rounded.DirectionsWalk)
            state.motionModel == MotionModel.VEHICLE -> StatusPill("车载约束", MaterialTheme.colorScheme.primary, icon = Icons.Rounded.DirectionsCar)
            state.motionModel == MotionModel.FREE && state.mode == NavMode.INERTIAL -> StatusPill("无约束 · 快速漂移", Instrument.Bad)
            else -> Unit
        }
        StatusPill(if (state.stationary) "静止 · ZUPT" else "运动中", if (state.stationary) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary, icon = if (state.stationary) Icons.Rounded.PauseCircle else Icons.Rounded.PlayCircle)
        if (gnss.permission) {
            StatusPill(
                "卫星 ${gnss.satellitesUsed}/${gnss.satellitesVisible}",
                if (gnss.satellitesUsed >= 4) Instrument.Good else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BigAngle(label: String, value: Double) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "${value.fmt(1)}°",
            style = com.imunav.app.ui.theme.NumberStyle.copy(fontSize = MaterialTheme.typography.headlineSmall.fontSize),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

fun accuracyColor(std: Double) = when {
    std < 5 -> Instrument.Good
    std < 20 -> Instrument.Warn
    else -> Instrument.Bad
}
