package com.imunav.fusion

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class FusionConfig(
    val useMagnetometer: Boolean = true,
    val useGps: Boolean = true,
    val useZupt: Boolean = true,
    val useBarometer: Boolean = true,
    val motionModel: MotionModel = MotionModel.AUTO,
) {
    val vehicleMode: Boolean get() = motionModel == MotionModel.VEHICLE
}

/**
 * Prior knowledge about how the phone moves. Without GPS this is what keeps the
 * error bounded: pure double integration of a phone accelerometer diverges in seconds.
 */
enum class MotionModel {
    /** Pedestrian dead reckoning while steps are detected, plain INS otherwise. */
    AUTO,
    /** Always on foot: no steps means not moving. */
    PEDESTRIAN,
    /** Phone fixed in a car: no sideways/vertical slip, vibration-tolerant stop detection. */
    VEHICLE,
    /** Unconstrained strapdown INS (drifts quickly without GPS). */
    FREE,
}

/** Learned sensor/motion parameters worth keeping between sessions. */
data class Calibration(
    val gyroBias: Vec3 = Vec3.ZERO,
    val stepK: Double = StepDetector.DEFAULT_K,
    /** Walking direction minus phone-pointing direction (rad), learned from GPS. */
    val walkHeadingOffset: Double = 0.0,
    /** Direction of travel in the body frame for vehicle mode, learned from GPS. */
    val vehicleForwardAxis: Vec3? = null,
)

data class GpsFix(
    /** Reception time on the caller's monotonic clock (ns). */
    val timeNs: Long,
    val lat: Double,
    val lon: Double,
    val alt: Double,
    val horizontalAccuracy: Double,
    val verticalAccuracy: Double? = null,
    val speed: Double? = null,
    val speedAccuracy: Double? = null,
    val bearingDeg: Double? = null,
    val bearingAccuracyDeg: Double? = null,
)

enum class NavMode { INITIALIZING, INERTIAL, GPS_AIDED }

data class TrackPoint(val e: Double, val n: Double)

data class NavState(
    val mode: NavMode = NavMode.INITIALIZING,
    val euler: Euler = Euler(0.0, 0.0, 0.0),
    val quaternion: Quaternion = Quaternion.IDENTITY,
    val velocityEnu: Vec3 = Vec3.ZERO,
    /** Horizontal ground speed (m/s). */
    val speed: Double = 0.0,
    val verticalSpeed: Double = 0.0,
    /** Course over ground from the fused velocity, degrees from north. */
    val course: Double = 0.0,
    /** Gravity-free acceleration in ENU (m/s²). */
    val linearAccelWorld: Vec3 = Vec3.ZERO,
    val positionEnu: Vec3 = Vec3.ZERO,
    val geo: GeoPoint? = null,
    val horizontalStd: Double = 0.0,
    val verticalStd: Double = 0.0,
    val velocityStd: Double = 0.0,
    val gyroBias: Vec3 = Vec3.ZERO,
    val accelBias: Vec3 = Vec3.ZERO,
    val stationary: Boolean = false,
    val accelAiding: Boolean = false,
    val magAiding: Boolean = false,
    val secondsSinceGps: Double? = null,
    val lastGpsAccuracy: Double? = null,
    val declinationDeg: Double = 0.0,
    val distance: Double = 0.0,
    val vehicleAxisLearned: Boolean = false,
    val motionModel: MotionModel = MotionModel.AUTO,
    /** Pedestrian dead reckoning is currently constraining velocity. */
    val pdrActive: Boolean = false,
    val walking: Boolean = false,
    val steps: Int = 0,
    val cadenceHz: Double = 0.0,
    val stepLength: Double = 0.0,
    val stepK: Double = StepDetector.DEFAULT_K,
    val walkHeadingOffsetDeg: Double = 0.0,
    val zaruActive: Boolean = false,
    val rawGyro: Vec3 = Vec3.ZERO,
    val rawAccel: Vec3 = Vec3.ZERO,
    val rawMag: Vec3 = Vec3.ZERO,
    val pressureHPa: Double? = null,
    val baroAltitude: Double? = null,
    val gyroHz: Double = 0.0,
    val accelHz: Double = 0.0,
    val magHz: Double = 0.0,
)

