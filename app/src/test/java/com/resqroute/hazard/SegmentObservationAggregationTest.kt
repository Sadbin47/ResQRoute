package com.resqroute.hazard

import com.resqroute.core.model.SegmentId
import com.resqroute.domain.hazard.DeviceId
import com.resqroute.domain.hazard.EventId
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.ObservationValidation
import com.resqroute.domain.hazard.ObservationSource
import com.resqroute.domain.hazard.ObservedReport
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.ReportContent
import com.resqroute.domain.hazard.RoadObservation
import com.resqroute.domain.hazard.SegmentObservationAggregator
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-segment aggregation (XPH-69), as a pure function.
 *
 * No Room, no Android, no clock: every property here is a statement about the *event set*,
 * which is what the canonical plan's determinism exit criterion is about. The same
 * properties are exercised again through persistence in
 * [ObservationAggregationPersistenceTest]; these tests are here so that a failure points at
 * the rule rather than at Room.
 */
class SegmentObservationAggregationTest {

    // ------------------------------------------------------- acceptance: order

    @Test
    fun theDerivedStateIsIdenticalForEveryOrderingOfTheSameReports() {
        val reports = listOf(
            report("evt-0001", "device-a", 10, obs(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION), occurredAt = 5_000),
            report("evt-0002", "device-b", 10, obs(HazardReason.BLOCKED, WaterLevelCategory.NO_VISIBLE_WATER, PassabilityStatus.IMPASSABLE), occurredAt = 1_000),
            report("evt-0003", "device-c", 20, obs(HazardReason.WATERLOGGED, WaterLevelCategory.ABOVE_WAIST, PassabilityStatus.AVOID), occurredAt = 9_000),
            report("evt-0004", "device-a", 20, obs(HazardReason.WATERLOGGED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE), occurredAt = 500),
            report("evt-0005", "device-d", 30, obs(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.PASSABLE_REPORTED), occurredAt = 7_000),
        )
        val reference = SegmentObservationAggregator.aggregate(reports)

        // Every rotation and every pairwise swap: all must agree, report for report.
        for (rotation in reports.indices) {
            val rotated = reports.drop(rotation) + reports.take(rotation)
            assertEquals(
                "rotation $rotation changed the derived state",
                reference,
                SegmentObservationAggregator.aggregate(rotated),
            )
        }
        for (i in reports.indices) {
            for (j in reports.indices) {
                val swapped = reports.toMutableList().also { it[i] = reports[j]; it[j] = reports[i] }
                assertEquals(
                    "swap($i,$j) changed the derived state",
                    reference,
                    SegmentObservationAggregator.aggregate(swapped),
                )
            }
        }
        assertEquals(setOf(10, 20, 30), reference.keys.map { it.value }.toSet())
    }

    @Test
    fun reportsAreOrderedByClaimedTimeThenEventIdSoTheListIsStableToo() {
        val later = report("evt-bbbb", "device-a", 10, obs(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE), occurredAt = 2_000)
        val tieEarlierId = report("evt-aaaa", "device-b", 10, obs(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE), occurredAt = 1_000)
        val first = report("evt-0000", "device-c", 10, obs(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE), occurredAt = 1_000)

        val state = SegmentObservationAggregator.aggregate(listOf(later, tieEarlierId, first)).getValue(SegmentId(10))

        assertEquals(
            listOf("evt-0000", "evt-aaaa", "evt-bbbb"),
            state.reports.map { it.eventId.value },
        )
    }

    @Test
    fun anEmptyLogAggregatesToAnEmptyMap() {
        assertTrue(SegmentObservationAggregator.aggregate(emptyList()).isEmpty())
    }

    // ------------------------------------ acceptance: severity keeps its own time

