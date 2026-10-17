package com.resqroute.hazard

import com.resqroute.core.graph.GraphBlocker
import com.resqroute.core.graph.RoutingGraph
import com.resqroute.core.model.NodeId
import com.resqroute.core.model.SegmentId
import com.resqroute.core.routing.AStarRoutingEngine
import com.resqroute.core.routing.RouteRequest
import com.resqroute.core.routing.RouteResult
import com.resqroute.core.routing.RoutingPolicy
import com.resqroute.core.routing.RoutingSnapshot
import com.resqroute.dataset.NodeSpec
import com.resqroute.dataset.SegmentSpec
import com.resqroute.dataset.buildTestGraph
import com.resqroute.domain.hazard.HazardContext
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.HazardSnapshot
import com.resqroute.domain.hazard.ObservationSource
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.SegmentObservationAggregator
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.domain.risk.RiskPenaltyPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The penalty policy driving the **real** A* engine, on a hand-computable graph (XPH-74).
 *
 * This is the evidence that the builder is correct without the engine being touched: the policy
 * is built from real derived state and handed to `AStarRoutingEngine` exactly as the app will
 * hand it over, and the engine's own route choice is what is asserted.
 *
 * ```
 *        route A: 100 m + 100 m = 200 m   (segments 0 and 1)
 *        route B: 120 m + 120 m = 240 m   (segments 2 and 3)
 * ```
 *
 * A 100 m penalty on segment 0 makes route A cost 300 m, so a reported hazard on the cheap road
 * sends the router the long way round — and a report that says the road is fine still does not.
 */
class RiskPenaltyRoutingTest {

    private val engine = AStarRoutingEngine()

    // ------------------------------------------------- the hazard changes the route

    @Test
    fun aReportedHazardMovesTheRouteToTheLongerRoad() = runBlocking {
        val graph = diamond()

        // Baseline: no reports at all, cost equals distance.
        val plain = findRoute(graph, RoutingPolicy.DISTANCE_ONLY)
        assertEquals(listOf(0, 1), plain.segmentIds.map { it.value })
        assertEquals(200.0, plain.distanceMeters, 1e-6)
        assertEquals(200.0, plain.totalCost, 1e-6)

        // The cheap road is reported above waist and to be avoided: risk 1.0, penalty 100 m.
        val policy = RiskPenaltyPolicy.forStates(
            mapOf(SegmentId(0) to reported(graph, 0, WaterLevelCategory.ABOVE_WAIST, PassabilityStatus.AVOID)),
        ) { graph.segmentLengthMeters(it) }
        // Expressed through LAMBDA so tuning the constant (XPH-84) cannot break this test:
        // what it asserts is the FORMULA, not the current exchange rate.
        assertEquals(LENGTH * RiskPenaltyPolicy.LAMBDA, policy.penaltyMeters(0), 1e-6)

        val avoided = findRoute(graph, policy)
        assertEquals("the hazard must send the router the long way", listOf(2, 3), avoided.segmentIds.map { it.value })
        // The cost carries the penalty; the distance the user is told does not.
        assertEquals(240.0, avoided.distanceMeters, 1e-6)
        assertEquals(240.0, avoided.totalCost, 1e-6)
    }

    @Test
    fun aReportThatTheRoadIsFineProducesTheDistanceOnlyResult() = runBlocking {
        // "no water seen, passable" is information, not a hazard: risk 0, no entry, distance only.
        val graph = diamond()
        val states = mapOf(
            SegmentId(0) to reported(graph, 0, WaterLevelCategory.NO_VISIBLE_WATER, PassabilityStatus.PASSABLE_REPORTED),
            SegmentId(1) to reported(graph, 1, WaterLevelCategory.NO_VISIBLE_WATER, PassabilityStatus.PASSABLE_REPORTED),
        )
        val policy = RiskPenaltyPolicy.forStates(states) { graph.segmentLengthMeters(it) }

        assertEquals(RoutingPolicy.DISTANCE_ONLY, policy)
        val path = findRoute(graph, policy)
        assertEquals(listOf(0, 1), path.segmentIds.map { it.value })
        assertEquals(200.0, path.totalCost, 1e-6)
    }