/**
 * Glue between raw sensor streams and the estimators. Thread-safe: sensor
 * callbacks and UI snapshot reads may come from different threads.
 *
 * Without GPS: gyro-integrated attitude (tilt aided by gravity, yaw by the
 * magnetometer), velocity/position by strapdown integration, bounded by ZUPT when
 * still and by non-holonomic constraints in vehicle mode.
 * With GPS: position/velocity fixes correct the INS and calibrate the accelerometer
 * bias, the barometer reference and the magnetic declination.
 */
class FusionEngine(config: FusionConfig = FusionConfig()) {

    @Volatile
    var config: FusionConfig = config

    private val attitude = AttitudeFilter()
    private val ins = InsKalmanFilter()
    private val motion = MotionDetector()
    private val steps = StepDetector()

    /** Low-passed specific force in ENU; points straight up on average while walking. */
    private var meanForceWorld = Vec3(0.0, 0.0, AttitudeFilter.GRAVITY)
    private var walkOffset = 0.0
    private var lastPdrNs = 0L
    private var pdrActive = false
    private var zaruActive = false
    private var pendingCalibration: Calibration? = null

    private var gravity = AttitudeFilter.GRAVITY

    private var lastGyroNs = 0L
    private var lastAccelNs = 0L
    private var latestAccel: Vec3? = null
    private var latestMag: Vec3? = null
    private var latestGyro = Vec3.ZERO

    // Start-up averaging.
    private var initAccelSum = Vec3.ZERO
    private var initMagSum = Vec3.ZERO
    private var initAccelCount = 0
    private var initMagCount = 0

    private var origin: LocalTangentPlane? = null
    private var lastGpsNs: Long? = null
    private var lastGpsAccuracy: Double? = null
    private var gpsRejects = 0

    private var pressure: Double? = null
    private var baroAlt: Double? = null
    private var baroRef: Double? = null
    private var lastBaroUpdateNs = 0L

    private var lastZuptNs = 0L
    private var lastNhcNs = 0L
    private var lastTrackNs = 0L

    /** Horizontal acceleration derived from consecutive GPS velocities (ENU). */
    private var gpsLinearAccel: Vec3? = null
    private var gpsLinearAccelNs = 0L
    private var lastGpsVel: Vec3? = null
    private var lastGpsVelNs = 0L

    private var forwardAxis: Vec3? = null
    private var forwardSamples = 0

    private var distance = 0.0

    private val track = ArrayDeque<TrackPoint>()
    private val gpsTrack = ArrayDeque<TrackPoint>()

    private val gyroRate = RateCounter()
    private val accelRate = RateCounter()
    private val magRate = RateCounter()

    /** Re-runs the initial alignment but keeps everything learned so far. */
    @Synchronized
    fun reset() {
        val keep = calibrationLocked()
        attitude.reset()
        steps.reset()
        meanForceWorld = Vec3(0.0, 0.0, gravity)
        pendingCalibration = keep
        ins.reset()
        motion.reset()
        lastGyroNs = 0; lastAccelNs = 0
        initAccelSum = Vec3.ZERO; initMagSum = Vec3.ZERO
        initAccelCount = 0; initMagCount = 0
        origin = null; lastGpsNs = null; lastGpsAccuracy = null; gpsRejects = 0
        baroRef = null
        gpsLinearAccel = null; lastGpsVel = null
        distance = 0.0
        track.clear(); gpsTrack.clear()
    }

    @Synchronized
    fun clearTrack() {
        track.clear(); gpsTrack.clear(); distance = 0.0
    }

