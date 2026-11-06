package com.imunav.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.imunav.app.sensors.GnssInfo
import com.imunav.app.sensors.SensorAvailability
import com.imunav.app.ui.screens.DashboardScreen
import com.imunav.app.ui.screens.SensorsScreen
import com.imunav.app.ui.screens.TrackScreen
import com.imunav.app.ui.theme.ImuNavTheme
import com.imunav.fusion.Euler
import com.imunav.fusion.FusionConfig
import com.imunav.fusion.GeoPoint
import com.imunav.fusion.GpsFix
import com.imunav.fusion.NavMode
import com.imunav.fusion.NavState
import com.imunav.fusion.TrackPoint
import com.imunav.fusion.Vec3
import org.junit.Rule
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Renders the main screens with representative data (run: ./gradlew :app:recordPaparazziDebug). */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 0.1)

    private val state = NavState(
        mode = NavMode.INERTIAL,
        euler = Euler(heading = 47.0, pitch = 6.5, roll = -12.0),
        velocityEnu = Vec3(9.1, 8.4, -0.12),
        speed = 12.4,
        verticalSpeed = -0.12,
        course = 47.3,
        linearAccelWorld = Vec3(0.4, 0.8, 0.1),
        positionEnu = Vec3(182.0, 140.0, 3.4),
        geo = GeoPoint(31.230416, 121.473701, 14.2),
        horizontalStd = 6.8,
        distance = 1534.0,
        secondsSinceGps = 23.0,
        gyroHz = 100.0, accelHz = 100.0, magHz = 50.0,
        rawAccel = Vec3(0.12, 1.1, 9.75), rawGyro = Vec3(0.001, -0.002, 0.05), rawMag = Vec3(3.1, 24.0, -38.0),
        pressureHPa = 1011.42, baroAltitude = 15.5,
    )
    private val gnss = GnssInfo(permission = true, providerEnabled = true, satellitesUsed = 0, satellitesVisible = 9,
        lastFix = GpsFix(0, 31.2303, 121.4736, 12.0, 4.2, speed = 12.1, bearingDeg = 46.0))

    @Test
    fun dashboard() = paparazzi.snapshot {
        ImuNavTheme(darkTheme = true) {
            Surface(color = MaterialTheme.colorScheme.background) {
                DashboardScreen(state, gnss, PaddingValues(), onRequestLocation = {})
            }
        }
    }

    @Test
    fun track() = paparazzi.snapshot {
        val fused = (0..120).map { i -> val t = i / 120.0 * 4.0; TrackPoint(60 * t * cos(t / 2) , 55 * t * sin(t / 1.6)) }
        val gps = fused.filterIndexed { i, _ -> i % 4 == 0 && i < 80 }.map { TrackPoint(it.e + 2.5, it.n - 1.5) }
        val s = state.copy(positionEnu = Vec3(fused.last().e, fused.last().n, 0.0))
        ImuNavTheme(darkTheme = true) {
            Surface(color = MaterialTheme.colorScheme.background) {
                TrackScreen(s, Tracks(fused, gps), PaddingValues(), onClear = {})
            }
        }
    }

    @Test
    fun sensors() = paparazzi.snapshot {
        ImuNavTheme(darkTheme = true) {
            Surface(color = MaterialTheme.colorScheme.background) {
                SensorsScreen(state, gnss, SensorAvailability("BMI260 Gyro", "BMI260 Accel", "AK09918 Mag", null),
                    FusionConfig(vehicleMode = true), PaddingValues(), onSettings = {}, onRealign = {})
            }
        }
    }
}