    @Test
    fun aMildReportOnTheCheapRoadDoesNotMoveTheRoute() = runBlocking {
        // Ankle-deep and passable costs 15 m: 215 m is still cheaper than 240 m. A penalty that
        // did not scale with severity would move the route for nothing.
        val graph = diamond()
        val policy = RiskPenaltyPolicy.forStates(
            mapOf(SegmentId(0) to reported(graph, 0, WaterLevelCategory.ANKLE, PassabilityStatus.PASSABLE_REPORTED)),
        ) { graph.segmentLengthMeters(it) }

        assertEquals(LENGTH * RiskPenaltyPolicy.LAMBDA * 0.15, policy.penaltyMeters(0), 1e-6)
        val path = findRoute(graph, policy)
        assertEquals(listOf(0, 1), path.segmentIds.map { it.value })
        assertEquals(200.0 + LENGTH * RiskPenaltyPolicy.LAMBDA * 0.15, path.totalCost, 1e-6)
    }

    @Test
    fun moreReportingDevicesMakeTheSameReportCostMore() = runBlocking {
        val graph = diamond()
        val one = RiskPenaltyPolicy.forStates(
            mapOf(SegmentId(0) to reported(graph, 0, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, devices = 1)),
        ) { graph.segmentLengthMeters(it) }
        val four = RiskPenaltyPolicy.forStates(
            mapOf(SegmentId(0) to reported(graph, 0, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, devices = 4)),
        ) { graph.segmentLengthMeters(it) }

        assertTrue(
            "four phones must not cost less than one: ${four.penaltyMeters(0)} vs ${one.penaltyMeters(0)}",
            four.penaltyMeters(0) > one.penaltyMeters(0),
        )
        assertEquals(LENGTH * RiskPenaltyPolicy.LAMBDA * 0.35, one.penaltyMeters(0), 1e-6)
        assertEquals(LENGTH * RiskPenaltyPolicy.LAMBDA * 0.35 * 1.75, four.penaltyMeters(0), 1e-6)
    }

    // ------------------------------ exclusion and penalty are separate mechanisms

    @Test
    fun anImpassableReportClosesTheRoadThroughTheBlockerAndNeverThroughACost() = runBlocking {
        val graph = diamond()
        val impassable = reported(graph, 0, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE)

        // The policy refuses to price it at all.
        val policy = RiskPenaltyPolicy.forStates(mapOf(SegmentId(0) to impassable)) { graph.segmentLengthMeters(it) }
        assertEquals(
            "an impassable report must not be a large number, it must be no number",
            RoutingPolicy.DISTANCE_ONLY,
            policy,
        )

        // The route changes anyway, because the blocker removes the segment from the search.
        val blocker = HazardSnapshot(
            context = HazardContext(graph.identity, "test-incident"),
            revision = 1,
            blockedSegments = setOf(SegmentId(0)),
        )
        val path = findRoute(graph, policy, blocker)
        assertEquals(listOf(2, 3), path.segmentIds.map { it.value })
        assertEquals(240.0, path.totalCost, 1e-6)
    }

    @Test
    fun anUnreportedSegmentIsUntouchedByThePolicy() = runBlocking {
        // Only segments present in the state get an entry; whether unreported road should cost
        // extra is XPH-89's decision.
        val graph = diamond()
        val policy = RiskPenaltyPolicy.forStates(
            mapOf(SegmentId(0) to reported(graph, 0, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION)),
        ) { graph.segmentLengthMeters(it) }
        assertEquals(setOf(SegmentId(0)), policy.segmentPenaltyMeters.keys)
    }

    // ------------------------------------- the engine receives an immutable map

    @Test
    fun thePolicyDoesNotRetainOrExposeTheCallersMap() = runBlocking {
        val graph = diamond()
        val mutable = mutableMapOf(SegmentId(0) to reported(graph, 0, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION))
        val policy = RiskPenaltyPolicy.forStates(mutable) { graph.segmentLengthMeters(it) }
        val before = policy.penaltyMeters(0)

        // Mutating the caller's map afterwards must not reach the policy the engine is using.
        mutable[SegmentId(0)] = reported(graph, 0, WaterLevelCategory.ABOVE_WAIST, PassabilityStatus.AVOID)
        mutable[SegmentId(1)] = reported(graph, 1, WaterLevelCategory.ABOVE_WAIST, PassabilityStatus.AVOID)

        assertEquals(before, policy.penaltyMeters(0), 1e-12)
        assertEquals(0.0, policy.penaltyMeters(1), 1e-12)
        assertEquals(1, policy.segmentPenaltyMeters.size)
    }

