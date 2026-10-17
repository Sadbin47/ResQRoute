package com.resqroute.hazard

import com.resqroute.core.model.SegmentId
import com.resqroute.domain.hazard.ClosureFreshness
import com.resqroute.domain.hazard.DeviceId
import com.resqroute.domain.hazard.EventId
import com.resqroute.domain.hazard.FreshnessPolicy
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Staleness labelling and clock handling (XPH-72), as a pure function.
 *
 * Implements ADR 0005 §6–§7 and its §8 requirements 8–14. The two properties worth stating
 * up front, because everything else follows from them:
 *
 *  * a label is **never** a restriction — no `now` exists that un-closes a road;
 *  * the age comes from **local receipt**, so a peer cannot make a closure look fresh.
 */
class FreshnessPolicyTest {

    private val now = 1_700_000_000_000L
    private val h12 = FreshnessPolicy.STALE_AFTER_MILLIS
    private val h72 = FreshnessPolicy.RECHECK_DEADLINE_AFTER_MILLIS

    // ------------------------------------------------------- requirement 8: states

    @Test
    fun aFreshClosureIsCurrent() {
        val freshness = freshnessOf(closureReceivedAgo(1_000L))
        assertEquals(ClosureFreshness.CURRENT, freshness.label)
        assertEquals(1_000L, freshness.ageMillis)
        assertTrue(freshness.claimedTimeVerified)
    }

    @Test
    fun theStaleBoundaryIsExactToTheMillisecond() {
        // age ≤ 12 h is Current; 12 h + 1 ms is Stale (ADR 0005 §6.3).
        assertEquals(ClosureFreshness.CURRENT, freshnessOf(closureReceivedAgo(h12)).label)
        assertEquals(ClosureFreshness.STALE, freshnessOf(closureReceivedAgo(h12 + 1)).label)
        assertEquals(ClosureFreshness.STALE, freshnessOf(closureReceivedAgo(h72)).label)
        assertEquals(ClosureFreshness.VERY_STALE, freshnessOf(closureReceivedAgo(h72 + 1)).label)
    }

    @Test
    fun theRecheckBoundaryIsExactToTheMillisecond() {
        assertEquals(ClosureFreshness.STALE, freshnessOf(closureReceivedAgo(h72 - 1)).label)
        assertEquals(ClosureFreshness.STALE, freshnessOf(closureReceivedAgo(h72)).label)
        assertEquals(ClosureFreshness.VERY_STALE, freshnessOf(closureReceivedAgo(h72 + 1)).label)
    }

    @Test
    fun aSegmentWithNoClosureHasNoClosureFreshnessToReport() {
        val state = SegmentObservationAggregator.aggregate(
            listOf(
                report(
                    "evt-mild", "device-a", 10,
                    obs(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION),
                ),
            ),
        ).getValue(SegmentId(10))

        val freshness = FreshnessPolicy.closureFreshness(state, now)
        assertEquals(ClosureFreshness.NO_CLOSURE, freshness.label)
        assertEquals(null, freshness.basis)
        assertEquals(null, freshness.ageMillis)
    }

    // ------------------------------- requirement: staleness never reopens a road

    @Test
    fun noValueOfNowMakesAnExcludedSegmentAnythingButExcluded() {
        // The sweep is the point: freshness is a label. Whatever the clock says - including
        // absurd extremes - the segment the router consults stays excluded.
        val state = closureReceivedAgo(1_000L)
        assertTrue(state.excludesTraversal)

        val probes = listOf(
            Long.MIN_VALUE / 4, -1L, 0L, now - h72 * 100, now - h72, now - h12, now,
            now + h12, now + h72 * 100, now + 100L * 365 * 24 * 3600_000, Long.MAX_VALUE / 4,
        )
        val labels = mutableSetOf<ClosureFreshness>()
        for (probe in probes) {
            val freshness = FreshnessPolicy.closureFreshness(state, probe)
            labels += freshness.label
            assertTrue(
                "freshness at now=$probe folded the closure away",
                state.excludesTraversal,
            )
            assertEquals(PassabilityStatus.IMPASSABLE, state.passability)
            assertTrue(
                "a closure may never be reported as having no closure",
                freshness.label != ClosureFreshness.NO_CLOSURE,
            )
        }
        // The sweep is meaningful: it really does produce several different labels.
        assertTrue("expected several labels, got $labels", labels.size >= 3)
    }