    @Synchronized
    fun onGyro(timeNs: Long, g: Vec3) {
        gyroRate.tick(timeNs)
        latestGyro = g
        motion.addGyro(g)
        val prev = lastGyroNs
        lastGyroNs = timeNs
        if (!attitude.initialized || prev == 0L) return
        val dt = (timeNs - prev) * 1e-9
        val mag = if (config.useMagnetometer) latestMag else null
        // Gravity is only a clean tilt reference at rest. While moving, rely on the gyro
        // unless GPS tells us the actual linear acceleration to remove.
        val linAcc = gpsLinearAccel?.takeIf { timeNs - gpsLinearAccelNs < 1_500_000_000L }
        val (weight, comp) = when {
            motion.stationary -> 1.0 to Vec3.ZERO
            linAcc != null -> 0.5 to attitude.q.toRotationMatrix().tTimes(linAcc)
            else -> MOVING_ACCEL_WEIGHT to Vec3.ZERO
        }
        attitude.update(g, latestAccel, mag, dt, gravity, weight, comp)

        val vehicleStopped = config.motionModel == MotionModel.VEHICLE && motion.stationaryLoose &&
            ins.velocity.norm() < VEHICLE_STOP_SPEED
        zaruActive = motion.stationary || vehicleStopped
        if (zaruActive) attitude.zeroRateUpdate(g, dt)
    }

    @Synchronized
    fun onMagnetometer(timeNs: Long, m: Vec3) {
        magRate.tick(timeNs)
        latestMag = m
        if (!attitude.initialized) {
            initMagSum += m; initMagCount++
        }
    }

    @Synchronized
    fun onAccelerometer(timeNs: Long, a: Vec3) {
        accelRate.tick(timeNs)
        latestAccel = a
        val prev = lastAccelNs
        lastAccelNs = timeNs
        val dt = if (prev == 0L) 0.0 else (timeNs - prev) * 1e-9
        motion.addAccel(a, dt)

        if (!attitude.initialized) {
            initAccelSum += a; initAccelCount++
            if (initAccelCount >= INIT_SAMPLES) initialize()
            return
        }

        val r = attitude.q.toRotationMatrix()
        ins.predict(a, r, dt)

        val cfg = config
        val model = cfg.motionModel
        val hsNow = ins.velocity.let { sqrt(it.x * it.x + it.y * it.y) }
        val still = motion.stationary ||
            (model == MotionModel.VEHICLE && motion.stationaryLoose && hsNow < VEHICLE_STOP_SPEED)
        if (cfg.useZupt && still && timeNs - lastZuptNs > 50_000_000L) {
            lastZuptNs = timeNs
            val rv = 0.02 * 0.02
            ins.observeState(InsKalmanFilter.VE, 0.0, rv)
            ins.observeState(InsKalmanFilter.VN, 0.0, rv)
            ins.observeState(InsKalmanFilter.VU, 0.0, rv)
        }

        // Gait analysis on gravity-aligned acceleration (independent of phone attitude).
        val fWorld = r * a
        if (dt > 0) meanForceWorld += (fWorld - meanForceWorld) * (dt / (TILT_AVG_SECONDS + dt))
        steps.update(timeNs, fWorld.z - gravity, dt)
        val walking = steps.walking(timeNs)
        val pedestrianModel = model == MotionModel.PEDESTRIAN || model == MotionModel.AUTO
        if (pedestrianModel && walking) attitude.correctTilt(meanForceWorld, WALK_TILT_GAIN, dt)

        pdrActive = false
        if (pedestrianModel && !still && timeNs - lastPdrNs > 200_000_000L) {
            val recentlyWalked = steps.secondsSinceStep(timeNs) < AUTO_STOP_HOLD_S
            val noBaro = !(cfg.useBarometer && baroAlt != null)
            if (walking) {
                // PDR: velocity = step length × cadence along the walking direction.
                lastPdrNs = timeNs
                val spd = steps.speed(timeNs)
                val dir = walkHeading(r) + walkOffset
                val rv = (0.15 + 0.12 * spd).let { it * it }
                ins.observeState(InsKalmanFilter.VE, spd * sin(dir), rv)
                ins.observeState(InsKalmanFilter.VN, spd * cos(dir), rv)
                if (noBaro) ins.observeState(InsKalmanFilter.VU, 0.0, 0.25 * 0.25)
                pdrActive = true
            } else if (model == MotionModel.PEDESTRIAN || recentlyWalked) {
                // On foot and no steps: not going anywhere, however the phone is waved around.
                lastPdrNs = timeNs
                val rv = 0.2 * 0.2
                ins.observeState(InsKalmanFilter.VE, 0.0, rv)
                ins.observeState(InsKalmanFilter.VN, 0.0, rv)
                if (noBaro) ins.observeState(InsKalmanFilter.VU, 0.0, rv)
                pdrActive = true
            }
        }

        if (model == MotionModel.VEHICLE && !still && timeNs - lastNhcNs > 100_000_000L) {
            lastNhcNs = timeNs
            val learned = forwardAxis?.takeIf { forwardSamples >= 5 }
            // Until GPS has taught us the mounting, guess it from how the phone sits:
            // top edge forward when lying flat, back facing forward when upright in a holder.
            val fwd = learned ?: guessForwardAxis(r)
            val helper = if (abs(fwd.z) < 0.9) Vec3.UP else Vec3(1.0, 0.0, 0.0)
            val side = (fwd cross helper).normalized()
            val upB = (side cross fwd).normalized()
            val rv = if (learned != null) 0.3 * 0.3 else 0.6 * 0.6
            ins.observeVelocityAlong(r * side, 0.0, rv)
            ins.observeVelocityAlong(r * upB, 0.0, rv)
        }

        val v = ins.velocity
        val hs = sqrt(v.x * v.x + v.y * v.y)
        if (!still && hs > 0.2) distance += hs * dt

        if (timeNs - lastTrackNs > 500_000_000L) {
            lastTrackNs = timeNs
            val p = ins.position
            addPoint(track, TrackPoint(p.x, p.y))
        }
    }

