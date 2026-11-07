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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
import com.imunav.fusion.MotionModel
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
    stepCalibration: Pair<Int, Double>? = null,
    onStartStepCalibration: () -> Unit = {},
    onFinishStepCalibration: (Double) -> Double? = { null },
    onCancelStepCalibration: () -> Unit = {},
    onClearCalibration: () -> Unit = {},
) {
    var showCalDialog by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "运动模型（无 GPS 时抑制漂移的关键）") {
            MotionModelSelector(settings.motionModel) { m -> onSettings { it.copy(motionModel = m) } }
            Spacer(Modifier.height(8.dp))
            Text(
                motionModelHelp(settings.motionModel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "标定（自动保存，下次启动沿用）") {
            KeyValueRow("步长系数 K", state.stepK.fmt(3))
            KeyValueRow("最近步长 / 步频", "${state.stepLength.fmt(2)} m / ${state.cadenceHz.fmt(2)} Hz")
            KeyValueRow("行走方向偏差", "${state.walkHeadingOffsetDeg.fmt(1)}°")
            KeyValueRow("车辆安装方向", if (state.vehicleAxisLearned) "已学习" else "未学习（有 GPS 行驶时自动学习）")
            Text(
                "有 GPS 行走时会自动校准步长和方向偏差；没有 GPS 时可走一段已知距离手动标定。静止时自动校准陀螺零偏（ZARU）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onStartStepCalibration(); showCalDialog = true }, modifier = Modifier.weight(1f)) {
                    Text("步长标定")
                }
                OutlinedButton(onClick = onClearCalibration, modifier = Modifier.weight(1f)) { Text("清除标定") }
            }
        }

        SectionCard(title = "融合设置") {
            ToggleRow("GPS 校正", "有定位时修正位置、速度和加速度计零偏", settings.useGps) { v -> onSettings { it.copy(useGps = v) } }
            ToggleRow("地磁航向", "用磁力计修正航向漂移（车内/铁磁环境可关闭）", settings.useMagnetometer, availability.magnetometer != null) { v -> onSettings { it.copy(useMagnetometer = v) } }
            ToggleRow("零速修正 ZUPT", "静止时将速度约束为零，抑制惯性漂移", settings.useZupt) { v -> onSettings { it.copy(useZupt = v) } }
            ToggleRow("气压高度", "用气压计约束高度通道", settings.useBarometer, availability.barometer != null) { v -> onSettings { it.copy(useBarometer = v) } }
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
            KeyValueRow("零角速度修正 ZARU", onOff(state.zaruActive))
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

    if (showCalDialog) {
        StepCalibrationDialog(
            progress = stepCalibration,
            onFinish = onFinishStepCalibration,
            onDismiss = { onCancelStepCalibration(); showCalDialog = false },
            onDone = { showCalDialog = false },
        )
    }
}

private fun motionModelHelp(m: MotionModel) = when (m) {
    MotionModel.AUTO -> "自动：检测到走路时用计步推算（PDR）约束速度，停步后短时归零；未走路时为常规惯导。"
    MotionModel.PEDESTRIAN -> "步行：位置只随步伐前进，没有步伐就不移动。手持/口袋均可，误差约为行走距离的几个百分点。"
    MotionModel.VEHICLE -> "车载：手机需固定在车上。约束车辆不侧滑、不跳跃，等红灯时可识别发动机振动下的静止。"
    MotionModel.FREE -> "自由惯导：纯加速度二次积分，没有任何约束，无 GPS 时几十秒内就会严重漂移，仅用于测试。"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MotionModelSelector(selected: MotionModel, onSelect: (MotionModel) -> Unit) {
    val options = listOf(MotionModel.AUTO to "自动", MotionModel.PEDESTRIAN to "步行", MotionModel.VEHICLE to "车载", MotionModel.FREE to "自由")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (m, label) ->
            SegmentedButton(
                selected = selected == m,
                onClick = { onSelect(m) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label) }
        }
    }
}

@Composable
private fun StepCalibrationDialog(
    progress: Pair<Int, Double>?,
    onFinish: (Double) -> Double?,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("步长标定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("沿直线正常行走一段已知距离（建议 30–100 m，例如跑道或量好的走廊），然后输入实际距离。")
                val (n, est) = progress ?: (0 to 0.0)
                Text("已走 $n 步 · 当前模型估计 ${est.fmt(1)} m", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text("实际距离 (m)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val m = text.toDoubleOrNull()
                if (m == null || m <= 0) { error = "请输入有效距离"; return@TextButton }
                if (onFinish(m) == null) error = "步数太少（至少 10 步）" else onDone()
            }) { Text("完成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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