    @Test
    fun aPenaltyNeverMakesASegmentCheaperThanItsLength() = runBlocking {
        // The admissibility contract: cost per segment is length + a non-negative penalty, so the
        // unpenalised Haversine heuristic can never overestimate it.
        val graph = diamond()
        val state = reported(graph, 0, WaterLevelCategory.ABOVE_WAIST, PassabilityStatus.AVOID, devices = 5)
        val policy = RiskPenaltyPolicy.forStates(mapOf(SegmentId(0) to state)) { graph.segmentLengthMeters(it) }

        val length = graph.segmentLengthMeters(0)
        val effective = (length + policy.penaltyMeters(0)) / length
        assertEquals(RiskPenaltyPolicy.effectiveWeight(RiskPenaltyPolicy.riskFactor(state)), effective, 1e-9)
        assertTrue("effective weight fell below 1: $effective", effective >= 1.0)

        val path = findRoute(graph, policy)
        assertTrue("the cost of a penalised route must exceed its distance", path.totalCost >= path.distanceMeters)
    }

    @Test
    fun theMeasurementOfTheHeuristicIsUnchangedByAnyPenalty() {
        // measureAdmissibility describes the graph and the heuristic, which no policy can alter.
        val graph = diamond()
        val plain = engine.measureAdmissibility(graph)
        RiskPenaltyPolicy.forStates(
            mapOf(SegmentId(0) to reported(graph, 0, WaterLevelCategory.ABOVE_WAIST, PassabilityStatus.AVOID)),
        ) { graph.segmentLengthMeters(it) }
        assertEquals(plain, engine.measureAdmissibility(graph))
        assertNotEquals(0.0, plain.appliedScale)
    }

    // ---------------------------------------------------------------- harness

    private suspend fun findRoute(
        graph: RoutingGraph,
        policy: RoutingPolicy,
        blocker: GraphBlocker = GraphBlocker.NONE,
    ): com.resqroute.core.routing.RoutePath {
        val snapshot = RoutingSnapshot(
            dataset = graph.identity,
            incidentId = "test-incident",
            hazardRevision = 7,
            graph = graph,
            blocker = blocker,
        )
        val result = engine.findRoute(snapshot, RouteRequest(1, NodeId(START), NodeId(DESTINATION), policy))
        assertTrue("expected a route but got $result", result is RouteResult.Found)
        return (result as RouteResult.Found).path
    }

    /**
     * Two routes between two nodes, with hand-computable lengths.
     *
     * ```
     *  100 - 101 - 103      segments 0 and 1, 100 m each   (the cheap road)
     *  100 - 102 - 103      segments 2 and 3, 120 m each   (the long way round)
     * ```
     */
    private fun diamond(): RoutingGraph {
        val nodes = listOf(
            NodeSpec(START, 23.0000, 91.4000),
            NodeSpec(101, 23.0010, 91.4005),
            NodeSpec(102, 23.0010, 91.3995),
            NodeSpec(DESTINATION, 23.0020, 91.4000),
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

    /** Real derived state for one segment, built by the aggregate the app itself uses. */
    private fun reported(
        graph: RoutingGraph,
        segmentIndex: Int,
        level: WaterLevelCategory,
        verdict: PassabilityStatus,
        devices: Int = 1,
    ): SegmentObservationState {
        val reports = (1..maxOf(devices, 1)).map { index ->
            HazardFixtures.observedReport(
                eventId = "evt-%02d".format(index),
                device = "device-%02d".format(index),
                segmentId = segmentIndex,
                observation = HazardFixtures.observation(HazardReason.WATERLOGGED, level, verdict),
                source = if (index == 1) ObservationSource.DIRECT_OBSERVATION else ObservationSource.RELAYED_ACCOUNT,
            )
        }
        // `graph` is used for the segment's existence; the length is looked up by the policy.
        assertTrue(graph.segmentLengthMeters(segmentIndex) > 0.0)
        return SegmentObservationAggregator.aggregate(reports).getValue(SegmentId(segmentIndex))
    }

    private companion object {
        const val START = 100L
        const val DESTINATION = 103L

        /** Segment 0's geometric length on the diamond, which the assertions scale from. */
        const val LENGTH = 100.0
    }
}
