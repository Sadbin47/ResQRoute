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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Weighted A* is still A* (XPH-75).
 *
 * A changed cost function must not weaken any guarantee the v1 engine already gave. This class
 * re-establishes the guarantees with **penalties active**, against an independent reference:
 * `dijkstraCost` solves the same weighted problem by a different algorithm, so agreement is
 * evidence rather than a restatement.
 *
 * Two properties of a good parity test are enforced here rather than assumed:
 *
 *  * **it must be able to fail** — the suite asserts that at least some policies move the optimum
 *    away from the distance-only one, so the comparison is not vacuous;
 *  * **the heuristic is not allowed to know about penalties** — every result's cost is `>=` its
 *    distance, which is the property the admissibility proof needs.
 *
 * Nothing here changes the engine, the heuristic, the safety factor or the measurement procedure.
 * If parity fails, the policy or the engine is wrong and the test is right (the issue says so in
 * as many words).
 */
class WeightedRoutingParityTest {

    private val engine = AStarRoutingEngine()

    // ------------------------------------------------------------ parity

    @Test
    fun weightedSearchMatchesTheReferenceUnderEveryPolicyShape() = runBlocking {
        val graph = lattice()
        val cases = listOf(
            "distance only" to RoutingPolicy.DISTANCE_ONLY,
            "one cheap segment" to policy(graph, mapOf(HORIZONTAL_MIDDLE to 40.0)),
            "one heavy segment" to policy(graph, mapOf(HORIZONTAL_MIDDLE to 500.0)),
            "all horizontal, light" to policy(graph, horizontalIndices().associateWith { 60.0 }),
            "all horizontal, heavy" to policy(graph, horizontalIndices().associateWith { 400.0 }),
            "all vertical, heavy" to policy(graph, verticalIndices().associateWith { 400.0 }),
            "every segment, uneven" to policy(graph, (0 until graph.segmentCount).associateWith { (it * 37 % 5) * 55.0 }),
            "every segment, never traversed" to policy(graph, (0 until graph.segmentCount).associateWith { 900.0 }),
        )

        for ((name, policyUnderTest) in cases) {
            val expected = dijkstraCost(graph, NodeId(START), NodeId(DESTINATION), GraphBlocker.NONE, policyUnderTest)
            val result = engine.findRoute(snapshot(graph), request(policyUnderTest))

            assertTrue("$name: expected a route but got $result", result is RouteResult.Found)
            result as RouteResult.Found
            assertTrue("$name: reference found no route but A* did", expected != null)
            assertEquals("$name: A* and the reference disagree", expected!!, result.path.totalCost, COST_TOLERANCE)
            assertValidPath(graph, GraphBlocker.NONE, result.path)
            assertCostIsDistancePlusPenalties(graph, policyUnderTest, result.path, name)
        }
    }

    @Test
    fun weightedSearchMatchesTheReferenceWithPenaltiesAndClosuresTogether() = runBlocking {
        val graph = lattice()
        val blockedSets = listOf(
            emptyList(),
            listOf(HORIZONTAL_MIDDLE),
            listOf(VERTICAL_MIDDLE),
            listOf(HORIZONTAL_MIDDLE, VERTICAL_MIDDLE),
            listOf(0, 1, 2),
        )
        // A closed segment is removed from the search *and* may still carry a penalty: the two
        // mechanisms must not interfere.
        val policyUnderTest = policy(
            graph,
            mapOf(
                HORIZONTAL_MIDDLE to 300.0,
                VERTICAL_MIDDLE to 250.0,
                0 to 120.0,
                1 to 90.0,
                2 to 150.0,
            ),
        )

        for (blocked in blockedSets) {
            val blocker = blockerFor(*blocked.toIntArray())
            val expected = dijkstraCost(graph, NodeId(START), NodeId(DESTINATION), blocker, policyUnderTest)
            val result = engine.findRoute(snapshot(graph, blocker), request(policyUnderTest))

            if (expected == null) {
                assertEquals("blocking $blocked must yield no route with penalties active", RouteResult.NoRoute, result)
            } else {
                result as RouteResult.Found
                assertEquals("blocked $blocked: parity lost", expected, result.path.totalCost, COST_TOLERANCE)
                assertValidPath(graph, blocker, result.path)
                for (segment in blocked) {
                    assertFalse(
                        "a closed segment must never be traversed, penalty or not",
                        result.path.segmentIds.any { it.value == segment },
                    )
                }
            }
        }
    }

