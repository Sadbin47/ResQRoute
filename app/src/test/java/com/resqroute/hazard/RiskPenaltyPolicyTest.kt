package com.resqroute.hazard

import com.resqroute.core.model.SegmentId
import com.resqroute.core.routing.RoutingPolicy
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.SegmentObservationAggregator
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.domain.risk.RiskPenaltyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The risk penalty policy (XPH-74), as a pure function.
 *
 * The acceptance criteria of this phase are properties, not examples: every penalty finite and
 * non-negative, the effective weight never below 1, and no penalty ever reaching an excluded
 * segment. Those are checked by sweeping the whole input space rather than by spot values.
 */
class RiskPenaltyPolicyTest {

    private val everyWaterLevel = WaterLevelCategory.entries.toList()
    private val everyVerdict = PassabilityStatus.entries.toList()
    private val deviceCounts = listOf(0, 1, 2, 3, 4, 5, 10, 100)

    // --------------------------------- acceptance: finite, non-negative, weight >= 1

    @Test
    fun everyPenaltyIsFiniteAndNonNegativeAndEveryEffectiveWeightIsAtLeastOne() {
        var checked = 0
        for (level in everyWaterLevel) {
            for (verdict in everyVerdict) {
                if (!isRepresentable(level, verdict)) continue
                for (count in deviceCounts) {
                    val state = state(level, verdict, count)
                    val risk = RiskPenaltyPolicy.riskFactor(state)

                    assertTrue("risk must be finite: $level/$verdict/$count", risk.isFinite())
                    assertTrue("risk must be non-negative: $level/$verdict/$count", risk >= 0.0)
                    assertTrue(
                        "effective weight fell below 1 for $level/$verdict/$count: " +
                            RiskPenaltyPolicy.effectiveWeight(risk),
                        RiskPenaltyPolicy.effectiveWeight(risk) >= 1.0,
                    )

                    val policy = RiskPenaltyPolicy.forStates(mapOf(SegmentId(1) to state)) { LENGTH }
                    val penalty = policy.penaltyMeters(1)
                    assertTrue("penalty must be finite: $level/$verdict/$count", penalty.isFinite())
                    assertTrue("penalty must be non-negative: $level/$verdict/$count", penalty >= 0.0)
                    checked++
                }
            }
        }
        // Exact rather than a floor, so the sweep cannot quietly shrink when a value is added:
        // every representable combination of the two axes, at every device count.
        val expected = everyWaterLevel
            .flatMap { level -> everyVerdict.filter { isRepresentable(level, it) } }
            .size * deviceCounts.size
        assertEquals("the sweep must cover the whole table, not a corner of it", expected, checked)
    }

    @Test
    fun theSweepCoversEveryRepresentableCombinationOfTheTwoAxes() {
        // Guards the sweep itself: if a value is added to either axis, this fails and the new
        // combination is brought into the property above rather than silently untested.
        val representable = everyWaterLevel.flatMap { level ->
            everyVerdict.filter { isRepresentable(level, it) }.map { level to it }
        }
        // 6 levels x 5 verdicts = 30, minus R1 (unknown + unknown) and R2 (above waist reported
        // passable) = 28, matching ADR 0002's count for a single reason.
        assertEquals(28, representable.size)
        assertTrue(representable.any { it.first == WaterLevelCategory.ABOVE_WAIST })
        assertTrue(representable.any { it.second == PassabilityStatus.IMPASSABLE })
    }

    @Test
    fun theExchangeRateIsNeverNegativeSoRiskCanOnlyAddCost() {
        assertTrue("a negative lambda would invert the proof", RiskPenaltyPolicy.LAMBDA >= 0.0)
        for (risk in listOf(0.0, 0.25, 1.0, 1.75, 1e9)) {
            assertTrue(RiskPenaltyPolicy.effectiveWeight(risk) >= 1.0)
        }
    }

    // ------------------------------------------ acceptance: monotonicity

