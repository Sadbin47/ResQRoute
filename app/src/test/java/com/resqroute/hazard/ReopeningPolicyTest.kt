package com.resqroute.hazard

import com.resqroute.core.model.SegmentId
import com.resqroute.domain.hazard.Clearance
import com.resqroute.domain.hazard.ClearanceValidation
import com.resqroute.domain.hazard.ClosureFreshness
import com.resqroute.domain.hazard.FreshnessPolicy
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.ObservedReport
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.ReopeningPolicy
import com.resqroute.domain.hazard.SegmentObservationAggregator
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reopening rule (XPH-79), against ADR 0005 §5 and §8.
 *
 * Three things are proven here, and they are different things:
 *
 *  1. **The comparison** ([ReopeningPolicy]) — when a clearance resolves a closure, and the direction of
 *     every failure (always "still closed").
 *  2. **The validation** ([Clearance]) — what may be a clearance at all, since it is the one event that
 *     lifts a restriction.
 *  3. **The consequence in the derivation** — that only a clearance reopens, only the closures older than
 *     it, that a resolved closure leaves the evidence but stays in history, and that no ordinary report
 *     reopens anything however fresh and however many devices sent it.
 *
 * The adversarial half matters more than the happy half. A rule that reopens too readily fails in the one
 * direction this project forbids everywhere: allowing a route over a road nobody has cleared.
 */
class ReopeningPolicyTest {

    /** A credible claimed time: inside the window, and the anchor for the cases below. */
    private val t0 = 1_700_000_000_000L

    private val hour = 3_600_000L

    // --------------------------------------------------------------- the comparison

    @Test
    fun aStrictlyNewerCredibleClearanceResolvesTheClosure() {
        assertTrue(ReopeningPolicy.resolves(clearanceClaimedAt = t0 + 60_000, closureClaimedAt = t0))
    }

    @Test
    fun anEqualClaimDoesNotResolveBecauseATieIsNotKnowledge() {
        // ADR 0005 §8 requirement 4: on equal timestamps the closure wins. A tie means "we cannot tell
        // which situation this belongs to", and "we cannot tell" is never "open".
        assertFalse(ReopeningPolicy.resolves(clearanceClaimedAt = t0, closureClaimedAt = t0))
    }

    @Test
    fun anOlderClearanceDoesNotResolveANewerClosure() {
        assertFalse(ReopeningPolicy.resolves(clearanceClaimedAt = t0 - 1, closureClaimedAt = t0))
        assertFalse(ReopeningPolicy.resolves(clearanceClaimedAt = t0 - 24 * hour, closureClaimedAt = t0))
    }

    @Test
    fun anImplausibleClaimOnEitherSideFailsTowardClosed() {
        // ADR 0005 §8 requirement 5: on undecidable time the closure wins. Zero, negative, absurdly old
        // and absurdly future all count as undecidable, in either position.
        val undecidable = listOf(
            "zero" to 0L,
            "negative" to -1L,
            "absurdly old" to FreshnessPolicy.EARLIEST_PLAUSIBLE_OBSERVATION_MILLIS - 1,
            "absurdly future" to ReopeningPolicy.LATEST_CREDIBLE_CLAIM_MILLIS + 1,
            "beyond any clock" to Long.MAX_VALUE,
        )
        for ((label, value) in undecidable) {
            assertFalse(
                "an undecidable clearance claim ($label) must not resolve a credible closure",
                ReopeningPolicy.resolves(clearanceClaimedAt = value, closureClaimedAt = t0),
            )
            assertFalse(
                "an undecidable closure claim ($label) must not be resolved by a credible clearance",
                ReopeningPolicy.resolves(clearanceClaimedAt = t0, closureClaimedAt = value),
            )
        }
    }

    @Test
    fun theCredibilityWindowIsClosedAtBothEnds() {
        val floor = ReopeningPolicy.EARLIEST_CREDIBLE_CLAIM_MILLIS
        val ceiling = ReopeningPolicy.LATEST_CREDIBLE_CLAIM_MILLIS
        assertTrue(ReopeningPolicy.isCredibleClaim(floor))
        assertTrue(ReopeningPolicy.isCredibleClaim(ceiling))
        assertFalse(ReopeningPolicy.isCredibleClaim(floor - 1))
        assertFalse(ReopeningPolicy.isCredibleClaim(ceiling + 1))
    }

    @Test
    fun theCredibilityFloorAgreesWithTheDisplayFloor() {
        // Two constants, two jobs: this one decides an excluded set, the other labels an age. They are
        // equal by intent rather than by reference (so the derivation does not read a display constant),
        // and this test is what stops them drifting apart silently.
        assertEquals(
            FreshnessPolicy.EARLIEST_PLAUSIBLE_OBSERVATION_MILLIS,
            ReopeningPolicy.EARLIEST_CREDIBLE_CLAIM_MILLIS,
        )
    }