    @Synchronized
    fun onPressure(timeNs: Long, hPa: Double) {
        pressure = hPa
        val alt = pressureToAltitude(hPa)
        val smoothed = baroAlt?.let { it + (alt - it) * 0.2 } ?: alt
        baroAlt = smoothed
        if (!attitude.initialized || !config.useBarometer) return
        val ref = baroRef ?: (smoothed - ins.x[InsKalmanFilter.PU]).also { baroRef = it }
        if (timeNs - lastBaroUpdateNs > 200_000_000L) {
            lastBaroUpdateNs = timeNs
            ins.observeState(InsKalmanFilter.PU, smoothed - ref, 1.0, gate = 25.0)
        }
    }

    /** @param declinationDeg magnetic declination at the fix (east-positive). */
    @Synchronized
    fun onGps(fix: GpsFix, declinationDeg: Double) {
        if (!attitude.initialized || !config.useGps) return
        attitude.declinationRad = Math.toRadians(declinationDeg)
        val cfg = config
        val hVar = max(fix.horizontalAccuracy, 1.0).let { it * it }
        val vVar = max(fix.verticalAccuracy ?: (fix.horizontalAccuracy * 1.5), 1.5).let { it * it }

        val o = origin
        if (o == null) {
            // Anchor the local frame here and shift the dead-reckoned track onto it.
            val newOrigin = LocalTangentPlane(fix.lat, fix.lon, fix.alt)
            origin = newOrigin
            val p = ins.position
            val shifted = track.map { TrackPoint(it.e - p.x, it.n - p.y) }
            track.clear(); track.addAll(shifted)
            ins.setPosition(Vec3.ZERO, hVar)
            baroRef = null
        } else {
            val z = o.toEnu(fix.lat, fix.lon, fix.alt)
            val gate = 25.0
            val okE = ins.observeState(InsKalmanFilter.PE, z.x, hVar, gate)
            val okN = ins.observeState(InsKalmanFilter.PN, z.y, hVar, gate)
            if (!okE && !okN) {
                if (++gpsRejects >= 5) {
                    // Persistent disagreement: trust GPS and re-seed the position.
                    ins.setPosition(Vec3(z.x, z.y, ins.x[InsKalmanFilter.PU]), hVar)
                    gpsRejects = 0
                }
            } else gpsRejects = 0

            val haveBaro = cfg.useBarometer && baroAlt != null
            if (haveBaro) {
                // Slowly align the barometric reference with GPS altitude (weather drift).
                val ba = baroAlt!!
                val target = ba - z.z
                baroRef = baroRef?.let { it + (target - it) * 0.02 } ?: target
            } else {
                ins.observeState(InsKalmanFilter.PU, z.z, vVar, gate)
            }
        }

        val speed = fix.speed
        val bearing = fix.bearingDeg
        if (speed != null) {
            val sAcc = max(fix.speedAccuracy ?: 0.5, 0.2)
            if (bearing != null && speed > 0.5) {
                val b = Math.toRadians(bearing)
                val bAcc = Math.toRadians(fix.bearingAccuracyDeg ?: 10.0)
                val rv = sAcc * sAcc + (speed * bAcc) * (speed * bAcc) * 0.5
                ins.observeState(InsKalmanFilter.VE, speed * sin(b), rv)
                ins.observeState(InsKalmanFilter.VN, speed * cos(b), rv)
                updateGpsAccel(fix.timeNs, Vec3(speed * sin(b), speed * cos(b), 0.0))

                learnWalkingModel(speed, b, fix.bearingAccuracyDeg)

                if (cfg.vehicleMode && speed > 4.0) {
                    val vw = Vec3(speed * sin(b), speed * cos(b), ins.x[InsKalmanFilter.VU])
                    val vb = attitude.q.toRotationMatrix().tTimes(vw).normalized()
                    val f = forwardAxis
                    forwardAxis = if (f == null) vb else (f * 0.85 + vb * 0.15).normalized()
                    forwardSamples++
                }
            } else if (speed < 0.3) {
                updateGpsAccel(fix.timeNs, Vec3.ZERO)
                val rv = max(sAcc, 0.3).let { it * it }
                ins.observeState(InsKalmanFilter.VE, 0.0, rv)
                ins.observeState(InsKalmanFilter.VN, 0.0, rv)
            }
        }

        lastGpsNs = fix.timeNs
        lastGpsAccuracy = fix.horizontalAccuracy
        val g = origin!!.toEnu(fix.lat, fix.lon, fix.alt)
        addPoint(gpsTrack, TrackPoint(g.x, g.y))
    }