    @Test
    fun aSevereOldReportAndAMildRecentReportAreBothVisibleWithTheirOwnTimes() {
        // The canonical plan's failure mode: MAX(severity) joined to MAX(timestamp) would
        // report "impassable, confirmed just now" - which nobody ever reported.
        val oldSevere = report(
            "evt-old-severe", "device-a", 42,
            obs(HazardReason.BLOCKED, WaterLevelCategory.NO_VISIBLE_WATER, PassabilityStatus.IMPASSABLE),
            occurredAt = 1_000, receivedAt = 1_100,
        )
        val recentMild = report(
            "evt-new-mild", "device-b", 42,
            obs(HazardReason.WATERLOGGED, WaterLevelCategory.ANKLE, PassabilityStatus.PASSABLE_REPORTED),
            occurredAt = 999_000, receivedAt = 999_500,
        )

        val state = SegmentObservationAggregator.aggregate(listOf(oldSevere, recentMild)).getValue(SegmentId(42))

        // Both observations survive, each with its own identity, device and both times.
        assertEquals(2, state.reports.size)
        assertEquals(listOf("evt-old-severe", "evt-new-mild"), state.reports.map { it.eventId.value })
        assertEquals(1_000L, state.reports.first().occurredAtEpochMillis)
        assertEquals(1_100L, state.reports.first().receivedAtEpochMillis)
        assertEquals(999_000L, state.reports.last().occurredAtEpochMillis)
        assertEquals(999_500L, state.reports.last().receivedAtEpochMillis)

        // The verdict is the severe one, and asking for its carriers returns the OLD report -
        // never the recent one.
        assertEquals(PassabilityStatus.IMPASSABLE, state.passability)
        val carriers = state.reportsExcludingTraversal()
        assertEquals(1, carriers.size)
        assertEquals("evt-old-severe", carriers.single().eventId.value)
        assertEquals(1_000L, carriers.single().occurredAtEpochMillis)

        // And the mild report is still visible as itself, with its own recent time.
        val mild = state.reportsWithPassability(PassabilityStatus.PASSABLE_REPORTED)
        assertEquals(1, mild.size)
        assertEquals(999_000L, mild.single().occurredAtEpochMillis)

        // Two distinct devices reported, so support is two - not one, not four.
        assertEquals(2, state.supportCount)
    }

    @Test
    fun theAggregateExposesNoBareTimestampForASeverityToBePairedWith() {
        // Structural guard for the rule above: if a scalar `latestOccurredAt` (or similar)
        // is ever added to the aggregate, this test fails and forces the author to justify
        // pairing a maximum severity with an unrelated newest timestamp.
        val forbidden = listOf("time", "stamp", "latest", "newest", "age", "recent", "clock")
        val suspicious = SegmentObservationState::class.java.declaredFields
            .map { it.name }
            .filter { name -> forbidden.any { name.lowercase().contains(it) } }
        assertTrue(
            "the aggregate must not expose a scalar time value, found: $suspicious",
            suspicious.isEmpty(),
        )
    }

    @Test
    fun theWorstWaterLevelIsAlwaysAccompaniedByTheReportsThatCarryIt() {
        val worst = report("evt-worst", "device-a", 7, obs(HazardReason.WATERLOGGED, WaterLevelCategory.WAIST, PassabilityStatus.CAUTION), occurredAt = 10)
        val other = report("evt-other", "device-b", 7, obs(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION), occurredAt = 20)

        val state = SegmentObservationAggregator.aggregate(listOf(worst, other)).getValue(SegmentId(7))

        assertEquals(WaterLevelCategory.WAIST, state.worstWaterLevel)
        val carriers = state.reportsWithWorstWaterLevel()
        assertEquals(1, carriers.size)
        assertEquals("evt-worst", carriers.single().eventId.value)
        assertEquals(10L, carriers.single().occurredAtEpochMillis)
    }

    // ------------------------------------------- acceptance: distinct device IDs

    @Test
    fun supportCountsDistinctDeviceIdsNeverReports() {
        val oneDeviceManyReports = listOf(
            report("evt-0001", "device-a", 10, obs(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE), occurredAt = 1),
            report("evt-0002", "device-a", 10, obs(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE), occurredAt = 2),
            report("evt-0003", "device-a", 10, obs(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION), occurredAt = 3),
        )
        val state = SegmentObservationAggregator.aggregate(oneDeviceManyReports).getValue(SegmentId(10))

        assertEquals(3, state.reports.size)
        assertEquals("three reports from one device is one device", 1, state.supportCount)
        assertEquals(setOf(DeviceId("device-a")), state.reportingDeviceIds)
    }