    @Test
    fun theSuiteIsNotVacuousBecausePenaltiesReallyMoveTheOptimum() = runBlocking {
        val graph = lattice()
        val plain = findRoute(graph, RoutingPolicy.DISTANCE_ONLY)

        // A penalty on one segment of the chosen route must move the optimum; a penalty on a
        // segment the route does not use must not. Sweeping single segments tests both directions,
        // and a uniform per-axis penalty would move nothing on any lattice (it adds the same
        // constant to every path between opposite corners), which is exactly the vacuity this
        // test exists to catch.
        val movedBy = mutableListOf<Int>()
        val ignoredBy = mutableListOf<Int>()
        for (segment in 0 until graph.segmentCount) {
            val weighted = findRoute(graph, policy(graph, mapOf(segment to 400.0)))
            if (weighted.segmentIds != plain.segmentIds) movedBy += segment else ignoredBy += segment
        }

        assertTrue(
            "no single-segment penalty moved the optimum, so parity proved nothing: " +
                "chosen=${plain.segmentIds.map { it.value }}",
            movedBy.size >= 3,
        )
        assertTrue(
            "every penalty moved the optimum, which cannot be right: " +
                "a segment the route never uses cannot change it",
            ignoredBy.isNotEmpty(),
        )
        println("OPTIMUM SHIFT: moved by $movedBy · unchanged by $ignoredBy")
    }

    @Test
    fun aZeroPenaltyPolicyReproducesTheDistanceOnlyRouteExactly() = runBlocking {
        // The LAMBDA = 0 case, expressed as the policy it produces: zero risk, no entries.
        val graph = lattice()
        val allPassable = (0 until graph.segmentCount).associate { index ->
            SegmentId(index) to passableState(index)
        }
        val zeroRisk = RiskPenaltyPolicy.forStates(allPassable) { graph.segmentLengthMeters(it) }
        assertEquals(RoutingPolicy.DISTANCE_ONLY, zeroRisk)

        val plain = findRoute(graph, RoutingPolicy.DISTANCE_ONLY)
        val guarded = findRoute(graph, zeroRisk)
        assertEquals(plain.segmentIds, guarded.segmentIds)
        assertEquals(plain.totalCost, guarded.totalCost, 0.0)
    }

    @Test
    fun veryLargePenaltiesStillTerminateAndStillReturnTheWeightedOptimum() = runBlocking {
        val graph = lattice()
        // A penalty orders of magnitude larger than the graph: the search must not degrade into
        // an exhaustive walk or lose optimality.
        val policyUnderTest = policy(graph, (0 until graph.segmentCount).associateWith { 1.0e9 })
        val result = withTimeout(10_000) {
            engine.findRoute(snapshot(graph), request(policyUnderTest))
        } as RouteResult.Found

        val expected = dijkstraCost(graph, NodeId(START), NodeId(DESTINATION), GraphBlocker.NONE, policyUnderTest)
        assertEquals(expected!!, result.path.totalCost, 1.0)
    }

