package com.resqroute.dataset

import com.resqroute.core.graph.RoutingGraph
import com.resqroute.core.graph.SegmentHit
import com.resqroute.core.graph.SegmentSpatialIndex
import com.resqroute.core.graph.SegmentViewport
import com.resqroute.core.model.GeoCoordinate
import com.resqroute.core.model.NodeId
import com.resqroute.core.model.SegmentId
import com.resqroute.core.routing.AStarRoutingEngine
import com.resqroute.core.routing.RouteRequest
import com.resqroute.core.routing.RouteResult
import com.resqroute.core.routing.RoutingPolicy
import com.resqroute.core.routing.RoutingSnapshot
import com.resqroute.domain.hazard.HazardContext
import com.resqroute.domain.hazard.HazardSnapshot
import com.resqroute.feature.map.ui.RoadSelectionAdapter
import com.resqroute.feature.map.ui.RoadSelectionResult
import com.resqroute.feature.map.ui.ScreenPoint
import kotlin.math.cos
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Long-press selection: screen-space refinement, ambiguity protection and
 * multi-zoom behaviour (Phase 5 acceptance criteria 2 and 6).
 *
 * Uses a hand-computed parallel-road graph and a fake projector so the pixel
 * arithmetic is exact and deterministic.
 */
class RoadSelectionAdapterTest {

    private val originLatitude = 23.0000
    private val originLongitude = 91.4005

    private fun parallelRoads(): RoutingGraph {
        val nodes = listOf(
            NodeSpec(1, 23.00000, 91.4000), // A
            NodeSpec(2, 23.00000, 91.4010), // B
        )
        val segments = listOf(
            SegmentSpec(0, 1, 102.0, listOf(23.00000 to 91.4000, 23.00000 to 91.4010), name = "south road"),
            SegmentSpec(0, 1, 102.0, listOf(23.00020 to 91.4000, 23.00020 to 91.4010), name = "north road"),
        )
        return buildTestGraph(nodes, segments)
    }

    private fun projector(graph: RoutingGraph, pixelsPerMeter: Double): (GeoCoordinate) -> ScreenPoint {
        val metersPerLat = 111320.0
        val metersPerLon = 111320.0 * cos(Math.toRadians(originLatitude))
        return { coordinate ->
            ScreenPoint(
                x = (coordinate.longitude - originLongitude) * metersPerLon * pixelsPerMeter,
                y = -(coordinate.latitude - originLatitude) * metersPerLat * pixelsPerMeter,
            )
        }
    }

    private class FakeSegmentIndex(private val hits: List<SegmentHit>) : SegmentSpatialIndex {
        override suspend fun nearestSegment(
            coordinate: GeoCoordinate,
            maximumDistanceMeters: Double,
        ): SegmentHit? = hits.filter { it.distanceMeters <= maximumDistanceMeters }
            .minByOrNull { it.distanceMeters }

        override suspend fun nearbySegments(
            coordinate: GeoCoordinate,
            maximumDistanceMeters: Double,
            limit: Int,
        ): List<SegmentHit> = hits.filter { it.distanceMeters <= maximumDistanceMeters }
            .sortedBy { it.distanceMeters }
            .take(limit)
    }

    private fun hits(): List<SegmentHit> = listOf(
        SegmentHit(SegmentId(0), GeoCoordinate(originLatitude, originLongitude), 0.0),
        SegmentHit(SegmentId(1), GeoCoordinate(originLatitude + 0.0002, originLongitude), 22.2),
    )

    private fun adapter(graph: RoutingGraph) = RoadSelectionAdapter(graph, FakeSegmentIndex(hits()))

    private fun touch() = GeoCoordinate(originLatitude, originLongitude)

    @Test
    fun selectsTheClosestSegmentWhenClear() = runBlocking {
        val graph = parallelRoads()
        val result = adapter(graph).select(
            touch = touch(),
            touchX = 0.0,
            touchY = 0.0,
            maximumPixelDistance = 28.0,
            metersPerPixel = 1.0,
            project = projector(graph, pixelsPerMeter = 1.0),
        )
        assertTrue("expected Selected but was $result", result is RoadSelectionResult.Selected)
        result as RoadSelectionResult.Selected
        assertEquals(SegmentId(0), result.segmentId)
        assertTrue(result.pixelDistance < 0.01)
    }

