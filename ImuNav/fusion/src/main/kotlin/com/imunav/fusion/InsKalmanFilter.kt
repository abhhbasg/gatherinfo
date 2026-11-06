package com.imunav.fusion

import kotlin.math.sqrt

/**
 * 9-state Kalman filter for strapdown inertial navigation in a local ENU frame.
 *
 * State: [pE, pN, pU, vE, vN, vU, baX, baY, baZ] where ba is the accelerometer
 * bias in the body frame. Attitude comes from [AttitudeFilter].
 *
 * Every aiding source (GPS position / velocity, barometer, zero-velocity and
 * non-holonomic constraints) is applied as a sequence of scalar updates, so no
 * matrix inversion is needed and each measurement can be gated independently.
 */
class InsKalmanFilter {
    val x = DoubleArray(N)
    val p = Array(N) { DoubleArray(N) }

    var accelNoise = 0.35 // m/s²/√Hz-ish, includes attitude error leakage
    var accelBiasWalk = 0.003 // m/s² /√s
    var gravity = AttitudeFilter.GRAVITY

    /** Last gravity-free acceleration in the navigation frame. */
    var lastAccelWorld: Vec3 = Vec3.ZERO
        private set

    private val tmpA = Array(N) { DoubleArray(N) }
    private val tmpF = Array(N) { DoubleArray(N) }

    init {
        reset()
    }

    fun reset(posVar: Double = 0.01, velVar: Double = 0.25, biasVar: Double = 0.2 * 0.2) {
        x.fill(0.0)
        for (i in 0 until N) p[i].fill(0.0)
        for (i in 0..2) p[i][i] = posVar
        for (i in 3..5) p[i][i] = velVar
        for (i in 6..8) p[i][i] = biasVar
    }

    val position get() = Vec3(x[0], x[1], x[2])
    val velocity get() = Vec3(x[3], x[4], x[5])
    val accelBias get() = Vec3(x[6], x[7], x[8])

    fun positionStd() = Vec3(sqrt(p[0][0]), sqrt(p[1][1]), sqrt(p[2][2]))
    fun velocityStd() = Vec3(sqrt(p[3][3]), sqrt(p[4][4]), sqrt(p[5][5]))

    /** Propagates the state with one accelerometer sample (body frame, m/s²). */
    fun predict(accelBody: Vec3, r: Mat3, dt: Double) {
        if (dt <= 0 || dt > 0.5) return
        val f = accelBody - accelBias
        val aw = r * f - Vec3(0.0, 0.0, gravity)
        lastAccelWorld = aw
        val dt2 = 0.5 * dt * dt
        x[0] += x[3] * dt + aw.x * dt2
        x[1] += x[4] * dt + aw.y * dt2
        x[2] += x[5] * dt + aw.z * dt2
        x[3] += aw.x * dt
        x[4] += aw.y * dt
        x[5] += aw.z * dt

        // F = I + ... (only the non-identity blocks are filled).
        val fm = tmpF
        for (i in 0 until N) { fm[i].fill(0.0); fm[i][i] = 1.0 }
        for (i in 0..2) fm[i][i + 3] = dt
        for (i in 0..2) for (j in 0..2) {
            fm[i + 3][j + 6] = -r[i, j] * dt
            fm[i][j + 6] = -r[i, j] * dt2
        }
        // P = F P Fᵀ
        val a = tmpA
        for (i in 0 until N) for (j in 0 until N) {
            var s = 0.0
            for (k in 0 until N) s += fm[i][k] * p[k][j]
            a[i][j] = s
        }
        for (i in 0 until N) for (j in 0 until N) {
            var s = 0.0
            for (k in 0 until N) s += a[i][k] * fm[j][k]
            p[i][j] = s
        }
        // Process noise.
        val qv = accelNoise * accelNoise * dt
        val qb = accelBiasWalk * accelBiasWalk * dt
        for (i in 0..2) {
            p[i][i] += qv * dt * dt / 3.0
            p[i][i + 3] += qv * dt / 2.0
            p[i + 3][i] += qv * dt / 2.0
            p[i + 3][i + 3] += qv
            p[i + 6][i + 6] += qb
        }
        // Keep the bias variance bounded so it stays adaptable after long aiding.
        for (i in 6..8) if (p[i][i] < 1e-6) p[i][i] = 1e-6
    }

    /**
     * Generic scalar update z = H·x + v, v ~ N(0, r).
     * @param gate chi-square gate on the normalised innovation (≤0 disables)
     * @return true if accepted
     */
    fun scalarUpdate(h: DoubleArray, z: Double, r: Double, gate: Double = 0.0): Boolean {
        val ph = DoubleArray(N)
        for (i in 0 until N) {
            var s = 0.0
            for (k in 0 until N) s += p[i][k] * h[k]
            ph[i] = s
        }
        var s = r
        var hx = 0.0
        for (i in 0 until N) { s += h[i] * ph[i]; hx += h[i] * x[i] }
        val innov = z - hx
        if (gate > 0 && innov * innov / s > gate) return false
        val k = DoubleArray(N) { ph[it] / s }
        for (i in 0 until N) x[i] += k[i] * innov
        // Joseph-free symmetric form: P = P - K (PH)ᵀ, then symmetrise.
        for (i in 0 until N) for (j in 0 until N) p[i][j] -= k[i] * ph[j]
        for (i in 0 until N) for (j in i + 1 until N) {
            val m = 0.5 * (p[i][j] + p[j][i]); p[i][j] = m; p[j][i] = m
        }
        for (i in 0 until N) if (p[i][i] < 1e-9) p[i][i] = 1e-9
        return true
    }

    fun observeState(index: Int, z: Double, r: Double, gate: Double = 0.0): Boolean {
        val h = DoubleArray(N); h[index] = 1.0
        return scalarUpdate(h, z, r, gate)
    }

    /** Velocity component along a world direction [dir] equals [value]. */
    fun observeVelocityAlong(dir: Vec3, value: Double, r: Double): Boolean {
        val h = DoubleArray(N)
        h[3] = dir.x; h[4] = dir.y; h[5] = dir.z
        return scalarUpdate(h, value, r)
    }

    /** Hard-sets the position (e.g. when the GPS origin is (re)established). */
    fun setPosition(pos: Vec3, variance: Double) {
        x[0] = pos.x; x[1] = pos.y; x[2] = pos.z
        for (i in 0..2) {
            for (j in 0 until N) { p[i][j] = 0.0; p[j][i] = 0.0 }
            p[i][i] = variance
        }
    }

    companion object {
        const val N = 9
        const val PE = 0; const val PN = 1; const val PU = 2
        const val VE = 3; const val VN = 4; const val VU = 5
    }
}
