package com.imunav.fusion

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GPS-denied drift scenarios driven by a synthetic but physically consistent IMU:
 * walking gait (vertical bounce, surge, sway), a phone held at a reading angle,
 * gyro/accel biases and noise. Each test reports the error so regressions are visible.
 */
class DriftTest {

    private val g = AttitudeFilter.GRAVITY
    private val magWorld = Vec3(0.0, 25.0, -40.0)

    private class Truth {
        var pos = Vec3.ZERO
        var headingRad = 0.0 // clockwise from north
        var distance = 0.0
    }

    private inner class Sim(
        val engine: FusionEngine,
        seed: Long = 7,
        val gyroBias: Vec3 = Vec3(0.004, -0.003, 0.006),
        val accelBias: Vec3 = Vec3(0.04, -0.05, 0.06),
        val pitchDeg: Double = 35.0,
        /** True stride length (m); the default Weinberg K is deliberately ~8 % off. */
        val stepFreq: Double = 1.8,
        val walkSpeed: Double = 1.4,
        /** Phone pointing direction minus walking direction (deg). */
        val phoneYawDeg: Double = 0.0,
    ) {
        val rnd = Random(seed)
        val truth = Truth()
        var t = 0L
        var phase = 0.0
        val ltp = LocalTangentPlane(31.2, 121.5, 5.0)

        private fun n(s: Double) = Vec3(rnd.nextGaussian() * s, rnd.nextGaussian() * s, rnd.nextGaussian() * s)

        fun attitude(): Quaternion =
            Quaternion.fromAxisAngle(Vec3.UP, -(truth.headingRad + Math.toRadians(phoneYawDeg))) *
                Quaternion.fromAxisAngle(Vec3(1.0, 0.0, 0.0), Math.toRadians(pitchDeg))

        /** Advance [seconds]; walking at [turnRateDeg]/s (clockwise) if [walking]. */
        fun run(seconds: Double, walking: Boolean, turnRateDeg: Double = 0.0, vibration: Double = 0.0, gpsEvery: Int = 0) {
            val dt = 0.01
            val steps = (seconds / dt).toInt()
            for (i in 0 until steps) {
                val psiDot = if (walking) Math.toRadians(turnRateDeg) else 0.0
                truth.headingRad += psiDot * dt
                var aWorld = Vec3.ZERO
                var speed = 0.0
                if (walking) {
                    phase += 2 * PI * stepFreq * dt
                    speed = walkSpeed + 0.12 * sin(phase)
                    val surge = 0.12 * 2 * PI * stepFreq * cos(phase)
                    val vertical = 2.3 * sin(phase) + 0.6 * sin(2 * phase + 0.7)
                    val sway = 0.35 * sin(phase / 2)
                    val fwd = Vec3(sin(truth.headingRad), cos(truth.headingRad), 0.0)
                    val right = Vec3(cos(truth.headingRad), -sin(truth.headingRad), 0.0)
                    // Centripetal term for turns.
                    val centripetal = right * (speed * psiDot)
                    aWorld = fwd * surge + right * sway + centripetal + Vec3(0.0, 0.0, vertical)
                    truth.pos += fwd * (speed * dt)
                    truth.distance += speed * dt
                }
                val q = attitude()
                val r = q.toRotationMatrix()
                val omegaBody = r.tTimes(Vec3(0.0, 0.0, -psiDot))
                t += 10_000_000L
                engine.onGyro(t, omegaBody + gyroBias + n(0.003))
                engine.onMagnetometer(t, r.tTimes(magWorld) + n(0.4))
                engine.onAccelerometer(t, r.tTimes(aWorld + Vec3(0.0, 0.0, g)) + accelBias + n(0.03 + vibration))
                if (gpsEvery > 0 && i % gpsEvery == 0) {
                    val p = ltp.toGeodetic(truth.pos)
                    val gp = ltp.toGeodetic(truth.pos + Vec3(rnd.nextGaussian() * 2, rnd.nextGaussian() * 2, 0.0))
                    engine.onGps(
                        GpsFix(
                            t, gp.lat, gp.lon, p.alt, 3.0, 5.0,
                            speed = if (walking) walkSpeed else 0.0, speedAccuracy = 0.3,
                            bearingDeg = if (walking) Math.toDegrees(truth.headingRad).mod(360.0) else null,
                            bearingAccuracyDeg = 8.0,
                        ),
                        0.0,
                    )
                }
            }
        }

        /** Horizontal error of the estimate relative to the truth (frame anchored at start / first fix). */
        fun error(origin: Vec3 = Vec3.ZERO): Double {
            val s = engine.snapshot(t)
            val e = s.geo?.let { ltp.toEnu(it.lat, it.lon, it.alt) } ?: (s.positionEnu + origin)
            return Vec3(e.x - truth.pos.x, e.y - truth.pos.y, 0.0).norm()
        }
    }

    private fun walkScenario(model: MotionModel, useMag: Boolean = true): Pair<Double, Double> {
        val e = FusionEngine(FusionConfig(useBarometer = false, motionModel = model, useMagnetometer = useMag))
        val sim = Sim(e)
        sim.run(10.0, walking = false)
        sim.run(60.0, walking = true)
        sim.run(15.0, walking = false)
        sim.run(30.0, walking = true, turnRateDeg = 3.0) // gentle 90° curve
        sim.run(30.0, walking = true)
        return sim.error() to sim.truth.distance
    }

