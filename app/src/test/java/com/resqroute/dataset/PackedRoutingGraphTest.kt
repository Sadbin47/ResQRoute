package com.resqroute.dataset

import com.resqroute.core.graph.GraphBlocker
import com.resqroute.core.graph.SegmentTraversal
import com.resqroute.core.model.DatasetIdentity
import com.resqroute.core.model.GeoCoordinate
import com.resqroute.core.model.NodeId
import com.resqroute.core.model.SegmentId
import com.resqroute.data.dataset.BundledDatasetReader
import com.resqroute.data.dataset.PackedDataset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the published graph against the real Phase 2 artifact.
 *
 * Covers Phase 3 acceptance criteria: immutable publication, CSR adjacency,
 * segment↔edge mapping, shelter/access-node resolution, spatial index, and the
 * "elevation unavailable, never zero" rule.
 */
class PackedRoutingGraphTest {

    private fun load(): PackedDataset {
        val manifest = TestDatasets.manifest
        return BundledDatasetReader.parse(
            manifest = manifest,
            bin = TestDatasets.datasetBytes,
            identity = DatasetIdentity(manifest.contentSha256, manifest.formatVersion),
            ioMillis = 0,
            digestMillis = 0,
        )
    }

    @Test
    fun publishesCountsMatchingManifest() {
        val manifest = TestDatasets.manifest
        val graph = load().graph
        assertEquals(manifest.nodeCount, graph.nodeCount)
        assertEquals(manifest.edgeCount, graph.edgeCount)
        assertEquals(manifest.segmentCount, graph.segmentCount)
        assertEquals(manifest.shelterCount, graph.shelterCount)
        assertEquals(manifest.contentSha256, graph.identity.contentHash)
        assertEquals(manifest.formatVersion, graph.identity.formatVersion)
    }

    @Test
    fun nodeLookupRoundTripsIncludingBinarySearchEdges() {
        val graph = load().graph
        val samples = intArrayOf(0, 1, graph.nodeCount / 2, graph.nodeCount - 1)
        for (index in samples) {
            assertEquals(index, graph.nodeIndex(graph.nodeId(index)))
        }
        assertNull(graph.nodeIndex(NodeId(Long.MAX_VALUE)))
    }

    @Test
    fun csrAdjacencyIsInternallyConsistent() {
        val graph = load().graph
        var visitedEdges = 0
        for (node in 0 until graph.nodeCount) {
            val begin = graph.outgoingBegin(node)
            val end = graph.outgoingEnd(node)
            assertTrue(begin <= end)
            for (slot in begin until end) {
                val edge = graph.outgoingEdgeIndex(slot)
                assertEquals(node, graph.edgeFrom(edge))
                assertTrue(graph.edgeTo(edge) in 0 until graph.nodeCount)
                assertTrue(graph.edgeCostMeters(edge) > 0.0)
                visitedEdges++
            }
        }
        assertEquals(graph.edgeCount, visitedEdges)
    }

    @Test
    fun segmentDirectionFlagsAgreeWithStoredEdges() {
        val graph = load().graph
        val edgesPerSegment = IntArray(graph.segmentCount)
        for (edge in 0 until graph.edgeCount) edgesPerSegment[graph.edgeSegmentIndex(edge)]++

        for (segment in 0 until graph.segmentCount) {
            val traversal = SegmentTraversal.of(graph.segmentFlags(segment))
            val directionCount = when (traversal) {
                SegmentTraversal.FORWARD, SegmentTraversal.BACKWARD -> 1
                SegmentTraversal.BOTH -> 2
                SegmentTraversal.NONE -> 0
            }
            // Excluded segments keep their oneway bits but own no directed
            // edges, so the eligible flag is the authority for routability.
            val eligible = graph.isSegmentEligible(segment)
            val expected = if (eligible) directionCount else 0
            assertEquals(expected, edgesPerSegment[segment])
            if (eligible) assertTrue(directionCount > 0)
        }
    }

