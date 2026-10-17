package com.resqroute.experiment

import com.resqroute.core.graph.GraphBlocker
import com.resqroute.core.graph.RoutingGraph
import com.resqroute.core.model.DatasetIdentity
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
import com.resqroute.dataset.TestDatasets
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.ObservationValidation
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.RoadObservation
import com.resqroute.domain.hazard.SegmentObservationAggregator
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.domain.risk.RiskPenaltyPolicy
import com.resqroute.hazard.HazardFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test

/**
 * Evidence for the unknown-coverage decision (XPH-89, `docs/decisions/0014-...`).
 *
 * This is **decision support, not a product test**: it measures two things on the real Feni graph
 * that the decision rests on, and it builds the candidate uncertainty penalty **without touching
 * production code** — as a `RoutingPolicy` carrying an extra `mu x length` entry for every segment
 * with no observation, which is exactly the shape a real implementation would take.
 *
 * It asserts the two premises a reader of the decision needs to be able to trust, and prints the
 * full table for the rest so the numbers in the ADR can be checked rather than believed:
 *
 *  1. **A uniform tax changes nothing.** With no reports at all, every segment is unreported, so the
 *     candidate term is a pure distance multiplier and no route may move. This is the control, and it
 *     is what shows the mechanism is a multiplier over 98.7 % of this dataset.
 *  2. **The risk-weighted route prefers roads nobody has reported.** That is the problem the decision
 *     is about, and it is currently true on every sampled pair.
 *
 * If (2) ever stops being true, the premise of ADR 0014 has changed and the decision should be
 * revisited rather than quietly inherited.
 */
class UnknownCoverageEvidenceTest {

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

    /** Shelter pairs that route non-trivially; built in the test because routing suspends. */
    private suspend fun pairs(graph: RoutingGraph): List<Pair<NodeId, NodeId>> {
        val pairs = mutableListOf<Pair<NodeId, NodeId>>()
        for (i in 0 until minOf(graph.shelterCount, 8)) {
            for (j in i + 1 until minOf(graph.shelterCount, 8)) {
                val start = graph.shelter(i).accessNodeId
                val destination = graph.shelter(j).accessNodeId
                val route = routeOrNull(graph, start, destination, RoutingPolicy.DISTANCE_ONLY)
                if (route != null && route.distanceMeters > 2_000.0) pairs += start to destination
                if (pairs.size >= PAIR_LIMIT) return pairs
            }
        }
        return pairs
    }

    @Test
    fun measure() = runBlocking {
        val graph = loaded.graph
        val pairs = pairs(graph)
        println("EXPERIMENT dataset=${loaded.identity.contentHash} segments=${graph.segmentCount} pairs=${pairs.size}")

        // ---------------------------------------------------------------- control
        // With no reports at all every segment is unreported, so an uncertainty penalty is a
        // uniform multiplier and must change nothing. If this moves a route, the harness is wrong.
        var controlMoved = 0
        for ((start, destination) in pairs.take(4)) {
            val empty = emptyMap<SegmentId, SegmentObservationState>()
            val base = route(graph, start, destination, policy(graph, empty, mu = 0.0))
            val taxed = route(graph, start, destination, policy(graph, empty, mu = 0.5))
            if (base.segmentIds != taxed.segmentIds) controlMoved++
        }
        println("CONTROL no reports, mu=0 vs mu=0.5: $controlMoved of 4 routes changed (expect 0)")
        // The control is the mechanism, not a coincidence: with uniform coverage the term is a
        // multiplier on every segment, so it cannot reorder two routes.
        org.junit.Assert.assertEquals("a uniform tax must not change a route", 0, controlMoved)

        // ------------------------------------------- 1. the perverse-incentive test
        println()
        println("EXPERIMENT 1: does the lower-reported-risk route prefer unreported roads?")
        println("  report set: each pair's own shortest route, knee/caution, 2 devices; lambda=1.5")
        println("  | pair | shortest m | shortest unreported % | risk route m | risk route unreported % | delta unreported m |")
        println("  | ---: | ---: | ---: | ---: | ---: | ---: |")

        var moreUnreported = 0
        var totalDeltaUnreported = 0.0
        var measured = 0
        val corridorStates = mutableMapOf<Pair<NodeId, NodeId>, Map<SegmentId, SegmentObservationState>>()
        for ((index, pair) in pairs.withIndex()) {
            val (start, destination) = pair
            val shortest = route(graph, start, destination, RoutingPolicy.DISTANCE_ONLY)
            val states = corridorReports(graph, shortest)
            corridorStates[pair] = states

            val risk = route(graph, start, destination, policy(graph, states, mu = 0.0))
            val shortestUnknown = unknownMeters(graph, shortest, states)
            val riskUnknown = unknownMeters(graph, risk, states)
            val delta = riskUnknown - shortestUnknown
            if (delta > 1.0) moreUnreported++
            totalDeltaUnreported += delta
            measured++

            println(
                "  | ${index + 1} | ${shortest.distanceMeters.f()} | ${percent(shortestUnknown, shortest.distanceMeters)} | " +
                    "${risk.distanceMeters.f()} | ${percent(riskUnknown, risk.distanceMeters)} | ${delta.f()} |",
            )
        }
        println(
            "  RESULT: the risk-weighted route was MORE unreported on $moreUnreported of $measured pairs; " +
                "mean change ${(totalDeltaUnreported / measured).f()} m",
        )
        // The premise of ADR 0014. Stated as a contract so a change to it is noticed: if the
        // risk-weighted route ever stops preferring unreported road, the disclosure requirement (and
        // this decision) should be re-examined rather than assumed to still hold.
        org.junit.Assert.assertEquals(
            "the risk-weighted route no longer prefers unreported road: ADR 0014's premise changed",
            measured,
            moreUnreported,
        )
        org.junit.Assert.assertTrue("the sample is too small to support a decision", measured >= 10)

        // ---------------------------------------- 2. what an uncertainty penalty does
        println()
        println("EXPERIMENT 2: what would an uncertainty penalty change?")
        println("  same report sets; penalty = mu x length on every segment with no observation")
        println("  | mu | routes changed | mean delta distance m | mean delta unreported m | mean delta risk exposure m |")
        println("  | ---: | ---: | ---: | ---: | ---: |")

        for (mu in MUS) {
            var changed = 0
            var sumDeltaDistance = 0.0
            var sumDeltaUnknown = 0.0
            var sumDeltaRisk = 0.0
            for (pair in pairs) {
                val (start, destination) = pair
                val states = corridorStates.getValue(pair)
                val base = route(graph, start, destination, policy(graph, states, mu = 0.0))
                val taxed = route(graph, start, destination, policy(graph, states, mu = mu))

                if (base.segmentIds != taxed.segmentIds) changed++
                sumDeltaDistance += taxed.distanceMeters - base.distanceMeters
                sumDeltaUnknown += unknownMeters(graph, taxed, states) - unknownMeters(graph, base, states)
                sumDeltaRisk += riskExposure(graph, taxed, states) - riskExposure(graph, base, states)
            }
            println(
                "  | $mu | $changed/${pairs.size} | ${(sumDeltaDistance / pairs.size).f()} | " +
                    "${(sumDeltaUnknown / pairs.size).f()} | ${(sumDeltaRisk / pairs.size).f()} |",
            )
        }

        // ------------------------------- 3. how much of the map is unreported at all
        val observed = corridorStates.values.flatMap { it.keys }.toSet()
        println()
        println(
            "COVERAGE reported segments in the union of all corridors: ${observed.size} of ${graph.segmentCount} " +
                "(${percent(observed.size.toDouble(), graph.segmentCount.toDouble())})",
        )
    }

