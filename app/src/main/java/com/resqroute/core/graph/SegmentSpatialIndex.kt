package com.resqroute.core.graph

import com.resqroute.core.model.GeoCoordinate
import com.resqroute.core.model.GeoMath
import com.resqroute.core.model.SegmentId
import kotlin.math.sqrt

/**
 * Nearest-segment lookup for long-press road selection (Phase 5) and any other
 * spatial query over the packed geometry.
 *
 * Implementations are pure CPU work; callers are responsible for running them
 * off the Main thread.
 */
interface SegmentSpatialIndex {

    /**
     * Closest physical segment to [coordinate], refined by exact distance.
     *
     * Returns null when no segment lies within [maximumDistanceMeters].
     * Never silently snaps to a far road.
     */
    suspend fun nearestSegment(
        coordinate: GeoCoordinate,
        maximumDistanceMeters: Double,
    ): SegmentHit?

    /**
     * All physical segments within [maximumDistanceMeters] of [coordinate],
     * sorted by distance (ties by segment index), capped at [limit].
     *
     * Phase 5 gathers candidates with this call and then refines them in screen
     * space before deciding which segment a long-press targets.
     */
    suspend fun nearbySegments(
        coordinate: GeoCoordinate,
        maximumDistanceMeters: Double,
        limit: Int,
    ): List<SegmentHit>
}

/** Result of a nearest-segment query. */
data class SegmentHit(
    val segmentId: SegmentId,
    val nearestPoint: GeoCoordinate,
    val distanceMeters: Double,
)

/**
 * Uniform lat/lon grid index, exactly as written by the Phase 2 builder.
 *
 * Cell `(ix, iy)` holds segment indices with `ix` derived from latitude and
 * `iy` from longitude: slice `offset[ix * ny + iy]`. A segment is listed in
 * every cell its bounding box touches, so candidates are deduplicated per query.
 *
 * Thread-safe: all state is immutable after construction.
 */
class PackedGridSegmentSpatialIndex internal constructor(
    private val south: Double,
    private val west: Double,
    private val gridNx: Int,
    private val gridNy: Int,
    private val cellDegrees: Double,
    private val cellOffset: IntArray,
    private val cellSegments: IntArray,
    private val graph: RoutingGraph,
) : SegmentSpatialIndex {

    override suspend fun nearestSegment(
        coordinate: GeoCoordinate,
        maximumDistanceMeters: Double,
    ): SegmentHit? = nearbySegments(coordinate, maximumDistanceMeters, limit = 1).firstOrNull()

    override suspend fun nearbySegments(
        coordinate: GeoCoordinate,
        maximumDistanceMeters: Double,
        limit: Int,
    ): List<SegmentHit> {
        if (limit <= 0) return emptyList()
        if (!maximumDistanceMeters.isFinite() || maximumDistanceMeters <= 0.0) return emptyList()
        if (graph.segmentCount == 0) return emptyList()

        val latitude = coordinate.latitude
        val longitude = coordinate.longitude

        // Query box derived from the search radius (metres -> degrees).
        val metersPerLon = GeoMath.metersPerDegreeLongitude(latitude)
        val latSpan = maximumDistanceMeters / GeoMath.METERS_PER_DEGREE_LATITUDE
        val lonSpan = maximumDistanceMeters / metersPerLon

        val ix0 = cellIndex(latitude - latSpan, south, gridNx)
        val ix1 = cellIndex(latitude + latSpan, south, gridNx)
        val iy0 = cellIndex(longitude - lonSpan, west, gridNy)
        val iy1 = cellIndex(longitude + lonSpan, west, gridNy)

        val maximumSquared = maximumDistanceMeters * maximumDistanceMeters
        val seen = HashSet<Int>(64)
        val hits = ArrayList<SegmentHit>(16)

        var ix = ix0
        while (ix <= ix1) {
            var iy = iy0
            while (iy <= iy1) {
                val cell = ix * gridNy + iy
                var slot = cellOffset[cell]
                val end = cellOffset[cell + 1]
                while (slot < end) {
                    val segment = cellSegments[slot]
                    if (seen.add(segment)) {
                        val metrics = distanceMetrics(latitude, longitude, segment)
                        if (metrics.distanceSq <= maximumSquared) {
                            hits.add(
                                SegmentHit(
                                    segmentId = SegmentId(segment),
                                    nearestPoint = GeoCoordinate(
                                        metrics.nearestLat.coerceIn(-90.0, 90.0),
                                        metrics.nearestLon.coerceIn(-180.0, 180.0),
                                    ),
                                    distanceMeters = sqrt(metrics.distanceSq),
                                ),
                            )
                        }
                    }
                    slot++
                }
                iy++
            }
            ix++
        }

        if (hits.isEmpty()) return emptyList()
        hits.sortWith(compareBy({ it.distanceMeters }, { it.segmentId.value }))
        return if (hits.size <= limit) hits else ArrayList(hits.subList(0, limit))
    }

    private fun cellIndex(value: Double, origin: Double, count: Int): Int {
        if (value <= origin) return 0
        val raw = ((value - origin) / cellDegrees).toInt()
        return if (raw >= count) count - 1 else raw
    }

    /**
     * Local equirectangular projection around the query point, then exact
     * point-to-polyline distance in that projected plane. Adequate (sub-meter)
     * at the scale of long-press selection; the projection is local, never used
     * for routing costs.
     */
    private fun distanceMetrics(
        latitude: Double,
        longitude: Double,
        segmentIndex: Int,
    ): SegmentMetrics {
        val metersPerLat = GeoMath.METERS_PER_DEGREE_LATITUDE
        val metersPerLon = GeoMath.metersPerDegreeLongitude(latitude)

        val pointCount = graph.geometryPointCount(segmentIndex)
        if (pointCount == 0) return SegmentMetrics(Double.MAX_VALUE, latitude, longitude)
        if (pointCount == 1) {
            val lat = graph.geometryLatitude(segmentIndex, 0)
            val lon = graph.geometryLongitude(segmentIndex, 0)
            val dy = (lat - latitude) * metersPerLat
            val dx = (lon - longitude) * metersPerLon
            return SegmentMetrics(dx * dx + dy * dy, lat, lon)
        }

        var bestSq = Double.MAX_VALUE
        var bestLat = latitude
        var bestLon = longitude

        var ax = (graph.geometryLongitude(segmentIndex, 0) - longitude) * metersPerLon
        var ay = (graph.geometryLatitude(segmentIndex, 0) - latitude) * metersPerLat
        var i = 1
        while (i < pointCount) {
            val bx = (graph.geometryLongitude(segmentIndex, i) - longitude) * metersPerLon
            val by = (graph.geometryLatitude(segmentIndex, i) - latitude) * metersPerLat

            val abx = bx - ax
            val aby = by - ay
            val lengthSq = abx * abx + aby * aby
            val t = if (lengthSq <= 1e-12) {
                0.0
            } else {
                // Projection of the query point (origin) onto segment a->b.
                val raw = -(ax * abx + ay * aby) / lengthSq
                raw.coerceIn(0.0, 1.0)
            }
            val nx = ax + t * abx
            val ny = ay + t * aby
            val distanceSq = nx * nx + ny * ny
            if (distanceSq < bestSq) {
                bestSq = distanceSq
                bestLon = longitude + nx / metersPerLon
                bestLat = latitude + ny / metersPerLat
            }
            ax = bx
            ay = by
            i++
        }
        return SegmentMetrics(bestSq, bestLat, bestLon)
    }

    private class SegmentMetrics(
        val distanceSq: Double,
        val nearestLat: Double,
        val nearestLon: Double,
    )
}