    // -------------------------------------------------------------- the validation

    @Test
    fun onlyVerdictsThatAssertUsabilityCanBeClearances() {
        for (verdict in listOf(
            PassabilityStatus.AVOID,
            PassabilityStatus.IMPASSABLE,
            PassabilityStatus.UNKNOWN,
        )) {
            val outcome = Clearance.create(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, verdict)
            assertTrue("$verdict must not be a clearance, was $outcome", outcome is ClearanceValidation.Invalid)
            assertEquals(
                Clearance.PROBLEM_PASSABILITY_DOES_NOT_ASSERT_USABILITY,
                (outcome as ClearanceValidation.Invalid).problem,
            )
        }
        for (verdict in listOf(PassabilityStatus.PASSABLE_REPORTED, PassabilityStatus.CAUTION)) {
            assertTrue(
                "$verdict is a clearance",
                Clearance.create(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, verdict)
                    is ClearanceValidation.Valid,
            )
        }
    }

    @Test
    fun anUnsafeDepthCannotBeSmuggledIntoAClearanceUnderAMilderVerdict() {
        // Above-waist is defined as unsafe, so it cannot be part of a claim that the road is usable. R2
        // already blocks above-waist + passable; CAUTION would otherwise be a loophole.
        val outcome = Clearance.create(
            HazardReason.WATERLOGGED,
            WaterLevelCategory.ABOVE_WAIST,
            PassabilityStatus.CAUTION,
        )
        assertTrue("was $outcome", outcome is ClearanceValidation.Invalid)
        assertEquals(Clearance.PROBLEM_WATER_LEVEL_IS_UNSAFE, (outcome as ClearanceValidation.Invalid).problem)
    }

    @Test
    fun aClearanceThatStatesNoDepthIsLegitimate() {
        // "The road is usable; I cannot say how deep" is a clearance, and it states no depth rather than
        // having one invented for it.
        val outcome = Clearance.create(
            HazardReason.WATERLOGGED,
            WaterLevelCategory.UNKNOWN,
            PassabilityStatus.CAUTION,
        )
        val clearance = (outcome as ClearanceValidation.Valid).clearance
        assertEquals(WaterLevelCategory.UNKNOWN, clearance.waterLevel)
        assertFalse("an unstating axis stays unstating", clearance.waterLevel.statesSomething)
    }

    @Test
    fun aValidatedClearanceIsAnOrdinaryObservationToo() {
        // What makes a clearance take part in the severity reductions instead of being a parallel kind of
        // evidence: it is an ordinarily valid observation (ADR 0005 §8 requirement 7).
        val clearance = HazardFixtures.clearance(
            waterLevel = WaterLevelCategory.ANKLE,
            passability = PassabilityStatus.CAUTION,
        )
        assertEquals(
            HazardFixtures.observation(HazardReason.BLOCKED, WaterLevelCategory.ANKLE, PassabilityStatus.CAUTION),
            clearance.observation,
        )
        assertFalse("a clearance is never an exclusion", clearance.observation.excludesTraversal)
    }

    // ------------------------------------------------------- the derivation, reopened

    @Test
    fun onlyAClearanceReopensAndOnlyTheClosuresOlderThanIt() {
        val state = stateOf(
            101,
            // The newer closure is *after* the clearance, so it is not superseded by it.
            close("evt-c-0001", 101, at = t0 + 90_000),
            close("evt-c-0002", 101, at = t0 - hour),
            HazardFixtures.clearedReport("evt-c-0003", device = "device-b-0002", segmentId = 101, occurredAt = t0 + 60_000),
        )

        assertEquals(1, state.clearances.size)
        assertEquals(
            "only the closure older than the clearance is resolved",
            listOf("evt-c-0002"),
            state.resolvedClosures.map { it.eventId.value },
        )
        assertTrue("the newer closure still applies, so the road stays closed", state.excludesTraversal)
        assertEquals(PassabilityStatus.IMPASSABLE, state.passability)
        assertEquals("and the resolved closure is still in the log", 3, state.reports.size)
    }

    @Test
    fun aClearanceNewerThanEveryClosureReopensTheRoad() {
        val state = stateOf(
            201,
            close("evt-d-0001", 201, at = t0),
            close("evt-d-0002", 201, at = t0 + 1000, device = "device-b-0002"),
            HazardFixtures.clearedReport("evt-d-0003", segmentId = 201, occurredAt = t0 + 60_000),
        )
        assertFalse(state.excludesTraversal)
        assertEquals(2, state.resolvedClosures.size)
        assertEquals(
            "and the invariant the state documents still holds",
            state.excludesTraversal,
            state.passability == PassabilityStatus.IMPASSABLE,
        )
    }

