package com.resqroute.core.model

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared geographic math.
 *
 * The constants and formulas MUST stay identical to the dataset builder
 * (`tools/dataset/build_dataset.py`) so that routing costs, heuristic values
 * and the loader's sanity checks agree with the artifact.
 */
object GeoMath {

    /** Mean Earth radius used by the Phase 2 builder. */
    const val EARTH_RADIUS_METERS = 6371008.8

    /** Meters per degree of latitude on the sphere used by the builder. */
    const val METERS_PER_DEGREE_LATITUDE = 111320.0

    /** Great-circle (Haversine) distance in meters. */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2).let { it * it } +
            cos(phi1) * cos(phi2) * sin(dLambda / 2).let { it * it }
        return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
    }

    /** Meters covered by one degree of longitude at the given latitude. */
    fun metersPerDegreeLongitude(latitudeDegrees: Double): Double =
        METERS_PER_DEGREE_LATITUDE * max(1e-6, cos(Math.toRadians(latitudeDegrees)))

    private fun max(a: Double, b: Double): Double = if (a > b) a else b
}