    @Test
    fun theRealPolicyBuilderProducesPoliciesThatStillMatchTheReference() = runBlocking {
        val graph = lattice()
        // Every severable level of the real builder, so the engine is tested against the numbers
        // that will actually reach it rather than hand-picked doubles.
        for (level in listOf(WaterLevelCategory.ANKLE, WaterLevelCategory.KNEE, WaterLevelCategory.WAIST, WaterLevelCategory.ABOVE_WAIST)) {
            for (verdict in listOf(PassabilityStatus.CAUTION, PassabilityStatus.AVOID)) {
                val states = horizontalIndices().associate { index ->
                    SegmentId(index) to reportedState(index, level, verdict, devices = 3)
                }
                val policyUnderTest = RiskPenaltyPolicy.forStates(states) { graph.segmentLengthMeters(it) }
                assertTrue("$level/$verdict should have produced a penalty", policyUnderTest.segmentPenaltyMeters.isNotEmpty())

                val expected = dijkstraCost(graph, NodeId(START), NodeId(DESTINATION), GraphBlocker.NONE, policyUnderTest)
                val result = engine.findRoute(snapshot(graph), request(policyUnderTest)) as RouteResult.Found
                assertEquals("$level/$verdict: parity lost with the real builder", expected!!, result.path.totalCost, COST_TOLERANCE)
            }
        }
    }

    // ------------------------------------------------- what must not change

    @Test
    fun aPenaltyNeverLetsARouteCostLessThanItsDistance() = runBlocking {
        val graph = lattice()
        for (weight in listOf(0.0, 1.0, 100.0, 10_000.0)) {
            val policyUnderTest = policy(graph, (0 until graph.segmentCount).associateWith { weight })
            val path = findRoute(graph, policyUnderTest)
            assertTrue(
                "weight $weight: cost ${path.totalCost} fell below distance ${path.distanceMeters}",
                path.totalCost >= path.distanceMeters - 1e-9,
            )
        }
    }

    @Test
    fun anUnreachableDestinationStillReturnsNoRouteWithPenaltiesActive() = runBlocking {
        val graph = lattice()
        // Cut every edge into the destination.
        val cut = listOf(SEGMENT_BEFORE_DESTINATION_H, SEGMENT_BEFORE_DESTINATION_V)
        val policyUnderTest = policy(graph, (0 until graph.segmentCount).associateWith { 500.0 })

        val result = engine.findRoute(snapshot(graph, blockerFor(*cut.toIntArray())), request(policyUnderTest))
        assertEquals(RouteResult.NoRoute, result)
        assertNull(dijkstraCost(graph, NodeId(START), NodeId(DESTINATION), blockerFor(*cut.toIntArray()), policyUnderTest))
    }

    @Test
    fun weightedSearchesAreDeterministic() = runBlocking {
        val graph = lattice()
        val policyUnderTest = policy(graph, (0 until graph.segmentCount).associateWith { (it * 53 % 7) * 30.0 })
        val first = findRoute(graph, policyUnderTest)
        repeat(3) {
            val again = findRoute(graph, policyUnderTest)
            assertEquals(first.nodeIds, again.nodeIds)
            assertEquals(first.edgeIds, again.edgeIds)
            assertEquals(first.segmentIds, again.segmentIds)
            assertEquals(first.totalCost, again.totalCost, 0.0)
        }
    }

    @Test
    fun cancellationIsPropagatedWhileSearchingWithPenalties() = runBlocking {
        // A 200 000-node chain with a penalty on every segment: the search is long enough to
        // interrupt, and a penalty must not turn cancellation into a route or a failure.
        val chain = buildChainGraph(200_000)
        val policyUnderTest = policy(chain, (0 until chain.segmentCount).associateWith { 5.0 })
        val deferred = async(Dispatchers.Default) {
            engine.findRoute(
                RoutingSnapshot(chain.identity, "phase15-test", 11, chain, GraphBlocker.NONE),
                RouteRequest(1, NodeId(0), NodeId(199_999), policyUnderTest),
            )
        }
        while (engine.lastHeuristicMeasurement == null) yield()
        deferred.cancel()

        val thrown = runCatching { deferred.await() }.exceptionOrNull()
        assertTrue("expected cancellation, got $thrown", thrown is kotlinx.coroutines.CancellationException)
        assertTrue(deferred.isCancelled)
    }

