package com.imunav.app

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imunav.app.sensors.GnssInfo
import com.imunav.app.sensors.SensorHub
import com.imunav.fusion.Calibration
import com.imunav.fusion.FusionConfig
import com.imunav.fusion.MotionModel
import com.imunav.fusion.Vec3
import com.imunav.fusion.FusionEngine
import com.imunav.fusion.NavState
import com.imunav.fusion.TrackPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class Tracks(val fused: List<TrackPoint> = emptyList(), val gps: List<TrackPoint> = emptyList())

class NavViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = FusionEngine()
    private val hub = SensorHub(app, engine)

    val availability = hub.availability
    val gnss: StateFlow<GnssInfo> = hub.gnss

    private val prefs = app.getSharedPreferences("imunav", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<FusionConfig> = _settings.asStateFlow()

    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state.asStateFlow()

    private val _tracks = MutableStateFlow(Tracks())
    val tracks: StateFlow<Tracks> = _tracks.asStateFlow()

    /** Steps / estimated metres since a known-distance calibration started, null if idle. */
    private val _stepCal = MutableStateFlow<Pair<Int, Double>?>(null)
    val stepCalibration: StateFlow<Pair<Int, Double>?> = _stepCal.asStateFlow()

    init {
        engine.config = _settings.value
        loadCalibration()?.let(engine::setCalibration)
        hub.start()
        viewModelScope.launch {
            while (isActive) {
                _state.value = engine.snapshot(SystemClock.elapsedRealtimeNanos())
                _stepCal.value = engine.stepCalibrationProgress()
                delay(33)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                val (fused, gps) = engine.track()
                _tracks.value = Tracks(fused, gps)
                delay(500)
            }
        }
        // Learned biases / stride / offsets survive app restarts.
        viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                saveCalibration()
            }
        }
    }

    fun startStepCalibration() = engine.startStepCalibration()

    fun finishStepCalibration(meters: Double): Double? = engine.finishStepCalibration(meters).also { saveCalibration() }

    fun cancelStepCalibration() = engine.cancelStepCalibration()

    fun clearCalibration() {
        engine.clearCalibration()
        prefs.edit().remove(KEY_CAL).apply()
    }

    private fun loadSettings(): FusionConfig {
        val d = FusionConfig()
        return FusionConfig(
            useMagnetometer = prefs.getBoolean("mag", d.useMagnetometer),
            useGps = prefs.getBoolean("gps", d.useGps),
            useZupt = prefs.getBoolean("zupt", d.useZupt),
            useBarometer = prefs.getBoolean("baro", d.useBarometer),
            motionModel = runCatching { MotionModel.valueOf(prefs.getString("model", null)!!) }.getOrDefault(d.motionModel),
        )
    }

    private fun saveSettings(c: FusionConfig) {
        prefs.edit()
            .putBoolean("mag", c.useMagnetometer)
            .putBoolean("gps", c.useGps)
            .putBoolean("zupt", c.useZupt)
            .putBoolean("baro", c.useBarometer)
            .putString("model", c.motionModel.name)
            .apply()
    }

    private fun saveCalibration() {
        val c = engine.calibration()
        val f = c.vehicleForwardAxis
        val parts = listOf(c.gyroBias.x, c.gyroBias.y, c.gyroBias.z, c.stepK, c.walkHeadingOffset) +
            (if (f != null) listOf(f.x, f.y, f.z) else emptyList())
        prefs.edit().putString(KEY_CAL, parts.joinToString(",")).apply()
    }

    private fun loadCalibration(): Calibration? {
        val v = prefs.getString(KEY_CAL, null)?.split(",")?.mapNotNull { it.toDoubleOrNull() } ?: return null
        if (v.size < 5) return null
        return Calibration(
            gyroBias = Vec3(v[0], v[1], v[2]),
            stepK = v[3],
            walkHeadingOffset = v[4],
            vehicleForwardAxis = if (v.size >= 8) Vec3(v[5], v[6], v[7]) else null,
        )
    }

    fun onLocationPermissionResult() = hub.startGps()

    fun updateSettings(transform: (FusionConfig) -> FusionConfig) {
        _settings.update(transform)
        engine.config = _settings.value
        saveSettings(_settings.value)
    }

    /** Re-runs initial alignment; keep the phone still for ~1 s afterwards. */
    fun realign() = engine.reset()

    fun clearTrack() = engine.clearTrack()

    override fun onCleared() {
        saveCalibration()
        hub.stop()
    }

    private companion object {
        const val KEY_CAL = "calibration"
    }
}