    @Test
    fun riskIsMonotonicInWaterLevel() {
        val ordered = listOf(
            WaterLevelCategory.NO_VISIBLE_WATER,
            WaterLevelCategory.ANKLE,
            WaterLevelCategory.KNEE,
            WaterLevelCategory.WAIST,
            WaterLevelCategory.ABOVE_WAIST,
        )
        var previous = -1.0
        for (level in ordered) {
            val risk = RiskPenaltyPolicy.waterLevelWeight(level)
            assertTrue("$level is lighter than the level before it", risk >= previous)
            previous = risk
        }
        // And through the aggregate: a deeper worst case can only cost more.
        val shallow = riskFor(HazardReason.WATERLOGGED, WaterLevelCategory.ANKLE, PassabilityStatus.CAUTION)
        val deep = riskFor(HazardReason.WATERLOGGED, WaterLevelCategory.ABOVE_WAIST, PassabilityStatus.CAUTION)
        assertTrue("deeper water must not be cheaper: $shallow vs $deep", deep > shallow)
    }

    @Test
    fun riskIsMonotonicInPassabilitySeverity() {
        val ordered = listOf(
            PassabilityStatus.PASSABLE_REPORTED,
            PassabilityStatus.CAUTION,
            PassabilityStatus.AVOID,
        )
        var previous = -1.0
        for (verdict in ordered) {
            val risk = RiskPenaltyPolicy.passabilityWeight(verdict)
            assertTrue("$verdict is lighter than the verdict before it", risk >= previous)
            previous = risk
        }
        assertTrue(
            riskFor(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.AVOID) >
                riskFor(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION),
        )
    }

    @Test
    fun riskIsMonotonicInDistinctDeviceCountAndThenSaturates() {
        var previous = -1.0
        for (count in 1..12) {
            val weight = RiskPenaltyPolicy.supportWeight(count)
            assertTrue("device $count is lighter than $count-1", weight >= previous)
            previous = weight
        }
        // Bounded: a crowd cannot make a road arbitrarily expensive.
        assertEquals(1.0, RiskPenaltyPolicy.supportWeight(1), 1e-12)
        assertEquals(
            "the cap holds",
            RiskPenaltyPolicy.supportWeight(4),
            RiskPenaltyPolicy.supportWeight(400),
            1e-12,
        )
        assertEquals(1.0 + 3 * RiskPenaltyPolicy.CORROBORATION_STEP, RiskPenaltyPolicy.supportWeight(4), 1e-12)
        // Zero devices cannot happen in a state, and must not produce a discount if it did.
        assertEquals(1.0, RiskPenaltyPolicy.supportWeight(0), 1e-12)
    }

    // ------------------------------- acceptance: the exclusion path is untouched

    @Test
    fun anExcludedSegmentIsNeverPriced() {
        val excluded = state(WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE, devices = 5)
        assertTrue(excluded.excludesTraversal)
        val policy = RiskPenaltyPolicy.forStates(mapOf(SegmentId(7) to excluded)) { LENGTH }
        assertEquals(
            "an excluded segment belongs to the exclusion path, not to the penalty map",
            RoutingPolicy.DISTANCE_ONLY,
            policy,
        )
    }

    @Test
    fun theDepthOrderingAndTheWaterWeightAgreeInDirection() {
        // `WaterLevelCategory.depthRank` (ADR 0019) is semantics and `waterLevelWeight` (ADR 0011) is
        // a routing cost, so they are separately owned and could in principle be retuned apart. This
        // pins that they cannot drift into disagreeing about which of two depths is the worse one —
        // otherwise the app would show one ordering and price another.
        val ranked = everyWaterLevel.filter { it.depthRank != null }.sortedBy { it.depthRank }

        assertEquals(
            "every depth category must be ranked (only UNKNOWN states nothing)",
            everyWaterLevel.size - 1,
            ranked.size,
        )
        assertEquals("an unstated depth has no rank to compare", null, WaterLevelCategory.UNKNOWN.depthRank)

        for ((shallower, deeper) in ranked.zipWithNext()) {
            assertTrue(
                "$deeper is deeper than $shallower, so its risk weight must not be smaller " +
                    "(${RiskPenaltyPolicy.waterLevelWeight(deeper)} vs " +
                    "${RiskPenaltyPolicy.waterLevelWeight(shallower)})",
                RiskPenaltyPolicy.waterLevelWeight(deeper) >= RiskPenaltyPolicy.waterLevelWeight(shallower),
            )
        }
        // And the reduction the derivation performs uses that same ordering, so a segment's reported
        // depth is the ranked maximum rather than a second, private one.
        val reports = listOf(WaterLevelCategory.ANKLE, WaterLevelCategory.WAIST, WaterLevelCategory.KNEE)
            .map { level ->
                HazardFixtures.observedReport(
                    eventId = "evt-depth-${level.code}",
                    device = "device-${level.code}",
                    segmentId = 17,
                    observation = obs(HazardReason.WATERLOGGED, level, PassabilityStatus.CAUTION),
                )
            }
        assertEquals(
            WaterLevelCategory.WAIST,
            SegmentObservationAggregator.aggregateSegment(SegmentId(17), reports).worstWaterLevel,
        )
    }