    // ---------------------------------------------------------------- harness

    private fun policy(
        graph: RoutingGraph,
        states: Map<SegmentId, SegmentObservationState>,
        mu: Double,
    ): RoutingPolicy {
        val penalties = LinkedHashMap<SegmentId, Double>()
        // The shipped risk pricing, verbatim.
        penalties += RiskPenaltyPolicy.forStates(states = states, segmentLengthMeters = graph::segmentLengthMeters)
            .segmentPenaltyMeters
        if (mu > 0.0) {
            // The candidate term: unreported road costs mu x its own length.
            for (index in 0 until graph.segmentCount) {
                val segment = SegmentId(index)
                if (states.containsKey(segment)) continue
                val penalty = graph.segmentLengthMeters(index) * mu
                if (penalty > 0.0) penalties[segment] = (penalties[segment] ?: 0.0) + penalty
            }
        }
        return if (penalties.isEmpty()) RoutingPolicy.DISTANCE_ONLY else RoutingPolicy(penalties)
    }

    private fun corridorReports(
        graph: RoutingGraph,
        route: RoutePath,
    ): Map<SegmentId, SegmentObservationState> = route.segmentIds.distinct().associateWith { segment ->
        SegmentObservationAggregator.aggregate(
            (1..2).map { index ->
                val outcome = RoadObservation.create(
                    HazardReason.WATERLOGGED,
                    WaterLevelCategory.KNEE,
                    PassabilityStatus.CAUTION,
                )
                check(outcome is ObservationValidation.Valid)
                HazardFixtures.observedReport(
                    eventId = "evt-corridor-$index",
                    device = "device-%02d".format(index),
                    segmentId = segment.value,
                    observation = outcome.observation,
                )
            },
        ).getValue(segment)
    }.also { check(graph.segmentCount > it.size) }

    private fun unknownMeters(
        graph: RoutingGraph,
        path: RoutePath,
        states: Map<SegmentId, SegmentObservationState>,
    ): Double {
        var total = 0.0
        for (segment in path.segmentIds) {
            if (!states.containsKey(segment)) total += graph.segmentLengthMeters(segment.value)
        }
        return total
    }

    private fun riskExposure(
        graph: RoutingGraph,
        path: RoutePath,
        states: Map<SegmentId, SegmentObservationState>,
    ): Double {
        var total = 0.0
        for (segment in path.segmentIds) {
            val state = states[segment] ?: continue
            total += graph.segmentLengthMeters(segment.value) * RiskPenaltyPolicy.riskFactor(state)
        }
        return total
    }

    private suspend fun route(
        graph: RoutingGraph,
        start: NodeId,
        destination: NodeId,
        policy: RoutingPolicy,
    ): RoutePath {
        val path = routeOrNull(graph, start, destination, policy)
        checkNotNull(path) { "no route for $start -> $destination" }
        return path
    }

    private suspend fun routeOrNull(
        graph: RoutingGraph,
        start: NodeId,
        destination: NodeId,
        policy: RoutingPolicy,
    ): RoutePath? {
        val result = engine.findRoute(
            RoutingSnapshot(graph.identity, "xph-89", 1, graph, GraphBlocker.NONE),
            RouteRequest(1, start, destination, policy),
        )
        return (result as? RouteResult.Found)?.path
    }

    private fun Double.f(): String = "%.0f".format(this)

    private fun percent(part: Double, whole: Double): String =
        if (whole <= 0.0) "n/a" else "%.1f".format(part / whole * 100.0)

    private companion object {
        const val PAIR_LIMIT = 12
        val MUS = listOf(0.05, 0.1, 0.2, 0.5, 1.0)
    }
}