    /** Calibrates step length and walking direction offset against GPS while walking. */
    private fun learnWalkingModel(gpsSpeed: Double, bearingRad: Double, bearingAccDeg: Double?) {
        val now = lastAccelNs
        if (!steps.walking(now) || gpsSpeed < 0.8 || gpsSpeed > 3.0) return
        if ((bearingAccDeg ?: 10.0) > 25.0) return
        val r = attitude.q.toRotationMatrix()
        val err = wrapPi(bearingRad - walkHeading(r) - walkOffset)
        walkOffset = wrapPi(walkOffset + 0.1 * err)
        val unit = steps.unitSpeed(now)
        if (unit > 0.2) {
            val target = (gpsSpeed / unit).coerceIn(0.3, 0.75)
            steps.k += (target - steps.k) * 0.08
        }
    }

    /** Horizontal direction the phone faces: top edge when flat, back side when upright. */
    private fun walkHeading(r: Mat3): Double {
        val top = r.column(1)
        val back = -r.column(2)
        return atan2(top.x + back.x, top.y + back.y)
    }

    private fun guessForwardAxis(r: Mat3): Vec3 {
        val topHorizontal = abs(r.column(1).z) < 0.7
        return if (topHorizontal) Vec3(0.0, 1.0, 0.0) else Vec3(0.0, 0.0, -1.0)
    }