    @Test
    fun aRelayedCopyKeepingItsOriginDeviceDoesNotInflateSupport() {
        // A relayed copy carries the ORIGINAL device id (the repository keys on event id), so
        // the same device arriving twice is still one device - ADR 0001 D5.
        val original = report("evt-original", "device-a", 10, obs(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE), occurredAt = 1)
        val relayedCopyDifferentEvent = report("evt-relayed", "device-a", 10, obs(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE), occurredAt = 2)
        val secondDevice = report("evt-second", "device-b", 10, obs(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION), occurredAt = 3)

        assertEquals(
            1,
            SegmentObservationAggregator.aggregate(listOf(original, relayedCopyDifferentEvent))
                .getValue(SegmentId(10)).supportCount,
        )
        assertEquals(
            2,
            SegmentObservationAggregator.aggregate(listOf(original, relayedCopyDifferentEvent, secondDevice))
                .getValue(SegmentId(10)).supportCount,
        )
    }

    @Test
    fun reportingDeviceIdsIterateInAStableOrder() {
        val reports = listOf("device-c", "device-a", "device-b", "device-a").mapIndexed { index, device ->
            report("evt-000$index", device, 10, obs(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION))
        }
        assertEquals(
            listOf("device-a", "device-b", "device-c"),
            SegmentObservationAggregator.aggregate(reports).getValue(SegmentId(10)).reportingDeviceIds.map { it.value },
        )
    }

    // ------------------------------------------------- acceptance: what excludes

    @Test
    fun onlyImpassableExcludesTraversal() {
        val expected = mapOf(
            PassabilityStatus.PASSABLE_REPORTED to false,
            PassabilityStatus.CAUTION to false,
            PassabilityStatus.AVOID to false,
            PassabilityStatus.IMPASSABLE to true,
            PassabilityStatus.UNKNOWN to false,
        )
        for ((status, excludes) in expected) {
            val state = SegmentObservationAggregator.aggregate(
                listOf(report("evt-$status", "device-a", 10, obs(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, status))),
            ).getValue(SegmentId(10))
            assertEquals("$status exclusion", excludes, state.excludesTraversal)
        }
    }

    @Test
    fun aDepthOnlyReportDoesNotExcludeWhileAnImpassableVerdictDoes() {
        // ADR 0002 §2: "a stated depth + UNKNOWN" is a valid, traversable depth-only report.
        val depthOnly = SegmentObservationAggregator.aggregate(
            listOf(report("evt-depth", "device-a", 10, obs(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.UNKNOWN))),
        ).getValue(SegmentId(10))
        assertFalse("a depth-only report must not close a road", depthOnly.excludesTraversal)
        assertEquals(WaterLevelCategory.KNEE, depthOnly.worstWaterLevel)
        assertEquals(PassabilityStatus.UNKNOWN, depthOnly.passability)

        // Adding an impassable verdict to the same segment does close it.
        val withVerdict = SegmentObservationAggregator.aggregate(
            listOf(
                report("evt-depth", "device-a", 10, obs(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.UNKNOWN)),
                report("evt-verdict", "device-b", 10, obs(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE)),
            ),
        ).getValue(SegmentId(10))
        assertTrue(withVerdict.excludesTraversal)
        assertEquals(PassabilityStatus.IMPASSABLE, withVerdict.passability)
    }

