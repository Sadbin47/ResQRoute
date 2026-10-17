package com.resqroute.hazard

import com.resqroute.core.model.SegmentId
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.ObservationSource
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.SegmentObservationAggregator
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Support and disagreement (XPH-73), as a pure function.
 *
 * Support is a count of **distinct device IDs**, never a probability: two reports from one
 * phone are one device (ADR 0001 D5). Disagreement is a statement about the *reports*, not
 * about the road — the app shows both sides with their own sources and averages nothing.
 */
class SupportAndDisagreementTest {

    // ---------------------------------------------------------------- support

    @Test
    fun supportCountsDistinctDevicesRegardlessOfSource() {
        val state = stateOf(
            report("evt-1", "device-self", source = ObservationSource.DIRECT_OBSERVATION),
            report("evt-2", "device-b", source = ObservationSource.RELAYED_ACCOUNT),
            report("evt-3", "device-b", source = ObservationSource.RELAYED_ACCOUNT),
            report("evt-4", "device-c", source = ObservationSource.RELAYED_ACCOUNT),
        )

        assertEquals(4, state.reports.size)
        assertEquals("four reports from three devices is three", 3, state.supportCount)
        assertEquals(
            listOf("device-b", "device-c", "device-self"),
            state.reportingDeviceIds.map { it.value },
        )
    }

    @Test
    fun sourceAttributionPartitionsTheDevicesWithoutChangingTheCount() {
        val state = stateOf(
            report("evt-1", "device-self", source = ObservationSource.DIRECT_OBSERVATION),
            report("evt-2", "device-b", source = ObservationSource.RELAYED_ACCOUNT),
            report("evt-3", "device-c", source = ObservationSource.RELAYED_ACCOUNT),
        )

        assertEquals(setOf("device-self"), state.reportingDeviceIdsBySource.getValue(ObservationSource.DIRECT_OBSERVATION).map { it.value }.toSet())
        assertEquals(setOf("device-b", "device-c"), state.reportingDeviceIdsBySource.getValue(ObservationSource.RELAYED_ACCOUNT).map { it.value }.toSet())
        assertTrue("no official source exists yet", state.reportingDeviceIdsBySource.getValue(ObservationSource.OFFICIAL_STATEMENT).isEmpty())
        assertEquals(1, state.directSupportCount)
        assertEquals(2, state.relayedSupportCount)
        // The headline number is unchanged by attribution: it is still distinct devices.
        assertEquals(3, state.supportCount)
    }

    @Test
    fun everySourceKeyIsPresentSoACallerNeedNotNullCheck() {
        val state = stateOf(report("evt-1", "device-a"))
        assertEquals(
            ObservationSource.entries.toSet(),
            state.reportingDeviceIdsBySource.keys,
        )
    }

    @Test
    fun reportsCanBeReadBackBySource() {
        val state = stateOf(
            report("evt-1", "device-a", source = ObservationSource.DIRECT_OBSERVATION),
            report("evt-2", "device-b", source = ObservationSource.RELAYED_ACCOUNT),
            report("evt-3", "device-b", source = ObservationSource.RELAYED_ACCOUNT),
        )
        assertEquals(listOf("evt-1"), state.reportsFrom(ObservationSource.DIRECT_OBSERVATION).map { it.eventId.value })
        assertEquals(listOf("evt-2", "evt-3"), state.reportsFrom(ObservationSource.RELAYED_ACCOUNT).map { it.eventId.value })
        assertTrue(state.reportsFrom(ObservationSource.OFFICIAL_STATEMENT).isEmpty())
    }

    @Test
    fun aDeviceCannotOutrankAnotherByRelayingItsOwnReportRepeatedly() {
        // Same device, four events, all relayed: one device, not four witnesses.
        val state = stateOf(
            report("evt-1", "device-b", source = ObservationSource.RELAYED_ACCOUNT),
            report("evt-2", "device-b", source = ObservationSource.RELAYED_ACCOUNT),
            report("evt-3", "device-b", source = ObservationSource.RELAYED_ACCOUNT),
            report("evt-4", "device-b", source = ObservationSource.RELAYED_ACCOUNT),
        )
        assertEquals(1, state.supportCount)
        assertEquals(1, state.relayedSupportCount)
    }