    @Synchronized
    fun calibration(): Calibration = calibrationLocked()

    private fun calibrationLocked() = Calibration(
        gyroBias = if (attitude.initialized) attitude.gyroBias else (pendingCalibration?.gyroBias ?: Vec3.ZERO),
        stepK = steps.k,
        walkHeadingOffset = walkOffset,
        vehicleForwardAxis = forwardAxis?.takeIf { forwardSamples >= 5 },
    )

    /** Applies a previously saved calibration (immediately, or at alignment if not yet aligned). */
    @Synchronized
    fun setCalibration(c: Calibration) {
        steps.k = c.stepK.coerceIn(0.3, 0.75)
        walkOffset = c.walkHeadingOffset
        if (c.vehicleForwardAxis != null && c.vehicleForwardAxis.norm() > 0.5) {
            forwardAxis = c.vehicleForwardAxis.normalized(); forwardSamples = 5
        }
        if (attitude.initialized) attitude.setGyroBias(c.gyroBias) else pendingCalibration = c
    }

    private var distanceCalStart: Pair<Double, Int>? = null

    /** Starts a "walk a known distance" step-length calibration (no GPS needed). */
    @Synchronized
    fun startStepCalibration() {
        distanceCalStart = steps.unitDistance to steps.stepCount
    }

    /** Steps counted since [startStepCalibration], or null if none is running. */
    @Synchronized
    fun stepCalibrationProgress(): Pair<Int, Double>? = distanceCalStart?.let { (u, n) ->
        (steps.stepCount - n) to (steps.unitDistance - u) * steps.k
    }

    /**
     * Finishes the calibration given the true distance walked.
     * @return the new step coefficient, or null if too few steps were recorded
     */
    @Synchronized
    fun finishStepCalibration(actualMeters: Double): Double? {
        val (u0, n0) = distanceCalStart ?: return null
        distanceCalStart = null
        val unit = steps.unitDistance - u0
        if (steps.stepCount - n0 < 10 || unit <= 0 || actualMeters <= 0) return null
        steps.k = (actualMeters / unit).coerceIn(0.3, 0.75)
        return steps.k
    }

    @Synchronized
    fun cancelStepCalibration() { distanceCalStart = null }

    /** Forgets everything learned (step length, offsets, biases). */
    @Synchronized
    fun clearCalibration() {
        steps.k = StepDetector.DEFAULT_K
        walkOffset = 0.0
        forwardAxis = null; forwardSamples = 0
        pendingCalibration = null
        if (attitude.initialized) attitude.setGyroBias(Vec3.ZERO)
    }

    private fun updateGpsAccel(timeNs: Long, v: Vec3) {
        val prev = lastGpsVel
        val dt = (timeNs - lastGpsVelNs) * 1e-9
        if (prev != null && dt in 0.2..2.5) {
            gpsLinearAccel = (v - prev) * (1.0 / dt)
            // Stamp with the IMU clock, which is what the gyro path compares against.
            gpsLinearAccelNs = lastGyroNs
        }
        lastGpsVel = v
        lastGpsVelNs = timeNs
    }

    private fun initialize() {
        val a = initAccelSum * (1.0 / initAccelCount)
        val m = if (config.useMagnetometer && initMagCount > 0) initMagSum * (1.0 / initMagCount) else null
        val gMeasured = a.norm()
        gravity = if (gMeasured in 9.6..10.0) gMeasured else AttitudeFilter.GRAVITY
        ins.gravity = gravity
        attitude.initialize(a, m)
        pendingCalibration?.let { attitude.setGyroBias(it.gyroBias) }
        pendingCalibration = null
        meanForceWorld = Vec3(0.0, 0.0, gravity)
        ins.reset()
    }

