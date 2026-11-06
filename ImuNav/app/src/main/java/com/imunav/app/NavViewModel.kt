package com.imunav.app

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imunav.app.sensors.GnssInfo
import com.imunav.app.sensors.SensorHub
import com.imunav.fusion.FusionConfig
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

    private val _settings = MutableStateFlow(FusionConfig())
    val settings: StateFlow<FusionConfig> = _settings.asStateFlow()

    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state.asStateFlow()

    private val _tracks = MutableStateFlow(Tracks())
    val tracks: StateFlow<Tracks> = _tracks.asStateFlow()

    init {
        hub.start()
        viewModelScope.launch {
            while (isActive) {
                _state.value = engine.snapshot(SystemClock.elapsedRealtimeNanos())
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
    }

    fun onLocationPermissionResult() = hub.startGps()

    fun updateSettings(transform: (FusionConfig) -> FusionConfig) {
        _settings.update(transform)
        engine.config = _settings.value
    }

    /** Re-runs initial alignment; keep the phone still for ~1 s afterwards. */
    fun realign() = engine.reset()

    fun clearTrack() = engine.clearTrack()

    override fun onCleared() {
        hub.stop()
    }
}
