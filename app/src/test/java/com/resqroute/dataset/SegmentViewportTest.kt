package com.resqroute.dataset

import com.resqroute.core.graph.GeoBox
import com.resqroute.core.graph.RoutingGraph
import com.resqroute.core.graph.SegmentViewport
import com.resqroute.core.model.SegmentId
import com.resqroute.core.routing.RoutePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Viewport culling and geometry consistency (requirements: "geometry
 * consistency", "viewport clipping").
 *
 * The culling rule is deliberately conservative (segment bounds vs viewport
 * bounds) and **read-only**: it can decide that a segment contributes nothing to
 * the current frame, but it must never alter the graph, the route or any
 * coordinate.
 */
class SegmentViewportTest {

    private val latA = 23.0000
    private val latB = 23.5000
    private val lonA = 91.4000
    private val lonB = 91.9000

    private fun graph(): RoutingGraph {
        val nodes = listOf(
            NodeSpec(1, 23.0000, 91.4000),
            NodeSpec(2, 23.0000, 91.4010),
            NodeSpec(3, 23.0000, 91.4020),
            NodeSpec(4, 23.5000, 91.9000), // far corner
        )
        val segments = listOf(
            SegmentSpec(0, 1, 100.0, listOf(23.0000 to 91.4000, 23.0000 to 91.4010), name = "a"),
            SegmentSpec(1, 2, 100.0, listOf(23.0000 to 91.4010, 23.0000 to 91.4020), name = "b"),
            SegmentSpec(1, 3, 100.0, listOf(23.0000 to 91.4010, 23.5000 to 91.9000), name = "far"),
        )
        return buildTestGraph(nodes, segments)
    }

    /** A viewport that covers only the first two segments. */
    private fun nearViewport() = GeoBox(south = 22.9995, west = 91.3995, north = 23.0005, east = 91.4025)

    @Test
    fun boundsMatchTheSegmentsOwnStoredGeometry() {
        val graph = graph()
        val bounds = SegmentViewport.boundsOf(graph, 0)
        assertNotNull(bounds)
        bounds!!
        assertEquals(latA, bounds.south, 1e-9)
        assertEquals(latA, bounds.north, 1e-9)
        assertEquals(91.4000, bounds.west, 1e-9)
        assertEquals(91.4010, bounds.east, 1e-9)
        assertTrue(bounds.contains(23.0000, 91.4005))
        assertFalse(bounds.contains(23.1000, 91.4005))
    }

    @Test
    fun everyGeometryPointStaysInsideItsSegmentsBounds() {
        val graph = graph()
        for (segment in 0 until graph.segmentCount) {
            val bounds = SegmentViewport.boundsOf(graph, segment)!!
            for (point in 0 until graph.geometryPointCount(segment)) {
                assertTrue(
                    "segment $segment point $point latitude out of bounds",
                    bounds.contains(graph.geometryLatitude(segment, point), graph.geometryLongitude(segment, point)),
                )
            }
        }
    }

    @Test
    fun coordinateAxesAreNotSwapped() {
        // A latitude/longitude swap would put points at (91.4, 23.0), which is
        // outside the dataset extent (and outside legal latitude).
        val graph = graph()
        for (segment in 0 until graph.segmentCount) {
            for (point in 0 until graph.geometryPointCount(segment)) {
                val latitude = graph.geometryLatitude(segment, point)
                val longitude = graph.geometryLongitude(segment, point)
                assertTrue("latitude $latitude on segment $segment", latitude in latA..latB)
                assertTrue("longitude $longitude on segment $segment", longitude in lonA..lonB)
            }
        }
    }

    @Test
    fun segmentEntirelyOutsideTheViewportIsCulled() {
        val graph = graph()
        // Segment 0 is at 91.40; this viewport is 200 km west of it.
        val elsewhere = GeoBox(south = 22.0, west = 91.0, north = 22.5, east = 91.2)
        assertFalse(SegmentViewport.isVisible(graph, 0, elsewhere))
        assertFalse(SegmentViewport.isVisible(graph, 1, elsewhere))
    }

    @Test
    fun segmentInsideTheViewportIsKept() {
        val graph = graph()
        assertTrue(SegmentViewport.isVisible(graph, 0, nearViewport()))
        assertTrue(SegmentViewport.isVisible(graph, 1, nearViewport()))
    }

    @Test
    fun segmentIsKeptWhenOnlyItsEdgeTouchesTheViewport() {
        val graph = graph()
        // The viewport ends exactly on segment 1's east edge: touching counts,
        // otherwise the last visible pixel of a road would flicker away.
        val touching = GeoBox(south = 22.9995, west = 91.3995, north = 23.0005, east = 91.4020)
        assertTrue(SegmentViewport.isVisible(graph, 1, touching))
    }

    @Test
    fun aSegmentSpanningPastTheViewportIsStillKept() {
        // Culling is by bounds: the far segment starts inside the viewport, so it
        // is kept whole and the canvas clips it. Its geometry is never shortened.
        val graph = graph()
        assertTrue(SegmentViewport.isVisible(graph, 2, nearViewport()))
    }

    @Test
    fun unionBoundsCoverEveryListedSegment() {
        val graph = graph()
        val union = SegmentViewport.boundsOf(graph, listOf(0, 1))!!
        assertEquals(latA, union.south, 1e-9)
        assertEquals(latA, union.north, 1e-9)
        assertEquals(91.4000, union.west, 1e-9)
        assertEquals(91.4020, union.east, 1e-9)
        assertNull(SegmentViewport.boundsOf(graph, emptyList<Int>()))
    }

    @Test
    fun cullingNeverMutatesTheRouteGeometry() {
        val graph = graph()
        val path = RoutePath(
            dataset = graph.identity,
            incidentId = "incident",
            requestGeneration = 1,
            hazardRevision = 0,
            nodeIds = listOf(graph.nodeId(0), graph.nodeId(1), graph.nodeId(2)),
            edgeIds = emptyList(),
            segmentIds = listOf(SegmentId(0), SegmentId(1), SegmentId(2)),
            distanceMeters = 300.0,
            totalCost = 300.0,
        )
        val before = path.copy()

        // Decide visibility for every route segment against a camera that only
        // covers the first segment; the other two lie fully outside it.
        val viewport = GeoBox(south = 22.9995, west = 91.3995, north = 23.0005, east = 91.4005)
        val visible = path.segmentIds.filter { SegmentViewport.isVisible(graph, it.value, viewport) }

        // Culling is a read-only query: the route itself is bit-for-bit identical
        // and the off-screen segments are filtered out of the draw set, not deleted.
        assertEquals(before, path)
        assertEquals(listOf(SegmentId(0)), visible)
        assertEquals(3, path.segmentIds.size)
        assertTrue(path.segmentIds.any { it == SegmentId(1) })
        assertTrue(path.segmentIds.any { it == SegmentId(2) })
    }
}
