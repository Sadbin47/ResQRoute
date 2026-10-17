package com.resqroute.dataset

import com.resqroute.core.graph.GraphBlocker
import com.resqroute.core.model.NodeId
import com.resqroute.core.model.SegmentId
import com.resqroute.core.routing.AStarRoutingEngine
import com.resqroute.core.routing.RouteEndpoint
import com.resqroute.core.routing.RoutePath
import com.resqroute.core.routing.RouteRequest
import com.resqroute.core.routing.RouteResult
import com.resqroute.core.routing.RoutingPolicy
import com.resqroute.core.routing.RoutingSnapshot
import com.resqroute.domain.hazard.HazardContext
import com.resqroute.domain.hazard.HazardSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic engine behaviour on hand-computed graphs. */
class AStarRoutingEngineTest {

    private val engine = AStarRoutingEngine()

    private fun diamond(): com.resqroute.core.graph.RoutingGraph {
        val nodes = listOf(
            NodeSpec(100, 23.0000, 91.4000), // S
            NodeSpec(101, 23.0010, 91.4005), // A
            NodeSpec(102, 23.0010, 91.3995), // B
            NodeSpec(103, 23.0020, 91.4000), // D
            NodeSpec(104, 23.0100, 91.4100), // isolated, unreachable
        )
        val segments = listOf(
            SegmentSpec(0, 1, 100.0, coords(nodes, 0, 1), name = "cheap"),
            SegmentSpec(1, 3, 100.0, coords(nodes, 1, 3), name = "cheap"),
            SegmentSpec(0, 2, 120.0, coords(nodes, 0, 2), name = "alt"),
            SegmentSpec(2, 3, 120.0, coords(nodes, 2, 3), name = "alt"),
        )
        return buildTestGraph(nodes, segments)
    }

    private fun coords(nodes: List<NodeSpec>, from: Int, to: Int) =
        listOf(nodes[from].latitude to nodes[from].longitude, nodes[to].latitude to nodes[to].longitude)

    private fun snapshot(graph: com.resqroute.core.graph.RoutingGraph, blocker: GraphBlocker = GraphBlocker.NONE) =
        RoutingSnapshot(
            dataset = graph.identity,
            incidentId = "test-incident",
            hazardRevision = 7,
            graph = graph,
            blocker = blocker,
        )

    private fun request(start: Long, destination: Long, policy: RoutingPolicy = RoutingPolicy.DISTANCE_ONLY) =
        RouteRequest(1, NodeId(start), NodeId(destination), policy)

    private fun assertValidPath(
        graph: com.resqroute.core.graph.RoutingGraph,
        blocker: GraphBlocker,
        start: Long,
        destination: Long,
        path: RoutePath,
    ) {
        assertEquals(NodeId(start), path.nodeIds.first())
        assertEquals(NodeId(destination), path.nodeIds.last())
        assertEquals(path.edgeIds.size + 1, path.nodeIds.size)
        assertEquals(path.edgeIds.size, path.segmentIds.size)
        for (index in path.edgeIds.indices) {
            val edge = path.edgeIds[index].value
            assertEquals(graph.nodeIndex(path.nodeIds[index]), graph.edgeFrom(edge))
            assertEquals(graph.nodeIndex(path.nodeIds[index + 1]), graph.edgeTo(edge))
            assertFalse(blocker.isBlockedSegment(graph.edgeSegmentIndex(edge)))
            assertEquals(graph.edgeSegmentId(edge), path.segmentIds[index])
        }
    }

    @Test
    fun matchesDijkstraForUnblockedAndBlockedGraphs() = runBlocking {
        val graph = diamond()
        val cases = listOf(GraphBlocker.NONE, blockerFor(0), blockerFor(3), blockerFor(0, 3))
        for (blocker in cases) {
            val result = engine.findRoute(snapshot(graph, blocker), request(100, 103))
            val expected = dijkstraCost(graph, NodeId(100), NodeId(103), blocker, RoutingPolicy.DISTANCE_ONLY)
            if (expected == null) {
                assertEquals(RouteResult.NoRoute, result)
            } else {
                assertTrue("expected Found for $blocker but was $result", result is RouteResult.Found)
                result as RouteResult.Found
                assertEquals(expected, result.path.totalCost, 1e-9)
                assertValidPath(graph, blocker, 100, 103, result.path)
            }
        }
    }

