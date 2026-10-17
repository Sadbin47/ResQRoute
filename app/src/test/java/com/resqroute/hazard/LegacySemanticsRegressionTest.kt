package com.resqroute.hazard

import android.app.Application
import android.content.Context
import com.resqroute.data.local.ResQRouteDatabase
import com.resqroute.data.repository.RoomHazardRepository
import com.resqroute.domain.hazard.HazardSnapshot
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The v1 blocked-road semantics contract (XPH-87).
 *
 * Phase 13.1 changed what the hazard table *is*. This class pins what it *means*: for any
 * event set a v1 build could have produced, the effective excluded set must be identical
 * after the migration, after a cold restart, after a full replay, and at the derivation the
 * router actually consults — **for every segment in the graph**, not just the reported ones.
 *
 * The expectation is never hardcoded. Every test asks v1 for its own answer
 * ([LegacyDatabaseSeed.readV1BlockedSet], running v1's exact `SELECT DISTINCT segmentId`
 * against the unmigrated file) and then holds the migrated app to it.
 *
 * The last property is the one that matters for the phases that follow: a v1 row must resolve
 * to an observation that **excludes traversal**. Without it, the day XPH-69 teaches the
 * derivation to consult the axes, every v1 report would silently stop blocking and a flooded
 * road would look open again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LegacySemanticsRegressionTest {

    private lateinit var context: Context

    private val openDatabases = mutableListOf<ResQRouteDatabase>()
    private val openScopes = mutableListOf<CoroutineScope>()
    private val eventIds = AtomicInteger(0)

    @Before
    fun setUp() {
        context = HazardFixtures.context()
        context.deleteDatabase(HazardFixtures.DATABASE_NAME)
        context.deleteDatabase(PEER_DATABASE_NAME)
    }

    @After
    fun tearDown() {
        openDatabases.forEach { runCatching { it.close() } }
        openScopes.forEach { it.cancel() }
        context.deleteDatabase(HazardFixtures.DATABASE_NAME)
        context.deleteDatabase(PEER_DATABASE_NAME)
    }

    // ------------------------------------------------------------------ fixture

    /**
     * A mixed v1 fixture, deliberately awkward:
     *
     *  * **both** legacy reasons, so "reason is provenance only" is exercised;
     *  * the same segment reported twice, by one device and by two devices, so provenance
     *    accumulates without changing the set;
     *  * an exact `(segment, reason)` repeat, so the DISTINCT projection has work to do;
     *  * one row belonging to a different dataset and one to a different incident, so the
     *    filter is exercised rather than assumed.
     */
    private val v1Rows = listOf(
        LegacyDatabaseSeed.Row("evt-v1-0001", 1001, "BLOCKED", deviceId = "device-a-0001"),
        LegacyDatabaseSeed.Row("evt-v1-0002", 1002, "WATERLOGGED", deviceId = "device-a-0001"),
        LegacyDatabaseSeed.Row("evt-v1-0003", 1003, "WATERLOGGED", deviceId = "device-b-0002"),
        LegacyDatabaseSeed.Row("evt-v1-0004", 1003, "BLOCKED", deviceId = "device-b-0002"),
        LegacyDatabaseSeed.Row("evt-v1-0005", 2001, "WATERLOGGED", deviceId = "device-c-0003"),
        LegacyDatabaseSeed.Row("evt-v1-0006", 2001, "WATERLOGGED", deviceId = "device-d-0004"),
        LegacyDatabaseSeed.Row("evt-v1-0007", 2002, "BLOCKED", deviceId = "device-d-0004"),
        LegacyDatabaseSeed.Row("evt-v1-0008", 2002, "WATERLOGGED", deviceId = "device-d-0004"),
        LegacyDatabaseSeed.Row(
            "evt-v1-9001",
            3001,
            "BLOCKED",
            deviceId = "device-e-0005",
            datasetHash = HazardFixtures.OTHER_DATASET_HASH,
        ),
        LegacyDatabaseSeed.Row(
            "evt-v1-9002",
            3002,
            "BLOCKED",
            deviceId = "device-e-0005",
            incidentId = HazardFixtures.OTHER_INCIDENT,
        ),
    )

    /** Rows for the active context, and the distinct segments v1 excluded from them. */
    private val activeRowCount = 8
    private val expectedV1Set = setOf(1001L, 1002L, 1003L, 2001L, 2002L)

    // -------------------------------------------------------------------- tests

    @Test
    fun theExcludedSetIsIdenticalBeforeAndAfterMigration() = runBlocking {
        LegacyDatabaseSeed.write(context, HazardFixtures.DATABASE_NAME, v1Rows)
        val answeredByV1 = v1Answer()

        // Sanity: the fixture is actually mixed, and v1's own answer matches the expectation,
        // so a later equality cannot pass vacuously.
        assertEquals(expectedV1Set, answeredByV1)
        val reasonsBefore = LegacyDatabaseSeed.readV1Reasons(
            context = context,
            databaseName = HazardFixtures.DATABASE_NAME,
            datasetHash = HazardFixtures.DATASET_HASH,
            incidentId = HazardFixtures.INCIDENT,
        )
        assertEquals(setOf("BLOCKED", "WATERLOGGED"), reasonsBefore)

        val handle = open()
        val repository = repositoryOn(handle, newScope())
        repository.initialize(HazardFixtures.contextAlpha)
        val migrated = HazardFixtures.awaitReady(repository).snapshot

        assertEquals("the migration changed which segments are excluded", answeredByV1, migrated.stableIds())
        assertEquals("the migration changed how many reports exist", activeRowCount, handle.hazardDao().eventCount(HazardFixtures.DATASET_HASH, HazardFixtures.INCIDENT))
        assertEquals(2, handle.openHelper.writableDatabase.version)
    }

    @Test
    fun theRouterFacingBlockDecisionIsIdenticalForEverySegmentInTheGraph() = runBlocking {
        LegacyDatabaseSeed.write(context, HazardFixtures.DATABASE_NAME, v1Rows)
        val answeredByV1 = v1Answer()

        val handle = open()
        val repository = repositoryOn(handle, newScope())
        repository.initialize(HazardFixtures.contextAlpha)
        val snapshot = HazardFixtures.awaitReady(repository).snapshot

        // `isBlockedSegment` is the O(1) lookup A* runs on every expansion step. Sweep the
        // whole graph: the decision for each segment must be the one v1 would have made.
        var divergences = 0
        var blocked = 0
        for (index in 0 until HazardFixtures.SEGMENT_COUNT) {
            val v1SaysBlocked = index.toLong() in answeredByV1
            val nowSaysBlocked = snapshot.isBlockedSegment(index)
            if (v1SaysBlocked != nowSaysBlocked) divergences++
            if (nowSaysBlocked) blocked++
        }
        assertEquals("the block decision diverged for $divergences segments", 0, divergences)
        assertEquals(expectedV1Set.size, blocked)
    }

    @Test
    fun forceStopAndRelaunchPreserveTheExcludedSet() = runBlocking {
        LegacyDatabaseSeed.write(context, HazardFixtures.DATABASE_NAME, v1Rows)
        val answeredByV1 = v1Answer()

        // First launch: the upgrade happens here, once.
        val firstHandle = open()
        val firstScope = newScope()
        val firstLaunch = repositoryOn(firstHandle, firstScope)
        firstLaunch.initialize(HazardFixtures.contextAlpha)
        assertEquals(answeredByV1, HazardFixtures.awaitReady(firstLaunch).snapshot.stableIds())

        // Force-stop: the process dies, taking its database handle and its scope with it.
        firstHandle.close()
        firstScope.cancel()

        // Relaunch: the same file, already at v2, restored from scratch.
        val secondHandle = open()
        val secondLaunch = repositoryOn(secondHandle, newScope())
        secondLaunch.initialize(HazardFixtures.contextAlpha)
        val relaunched = HazardFixtures.awaitReady(secondLaunch).snapshot

        assertEquals("a relaunch changed the excluded set", answeredByV1, relaunched.stableIds())
        assertEquals("the revision must be rebuilt from scratch", 0L, relaunched.revision)
        assertEquals(HazardFixtures.contextAlpha, relaunched.context)
        assertEquals("the relaunch must not need another migration", 2, secondHandle.openHelper.writableDatabase.version)
        assertEquals(activeRowCount, secondHandle.hazardDao().eventCount(HazardFixtures.DATASET_HASH, HazardFixtures.INCIDENT))
    }

    @Test
    fun replayingTheFullLogIntoTheOriginChangesNothing() = runBlocking {
        LegacyDatabaseSeed.write(context, HazardFixtures.DATABASE_NAME, v1Rows)
        val handle = open()
        val repository = repositoryOn(handle, newScope())
        repository.initialize(HazardFixtures.contextAlpha)
        val before = HazardFixtures.awaitReady(repository).snapshot

        val log = repository.persistedEvents(HazardFixtures.contextAlpha).toList()
        assertEquals(activeRowCount, log.size)
        for (mutation in log) {
            val result = repository.ingestRemote(mutation)
            assertEquals(
                "replaying ${mutation.eventId.value} must be a no-op",
                MutationResult.Duplicate,
                result,
            )
        }

        val after = (repository.state.value as HazardState.Ready).snapshot
        assertEquals(before.blockedSegments, after.blockedSegments)
        assertEquals("an idempotent replay must not bump the revision", before.revision, after.revision)
        assertEquals(activeRowCount, handle.hazardDao().eventCount(HazardFixtures.DATASET_HASH, HazardFixtures.INCIDENT))
    }

    @Test
    fun replayingTheFullLogIntoAFreshPeerReproducesTheSameExcludedSet() = runBlocking {
        LegacyDatabaseSeed.write(context, HazardFixtures.DATABASE_NAME, v1Rows)
        val answeredByV1 = v1Answer()

        val originHandle = open()
        val origin = repositoryOn(originHandle, newScope())
        origin.initialize(HazardFixtures.contextAlpha)
        HazardFixtures.awaitReady(origin)
        val log = origin.persistedEvents(HazardFixtures.contextAlpha).toList()

        // A second installation that has never seen this incident.
        val peerHandle = open(PEER_DATABASE_NAME)
        val peer = repositoryOn(peerHandle, newScope())
        peer.initialize(HazardFixtures.contextAlpha)
        assertTrue(
            "the peer must start empty",
            HazardFixtures.awaitReady(peer).snapshot.blockedSegments.isEmpty(),
        )

        for (mutation in log) {
            val result = peer.ingestRemote(mutation)
            assertTrue(
                "replaying ${mutation.eventId.value} to a peer was $result",
                result is MutationResult.Accepted,
            )
        }

        assertEquals(answeredByV1, HazardFixtures.awaitReady(peer).snapshot.stableIds())

        // Replay must not invent observation data: a v1 row round-trips as a v1 row.
        val replayedRows = peerHandle.hazardDao().events(HazardFixtures.DATASET_HASH, HazardFixtures.INCIDENT)
        assertEquals(activeRowCount, replayedRows.size)
        for (row in replayedRows) {
            assertNull("replay fabricated a water level for ${row.eventId}", row.levelCode)
            assertNull("replay fabricated a passability for ${row.eventId}", row.passabilityCode)
        }
    }

    @Test
    fun noLegacyRowCanProduceAFinitePenalty() = runBlocking {
        LegacyDatabaseSeed.write(context, HazardFixtures.DATABASE_NAME, v1Rows)
        val handle = open()
        val repository = repositoryOn(handle, newScope())
        repository.initialize(HazardFixtures.contextAlpha)
        HazardFixtures.awaitReady(repository)

        val log = repository.persistedEvents(HazardFixtures.contextAlpha).toList()
        assertEquals(activeRowCount, log.size)

        val bySegment = mutableMapOf<Int, MutableList<RoadObservation>>()
        for (mutation in log) {
            val observation = mutation.observation
            // Every v1 row resolves to the v1 interpretation of its own reason...
            assertEquals(
                "a v1 row must keep the v1 meaning of its reason",
                RoadObservation.fromLegacy(mutation.reason),
                observation,
            )
            assertEquals(WaterLevelCategory.UNKNOWN, observation.waterLevel)
            assertEquals(PassabilityStatus.IMPASSABLE, observation.passability)
            // ...and that interpretation excludes. This is the property XPH-69 must preserve.
            assertTrue(
                "${mutation.eventId.value} must exclude traversal",
                observation.excludesTraversal,
            )
            bySegment.getOrPut(mutation.segmentId.value) { mutableListOf() } += observation
        }

        // A finite penalty is only ever reachable through a traversable observation, and a
        // v1-shaped log has none - so the penalty set for it is empty by construction.
        val traversable = bySegment.filterValues { observations ->
            observations.any { !it.excludesTraversal }
        }
        assertTrue("no legacy segment may be traversable, but found $traversable", traversable.isEmpty())
        assertEquals("the replayed log must cover exactly v1's segments", expectedV1Set, bySegment.keys.map { it.toLong() }.toSet())
    }

    @Test
    fun aReportWithAReasonThisBuildDoesNotKnowStillFailsClosed() = runBlocking {
        // A newer build's reason code reaching this one. v1 had no reason filter at all, so
        // such a row was excluded, and it must stay excluded: the derivation must never
        // narrow itself to the reasons this build happens to recognise.
        LegacyDatabaseSeed.write(
            context,
            HazardFixtures.DATABASE_NAME,
            listOf(
                LegacyDatabaseSeed.Row("evt-v1-0001", 1001, "BLOCKED"),
                LegacyDatabaseSeed.Row("evt-v1-0002", 1002, "FLOODED_DEEPER"),
            ),
        )

        val handle = open()
        val repository = repositoryOn(handle, newScope())
        repository.initialize(HazardFixtures.contextAlpha)
        val snapshot = HazardFixtures.awaitReady(repository).snapshot

        assertTrue("an unrecognised reason must still exclude 1001", snapshot.isBlockedSegment(1001))
        assertTrue("an unrecognised reason must still exclude 1002", snapshot.isBlockedSegment(1002))
        assertEquals(setOf(1001L, 1002L), snapshot.stableIds())
    }

    // ---------------------------------------------------------------- harness

    /** Boots the app against a database file, exactly as a cold start does. */
    private fun open(databaseName: String = HazardFixtures.DATABASE_NAME): ResQRouteDatabase =
        HazardFixtures.openDatabase(context, databaseName).also { openDatabases += it }

    private fun newScope(): CoroutineScope =
        CoroutineScope(Dispatchers.Unconfined + SupervisorJob()).also { openScopes += it }

    private fun repositoryOn(db: ResQRouteDatabase, scope: CoroutineScope): RoomHazardRepository =
        HazardFixtures.repository(
            database = db,
            scope = scope,
            segmentCount = HazardFixtures.SEGMENT_COUNT,
            deviceIdGenerator = { "device-fixed-0001" },
            eventIds = eventIds,
        )

    /** v1's own answer for the active context, read from the file before Room touches it. */
    private fun v1Answer(): Set<Long> = LegacyDatabaseSeed.readV1BlockedSet(
        context = context,
        databaseName = HazardFixtures.DATABASE_NAME,
        datasetHash = HazardFixtures.DATASET_HASH,
        incidentId = HazardFixtures.INCIDENT,
    )

    private fun HazardSnapshot.stableIds(): Set<Long> =
        blockedSegments.mapTo(mutableSetOf()) { it.value.toLong() }

    private companion object {
        /** The second installation used by the replay test. */
        const val PEER_DATABASE_NAME = "resqroute-hazard-peer-test.db"
    }
}
