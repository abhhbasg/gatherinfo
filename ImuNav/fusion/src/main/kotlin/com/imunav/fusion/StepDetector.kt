package com.imunav.fusion

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Step detector and step-length estimator for pedestrian dead reckoning (PDR).
 *
 * Works on the vertical (gravity-aligned) acceleration, so it does not matter how
 * the phone is held. A step is a peak above [peakThreshold] followed by a
 * zero crossing, with a plausible and regular cadence. Step length uses the
 * Weinberg model L = K · (aMax − aMin)^¼, where K is calibrated from GPS.
 *
 * PDR turns position error growth from ~t³ (double-integrated accelerometer with
 * tilt error) into ~distance × (step-length error + heading error).
 */
class StepDetector(
    /** Weinberg coefficient; typical adults 0.42–0.52. Learned from GPS when available. */
    var k: Double = DEFAULT_K,
    private val peakThreshold: Double = 0.9,
    private val minAmplitude: Double = 1.4,
) {
    private var filtered = 0.0
    private var armed = false
    private var maxSince = 0.0
    private var minSince = 0.0
    private var lastStepNs = 0L
    private var lastPeriod = 0.0
    private var regularSteps = 0

    var stepCount = 0
        private set

    /** Length of the most recent step (m). */
    var lastStepLength = 0.0
        private set

    /** (aMax − aMin)^¼ of the most recent step; speed = k · this / period. */
    var lastAmplitudeTerm = 0.0
        private set

    var cadenceHz = 0.0
        private set

    /** Σ (aMax − aMin)^¼ over steps; distance = k × this. Used for known-distance calibration. */
    var unitDistance = 0.0
        private set

    /**
     * @param aUp gravity-free vertical acceleration (m/s²)
     * @return true when a step was detected on this sample
     */
    fun update(timeNs: Long, aUp: Double, dt: Double): Boolean {
        // ~3.5 Hz low-pass removes hand jitter but keeps the step fundamental.
        val alpha = if (dt > 0) dt / (0.045 + dt) else 1.0
        filtered += (aUp - filtered) * alpha
        if (filtered > maxSince) maxSince = filtered
        if (filtered < minSince) minSince = filtered

        if (!armed && filtered > peakThreshold) armed = true
        if (!armed || filtered > 0) return false
        armed = false

        val period = if (lastStepNs == 0L) Double.MAX_VALUE else (timeNs - lastStepNs) * 1e-9
        if (period < MIN_PERIOD) return false // bounce within the same step
        val amplitude = maxSince - minSince
        maxSince = filtered; minSince = filtered
        if (amplitude < minAmplitude) { lastStepNs = timeNs; return false }

        val valid = period <= MAX_PERIOD
        val regular = valid && (lastPeriod == 0.0 || abs(period - lastPeriod) < 0.4 * max(period, lastPeriod))
        regularSteps = when {
            !valid -> 0
            regular -> regularSteps + 1
            else -> 1
        }
        if (valid) {
            lastPeriod = period
            cadenceHz = 1.0 / period
        }
        lastStepNs = timeNs
        lastAmplitudeTerm = amplitude.pow(0.25)
        lastStepLength = k * lastAmplitudeTerm
        unitDistance += lastAmplitudeTerm
        stepCount++
        return true
    }

    /** True while a regular gait is in progress (≥ 2 consecutive regular steps, none missed). */
    fun walking(nowNs: Long): Boolean =
        regularSteps >= 2 && lastStepNs != 0L && (nowNs - lastStepNs) * 1e-9 < max(1.5 * lastPeriod, 0.9)

    fun secondsSinceStep(nowNs: Long): Double =
        if (lastStepNs == 0L) Double.MAX_VALUE else (nowNs - lastStepNs) * 1e-9

    /** Current walking speed (m/s) from step length and cadence, 0 when not walking. */
    fun speed(nowNs: Long): Double {
        if (!walking(nowNs) || lastPeriod <= 0) return 0.0
        return lastStepLength / lastPeriod
    }

    /** Speed the model would report with K = 1 (used to calibrate K against GPS). */
    fun unitSpeed(nowNs: Long): Double {
        if (!walking(nowNs) || lastPeriod <= 0) return 0.0
        return lastAmplitudeTerm / lastPeriod
    }

    fun reset() {
        filtered = 0.0; armed = false; maxSince = 0.0; minSince = 0.0
        lastStepNs = 0L; lastPeriod = 0.0; regularSteps = 0
        stepCount = 0; lastStepLength = 0.0; cadenceHz = 0.0; unitDistance = 0.0
    }

    companion object {
        const val DEFAULT_K = 0.47
        const val MIN_PERIOD = 0.28
        const val MAX_PERIOD = 1.6
    }
}