    @Test
    fun blockedSegmentIsNeverTraversedAndDetourIsChosen() = runBlocking {
        val graph = diamond()
        val blocked = blockerFor(0) // cut the cheap S->A segment
        val result = engine.findRoute(snapshot(graph, blocked), request(100, 103))
        assertTrue(result is RouteResult.Found)
        result as RouteResult.Found

        assertEquals(240.0, result.path.totalCost, 1e-9)
        assertValidPath(graph, blocked, 100, 103, result.path)
        assertTrue(result.path.segmentIds.none { it.value == 0 })
    }

    @Test
    fun aNewerSnapshotWithoutTheBlockRestoresTheOptimalDirectRoute() = runBlocking {
        val graph = diamond()
        val context = HazardContext(graph.identity, "test-incident")
        val blocked = HazardSnapshot(context, revision = 1, blockedSegments = setOf(SegmentId(0)))

        val blockedResult = engine.findRoute(snapshot(graph, blocked), request(100, 103))
        assertTrue(blockedResult is RouteResult.Found)
        blockedResult as RouteResult.Found
        assertEquals(240.0, blockedResult.path.totalCost, 1e-9)
        assertTrue(blockedResult.path.segmentIds.none { it.value == 0 })

        // The committed set is a revisioned value: a block stops applying when a
        // newer snapshot no longer contains the segment (the packed event log
        // itself is append-only, so this is how "removed" is expressed). The
        // engine must then use the cheaper edge again.
        val cleared = HazardSnapshot(context, revision = 2, blockedSegments = emptySet())
        val clearedResult = engine.findRoute(snapshot(graph, cleared), request(100, 103))
        assertTrue(clearedResult is RouteResult.Found)
        clearedResult as RouteResult.Found
        assertEquals(200.0, clearedResult.path.totalCost, 1e-9)
        assertTrue(clearedResult.path.segmentIds.any { it.value == 0 })
        assertTrue(clearedResult.path.totalCost < blockedResult.path.totalCost)
    }

    @Test
    fun weightedPolicyChoosesTheLowerCostRoute() = runBlocking {
        val graph = diamond()
        // Add 60 m of penalty to the cheap route's first segment: 200+60 > 240.
        val policy = RoutingPolicy(segmentPenaltyMeters = mapOf(SegmentId(0) to 60.0))
        val result = engine.findRoute(snapshot(graph), request(100, 103, policy))
        assertTrue(result is RouteResult.Found)
        result as RouteResult.Found

        assertEquals(240.0, result.path.totalCost, 1e-9)
        assertEquals(240.0, result.path.distanceMeters, 1e-9)
        assertValidPath(graph, GraphBlocker.NONE, 100, 103, result.path)
    }

    @Test
    fun oneWaySegmentsAreObeyed() = runBlocking {
        val nodes = listOf(NodeSpec(1, 23.0, 91.4), NodeSpec(2, 23.001, 91.401))
        val graph = buildTestGraph(
            nodes,
            listOf(SegmentSpec(0, 1, 50.0, coords(nodes, 0, 1), forward = true, backward = false)),
        )

        val forward = engine.findRoute(snapshot(graph), request(1, 2))
        assertTrue(forward is RouteResult.Found)

        val reverse = engine.findRoute(snapshot(graph), request(2, 1))
        assertEquals(RouteResult.NoRoute, reverse)
    }

    @Test
    fun parallelRoadsReconstructTheCorrectEdge() = runBlocking {
        val nodes = listOf(NodeSpec(1, 23.0, 91.4), NodeSpec(2, 23.001, 91.401))
        val graph = buildTestGraph(
            nodes,
            listOf(
                SegmentSpec(0, 1, 100.0, coords(nodes, 0, 1), name = "first"),
                SegmentSpec(0, 1, 150.0, coords(nodes, 0, 1), name = "second"),
            ),
        )

        val cheapest = engine.findRoute(snapshot(graph), request(1, 2))
        assertTrue(cheapest is RouteResult.Found)
        cheapest as RouteResult.Found
        assertEquals(SegmentId(0), cheapest.path.segmentIds.single())

        val blocked = blockerFor(0)
        val alternative = engine.findRoute(snapshot(graph, blocked), request(1, 2))
        assertTrue(alternative is RouteResult.Found)
        alternative as RouteResult.Found
        assertEquals(SegmentId(1), alternative.path.segmentIds.single())
        assertEquals(150.0, alternative.path.totalCost, 1e-9)
        assertValidPath(graph, blocked, 1, 2, alternative.path)
    }