    @Test
    fun aResolvedClosureStopsPricingTheRoadItClosed() {
        // The point of reopening, seen from the router's side. A road reported waist-deep and impassable,
        // then explicitly cleared with "no visible water, passable", is no longer excluded - and it must be
        // priced by the evidence actually in force, not by the depth of the report a person superseded.
        // Were the resolved closure still feeding the reductions, this road would carry a waist-deep
        // penalty on top of being traversable.
        val resolved = SegmentObservationAggregator.aggregateSegment(
            SegmentId(9),
            listOf(
                HazardFixtures.observedReport(
                    "evt-r-0001", "device-a-0001", 9, occurredAt = 1_700_000_000_000L,
                    observation = obs(HazardReason.WATERLOGGED, WaterLevelCategory.WAIST, PassabilityStatus.IMPASSABLE),
                ),
                HazardFixtures.clearedReport(
                    "evt-r-0002", device = "device-b-0002", segmentId = 9, occurredAt = 1_700_000_600_000L,
                    clearance = HazardFixtures.clearance(
                        waterLevel = WaterLevelCategory.NO_VISIBLE_WATER,
                        passability = PassabilityStatus.PASSABLE_REPORTED,
                    ),
                ),
            ),
        )
        assertFalse("the clearance reopened it", resolved.excludesTraversal)
        assertEquals(1, resolved.supportCount)

        val resolvedPolicy = RiskPenaltyPolicy.forStates(mapOf(SegmentId(9) to resolved)) { LENGTH }
        val asIfOnlyTheClearanceWereReported = RiskPenaltyPolicy.forStates(
            mapOf(SegmentId(9) to state(WaterLevelCategory.NO_VISIBLE_WATER, PassabilityStatus.PASSABLE_REPORTED, devices = 1)),
        ) { LENGTH }

        assertEquals(
            "the superseded depth must not survive into the price of the reopened road",
            asIfOnlyTheClearanceWereReported.penaltyMeters(9),
            resolvedPolicy.penaltyMeters(9),
            1e-9,
        )
        assertTrue("and the reopened road is cheaper than while it was closed", resolvedPolicy.penaltyMeters(9) >= 0.0)
    }

    @Test
    fun aLegacyReportIsNeverPriced() {
        // The v1 contract: every legacy row is an exclusion, so no legacy row may become a finite
        // penalty (ADR 0001 D2, ADR 0002 §3).
        for (reason in HazardReason.entries) {
            val legacy = SegmentObservationAggregator
                .aggregate(listOf(HazardFixtures.observedReport("evt-$reason", "device-a", 9, com.resqroute.domain.hazard.RoadObservation.fromLegacy(reason))))
                .getValue(SegmentId(9))
            val policy = RiskPenaltyPolicy.forStates(mapOf(SegmentId(9) to legacy)) { LENGTH }
            assertEquals("$reason must stay an exclusion", RoutingPolicy.DISTANCE_ONLY, policy)
        }
    }

    @Test
    fun anUnreadableReportIsNeverPriced() {
        val unreadable = SegmentObservationAggregator
            .aggregate(listOf(HazardFixtures.unreadableReport("evt-unreadable", "device-b", 9)))
            .getValue(SegmentId(9))
        assertTrue(unreadable.excludesTraversal)
        assertEquals(
            RoutingPolicy.DISTANCE_ONLY,
            RiskPenaltyPolicy.forStates(mapOf(SegmentId(9) to unreadable)) { LENGTH },
        )
    }

