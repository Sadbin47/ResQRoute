package com.resqroute.hazard

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
import com.resqroute.dataset.dijkstraCost
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.ObservationValidation
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.RoadObservation
import com.resqroute.domain.hazard.SegmentObservationAggregator
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.domain.risk.RiskPenaltyPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How far should the app detour to avoid a reported road? (XPH-84)
 *
 * A measurement, not a policy: the sweep below prices a **fixed, synthetic report set** over the
 * real Feni graph at eight values of `LAMBDA` and tabulates what each one costs in detour distance
 * and buys in reported risk avoided. The numbers are recorded in ADR 0011; the assertions here keep
 * the harness honest.
 *
 * ## Two metrics, and why they are defined this way
 *
 * * **Exposure** is the reported risk a route carries, in metres:
 *   `Σ over the path's edges of (edge length × riskFactor(state))`. It is **independent of
 *   `LAMBDA`** — exactly the penalty the route would pay at `LAMBDA = 1` — which is what makes
 *   routes at different `LAMBDA` comparable. Without a `LAMBDA`-free measure of "risk traversed", a
 *   sweep would only be comparing a route with itself.
 * * **Detour** is `distance(chosen) − distance(shortest)`, where "shortest" means the route that
 *   minimises distance with no regard for reports. Detour is therefore always `>= 0`, which is
 *   asserted rather than hoped for.
 *
 * ## What this harness deliberately does not claim
 *
 * A larger `LAMBDA` is **not** safer. It trades metres of travel for reported risk, and a longer
 * evacuation route has its own costs: more fuel, more time in the flood, more exposure for people
 * on foot, and more chance of being cut off. The sweep measures that trade; it does not resolve it,
 * and no value here is a safety guarantee.
 *
 * ## Running it
 *
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "com.resqroute.hazard.RiskWeightSweepTest" --console=plain
 * ```
 *
 * Deterministic: a fixed shelter pair, a fixed report set derived from the shortest route, a fixed
 * `LAMBDA` list, no hardware, no network and no clock.
 */
class RiskWeightSweepTest {

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

    /** The first shelter pair (bounded search) that yields a non-trivial route. */
    private val routablePair: Pair<NodeId, NodeId> by lazy {
        val graph = loaded.graph
        val shelters = minOf(graph.shelterCount, 12)
        for (i in 0 until shelters) {
            for (j in i + 1 until shelters) {
                val start = graph.shelter(i).accessNodeId
                val destination = graph.shelter(j).accessNodeId
                val result = runBlocking { routeOrNull(graph, start, destination, RoutingPolicy.DISTANCE_ONLY) }
                if (result != null && result.distanceMeters > 1_000.0) return@lazy start to destination
            }
        }
        error("no routable shelter pair found")
    }

    // ------------------------------------------------------------ the sweep

    @Test
    fun theSweepTabulatesDetourAgainstReportedRiskAvoided() = runBlocking {
        val graph = loaded.graph
        val (start, destination) = routablePair
        val shortest = route(graph, start, destination, RoutingPolicy.DISTANCE_ONLY)

        println(
            "SWEEP DATASET: hash=${loaded.identity.contentHash} format=${loaded.identity.formatVersion} " +
                "edges=${graph.edgeCount} segments=${graph.segmentCount}",
        )
        println(
            "SWEEP PAIR: $start -> $destination · shortest=${shortest.distanceMeters.round()} m " +
                "(the baseline every detour below is measured from)",
        )

        val tabulated = mutableListOf<Row>()
        for (scenario in scenarios(shortest, graph)) {
            val rows = printSweep(graph, start, destination, shortest, scenario)
            assertTableIsInternallyConsistent(rows, shortest, scenario.name)
            tabulated += rows
        }
        assertTrue("the sweep tabulated nothing", tabulated.isNotEmpty())

        // The criterion asks for at least six values; the list is fixed so the table is comparable.
        assertTrue("the sweep must cover at least six lambda values", LAMBDAS.size >= 6)
        assertEquals(9, LAMBDAS.size)
    }

    /** The three fixed scenarios, derived from the shortest route so they are reproducible. */
    private fun scenarios(shortest: RoutePath, graph: RoutingGraph): List<ReportScenario> {
        val corridor = shortest.segmentIds.distinct()
        return listOf(
            ReportScenario(
                name = "corridor-knee",
                description = "every segment of the shortest route reported knee-deep / caution, by 2 devices",
                segments = corridor,
                level = WaterLevelCategory.KNEE,
                verdict = PassabilityStatus.CAUTION,
                devices = 2,
            ),
            ReportScenario(
                name = "corridor-above-waist",
                description = "the same corridor reported above-waist / avoid, by 3 devices",
                segments = corridor,
                level = WaterLevelCategory.ABOVE_WAIST,
                verdict = PassabilityStatus.AVOID,
                devices = 3,
            ),
            ReportScenario(
                name = "scattered-ankle",
                description = "every 37th segment reported ankle-deep / caution by 1 device (a wide, gentle field)",
                segments = (0 until graph.segmentCount step 37).map { SegmentId(it) },
                level = WaterLevelCategory.ANKLE,
                verdict = PassabilityStatus.CAUTION,
                devices = 1,
            ),
        )
    }

    // ------------------------------------------------------- the invariants

    @Test
    fun aLargerLambdaIsNeverCheaperAndNeverShortensTheRoute() = runBlocking {
        val graph = loaded.graph
        val (start, destination) = routablePair
        val shortest = route(graph, start, destination, RoutingPolicy.DISTANCE_ONLY)
        val states = statesFor(
            ReportScenario(
                name = "invariants",
                description = "the shortest route reported above-waist / avoid by 3 devices",
                segments = shortest.segmentIds.distinct(),
                level = WaterLevelCategory.ABOVE_WAIST,
                verdict = PassabilityStatus.AVOID,
                devices = 3,
            ),
        )

        var previousCost = -1.0
        for (lambda in LAMBDAS) {
            val policy = policyFor(graph, states, lambda)
            val path = route(graph, start, destination, policy)

            assertTrue(
                "lambda=$lambda produced a route shorter than the shortest: " +
                    "${path.distanceMeters} < ${shortest.distanceMeters}",
                path.distanceMeters >= shortest.distanceMeters - 1e-6,
            )
            assertTrue(
                "lambda=$lambda broke cost monotonicity: ${path.totalCost} < $previousCost",
                path.totalCost >= previousCost - 1e-6,
            )
            assertTrue("cost fell below distance at lambda=$lambda", path.totalCost >= path.distanceMeters - 1e-9)
            previousCost = path.totalCost

            // Every row must equal the independent reference, or the table would be an artefact of
            // the engine rather than a measurement of the policy.
            val reference = dijkstraCost(graph, start, destination, GraphBlocker.NONE, policy)
            assertEquals("lambda=$lambda: A* and the reference disagree", reference!!, path.totalCost, 1e-6)
        }
    }

    @Test
    fun aLambdaOfZeroReproducesTheShortestRouteExactly() = runBlocking {
        val graph = loaded.graph
        val (start, destination) = routablePair
        val shortest = route(graph, start, destination, RoutingPolicy.DISTANCE_ONLY)
        val states = statesFor(
            ReportScenario(
                name = "zero",
                description = "",
                segments = shortest.segmentIds.distinct(),
                level = WaterLevelCategory.ABOVE_WAIST,
                verdict = PassabilityStatus.AVOID,
                devices = 3,
            ),
        )

        val atZero = route(graph, start, destination, policyFor(graph, states, lambda = 0.0))
        assertEquals("lambda=0 must not move the route", shortest.segmentIds, atZero.segmentIds)
        assertEquals(shortest.distanceMeters, atZero.distanceMeters, 1e-9)
        assertEquals(shortest.totalCost, atZero.totalCost, 1e-9)
    }

    @Test
    fun enoughLambdaActuallyAvoidsReportedRisk() = runBlocking {
        // The sweep is only meaningful if some value changes what the router does. Same spirit as
        // the non-vacuity guard in WeightedRoutingParityTest, which caught a degenerate graph.
        val graph = loaded.graph
        val (start, destination) = routablePair
        val shortest = route(graph, start, destination, RoutingPolicy.DISTANCE_ONLY)
        val states = statesFor(
            ReportScenario(
                name = "non-vacuity",
                description = "",
                segments = shortest.segmentIds.distinct(),
                level = WaterLevelCategory.ABOVE_WAIST,
                verdict = PassabilityStatus.AVOID,
                devices = 3,
            ),
        )
        val shortestExposure = exposure(graph, shortest, states)
        assertTrue("the shortest route must carry reported risk", shortestExposure > 0.0)

        val exposures = LAMBDAS.associateWith { lambda ->
            exposure(graph, route(graph, start, destination, policyFor(graph, states, lambda)), states)
        }
        val best = exposures.minBy { it.value }
        assertTrue(
            "no lambda reduced the reported risk carried, so the sweep proves nothing: $exposures",
            best.value < shortestExposure - 1e-6,
        )
        println(
            "SWEEP NON-VACUITY: shortest exposure=${shortestExposure.round()} m · " +
                "best=${best.value.round()} m at lambda=${best.key}",
        )
    }

    @Test
    fun lambdaIsRefusedWhenItWouldBreakTheProof() {
        val states = statesFor(
            ReportScenario("refuse", "", listOf(SegmentId(0)), WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, 1),
        )
        for (bad in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val thrown = runCatching {
                RiskPenaltyPolicy.forStates(states, bad) { 100.0 }
            }.exceptionOrNull()
            assertTrue("lambda=$bad must be refused, got $thrown", thrown is IllegalArgumentException)
        }
        // Zero is legitimate: it reproduces the distance-only policy exactly.
        assertEquals(
            RoutingPolicy.DISTANCE_ONLY,
            RiskPenaltyPolicy.forStates(states, 0.0) { 100.0 },
        )
    }

    // -------------------------------------------------------------- the table

    private suspend fun printSweep(
        graph: RoutingGraph,
        start: NodeId,
        destination: NodeId,
        shortest: RoutePath,
        scenario: ReportScenario,
    ): List<Row> {
        val states = statesFor(scenario)
        // The baseline is the shortest route measured against THIS scenario's reports, which is
        // what "risk avoided" must be relative to.
        val shortestExposure = exposure(graph, shortest, states)

        val rows = LAMBDAS.map { lambda ->
            val path = route(graph, start, destination, policyFor(graph, states, lambda))
            val carried = exposure(graph, path, states)
            val detour = path.distanceMeters - shortest.distanceMeters
            Row(
                lambda = lambda,
                distance = path.distanceMeters,
                detour = detour,
                detourPercent = detour / shortest.distanceMeters * 100.0,
                exposure = carried,
                avoided = shortestExposure - carried,
                cost = path.totalCost,
            )
        }

        println()
        println("SCENARIO ${scenario.name}: ${scenario.description}")
        println(
            "  reports=${states.size} segments · shortest=${shortest.distanceMeters.round()} m · " +
                "shortest exposure=${shortestExposure.round()} m",
        )
        println("  | lambda | distance m | detour m | detour % | exposure m | avoided m | cost m |")
        println("  | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
        for (row in rows) {
            val marker = if (row.lambda == RiskPenaltyPolicy.LAMBDA) " **shipped**" else ""
            println(
                "  | ${row.lambda}$marker | ${row.distance.round()} | ${row.detour.round()} | " +
                    "${row.detourPercent.round()} | ${row.exposure.round()} | " +
                    "${row.avoided.round()} | ${row.cost.round()} |",
            )
        }

        // The marginal exchange rate across the swept range: metres paid per metre avoided.
        val first = rows.last { it.avoided > 0.0 }
        val best = rows.maxBy { it.avoided }
        if (best.avoided > first.avoided + 1e-9) {
            val ratio = (best.detour - first.detour) / (best.avoided - first.avoided)
            println(
                "  MARGINAL: from lambda=${first.lambda} to ${best.lambda}, " +
                    "${(best.detour - first.detour).round()} m of detour buys " +
                    "${(best.avoided - first.avoided).round()} m of avoided exposure " +
                    "(ratio ${ratio.round(3)} m per m)",
            )
        }
        return rows
    }

    /**
     * Every figure in the table must follow from the others.
     *
     * Added after a mutation showed the detour column could be zeroed without a single test
     * failing: the columns are this phase's output, so a wrong one would be printed and believed.
     */
    private fun assertTableIsInternallyConsistent(rows: List<Row>, shortest: RoutePath, scenario: String) {
        for (row in rows) {
            val label = "$scenario at lambda=${row.lambda}"
            assertEquals("$label: detour is not distance minus the shortest", row.distance - shortest.distanceMeters, row.detour, 1e-6)
            assertTrue("$label: negative detour", row.detour >= -1e-9)
            assertEquals("$label: detour percent disagrees with detour", row.detour / shortest.distanceMeters * 100.0, row.detourPercent, 1e-6)
            assertTrue("$label: cost fell below distance", row.cost >= row.distance - 1e-9)
            assertTrue("$label: negative exposure", row.exposure >= -1e-9)
            assertTrue("$label: more than the shortest route's exposure was carried", row.exposure <= rows.first().exposure + 1e-6)
        }
        // The measured quantity must actually fall as the exchange rate rises.
        val first = rows.first()
        val last = rows.last()
        assertTrue(
            "$scenario: raising lambda did not reduce the reported risk carried " +
                "(${first.exposure} -> ${last.exposure})",
            last.exposure <= first.exposure + 1e-6,
        )
        assertTrue("$scenario: raising lambda lowered the avoided figure", last.avoided >= first.avoided - 1e-6)
    }

    private data class Row(
        val lambda: Double,
        val distance: Double,
        val detour: Double,
        val detourPercent: Double,
        val exposure: Double,
        val avoided: Double,
        val cost: Double,
    )

    private data class ReportScenario(
        val name: String,
        val description: String,
        val segments: List<SegmentId>,
        val level: WaterLevelCategory,
        val verdict: PassabilityStatus,
        val devices: Int,
    )

    // ---------------------------------------------------------------- harness

    /** Real derived state per reported segment, built by the aggregate the app itself uses. */
    private fun statesFor(scenario: ReportScenario): Map<SegmentId, SegmentObservationState> {
        assertTrue("a scenario must report something", scenario.segments.isNotEmpty())
        return scenario.segments.distinct().associateWith { segment ->
            SegmentObservationAggregator.aggregate(
                (1..scenario.devices).map { index ->
                    val outcome = RoadObservation.create(HazardReason.WATERLOGGED, scenario.level, scenario.verdict)
                    check(outcome is ObservationValidation.Valid) { "invalid fixture: $outcome" }
                    HazardFixtures.observedReport(
                        eventId = "evt-%s-%03d".format(scenario.name, index),
                        device = "device-%03d".format(index),
                        segmentId = segment.value,
                        observation = outcome.observation,
                    )
                },
            ).getValue(segment)
        }
    }

    private fun policyFor(
        graph: RoutingGraph,
        states: Map<SegmentId, SegmentObservationState>,
        lambda: Double,
    ): RoutingPolicy = RiskPenaltyPolicy.forStates(states, lambda, graph::segmentLengthMeters)

    private suspend fun route(
        graph: RoutingGraph,
        start: NodeId,
        destination: NodeId,
        policy: RoutingPolicy,
    ): RoutePath {
        val path = routeOrNull(graph, start, destination, policy)
        assertTrue("expected a route", path != null)
        return path!!
    }

    private suspend fun routeOrNull(
        graph: RoutingGraph,
        start: NodeId,
        destination: NodeId,
        policy: RoutingPolicy,
    ): RoutePath? {
        val result = engine.findRoute(
            RoutingSnapshot(graph.identity, "xph-84", 1, graph, GraphBlocker.NONE),
            RouteRequest(1, start, destination, policy),
        )
        return (result as? RouteResult.Found)?.path
    }

    /**
     * Reported risk carried by a path, in metres, independent of `LAMBDA`.
     *
     * Equals the penalty the path would pay at `LAMBDA = 1`, so it measures the same quantity the
     * router prices without inheriting the exchange rate being swept.
     */
    private fun exposure(
        graph: RoutingGraph,
        path: RoutePath,
        states: Map<SegmentId, SegmentObservationState>,
    ): Double {
        var total = 0.0
        for (edge in path.edgeIds.map { it.value }) {
            val state = states[SegmentId(graph.edgeSegmentIndex(edge))] ?: continue
            total += graph.edgeCostMeters(edge) * RiskPenaltyPolicy.riskFactor(state)
        }
        return total
    }

    private fun Double.round(decimals: Int = 2): String = "%.${decimals}f".format(this)

    private companion object {
        /**
         * From "ignore reports" to "avoid almost absolutely", so the trade-off is visible rather
         * than a winner picked by construction.
         */
        val LAMBDAS = listOf(0.0, 0.25, 0.5, 1.0, 1.5, 2.0, 4.0, 8.0, 16.0)
    }
}
