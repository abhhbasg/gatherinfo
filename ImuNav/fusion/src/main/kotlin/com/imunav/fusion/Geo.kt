package com.imunav.fusion

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Local tangent plane (ENU) anchored at a geodetic origin; accurate to a few tens of km. */
class LocalTangentPlane(val lat0: Double, val lon0: Double, val alt0: Double) {
    private val latRad = Math.toRadians(lat0)
    private val sinLat = sin(latRad)
    // WGS-84 meridian (M) and prime vertical (N) radii of curvature.
    private val n = A / sqrt(1 - E2 * sinLat * sinLat)
    private val m = A * (1 - E2) / (1 - E2 * sinLat * sinLat).pow(1.5)
    private val metersPerDegLat = Math.toRadians(1.0) * (m + alt0)
    private val metersPerDegLon = Math.toRadians(1.0) * (n + alt0) * cos(latRad)

    fun toEnu(lat: Double, lon: Double, alt: Double) = Vec3(
        (lon - lon0) * metersPerDegLon,
        (lat - lat0) * metersPerDegLat,
        alt - alt0,
    )

    fun toGeodetic(enu: Vec3) = GeoPoint(
        lat0 + enu.y / metersPerDegLat,
        lon0 + enu.x / metersPerDegLon,
        alt0 + enu.z,
    )

    companion object {
        const val A = 6378137.0
        const val E2 = 6.69437999014e-3
    }
}

data class GeoPoint(val lat: Double, val lon: Double, val alt: Double)

/** Barometric altitude (m) from pressure (hPa) using the standard atmosphere. */
fun pressureToAltitude(hPa: Double, seaLevelHPa: Double = 1013.25): Double =
    44330.0 * (1.0 - (hPa / seaLevelHPa).pow(1.0 / 5.255))