    @Test
    fun oneImpassableReportExcludesRegardlessOfHowManyMilderReportsAgree() {
        val reports = listOf(
            report("evt-1", "device-a", 10, obs(HazardReason.WATERLOGGED, WaterLevelCategory.ANKLE, PassabilityStatus.PASSABLE_REPORTED)),
            report("evt-2", "device-b", 10, obs(HazardReason.WATERLOGGED, WaterLevelCategory.ANKLE, PassabilityStatus.PASSABLE_REPORTED)),
            report("evt-3", "device-c", 10, obs(HazardReason.WATERLOGGED, WaterLevelCategory.ANKLE, PassabilityStatus.PASSABLE_REPORTED)),
            report("evt-4", "device-d", 10, obs(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE)),
        )
        val state = SegmentObservationAggregator.aggregate(reports).getValue(SegmentId(10))

        assertTrue("one closure outranks three passable reports", state.excludesTraversal)
        assertEquals(PassabilityStatus.IMPASSABLE, state.passability)
        // A newer-but-weaker observation never resolves it (ADR 0001 D3).
        assertEquals(4, state.supportCount)
    }

    @Test
    fun everyLegacyReportStillExcludesAndInventsNoDepth() {
        for (reason in HazardReason.entries) {
            val state = SegmentObservationAggregator.aggregate(
                listOf(report("evt-$reason", "device-a", 10, RoadObservation.fromLegacy(reason))),
            ).getValue(SegmentId(10))

            assertTrue("$reason must still exclude", state.excludesTraversal)
            assertEquals(PassabilityStatus.IMPASSABLE, state.passability)
            assertEquals("no depth may be invented for $reason", WaterLevelCategory.UNKNOWN, state.worstWaterLevel)
            assertTrue(state.reportsWithWorstWaterLevel().isEmpty())
            assertEquals(reason, state.reports.single().observation?.reason)
            assertFalse(state.hasUnreadableReports)
        }
    }

    @Test
    fun anUnreadableReportExcludesButInventsNeitherAxis() {
        val unreadable = ObservedReport(
            eventId = EventId("evt-unreadable"),
            deviceId = DeviceId("device-a"),
            source = ObservationSource.DIRECT_OBSERVATION,
            segmentId = SegmentId(10),
            occurredAtEpochMillis = 1_000,
            receivedAtEpochMillis = 2_000,
            content = ReportContent.Unreadable("FLOODED_DEEPER"),
        )
        val state = SegmentObservationAggregator.aggregate(listOf(unreadable)).getValue(SegmentId(10))

        assertTrue("ADR 0004 §2: an unrecognised reason still fails closed", state.excludesTraversal)
        assertTrue(state.hasUnreadableReports)
        assertEquals(PassabilityStatus.IMPASSABLE, state.passability)
        assertEquals("no water level may be invented", WaterLevelCategory.UNKNOWN, state.worstWaterLevel)
        assertNull(state.reports.single().observation)
        assertEquals("FLOODED_DEEPER", (state.reports.single().content as ReportContent.Unreadable).reasonCode)
        assertEquals(1, state.supportCount)
    }

    @Test
    fun theTraversalDecisionAlwaysAgreesWithTheReportedVerdict() {
        val fixtures = listOf(
            listOf(obs(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.UNKNOWN)),
            listOf(obs(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE)),
            listOf(obs(HazardReason.WATERLOGGED, WaterLevelCategory.ANKLE, PassabilityStatus.AVOID), obs(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION)),
            listOf(RoadObservation.fromLegacy(HazardReason.BLOCKED)),
        )
        for ((index, observations) in fixtures.withIndex()) {
            val reports = observations.mapIndexed { i, o -> report("evt-$index-$i", "device-$i", 10, o) }
            val state = SegmentObservationAggregator.aggregate(reports).getValue(SegmentId(10))
            assertEquals(
                "fixture $index: excludesTraversal and the reported verdict disagree",
                state.excludesTraversal,
                state.passability == PassabilityStatus.IMPASSABLE,
            )
        }
        // ...and the unreadable case keeps the same invariant.
        val unreadableState = SegmentObservationState(
            SegmentId(10),
            listOf(
                ObservedReport(
                    EventId("e"), DeviceId("d"), ObservationSource.DIRECT_OBSERVATION,
                    SegmentId(10), 0, 0, ReportContent.Unreadable("X"),
                ),
            ),
        )
        assertEquals(unreadableState.excludesTraversal, unreadableState.passability == PassabilityStatus.IMPASSABLE)
    }