    @Test
    fun aStaleClosureIsStillExcludedAndStillImpassable() {
        // The regression the issue asks for, at the labelling level. The exclusion is the
        // same value the router reads; only the wording aged.
        for (ago in listOf(1L, h12, h12 + 1, h72, h72 + 1, 30L * 24 * 3600_000)) {
            val state = closureReceivedAgo(ago)
            val freshness = FreshnessPolicy.closureFreshness(state, now)
            assertTrue("age $ago: the closure must survive", state.excludesTraversal)
            assertEquals(PassabilityStatus.IMPASSABLE, state.passability)
            assertNotNull(freshness.basis)
            assertEquals(
                "age $ago: the label must describe the closing report",
                state.reportsExcludingTraversal().single().eventId,
                freshness.basis?.eventId,
            )
        }
    }

    @Test
    fun aGenuinelyOldReportIsStaleNotUnverified() {
        // Regression for a flaw in this rule's first draft: "old" was defined relative to
        // now (older than 180 days), so after two years a real report was labelled "time
        // not verified" instead of "very stale". Old and implausible are different things,
        // and old is the normal case for a report that survives until somebody rechecks.
        val twoYears = 2L * 365 * 24 * 60 * 60 * 1000
        val state = claimedAtAgo(claimedAt = now - twoYears, receivedAgo = twoYears)

        val freshness = FreshnessPolicy.closureFreshness(state, now)
        assertTrue("an old claim is believable", freshness.claimedTimeVerified)
        assertEquals(ClosureFreshness.VERY_STALE, freshness.label)
        assertEquals(twoYears, freshness.ageMillis)
        assertTrue(state.excludesTraversal)
    }

    @Test
    fun onlyATimePredatingTheSystemIsImplausibleOnTheOldSide() {
        assertTrue(FreshnessPolicy.isClaimedTimePlausible(FreshnessPolicy.EARLIEST_PLAUSIBLE_OBSERVATION_MILLIS, now))
        assertFalse(FreshnessPolicy.isClaimedTimePlausible(FreshnessPolicy.EARLIEST_PLAUSIBLE_OBSERVATION_MILLIS - 1, now))
        // A year-old claim is old, not impossible.
        assertTrue(FreshnessPolicy.isClaimedTimePlausible(now - 365L * 24 * 60 * 60 * 1000, now))
    }

    // --------------------------- requirement 10: a negative age is never "current"

    @Test
    fun aNegativeAgeNeverRendersCurrent() {
        // The local clock moved backwards after the report was stamped.
        val state = closureReceivedAgo(-1L)
        val freshness = FreshnessPolicy.closureFreshness(state, now)
        assertEquals(ClosureFreshness.TIME_NOT_VERIFIED, freshness.label)
        assertEquals(-1L, freshness.ageMillis)
        assertFalse("an age that far in the future is not a verified age", freshness.ageIsKnown)
        assertTrue(state.excludesTraversal)
    }

    @Test
    fun aClockRolledFarBackwardsStillCannotOpenTheRoad() {
        val state = closureReceivedAgo(-10L * 365 * 24 * 3600_000)
        assertEquals(ClosureFreshness.TIME_NOT_VERIFIED, FreshnessPolicy.closureFreshness(state, now).label)
        assertTrue(state.excludesTraversal)
    }

    // ------------------------ requirement 11: an implausible claim is not verified

    @Test
    fun aFutureClaimedTimeRendersTimeNotVerifiedAndKeepsTheClosure() {
        val justInsideTolerance = claimed(
            claimedAgo = -FreshnessPolicy.FUTURE_TIME_TOLERANCE_MILLIS,
            receivedAgo = 1_000L,
        )
        assertEquals(
            "a claim inside the tolerance is ordinary clock drift",
            ClosureFreshness.CURRENT,
            FreshnessPolicy.closureFreshness(justInsideTolerance, now).label,
        )

        val beyondTolerance = claimed(
            claimedAgo = -(FreshnessPolicy.FUTURE_TIME_TOLERANCE_MILLIS + 1),
            receivedAgo = 1_000L,
        )
        val freshness = FreshnessPolicy.closureFreshness(beyondTolerance, now)
        assertEquals(ClosureFreshness.TIME_NOT_VERIFIED, freshness.label)
        assertFalse(freshness.claimedTimeVerified)
        assertTrue("the closure survives an unbelievable claim", beyondTolerance.excludesTraversal)
    }

    @Test
    fun anAbsurdlyOldOrZeroClaimedTimeIsNotVerified() {
        val absurd = listOf(
            0L,
            -1L,
            Long.MIN_VALUE / 4,
            FreshnessPolicy.EARLIEST_PLAUSIBLE_OBSERVATION_MILLIS - 1,
        )
        for (claimedAt in absurd) {
            val state = claimedAtAgo(claimedAt, receivedAgo = 1_000L)
            assertEquals(
                "claimed=$claimedAt",
                ClosureFreshness.TIME_NOT_VERIFIED,
                FreshnessPolicy.closureFreshness(state, now).label,
            )
            assertTrue("claimed=$claimedAt must stay excluded", state.excludesTraversal)
        }
    }

