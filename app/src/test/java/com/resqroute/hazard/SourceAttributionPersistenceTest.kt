package com.resqroute.hazard

import android.app.Application
import android.content.Context
import com.resqroute.core.model.SegmentId
import com.resqroute.data.local.ResQRouteDatabase
import com.resqroute.data.repository.RoomHazardRepository
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.HazardState
import com.resqroute.domain.hazard.MutationResult
import com.resqroute.domain.hazard.ObservationSource
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.WaterLevelCategory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * Source typing as it actually ships (XPH-73): derived from the origin device, never stored.
 *
 * The interesting claim is that `DIRECT` versus `RELAYED` needs no schema and no wire field,
 * because `originDeviceId == this device` already answers it — and that storing it would be
 * wrong, since the same row is first-hand on one device and relayed on every other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SourceAttributionPersistenceTest {

    private lateinit var context: Context
    private val databases = mutableListOf<ResQRouteDatabase>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val eventIds = AtomicInteger(0)

    @Before
    fun setUp() {
        context = HazardFixtures.context()
        context.deleteDatabase(HazardFixtures.DATABASE_NAME)
    }

    @After
    fun tearDown() {
        databases.forEach { runCatching { it.close() } }
        scopes.forEach { it.cancel() }
        context.deleteDatabase(HazardFixtures.DATABASE_NAME)
    }

    // ------------------------------------------------------ the derivation

    @Test
    fun ourOwnReportIsFirstHandAndAPeersIsRelayed() = runBlocking {
        val repository = launch()
        assertTrue(repository.blockLocal(SegmentId(101), HazardReason.BLOCKED) is MutationResult.Accepted)
        assertTrue(
            repository.ingestRemote(
                HazardFixtures.mutation(
                    "evt-peer-0001", segmentId = 102, deviceId = "device-b-0002",
                    observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION),
                ),
            ) is MutationResult.Accepted,
        )

        val mine = HazardFixtures.awaitObservedState(repository, 101)
        assertEquals(ObservationSource.DIRECT_OBSERVATION, mine.reports.single().source)
        assertEquals(1, mine.directSupportCount)
        assertEquals(0, mine.relayedSupportCount)

        val theirs = HazardFixtures.awaitObservedState(repository, 102)
        assertEquals(ObservationSource.RELAYED_ACCOUNT, theirs.reports.single().source)
        assertEquals(0, theirs.directSupportCount)
        assertEquals(1, theirs.relayedSupportCount)
    }

    @Test
    fun theOriginDeviceDecidesNotTheIngressPath() = runBlocking {
        // The rule, stated plainly: first-hand means "this device is the origin of the report".
        // A peer that claims our device id is therefore labelled first-hand - a caveat
        // documented in ObservationSource, bounded to attribution because the device id is
        // unchanged either way and no verdict, count or route reads the source.
        val repository = launch()
        val deviceId = requireNotNull(repository.deviceId).value
        assertTrue(
            repository.ingestRemote(
                HazardFixtures.mutation("evt-spoofed-0001", segmentId = 103, deviceId = deviceId),
            ) is MutationResult.Accepted,
        )
        val state = HazardFixtures.awaitObservedState(repository, 103)
        assertEquals(ObservationSource.DIRECT_OBSERVATION, state.reports.single().source)
        // ...and the count of devices is unaffected, because it is the same device id.
        assertEquals(1, state.supportCount)
    }

    @Test
    fun sourceIsNotStoredBecauseItIsDerivable() = runBlocking {
        val repository = launch()
        assertTrue(
            repository.ingestRemote(
                HazardFixtures.mutation("evt-peer-0002", segmentId = 104, deviceId = "device-b-0002"),
            ) is MutationResult.Accepted,
        )
        HazardFixtures.awaitObservedState(repository, 104)

        // The row has no source code at all...
        val row = database.hazardDao().findEvent("evt-peer-0002")
        assertNull("the reserved source column stays null: this fact is derived, not stored", row?.sourceCode)
        // ...yet the derived state knows exactly where it came from.
        assertEquals(
            ObservationSource.RELAYED_ACCOUNT,
            repository.observationStates.value.getValue(SegmentId(104)).reports.single().source,
        )
    }

    @Test
    fun relayedReportsFromOneDeviceCountAsOneDevice() = runBlocking {
        val repository = launch()
        val peer = HazardFixtures.mutation(
            "evt-peer-0003", segmentId = 105, deviceId = "device-b-0002",
            observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION),
        )
        assertTrue(repository.ingestRemote(peer) is MutationResult.Accepted)
        // Two more events from the same phone: different ids, same device.
        assertTrue(repository.ingestRemote(peer.copy(eventId = com.resqroute.domain.hazard.EventId("evt-peer-0004"))) is MutationResult.Accepted)
        assertTrue(repository.ingestRemote(peer.copy(eventId = com.resqroute.domain.hazard.EventId("evt-peer-0005"))) is MutationResult.Accepted)

        val state = HazardFixtures.awaitObservedState(repository, 105) { it.reports.size == 3 }
        assertEquals("three reports from one phone are one device", 1, state.supportCount)
        assertEquals(1, state.relayedSupportCount)
        assertEquals(3, state.reports.size)
    }

    @Test
    fun attributionSurvivesARestart() = runBlocking {
        val handle = open()
        val repository = HazardFixtures.repository(handle, newScope(), eventIds = eventIds)
        repository.initialize(HazardFixtures.contextAlpha)
        assertTrue(repository.blockLocal(SegmentId(106), HazardReason.BLOCKED) is MutationResult.Accepted)
        assertTrue(
            repository.ingestRemote(
                HazardFixtures.mutation("evt-peer-0006", segmentId = 106, deviceId = "device-b-0002"),
            ) is MutationResult.Accepted,
        )
        val before = HazardFixtures.awaitObservedState(repository, 106) { it.reports.size == 2 }
        handle.close()

        val relaunched = HazardFixtures.repository(open(), newScope(), eventIds = eventIds)
        relaunched.initialize(HazardFixtures.contextAlpha)
        val after = HazardFixtures.awaitObservedState(relaunched, 106) { it.reports.size == 2 }

        assertEquals("attribution must be rebuilt identically", before, after)
        assertEquals(2, after.supportCount)
        assertEquals(1, after.directSupportCount)
        assertEquals(1, after.relayedSupportCount)
        assertEquals(
            listOf(ObservationSource.DIRECT_OBSERVATION, ObservationSource.RELAYED_ACCOUNT),
            after.reports.map { it.source }.distinct(),
        )
    }

    @Test
    fun sourceDoesNotAffectWhatTheRouterMayDo() = runBlocking {
        val repository = launch()
        assertTrue(
            repository.ingestRemote(
                HazardFixtures.mutation("evt-peer-0007", segmentId = 107, deviceId = "device-b-0002"),
            ) is MutationResult.Accepted,
        )
        // Wait for the derived set, not merely for Ready: the repository was already Ready
        // before this report arrived, so awaitReady would read the previous snapshot.
        val snapshot = HazardFixtures.awaitBlocked(repository, 107).snapshot
        assertTrue("a relayed closure still closes the road", snapshot.isBlockedSegment(107))
        assertEquals(setOf(107), snapshot.blockedSegments.map { it.value }.toSet())
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun launch(): RoomHazardRepository {
        val repository = HazardFixtures.repository(open(), newScope(), eventIds = eventIds)
        repository.initialize(HazardFixtures.contextAlpha)
        HazardFixtures.awaitReady(repository)
        return repository
    }

    private fun open(): ResQRouteDatabase =
        HazardFixtures.openDatabase(context, HazardFixtures.DATABASE_NAME).also { databases += it }

    /** The last opened database, which is the one the repository under test is using. */
    private val database: ResQRouteDatabase get() = databases.last()

    private fun newScope(): CoroutineScope =
        CoroutineScope(Dispatchers.Unconfined + SupervisorJob()).also { scopes += it }
}