    // ---------------------------------------------------------- reductions

    @Test
    fun theWorstWaterLevelIsTheDeepestStatedCategory() {
        val order = listOf(
            WaterLevelCategory.NO_VISIBLE_WATER,
            WaterLevelCategory.ANKLE,
            WaterLevelCategory.KNEE,
            WaterLevelCategory.WAIST,
            WaterLevelCategory.ABOVE_WAIST,
        )
        for ((index, expected) in order.withIndex()) {
            // One report at each level up to `index`: the deepest stated one must win.
            val reports = order.take(index + 1).mapIndexed { i, level ->
                report("evt-$i", "device-$i", 10, obs(HazardReason.WATERLOGGED, level, PassabilityStatus.CAUTION))
            }
            assertEquals(expected, SegmentObservationAggregator.aggregate(reports.reversed()).getValue(SegmentId(10)).worstWaterLevel)
        }
    }

    @Test
    fun aReportThatStatesNothingOnAnAxisNeverChangesThatAxis() {
        val stated = report("evt-stated", "device-a", 10, obs(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION))
        val silentLevel = report("evt-silent-level", "device-b", 10, obs(HazardReason.WATERLOGGED, WaterLevelCategory.UNKNOWN, PassabilityStatus.AVOID))
        val silentVerdict = report("evt-silent-verdict", "device-c", 10, obs(HazardReason.WATERLOGGED, WaterLevelCategory.WAIST, PassabilityStatus.UNKNOWN))

        val state = SegmentObservationAggregator.aggregate(listOf(stated, silentLevel, silentVerdict)).getValue(SegmentId(10))

        assertEquals("an unknown level must not outrank a stated one", WaterLevelCategory.WAIST, state.worstWaterLevel)
        assertEquals("an unknown verdict must not outrank a stated one", PassabilityStatus.AVOID, state.passability)
        // Only the water-level axis is stated by the WAIST report; the AVOID report stated a verdict.
        assertEquals(listOf("evt-silent-verdict"), state.reportsWithWorstWaterLevel().map { it.eventId.value })
        assertEquals(listOf("evt-silent-level"), state.reportsWithPassability(PassabilityStatus.AVOID).map { it.eventId.value })
    }

    @Test
    fun anAxesValueOfUnknownIsReportedOnlyWhenNoReportStatedThatAxis() {
        val onlySilent = SegmentObservationAggregator.aggregate(
            listOf(report("evt-a", "device-a", 10, obs(HazardReason.WATERLOGGED, WaterLevelCategory.UNKNOWN, PassabilityStatus.CAUTION))),
        ).getValue(SegmentId(10))

        assertEquals(WaterLevelCategory.UNKNOWN, onlySilent.worstWaterLevel)
        assertTrue("no report stated a level, so there are no carriers", onlySilent.reportsWithWorstWaterLevel().isEmpty())
        assertEquals(PassabilityStatus.CAUTION, onlySilent.passability)
    }

    // -------------------------------------------------------------- fixture

    private fun obs(
        reason: HazardReason,
        waterLevel: WaterLevelCategory,
        passability: PassabilityStatus,
    ): RoadObservation {
        val outcome = RoadObservation.create(reason, waterLevel, passability)
        check(outcome is ObservationValidation.Valid) { "invalid fixture observation: $outcome" }
        return outcome.observation
    }

    private fun report(
        eventId: String,
        device: String,
        segment: Int,
        observation: RoadObservation,
        occurredAt: Long = 1_000L,
        receivedAt: Long = 2_000L,
        source: ObservationSource = ObservationSource.DIRECT_OBSERVATION,
    ): ObservedReport = ObservedReport(
        eventId = EventId(eventId),
        deviceId = DeviceId(device),
        source = source,
        segmentId = SegmentId(segment),
        occurredAtEpochMillis = occurredAt,
        receivedAtEpochMillis = receivedAt,
        content = ReportContent.Observed(observation),
    )
}