    @Test
    fun theRoutingPolicyRefusesAPenaltyThatWouldBreakTheProof() {
        // The type-level guard, asserted rather than assumed: a negative or non-finite penalty
        // cannot even be constructed, so no engine call can receive one.
        for (bad in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val thrown = runCatching { RoutingPolicy(mapOf(SegmentId(0) to bad)) }.exceptionOrNull()
            assertTrue("a penalty of $bad must be refused, got $thrown", thrown is IllegalArgumentException)
        }
    }

    @Test
    fun theHeuristicMeasurementIsUnchangedByAnyPolicy() {
        val graph = lattice()
        val before = engine.measureAdmissibility(graph)
        RiskPenaltyPolicy.forStates(
            horizontalIndices().associate { SegmentId(it) to reportedState(it, WaterLevelCategory.ABOVE_WAIST, PassabilityStatus.AVOID, devices = 4) },
        ) { graph.segmentLengthMeters(it) }
        val after = engine.measureAdmissibility(graph)
        // The heuristic is the unpenalised Haversine estimate, so no penalty can move this.
        assertEquals(before, after)
    }

    // ---------------------------------------------------------------- harness

    private suspend fun findRoute(graph: RoutingGraph, policyUnderTest: RoutingPolicy): RoutePath {
        val result = engine.findRoute(snapshot(graph), request(policyUnderTest))
        assertTrue("expected a route but got $result", result is RouteResult.Found)
        return (result as RouteResult.Found).path
    }

    private fun snapshot(
        graph: RoutingGraph,
        blocker: GraphBlocker = GraphBlocker.NONE,
        identity: DatasetIdentity = graph.identity,
    ) = RoutingSnapshot(
        dataset = identity,
        incidentId = "phase15-test",
        hazardRevision = 11,
        graph = graph,
        blocker = blocker,
    )

    private fun request(policyUnderTest: RoutingPolicy) =
        RouteRequest(1, NodeId(START), NodeId(DESTINATION), policyUnderTest)

    private fun policy(graph: RoutingGraph, meters: Map<Int, Double>): RoutingPolicy =
        RoutingPolicy(meters.mapKeys { SegmentId(it.key) })

    /** Recomputes the cost from the path, so a penalty cannot be hidden inside `totalCost`. */
    private fun assertCostIsDistancePlusPenalties(
        graph: RoutingGraph,
        policyUnderTest: RoutingPolicy,
        path: RoutePath,
        label: String,
    ) {
        var distance = 0.0
        var penalties = 0.0
        for (edge in path.edgeIds.map { it.value }) {
            distance += graph.edgeCostMeters(edge)
            penalties += policyUnderTest.penaltyMeters(graph.edgeSegmentIndex(edge))
        }
        assertEquals("$label: reported distance does not match the path", distance, path.distanceMeters, 1e-6)
        assertEquals("$label: cost is not distance plus penalties", distance + penalties, path.totalCost, 1e-6)
    }

    private fun assertValidPath(graph: RoutingGraph, blocker: GraphBlocker, path: RoutePath) {
        assertEquals(NodeId(START), path.nodeIds.first())
        assertEquals(NodeId(DESTINATION), path.nodeIds.last())
        assertEquals(path.edgeIds.size + 1, path.nodeIds.size)
        assertEquals(path.edgeIds.size, path.segmentIds.size)
        for (index in path.edgeIds.indices) {
            val edge = path.edgeIds[index].value
            assertEquals(graph.nodeIndex(path.nodeIds[index])!!, graph.edgeFrom(edge))
            assertEquals(graph.nodeIndex(path.nodeIds[index + 1])!!, graph.edgeTo(edge))
            assertFalse("a closed segment was traversed", blocker.isBlockedSegment(graph.edgeSegmentIndex(edge)))
            assertEquals(graph.edgeSegmentId(edge), path.segmentIds[index])
        }
    }

    // -------------------------------------------------------------- lattice

