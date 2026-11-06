package com.imunav.fusion

import kotlin.math.sqrt

/**
 * Detects when the device is at rest from short windows of accelerometer and
 * gyroscope magnitudes. Used to trigger zero-velocity updates (ZUPT), which is
 * what keeps pure inertial dead-reckoning from drifting while standing still.
 */
class MotionDetector(
    private val window: Int = 40,
    var accelStdThreshold: Double = 0.10,
    var gyroThreshold: Double = 0.06,
    private val holdSeconds: Double = 0.35,
) {
    private val acc = DoubleArray(window)
    private var accCount = 0
    private var accIdx = 0
    private val gyr = DoubleArray(window)
    private var gyrCount = 0
    private var gyrIdx = 0
    private var stillTime = 0.0

    var stationary = false
        private set

    /** Mean |a| over the window, useful to calibrate local gravity at rest. */
    var meanAccel = 0.0
        private set

    fun addGyro(g: Vec3) {
        gyr[gyrIdx] = g.norm()
        gyrIdx = (gyrIdx + 1) % window
        if (gyrCount < window) gyrCount++
    }

    fun addAccel(a: Vec3, dt: Double) {
        acc[accIdx] = a.norm()
        accIdx = (accIdx + 1) % window
        if (accCount < window) accCount++
        if (accCount < window / 2) { stationary = false; return }

        var mean = 0.0
        for (i in 0 until accCount) mean += acc[i]
        mean /= accCount
        var v = 0.0
        for (i in 0 until accCount) { val d = acc[i] - mean; v += d * d }
        val std = sqrt(v / accCount)
        meanAccel = mean

        var gMax = 0.0
        for (i in 0 until gyrCount) if (gyr[i] > gMax) gMax = gyr[i]
        val gyroOk = gyrCount == 0 || gMax < gyroThreshold

        val still = std < accelStdThreshold && gyroOk
        stillTime = if (still) stillTime + dt else 0.0
        stationary = stillTime >= holdSeconds
    }

    fun reset() {
        accCount = 0; accIdx = 0; gyrCount = 0; gyrIdx = 0
        stillTime = 0.0; stationary = false
    }
}
