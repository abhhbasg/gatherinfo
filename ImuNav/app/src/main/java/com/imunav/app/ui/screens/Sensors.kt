package com.imunav.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.imunav.app.sensors.GnssInfo
import com.imunav.app.sensors.SensorAvailability
import com.imunav.app.ui.components.KeyValueRow
import com.imunav.app.ui.components.SectionCard
import com.imunav.app.ui.components.fmt
import com.imunav.fusion.FusionConfig
import com.imunav.fusion.NavState
import com.imunav.fusion.Vec3

private fun Vec3.fmt3(d: Int = 2) = "${x.fmt(d)}, ${y.fmt(d)}, ${z.fmt(d)}"

@Composable
fun SensorsScreen(
    state: NavState,
    gnss: GnssInfo,
    availability: SensorAvailability,
    settings: FusionConfig,
    contentPadding: PaddingValues,
    onSettings: ((FusionConfig) -> FusionConfig) -> Unit,
    onRealign: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "融合设置") {
            ToggleRow("GPS 校正", "有定位时修正位置、速度和加速度计零偏", settings.useGps) { v -> onSettings { it.copy(useGps = v) } }
            ToggleRow("地磁航向", "用磁力计修正航向漂移（车内/铁磁环境可关闭）", settings.useMagnetometer, availability.magnetometer != null) { v -> onSettings { it.copy(useMagnetometer = v) } }
            ToggleRow("零速修正 ZUPT", "静止时将速度约束为零，抑制惯性漂移", settings.useZupt) { v -> onSettings { it.copy(useZupt = v) } }
            ToggleRow("气压高度", "用气压计约束高度通道", settings.useBarometer, availability.barometer != null) { v -> onSettings { it.copy(useBarometer = v) } }
            ToggleRow("车载模式", "手机固定在车上时启用非完整约束（不侧滑、不跳跃），GPS 行驶中自动学习安装方向", settings.vehicleMode) { v -> onSettings { it.copy(vehicleMode = v) } }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRealign, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.RestartAlt, null)
                Text("  重新对准（请保持手机静止）")
            }
        }

        SectionCard(title = "估计器状态") {
            KeyValueRow("陀螺零偏 (°/s)", Vec3(Math.toDegrees(state.gyroBias.x), Math.toDegrees(state.gyroBias.y), Math.toDegrees(state.gyroBias.z)).fmt3(3))
            KeyValueRow("加计零偏 (m/s²)", state.accelBias.fmt3(3))
            KeyValueRow("四元数 w,x,y,z", "${state.quaternion.w.fmt(3)}, ${state.quaternion.x.fmt(3)}, ${state.quaternion.y.fmt(3)}, ${state.quaternion.z.fmt(3)}")
            KeyValueRow("速度 1σ", "±${state.velocityStd.fmt(2)} m/s")
            KeyValueRow("高度 1σ", "±${state.verticalStd.fmt(1)} m")
            KeyValueRow("磁偏角", "${state.declinationDeg.fmt(1)}°")
            KeyValueRow("重力校正 / 地磁校正", "${onOff(state.accelAiding)} / ${onOff(state.magAiding)}")
            if (settings.vehicleMode) KeyValueRow("车辆安装方向", if (state.vehicleAxisLearned) "已学习" else "待 GPS 行驶学习")
        }

        SectionCard(title = "原始传感器") {
            KeyValueRow("陀螺仪 (rad/s) · ${state.gyroHz.fmt(0)} Hz", state.rawGyro.fmt3(3))
            KeyValueRow("加速度计 (m/s²) · ${state.accelHz.fmt(0)} Hz", state.rawAccel.fmt3(2))
            KeyValueRow("磁力计 (µT) · ${state.magHz.fmt(0)} Hz", state.rawMag.fmt3(1))
            KeyValueRow("气压 (hPa)", state.pressureHPa?.fmt(2) ?: "—")
            KeyValueRow("气压高度 (m)", state.baroAltitude?.fmt(1) ?: "—")
        }

        SectionCard(title = "GNSS") {
            KeyValueRow("权限 / 开关", "${onOff(gnss.permission)} / ${onOff(gnss.providerEnabled)}")
            KeyValueRow("卫星 (使用/可见)", "${gnss.satellitesUsed} / ${gnss.satellitesVisible}")
            val f = gnss.lastFix
            if (f != null) {
                KeyValueRow("纬度, 经度", "%.6f, %.6f".format(f.lat, f.lon))
                KeyValueRow("水平精度", "±${f.horizontalAccuracy.fmt(1)} m")
                KeyValueRow("GPS 速度", f.speed?.let { "${(it * 3.6).fmt(1)} km/h" } ?: "—")
                KeyValueRow("GPS 航迹", f.bearingDeg?.let { "${it.fmt(0)}°" } ?: "—")
            }
            KeyValueRow("距上次定位", state.secondsSinceGps?.let { "${it.fmt(1)} s" } ?: "—")
        }

        SectionCard(title = "设备传感器") {
            KeyValueRow("陀螺仪", availability.gyroscope ?: "不可用")
            HorizontalDivider()
            KeyValueRow("加速度计", availability.accelerometer ?: "不可用")
            HorizontalDivider()
            KeyValueRow("磁力计", availability.magnetometer ?: "不可用")
            HorizontalDivider()
            KeyValueRow("气压计", availability.barometer ?: "不可用")
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun onOff(b: Boolean) = if (b) "开" else "关"

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                if (enabled) subtitle else "设备不支持",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
    }
}