    @Test
    fun startEqualsDestinationReturnsAZeroLengthPath() = runBlocking {
        val graph = diamond()
        val result = engine.findRoute(snapshot(graph), request(100, 100))
        assertTrue(result is RouteResult.Found)
        result as RouteResult.Found
        assertEquals(listOf(NodeId(100)), result.path.nodeIds)
        assertTrue(result.path.edgeIds.isEmpty())
        assertTrue(result.path.segmentIds.isEmpty())
        assertEquals(0.0, result.path.totalCost, 0.0)
        assertEquals(0.0, result.path.distanceMeters, 0.0)
    }

    @Test
    fun invalidEndpointsAreReportedPrecisely() = runBlocking {
        val graph = diamond()
        val missing = engine.findRoute(snapshot(graph), request(999_999, 103))
        assertEquals(RouteResult.InvalidEndpoint(RouteEndpoint.START), missing)

        val missingDestination = engine.findRoute(snapshot(graph), request(100, 999_999))
        assertEquals(RouteResult.InvalidEndpoint(RouteEndpoint.DESTINATION), missingDestination)
    }

    @Test
    fun disconnectedDestinationReturnsNoRoute() = runBlocking {
        val graph = diamond()
        // Node 104 exists but owns no edges: reachable endpoints give NoRoute,
        // while a completely unknown id gives InvalidEndpoint.
        val result = engine.findRoute(snapshot(graph), request(100, 104))
        assertEquals(RouteResult.NoRoute, result)
        assertEquals(
            RouteResult.InvalidEndpoint(RouteEndpoint.DESTINATION),
            engine.findRoute(snapshot(graph), request(100, 999_999)),
        )
    }

    @Test
    fun equalCostRoutesAreDeterministic() = runBlocking {
        val nodes = listOf(
            NodeSpec(1, 23.000, 91.400),
            NodeSpec(2, 23.001, 91.401),
            NodeSpec(3, 23.001, 91.399),
            NodeSpec(4, 23.002, 91.400),
        )
        val graph = buildTestGraph(
            nodes,
            listOf(
                SegmentSpec(0, 1, 50.0, coords(nodes, 0, 1)),
                SegmentSpec(1, 3, 50.0, coords(nodes, 1, 3)),
                SegmentSpec(0, 2, 50.0, coords(nodes, 0, 2)),
                SegmentSpec(2, 3, 50.0, coords(nodes, 2, 3)),
            ),
        )
        val first = engine.findRoute(snapshot(graph), request(1, 4)) as RouteResult.Found
        val second = engine.findRoute(snapshot(graph), request(1, 4)) as RouteResult.Found
        assertEquals(first.path.nodeIds, second.path.nodeIds)
        assertEquals(first.path.edgeIds, second.path.edgeIds)
        assertEquals(first.path.segmentIds, second.path.segmentIds)
    }

    @Test
    fun cancellationIsPropagatedAndNeverBecomesARouteFailure() = runBlocking {
        val engine = AStarRoutingEngine()
        val graph = buildChainGraph(200_000)
        val snapshot = snapshot(graph)
        val request = request(0, 199_999)

        val deferred = async(Dispatchers.Default) { engine.findRoute(snapshot, request) }
        while (engine.lastHeuristicMeasurement == null) yield()
        deferred.cancel()

        val thrown = runCatching { deferred.await() }.exceptionOrNull()
        assertTrue("expected cancellation, got $thrown", thrown is kotlinx.coroutines.CancellationException)
        assertTrue(deferred.isCancelled)
    }

    @Test
    fun timeoutCancellationIsNotSwallowed() = runBlocking {
        val engine = AStarRoutingEngine()
        val graph = buildChainGraph(200_000)
        val snapshot = snapshot(graph)
        val request = request(0, 199_999)
        var timeoutThrown = false
        try {
            withTimeout(1L) { engine.findRoute(snapshot, request) }
        } catch (cancellation: kotlinx.coroutines.TimeoutCancellationException) {
            timeoutThrown = true
        }
        assertTrue("withTimeout must cancel the search", timeoutThrown)
    }
}
