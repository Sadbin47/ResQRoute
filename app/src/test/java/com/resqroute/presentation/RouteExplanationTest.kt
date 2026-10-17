package com.resqroute.presentation

import android.app.Application
import android.content.Context
import com.resqroute.core.model.DatasetIdentity
import com.resqroute.core.model.EdgeId
import com.resqroute.core.model.NodeId
import com.resqroute.core.model.SegmentId
import com.resqroute.core.routing.RoutePath
import com.resqroute.domain.hazard.ClosureFreshness
import com.resqroute.domain.hazard.DeviceId
import com.resqroute.domain.hazard.EventId
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.ObservationSource
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.ReportContent
import com.resqroute.domain.hazard.RoadObservation
import com.resqroute.domain.hazard.SegmentObservationAggregator
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.feature.map.presentation.OfferedRoute
import com.resqroute.feature.map.presentation.RouteExplanation
import com.resqroute.feature.map.presentation.RouteExplanationCopy
import com.resqroute.domain.risk.RiskPenaltyPolicy
import com.resqroute.feature.map.presentation.RouteOptions
import com.resqroute.wording.WordingGate
import com.resqroute.hazard.HazardFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The route explanation (XPH-77): comparison, avoid list and unknown coverage.
 *
 * Its fixture builders are `internal` on purpose: [RouteExplanationScreenshotTest] renders the same
 * four documented cases, and building them twice would let the rendered evidence drift from the
 * assertions.
 *
 * Every assertion here is about what a person is told. The two that carry the most weight are
 * negative — an unreported length is never worded as safety, and no line implies a guarantee — so
 * they are stated as sweeps over everything the sheet can render rather than as single examples.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RouteExplanationTest {

    private val context: Context get() = HazardFixtures.context()

    /** The instant every fixture is built against; shared with the screenshot render. */
    internal val now = 1_700_000_000_000L

    // ----------------------------------------------------------- comparison

    @Test
    fun differingRoutesAreComparedByDistanceAndDetour() {
        val explanation = explain(
            shortest = routeOf(LENGTHS_A, 0, 1),
            lowerRisk = routeOf(LENGTHS_B, 2, 3),
            lengths = mapOf(0 to 100.0, 1 to 100.0, 2 to 120.0, 3 to 120.0),
            states = emptyMap(),
        )

        assertTrue(explanation is RouteExplanation.Comparison)
        val comparison = explanation as RouteExplanation.Comparison
        assertEquals(200.0, comparison.shortest.route.distanceMeters, 1e-6)
        assertEquals(240.0, comparison.lowerRisk.route.distanceMeters, 1e-6)
        assertTrue(comparison.hasLowerRiskAlternative)
        assertEquals(
            "Lower-reported-risk route: 40 m longer (240 m total).",
            RouteExplanationCopy.comparisonLine(context, explanation),
        )
    }

    @Test
    fun identicalRoutesCollapseToOneLineAndSayWhy() {
        val explanation = explain(
            shortest = routeOf(LENGTHS_A, 0, 1),
            lowerRisk = routeOf(LENGTHS_A, 0, 1),
            lengths = mapOf(0 to 100.0, 1 to 100.0),
            states = emptyMap(),
        )

        assertTrue(explanation is RouteExplanation.Single)
        assertEquals(1, explanation.reports.size)
        assertFalse(explanation.hasLowerRiskAlternative)
        assertTrue(explanation.hazardsAvoidedByLowerRisk.isEmpty())
        assertEquals(
            "Shortest and lower-reported-risk routes are the same road.",
            RouteExplanationCopy.comparisonLine(context, explanation),
        )
        assertNull(RouteExplanationCopy.avoidedLine(context, explanation))
    }

    @Test
    fun anAlternativeOfEqualLengthIsNotDescribedAsALongerOne() {
        val explanation = explain(
            shortest = routeOf(LENGTHS_EQUAL, 0, 1),
            lowerRisk = routeOf(LENGTHS_EQUAL, 2, 3),
            lengths = LENGTHS_EQUAL,
            states = emptyMap(),
        )
        assertEquals(
            "A lower-reported-risk alternative was found, with the same distance.",
            RouteExplanationCopy.comparisonLine(context, explanation),
        )
    }

    // --------------------------------------------------------- avoid list

    @Test
    fun theAvoidListNamesTheRoadWhatWasReportedHowOldAndHowWellSupported() {
        val states = mapOf(
            SegmentId(0) to riskState(
                SegmentId(0),
                WaterLevelCategory.KNEE,
                PassabilityStatus.CAUTION,
                devices = 3,
                relayed = 1,
                receivedAgoMillis = 2 * HOUR,
            ),
        )
        val explanation = explain(
            shortest = routeOf(LENGTHS_A, 0, 1),
            lowerRisk = routeOf(LENGTHS_B, 2, 3),
            lengths = mapOf(0 to 100.0, 1 to 100.0, 2 to 120.0, 3 to 120.0),
            states = states,
        )
        val hazard = (explanation as RouteExplanation.Comparison).shortest.hazardsCrossed.single()

        assertEquals(SegmentId(0), hazard.segmentId)
        assertEquals(WaterLevelCategory.KNEE, hazard.waterLevel)
        assertEquals(PassabilityStatus.CAUTION, hazard.passability)
        assertEquals(3, hazard.supportCount)
        assertEquals(2, hazard.directSupportCount)
        assertEquals(1, hazard.relayedSupportCount)
        assertEquals(2 * HOUR, hazard.ageMillis)
        assertTrue(hazard.timeVerified)

        val entry = RouteExplanationCopy.avoidEntry(context, hazard)
        assertTrue("the stable segment id must be named: $entry", entry.contains(SegmentId(0).stableId))
        assertTrue("the category must be named: $entry", entry.contains("knee-deep") && entry.contains("caution"))
        assertTrue("the age must be named: $entry", entry.contains("2 h ago"))
        assertTrue("the support must be a device count: $entry", entry.contains("3 device IDs"))
        assertTrue("and who told us: $entry", entry.contains("1 relayed"))
        assertEquals(
            "the header names the route's own distance",
            "Reported on the 200 m route: 1",
            RouteExplanationCopy.avoidHeader(context, (explanation).shortest),
        )
    }

    @Test
    fun whatTheLowerRiskRouteAvoidsIsListed() {
        val states = mapOf(
            SegmentId(0) to riskState(SegmentId(0), WaterLevelCategory.WAIST, PassabilityStatus.AVOID, devices = 1),
            SegmentId(2) to riskState(SegmentId(2), WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, devices = 1),
        )
        val explanation = explain(
            shortest = routeOf(LENGTHS_A, 0, 1),
            lowerRisk = routeOf(LENGTHS_B, 2, 3),
            lengths = mapOf(0 to 100.0, 1 to 100.0, 2 to 120.0, 3 to 120.0),
            states = states,
        )
        val comparison = explanation as RouteExplanation.Comparison

        assertEquals("the shortest route crosses its own reported road", 1, comparison.shortest.hazardsCrossed.size)
        assertEquals("the alternative crosses a different one", 1, comparison.lowerRisk.hazardsCrossed.size)
        assertEquals(
            "the alternative avoids segment 0 but not segment 2",
            listOf(SegmentId(0)),
            comparison.hazardsAvoidedByLowerRisk.map { it.segmentId },
        )
        assertEquals("It avoids 1 reported road.", RouteExplanationCopy.avoidedLine(context, explanation))
    }

    @Test
    fun aRoadThisBuildCannotReadIsCountedAsObservedAndNeverAsACrossedHazard() {
        // An unreadable row excludes the road (ADR 0004 §2), so the router never traverses it and it
        // can never be listed as something a route crosses. It *is* observed, though: somebody
        // reported there, so it is not part of the unknown coverage either.
        val states = mapOf(
            SegmentId(0) to SegmentObservationAggregator.aggregate(
                listOf(HazardFixtures.unreadableReport("evt-x", "device-b", 0, reasonCode = "FLOODED_DEEPER")),
            ).getValue(SegmentId(0)),
        )
        val explanation = explain(
            shortest = routeOf(mapOf(0 to 100.0), 0),
            lowerRisk = routeOf(mapOf(0 to 100.0), 0),
            lengths = mapOf(0 to 100.0),
            states = states,
        )
        val report = explanation.reports.single()

        assertTrue("an unreadable row still counts as reported", states.getValue(SegmentId(0)).hasUnreadableReports)
        assertTrue("so it is observed, not unknown", report.unknownLengthMeters == 0.0)
        assertTrue("and it is never a hazard a route crosses, because it closes the road", report.hazardsCrossed.isEmpty())
        assertFalse(report.crossesReportedRisk)
    }

    @Test
    fun theExplanationAgreesWithTheFlagTheRouterPublished() {
        // The view model decides `crossesReportedRisk` from the risk policy's priced set; the
        // explanation derives it from the hazard list. They must never disagree, or the sheet would
        // contradict the line the app already shows.
        val states = mapOf(
            SegmentId(0) to riskState(SegmentId(0), WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, devices = 1),
        )
        val explanation = explain(
            shortest = routeOf(LENGTHS_A, 0, 1),
            lowerRisk = routeOf(LENGTHS_B, 2, 3),
            lengths = LENGTHS_AB,
            states = states,
        )
        val shortest = (explanation as RouteExplanation.Comparison).shortest
        assertTrue("the reported road is priced, so the router already says so", shortest.crossesReportedRisk)
        assertEquals(
            "the hazard list and the published flag must agree",
            shortest.crossesReportedRisk,
            shortest.hazardsCrossed.isNotEmpty(),
        )
        assertFalse(
            "the unreported alternative crosses nothing",
            explanation.lowerRisk.crossesReportedRisk,
        )
    }

    // ----------------------------------------------------- unknown coverage

    @Test
    fun unknownCoverageIsReportedAsALengthOfTheRoute() {
        // Segment 0 is reported; 1 and 2 are not.
        val states = mapOf(
            SegmentId(0) to riskState(SegmentId(0), WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, devices = 1),
        )
        val explanation = explain(
            shortest = routeOf(LENGTHS_C, 0, 1, 2),
            lowerRisk = routeOf(LENGTHS_C, 0, 1, 2),
            lengths = mapOf(0 to 100.0, 1 to 200.0, 2 to 100.0),
            states = states,
        )
        val report = explanation.reports.single()

        assertEquals(100.0, report.observedLengthMeters, 1e-6)
        assertEquals(300.0, report.unknownLengthMeters, 1e-6)
        assertEquals("every metre is either observed or not", 400.0, report.totalLengthMeters, 1e-6)
        assertEquals(0.75, report.unknownProportion, 1e-9)
        assertEquals(
            "No reports for 300 m of this 400 m route.",
            RouteExplanationCopy.unknownLine(context, report),
        )
        assertEquals(
            "Most of this route has no reports.",
            RouteExplanationCopy.mostlyUnknownLine(context, report),
        )
    }

    @Test
    fun aWhollyUnreportedRouteIsNotToldTwice() {
        // "No reports for 480 m of this 480 m route" is already complete; the emphasis line would
        // only repeat it.
        val explanation = explain(
            shortest = routeOf(LENGTHS_C, 0, 1, 2),
            lowerRisk = routeOf(LENGTHS_C, 0, 1, 2),
            lengths = LENGTHS_C,
            states = emptyMap(),
        )
        val report = explanation.reports.single()
        assertEquals(1.0, report.unknownProportion, 1e-9)
        assertNull(RouteExplanationCopy.mostlyUnknownLine(context, report))
        assertEquals("No reports for 400 m of this 400 m route.", RouteExplanationCopy.unknownLine(context, report))
    }

    @Test
    fun aWellCoveredRouteDoesNotClaimToBeMostlyUnknown() {
        val states = (0..9).associate { index ->
            SegmentId(index) to riskState(SegmentId(index), WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, devices = 1)
        }
        val explanation = explain(
            shortest = routeOf(TEN_BY_HUNDRED, *(0..9).toList().toIntArray()),
            lowerRisk = routeOf(TEN_BY_HUNDRED, *(0..9).toList().toIntArray()),
            lengths = (0..9).associateWith { 100.0 },
            states = states,
        )
        val report = explanation.reports.single()
        assertEquals(0.0, report.unknownLengthMeters, 1e-9)
        assertNull(RouteExplanationCopy.mostlyUnknownLine(context, report))
        assertEquals("No reports for 0 m of this 1,000 m route.", RouteExplanationCopy.unknownLine(context, report))
    }

    @Test
    fun aSegmentSomebodyLookedAtAndFoundPassableCountsAsObserved() {
        // "no water, passable" is information: the road is not unreported, and it is not a hazard.
        val states = mapOf(
            SegmentId(0) to SegmentObservationAggregator.aggregate(
                listOf(
                    HazardFixtures.observedReport(
                        "evt-passable", "device-a", 0,
                        HazardFixtures.observation(
                            HazardReason.WATERLOGGED,
                            WaterLevelCategory.NO_VISIBLE_WATER,
                            PassabilityStatus.PASSABLE_REPORTED,
                        ),
                    ),
                ),
            ).getValue(SegmentId(0)),
        )
        val explanation = explain(
            shortest = routeOf(mapOf(0 to 100.0), 0),
            lowerRisk = routeOf(mapOf(0 to 100.0), 0),
            lengths = mapOf(0 to 100.0),
            states = states,
        )
        val report = explanation.reports.single()

        assertEquals("an observed road is not unknown", 0.0, report.unknownLengthMeters, 1e-9)
        assertTrue("and it is not a hazard either", report.hazardsCrossed.isEmpty())
        assertFalse(report.crossesReportedRisk)
    }

    // ------------------------------------------------- the no-alternative case

    @Test
    fun whenTheOnlyRouteCrossesAReportTheExplanationSaysSo() {
        val states = mapOf(
            SegmentId(0) to riskState(SegmentId(0), WaterLevelCategory.ABOVE_WAIST, PassabilityStatus.AVOID, devices = 2),
        )
        val explanation = explain(
            shortest = routeOf(LENGTHS_A, 0, 1),
            lowerRisk = routeOf(LENGTHS_A, 0, 1),
            lengths = mapOf(0 to 100.0, 1 to 100.0),
            states = states,
        )
        val report = explanation.reports.single()
        assertTrue(report.crossesReportedRisk)
        assertTrue(explanation.everyCandidateCrossesReportedRisk)
        assertEquals(
            "No lower-reported-risk alternative exists, and this route crosses reported flooding.",
            RouteExplanationCopy.noAlternativeLine(context, explanation),
        )
        assertEquals(
            "This route crosses 1 reported road.",
            RouteExplanationCopy.crossedLine(context, report),
        )
    }

    @Test
    fun whenEveryCandidateCrossesAReportThatIsStatedToo() {
        val states = mapOf(
            SegmentId(0) to riskState(SegmentId(0), WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, devices = 1),
            SegmentId(2) to riskState(SegmentId(2), WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, devices = 1),
        )
        val explanation = explain(
            shortest = routeOf(LENGTHS_A, 0, 1),
            lowerRisk = routeOf(LENGTHS_B, 2, 3),
            lengths = mapOf(0 to 100.0, 1 to 100.0, 2 to 120.0, 3 to 120.0),
            states = states,
        )
        assertTrue(explanation.hasLowerRiskAlternative)
        assertTrue(explanation.everyCandidateCrossesReportedRisk)
        assertEquals(
            "Every route offered crosses reported flooding.",
            RouteExplanationCopy.noAlternativeLine(context, explanation),
        )
    }

    @Test
    fun aDecisionWithNoReportedRoadsMakesNoNoAlternativeClaim() {
        val explanation = explain(
            shortest = routeOf(LENGTHS_A, 0, 1),
            lowerRisk = routeOf(LENGTHS_A, 0, 1),
            lengths = mapOf(0 to 100.0, 1 to 100.0),
            states = emptyMap(),
        )
        assertNull(RouteExplanationCopy.noAlternativeLine(context, explanation))
        assertNull(RouteExplanationCopy.crossedLine(context, explanation.reports.single()))
        assertFalse(explanation.everyCandidateCrossesReportedRisk)
    }

    // ------------------------------------------------------- age and clock rules

    @Test
    fun anOldReportIsAgedAndAVeryOldOneIsStillShown() {
        val states = mapOf(
            SegmentId(0) to riskState(
                SegmentId(0), WaterLevelCategory.KNEE, PassabilityStatus.CAUTION,
                devices = 1, receivedAgoMillis = 4L * 24 * HOUR,
            ),
        )
        val hazard = explain(
            shortest = routeOf(LENGTHS_A, 0, 1), lowerRisk = routeOf(LENGTHS_A, 0, 1),
            lengths = mapOf(0 to 100.0, 1 to 100.0), states = states,
        ).reports.single().hazardsCrossed.single()

        assertEquals(ClosureFreshness.VERY_STALE, hazard.freshness)
        assertEquals(4L, hazard.ageMillis!! / DAY)
        assertTrue(RouteExplanationCopy.age(context, hazard).contains("4 d ago"))
    }

    @Test
    fun anUnverifiedClaimedTimeIsLabelledRatherThanRepaired() {
        val states = mapOf(
            SegmentId(0) to SegmentObservationAggregator.aggregate(
                listOf(
                    HazardFixtures.observedReport(
                        "evt-future", "device-a", 0,
                        HazardFixtures.observation(HazardReason.BLOCKED, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION),
                        occurredAt = now + 10L * 24 * HOUR,
                        receivedAt = now - HOUR,
                    ),
                ),
            ).getValue(SegmentId(0)),
        )
        val hazard = explain(
            shortest = routeOf(LENGTHS_A, 0, 1), lowerRisk = routeOf(LENGTHS_A, 0, 1),
            lengths = mapOf(0 to 100.0, 1 to 100.0), states = states,
        ).reports.single().hazardsCrossed.single()

        assertFalse(hazard.timeVerified)
        assertEquals(ClosureFreshness.TIME_NOT_VERIFIED, hazard.freshness)
        assertEquals("reported, time not verified", RouteExplanationCopy.age(context, hazard))
    }

    // ------------------------------------- the wording gates, as sweeps

    @Test
    fun noRenderedLineCallsARouteSafeOrClaimsConfidence() {
        // Every case the sheet can face, so the gate is a sweep rather than an example.
        val cases = listOf(
            explain(routeOf(LENGTHS_A, 0, 1), routeOf(LENGTHS_A, 0, 1), LENGTHS_A, emptyMap()),
            explain(
                routeOf(LENGTHS_AB, 0, 1), routeOf(LENGTHS_AB, 2, 3), LENGTHS_AB,
                mapOf(SegmentId(0) to riskState(SegmentId(0), WaterLevelCategory.WAIST, PassabilityStatus.AVOID, devices = 2)),
            ),
            explain(
                routeOf(LENGTHS_AB, 0, 1), routeOf(LENGTHS_AB, 2, 3), LENGTHS_AB,
                mapOf(
                    SegmentId(0) to riskState(SegmentId(0), WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, devices = 1),
                    SegmentId(2) to riskState(SegmentId(2), WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, devices = 1),
                ),
            ),
            explain(
                routeOf(LENGTHS_C, 0, 1, 2), routeOf(LENGTHS_C, 0, 1, 2), LENGTHS_C,
                mapOf(SegmentId(0) to riskState(SegmentId(0), WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, devices = 1)),
            ),
            explain(
                routeOf(LENGTHS_A, 0, 1), routeOf(LENGTHS_A, 0, 1), LENGTHS_A,
                mapOf(SegmentId(0) to SegmentObservationAggregator.aggregate(
                    listOf(HazardFixtures.unreadableReport("evt-u", "device-b", 0)),
                ).getValue(SegmentId(0))),
            ),
        )

        for ((index, explanation) in cases.withIndex()) {
            val rendered = RouteExplanationCopy.sheetLines(context, explanation).joinToString("\n")
            val violations = WordingGate.renderedViolations(rendered)
            assertTrue(
                "case $index said $violations, which claims more than a report can support:\n$rendered",
                violations.isEmpty(),
            )
        }
    }

    @Test
    fun unknownIsNeverWordedAsClearOrAsAValidRoute() {
        // The acceptance criterion in its negative form: an unreported stretch must not be described
        // as anything resembling a clean road.
        val explanation = explain(
            shortest = routeOf(LENGTHS_A, 0, 1), lowerRisk = routeOf(LENGTHS_A, 0, 1), mapOf(0 to 100.0, 1 to 100.0),
            states = mapOf(SegmentId(0) to riskState(SegmentId(0), WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, devices = 1)),
        )
        val rendered = RouteExplanationCopy.sheetLines(context, explanation).joinToString("\n").lowercase()

        for (token in listOf("clear", "safe", "no hazards", "no risks", "nothing to avoid", "guaranteed", "clean")) {
            assertFalse("unknown coverage was worded as \"$token\":\n$rendered", rendered.contains(token))
        }
        assertTrue("the disclosure must be quantitative", rendered.contains("no reports for"))
    }

    @Test
    fun theUnknownCoverageSentenceCarriesBothNumbers() {
        val explanation = explain(
            shortest = routeOf(LENGTHS_A, 0, 1), lowerRisk = routeOf(LENGTHS_A, 0, 1), mapOf(0 to 100.0, 1 to 300.0),
            states = mapOf(SegmentId(0) to riskState(SegmentId(0), WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, devices = 1)),
        )
        val line = RouteExplanationCopy.unknownLine(context, explanation.reports.single())
        assertTrue(line, line.contains("300 m"))
        assertTrue(line, line.contains("400 m"))
    }

    // -------------------------------------------------------------- fixture

    internal fun explain(
        shortest: RoutePath,
        lowerRisk: RoutePath,
        lengths: Map<Int, Double>,
        states: Map<SegmentId, SegmentObservationState>,
    ): RouteExplanation = RouteExplanation.of(
        options = RouteOptions.of(
            shortest = OfferedRoute(shortest, crossesRisk(shortest, states, lengths)),
            lowerRisk = OfferedRoute(lowerRisk, crossesRisk(lowerRisk, states, lengths)),
        ),
        states = states,
        segmentLengthMeters = { lengths.getValue(it) },
        now = now,
    )

    /**
     * A route over [segments] whose distance is the sum of the same per-segment lengths the
     * explanation is handed, so the two cannot disagree about how long the route is.
     */
    /**
     * The view model's own rule, reproduced: the flag comes from the risk policy's priced set.
     *
     * Deliberately the same source the router used, so a test can compare the explanation's own
     * `crossesReportedRisk` against it and catch the two drifting apart.
     */
    internal fun crossesRisk(
        route: RoutePath,
        states: Map<SegmentId, SegmentObservationState>,
        lengths: Map<Int, Double>,
    ): Boolean {
        val policy = RiskPenaltyPolicy.forStates(states) { lengths.getValue(it) }
        return route.segmentIds.any { it in policy.segmentPenaltyMeters }
    }

    internal fun routeOf(
        lengths: Map<Int, Double>,
        vararg segments: Int,
    ): RoutePath {
        val distance = segments.sumOf { lengths.getValue(it) }
        return RoutePath(
            dataset = DatasetIdentity("explanation-test", 1),
            incidentId = "incident-alpha",
            requestGeneration = 1,
            hazardRevision = 1,
            nodeIds = (0..segments.size).map { NodeId(100L + it) },
            edgeIds = segments.map { EdgeId(it) },
            segmentIds = segments.map { SegmentId(it) },
            distanceMeters = distance,
            totalCost = distance,
        )
    }

    internal fun riskState(
        segment: SegmentId,
        level: WaterLevelCategory,
        verdict: PassabilityStatus,
        devices: Int,
        relayed: Int = 0,
        receivedAgoMillis: Long = HOUR,
        occurredAgoMillis: Long = receivedAgoMillis,
    ): SegmentObservationState = SegmentObservationAggregator.aggregate(
        (1..devices).map { index ->
            HazardFixtures.observedReport(
                eventId = "evt-$segment-$index",
                device = "device-%02d".format(index),
                segmentId = segment.value,
                observation = HazardFixtures.observation(HazardReason.WATERLOGGED, level, verdict),
                source = if (index <= devices - relayed) ObservationSource.DIRECT_OBSERVATION else ObservationSource.RELAYED_ACCOUNT,
                occurredAt = now - occurredAgoMillis,
                receivedAt = now - receivedAgoMillis,
            )
        },
    ).getValue(segment)

    internal companion object {
        const val HOUR = 60L * 60 * 1000
        val LENGTHS_A = mapOf(0 to 100.0, 1 to 100.0)
        val LENGTHS_B = mapOf(2 to 120.0, 3 to 120.0)
        val LENGTHS_AB = LENGTHS_A + LENGTHS_B
        val LENGTHS_EQUAL = mapOf(0 to 100.0, 1 to 100.0, 2 to 100.0, 3 to 100.0)
        val LENGTHS_C = mapOf(0 to 100.0, 1 to 200.0, 2 to 100.0)
        val TEN_BY_HUNDRED = (0..9).associateWith { 100.0 }
        const val DAY = 24L * HOUR
        const val DEFAULT_LENGTH = 100.0

        /** The shared gate's policy; see [WordingGate] for what it refuses and why. */
        val FORBIDDEN_TOKENS = WordingGate.FORBIDDEN_TOKENS
    }
}
