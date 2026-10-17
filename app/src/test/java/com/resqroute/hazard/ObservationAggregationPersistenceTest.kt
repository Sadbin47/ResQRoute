package com.resqroute.hazard

import android.app.Application
import android.content.Context
import com.resqroute.core.model.SegmentId
import com.resqroute.data.local.HazardEventEntity
import com.resqroute.data.local.ResQRouteDatabase
import com.resqroute.data.repository.RoomHazardRepository
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.HazardState
import com.resqroute.domain.hazard.MutationResult
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.WaterLevelCategory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The aggregation as it actually ships: derived from committed rows, through the repository's
 * single resolution rule (XPH-69).
 *
 * [SegmentObservationAggregationTest] proves the rule; this class proves the wiring — that the
 * derivation is driven by real persisted rows, that insertion order and repetition cannot
 * change it, and that the axes-aware exclusion is live rather than latent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ObservationAggregationPersistenceTest {

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

    // ------------------------------------------------ acceptance: order, replay

    @Test
    fun theSameEventSetInsertedInAnyOrderDerivesTheSameState() = runBlocking {
        val events = listOf(
            HazardFixtures.mutation(
                "evt-a-0001", segmentId = 101,
                observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION),
            ),
            HazardFixtures.mutation(
                "evt-a-0002", segmentId = 101, deviceId = "device-b-0002",
                observation = HazardFixtures.observation(HazardReason.BLOCKED, WaterLevelCategory.NO_VISIBLE_WATER, PassabilityStatus.IMPASSABLE),
            ),
            HazardFixtures.mutation(
                "evt-a-0003", segmentId = 102,
                observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.WAIST, PassabilityStatus.AVOID),
            ),
            HazardFixtures.mutation(
                "evt-a-0004", segmentId = 102, deviceId = "device-b-0002",
                observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE),
            ),
            HazardFixtures.mutation("evt-a-0005", segmentId = 103, reason = HazardReason.BLOCKED),
        )

        val forwards = ingestInto(HazardFixtures.DATABASE_NAME, events)
        val backwards = ingestInto(SECOND_DATABASE_NAME, events.reversed())

        assertEquals(
            "insertion order changed the derived state",
            forwards.observationStates.value,
            backwards.observationStates.value,
        )
        assertEquals(
            "insertion order changed the blocked set",
            (forwards.state.value as HazardState.Ready).snapshot.blockedSegments,
            (backwards.state.value as HazardState.Ready).snapshot.blockedSegments,
        )
        // The fixture is meaningful: three segments, two of them closed.
        assertEquals(setOf(101, 102, 103), forwards.observationStates.value.keys.map { it.value }.toSet())
    }

    @Test
    fun reIngestingTheWholeLogTwiceLeavesTheDerivedStateIdentical() = runBlocking {
        val events = listOf(
            HazardFixtures.mutation("evt-b-0001", segmentId = 201, reason = HazardReason.BLOCKED),
            HazardFixtures.mutation(
                "evt-b-0002", segmentId = 201, deviceId = "device-b-0002",
                observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION),
            ),
            HazardFixtures.mutation(
                "evt-b-0003", segmentId = 202,
                observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.ANKLE, PassabilityStatus.PASSABLE_REPORTED),
            ),
        )
        val repository = ingestInto(HazardFixtures.DATABASE_NAME, events)
        val before = repository.observationStates.value
        val revisionBefore = (repository.state.value as HazardState.Ready).snapshot.revision

        repeat(2) {
            val log = repository.persistedEvents(HazardFixtures.contextAlpha).toList()
            assertEquals(3, log.size)
            for (mutation in log) {
                assertEquals(
                    "replaying ${mutation.eventId.value} must be a no-op",
                    MutationResult.Duplicate,
                    repository.ingestRemote(mutation),
                )
            }
        }

        assertEquals("replay changed the derived state", before, repository.observationStates.value)
        assertEquals(revisionBefore, (repository.state.value as HazardState.Ready).snapshot.revision)
        assertEquals(3, repository.databaseEventCount())
    }

    // ------------------------- acceptance: many shuffled and duplicated applications

    @Test
    fun shuffledAndDuplicatedApplicationConvergesOnOneDerivedState() = runBlocking {
        // XPH-79's convergence criterion, at the level where arrival order and duplication actually
        // happen. Duplicates are absorbed at the single ingress by event id, so this is where "the same
        // event twice changes nothing" is a property of the system rather than of the aggregator's
        // contract: a replay, a relayed echo and a re-send all land as MutationResult.Duplicate.
        val events = listOf(
            HazardFixtures.mutation("evt-s-0001", segmentId = 701, reason = HazardReason.BLOCKED),
            HazardFixtures.mutation(
                "evt-s-0002", segmentId = 701, deviceId = "device-b-0002",
                observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION),
            ),
            HazardFixtures.mutation(
                "evt-s-0003", segmentId = 702,
                observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.WAIST, PassabilityStatus.AVOID),
            ),
            HazardFixtures.mutation(
                "evt-s-0004", segmentId = 702, deviceId = "device-c-0003",
                observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.NO_VISIBLE_WATER, PassabilityStatus.PASSABLE_REPORTED),
            ),
            HazardFixtures.mutation("evt-s-0005", segmentId = 703, reason = HazardReason.WATERLOGGED),
            HazardFixtures.mutation(
                "evt-s-0006", segmentId = 703, deviceId = "device-d-0004",
                observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.ANKLE, PassabilityStatus.CAUTION),
            ),
        )

        val reference = ingestInto(HazardFixtures.DATABASE_NAME, events)
        val expected = reference.observationStates.value
        assertEquals("the fixture must derive real state", 3, expected.size)

        val names = mutableListOf<String>()
        try {
            for (seed in listOf(1L, 2L, 3L, 4L)) {
                val name = "${HazardFixtures.DATABASE_NAME}.shuffle$seed"
                names += name
                val random = kotlin.random.Random(seed)
                // Every event applied one to three times, in a shuffled order.
                val applications = events.flatMap { event -> List(random.nextInt(1, 4)) { event } }.shuffled(random)

                val repository = HazardFixtures.repository(open(name), newScope(), eventIds = eventIds)
                repository.initialize(HazardFixtures.contextAlpha)
                HazardFixtures.awaitReady(repository)
                for (event in applications) repository.ingestRemote(event)

                assertEquals(
                    "seed $seed: a shuffled, duplicated application derived a different state",
                    expected,
                    // Wait for the *whole* set, not merely for the segments to appear: reading the flow
                    // mid-emission would compare a partial derivation.
                    HazardFixtures.awaitDerivedState(repository) { states ->
                        states.size == events.map { it.segmentId }.distinct().size &&
                            states.values.sumOf { state -> state.reports.size } == events.size
                    },
                )
                assertEquals(
                    "seed $seed: duplicates must not become rows",
                    events.size,
                    repository.databaseEventCount(),
                )
            }
        } finally {
            names.forEach { context.deleteDatabase(it) }
        }
    }

    @Test
    fun noPersistedRowCanEverBeAClearanceSoEveryExistingDatabaseIsUnchanged() = runBlocking {
        // ADR 0005 §8 requirement 1: with no clearance events, the excluded set is what it always was.
        // That holds here *by construction* - no column, no codec field and no UI produces a clearance, so
        // the resolution rule is unreachable from persisted rows and this phase changes no route on any
        // device that has already shipped. Proven rather than asserted, because the day a producer is
        // added, this test is what should fail and be replaced by the new behaviour's own tests.
        val repository = ingestInto(
            HazardFixtures.DATABASE_NAME,
            listOf(
                HazardFixtures.mutation("evt-t-0001", segmentId = 801, reason = HazardReason.BLOCKED),
                HazardFixtures.mutation(
                    "evt-t-0002", segmentId = 802,
                    observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION),
                ),
                HazardFixtures.mutation(
                    "evt-t-0003", segmentId = 802, deviceId = "device-b-0002",
                    observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.NO_VISIBLE_WATER, PassabilityStatus.PASSABLE_REPORTED),
                ),
            ),
        )

        for ((segment, state) in repository.observationStates.value) {
            assertEquals("$segment has a clearance, but nothing can create one", 0, state.clearances.size)
            assertEquals("$segment resolved something with no clearance in the log", 0, state.resolvedClosures.size)
        }
        assertTrue(
            "the exclusion still comes from the report, as it always did",
            (repository.state.value as HazardState.Ready).snapshot.isBlockedSegment(801),
        )
    }

    // ------------------------------------- acceptance: the axes decide exclusion

    @Test
    fun aDepthOnlyReportIsNotExcludedWhileAnImpassableReportIs() = runBlocking {
        val repository = ingestInto(
            HazardFixtures.DATABASE_NAME,
            listOf(
                HazardFixtures.mutation(
                    "evt-depth-only", segmentId = 301, reason = HazardReason.WATERLOGGED,
                    observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.UNKNOWN),
                ),
                HazardFixtures.mutation(
                    "evt-impassable", segmentId = 302, reason = HazardReason.BLOCKED,
                    observation = HazardFixtures.observation(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE),
                ),
            ),
        )
        HazardFixtures.awaitObservedSegmentCount(repository, 2)

        val depthOnly = HazardFixtures.awaitObservedState(repository, 301)
        val impassable = HazardFixtures.awaitObservedState(repository, 302)

        assertFalse("a depth-only report must not close a road", depthOnly.excludesTraversal)
        assertEquals(WaterLevelCategory.KNEE, depthOnly.worstWaterLevel)
        assertEquals(PassabilityStatus.UNKNOWN, depthOnly.passability)

        assertTrue("an impassable verdict must close the road", impassable.excludesTraversal)
        assertEquals(PassabilityStatus.IMPASSABLE, impassable.passability)
        assertEquals("no depth may be invented for a verdict-only report", WaterLevelCategory.UNKNOWN, impassable.worstWaterLevel)

        val snapshot = (repository.state.value as HazardState.Ready).snapshot
        assertFalse("301 must not be in the blocked set", snapshot.isBlockedSegment(301))
        assertTrue("302 must be in the blocked set", snapshot.isBlockedSegment(302))
        assertEquals(setOf(302), snapshot.blockedSegments.map { it.value }.toSet())
    }

    @Test
    fun aBlockedSegmentSurvivesRestartThroughTheAggregation() = runBlocking {
        val handle = open(HazardFixtures.DATABASE_NAME)
        val repository = HazardFixtures.repository(handle, newScope(), eventIds = eventIds)
        repository.initialize(HazardFixtures.contextAlpha)
        assertTrue(
            repository.ingestRemote(legacyBlock("evt-c-0001", 401)) is MutationResult.Accepted,
        )
        val before = HazardFixtures.awaitObservedState(repository, 401)

        // Restart: new handle, new scope, same file.
        handle.close()
        val reopened = open(HazardFixtures.DATABASE_NAME)
        val relaunched = HazardFixtures.repository(reopened, newScope(), eventIds = eventIds)
        relaunched.initialize(HazardFixtures.contextAlpha)
        HazardFixtures.awaitReady(relaunched)

        assertEquals("the derived state must be rebuilt identically", before, relaunched.observationStates.value.getValue(SegmentId(401)))
        assertTrue(relaunched.observationStates.value.getValue(SegmentId(401)).excludesTraversal)
    }

    // --------------------------------------------------------- revision semantics

    @Test
    fun aNonExcludingReportChangesTheDerivedStateWithoutBumpingTheRevision() = runBlocking {
        val repository = ingestInto(HazardFixtures.DATABASE_NAME, emptyList())
        assertEquals(0L, (repository.state.value as HazardState.Ready).snapshot.revision)

        val result = repository.ingestRemote(
            HazardFixtures.mutation(
                "evt-d-0001", segmentId = 501,
                observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION),
            ),
        )
        assertTrue("expected Accepted but was $result", result is MutationResult.Accepted)
        assertEquals("a non-excluding report changes no routing state", false, (result as MutationResult.Accepted).effectiveStateChanged)

        val state = HazardFixtures.awaitObservedState(repository, 501)
        assertEquals(PassabilityStatus.CAUTION, state.passability)
        assertEquals(WaterLevelCategory.KNEE, state.worstWaterLevel)
        assertEquals(1, state.supportCount)

        val snapshot = (repository.state.value as HazardState.Ready).snapshot
        assertTrue("no route may be affected by a non-excluding report", snapshot.blockedSegments.isEmpty())
        assertEquals("the revision must not move", 0L, snapshot.revision)
    }

    @Test
    fun anExtraReportOnAnAlreadyClosedSegmentKeepsTheRevisionStill() = runBlocking {
        val repository = ingestInto(HazardFixtures.DATABASE_NAME, listOf(legacyBlock("evt-e-0001", 601)))
        val firstRevision = (repository.state.value as HazardState.Ready).snapshot.revision

        val second = repository.ingestRemote(legacyBlock("evt-e-0002", 601))
        assertTrue("expected an accepted no-op, was $second", second is MutationResult.Accepted)
        assertFalse("the effective set did not change", (second as MutationResult.Accepted).effectiveStateChanged)

        val state = HazardFixtures.awaitObservedState(repository, 601) { it.reports.size == 2 }
        assertEquals(firstRevision, (repository.state.value as HazardState.Ready).snapshot.revision)
        assertEquals(2, state.reports.size)
        assertEquals("both reports came from the same device id", 1, state.supportCount)
    }

    // ------------------------------------------------------------- support

    @Test
    fun supportCountsDistinctDeviceIdsFromPersistedRows() = runBlocking {
        val repository = ingestInto(
            HazardFixtures.DATABASE_NAME,
            listOf(
                legacyBlock("evt-f-0001", 701),
                legacyBlock("evt-f-0002", 701),
                legacyBlock("evt-f-0003", 701, deviceId = "device-b-0002"),
                legacyBlock("evt-f-0004", 701, deviceId = "device-c-0003"),
            ),
        )
        val state = HazardFixtures.awaitObservedState(repository, 701) { it.reports.size == 4 }

        assertEquals(4, state.reports.size)
        assertEquals("four reports from three device ids", 3, state.supportCount)
        assertEquals(
            listOf("device-b-0002", "device-c-0003", "device-fixed-0001"),
            state.reportingDeviceIds.map { it.value },
        )
    }

    // -------------------------------------------------- unreadable rows

    @Test
    fun aRowWithAReasonThisBuildCannotReadStillExcludesAndIsVisibleAsUnreadable() = runBlocking {
        // A newer build's reason code, written straight into the table.
        val handle = open(HazardFixtures.DATABASE_NAME)
        handle.hazardDao().insert(
            HazardEventEntity(
                eventId = "evt-unreadable-0001",
                datasetHash = HazardFixtures.DATASET_HASH,
                incidentId = HazardFixtures.INCIDENT,
                segmentId = 801L,
                originDeviceId = "device-b-0002",
                reasonCode = "FLOODED_DEEPER",
                occurredAtEpochMillis = HazardFixtures.FIXED_CLOCK,
                receivedAtEpochMillis = HazardFixtures.FIXED_CLOCK,
            ),
        )
        val repository = HazardFixtures.repository(handle, newScope(), eventIds = eventIds)
        repository.initialize(HazardFixtures.contextAlpha)
        val state = HazardFixtures.awaitObservedState(repository, 801)

        assertTrue("ADR 0004 §2: an unrecognised reason still fails closed", state.excludesTraversal)
        assertTrue(state.hasUnreadableReports)
        assertEquals(PassabilityStatus.IMPASSABLE, state.passability)
        assertEquals(WaterLevelCategory.UNKNOWN, state.worstWaterLevel)
        assertNull("no axis may be invented", state.reports.single().observation)
        assertEquals(1, state.supportCount)
        assertTrue((repository.state.value as HazardState.Ready).snapshot.isBlockedSegment(801))
    }

    @Test
    fun aLegacyRowDerivesTheV1StateExactly() = runBlocking {
        val repository = ingestInto(HazardFixtures.DATABASE_NAME, listOf(legacyBlock("evt-g-0001", 901)))
        val state = HazardFixtures.awaitObservedState(repository, 901)

        assertEquals(1, state.reports.size)
        assertEquals(PassabilityStatus.IMPASSABLE, state.passability)
        assertEquals(WaterLevelCategory.UNKNOWN, state.worstWaterLevel)
        assertTrue(state.excludesTraversal)
        assertFalse(state.hasUnreadableReports)
        assertEquals(HazardReason.BLOCKED, state.reports.single().observation?.reason)
        assertEquals(1, state.supportCount)
        assertTrue((repository.state.value as HazardState.Ready).snapshot.isBlockedSegment(901))
    }

    // ---------------------------------------------------------------- helpers

    /** A v1-shaped block: reason only, so the observation is the legacy interpretation. */
    private fun legacyBlock(
        eventId: String,
        segmentId: Int,
        deviceId: String = "device-fixed-0001",
    ) = HazardFixtures.mutation(eventId, segmentId, HazardReason.BLOCKED, deviceId = deviceId)

    /**
     * Opens a database, builds a repository, initializes it, and commits [events] in the
     * order given.
     */
    private suspend fun ingestInto(
        databaseName: String,
        events: List<com.resqroute.domain.hazard.HazardMutation>,
    ): RoomHazardRepository {
        val repository = HazardFixtures.repository(open(databaseName), newScope(), eventIds = eventIds)
        repository.initialize(HazardFixtures.contextAlpha)
        HazardFixtures.awaitReady(repository)
        for (event in events) {
            val result = repository.ingestRemote(event)
            assertTrue("ingesting ${event.eventId.value} returned $result", result is MutationResult.Accepted)
        }
        val expectedSegments = events.map { it.segmentId }.distinct().size
        if (expectedSegments > 0) {
            // Wait for the whole set, not merely for the first segment to appear: reading
            // the flow mid-emission would compare a partial derivation.
            HazardFixtures.awaitDerivedState(repository) { states ->
                states.size == expectedSegments &&
                    states.values.sumOf { state -> state.reports.size } == events.size
            }
        }
        return repository
    }

    private fun open(databaseName: String): ResQRouteDatabase =
        HazardFixtures.openDatabase(context, databaseName).also { databases += it }

    private fun newScope(): CoroutineScope =
        CoroutineScope(Dispatchers.Unconfined + SupervisorJob()).also { scopes += it }

    private suspend fun RoomHazardRepository.databaseEventCount(): Int {
        var count = 0
        persistedEvents(HazardFixtures.contextAlpha).collect { count++ }
        return count
    }

    private companion object {
        const val SECOND_DATABASE_NAME = "resqroute-hazard-order-test.db"
    }
}
