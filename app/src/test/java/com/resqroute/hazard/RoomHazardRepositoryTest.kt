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
import com.resqroute.domain.hazard.RoadObservation
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 6 acceptance criteria against a real Room database (Robolectric).
 *
 * Covers persistence/restore, deduplication, conflict rejection, provenance,
 * revision semantics, validation, failure honesty and single-ingress behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomHazardRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: ResQRouteDatabase
    private lateinit var scope: CoroutineScope
    private val eventIds = AtomicInteger(0)

    @Before
    fun setUp() {
        context = HazardFixtures.context()
        context.deleteDatabase(HazardFixtures.DATABASE_NAME)
        database = HazardFixtures.openDatabase(context)
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    }

    @After
    fun tearDown() {
        runCatching { database.close() }
        context.deleteDatabase(HazardFixtures.DATABASE_NAME)
        scope.cancel()
    }

    private fun repository(
        db: ResQRouteDatabase = database,
        scopeForRepo: CoroutineScope = scope,
        segmentCount: Int? = HazardFixtures.SEGMENT_COUNT,
        deviceIdGenerator: () -> String = { "device-fixed-0001" },
    ): RoomHazardRepository = HazardFixtures.repository(db, scopeForRepo, segmentCount, deviceIdGenerator, eventIds)

    // ------------------------------------------------------------------ AC1

    @Test
    fun stateIsRestoringUntilInitialized() = runBlocking {
        val repository = repository()
        assertEquals(HazardState.Restoring, repository.state.value)
    }

    @Test
    fun emptyRestoredSetPublishesReadySoRoutingCanRun() = runBlocking {
        val repository = repository()
        repository.initialize(HazardFixtures.contextAlpha)

        val ready = HazardFixtures.awaitReady(repository)
        assertTrue(ready.snapshot.blockedSegments.isEmpty())
        assertEquals(0L, ready.snapshot.revision)
        assertEquals(HazardFixtures.contextAlpha, ready.snapshot.context)
    }

    @Test
    fun localBlockPersistsAndRestoresAcrossRestart() = runBlocking {
        val repository = repository()
        repository.initialize(HazardFixtures.contextAlpha)

        val result = repository.blockLocal(SegmentId(4242), HazardReason.WATERLOGGED)
        assertTrue("expected Accepted but was $result", result is MutationResult.Accepted)
        HazardFixtures.awaitBlocked(repository, 4242)

        // Simulate a process restart: close the database and open it again.
        database.close()
        database = HazardFixtures.openDatabase(context)
        val reopened = repository(database)
        reopened.initialize(HazardFixtures.contextAlpha)

        val restored = HazardFixtures.awaitBlocked(reopened, 4242)
        assertTrue(restored.snapshot.isBlockedSegment(4242))
        assertEquals(1, restored.snapshot.blockedSegments.size)
    }

    // ------------------------------------------------------- AC2, AC7, AC8

    @Test
    fun duplicateEventIsIdempotentAndDoesNotBumpRevision() = runBlocking {
        val repository = repository()
        repository.initialize(HazardFixtures.contextAlpha)

        val mutation = HazardFixtures.mutation("evt-duplicate-1", segmentId = 7)
        val first = repository.ingestRemote(mutation)
        assertTrue(first is MutationResult.Accepted)
        assertTrue((first as MutationResult.Accepted).effectiveStateChanged)
        val afterFirst = HazardFixtures.awaitBlocked(repository, 7)

        val second = repository.ingestRemote(mutation)
        assertEquals(MutationResult.Duplicate, second)

        val state = repository.state.value as HazardState.Ready
        assertEquals(afterFirst.snapshot.revision, state.snapshot.revision)
        assertEquals(1, database.hazardDao().eventCount(HazardFixtures.DATASET_HASH, HazardFixtures.INCIDENT))
    }

    @Test
    fun sameEventIdWithDifferentContentIsRejectedAndNotOverwritten() = runBlocking {
        val repository = repository()
        repository.initialize(HazardFixtures.contextAlpha)

        val original = HazardFixtures.mutation("evt-conflict-1", segmentId = 11, reason = HazardReason.WATERLOGGED)
        assertTrue(repository.ingestRemote(original) is MutationResult.Accepted)
        HazardFixtures.awaitBlocked(repository, 11)

        val differentReason = HazardFixtures.mutation("evt-conflict-1", segmentId = 11, reason = HazardReason.BLOCKED)
        val result = repository.ingestRemote(differentReason)
        assertTrue("expected Rejected but was $result", result is MutationResult.Rejected)

        // The original row is untouched and no second row was written.
        val stored = database.hazardDao().findEvent("evt-conflict-1")
        assertNotNull(stored)
        assertEquals(HazardReason.WATERLOGGED.code, stored!!.reasonCode)
        assertEquals(1, database.hazardDao().eventCount(HazardFixtures.DATASET_HASH, HazardFixtures.INCIDENT))
    }

    @Test
    fun newEventForAlreadyBlockedSegmentKeepsProvenanceWithoutChangingState() = runBlocking {
        val repository = repository()
        repository.initialize(HazardFixtures.contextAlpha)

        assertTrue(repository.ingestRemote(HazardFixtures.mutation("evt-first-1", segmentId = 21)) is MutationResult.Accepted)
        val afterFirst = HazardFixtures.awaitBlocked(repository, 21)

        val second = repository.ingestRemote(
            HazardFixtures.mutation("evt-second-2", segmentId = 21, reason = HazardReason.BLOCKED),
        )
        assertTrue(second is MutationResult.Accepted)
        second as MutationResult.Accepted
        assertFalse("the effective set must not change", second.effectiveStateChanged)

        val state = repository.state.value as HazardState.Ready
        assertEquals(afterFirst.snapshot.revision, state.snapshot.revision)
        assertEquals(1, state.snapshot.blockedSegments.size)
        // Both reports are preserved as provenance.
        assertEquals(2, database.hazardDao().eventCount(HazardFixtures.DATASET_HASH, HazardFixtures.INCIDENT))
    }

    @Test
    fun bothReasonCodesPersistRestoreAndProduceTheSameEffectiveBlock() = runBlocking {
        val repository = repository()
        repository.initialize(HazardFixtures.contextAlpha)

        assertTrue(repository.blockLocal(SegmentId(31), HazardReason.WATERLOGGED) is MutationResult.Accepted)
        HazardFixtures.awaitBlocked(repository, 31)
        assertTrue(repository.blockLocal(SegmentId(32), HazardReason.BLOCKED) is MutationResult.Accepted)
        HazardFixtures.awaitBlocked(repository, 32)

        database.close()
        database = HazardFixtures.openDatabase(context)
        val reopened = repository(database)
        reopened.initialize(HazardFixtures.contextAlpha)

        val restored = HazardFixtures.awaitBlocked(reopened, 31)
        assertTrue(restored.snapshot.isBlockedSegment(31))
        assertTrue(restored.snapshot.isBlockedSegment(32))

        val events = reopened.persistedEvents(HazardFixtures.contextAlpha).toList()
        val reasons = events.map { it.segmentId.value to it.reason }.toMap()
        assertEquals(HazardReason.WATERLOGGED, reasons[31])
        assertEquals(HazardReason.BLOCKED, reasons[32])
    }

    // ------------------------------------------------------------------ AC4

    @Test
    fun wrongDatasetOrIncidentIsRejectedAndDoesNotContaminateHazards() = runBlocking {
        val repository = repository()
        repository.initialize(HazardFixtures.contextAlpha)

        val wrongDataset = repository.ingestRemote(
            HazardFixtures.mutation("evt-wrong-ds", segmentId = 5, context = HazardFixtures.contextOtherDataset),
        )
        assertTrue(wrongDataset is MutationResult.Rejected)
        assertTrue((wrongDataset as MutationResult.Rejected).reason.contains("dataset"))

        val wrongIncident = repository.ingestRemote(
            HazardFixtures.mutation("evt-wrong-inc", segmentId = 5, context = HazardFixtures.contextOtherIncident),
        )
        assertTrue(wrongIncident is MutationResult.Rejected)
        assertTrue((wrongIncident as MutationResult.Rejected).reason.contains("incident"))

        val state = HazardFixtures.awaitReady(repository)
        assertTrue(state.snapshot.blockedSegments.isEmpty())
        assertEquals(0, database.hazardDao().eventCount(HazardFixtures.DATASET_HASH, HazardFixtures.INCIDENT))
    }

    @Test
    fun unknownOrInvalidSegmentIsRejected() = runBlocking {
        val repository = repository()
        repository.initialize(HazardFixtures.contextAlpha)

        val outOfRange = repository.blockLocal(
            SegmentId(HazardFixtures.SEGMENT_COUNT),
            HazardReason.BLOCKED,
        )
        assertTrue(outOfRange is MutationResult.Rejected)
        assertTrue((outOfRange as MutationResult.Rejected).reason.contains("unknown segment"))

        val negative = repository.blockLocal(SegmentId(-1), HazardReason.BLOCKED)
        assertTrue(negative is MutationResult.Rejected)
    }

    @Test
    fun missingSegmentCountMeansDatasetNotLoadedAndNothingIsWritten() = runBlocking {
        val repository = repository(segmentCount = null)
        repository.initialize(HazardFixtures.contextAlpha)

        val result = repository.blockLocal(SegmentId(9), HazardReason.BLOCKED)
        assertTrue(result is MutationResult.Rejected)
        assertTrue((result as MutationResult.Rejected).reason.contains("not loaded"))
        assertEquals(0, database.hazardDao().eventCount(HazardFixtures.DATASET_HASH, HazardFixtures.INCIDENT))
    }

    @Test
    fun invalidIdentifiersAreRejected() = runBlocking {
        val repository = repository()
        repository.initialize(HazardFixtures.contextAlpha)

        val shortId = repository.ingestRemote(HazardFixtures.mutation("short", segmentId = 5))
        assertTrue(shortId is MutationResult.Rejected)

        val badChars = repository.ingestRemote(HazardFixtures.mutation("bad id with spaces", segmentId = 5))
        assertTrue(badChars is MutationResult.Rejected)

        val blankDevice = repository.ingestRemote(
            HazardFixtures.mutation("evt-device-bad", segmentId = 5, deviceId = "x"),
        )
        assertTrue(blankDevice is MutationResult.Rejected)
    }

    // ------------------------------------------------------------------ AC6

    @Test
    fun revisionBumpsOnlyWhenTheEffectiveSetChanges() = runBlocking {
        val repository = repository()
        repository.initialize(HazardFixtures.contextAlpha)
        assertEquals(0L, HazardFixtures.awaitReady(repository).snapshot.revision)

        assertTrue(repository.blockLocal(SegmentId(101), HazardReason.BLOCKED) is MutationResult.Accepted)
        val afterFirst = HazardFixtures.awaitBlocked(repository, 101)
        assertEquals(1L, afterFirst.snapshot.revision)

        // A duplicate does not bump the revision.
        assertTrue(repository.blockLocal(SegmentId(101), HazardReason.BLOCKED) is MutationResult.Accepted)
        Thread.sleep(50)
        assertEquals(1L, (repository.state.value as HazardState.Ready).snapshot.revision)

        // A second distinct segment does bump it.
        assertTrue(repository.blockLocal(SegmentId(102), HazardReason.WATERLOGGED) is MutationResult.Accepted)
        val afterSecond = HazardFixtures.awaitBlocked(repository, 102)
        assertEquals(2L, afterSecond.snapshot.revision)
    }

    // ------------------------------------------------------------------ AC3

    @Test
    fun writeFailureIsReportedAndNothingIsAccepted() = runBlocking {
        val isolatedScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val repository = repository(scopeForRepo = isolatedScope)
            repository.initialize(HazardFixtures.contextAlpha)
            HazardFixtures.awaitReady(repository)

            // Stop observation, then make the database unusable so the commit fails.
            isolatedScope.cancel()
            database.close()

            val result = repository.blockLocal(SegmentId(77), HazardReason.BLOCKED)
            assertTrue("a failed write must not be Accepted but was $result", result is MutationResult.PersistenceFailure)
            assertTrue((result as MutationResult.PersistenceFailure).message.isNotBlank())
        } finally {
            isolatedScope.cancel()
        }
    }

    // ------------------------------------------------------ identity, replay

    // The repository publishes on Room's invalidation executor, so these tests
    // use real dispatchers and real time (runBlocking), not runTest's virtual clock.

    @Test
    fun deviceIdentityPersistsAcrossRepositoryInstances() = runBlocking {
        val first = repository(deviceIdGenerator = { "device-aaaa-0001" })
        first.initialize(HazardFixtures.contextAlpha)
        assertEquals("device-aaaa-0001", first.deviceId?.value)

        val second = repository(deviceIdGenerator = { "device-bbbb-0002" })
        second.initialize(HazardFixtures.contextAlpha)
        // The stored identity wins; the generator is not consulted again.
        assertEquals("device-aaaa-0001", second.deviceId?.value)

        val storedRows = database.deviceIdentityDao().get()
        assertNotNull(storedRows)
        assertEquals("device-aaaa-0001", storedRows!!.deviceId)
    }

    @Test
    fun persistedEventsExposeCommittedReportsForReplay() = runBlocking {
        val repository = repository()
        repository.initialize(HazardFixtures.contextAlpha)

        assertTrue(repository.blockLocal(SegmentId(201), HazardReason.WATERLOGGED) is MutationResult.Accepted)
        HazardFixtures.awaitBlocked(repository, 201)
        assertTrue(repository.blockLocal(SegmentId(202), HazardReason.BLOCKED) is MutationResult.Accepted)
        HazardFixtures.awaitBlocked(repository, 202)

        val events = repository.persistedEvents(HazardFixtures.contextAlpha).toList()
        assertEquals(2, events.size)
        assertEquals(setOf(201, 202), events.map { it.segmentId.value }.toSet())
        assertTrue(events.all { it.originDeviceId.value == repository.deviceId?.value })

        // A different context has no events.
        assertTrue(repository.persistedEvents(HazardFixtures.contextOtherIncident).toList().isEmpty())
    }

    @Test
    fun localMarksAndRemoteEventsShareOneCommitPath() = runBlocking {
        val repository = repository()
        repository.initialize(HazardFixtures.contextAlpha)

        // A local mark generates its own identity...
        val local = repository.blockLocal(SegmentId(301), HazardReason.WATERLOGGED)
        assertTrue(local is MutationResult.Accepted)
        val localDevice = (local as MutationResult.Accepted).event.originDeviceId.value

        // ...and a remote event reuses the caller's identity, through the same path.
        val remote = repository.ingestRemote(
            HazardFixtures.mutation("evt-remote-peer", segmentId = 301, deviceId = "peer-device-0009"),
        )
        assertTrue(remote is MutationResult.Accepted)
        assertFalse((remote as MutationResult.Accepted).effectiveStateChanged)

        HazardFixtures.awaitBlocked(repository, 301)
        assertEquals("device-fixed-0001", localDevice)

        val stored = database.hazardDao().events(HazardFixtures.DATASET_HASH, HazardFixtures.INCIDENT)
        assertEquals(2, stored.size)
        assertEquals(setOf("device-fixed-0001", "peer-device-0009"), stored.map { it.originDeviceId }.toSet())
    }

    // ------------------------------------------- observation axes (v2, XPH-68)

    @Test
    fun observationAxesPersistAndAreReadBackUnchanged() = runBlocking {
        val repository = repository()
        repository.initialize(HazardFixtures.contextAlpha)

        val stated = HazardFixtures.observation(
            reason = HazardReason.WATERLOGGED,
            waterLevel = WaterLevelCategory.KNEE,
            passability = PassabilityStatus.CAUTION,
        )
        val result = repository.ingestRemote(
            HazardFixtures.mutation("evt-axes-knee-0001", segmentId = 501, observation = stated),
        )
        assertTrue("expected Accepted but was $result", result is MutationResult.Accepted)
        // Deliberately no blocked-set assertion on this row. Whether a CAUTION observation
        // excludes is the derivation contract, and it belongs to XPH-69: asserting it here
        // would freeze today's temporary over-exclusion of v2 axes into a contract test.
        HazardFixtures.awaitReady(repository)

        // Stored as the pinned codes, with the reserved columns left alone.
        val row = database.hazardDao().findEvent("evt-axes-knee-0001")
        assertNotNull(row)
        assertEquals(WaterLevelCategory.KNEE.code, row!!.levelCode)
        assertEquals(PassabilityStatus.CAUTION.code, row.passabilityCode)
        assertNull("sourceCode is reserved for XPH-73", row.sourceCode)
        assertNull("accuracyMeters is reserved for XPH-90", row.accuracyMeters)

        // And read back as the same observation, across a restart.
        database.close()
        database = HazardFixtures.openDatabase(context)
        val reopened = repository(database)
        reopened.initialize(HazardFixtures.contextAlpha)

        val replayed = reopened.persistedEvents(HazardFixtures.contextAlpha).toList()
        assertEquals(1, replayed.size)
        assertEquals(stated, replayed.single().observation)
    }

    @Test
    fun aPlainBlockStoresNoAxesAndReadsBackAsTheV1Interpretation() = runBlocking {
        val repository = repository()
        repository.initialize(HazardFixtures.contextAlpha)

        assertTrue(repository.blockLocal(SegmentId(601), HazardReason.WATERLOGGED) is MutationResult.Accepted)
        HazardFixtures.awaitBlocked(repository, 601)

        val row = database.hazardDao().events(HazardFixtures.DATASET_HASH, HazardFixtures.INCIDENT).single()
        assertNull("a plain block states no water level", row.levelCode)
        assertNull("a plain block states no passability", row.passabilityCode)

        val replay = repository.persistedEvents(HazardFixtures.contextAlpha).toList().single()
        assertEquals(RoadObservation.fromLegacy(HazardReason.WATERLOGGED), replay.observation)
        assertTrue(replay.observation.excludesTraversal)
        assertEquals(WaterLevelCategory.UNKNOWN, replay.observation.waterLevel)
    }

    @Test
    fun anUnrecognisedStoredLevelBecomesUnknownAndKeepsTheStatedVerdict() = runBlocking {
        val repository = repository()
        repository.initialize(HazardFixtures.contextAlpha)
        insertRow(
            eventId = "evt-unknown-level-0001",
            segment = 701,
            reason = "WATERLOGGED",
            level = "SUPERDEEP",
            passability = PassabilityStatus.CAUTION.code,
        )

        val replay = repository.persistedEvents(HazardFixtures.contextAlpha).toList().single()
        assertEquals(WaterLevelCategory.UNKNOWN, replay.observation.waterLevel)
        assertEquals(PassabilityStatus.CAUTION, replay.observation.passability)
        assertFalse(replay.observation.excludesTraversal)
    }

    @Test
    fun anUnrecognisedStoredVerdictFailsClosed() = runBlocking {
        val repository = repository()
        repository.initialize(HazardFixtures.contextAlpha)
        insertRow(
            eventId = "evt-unknown-verdict-0001",
            segment = 702,
            reason = "BLOCKED",
            level = WaterLevelCategory.KNEE.code,
            passability = "DESTROYED",
        )

        val replay = repository.persistedEvents(HazardFixtures.contextAlpha).toList().single()
        assertEquals(PassabilityStatus.IMPASSABLE, replay.observation.passability)
        assertTrue("an unparseable verdict must fail closed", replay.observation.excludesTraversal)
    }

    @Test
    fun aStoredCombinationThatIsNotRepresentableFallsBackToTheLegacyInterpretation() = runBlocking {
        val repository = repository()
        repository.initialize(HazardFixtures.contextAlpha)
        // States nothing on either axis, which the domain rejects (rule R1).
        insertRow(
            eventId = "evt-states-nothing-0001",
            segment = 703,
            reason = "WATERLOGGED",
            level = null,
            passability = PassabilityStatus.UNKNOWN.code,
        )

        val replay = repository.persistedEvents(HazardFixtures.contextAlpha).toList().single()
        assertEquals(RoadObservation.fromLegacy(HazardReason.WATERLOGGED), replay.observation)
        assertTrue(replay.observation.excludesTraversal)
    }

    @Test
    fun sameEventIdWithDifferentAxesIsRejectedAndNotOverwritten() = runBlocking {
        val repository = repository()
        repository.initialize(HazardFixtures.contextAlpha)

        val original = HazardFixtures.mutation(
            "evt-axes-conflict-0001",
            segmentId = 801,
            observation = HazardFixtures.observation(
                HazardReason.WATERLOGGED,
                WaterLevelCategory.KNEE,
                PassabilityStatus.CAUTION,
            ),
        )
        assertTrue(repository.ingestRemote(original) is MutationResult.Accepted)

        val conflicting = HazardFixtures.mutation(
            "evt-axes-conflict-0001",
            segmentId = 801,
            observation = HazardFixtures.observation(
                HazardReason.WATERLOGGED,
                WaterLevelCategory.WAIST,
                PassabilityStatus.AVOID,
            ),
        )
        val result = repository.ingestRemote(conflicting)
        assertTrue("expected Rejected but was $result", result is MutationResult.Rejected)

        val row = database.hazardDao().findEvent("evt-axes-conflict-0001")
        assertEquals("the original row must be untouched", WaterLevelCategory.KNEE.code, row!!.levelCode)
        assertEquals(1, database.hazardDao().eventCount(HazardFixtures.DATASET_HASH, HazardFixtures.INCIDENT))
    }

    @Test
    fun aRelayedCopyOfAV1ShapedRowStillDeduplicates() = runBlocking {
        val repository = repository()
        repository.initialize(HazardFixtures.contextAlpha)

        // Exactly what the v1 -> v2 migration leaves behind: no axes stored.
        insertRow(
            eventId = "evt-v1-shape-0001",
            segment = 901,
            reason = "BLOCKED",
            level = null,
            passability = null,
            deviceId = "device-fixed-0001",
        )

        // The same event arriving from a peer carries the legacy interpretation, and
        // resolving the stored row produces the same one - so it is a duplicate.
        val result = repository.ingestRemote(
            HazardFixtures.mutation("evt-v1-shape-0001", segmentId = 901, reason = HazardReason.BLOCKED),
        )
        assertEquals(MutationResult.Duplicate, result)
    }

    /** Writes a row directly, standing in for data written by another build. */
    private suspend fun insertRow(
        eventId: String,
        segment: Long,
        reason: String,
        level: String?,
        passability: String?,
        deviceId: String = "peer-device-0009",
    ) {
        database.hazardDao().insert(
            HazardEventEntity(
                eventId = eventId,
                datasetHash = HazardFixtures.DATASET_HASH,
                incidentId = HazardFixtures.INCIDENT,
                segmentId = segment,
                originDeviceId = deviceId,
                reasonCode = reason,
                occurredAtEpochMillis = HazardFixtures.FIXED_CLOCK,
                receivedAtEpochMillis = HazardFixtures.FIXED_CLOCK,
                levelCode = level,
                passabilityCode = passability,
            ),
        )
    }
}