    @Test
    fun aClaimAtTheEdgeOfPlausibilityIsVerified() {
        assertTrue(FreshnessPolicy.isClaimedTimePlausible(FreshnessPolicy.EARLIEST_PLAUSIBLE_OBSERVATION_MILLIS, now))
        assertFalse(FreshnessPolicy.isClaimedTimePlausible(FreshnessPolicy.EARLIEST_PLAUSIBLE_OBSERVATION_MILLIS - 1, now))
        assertTrue(FreshnessPolicy.isClaimedTimePlausible(now, now))
        assertTrue(
            FreshnessPolicy.isClaimedTimePlausible(now + FreshnessPolicy.FUTURE_TIME_TOLERANCE_MILLIS, now),
        )
        assertFalse(
            FreshnessPolicy.isClaimedTimePlausible(now + FreshnessPolicy.FUTURE_TIME_TOLERANCE_MILLIS + 1, now),
        )
    }

    // --------------------- requirement: receipt time, never a peer's claimed time

    @Test
    fun ageComesFromLocalReceiptSoAPeerCannotMakeAClosureLookNew() {
        // A month-old observation forwarded to us a minute ago: we can only honestly say
        // "we received this a minute ago", and it is still excluded. The reverse of the
        // §6.2 trap.
        val relayed = claimed(claimedAgo = 30L * 24 * 3600_000, receivedAgo = 60_000L)
        assertEquals(ClosureFreshness.CURRENT, FreshnessPolicy.closureFreshness(relayed, now).label)

        // A claim of "just now" on a report we received four days ago cannot make it fresh.
        val staleClaim = claimed(claimedAgo = 0L, receivedAgo = h72 + 1)
        val freshness = FreshnessPolicy.closureFreshness(staleClaim, now)
        assertEquals(ClosureFreshness.VERY_STALE, freshness.label)
        assertEquals(
            "the basis is the received-at age, not the claim",
            h72 + 1,
            freshness.ageMillis,
        )
    }

    // -------------------- requirement: freshness follows the closing observation

    @Test
    fun aRecentMildReportCannotMakeAnOldClosureLookFresh() {
        // The plan's §4.6 trap, at the labelling level: the closure was reported four days
        // ago and somebody else says "ankle deep, passable" ten minutes ago. The closure's
        // freshness must describe the CLOSING report, not the newest report of any kind.
        val oldClosure = report(
            "evt-old-closure", "device-a", 42,
            obs(HazardReason.BLOCKED, WaterLevelCategory.NO_VISIBLE_WATER, PassabilityStatus.IMPASSABLE),
            receivedAgo = h72 + 1,
        )
        val recentMild = report(
            "evt-recent-mild", "device-b", 42,
            obs(HazardReason.WATERLOGGED, WaterLevelCategory.ANKLE, PassabilityStatus.PASSABLE_REPORTED),
            receivedAgo = 600_000L,
        )
        val state = SegmentObservationAggregator.aggregate(listOf(oldClosure, recentMild)).getValue(SegmentId(42))

        val freshness = FreshnessPolicy.closureFreshness(state, now)
        assertEquals(ClosureFreshness.VERY_STALE, freshness.label)
        assertEquals("evt-old-closure", freshness.basis?.eventId?.value)
        assertEquals(h72 + 1, freshness.ageMillis)
        assertTrue(state.excludesTraversal)
        // ...and the mild report is still visible as itself.
        assertEquals(2, state.reports.size)
        assertEquals(2, state.supportCount)
    }

    @Test
    fun theBasisIsTheNewestReceiptAmongTheClosingReports() {
        // Two closures, received at different times: the newest one is the best evidence that
        // the road is still closed, so it is what the label describes.
        val older = report(
            "evt-closure-old", "device-a", 10,
            obs(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE),
            receivedAgo = 3 * h12,
        )
        val newer = report(
            "evt-closure-new", "device-b", 10,
            obs(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE),
            receivedAgo = 1_000L,
        )
        val state = SegmentObservationAggregator.aggregate(listOf(older, newer)).getValue(SegmentId(10))

        val freshness = FreshnessPolicy.closureFreshness(state, now)
        assertEquals("evt-closure-new", freshness.basis?.eventId?.value)
        assertEquals(ClosureFreshness.CURRENT, freshness.label)
        assertEquals(1_000L, freshness.ageMillis)
    }