    @Test
    fun noOrdinaryNewerReportReopensARoadHoweverFreshAndHoweverManyDevices() {
        // The heart of "never let a single lower reading reopen a road" — and of "never let a timestamp
        // reopen a road". Every one of these is newer than the closure, and none of them is a clearance.
        val fresh = t0 + hour
        val attempts = listOf(
            "one passable report" to listOf(
                HazardFixtures.observedReport(
                    "evt-e-0001", "device-b-0002", 301, occurredAt = fresh,
                    observation = HazardFixtures.observation(
                        HazardReason.WATERLOGGED, WaterLevelCategory.NO_VISIBLE_WATER, PassabilityStatus.PASSABLE_REPORTED,
                    ),
                ),
            ),
            "one caution report" to listOf(
                HazardFixtures.observedReport(
                    "evt-e-0002", "device-b-0002", 301, occurredAt = fresh,
                    observation = HazardFixtures.observation(
                        HazardReason.WATERLOGGED, WaterLevelCategory.ANKLE, PassabilityStatus.CAUTION,
                    ),
                ),
            ),
            "a shallower depth on its own" to listOf(
                HazardFixtures.observedReport(
                    "evt-e-0003", "device-b-0002", 301, occurredAt = fresh,
                    observation = HazardFixtures.observation(
                        HazardReason.WATERLOGGED, WaterLevelCategory.NO_VISIBLE_WATER, PassabilityStatus.UNKNOWN,
                    ),
                ),
            ),
            "six devices all saying passable" to (1..6).map {
                HazardFixtures.observedReport(
                    "evt-e-10$it", "device-b-000$it", 301, occurredAt = fresh,
                    observation = HazardFixtures.observation(
                        HazardReason.WATERLOGGED, WaterLevelCategory.NO_VISIBLE_WATER, PassabilityStatus.PASSABLE_REPORTED,
                    ),
                )
            },
        )

        for ((label, reports) in attempts) {
            val state = stateOf(301, close("evt-e-0000", 301, at = t0), *reports.toTypedArray())
            assertTrue("$label must not reopen the road", state.excludesTraversal)
            assertEquals(label, PassabilityStatus.IMPASSABLE, state.passability)
            assertEquals("$label resolved nothing", 0, state.resolvedClosures.size)
        }
    }

    @Test
    fun aResolvedClosureLeavesHistoryButStopsBeingEvidence() {
        val state = stateOf(
            401,
            HazardFixtures.observedReport(
                "evt-f-0001", "device-b-0002", 401, occurredAt = t0,
                observation = HazardFixtures.observation(
                    HazardReason.WATERLOGGED, WaterLevelCategory.WAIST, PassabilityStatus.IMPASSABLE,
                ),
            ),
            HazardFixtures.clearedReport(
                "evt-f-0002", device = "device-c-0003", segmentId = 401, occurredAt = t0 + 60_000,
                clearance = HazardFixtures.clearance(
                    waterLevel = WaterLevelCategory.NO_VISIBLE_WATER,
                    passability = PassabilityStatus.PASSABLE_REPORTED,
                ),
            ),
        )

        // History keeps the closure, and can say which act superseded it.
        assertEquals(2, state.reports.size)
        assertEquals("evt-f-0001", state.resolvedClosures.single().eventId.value)
        assertEquals(
            "the clearance that resolved it is named",
            "evt-f-0002",
            state.clearingClearance(state.resolvedClosures.single())!!.eventId.value,
        )
        assertNull(
            "a report that is not resolved has no clearing clearance",
            state.clearingClearance(state.reports.first { it.eventId.value == "evt-f-0002" }),
        )

        // The reductions read the evidence in force: the superseded waist-deep, impassable statement no
        // longer closes the road, colours the verdict or deepens the level.
        assertFalse(state.excludesTraversal)
        assertEquals(PassabilityStatus.PASSABLE_REPORTED, state.passability)
        assertEquals(WaterLevelCategory.NO_VISIBLE_WATER, state.worstWaterLevel)
        assertEquals(listOf(WaterLevelCategory.NO_VISIBLE_WATER), state.statedWaterLevels)

        // Which is what makes the closure's freshness the freshness of a closure that still applies.
        assertEquals(
            ClosureFreshness.NO_CLOSURE,
            FreshnessPolicy.closureFreshness(state, now = t0 + 120_000).label,
        )
    }

    @Test
    fun theSupersededClosureAlsoStopsCountingTowardsSupport() {
        // Support is evidence in force, not a headcount of the log: a device whose report was explicitly
        // cleared is no longer amplifying anything, and its risk weight must not survive the clearing.
        val state = stateOf(
            501,
            close("evt-g-0001", 501, at = t0),
            close("evt-g-0002", 501, at = t0 + 1000, device = "device-b-0002"),
            HazardFixtures.clearedReport("evt-g-0003", device = "device-c-0003", segmentId = 501, occurredAt = t0 + 60_000),
        )
        assertEquals(1, state.supportCount)
        assertEquals(setOf("device-c-0003"), state.reportingDeviceIds.map { it.value }.toSet())
        assertEquals("the whole log is still there", 3, state.reports.size)
    }