    @Test
    fun theSelectedSegmentIsExactlyTheOneTheEngineExcludes() = runBlocking {
        val graph = parallelRoads()
        val selected = adapter(graph).select(
            touch = touch(),
            touchX = 0.0,
            touchY = 0.0,
            maximumPixelDistance = 28.0,
            metersPerPixel = 1.0,
            project = projector(graph, pixelsPerMeter = 1.0),
        ) as RoadSelectionResult.Selected

        // One stable identity for the whole chain: what the user long-pressed,
        // what would be persisted, what is drawn, and what the router excludes.
        assertEquals(graph.segmentStableId(selected.segmentId.value), selected.segmentId.stableId)
        val bounds = SegmentViewport.boundsOf(graph, selected.segmentId.value)!!
        for (point in 0 until graph.geometryPointCount(selected.segmentId.value)) {
            assertTrue(
                "rendered geometry point $point escapes the selected segment",
                bounds.contains(
                    graph.geometryLatitude(selected.segmentId.value, point),
                    graph.geometryLongitude(selected.segmentId.value, point),
                ),
            )
        }

        // Committing a block on that id makes the real engine return the other
        // physical road — the visual mark and the routing constraint agree.
        val context = HazardContext(graph.identity, "incident")
        val blocker = HazardSnapshot(context, revision = 1, blockedSegments = setOf(selected.segmentId))
        val result = AStarRoutingEngine().findRoute(
            RoutingSnapshot(
                dataset = graph.identity,
                incidentId = "incident",
                hazardRevision = 1,
                graph = graph,
                blocker = blocker,
            ),
            RouteRequest(1, NodeId(1), NodeId(2), RoutingPolicy.DISTANCE_ONLY),
        )
        assertTrue("expected a route, got $result", result is RouteResult.Found)
        result as RouteResult.Found
        assertFalse(result.path.segmentIds.contains(selected.segmentId))
        assertEquals(1, result.path.segmentIds.size)
    }

    @Test
    fun selectsTheSamePhysicalSegmentAtMultipleZoomLevels() = runBlocking {
        val graph = parallelRoads()
        for (pixelsPerMeter in listOf(0.5, 1.0, 2.0, 4.0)) {
            val result = adapter(graph).select(
                touch = touch(),
                touchX = 0.0,
                touchY = 0.0,
                maximumPixelDistance = 28.0,
                metersPerPixel = 1.0 / pixelsPerMeter,
                project = projector(graph, pixelsPerMeter),
            )
            assertTrue("zoom $pixelsPerMeter px/m gave $result", result is RoadSelectionResult.Selected)
            assertEquals(SegmentId(0), (result as RoadSelectionResult.Selected).segmentId)
        }
    }

    @Test
    fun parallelRoadsAtLowZoomAreReportedAmbiguousNotSilentlyPicked() = runBlocking {
        val graph = parallelRoads()
        val result = adapter(graph).select(
            touch = touch(),
            touchX = 0.0,
            touchY = 0.0,
            maximumPixelDistance = 28.0,
            metersPerPixel = 10.0, // 0.1 px per meter: the two roads are ~2 px apart
            project = projector(graph, pixelsPerMeter = 0.1),
        )
        assertTrue("expected Ambiguous but was $result", result is RoadSelectionResult.Ambiguous)
        result as RoadSelectionResult.Ambiguous
        assertTrue(result.candidates.contains(SegmentId(0)))
        assertTrue(result.candidates.contains(SegmentId(1)))
    }

    @Test
    fun nothingNearTheTouchReturnsNone() = runBlocking {
        val graph = parallelRoads()
        val result = adapter(graph).select(
            touch = GeoCoordinate(originLatitude + 0.01, originLongitude),
            touchX = 0.0,
            touchY = -1113.0, // ~1 km away at 1 px per meter
            maximumPixelDistance = 28.0,
            metersPerPixel = 1.0,
            project = projector(graph, pixelsPerMeter = 1.0),
        )
        assertEquals(RoadSelectionResult.None, result)
    }

    @Test
    fun emptyCandidateIndexReturnsNone() = runBlocking {
        val graph = parallelRoads()
        val empty = RoadSelectionAdapter(graph, FakeSegmentIndex(emptyList()))
        val result = empty.select(
            touch = touch(),
            touchX = 0.0,
            touchY = 0.0,
            maximumPixelDistance = 28.0,
            metersPerPixel = 1.0,
            project = projector(graph, pixelsPerMeter = 1.0),
        )
        assertEquals(RoadSelectionResult.None, result)
    }

    @Test
    fun zeroToleranceOrZoomIsRejected() = runBlocking {
        val graph = parallelRoads()
        val selection = adapter(graph)
        assertEquals(
            RoadSelectionResult.None,
            selection.select(touch(), 0.0, 0.0, 0.0, 1.0, projector(graph, 1.0)),
        )
        assertEquals(
            RoadSelectionResult.None,
            selection.select(touch(), 0.0, 0.0, 28.0, 0.0, projector(graph, 1.0)),
        )
    }
}