    private fun addPoint(list: ArrayDeque<TrackPoint>, p: TrackPoint) {
        list.addLast(p)
        while (list.size > MAX_TRACK) list.removeFirst()
    }

    @Synchronized
    fun track(): Pair<List<TrackPoint>, List<TrackPoint>> = track.toList() to gpsTrack.toList()

    /** @param nowNs current time on the same clock as [GpsFix.timeNs]. */
    @Synchronized
    fun snapshot(nowNs: Long): NavState {
        val init = attitude.initialized
        val sinceGps = lastGpsNs?.let { (nowNs - it) * 1e-9 }
        val mode = when {
            !init -> NavMode.INITIALIZING
            config.useGps && sinceGps != null && sinceGps < GPS_TIMEOUT_S -> NavMode.GPS_AIDED
            else -> NavMode.INERTIAL
        }
        val v = ins.velocity
        val pos = ins.position
        val ps = ins.positionStd()
        val vs = ins.velocityStd()
        val hs = sqrt(v.x * v.x + v.y * v.y)
        var course = Math.toDegrees(atan2(v.x, v.y))
        if (course < 0) course += 360.0
        val o = origin
        val geo = o?.toGeodetic(pos)
        return NavState(
            mode = mode,
            euler = Euler.from(attitude.q),
            quaternion = attitude.q,
            velocityEnu = v,
            speed = hs,
            verticalSpeed = v.z,
            course = course,
            linearAccelWorld = ins.lastAccelWorld,
            positionEnu = pos,
            geo = geo,
            horizontalStd = sqrt(ps.x * ps.x + ps.y * ps.y),
            verticalStd = ps.z,
            velocityStd = sqrt(vs.x * vs.x + vs.y * vs.y),
            gyroBias = attitude.gyroBias,
            accelBias = ins.accelBias,
            stationary = motion.stationary,
            accelAiding = attitude.lastAccelUsed,
            magAiding = attitude.lastMagUsed,
            secondsSinceGps = sinceGps,
            lastGpsAccuracy = lastGpsAccuracy,
            declinationDeg = Math.toDegrees(attitude.declinationRad),
            distance = distance,
            vehicleAxisLearned = forwardAxis != null && forwardSamples >= 5,
            motionModel = config.motionModel,
            pdrActive = pdrActive,
            walking = steps.walking(lastAccelNs),
            steps = steps.stepCount,
            cadenceHz = steps.cadenceHz,
            stepLength = steps.lastStepLength,
            stepK = steps.k,
            walkHeadingOffsetDeg = Math.toDegrees(walkOffset),
            zaruActive = zaruActive,
            rawGyro = latestGyro,
            rawAccel = latestAccel ?: Vec3.ZERO,
            rawMag = latestMag ?: Vec3.ZERO,
            pressureHPa = pressure,
            baroAltitude = baroAlt,
            gyroHz = gyroRate.hz,
            accelHz = accelRate.hz,
            magHz = magRate.hz,
        )
    }

    private class RateCounter {
        private var windowStart = 0L
        private var count = 0
        var hz = 0.0
            private set

        fun tick(t: Long) {
            if (windowStart == 0L) windowStart = t
            count++
            val span = t - windowStart
            if (span >= 1_000_000_000L) {
                hz = count * 1e9 / span
                count = 0
                windowStart = t
            }
        }
    }

    companion object {
        const val INIT_SAMPLES = 50
        const val MOVING_ACCEL_WEIGHT = 0.01
        const val VEHICLE_STOP_SPEED = 3.0
        const val TILT_AVG_SECONDS = 2.0
        const val WALK_TILT_GAIN = 0.3
        const val AUTO_STOP_HOLD_S = 3.0
        const val GPS_TIMEOUT_S = 3.0
        const val MAX_TRACK = 4000
    }
}