    @Test
    fun anUnreadableRowKeepsItsSegmentClosedEvenWhenAClearanceArrives() {
        // ADR 0004 §2: a row this build cannot read excludes by failing closed. That is a conservative
        // default rather than a statement about the road, and the app cannot claim a clearance has
        // superseded evidence whose meaning it never understood.
        val state = stateOf(
            601,
            HazardFixtures.unreadableReport("evt-h-0001", device = "device-b-0002", segmentId = 601, occurredAt = t0),
            HazardFixtures.clearedReport("evt-h-0002", segmentId = 601, occurredAt = t0 + 60_000),
        )
        assertTrue("the unreadable row still closes the road", state.excludesTraversal)
        assertEquals(0, state.resolvedClosures.size)
        assertTrue(state.hasUnreadableReports)
    }

    @Test
    fun aClearanceWithNoClosuresChangesNothingAndIsAnOrdinaryPassableObservation() {
        // ADR 0005 §8 requirement 7: a clearance never survives as the only evidence — it is a no-op on
        // the excluded set and records an ordinarily readable, passable observation.
        val state = stateOf(
            701,
            HazardFixtures.clearedReport(
                "evt-i-0001", segmentId = 701, occurredAt = t0,
                clearance = HazardFixtures.clearance(
                    waterLevel = WaterLevelCategory.ANKLE,
                    passability = PassabilityStatus.CAUTION,
                ),
            ),
        )
        assertFalse(state.excludesTraversal)
        assertEquals("nothing was resolved, because there was nothing to resolve", 0, state.resolvedClosures.size)
        assertEquals(PassabilityStatus.CAUTION, state.passability)
        assertEquals(WaterLevelCategory.ANKLE, state.worstWaterLevel)
        assertEquals(1, state.supportCount)
    }

    @Test
    fun aClearanceResolvesOnlyItsOwnSegment() {
        // ADR 0005 §8 requirement 2. The dataset and incident halves of that requirement are structural:
        // the repository derives from rows filtered by `(datasetHash, incidentId)`
        // (RoomHazardRepository.initialize/derive) and builds one state per segment, so a clearance in
        // another incident cannot reach this one at all.
        val states = SegmentObservationAggregator.aggregate(
            listOf(
                close("evt-j-0002", 801, at = t0),
                close("evt-j-0003", 802, at = t0),
                HazardFixtures.clearedReport("evt-j-0001", segmentId = 801, occurredAt = t0 + 60_000),
            ),
        )
        assertFalse(
            "its own segment is resolved",
            states.getValue(SegmentId(801)).excludesTraversal,
        )
        assertTrue(
            "the neighbouring segment is untouched",
            states.getValue(SegmentId(802)).excludesTraversal,
        )
    }

    @Test
    fun theResolutionIsIndependentOfWhichDeviceSawWhichReportFirst() {
        // Convergence stated as the property it is: the resolution is a function of the set, so a device
        // that received the clearance first computes what one that received it last computes.
        val closure = close("evt-k-0001", 901, at = t0)
        val clearance = HazardFixtures.clearedReport("evt-k-0002", segmentId = 901, occurredAt = t0 + 60_000)
        val unrelated = close("evt-k-0003", 902, at = t0 + 10_000)

        val clearanceFirst = stateOf(901, clearance, unrelated, closure)
        val clearanceLast = stateOf(901, unrelated, closure, clearance)

        assertEquals(clearanceFirst, clearanceLast)
        assertNotEquals(
            "and the fixture would notice if the clearance stopped resolving anything",
            stateOf(901, unrelated, closure).resolvedClosures.size,
            clearanceFirst.resolvedClosures.size,
        )
    }

    // -------------------------------------------------------------- tiny helpers

    /** An impassable observation on [segment], the shape every closure in these tests takes. */
    private fun close(eventId: String, segment: Int, at: Long, device: String = "device-fixed-0001") =
        HazardFixtures.observedReport(
            eventId, device, segment, occurredAt = at,
            observation = HazardFixtures.observation(
                HazardReason.WATERLOGGED, WaterLevelCategory.WAIST, PassabilityStatus.IMPASSABLE,
            ),
        )

    /** The derived state of one segment, from a report set built for that segment. */
    private fun stateOf(segment: Int, vararg reports: ObservedReport): SegmentObservationState =
        SegmentObservationAggregator.aggregateSegment(SegmentId(segment), reports.toList())
}
