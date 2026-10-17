package com.resqroute.core.model

/**
 * WGS84 geographic coordinate in decimal degrees.
 *
 * This is a boundary value object. The routing hot path works on packed
 * primitive arrays and never allocates coordinates per node or per neighbor.
 */
data class GeoCoordinate(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }
    }
}