    /**
     * A 3x3 lattice: nine nodes, twelve segments, six monotone routes between opposite corners.
     *
     * Segment costs are the haversine distance of their endpoints, so the declared cost is exactly
     * consistent with the geometry the heuristic uses — the scale stays 1.0 and the only thing that
     * can move the optimum is a penalty.
     *
     * ```
     *   1000 - 1001 - 1002
     *    |      |      |
     *   1010 - 1011 - 1012
     *    |      |      |
     *   1020 - 1021 - 1022
     * ```
     */
    private fun lattice(): RoutingGraph {
        // Unequal spacing on purpose: on a uniform lattice every monotone path between the
        // corners has the same length, so a penalty per axis adds a constant to all of them and
        // no policy could ever move the optimum - the parity suite would prove nothing.
        val latitudes = listOf(23.0000, 23.0012, 23.0020)
        val longitudes = listOf(91.4000, 91.4009, 91.4016)
        val nodes = buildList {
            for (row in 0..2) {
                for (column in 0..2) {
                    add(NodeSpec(1000L + row * 10 + column, latitudes[row], longitudes[column]))
                }
            }
        }
        val specs = mutableListOf<SegmentSpec>()
        fun add(from: Int, to: Int) {
            specs += SegmentSpec(
                from = from,
                to = to,
                costMeters = GeoMath.haversineMeters(
                    nodes[from].latitude, nodes[from].longitude,
                    nodes[to].latitude, nodes[to].longitude,
                ),
                coordinates = listOf(
                    nodes[from].latitude to nodes[from].longitude,
                    nodes[to].latitude to nodes[to].longitude,
                ),
            )
        }
        // Horizontal first, then vertical, so segment indices are predictable for the fixtures.
        for (row in 0..2) for (column in 0..1) add(row * 3 + column, row * 3 + column + 1)
        for (row in 0..1) for (column in 0..2) add(row * 3 + column, (row + 1) * 3 + column)
        return buildTestGraph(nodes, specs)
    }

    private fun horizontalIndices(): List<Int> = (0..5).toList()
    private fun verticalIndices(): List<Int> = (6..11).toList()

    private fun passableState(segmentIndex: Int) = stateOf(
        segmentIndex,
        Observations(
            level = WaterLevelCategory.NO_VISIBLE_WATER,
            verdict = PassabilityStatus.PASSABLE_REPORTED,
            devices = 1,
        ),
    )

    private fun reportedState(
        segmentIndex: Int,
        level: WaterLevelCategory,
        verdict: PassabilityStatus,
        devices: Int,
    ) = stateOf(segmentIndex, Observations(level, verdict, devices))

    private data class Observations(
        val level: WaterLevelCategory,
        val verdict: PassabilityStatus,
        val devices: Int,
    )

    /** Real derived state for one segment, built by the aggregate the app itself uses. */
    private fun stateOf(segmentIndex: Int, observations: Observations) =
        SegmentObservationAggregator.aggregate(
            (1..observations.devices).map { index ->
                val outcome = RoadObservation.create(HazardReason.WATERLOGGED, observations.level, observations.verdict)
                check(outcome is ObservationValidation.Valid) { "invalid fixture: $outcome" }
                ObservedReport(
                    eventId = EventId("evt-%02d".format(index)),
                    deviceId = DeviceId("device-%02d".format(index)),
                    source = ObservationSource.RELAYED_ACCOUNT,
                    segmentId = SegmentId(segmentIndex),
                    occurredAtEpochMillis = 0,
                    receivedAtEpochMillis = 0,
                    content = ReportContent.Observed(outcome.observation),
                )
            },
        ).getValue(SegmentId(segmentIndex))

    private companion object {
        const val START = 1000L
        const val DESTINATION = 1022L

        /** Horizontal segment between the centre nodes: index 1 in the horizontal block. */
        const val HORIZONTAL_MIDDLE = 1

        /** Vertical segment between the centre nodes: index 7 overall (6 + 1). */
        const val VERTICAL_MIDDLE = 7

        const val SEGMENT_BEFORE_DESTINATION_H = 5
        const val SEGMENT_BEFORE_DESTINATION_V = 11

        /** Loose enough for two different accumulation orders, far tighter than any real error. */
        const val COST_TOLERANCE = 1e-6
    }
}