    @Test
    fun pedestrianDeadReckoningBeatsFreeInertial() {
        val (freeErr, dist) = walkScenario(MotionModel.FREE)
        val (autoErr, _) = walkScenario(MotionModel.AUTO)
        val (pedErr, _) = walkScenario(MotionModel.PEDESTRIAN)
        println("walk %.0f m: FREE err %.1f m, AUTO err %.1f m (%.1f %%), PEDESTRIAN err %.1f m".format(
            dist, freeErr, autoErr, 100 * autoErr / dist, pedErr))
        // Uncalibrated: dominated by the generic stride model (~14 % short for this walker).
        assertTrue(autoErr < 0.15 * dist, "AUTO error $autoErr over $dist m")
        assertTrue(pedErr < 0.15 * dist, "PEDESTRIAN error $pedErr over $dist m")
        assertTrue(freeErr > 5 * autoErr, "FREE $freeErr vs AUTO $autoErr")
    }

    @Test
    fun zaruHoldsHeadingWithoutMagnetometer() {
        val e = FusionEngine(FusionConfig(useMagnetometer = false, useBarometer = false))
        val sim = Sim(e, gyroBias = Vec3(0.002, -0.003, 0.008)) // 0.46°/s yaw bias
        sim.run(20.0, walking = false)
        val bias = e.snapshot(sim.t).gyroBias
        assertTrue((bias - sim.gyroBias).norm() < 0.0015, "bias estimate $bias")
        val h0 = e.snapshot(sim.t).euler.heading
        sim.run(120.0, walking = false)
        val h1 = e.snapshot(sim.t).euler.heading
        val drift = abs(Math.toDegrees(wrapPi(Math.toRadians(h1 - h0))))
        println("heading drift over 120 s at rest, no mag: %.2f°".format(drift))
        // Uncorrected the yaw bias alone would be ~55°.
        assertTrue(drift < 1.0, "drift $drift°")
    }

    @Test
    fun gpsCalibratesStepLengthAndOffsetForLaterOutage() {
        val e = FusionEngine(FusionConfig(useBarometer = false))
        // Long stride (0.86 m) the default model underestimates, phone yawed 20° from walking direction.
        val sim = Sim(e, stepFreq = 1.75, walkSpeed = 1.5, phoneYawDeg = 20.0)
        sim.run(10.0, walking = false, gpsEvery = 100)
        sim.run(90.0, walking = true, gpsEvery = 100)
        val cal = e.calibration()
        assertEquals(-20.0, Math.toDegrees(cal.walkHeadingOffset), 4.0)
        val startErr = sim.error()
        val d0 = sim.truth.distance
        sim.run(90.0, walking = true, turnRateDeg = 2.0) // GPS lost
        val err = sim.error()
        val walked = sim.truth.distance - d0
        println("after GPS calibration K=%.3f: outage %.0f m, error %.1f m (%.1f %%), start err %.1f".format(
            cal.stepK, walked, err, 100 * err / walked, startErr))
        assertTrue(err < 0.06 * walked + startErr, "error $err over $walked m")
    }

    @Test
    fun knownDistanceCalibrationWithoutGps() {
        val e = FusionEngine(FusionConfig(useBarometer = false, useGps = false))
        val sim = Sim(e)
        sim.run(10.0, walking = false)
        e.startStepCalibration()
        val d0 = sim.truth.distance
        sim.run(40.0, walking = true)
        val k = e.finishStepCalibration(sim.truth.distance - d0)!!
        // Then walk a fresh route.
        val startPos = sim.truth.pos
        val est0 = e.snapshot(sim.t).positionEnu
        val d1 = sim.truth.distance
        sim.run(30.0, walking = false)
        sim.run(90.0, walking = true, turnRateDeg = -2.0)
        val est = e.snapshot(sim.t).positionEnu - est0
        val tru = sim.truth.pos - startPos
        val err = Vec3(est.x - tru.x, est.y - tru.y, 0.0).norm()
        val walked = sim.truth.distance - d1
        println("known-distance calibration K=%.3f: walk %.0f m, error %.1f m (%.1f %%)".format(k, walked, err, 100 * err / walked))
        assertTrue(err < 0.05 * walked, "error $err over $walked")
    }

    @Test
    fun calibrationSurvivesRestart() {
        val a = FusionEngine()
        val sim = Sim(a)
        sim.run(20.0, walking = false)
        val cal = a.calibration().copy(stepK = 0.55, walkHeadingOffset = 0.3)
        val b = FusionEngine()
        b.setCalibration(cal)
        val sim2 = Sim(b)
        sim2.run(1.0, walking = false)
        val restored = b.calibration()
        assertTrue((restored.gyroBias - cal.gyroBias).norm() < 5e-4, "gyro ${restored.gyroBias} vs ${cal.gyroBias}")
        assertEquals(0.55, restored.stepK, 1e-9)
        assertEquals(0.3, restored.walkHeadingOffset, 1e-9)
    }

    @Test
    fun vehicleStopWithEngineVibrationIsDetected() {
        fun run(model: MotionModel): Double {
            val e = FusionEngine(FusionConfig(useBarometer = false, motionModel = model))
            val sim = Sim(e, accelBias = Vec3(0.0, 0.0, 0.0), pitchDeg = 80.0)
            sim.run(5.0, walking = false)
            // Idling at a red light: strong vibration, accelerometer bias shifts with temperature.
            val drifting = Sim(e, accelBias = Vec3(0.06, 0.05, 0.08), pitchDeg = 80.0).also { it.t = sim.t }
            drifting.run(60.0, walking = false, vibration = 0.15)
            return e.snapshot(drifting.t).positionEnu.norm()
        }
        val free = run(MotionModel.FREE)
        val vehicle = run(MotionModel.VEHICLE)
        println("60 s idling: FREE drift %.1f m, VEHICLE drift %.1f m".format(free, vehicle))
        assertTrue(vehicle < 2.0, "vehicle drift $vehicle")
        assertTrue(free > 10 * vehicle)
    }
}
