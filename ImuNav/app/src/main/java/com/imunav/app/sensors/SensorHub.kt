package com.imunav.app.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.imunav.fusion.FusionEngine
import com.imunav.fusion.GpsFix
import com.imunav.fusion.Vec3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SensorAvailability(
    val gyroscope: String? = null,
    val accelerometer: String? = null,
    val magnetometer: String? = null,
    val barometer: String? = null,
)

data class GnssInfo(
    val permission: Boolean = false,
    val providerEnabled: Boolean = false,
    val satellitesUsed: Int = 0,
    val satellitesVisible: Int = 0,
    val lastFix: GpsFix? = null,
)

/**
 * Owns the Android sensor and GNSS subscriptions and forwards samples to the
 * [FusionEngine] on a dedicated high-priority thread.
 */
class SensorHub(private val context: Context, private val engine: FusionEngine) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val baro = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    val availability = SensorAvailability(
        gyroscope = gyro?.name,
        accelerometer = accel?.name,
        magnetometer = mag?.name,
        barometer = baro?.name,
    )

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var gpsRunning = false

    private val _gnss = MutableStateFlow(GnssInfo())
    val gnss: StateFlow<GnssInfo> = _gnss.asStateFlow()

    fun start() {
        if (thread != null) return
        val t = HandlerThread("imu-fusion", Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        // 100 Hz for the inertial sensors; magnetometer and barometer are slower by nature.
        gyro?.let { sensorManager.registerListener(this, it, IMU_PERIOD_US, h) }
        accel?.let { sensorManager.registerListener(this, it, IMU_PERIOD_US, h) }
        mag?.let { sensorManager.registerListener(this, it, 20_000, h) }
        baro?.let { sensorManager.registerListener(this, it, 100_000, h) }
        startGps()
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        stopGps()
        thread?.quitSafely()
        thread = null
        handler = null
    }

    fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun startGps() {
        val h = handler ?: return
        val permitted = hasLocationPermission()
        val enabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        _gnss.value = _gnss.value.copy(permission = permitted, providerEnabled = enabled)
        if (!permitted || gpsRunning) return
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, h.looper,
            )
            locationManager.registerGnssStatusCallback(gnssCallback, h)
            gpsRunning = true
        } catch (e: SecurityException) {
            _gnss.value = _gnss.value.copy(permission = false)
        } catch (e: IllegalArgumentException) {
            _gnss.value = _gnss.value.copy(providerEnabled = false)
        }
    }

    private fun stopGps() {
        if (!gpsRunning) return
        locationManager.removeUpdates(locationListener)
        locationManager.unregisterGnssStatusCallback(gnssCallback)
        gpsRunning = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        val t = event.timestamp
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> engine.onGyro(t, Vec3(v[0].toDouble(), v[1].toDouble(), v[2].toDouble()))
            Sensor.TYPE_ACCELEROMETER -> engine.onAccelerometer(t, Vec3(v[0].toDouble(), v[1].toDouble(), v[2].toDouble()))
            Sensor.TYPE_MAGNETIC_FIELD -> engine.onMagnetometer(t, Vec3(v[0].toDouble(), v[1].toDouble(), v[2].toDouble()))
            Sensor.TYPE_PRESSURE -> engine.onPressure(t, v[0].toDouble())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val fix = location.toFix()
            val declination = GeomagneticField(
                location.latitude.toFloat(),
                location.longitude.toFloat(),
                location.altitude.toFloat(),
                System.currentTimeMillis(),
            ).declination.toDouble()
            engine.onGps(fix, declination)
            _gnss.value = _gnss.value.copy(lastFix = fix, providerEnabled = true)
        }

        override fun onProviderEnabled(provider: String) {
            _gnss.value = _gnss.value.copy(providerEnabled = true)
        }

        override fun onProviderDisabled(provider: String) {
            _gnss.value = _gnss.value.copy(providerEnabled = false)
        }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            _gnss.value = _gnss.value.copy(satellitesUsed = used, satellitesVisible = status.satelliteCount)
        }
    }

    private fun Location.toFix() = GpsFix(
        timeNs = SystemClock.elapsedRealtimeNanos(),
        lat = latitude,
        lon = longitude,
        alt = altitude,
        horizontalAccuracy = accuracy.toDouble(),
        verticalAccuracy = if (hasVerticalAccuracy()) verticalAccuracyMeters.toDouble() else null,
        speed = if (hasSpeed()) speed.toDouble() else null,
        speedAccuracy = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond.toDouble() else null,
        bearingDeg = if (hasBearing()) bearing.toDouble() else null,
        bearingAccuracyDeg = if (hasBearingAccuracy()) bearingAccuracyDegrees.toDouble() else null,
    )

    companion object {
        const val IMU_PERIOD_US = 10_000
    }
}
