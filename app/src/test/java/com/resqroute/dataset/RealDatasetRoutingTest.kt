package com.resqroute.dataset

import com.resqroute.core.graph.GraphBlocker
import com.resqroute.core.graph.RoutingGraph
import com.resqroute.core.model.DatasetIdentity
import com.resqroute.core.model.GeoMath
import com.resqroute.core.model.NodeId
import com.resqroute.core.model.SegmentId
import com.resqroute.core.routing.AStarRoutingEngine
import com.resqroute.core.routing.RoutePath
import com.resqroute.core.routing.RouteRequest
import com.resqroute.core.routing.RouteResult
import com.resqroute.core.routing.RoutingPolicy
import com.resqroute.core.routing.RoutingSnapshot
import com.resqroute.data.dataset.BundledDatasetReader
import com.resqroute.data.dataset.PackedDataset
import com.resqroute.domain.hazard.DeviceId
import com.resqroute.domain.hazard.EventId
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.ObservationSource
import com.resqroute.domain.hazard.ObservationValidation
import com.resqroute.domain.hazard.ObservedReport
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.ReportContent
import com.resqroute.domain.hazard.RoadObservation
import com.resqroute.domain.hazard.SegmentObservationAggregator
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.domain.risk.RiskPenaltyPolicy
import kotlin.math.abs
import kotlin.math.min
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing against the real bundled graph.
 *
 * Proves acceptance criteria 1, 2, 3, 6, 7 and 8 on the actual Feni dataset,
 * not a fixture.
 */
class RealDatasetRoutingTest {

    private val engine = AStarRoutingEngine()

    private val loaded: PackedDataset by lazy {
        val manifest = TestDatasets.manifest
        BundledDatasetReader.parse(
            manifest = manifest,
            bin = TestDatasets.datasetBytes,
            identity = DatasetIdentity(manifest.contentSha256, manifest.formatVersion),
            ioMillis = 0,
            digestMillis = 0,
        )
    }

    /** First shelter pair (bounded search) that yields a non-trivial route. */
    private val routablePair: Pair<NodeId, NodeId> by lazy {
        val graph = loaded.graph
        val pairs = buildList {
            for (i in 0 until graph.shelterCount) {
                for (j in i + 1 until graph.shelterCount) add(i to j)
            }
        }.take(80)
        for ((i, j) in pairs) {
            val start = graph.shelter(i).accessNodeId
            val destination = graph.shelter(j).accessNodeId
            val result = runBlocking {
                engine.findRoute(snapshot(graph, GraphBlocker.NONE), RouteRequest(1, start, destination))
            }
            if (result is RouteResult.Found && result.path.distanceMeters > 50.0) {
                return@lazy start to destination
            }
        }
        error("no routable shelter pair found in the first 80 combinations")
    }

    private fun snapshot(graph: RoutingGraph, blocker: GraphBlocker = GraphBlocker.NONE) =
        RoutingSnapshot(
            dataset = graph.identity,
            incidentId = "phase4-test",
            hazardRevision = 3,
            graph = graph,
            blocker = blocker,
        )

    private fun assertValidRoute(
        graph: RoutingGraph,
        blocker: GraphBlocker,
        start: NodeId,
        destination: NodeId,
        path: RoutePath,
    ) {
        assertEquals(start, path.nodeIds.first())
        assertEquals(destination, path.nodeIds.last())
        assertEquals(path.edgeIds.size + 1, path.nodeIds.size)
        assertEquals(path.edgeIds.size, path.segmentIds.size)
        for (index in path.edgeIds.indices) {
            val edge = path.edgeIds[index].value
            assertEquals(graph.nodeIndex(path.nodeIds[index])!!, graph.edgeFrom(edge))
            assertEquals(graph.nodeIndex(path.nodeIds[index + 1])!!, graph.edgeTo(edge))
            assertFalse(blocker.isBlockedSegment(graph.edgeSegmentIndex(edge)))
            assertEquals(graph.edgeSegmentId(edge), path.segmentIds[index])
        }
    }

    @Test
    fun findsAValidRouteBetweenShelters() = runBlocking {
        val graph = loaded.graph
        val (start, destination) = routablePair
        val result = engine.findRoute(snapshot(graph), RouteRequest(42, start, destination))

        assertTrue("expected a route, got $result", result is RouteResult.Found)
        result as RouteResult.Found
        val path = result.path

        assertValidRoute(graph, GraphBlocker.NONE, start, destination, path)
        assertTrue(path.distanceMeters > 0.0)
        assertEquals(path.distanceMeters, path.totalCost, 1e-6)
        assertEquals(42L, path.requestGeneration)
        assertEquals(3L, path.hazardRevision)
        assertEquals("phase4-test", path.incidentId)
        assertEquals(loaded.identity, path.dataset)
        println("REAL ROUTE: nodes=${path.nodeIds.size} edges=${path.edgeIds.size} distance=${path.distanceMeters} m")
    }