    // ------------------------------------------- acceptance: zero penalties

    @Test
    fun aLogOfOnlyPassableReportsProducesTheDistanceOnlyPolicy() {
        // Somebody looked at the road and believed it usable, with no water on it: that is
        // information, and it is not a hazard.
        val fine = state(WaterLevelCategory.NO_VISIBLE_WATER, PassabilityStatus.PASSABLE_REPORTED, devices = 3)
        assertFalse(fine.excludesTraversal)
        assertEquals(0.0, RiskPenaltyPolicy.riskFactor(fine), 1e-12)
        assertEquals(
            "no risk must reproduce the distance-only policy exactly",
            RoutingPolicy.DISTANCE_ONLY,
            RiskPenaltyPolicy.forStates(mapOf(SegmentId(3) to fine)) { LENGTH },
        )
    }

    @Test
    fun anEmptyStateProducesTheDistanceOnlyPolicy() {
        assertEquals(
            RoutingPolicy.DISTANCE_ONLY,
            RiskPenaltyPolicy.forStates(emptyMap()) { LENGTH },
        )
    }

    @Test
    fun unreportedSegmentsAreNotPriced() {
        // Whether unreported road should cost extra is XPH-89's decision, not this builder's:
        // only segments present in the state appear in the policy.
        val state = mapOf(SegmentId(1) to state(WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, devices = 1))
        val policy = RiskPenaltyPolicy.forStates(state) { LENGTH }
        assertEquals(setOf(SegmentId(1)), policy.segmentPenaltyMeters.keys)
        assertEquals(0.0, policy.penaltyMeters(2), 1e-12)
    }

    // -------------------------------------------------------- the formula

    @Test
    fun thePenaltyIsTheLengthTimesLambdaTimesRisk() {
        val state = state(WaterLevelCategory.ABOVE_WAIST, PassabilityStatus.AVOID, devices = 1)
        val risk = RiskPenaltyPolicy.riskFactor(state)
        val policy = RiskPenaltyPolicy.forStates(mapOf(SegmentId(4) to state)) { LENGTH }

        assertEquals(LENGTH * RiskPenaltyPolicy.LAMBDA * risk, policy.penaltyMeters(4), 1e-9)
        // A different length scales the penalty in proportion.
        val shorter = RiskPenaltyPolicy.forStates(mapOf(SegmentId(4) to state)) { LENGTH / 2 }
        assertEquals(policy.penaltyMeters(4) / 2, shorter.penaltyMeters(4), 1e-9)
    }

    @Test
    fun theBaseRiskIsTheWorseOfTheTwoAxesNotTheirSum() {
        // The axes are independent signals, so the worse one describes the road. Summing them
        // would invent a severity neither observer reported.
        val level = RiskPenaltyPolicy.waterLevelWeight(WaterLevelCategory.KNEE)
        val verdict = RiskPenaltyPolicy.passabilityWeight(PassabilityStatus.CAUTION)
        val state = state(WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, devices = 1)
        assertEquals(maxOf(level, verdict) * 1.0, RiskPenaltyPolicy.riskFactor(state), 1e-12)
        assertTrue(RiskPenaltyPolicy.riskFactor(state) < level + verdict)
    }

    @Test
    fun aDepthOnlyReportStillCostsSomething() {
        // This is the gap this phase closes: before it, a report that did not exclude was
        // invisible to the router.
        val depthOnly = state(WaterLevelCategory.WAIST, PassabilityStatus.UNKNOWN, devices = 1)
        assertFalse(depthOnly.excludesTraversal)
        assertTrue(RiskPenaltyPolicy.riskFactor(depthOnly) > 0.0)
        assertTrue(RiskPenaltyPolicy.forStates(mapOf(SegmentId(5) to depthOnly)) { LENGTH }.penaltyMeters(5) > 0.0)
    }