    @Test
    fun blockingASegmentCoversEveryDirectedEdgeItOwns() {
        val graph = load().graph
        val segment = (0 until graph.segmentCount).first { graph.isSegmentEligible(it) }
        val blocker = object : GraphBlocker {
            override fun isBlockedSegment(segmentIndex: Int): Boolean = segmentIndex == segment
        }
        assertTrue(blocker.isBlockedSegment(segment))

        var ownedEdges = 0
        for (edge in 0 until graph.edgeCount) {
            if (graph.edgeSegmentIndex(edge) == segment) {
                ownedEdges++
                assertEquals(segment, graph.edgeSegmentId(edge).value)
                assertTrue(blocker.isBlockedSegment(graph.edgeSegmentIndex(edge)))
            }
        }
        assertTrue(ownedEdges > 0)
    }

    @Test
    fun geometrySlicesAreBoundedAndMatchEndpointNodes() {
        val graph = load().graph
        val segment = (0 until graph.segmentCount).first { graph.isSegmentEligible(it) }
        val points = ArrayList<GeoCoordinate>()
        graph.copyGeometry(segment, points)

        assertEquals(graph.geometryPointCount(segment), points.size)
        assertTrue(points.size >= 2)

        val endpoints = HashSet<Long>()
        for (edge in 0 until graph.edgeCount) {
            if (graph.edgeSegmentIndex(edge) == segment) {
                endpoints.add(key(graph.latitude(graph.edgeFrom(edge)), graph.longitude(graph.edgeFrom(edge))))
                endpoints.add(key(graph.latitude(graph.edgeTo(edge)), graph.longitude(graph.edgeTo(edge))))
            }
        }
        assertTrue(endpoints.contains(key(points.first().latitude, points.first().longitude)))
        assertTrue(endpoints.contains(key(points.last().latitude, points.last().longitude)))
    }

    @Test
    fun sheltersResolveToValidAccessNodes() {
        val bundle = load()
        val graph = bundle.graph
        val bbox = TestDatasets.manifest.bboxSouthWestNorthEast
        assertEquals(TestDatasets.manifest.shelterCount, bundle.shelters.size)

        for (shelter in bundle.shelters) {
            assertTrue(shelter.accessNodeIndex in 0 until graph.nodeCount)
            assertEquals(shelter.accessNodeIndex, graph.nodeIndex(shelter.accessNodeId))
            assertTrue(shelter.name.isNotBlank())
            assertTrue(shelter.coordinate.latitude in bbox[0]..bbox[2])
            assertTrue(shelter.coordinate.longitude in bbox[1]..bbox[3])
        }
    }

    @Test
    fun elevationIsUnavailableAndNeverZeroOrSafe() {
        val graph = load().graph
        assertFalse(graph.hasElevation)
        assertNull(graph.elevationSource)
        for (node in intArrayOf(0, graph.nodeCount / 2, graph.nodeCount - 1)) {
            assertNull(graph.elevationMeters(node))
        }
    }

    @Test
    fun spatialIndexFindsTheShelterAndRefusesDistantQueries() = runBlocking {
        val bundle = load()
        val shelter = bundle.shelters.first()
        val hit = bundle.spatialIndex.nearestSegment(shelter.coordinate, 300.0)
        assertNotNull(hit)
        assertTrue(hit!!.distanceMeters <= 300.0)

        // A point on the other side of the planet must not silently snap to Feni.
        assertNull(bundle.spatialIndex.nearestSegment(GeoCoordinate(0.0, 0.0), 10.0))
        // A zero/negative radius is refused rather than treated as "anywhere".
        assertNull(bundle.spatialIndex.nearestSegment(shelter.coordinate, 0.0))
    }

    @Test
    fun segmentStableIdsMatchTheFrozenSequence() {
        assertEquals("S000001", SegmentId(0).stableId)
        assertEquals("S039923", SegmentId(39922).stableId)
        val graph = load().graph
        assertEquals("S000001", graph.segmentStableId(0))
        assertEquals("S039923", graph.segmentStableId(graph.segmentCount - 1))
    }

    private fun key(latitude: Double, longitude: Double): Long =
        (Math.round(latitude * 1e7) shl 32) xor Math.round(longitude * 1e7)
}