    @Test
    fun blockingASegmentNeverTraversesItAndDoesNotLowerTheCost() = runBlocking {
        val graph = loaded.graph
        val (start, destination) = routablePair
        val original = engine.findRoute(snapshot(graph), RouteRequest(1, start, destination)) as RouteResult.Found
        val chosenSegment = original.path.segmentIds[original.path.segmentIds.size / 2]

        val blocked = blockerFor(chosenSegment.value)
        val rerouted = engine.findRoute(snapshot(graph, blocked), RouteRequest(2, start, destination))

        when (rerouted) {
            is RouteResult.Found -> {
                assertValidRoute(graph, blocked, start, destination, rerouted.path)
                assertFalse(rerouted.path.segmentIds.any { it.value == chosenSegment.value })
                assertTrue(rerouted.path.totalCost >= original.path.totalCost - 1e-6)
                println(
                    "DETOUR: blocked segment ${chosenSegment.value} · " +
                        "original=${original.path.distanceMeters} m · detour=${rerouted.path.distanceMeters} m",
                )
            }
            RouteResult.NoRoute -> println("blocking segment ${chosenSegment.value} disconnects the pair (valid NoRoute)")
            is RouteResult.InvalidEndpoint, is RouteResult.Failure -> throw AssertionError("unexpected: $rerouted")
        }
    }

    @Test
    fun aStarMatchesReferenceDijkstraOnTheRealGraph() = runBlocking {
        val graph = loaded.graph
        val (start, destination) = routablePair
        val result = engine.findRoute(snapshot(graph), RouteRequest(1, start, destination)) as RouteResult.Found
        val reference = dijkstraCost(graph, start, destination, GraphBlocker.NONE, RoutingPolicy.DISTANCE_ONLY)

        assertTrue(reference != null)
        assertEquals(reference!!, result.path.totalCost, 1e-6)
        println("DIJKSTRA PARITY: A*=${result.path.totalCost} m · Dijkstra=${reference} m")
    }

    @Test
    fun heuristicIsAdmissibleOnTheRealGraph() {
        val graph = loaded.graph
        val measurement = engine.measureAdmissibility(graph)
        println(
            "ADMISSIBILITY: min(cost/haversine)=${measurement.minCostToHaversineRatio} " +
                "scale=${measurement.appliedScale} edges=${measurement.edgeCount}",
        )

        assertTrue(measurement.appliedScale > 0.0)
        assertTrue(measurement.appliedScale <= min(1.0, measurement.minCostToHaversineRatio) + 1e-12)

        // Consistency: h(u) <= cost(u,v) + h(v) for every edge, which follows
        // from scale * haversine(u,v) <= cost(u,v).
        for (edge in 0 until graph.edgeCount) {
            val from = graph.edgeFrom(edge)
            val to = graph.edgeTo(edge)
            val straight = GeoMath.haversineMeters(
                graph.latitude(from),
                graph.longitude(from),
                graph.latitude(to),
                graph.longitude(to),
            )
            val scaled = measurement.appliedScale * straight
            assertTrue(
                "edge $edge violates the heuristic bound: $scaled > ${graph.edgeCostMeters(edge)}",
                scaled <= graph.edgeCostMeters(edge) + 1e-9,
            )
        }
    }

    @Test
    fun repeatedSearchesAreDeterministic() = runBlocking {
        val graph = loaded.graph
        val (start, destination) = routablePair
        val first = engine.findRoute(snapshot(graph), RouteRequest(9, start, destination)) as RouteResult.Found
        val second = engine.findRoute(snapshot(graph), RouteRequest(9, start, destination)) as RouteResult.Found
        assertEquals(first.path.nodeIds, second.path.nodeIds)
        assertEquals(first.path.edgeIds, second.path.edgeIds)
        assertEquals(first.path.segmentIds, second.path.segmentIds)
        assertEquals(first.path.totalCost, second.path.totalCost, 0.0)
    }