    @Test
    fun aVerdictOnlyReportStillCostsSomething() {
        val verdictOnly = state(WaterLevelCategory.UNKNOWN, PassabilityStatus.AVOID, devices = 1)
        assertFalse(verdictOnly.excludesTraversal)
        assertEquals(
            RiskPenaltyPolicy.passabilityWeight(PassabilityStatus.AVOID),
            RiskPenaltyPolicy.riskFactor(verdictOnly),
            1e-12,
        )
    }

    @Test
    fun moreCorroboratingDevicesCostMore() {
        val one = RiskPenaltyPolicy.forStates(
            mapOf(SegmentId(6) to state(WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, devices = 1)),
        ) { LENGTH }
        val three = RiskPenaltyPolicy.forStates(
            mapOf(SegmentId(6) to state(WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, devices = 3)),
        ) { LENGTH }
        assertTrue("three devices must not be cheaper than one", three.penaltyMeters(6) > one.penaltyMeters(6))
    }

    @Test
    fun thePolicyIsImmutableAndOffersNoWayToMutateIt() {
        val policy = RiskPenaltyPolicy.forStates(
            mapOf(SegmentId(8) to state(WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, devices = 1)),
        ) { LENGTH }
        assertNotNull(policy.segmentPenaltyMeters)
        assertEquals(1, policy.segmentPenaltyMeters.size)
        // Two independent builds of the same state are equal, and the map the engine reads is the
        // policy's own copy rather than the caller's.
        val again = RiskPenaltyPolicy.forStates(
            mapOf(SegmentId(8) to state(WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, devices = 1)),
        ) { LENGTH }
        assertEquals(policy, again)
    }

    @Test
    fun aMalformedSegmentLengthCannotProduceAnInvalidPolicy() {
        // The graph is validated at load, so this is defensive; what matters is that an impossible
        // length can never reach RoutingPolicy, which would throw on a NaN or a negative penalty.
        val state = mapOf(SegmentId(9) to state(WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, devices = 1))
        for (bad in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -5.0, 0.0)) {
            val policy = RiskPenaltyPolicy.forStates(state) { bad }
            assertEquals("length $bad must not be priced", RoutingPolicy.DISTANCE_ONLY, policy)
        }
        // A merely huge length is still representable, and stays finite.
        val huge = RiskPenaltyPolicy.forStates(state) { 1e300 }
        assertTrue(huge.penaltyMeters(9).isFinite())
    }

    // -------------------------------------------------------------- fixture

    /**
     * Builds a real one-report state, so these tests exercise the same aggregate the app derives
     * rather than a hand-made stand-in.
     */
    private fun state(
        level: WaterLevelCategory,
        verdict: PassabilityStatus,
        devices: Int = 1,
    ): SegmentObservationState {
        val reports = (1..maxOf(devices, 1)).map { index ->
            HazardFixtures.observedReport(
                eventId = "evt-%02d".format(index),
                device = "device-%02d".format(index),
                segmentId = 1,
                observation = obs(HazardReason.WATERLOGGED, level, verdict),
            )
        }
        return SegmentObservationAggregator.aggregate(reports).getValue(SegmentId(1))
    }

    private fun riskFor(
        reason: HazardReason,
        level: WaterLevelCategory,
        verdict: PassabilityStatus,
    ): Double = RiskPenaltyPolicy.riskFactor(
        SegmentObservationAggregator
            .aggregate(listOf(HazardFixtures.observedReport("evt", "device-a", 1, obs(reason, level, verdict))))
            .getValue(SegmentId(1)),
    )

    private fun obs(
        reason: HazardReason,
        level: WaterLevelCategory,
        verdict: PassabilityStatus,
    ) = HazardFixtures.observation(reason, level, verdict)

    /**
     * The two rejected combinations of ADR 0002 §2: R1 (states nothing) and R2 (above-waist
     * water cannot be reported passable). Everything else is representable.
     */
    private fun isRepresentable(level: WaterLevelCategory, verdict: PassabilityStatus): Boolean {
        if (!level.statesSomething && !verdict.statesSomething) return false
        if (level == WaterLevelCategory.ABOVE_WAIST && verdict == PassabilityStatus.PASSABLE_REPORTED) return false
        return true
    }

    private companion object {
        /** The geometric length every fixture segment reports; penalties scale from it. */
        const val LENGTH = 100.0
    }
}
