package com.resqroute.presentation

import com.resqroute.core.model.SegmentId
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.HazardSnapshot
import com.resqroute.domain.hazard.HazardState
import com.resqroute.domain.hazard.ObservedReport
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.SegmentObservationAggregator
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.domain.risk.RiskPenaltyPolicy
import com.resqroute.feature.map.presentation.MapUiState
import com.resqroute.hazard.HazardFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which roads the map shows as "reported, still usable" (XPH-92).
 *
 * The complaint this answers: a road reported knee-deep **and passable** was accepted, priced and
 * routed around, and the map drew **nothing** for it — only green routes and red closures existed.
 * From a phone that reads as "my report did nothing".
 *
 * The fix is not "draw every report": it is to draw exactly the roads the **router prices**, so the
 * map and the routing cost agree by construction. The last test here is that guarantee — if the set
 * and the policy ever disagree, it fails rather than the map quietly lying.
 */
class PricedSegmentsTest {

    @Test
    fun aRiskyButUsableRoadIsShown() {
        // Knee-deep and passable: exactly the report that used to be invisible.
        val shown = pricedIn(stateOf(report("evt-1", WaterLevelCategory.KNEE, PassabilityStatus.CAUTION)))
        assertTrue("a knee-deep caution report must be visible on the map", SegmentId(SEGMENT) in shown)
    }

    @Test
    fun aRoadReportedImpassableIsDrawnAsAClosureInstead() {
        // It appears in red as an exclusion. Painting it amber as well would make the two marks
        // ambiguous, and the red one is the answer that must not be diluted.
        val state = stateOf(report("evt-1", WaterLevelCategory.WAIST, PassabilityStatus.IMPASSABLE))
        assertTrue("it is excluded", state.excludesTraversal)
        assertFalse(SegmentId(SEGMENT) in pricedIn(state))
    }

    @Test
    fun aRoadSomebodyLookedAtAndFoundClearIsNotPainted() {
        // "No water seen, passable" is information with no risk. Painting it would imply a hazard
        // nobody observed — the app would be warning about a road it considers open.
        val state = stateOf(report("evt-1", WaterLevelCategory.NO_VISIBLE_WATER, PassabilityStatus.PASSABLE_REPORTED))
        assertEquals("nothing there carries risk", 0.0, RiskPenaltyPolicy.riskFactor(state), 1e-9)
        assertFalse(SegmentId(SEGMENT) in pricedIn(state))
    }

    @Test
    fun anUnreadableRowIsAClosureRatherThanAUsableReport() {
        val state = SegmentObservationAggregator.aggregateSegment(
            SegmentId(SEGMENT),
            listOf(HazardFixtures.unreadableReport("evt-u", "device-b", SEGMENT)),
        )
        assertTrue("an unreadable row fails closed", state.excludesTraversal)
        assertFalse(SegmentId(SEGMENT) in pricedIn(state))
    }

    @Test
    fun theShownSetIsExactlyWhatTheRouterPrices() {
        // The design guarantee, stated as a test: the map's amber set and the routing policy's priced
        // set are the same set. Two independent definitions would drift; this one cannot, because
        // both read `riskFactor`.
        val states = mapOf(
            SegmentId(1) to stateOf(report("e1", WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, segment = 1)),
            SegmentId(2) to stateOf(report("e2", WaterLevelCategory.WAIST, PassabilityStatus.IMPASSABLE, segment = 2)),
            SegmentId(3) to stateOf(report("e3", WaterLevelCategory.NO_VISIBLE_WATER, PassabilityStatus.PASSABLE_REPORTED, segment = 3)),
            SegmentId(4) to stateOf(report("e4", WaterLevelCategory.ANKLE, PassabilityStatus.AVOID, segment = 4)),
            SegmentId(5) to stateOf(
                HazardFixtures.unreadableReport("e5", "device-b", 5),
            ),
        )

        val pricedByPolicy = RiskPenaltyPolicy.forStates(states) { 100.0 }.segmentPenaltyMeters.keys
        val pricedByMap = MapUiState.Initial.copy(hazardState = readyWith(states)).pricedSegments

        assertEquals("the map must paint exactly the roads the router pays for", pricedByPolicy, pricedByMap)
        assertEquals("and the fixture must be non-trivial", setOf(SegmentId(1), SegmentId(4)), pricedByMap)
    }

    // ------------------------------------------------------------------ fixture

    private fun report(
        eventId: String,
        level: WaterLevelCategory,
        verdict: PassabilityStatus,
        segment: Int = SEGMENT,
    ): ObservedReport = HazardFixtures.observedReport(
        eventId = eventId,
        device = "device-b",
        segmentId = segment,
        observation = HazardFixtures.observation(HazardReason.WATERLOGGED, level, verdict),
    )

    private fun stateOf(report: ObservedReport): SegmentObservationState =
        SegmentObservationAggregator.aggregateSegment(SegmentId(report.segmentId.value), listOf(report))

    private fun pricedIn(state: SegmentObservationState): Set<SegmentId> =
        MapUiState.Initial.copy(hazardState = readyWith(mapOf(state.segmentId to state))).pricedSegments

    private fun readyWith(states: Map<SegmentId, SegmentObservationState>): HazardState =
        HazardState.Ready(
            HazardSnapshot(HazardFixtures.contextAlpha, revision = 0L, blockedSegments = emptySet()),
            states,
        )

    private companion object {
        const val SEGMENT = 42
    }
}
