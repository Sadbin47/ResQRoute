package com.resqroute.feature.map.ui

import com.resqroute.core.graph.RoutingGraph
import com.resqroute.core.graph.SegmentHit
import com.resqroute.core.graph.SegmentSpatialIndex
import com.resqroute.core.model.GeoCoordinate
import com.resqroute.core.model.SegmentId

/** Screen-space point in pixels, produced by the map projection. */
data class ScreenPoint(val x: Double, val y: Double)

/** Outcome of a long-press road selection. */
sealed interface RoadSelectionResult {

    /** Exactly one physical segment is clearly the target. */
    data class Selected(
        val segmentId: SegmentId,
        val pixelDistance: Double,
        val geographicPoint: GeoCoordinate,
    ) : RoadSelectionResult

    /**
     * Two or more different physical segments are within the ambiguity margin.
     * The UI must ask the user to zoom in or choose — never silently pick one.
     */
    data class Ambiguous(
        val candidates: List<SegmentId>,
    ) : RoadSelectionResult

    /** Nothing within the touch tolerance. */
    data object None : RoadSelectionResult
}

/**
 * Long-press road selection.
 *
 * Pipeline (blueprint section 12, "Long-press selection"):
 *  1. the caller converts the touch to a [GeoCoordinate];
 *  2. a conservative geographic radius (touch tolerance in pixels × metres per
 *     pixel × margin) is queried against the segment spatial index;
 *  3. candidates are refined in **screen space** — the closest point of each
 *     candidate polyline is found in pixels;
 *  4. the closest candidate wins, unless another candidate is within
 *     [ambiguityMarginPixels]; then the result is [RoadSelectionResult.Ambiguous].
 *
 * The result is always a **physical segment id** — the same value Phase 6
 * persists and Phase 4 excludes. It is never a node id.
 */
class RoadSelectionAdapter(
    private val graph: RoutingGraph,
    private val spatialIndex: SegmentSpatialIndex,
    private val ambiguityMarginPixels: Double = DEFAULT_AMBIGUITY_MARGIN_PIXELS,
) {

    suspend fun select(
        touch: GeoCoordinate,
        touchX: Double,
        touchY: Double,
        maximumPixelDistance: Double,
        metersPerPixel: Double,
        project: (GeoCoordinate) -> ScreenPoint,
    ): RoadSelectionResult {
        if (maximumPixelDistance <= 0.0 || metersPerPixel <= 0.0) return RoadSelectionResult.None

        val searchRadiusMeters = maximumPixelDistance * metersPerPixel * SEARCH_RADIUS_MARGIN
        val candidates = spatialIndex.nearbySegments(touch, searchRadiusMeters, CANDIDATE_LIMIT)
        if (candidates.isEmpty()) return RoadSelectionResult.None

        val scored = ArrayList<Scored>(candidates.size)
        for (candidate in candidates) {
            scored.add(score(candidate, touchX, touchY, project))
        }
        scored.sortWith(compareBy({ it.pixelDistance }, { it.segmentId.value }))

        val best = scored.first()
        if (best.pixelDistance > maximumPixelDistance) return RoadSelectionResult.None

        val withinMargin = scored.drop(1).filter {
            it.pixelDistance - best.pixelDistance <= ambiguityMarginPixels
        }
        if (withinMargin.isNotEmpty()) {
            return RoadSelectionResult.Ambiguous(
                (listOf(best) + withinMargin).map { it.segmentId }.distinct(),
            )
        }

        return RoadSelectionResult.Selected(
            segmentId = best.segmentId,
            pixelDistance = best.pixelDistance,
            geographicPoint = best.geographicPoint,
        )
    }

    private fun score(
        hit: SegmentHit,
        touchX: Double,
        touchY: Double,
        project: (GeoCoordinate) -> ScreenPoint,
    ): Scored {
        val segment = hit.segmentId.value
        val pointCount = graph.geometryPointCount(segment)

        if (pointCount == 1) {
            val point = project(GeoCoordinate(graph.geometryLatitude(segment, 0), graph.geometryLongitude(segment, 0)))
            val dx = point.x - touchX
            val dy = point.y - touchY
            // Must be the distance, not its square: callers compare it against a
            // pixel tolerance and against other candidates' distances.
            return Scored(hit.segmentId, kotlin.math.hypot(dx, dy), hit.nearestPoint)
        }

        var bestSquared = Double.MAX_VALUE
        var bestLatitude = hit.nearestPoint.latitude
        var bestLongitude = hit.nearestPoint.longitude

        var index = 0
        var ax = 0.0
        var ay = 0.0
        var aLatitude = 0.0
        var aLongitude = 0.0
        while (index < pointCount) {
            val latitude = graph.geometryLatitude(segment, index)
            val longitude = graph.geometryLongitude(segment, index)
            val projected = project(GeoCoordinate(latitude, longitude))
            if (index > 0) {
                val abx = projected.x - ax
                val aby = projected.y - ay
                val lengthSquared = abx * abx + aby * aby
                val t = if (lengthSquared <= 1e-12) {
                    0.0
                } else {
                    (((touchX - ax) * abx + (touchY - ay) * aby) / lengthSquared).coerceIn(0.0, 1.0)
                }
                val closestX = ax + t * abx
                val closestY = ay + t * aby
                val dx = closestX - touchX
                val dy = closestY - touchY
                val squared = dx * dx + dy * dy
                if (squared < bestSquared) {
                    bestSquared = squared
                    bestLatitude = aLatitude + t * (latitude - aLatitude)
                    bestLongitude = aLongitude + t * (longitude - aLongitude)
                }
            }
            ax = projected.x
            ay = projected.y
            aLatitude = latitude
            aLongitude = longitude
            index++
        }

        val pixelDistance = if (bestSquared == Double.MAX_VALUE) {
            val first = project(GeoCoordinate(graph.geometryLatitude(segment, 0), graph.geometryLongitude(segment, 0)))
            kotlin.math.hypot(first.x - touchX, first.y - touchY)
        } else {
            kotlin.math.sqrt(bestSquared)
        }
        return Scored(
            segmentId = hit.segmentId,
            pixelDistance = pixelDistance,
            geographicPoint = GeoCoordinate(bestLatitude, bestLongitude),
        )
    }

    private data class Scored(
        val segmentId: SegmentId,
        val pixelDistance: Double,
        val geographicPoint: GeoCoordinate,
    )

    companion object {
        /** Cap on candidates refined per long-press. */
        const val CANDIDATE_LIMIT = 24

        /** The geographic search box is enlarged to absorb projection error. */
        const val SEARCH_RADIUS_MARGIN = 1.6

        /** Two candidates closer than this are treated as ambiguous. */
        const val DEFAULT_AMBIGUITY_MARGIN_PIXELS = 6.0
    }
}