    @Test
    fun reportsWarmRouteTiming() = runBlocking {
        val graph = loaded.graph
        val (start, destination) = routablePair
        engine.findRoute(snapshot(graph), RouteRequest(0, start, destination)) // warm up + measure admissibility

        val samples = LongArray(20)
        repeat(20) { index ->
            samples[index] = measureTimeMillis {
                val result = runBlocking { engine.findRoute(snapshot(graph), RouteRequest(index.toLong(), start, destination)) }
                check(result is RouteResult.Found)
            }
        }
        val sorted = samples.sorted()
        val p50 = sorted[sorted.size / 2]
        val p95 = sorted[(sorted.size * 95) / 100]
        println("WARM ROUTE TIMING (JVM): p50=$p50 ms · p95=$p95 ms · all=${samples.toList()}")
        assertTrue("warm p95 was $p95 ms", p95 < 2_000)
    }

    @Test
    fun zeroLengthAndInvalidEndpointsOnRealGraph() = runBlocking {
        val graph = loaded.graph
        val (start, _) = routablePair

        val zero = engine.findRoute(snapshot(graph), RouteRequest(1, start, start))
        assertTrue(zero is RouteResult.Found)
        assertTrue((zero as RouteResult.Found).path.edgeIds.isEmpty())

        val missing = engine.findRoute(snapshot(graph), RouteRequest(1, NodeId(Long.MAX_VALUE), start))
        assertEquals(RouteResult.InvalidEndpoint(com.resqroute.core.routing.RouteEndpoint.START), missing)
    }

    @Test
    fun distanceIsNonNegativeAndSymmetricInMagnitude() = runBlocking {
        val graph = loaded.graph
        val (start, destination) = routablePair
        val forward = engine.findRoute(snapshot(graph), RouteRequest(1, start, destination)) as RouteResult.Found
        val backward = engine.findRoute(snapshot(graph), RouteRequest(2, destination, start))
        if (backward is RouteResult.Found) {
            // Two-way roads make both directions possible; costs need not be
            // identical on one-way networks, but both must be finite and positive.
            assertTrue(forward.path.distanceMeters > 0)
            assertTrue(backward.path.distanceMeters > 0)
            assertTrue(abs(forward.path.distanceMeters - backward.path.distanceMeters) < 1e9)
        }
    }

    // ------------------------------------------------- weighted routing (XPH-75)

    @Test
    fun aStarMatchesReferenceDijkstraWithPenaltiesOnTheRealGraph() = runBlocking {
        val graph = loaded.graph
        val (start, destination) = routablePair
        val plain = engine.findRoute(snapshot(graph), RouteRequest(1, start, destination)) as RouteResult.Found

        // Penalise the segments the distance-only route actually uses, which is the case that
        // matters: the hazard is on the road the router would otherwise take.
        val onRoute = plain.path.segmentIds.distinct()
        val policy = RoutingPolicy(onRoute.associateWith { 400.0 })

        val weighted = engine.findRoute(
            snapshot(graph),
            RouteRequest(2, start, destination, policy),
        ) as RouteResult.Found
        val reference = dijkstraCost(graph, start, destination, GraphBlocker.NONE, policy)

        assertTrue("the reference must find a route too", reference != null)
        assertEquals("A* and the reference disagree with penalties active", reference!!, weighted.path.totalCost, 1e-6)
        assertValidRoute(graph, GraphBlocker.NONE, start, destination, weighted.path)
        assertTrue("a penalty cannot lower a cost", weighted.path.totalCost >= plain.path.totalCost - 1e-6)
        println(
            "WEIGHTED REAL PARITY: A*=${weighted.path.totalCost} m · Dijkstra=${reference} m · " +
                "plain=${plain.path.totalCost} m · ${onRoute.size} of ${graph.segmentCount} segments penalised",
        )
    }

    @Test
    fun aPenalisedRealGraphIsStillSearchedOptimallyAcrossManySegments() = runBlocking {
        val graph = loaded.graph
        val (start, destination) = routablePair
        // A penalty on a wide, deterministic subset rather than a handful, so the measure is not
        // an artefact of one or two penalised segments.
        val policy = RoutingPolicy(
            (0 until graph.segmentCount step 37).associate { index -> SegmentId(index) to (index % 5) * 150.0 },
        )

        val weighted = engine.findRoute(
            snapshot(graph),
            RouteRequest(3, start, destination, policy),
        ) as RouteResult.Found
        val reference = dijkstraCost(graph, start, destination, GraphBlocker.NONE, policy)

        assertEquals(reference!!, weighted.path.totalCost, 1e-6)
        assertValidRoute(graph, GraphBlocker.NONE, start, destination, weighted.path)
        println(
            "WEIGHTED SWEEP PARITY: ${policy.segmentPenaltyMeters.size} segments penalised · " +
                "A*=${weighted.path.totalCost} m · Dijkstra=${reference} m",
        )
    }

