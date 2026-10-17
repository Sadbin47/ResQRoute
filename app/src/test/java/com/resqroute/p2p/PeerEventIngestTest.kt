package com.resqroute.p2p

import android.app.Application
import android.content.Context
import com.resqroute.core.model.SegmentId
import com.resqroute.data.local.ResQRouteDatabase
import com.resqroute.data.p2p.DecodeResult
import com.resqroute.data.p2p.PeerMessageCodec
import com.resqroute.data.repository.RoomHazardRepository
import com.resqroute.domain.hazard.DeviceId
import com.resqroute.domain.hazard.EventId
import com.resqroute.domain.hazard.HazardMutation
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.HazardState
import com.resqroute.domain.hazard.MutationResult
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.domain.sync.PeerMessage
import com.resqroute.hazard.HazardFixtures
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * What a **wire report** does to durable state (XPH-78).
 *
 * `PeerMessageCodecTest` proves the codec in isolation; this class proves the consequence, through
 * the real codec, the real Room repository and the real derivation — that an event which arrived as
 * bytes is stored with the axes the reporter stated, that a reason-only event still means what v1
 * meant, and that a report which closes no road still moves the derived state (which is what the
 * router prices) without moving the hazard revision (which is what the router re-routes on).
 *
 * The distinction the owner flagged in XPH-76 lives exactly here: `hazardRevision` moves only when
 * the blocked set changes, so a two-axis report must be visible in the derived state and in
 * `RoutingInputKey.reportedRisk` even though it closes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PeerEventIngestTest {

    private lateinit var context: Context
    private lateinit var database: ResQRouteDatabase
    private lateinit var repository: RoomHazardRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        context = HazardFixtures.context()
        context.deleteDatabase(HazardFixtures.DATABASE_NAME)
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        database = HazardFixtures.openDatabase(context, HazardFixtures.DATABASE_NAME)
        repository = HazardFixtures.repository(database, scope, eventIds = AtomicInteger(0))
    }

    @After
    fun tearDown() {
        runCatching { scope.cancel() }
        runCatching { database.close() }
        context.deleteDatabase(HazardFixtures.DATABASE_NAME)
    }

    @Test
    fun aDecodedTwoAxisReportIsStoredWithItsAxesAndClosesNothing() = runBlocking {
        start()

        val claim = reportFromTheWire(
            id = "evt-wire-0001",
            segment = 501,
            level = WaterLevelCategory.KNEE,
            passability = PassabilityStatus.CAUTION,
        )
        val result = repository.ingestRemote(claim)
        assertTrue("expected Accepted but was $result", result is MutationResult.Accepted)
        assertFalse(
            "a report that closes no road must say so, or it would invalidate a route for nothing",
            (result as MutationResult.Accepted).effectiveStateChanged,
        )

        val state = HazardFixtures.awaitObservedState(repository, 501)
        assertEquals(WaterLevelCategory.KNEE, state.worstWaterLevel)
        assertEquals(PassabilityStatus.CAUTION, state.passability)
        assertFalse("caution closes nothing", state.excludesTraversal)

        // The point of the version bump: the axes reached durable state, so this device derives its
        // own excluded set from what the reporter stated instead of trusting the sender's reason.
        val row = database.hazardDao().findEvent("evt-wire-0001")!!
        assertEquals("the water-level axis must survive the wire into the row", "KNEE", row.levelCode)
        assertEquals("the passability axis must survive the wire into the row", "CAUTION", row.passabilityCode)

        val ready = repository.state.value as HazardState.Ready
        assertTrue("and no segment may become blocked", ready.snapshot.blockedSegments.isEmpty())
        assertEquals(
            "a report that closes nothing must not invalidate every route in flight",
            0L,
            ready.snapshot.revision,
        )
    }

    @Test
    fun aDecodedReasonOnlyReportStillExcludesExactlyAsV1Did() = runBlocking {
        start()

        val claim = decodeOne(
            """
            {"t":"events","v":2,"events":[
              {"id":"evt-wire-0002","device":"device-peer-0007","dataset":"${HazardFixtures.DATASET_HASH}",
               "incident":"${HazardFixtures.INCIDENT}","segment":601,"reason":"BLOCKED","occurredAt":1}
            ]}
            """.trimIndent(),
        )
        val result = repository.ingestRemote(claim)
        assertTrue("expected Accepted but was $result", result is MutationResult.Accepted)
        assertTrue(
            "a reason-only report is the v1 hard exclusion (ADR 0001 D2)",
            (result as MutationResult.Accepted).effectiveStateChanged,
        )

        val ready = HazardFixtures.awaitBlocked(repository, 601)
        assertTrue(ready.snapshot.isBlockedSegment(601))

        val row = database.hazardDao().findEvent("evt-wire-0002")!!
        assertNull("a legacy-shaped report stores no axes; it resolves through the legacy mapping", row.levelCode)
        assertNull(row.passabilityCode)
    }

    @Test
    fun aRelayedEchoOfTheSameWireEventIsDeduplicatedRatherThanCountedTwice() = runBlocking {
        start()

        val claim = reportFromTheWire(
            id = "evt-wire-0003",
            segment = 701,
            level = WaterLevelCategory.WAIST,
            passability = PassabilityStatus.AVOID,
        )
        assertTrue(repository.ingestRemote(claim) is MutationResult.Accepted)
        val state = HazardFixtures.awaitObservedState(repository, 701)
        assertEquals("one report from one device", 1, state.supportCount)

        // A peer relays the event back with identical bytes - the echo the coordinator terminates.
        assertEquals(
            "the same event must dedupe however many copies arrive",
            MutationResult.Duplicate,
            repository.ingestRemote(claim),
        )
        val after = repository.observationStates.value.getValue(SegmentId(701))
        assertEquals("the relayed copy must not inflate distinct-device support", 1, after.supportCount)
        assertEquals("nor the report set", 1, after.reports.size)
    }

    @Test
    fun theReceiptStampIsThisDevicesOwnAndComesFromNoWireField() = runBlocking {
        start()

        val claimed = HazardFixtures.FIXED_CLOCK + 999_999
        val claim = reportFromTheWire(
            id = "evt-wire-0004",
            segment = 801,
            level = WaterLevelCategory.ANKLE,
            passability = PassabilityStatus.PASSABLE_REPORTED,
            occurredAt = claimed,
        )
        assertTrue(repository.ingestRemote(claim) is MutationResult.Accepted)

        val row = database.hazardDao().findEvent("evt-wire-0004")!!
        assertEquals("the sender's time is kept as a claim, and as audit only", claimed, row.occurredAtEpochMillis)
        assertEquals(
            "the receipt stamp is this device's own: it is the one freshness input no peer can influence (ADR 0005 6.2)",
            HazardFixtures.FIXED_CLOCK,
            row.receivedAtEpochMillis,
        )
    }

    // -------------------------------------------------------------- fixture

    private suspend fun start() {
        repository.initialize(HazardFixtures.contextAlpha)
        HazardFixtures.awaitReady(repository)
    }

    /**
     * A report that arrived as bytes: built, encoded and decoded, never hand-assembled.
     *
     * Whatever the tests below assert therefore holds for what a peer actually sends, not for what
     * this test would like a mutation to look like.
     */
    private fun reportFromTheWire(
        id: String,
        segment: Int,
        level: WaterLevelCategory,
        passability: PassabilityStatus,
        reason: HazardReason = HazardReason.WATERLOGGED,
        occurredAt: Long = HazardFixtures.FIXED_CLOCK,
    ): HazardMutation {
        val sent = HazardMutation(
            eventId = EventId(id),
            originDeviceId = DeviceId("device-peer-0007"),
            context = HazardFixtures.contextAlpha,
            segmentId = SegmentId(segment),
            reason = reason,
            occurredAtEpochMillis = occurredAt,
            observation = HazardFixtures.observation(reason, level, passability),
        )
        val bytes = PeerMessageCodec.encode(PeerMessage.EventBatch(listOf(sent)))
        return ((PeerMessageCodec.decode(bytes) as DecodeResult.Ok).message as PeerMessage.EventBatch)
            .events.single()
    }

    private fun decodeOne(payload: String): HazardMutation =
        ((PeerMessageCodec.decode(payload.toByteArray()) as DecodeResult.Ok).message as PeerMessage.EventBatch)
            .events.single()
}
