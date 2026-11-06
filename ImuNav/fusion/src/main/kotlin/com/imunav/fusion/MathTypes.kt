package com.imunav.fusion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Immutable 3D vector. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 {
        val n = norm()
        return if (n < 1e-12) ZERO else this * (1.0 / n)
    }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val UP = Vec3(0.0, 0.0, 1.0)
    }
}

/** Row-major 3x3 matrix. */
class Mat3(val m: DoubleArray) {
    init {
        require(m.size == 9)
    }

    operator fun get(r: Int, c: Int) = m[r * 3 + c]

    operator fun times(v: Vec3) = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z,
    )

    /** Transpose times vector, i.e. world -> body when this is body -> world. */
    fun tTimes(v: Vec3) = Vec3(
        m[0] * v.x + m[3] * v.y + m[6] * v.z,
        m[1] * v.x + m[4] * v.y + m[7] * v.z,
        m[2] * v.x + m[5] * v.y + m[8] * v.z,
    )

    fun column(c: Int) = Vec3(m[c], m[3 + c], m[6 + c])
}

/**
 * Unit quaternion describing the rotation from the device (body) frame to the
 * local navigation frame (ENU: x = East, y = North, z = Up).
 *
 * Body frame follows Android's sensor convention: x to the right of the screen,
 * y towards the top of the screen, z out of the screen.
 */
data class Quaternion(val w: Double, val x: Double, val y: Double, val z: Double) {

    operator fun times(o: Quaternion) = Quaternion(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    fun conjugate() = Quaternion(w, -x, -y, -z)

    fun normalized(): Quaternion {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return if (n < 1e-12) IDENTITY else Quaternion(w / n, x / n, y / n, z / n)
    }

    fun toRotationMatrix(): Mat3 {
        val ww = w * w; val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        return Mat3(
            doubleArrayOf(
                ww + xx - yy - zz, 2 * (xy - wz), 2 * (xz + wy),
                2 * (xy + wz), ww - xx + yy - zz, 2 * (yz - wx),
                2 * (xz - wy), 2 * (yz + wx), ww - xx - yy + zz,
            )
        )
    }

    fun rotate(v: Vec3): Vec3 = toRotationMatrix() * v

    /** Integrates a body angular rate (rad/s) over dt using the exact exponential map. */
    fun integrate(omega: Vec3, dt: Double): Quaternion {
        val angle = omega.norm() * dt
        if (angle < 1e-12) return this
        val axis = omega * (1.0 / omega.norm())
        val h = angle / 2
        val s = sin(h)
        return (this * Quaternion(cos(h), axis.x * s, axis.y * s, axis.z * s)).normalized()
    }

    companion object {
        val IDENTITY = Quaternion(1.0, 0.0, 0.0, 0.0)

        fun fromAxisAngle(axis: Vec3, angle: Double): Quaternion {
            val a = axis.normalized()
            val s = sin(angle / 2)
            return Quaternion(cos(angle / 2), a.x * s, a.y * s, a.z * s)
        }

        fun fromRotationMatrix(r: Mat3): Quaternion {
            val tr = r[0, 0] + r[1, 1] + r[2, 2]
            val q = if (tr > 0) {
                val s = sqrt(tr + 1.0) * 2
                Quaternion(0.25 * s, (r[2, 1] - r[1, 2]) / s, (r[0, 2] - r[2, 0]) / s, (r[1, 0] - r[0, 1]) / s)
            } else if (r[0, 0] > r[1, 1] && r[0, 0] > r[2, 2]) {
                val s = sqrt(1.0 + r[0, 0] - r[1, 1] - r[2, 2]) * 2
                Quaternion((r[2, 1] - r[1, 2]) / s, 0.25 * s, (r[0, 1] + r[1, 0]) / s, (r[0, 2] + r[2, 0]) / s)
            } else if (r[1, 1] > r[2, 2]) {
                val s = sqrt(1.0 + r[1, 1] - r[0, 0] - r[2, 2]) * 2
                Quaternion((r[0, 2] - r[2, 0]) / s, (r[0, 1] + r[1, 0]) / s, 0.25 * s, (r[1, 2] + r[2, 1]) / s)
            } else {
                val s = sqrt(1.0 + r[2, 2] - r[0, 0] - r[1, 1]) * 2
                Quaternion((r[1, 0] - r[0, 1]) / s, (r[0, 2] + r[2, 0]) / s, (r[1, 2] + r[2, 1]) / s, 0.25 * s)
            }
            return q.normalized()
        }

        /**
         * Builds an attitude from a gravity reading (specific force, pointing up at rest)
         * and an optional magnetometer reading. [declinationRad] converts magnetic north
         * to true north (east-positive).
         */
        fun fromAccelMag(accel: Vec3, mag: Vec3?, declinationRad: Double = 0.0): Quaternion {
            val up = accel.normalized()
            // Pick a horizontal "east" reference: from the magnetometer if usable, else any.
            var east = if (mag != null) (mag cross up) else Vec3.ZERO
            if (east.norm() < 1e-6) {
                val helper = if (abs(up.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
                east = helper cross up
            }
            east = east.normalized()
            val north = up cross east
            // Rows of R are the world axes expressed in body coordinates.
            val r = Mat3(
                doubleArrayOf(
                    east.x, east.y, east.z,
                    north.x, north.y, north.z,
                    up.x, up.y, up.z,
                )
            )
            val qMag = fromRotationMatrix(r)
            return (fromAxisAngle(Vec3.UP, -declinationRad) * qMag).normalized()
        }
    }
}

/** Human friendly attitude, all angles in degrees. */
data class Euler(
    /** 0..360, clockwise from (true) north, direction the top of the phone points. */
    val heading: Double,
    /** -90..90, positive when the top of the phone is raised. */
    val pitch: Double,
    /** -180..180, positive when the right edge of the phone goes down. */
    val roll: Double,
) {
    companion object {
        fun from(q: Quaternion): Euler {
            val r = q.toRotationMatrix()
            // Forward (body y) expressed in world: column 1.
            var heading = Math.toDegrees(atan2(r[0, 1], r[1, 1]))
            if (heading < 0) heading += 360.0
            val pitch = Math.toDegrees(asin(r[2, 1].coerceIn(-1.0, 1.0)))
            val roll = Math.toDegrees(atan2(-r[2, 0], r[2, 2]))
            return Euler(heading, pitch, roll)
        }
    }
}

fun wrapPi(a: Double): Double {
    var x = a
    while (x > PI) x -= 2 * PI
    while (x < -PI) x += 2 * PI
    return x
}
