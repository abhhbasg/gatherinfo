package com.imunav.fusion

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Nonlinear complementary (Mahony) attitude filter on SO(3) with online gyro bias
 * estimation.
 *
 * - The gyroscope is integrated at full rate (works without any aiding).
 * - Gravity (accelerometer) corrects roll/pitch, but only when the measured specific
 *   force is close to 1 g, so linear accelerations don't tilt the horizon.
 * - The magnetometer corrects yaw only (its error is projected onto the vertical axis),
 *   and is rejected when the field strength looks disturbed.
 */
class AttitudeFilter(
    var kpAccel: Double = 1.2,
    var kpMag: Double = 0.8,
    var ki: Double = 0.03,
) {
    var q: Quaternion = Quaternion.IDENTITY
        private set

    /** Integral feedback term; the estimated gyro bias is its negation. */
    private var integral: Vec3 = Vec3.ZERO

    val gyroBias: Vec3 get() = -integral

    var initialized = false
        private set

    /** Expected local field strength (µT), learned at init / while calm. */
    private var magStrength = 0.0

    /** Set by the caller (from GPS position) to convert magnetic to true north. */
    var declinationRad: Double = 0.0

    var lastAccelUsed = false
        private set
    var lastMagUsed = false
        private set

    private var timeSinceInit = 0.0

    fun initialize(accel: Vec3, mag: Vec3?) {
        q = Quaternion.fromAccelMag(accel, mag, declinationRad)
        integral = Vec3.ZERO
        magStrength = mag?.norm() ?: 0.0
        initialized = true
        timeSinceInit = 0.0
    }

    fun reset() {
        initialized = false
        q = Quaternion.IDENTITY
        integral = Vec3.ZERO
    }

    /**
     * @param gyro body rate in rad/s
     * @param accel latest specific force in m/s² (null to skip)
     * @param mag latest magnetic field in µT (null to skip)
     * @param gravity local gravity magnitude used to gate accelerometer corrections
     * @param accelWeight extra weight on the gravity correction (lower it while moving)
     * @param linearAccelBody known non-gravitational acceleration in body frame (e.g. from
     *        GPS velocity changes); subtracted so that only gravity remains
     */
    fun update(
        gyro: Vec3,
        accel: Vec3?,
        mag: Vec3?,
        dt: Double,
        gravity: Double = GRAVITY,
        accelWeight: Double = 1.0,
        linearAccelBody: Vec3 = Vec3.ZERO,
    ) {
        if (!initialized || dt <= 0 || dt > 0.5) return
        timeSinceInit += dt
        // Converge fast right after start-up, then settle to the nominal gains.
        val boost = if (timeSinceInit < 2.0) 5.0 else 1.0
        val r = q.toRotationMatrix()
        var err = Vec3.ZERO
        var haveErr = false

        lastAccelUsed = false
        if (accel != null && accelWeight > 0) {
            val gravityOnly = accel - linearAccelBody
            val an = gravityOnly.norm()
            val dev = abs(an - gravity) / gravity
            if (dev < ACCEL_GATE) {
                // Down-weight smoothly as the reading departs from 1 g.
                // Centripetal acceleration makes tilt unreliable while turning.
                val turn = 1.0 / (1.0 + gyro.norm() / 0.5)
                val w = (1.0 - dev / ACCEL_GATE) * turn * accelWeight
                val measuredUp = gravityOnly * (1.0 / an)
                val estimatedUp = Vec3(r[2, 0], r[2, 1], r[2, 2])
                err += (measuredUp cross estimatedUp) * (kpAccel * w * boost)
                haveErr = true
                lastAccelUsed = true
            }
        }

        lastMagUsed = false
        if (mag != null) {
            val mn = mag.norm()
            val strengthOk = magStrength <= 0 || abs(mn - magStrength) / magStrength < MAG_GATE
            if (mn > 1e-3 && strengthOk) {
                val m = mag * (1.0 / mn)
                val h = r * m
                val bh = sqrt(h.x * h.x + h.y * h.y)
                val ref = Vec3(bh * sin(declinationRad), bh * cos(declinationRad), h.z)
                val estimated = r.tTimes(ref)
                val e = m cross estimated
                // Keep only the yaw component so the magnetometer never disturbs tilt.
                val eWorldZ = (r * e).z
                err += r.tTimes(Vec3(0.0, 0.0, eWorldZ)) * (kpMag * boost)
                haveErr = true
                lastMagUsed = true
                if (magStrength <= 0) magStrength = mn else magStrength += (mn - magStrength) * 0.001
            }
        }

        if (haveErr && ki > 0) {
            integral += err * (ki * dt)
            // Clamp to a physically plausible MEMS bias (~3 deg/s).
            val n = integral.norm()
            if (n > MAX_BIAS) integral = integral * (MAX_BIAS / n)
        }
        val omega = gyro + err + integral
        q = q.integrate(omega, dt)
    }

    /**
     * Zero Angular Rate Update: while the device is at rest the gyro output *is* its
     * bias, on all three axes, including yaw, which gravity can never observe. This is
     * the main defence against heading drift when the magnetometer is off or disturbed.
     */
    fun zeroRateUpdate(gyro: Vec3, dt: Double, timeConstant: Double = 4.0) {
        if (!initialized || dt <= 0) return
        val a = (dt / timeConstant).coerceAtMost(1.0)
        integral += (-gyro - integral) * a
        val n = integral.norm()
        if (n > MAX_BIAS) integral = integral * (MAX_BIAS / n)
    }

    /**
     * Tilt correction from the *average* specific force in the navigation frame.
     * While walking, accelerations are zero-mean over a stride, so the low-passed
     * world-frame specific force points straight up; any horizontal component is
     * tilt error. Unlike the raw accelerometer this is immune to the step impacts.
     */
    fun correctTilt(meanSpecificForceWorld: Vec3, gain: Double, dt: Double) {
        if (!initialized || dt <= 0) return
        val n = meanSpecificForceWorld.norm()
        if (n < 1e-3) return
        val r = q.toRotationMatrix()
        val measuredUp = r.tTimes(meanSpecificForceWorld * (1.0 / n))
        val estimatedUp = Vec3(r[2, 0], r[2, 1], r[2, 2])
        val err = (measuredUp cross estimatedUp) * gain
        q = q.integrate(err, dt)
        if (ki > 0) integral += err * (ki * 0.5 * dt)
    }

    /** Seeds the gyro bias, e.g. from a calibration saved in a previous session. */
    fun setGyroBias(bias: Vec3) {
        integral = -bias
        val n = integral.norm()
        if (n > MAX_BIAS) integral = integral * (MAX_BIAS / n)
    }

    /** Rotates the estimate about the vertical so that heading increases by [deltaRad]. */
    fun applyHeadingCorrection(deltaRad: Double) {
        q = (Quaternion.fromAxisAngle(Vec3.UP, -deltaRad) * q).normalized()
    }

    companion object {
        const val GRAVITY = 9.80665
        const val ACCEL_GATE = 0.15
        const val MAG_GATE = 0.25
        const val MAX_BIAS = 0.05
    }
}