    // ---------------------------------------------------------- disagreement

    @Test
    fun waistThenAnkleIsShownAsADifferenceNotAveragedIntoOneValue() {
        // The issue's own example. Two observers, two depths, two times, two sources.
        val state = stateOf(
            report(
                "evt-waist", "device-b",
                observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.WAIST, PassabilityStatus.AVOID),
                source = ObservationSource.RELAYED_ACCOUNT, occurredAt = 1_000,
            ),
            report(
                "evt-ankle", "device-self",
                observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.ANKLE, PassabilityStatus.CAUTION),
                source = ObservationSource.DIRECT_OBSERVATION, occurredAt = 9_000,
            ),
        )

        assertTrue(state.hasDisagreement)
        assertEquals(
            "both stated depths survive, shallowest first",
            listOf(WaterLevelCategory.ANKLE, WaterLevelCategory.WAIST),
            state.statedWaterLevels,
        )
        assertEquals(
            listOf(PassabilityStatus.CAUTION, PassabilityStatus.AVOID),
            state.statedPassabilities,
        )
        // Each view keeps its own time, device and source: nothing is merged.
        val waist = state.reportsWithWaterLevel(WaterLevelCategory.WAIST).single()
        assertEquals("evt-waist", waist.eventId.value)
        assertEquals("device-b", waist.deviceId.value)
        assertEquals(ObservationSource.RELAYED_ACCOUNT, waist.source)
        assertEquals(1_000L, waist.occurredAtEpochMillis)
        val ankle = state.reportsWithWaterLevel(WaterLevelCategory.ANKLE).single()
        assertEquals(9_000L, ankle.occurredAtEpochMillis)
        assertEquals(ObservationSource.DIRECT_OBSERVATION, ankle.source)
        // Neither one is "the" answer: the deepest is reported as the worst, per ADR 0006.
        assertEquals(WaterLevelCategory.WAIST, state.worstWaterLevel)
        assertFalse("neither report closes the road", state.hasTraversalDisagreement)
    }

    @Test
    fun aClosureAgainstAPassableReportIsATraversalDisagreementAndTheClosureStillWins() {
        val state = stateOf(
            report("evt-closed", "device-b", observation = HazardFixtures.observation(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE)),
            report("evt-open", "device-c", observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.ANKLE, PassabilityStatus.PASSABLE_REPORTED)),
        )

        assertTrue(state.hasTraversalDisagreement)
        assertTrue("the closure still decides routing", state.excludesTraversal)
        assertEquals(PassabilityStatus.IMPASSABLE, state.passability)
        assertEquals(2, state.supportCount)
        // The dissenting report is fully preserved, with its own identity.
        val open = state.reportsWithPassability(PassabilityStatus.PASSABLE_REPORTED).single()
        assertEquals("evt-open", open.eventId.value)
        assertEquals("device-c", open.deviceId.value)
    }

    @Test
    fun aDepthOnlyReportIsNotACompetingClaimAboutUsability() {
        // Depth-only (passability UNKNOWN) versus a closure: different information, not a
        // contradiction, because the depth-only report never claimed the road was usable.
        val state = stateOf(
            report("evt-closed", "device-b", observation = HazardFixtures.observation(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE)),
            report("evt-depth", "device-c", observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.UNKNOWN)),
        )

        assertFalse(state.hasTraversalDisagreement)
        assertEquals(listOf(WaterLevelCategory.KNEE), state.statedWaterLevels)
        assertEquals(listOf(PassabilityStatus.IMPASSABLE), state.statedPassabilities)
        assertFalse("one depth and one verdict do not contradict", state.hasDisagreement)
    }

    @Test
    fun anUnreadableReportDisagreesWithAPassableOneBecauseItFailsClosed() {
        val state = stateOf(
            HazardFixtures.unreadableReport("evt-unreadable", "device-b", 10),
            report("evt-open", "device-c", observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.ANKLE, PassabilityStatus.CAUTION)),
        )

        assertTrue("an unreadable report closes the road, so it disagrees with a caution", state.hasTraversalDisagreement)
        assertTrue(state.excludesTraversal)
        assertEquals(PassabilityStatus.IMPASSABLE, state.passability)
        // It states no axis, so it cannot create a depth or verdict spread on its own.
        assertEquals(listOf(WaterLevelCategory.ANKLE), state.statedWaterLevels)
        assertEquals(listOf(PassabilityStatus.CAUTION), state.statedPassabilities)
    }

    @Test
    fun agreeingReportsAreNotReportedAsDisagreeing() {
        val state = stateOf(
            report("evt-1", "device-b", observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION)),
            report("evt-2", "device-c", observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION)),
        )

        assertFalse(state.hasDisagreement)
        assertFalse(state.hasTraversalDisagreement)
        assertEquals(listOf(WaterLevelCategory.KNEE), state.statedWaterLevels)
        assertEquals(2, state.supportCount)
    }

    @Test
    fun aSingleReportNeverDisagreesWithItself() {
        val state = stateOf(report("evt-1", "device-a"))
        assertFalse(state.hasDisagreement)
        assertFalse(state.hasTraversalDisagreement)
    }

    @Test
    fun severalClosuresFromSeveralDevicesAreNotADisagreement() {
        // Two devices both reporting impassable agree about the important thing.
        val state = stateOf(
            report("evt-1", "device-b", observation = HazardFixtures.observation(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE)),
            report("evt-2", "device-c", observation = HazardFixtures.observation(HazardReason.BLOCKED, WaterLevelCategory.NO_VISIBLE_WATER, PassabilityStatus.IMPASSABLE)),
        )
        assertEquals(2, state.supportCount)
        assertFalse(state.hasTraversalDisagreement)
        // The first report stated no depth at all, and an unstating report is not a competing
        // value on that axis - so there is no spread to report.
        assertFalse(state.hasDisagreement)

        // When both really do state a depth, the spread is reported - still without touching
        // the traversal decision.
        val differingDepths = stateOf(
            report("evt-1", "device-b", observation = HazardFixtures.observation(HazardReason.BLOCKED, WaterLevelCategory.KNEE, PassabilityStatus.IMPASSABLE)),
            report("evt-2", "device-c", observation = HazardFixtures.observation(HazardReason.BLOCKED, WaterLevelCategory.NO_VISIBLE_WATER, PassabilityStatus.IMPASSABLE)),
        )
        assertTrue(differingDepths.hasDisagreement)
        assertFalse(differingDepths.hasTraversalDisagreement)
        assertTrue(differingDepths.excludesTraversal)
    }

    @Test
    fun disagreementDoesNotChangeWhatTheRouterMayDo() {
        // Whichever way the reports disagree, the traversal decision is the same monotone
        // rule: any closure excludes. Nothing here smooths it.
        val agreeing = stateOf(
            report("evt-1", "device-b", observation = HazardFixtures.observation(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE)),
            report("evt-2", "device-c", observation = HazardFixtures.observation(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE)),
        )
        val disagreeing = stateOf(
            report("evt-1", "device-b", observation = HazardFixtures.observation(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE)),
            report("evt-2", "device-c", observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.ANKLE, PassabilityStatus.PASSABLE_REPORTED)),
        )
        assertTrue(agreeing.excludesTraversal)
        assertTrue(disagreeing.excludesTraversal)
    }

    // -------------------------------------------------------------- fixture

    private fun stateOf(vararg reports: com.resqroute.domain.hazard.ObservedReport): SegmentObservationState =
        SegmentObservationAggregator.aggregate(reports.toList()).getValue(SegmentId(10))

    /** A closure by default; callers vary whichever axis their case is about. */
    private fun report(
        eventId: String,
        device: String,
        observation: com.resqroute.domain.hazard.RoadObservation = HazardFixtures.observation(
            HazardReason.BLOCKED,
            WaterLevelCategory.UNKNOWN,
            PassabilityStatus.IMPASSABLE,
        ),
        source: ObservationSource = ObservationSource.DIRECT_OBSERVATION,
        occurredAt: Long = HazardFixtures.FIXED_CLOCK,
    ) = HazardFixtures.observedReport(
        eventId = eventId,
        device = device,
        segmentId = 10,
        observation = observation,
        source = source,
        occurredAt = occurredAt,
    )
}