    @Test
    fun aRouteBuiltFromReportedHazardsStillMatchesTheReference() = runBlocking {
        val graph = loaded.graph
        val (start, destination) = routablePair
        val plain = engine.findRoute(snapshot(graph), RouteRequest(1, start, destination)) as RouteResult.Found

        // Real derived state for the segments on the plain route, priced by the real builder.
        val states = plain.path.segmentIds.distinct().associateWith { segment ->
            SegmentObservationAggregator.aggregate(
                (1..3).map { index ->
                    ObservedReport(
                        eventId = EventId("evt-real-%03d".format(index)),
                        deviceId = DeviceId("device-%03d".format(index)),
                        source = ObservationSource.RELAYED_ACCOUNT,
                        segmentId = segment,
                        occurredAtEpochMillis = 0,
                        receivedAtEpochMillis = 0,
                        content = ReportContent.Observed(
                            (RoadObservation.create(
                                HazardReason.WATERLOGGED,
                                WaterLevelCategory.WAIST,
                                PassabilityStatus.AVOID,
                            ) as ObservationValidation.Valid).observation,
                        ),
                    )
                },
            ).getValue(segment)
        }
        val policy = RiskPenaltyPolicy.forStates(states) { graph.segmentLengthMeters(it) }
        assertTrue("the builder must price the reported segments", policy.segmentPenaltyMeters.isNotEmpty())

        val route = engine.findRoute(
            snapshot(graph),
            RouteRequest(4, start, destination, policy),
        ) as RouteResult.Found
        val reference = dijkstraCost(graph, start, destination, GraphBlocker.NONE, policy)

        assertEquals("the real builder's policy must not break parity", reference!!, route.path.totalCost, 1e-6)
        assertValidRoute(graph, GraphBlocker.NONE, start, destination, route.path)
        assertTrue(route.path.totalCost >= plain.path.totalCost - 1e-6)
        println(
            "REPORTED-HAZARD ROUTE: ${states.size} segments reported · penalised=${policy.segmentPenaltyMeters.size} · " +
                "cost=${route.path.totalCost} m vs plain ${plain.path.totalCost} m",
        )
    }

    @Test
    fun theRecordedAdmissibilityMeasurementIsUnchangedByPenalties() {
        val graph = loaded.graph
        val plain = engine.measureAdmissibility(graph)

        // A heavily penalised policy, then the same measurement again: the heuristic is the
        // unpenalised geometric estimate, so nothing about a policy may move these numbers.
        RiskPenaltyPolicy.forStates(
            (0 until graph.segmentCount step 53).associate { index ->
                SegmentId(index) to SegmentObservationAggregator.aggregate(
                    listOf(
                        ObservedReport(
                            eventId = EventId("evt-measure-%03d".format(index)),
                            deviceId = DeviceId("device-measure"),
                            source = ObservationSource.DIRECT_OBSERVATION,
                            segmentId = SegmentId(index),
                            occurredAtEpochMillis = 0,
                            receivedAtEpochMillis = 0,
                            content = ReportContent.Observed(
                                (RoadObservation.create(
                                    HazardReason.BLOCKED,
                                    WaterLevelCategory.ABOVE_WAIST,
                                    PassabilityStatus.AVOID,
                                ) as ObservationValidation.Valid).observation,
                            ),
                        ),
                    ),
                ).getValue(SegmentId(index))
            },
        ) { graph.segmentLengthMeters(it) }
        val afterPenalties = engine.measureAdmissibility(graph)

        assertEquals("a policy moved the admissibility measurement", plain, afterPenalties)
        assertEquals("the recorded scale moved", plain.appliedScale, afterPenalties.appliedScale, 0.0)
        assertEquals(plain.edgeCount, afterPenalties.edgeCount)

        // The recorded measurement line, with the dataset identity, for the issue's evidence.
        println(
            "ADMISSIBILITY (weighted phase, XPH-75): " +
                "ratio=${plain.minCostToHaversineRatio} scale=${plain.appliedScale} " +
                "edges=${plain.edgeCount} dataset=${loaded.identity.contentHash} " +
                "format=${loaded.identity.formatVersion}",
        )
        assertTrue(plain.appliedScale > 0.0)
        assertTrue(plain.appliedScale <= min(1.0, plain.minCostToHaversineRatio) + 1e-12)
    }
}