    @Test
    fun anUnreadableReportAgesLikeAnyOtherClosure() {
        // A row this build cannot read still closes a road; it also has an age, and no water
        // level or passability is invented while labelling it.
        val unreadable = ObservedReport(
            eventId = EventId("evt-unreadable"),
            deviceId = DeviceId("device-a"),
            source = ObservationSource.DIRECT_OBSERVATION,
            segmentId = SegmentId(10),
            occurredAtEpochMillis = now - 1_000L,
            receivedAtEpochMillis = now - 2 * h72,
            content = ReportContent.Unreadable("FLOODED_DEEPER"),
        )
        val state = SegmentObservationAggregator.aggregate(listOf(unreadable)).getValue(SegmentId(10))

        val freshness = FreshnessPolicy.closureFreshness(state, now)
        assertEquals(ClosureFreshness.VERY_STALE, freshness.label)
        assertEquals("evt-unreadable", freshness.basis?.eventId?.value)
        assertTrue(state.excludesTraversal)
        assertEquals(WaterLevelCategory.UNKNOWN, state.worstWaterLevel)
    }

    // ------------------------------- requirement: determinism with injected clocks

    @Test
    fun theSameStateAndNowAlwaysProduceTheSameResult() {
        val state = closureReceivedAgo(h12 + 1)
        val first = FreshnessPolicy.closureFreshness(state, now)
        repeat(50) { assertEquals(first, FreshnessPolicy.closureFreshness(state, now)) }
        // ...and the result is a function of `now`, not of when the test ran.
        assertEquals(
            ClosureFreshness.CURRENT,
            FreshnessPolicy.closureFreshness(state, state.reports.single().receivedAtEpochMillis).label,
        )
    }

    @Test
    fun noOrderingOfTheSameReportsChangesTheLabel() {
        val reports = listOf(
            report("evt-1", "device-a", 10, obs(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE), receivedAgo = 3 * h12),
            report("evt-2", "device-b", 10, obs(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION), receivedAgo = 10L),
            report("evt-3", "device-c", 10, obs(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE), receivedAgo = 5 * h12),
        )
        val reference = FreshnessPolicy.closureFreshness(
            SegmentObservationAggregator.aggregate(reports).getValue(SegmentId(10)),
            now,
        )
        for (rotation in reports.indices) {
            val shuffled = reports.drop(rotation) + reports.take(rotation)
            assertEquals(
                "rotation $rotation changed the label",
                reference,
                FreshnessPolicy.closureFreshness(
                    SegmentObservationAggregator.aggregate(shuffled).getValue(SegmentId(10)),
                    now,
                ),
            )
        }
    }

    // ------------------------------- requirement 14: names cannot mislead

    @Test
    fun noFreshnessConstantIsNamedLikeTheRejectedTtlRule() {
        val forbidden = listOf("ttl", "timeout", "expiry", "expire")
        val offenders = FreshnessPolicy::class.java.declaredFields
            .map { it.name }
            .filter { name -> forbidden.any { name.lowercase().contains(it) } }
        assertTrue(
            "ADR 0001 §3 rejected a TTL that removes a closure; these names would resurrect it: $offenders",
            offenders.isEmpty(),
        )
    }

    // -------------------------------------------------------------- fixtures

    /** An impassable closure whose local receipt [ago] millis before [now], claim matching. */
    private fun closureReceivedAgo(ago: Long): SegmentObservationState = claimed(
        claimedAgo = ago,
        receivedAgo = ago,
    )

    private fun claimed(claimedAgo: Long, receivedAgo: Long): SegmentObservationState = claimedAtAgo(
        claimedAt = now - claimedAgo,
        receivedAgo = receivedAgo,
    )

    private fun claimedAtAgo(claimedAt: Long, receivedAgo: Long): SegmentObservationState =
        SegmentObservationAggregator.aggregate(
            listOf(
                ObservedReport(
                    eventId = EventId("evt-closure"),
                    deviceId = DeviceId("device-a"),
                    source = ObservationSource.DIRECT_OBSERVATION,
                    segmentId = SegmentId(10),
                    occurredAtEpochMillis = claimedAt,
                    receivedAtEpochMillis = now - receivedAgo,
                    content = ReportContent.Observed(
                        obs(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE),
                    ),
                ),
            ),
        ).getValue(SegmentId(10))

    private fun freshnessOf(state: SegmentObservationState) =
        FreshnessPolicy.closureFreshness(state, now)

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
        receivedAgo: Long = 1_000L,
        source: ObservationSource = ObservationSource.DIRECT_OBSERVATION,
    ): ObservedReport = ObservedReport(
        eventId = EventId(eventId),
        deviceId = DeviceId(device),
        source = source,
        segmentId = SegmentId(segment),
        occurredAtEpochMillis = now - receivedAgo,
        receivedAtEpochMillis = now - receivedAgo,
        content = ReportContent.Observed(observation),
    )
}
