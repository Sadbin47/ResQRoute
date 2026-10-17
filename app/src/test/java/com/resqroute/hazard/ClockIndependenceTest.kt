package com.resqroute.hazard

import android.app.Application
import android.content.Context
import com.resqroute.core.model.SegmentId
import com.resqroute.data.local.HazardEventEntity
import com.resqroute.data.local.ResQRouteDatabase
import com.resqroute.data.repository.RoomHazardRepository
import com.resqroute.domain.hazard.ClosureFreshness
import com.resqroute.domain.hazard.FreshnessPolicy
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.HazardState
import com.resqroute.domain.hazard.MutationResult
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Time cannot change what the router may do (XPH-72).
 *
 * The acceptance criteria for this phase are mostly *negative*: no code path removes an
 * exclusion because of time alone, and a future-dated report cannot make a closure look
 * fresher than its local receipt. Negative properties are only believable when they are
 * exercised against real persisted rows at clocks years apart, which is what this class
 * does.
 *
 * [FreshnessPolicyTest] proves the labelling rules; this proves the separation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ClockIndependenceTest {

    private lateinit var context: Context
    private val databases = mutableListOf<ResQRouteDatabase>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val eventIds = AtomicInteger(0)

    @Before
    fun setUp() {
        context = HazardFixtures.context()
        context.deleteDatabase(HazardFixtures.DATABASE_NAME)
        context.deleteDatabase(SECOND_DATABASE_NAME)
    }

    @After
    fun tearDown() {
        databases.forEach { runCatching { it.close() } }
        scopes.forEach { it.cancel() }
        context.deleteDatabase(HazardFixtures.DATABASE_NAME)
        context.deleteDatabase(SECOND_DATABASE_NAME)
    }

    // ------------------------------------------------ the required regression

    @Test
    fun aClosureAgedYearsBeyondEveryThresholdIsStillExcluded() = runBlocking {
        val dayZero = open(HazardFixtures.DATABASE_NAME)
        val atDayZero = repositoryOn(dayZero) { HazardFixtures.FIXED_CLOCK }
        atDayZero.initialize(HazardFixtures.contextAlpha)
        assertTrue(atDayZero.ingestRemote(legacyBlock("evt-old-closure", 401)) is MutationResult.Accepted)
        HazardFixtures.awaitObservedState(atDayZero, 401)
        assertTrue((atDayZero.state.value as HazardState.Ready).snapshot.isBlockedSegment(401))
        dayZero.close()

        // Two years later, a cold start over the same report.
        val yearsLater = HazardFixtures.FIXED_CLOCK + 2L * 365 * 24 * 60 * 60 * 1000
        val later = open(HazardFixtures.DATABASE_NAME)
        val atTwoYears = repositoryOn(later) { yearsLater }
        atTwoYears.initialize(HazardFixtures.contextAlpha)
        val ready = HazardFixtures.awaitReady(atTwoYears)

        // Still excluded, still impassable, and the revision was rebuilt from scratch.
        assertTrue("time alone opened a road", ready.snapshot.isBlockedSegment(401))
        assertEquals(setOf(401), ready.snapshot.blockedSegments.map { it.value }.toSet())
        assertEquals(0L, ready.snapshot.revision)

        val state = atTwoYears.observationStates.value.getValue(SegmentId(401))
        assertTrue(state.excludesTraversal)
        assertEquals(PassabilityStatus.IMPASSABLE, state.passability)

        // Only the wording aged - and it says so, plainly.
        val freshness = FreshnessPolicy.closureFreshness(state, yearsLater)
        assertEquals(ClosureFreshness.VERY_STALE, freshness.label)
        assertEquals(2L * 365 * 24 * 60 * 60 * 1000, freshness.ageMillis)
    }

    @Test
    fun theBlockedSetVerdictsAndRevisionAreIdenticalAtAnyClock() = runBlocking {
        val events = listOf(
            legacyBlock("evt-cl-0001", 101),
            HazardFixtures.mutation(
                "evt-cl-0002", segmentId = 102,
                observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.UNKNOWN),
            ),
            HazardFixtures.mutation(
                "evt-cl-0003", segmentId = 102, deviceId = "device-b-0002",
                observation = HazardFixtures.observation(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE),
            ),
            HazardFixtures.mutation("evt-cl-0004", segmentId = 103, reason = HazardReason.WATERLOGGED),
        )

        val now = ingestInto(HazardFixtures.DATABASE_NAME, events, HazardFixtures.FIXED_CLOCK)
        val farFuture = ingestInto(SECOND_DATABASE_NAME, events, HazardFixtures.FIXED_CLOCK + 5L * 365 * 24 * 60 * 60 * 1000)

        // Routing state: identical, including the revision a reroute keys on.
        val nowSnapshot = (now.state.value as HazardState.Ready).snapshot
        val laterSnapshot = (farFuture.state.value as HazardState.Ready).snapshot
        assertEquals(nowSnapshot.blockedSegments, laterSnapshot.blockedSegments)
        assertEquals(nowSnapshot.revision, laterSnapshot.revision)

        // Derived verdicts: identical. Only the receipt timestamps are data that differ.
        assertEquals(now.observationStates.value.keys, farFuture.observationStates.value.keys)
        for ((segment, state) in now.observationStates.value) {
            val laterState = farFuture.observationStates.value.getValue(segment)
            assertEquals(state.excludesTraversal, laterState.excludesTraversal)
            assertEquals(state.passability, laterState.passability)
            assertEquals(state.worstWaterLevel, laterState.worstWaterLevel)
            assertEquals(state.supportCount, laterState.supportCount)
            assertEquals(state.reports.size, laterState.reports.size)
            assertEquals(state.hasUnreadableReports, laterState.hasUnreadableReports)
        }
        assertEquals(
            "101 legacy block, 102 impassable verdict, 103 legacy block",
            setOf(101, 102, 103),
            nowSnapshot.blockedSegments.map { it.value }.toSet(),
        )
    }

    // ---------------------------------------------- a peer cannot set the age

    @Test
    fun aFreshClaimOnAnOldReceiptCannotMakeAClosureLookNew() = runBlocking {
        // Written as a peer's report would be: the claim says "just now", the local receipt
        // says four days ago. The label must follow the receipt.
        seedRow(
            databaseName = HazardFixtures.DATABASE_NAME,
            eventId = "evt-claim-now",
            segment = 501,
            reasonCode = HazardReason.BLOCKED.code,
            occurredAt = HazardFixtures.FIXED_CLOCK,
            receivedAt = HazardFixtures.FIXED_CLOCK - 4L * 24 * 60 * 60 * 1000,
        )
        val repository = repositoryOn(open(HazardFixtures.DATABASE_NAME)) { HazardFixtures.FIXED_CLOCK }
        repository.initialize(HazardFixtures.contextAlpha)
        val state = HazardFixtures.awaitObservedState(repository, 501)

        val freshness = FreshnessPolicy.closureFreshness(state, HazardFixtures.FIXED_CLOCK)
        assertEquals("a peer's claim must not buy freshness", ClosureFreshness.VERY_STALE, freshness.label)
        assertEquals(4L * 24 * 60 * 60 * 1000, freshness.ageMillis)
        assertTrue(state.excludesTraversal)

        // The mirror image: an old claim we received a minute ago is labelled from receipt,
        // which under-states the true age - the direction ADR 0005 §6.2 chose on purpose.
        seedRow(
            databaseName = SECOND_DATABASE_NAME,
            eventId = "evt-claim-old",
            segment = 502,
            reasonCode = HazardReason.BLOCKED.code,
            occurredAt = HazardFixtures.FIXED_CLOCK - 30L * 24 * 60 * 60 * 1000,
            receivedAt = HazardFixtures.FIXED_CLOCK - 60_000,
        )
        val mirrored = repositoryOn(open(SECOND_DATABASE_NAME)) { HazardFixtures.FIXED_CLOCK }
        mirrored.initialize(HazardFixtures.contextAlpha)
        val mirroredState = HazardFixtures.awaitObservedState(mirrored, 502)
        assertEquals(
            ClosureFreshness.CURRENT,
            FreshnessPolicy.closureFreshness(mirroredState, HazardFixtures.FIXED_CLOCK).label,
        )
        assertTrue(mirroredState.excludesTraversal)
    }

    @Test
    fun aFutureDatedReportIsFlaggedAndStillClosesTheRoad() = runBlocking {
        val aYearAhead = HazardFixtures.FIXED_CLOCK + 365L * 24 * 60 * 60 * 1000
        seedRow(
            databaseName = HazardFixtures.DATABASE_NAME,
            eventId = "evt-future",
            segment = 601,
            reasonCode = HazardReason.BLOCKED.code,
            occurredAt = aYearAhead,
            receivedAt = HazardFixtures.FIXED_CLOCK - 60_000,
        )
        val repository = repositoryOn(open(HazardFixtures.DATABASE_NAME)) { HazardFixtures.FIXED_CLOCK }
        repository.initialize(HazardFixtures.contextAlpha)
        val state = HazardFixtures.awaitObservedState(repository, 601)

        val freshness = FreshnessPolicy.closureFreshness(state, HazardFixtures.FIXED_CLOCK)
        assertEquals(ClosureFreshness.TIME_NOT_VERIFIED, freshness.label)
        assertFalse(freshness.claimedTimeVerified)
        // Flagged, never rejected: the road is closed and the evidence is kept.
        assertTrue(state.excludesTraversal)
        assertTrue((repository.state.value as HazardState.Ready).snapshot.isBlockedSegment(601))
        assertEquals(1, state.reports.size)
    }

    // ------------------------------------ the label is not part of the state

    @Test
    fun thePublishedDerivedStateStoresNoFreshnessLabel() {
        // If a label were stored, the derived state would silently become time-dependent and
        // would need a ticker to stay true. It is computed on demand from (state, now).
        val freshnessFields = SegmentObservationState::class.java.declaredFields
            .map { it.type.name }
            .filter { it.contains("ClosureFreshness") || it.contains("SegmentFreshness") }
        assertTrue(
            "the derived state must stay clock-free; found $freshnessFields",
            freshnessFields.isEmpty(),
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun legacyBlock(eventId: String, segmentId: Int) =
        HazardFixtures.mutation(eventId, segmentId, HazardReason.BLOCKED)

    private suspend fun seedRow(
        databaseName: String,
        eventId: String,
        segment: Long,
        reasonCode: String,
        occurredAt: Long,
        receivedAt: Long,
    ) {
        open(databaseName).hazardDao().insert(
            HazardEventEntity(
                eventId = eventId,
                datasetHash = HazardFixtures.DATASET_HASH,
                incidentId = HazardFixtures.INCIDENT,
                segmentId = segment,
                originDeviceId = "peer-device-0009",
                reasonCode = reasonCode,
                occurredAtEpochMillis = occurredAt,
                receivedAtEpochMillis = receivedAt,
            ),
        )
    }

    private suspend fun ingestInto(
        databaseName: String,
        events: List<com.resqroute.domain.hazard.HazardMutation>,
        clock: Long,
    ): RoomHazardRepository {
        val repository = repositoryOn(open(databaseName)) { clock }
        repository.initialize(HazardFixtures.contextAlpha)
        HazardFixtures.awaitReady(repository)
        for (event in events) {
            val result = repository.ingestRemote(event)
            assertTrue("ingesting ${event.eventId.value} returned $result", result is MutationResult.Accepted)
        }
        HazardFixtures.awaitDerivedState(repository) { states ->
            states.size == events.map { it.segmentId }.distinct().size
        }
        return repository
    }

    private fun open(databaseName: String): ResQRouteDatabase =
        HazardFixtures.openDatabase(context, databaseName).also { databases += it }

    private fun repositoryOn(
        database: ResQRouteDatabase,
        clock: () -> Long,
    ): RoomHazardRepository = HazardFixtures.repository(
        database = database,
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()).also { scopes += it },
        eventIds = eventIds,
        clock = clock,
    )

    private companion object {
        const val SECOND_DATABASE_NAME = "resqroute-hazard-clock-test.db"
    }
}
