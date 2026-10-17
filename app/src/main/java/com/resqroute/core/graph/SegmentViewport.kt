package com.resqroute.core.graph

/**
 * Axis-aligned geographic box in WGS84 degrees.
 *
 * Rendering-only value: it never changes graph data, routing or persistence.
 */
data class GeoBox(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {

    /** True when the given segment bounds overlap this box (touching counts). */
    fun intersects(
        minLatitude: Double,
        maxLatitude: Double,
        minLongitude: Double,
        maxLongitude: Double,
    ): Boolean = !(
        maxLatitude < south ||
            minLatitude > north ||
            maxLongitude < west ||
            minLongitude > east
        )

    fun contains(latitude: Double, longitude: Double): Boolean =
        latitude in south..north && longitude in west..east
}

/**
 * Viewport culling for the vector layers.
 *
 * A renderer must never project a polyline that lies entirely outside the
 * camera: at high zoom those points land millions of pixels away from the
 * viewport and a single such Path can be dropped or mis-rendered by the
 * rasteriser (the same failure mode that once made the road layer blank).
 *
 * Culling is decided from each segment's own stored geometry, so the geometry
 * that is drawn is exactly the geometry the router used. Nothing is rewritten,
 * shortened, simplified in the data, or clipped by mutating coordinates — the
 * canvas still clips, this only avoids emitting off-screen work.
 */
object SegmentViewport {

    /** Bounds of one segment's stored polyline, or null when it has no points. */
    fun boundsOf(graph: RoutingGraph, segmentIndex: Int): GeoBox? {
        val points = graph.geometryPointCount(segmentIndex)
        if (points == 0) return null
        var minLatitude = Double.MAX_VALUE
        var maxLatitude = -Double.MAX_VALUE
        var minLongitude = Double.MAX_VALUE
        var maxLongitude = -Double.MAX_VALUE
        var index = 0
        while (index < points) {
            val latitude = graph.geometryLatitude(segmentIndex, index)
            val longitude = graph.geometryLongitude(segmentIndex, index)
            if (latitude < minLatitude) minLatitude = latitude
            if (latitude > maxLatitude) maxLatitude = latitude
            if (longitude < minLongitude) minLongitude = longitude
            if (longitude > maxLongitude) maxLongitude = longitude
            index++
        }
        return GeoBox(
            south = minLatitude,
            west = minLongitude,
            north = maxLatitude,
            east = maxLongitude,
        )
    }

    /** Union of the bounds of every listed segment, or null when none has geometry. */
    fun boundsOf(graph: RoutingGraph, segmentIndexes: Iterable<Int>): GeoBox? {
        var south = Double.MAX_VALUE
        var west = Double.MAX_VALUE
        var north = -Double.MAX_VALUE
        var east = -Double.MAX_VALUE
        var found = false
        for (segment in segmentIndexes) {
            val bounds = boundsOf(graph, segment) ?: continue
            found = true
            if (bounds.south < south) south = bounds.south
            if (bounds.west < west) west = bounds.west
            if (bounds.north > north) north = bounds.north
            if (bounds.east > east) east = bounds.east
        }
        return if (found) GeoBox(south = south, west = west, north = north, east = east) else null
    }

    /** True when the segment has geometry that meets [viewport]. */
    fun isVisible(graph: RoutingGraph, segmentIndex: Int, viewport: GeoBox): Boolean {
        val bounds = boundsOf(graph, segmentIndex) ?: return false
        return viewport.intersects(
            minLatitude = bounds.south,
            maxLatitude = bounds.north,
            minLongitude = bounds.west,
            maxLongitude = bounds.east,
        )
    }
}
