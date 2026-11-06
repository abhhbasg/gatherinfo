package com.imunav.fusion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FusionTest {

    private val g = AttitudeFilter.GRAVITY
    // Earth field in ENU (µT): north + down component (northern hemisphere).
    private val magWorld = Vec3(0.0, 25.0, -40.0)

    private fun angleDiff(a: Double, b: Double) = abs(Math.toDegrees(wrapPi(Math.toRadians(a - b))))

    /** Sensor readings a perfect phone at attitude [q] (body→ENU) would produce. */
    private fun sensorsAt(q: Quaternion, accelWorld: Vec3 = Vec3.ZERO): Pair<Vec3, Vec3> {
        val r = q.toRotationMatrix()
        val accel = r.tTimes(accelWorld + Vec3(0.0, 0.0, g))
        val mag = r.tTimes(magWorld)
        return accel to mag
    }

    @Test
    fun eulerConventions() {
        assertEquals(0.0, Euler.from(Quaternion.IDENTITY).heading, 1e-9)
        // Rotate +90° about Up (counter-clockwise seen from above): phone top points West.
        val west = Euler.from(Quaternion.fromAxisAngle(Vec3.UP, PI / 2))
        assertEquals(270.0, west.heading, 1e-6)
        val pitched = Euler.from(Quaternion.fromAxisAngle(Vec3(1.0, 0.0, 0.0), Math.toRadians(20.0)))
        assertEquals(20.0, pitched.pitch, 1e-6)
        val rolled = Euler.from(Quaternion.fromAxisAngle(Vec3(0.0, 1.0, 0.0), Math.toRadians(30.0)))
        assertEquals(30.0, rolled.roll, 1e-6)
    }

    @Test
    fun initialAlignmentFromAccelAndMag() {
        val truth = Quaternion.fromAxisAngle(Vec3.UP, Math.toRadians(-60.0)) *
            Quaternion.fromAxisAngle(Vec3(1.0, 0.0, 0.0), Math.toRadians(15.0)) *
            Quaternion.fromAxisAngle(Vec3(0.0, 1.0, 0.0), Math.toRadians(-25.0))
        val (a, m) = sensorsAt(truth)
        val e = Euler.from(Quaternion.fromAccelMag(a, m))
        val t = Euler.from(truth)
        assertEquals(t.heading, e.heading, 1e-6)
        assertEquals(t.pitch, e.pitch, 1e-6)
        assertEquals(t.roll, e.roll, 1e-6)
        assertEquals(60.0, t.heading, 1e-6)
    }

    @Test
    fun filterConvergesFromWrongAttitudeAndLearnsGyroBias() {
        val f = AttitudeFilter()
        val truth = Quaternion.fromAxisAngle(Vec3.UP, Math.toRadians(-120.0)) *
            Quaternion.fromAxisAngle(Vec3(1.0, 0.0, 0.0), Math.toRadians(10.0))
        val (a, m) = sensorsAt(truth)
        // Start badly wrong: level and facing north.
        f.initialize(Vec3(0.0, 0.0, g), Vec3(0.0, 25.0, -40.0))
        val bias = Vec3(0.01, -0.02, 0.015)
        val dt = 0.01
        repeat(120_00) { f.update(bias, a, m, dt) }
        val e = Euler.from(f.q)
        val t = Euler.from(truth)
        assertTrue(angleDiff(e.heading, t.heading) < 1.0, "heading ${e.heading} vs ${t.heading}")
        assertEquals(t.pitch, e.pitch, 1.0)
        assertEquals(t.roll, e.roll, 1.0)
        val be = f.gyroBias - bias
        assertTrue(be.norm() < 0.003, "bias error $be")
    }

    @Test
    fun gyroOnlyTracksRotation() {
        val f = AttitudeFilter()
        f.initialize(Vec3(0.0, 0.0, g), null)
        // 90°/s about the up axis for 1 s, no aiding.
        repeat(100) { f.update(Vec3(0.0, 0.0, PI / 2), null, null, 0.01) }
        assertEquals(270.0, Euler.from(f.q).heading, 0.01)
    }

    @Test
    fun linearAccelerationDoesNotTiltHorizon() {
        val f = AttitudeFilter()
        f.initialize(Vec3(0.0, 0.0, g), magWorld)
        // 0.6 g forward acceleration for 5 s: |a| is outside the gate, so tilt must stay ~0.
        val (a, m) = sensorsAt(Quaternion.IDENTITY, Vec3(0.0, 6.0, 0.0))
        repeat(500) { f.update(Vec3.ZERO, a, m, 0.01) }
        assertTrue(abs(Euler.from(f.q).pitch) < 0.5)
    }

    private val rnd = java.util.Random(42)
    private fun noise(s: Double) = Vec3(rnd.nextGaussian() * s, rnd.nextGaussian() * s, rnd.nextGaussian() * s)

    /** Feeds 100 Hz IMU data; [vibration] is accelerometer noise (m/s²), as felt in a vehicle. */
    private fun feedImu(
        e: FusionEngine, t0: Long, seconds: Double, q: Quaternion, accelWorld: Vec3,
        bias: Vec3 = Vec3.ZERO, vibration: Double = 0.02,
    ): Long {
        val (a, m) = sensorsAt(q, accelWorld)
        var t = t0
        val steps = (seconds * 100).toInt()
        repeat(steps) {
            t += 10_000_000L
            e.onGyro(t, noise(0.002))
            e.onMagnetometer(t, m + noise(0.3))
            e.onAccelerometer(t, a + bias + noise(vibration))
        }
        return t
    }

    @Test
    fun deadReckoningIntegratesVelocity() {
        val e = FusionEngine(FusionConfig(useZupt = false, useBarometer = false))
        var t = feedImu(e, 0, 3.0, Quaternion.IDENTITY, Vec3.ZERO)
        // Accelerate north at 1 m/s² for 5 s with road vibration, no GPS.
        t = feedImu(e, t, 5.0, Quaternion.IDENTITY, Vec3(0.0, 1.0, 0.0), vibration = 0.25)
        val s = e.snapshot(t)
        assertEquals(NavMode.INERTIAL, s.mode)
        assertEquals(5.0, s.velocityEnu.y, 0.5)
        assertEquals(12.5, s.positionEnu.y, 1.5)
        assertTrue(angleDiff(s.course, 0.0) < 3.0)
        assertTrue(abs(s.euler.pitch) < 1.0, "pitch ${s.euler.pitch}")
    }

    @Test
    fun zuptBoundsDriftWithBiasedAccelerometer() {
        // A vertical bias appearing after start-up can't be hidden as tilt or gravity.
        val bias = Vec3(0.0, 0.0, 0.05)
        val withZupt = FusionEngine(FusionConfig(useBarometer = false))
        val noZupt = FusionEngine(FusionConfig(useBarometer = false, useZupt = false))
        val t1 = feedImu(withZupt, feedImu(withZupt, 0, 1.0, Quaternion.IDENTITY, Vec3.ZERO), 60.0, Quaternion.IDENTITY, Vec3.ZERO, bias)
        val t2 = feedImu(noZupt, feedImu(noZupt, 0, 1.0, Quaternion.IDENTITY, Vec3.ZERO), 60.0, Quaternion.IDENTITY, Vec3.ZERO, bias)
        val a = withZupt.snapshot(t1)
        val b = noZupt.snapshot(t2)
        assertTrue(a.stationary)
        assertTrue(a.velocityEnu.norm() < 0.05, "velocity with ZUPT ${a.velocityEnu}")
        assertTrue(a.positionEnu.norm() < 1.0, "pos with ZUPT ${a.positionEnu}")
        assertTrue(b.positionEnu.norm() > 30.0, "pos without ZUPT ${b.positionEnu}")
        assertEquals(0.05, a.accelBias.z, 0.01)
    }

    @Test
    fun gpsCorrectsPositionAndEstimatesAccelBias() {
        val bias = Vec3(0.0, 0.0, 0.06)
        val e = FusionEngine(FusionConfig(useZupt = false, useBarometer = false))
        val ltp = LocalTangentPlane(31.0, 121.0, 10.0)
        var t = feedImu(e, 0, 1.0, Quaternion.IDENTITY, Vec3.ZERO)
        // 120 s with GPS at 1 Hz (no ZUPT, so only GPS can calibrate the bias).
        repeat(120) {
            t = feedImu(e, t, 1.0, Quaternion.IDENTITY, Vec3.ZERO, bias)
            val p = ltp.toGeodetic(Vec3.ZERO)
            e.onGps(GpsFix(t, p.lat, p.lon, p.alt, 3.0, 4.0, 0.0, 0.3, null, null), 0.0)
        }
        val s = e.snapshot(t)
        assertEquals(NavMode.GPS_AIDED, s.mode)
        assertTrue(s.positionEnu.norm() < 3.0, "pos ${s.positionEnu}")
        assertEquals(bias.z, s.accelBias.z, 0.015)
        // GPS lost: the calibrated bias keeps the drift small for 10 s.
        t = feedImu(e, t, 10.0, Quaternion.IDENTITY, Vec3.ZERO, bias)
        val after = e.snapshot(t)
        assertEquals(NavMode.INERTIAL, after.mode)
        assertTrue(after.positionEnu.norm() < 5.0, "drift ${after.positionEnu}")
    }

    @Test
    fun gpsAlignsTrackAndCorrectsVelocity() {
        val e = FusionEngine(FusionConfig(useBarometer = false))
        val ltp = LocalTangentPlane(31.0, 121.0, 10.0)
        var t = feedImu(e, 0, 3.0, Quaternion.IDENTITY, Vec3.ZERO)
        // Drive east at a constant 10 m/s with GPS; IMU sees only vibration.
        var pos = Vec3.ZERO
        repeat(30) {
            t = feedImu(e, t, 1.0, Quaternion.IDENTITY, Vec3.ZERO, vibration = 0.25)
            pos += Vec3(10.0, 0.0, 0.0)
            val p = ltp.toGeodetic(pos)
            e.onGps(GpsFix(t, p.lat, p.lon, p.alt, 3.0, 4.0, 10.0, 0.3, 90.0, 2.0), 0.0)
        }
        val s = e.snapshot(t)
        assertEquals(10.0, s.speed, 0.6)
        assertTrue(angleDiff(s.course, 90.0) < 5.0, "course ${s.course}")
        val geo = s.geo!!
        val err = ltp.toEnu(geo.lat, geo.lon, geo.alt) - pos
        assertTrue(Vec3(err.x, err.y, 0.0).norm() < 5.0, "err $err")
    }

    @Test
    fun geodeticRoundTrip() {
        val ltp = LocalTangentPlane(39.9, 116.4, 50.0)
        val enu = Vec3(1234.0, -567.0, 8.0)
        val g = ltp.toGeodetic(enu)
        val back = ltp.toEnu(g.lat, g.lon, g.alt)
        assertTrue((back - enu).norm() < 1e-6)
    }
}
