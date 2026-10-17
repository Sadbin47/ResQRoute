package com.resqroute.p2p

import android.app.Application
import android.content.Context
import com.resqroute.core.model.DatasetIdentity
import com.resqroute.core.model.SegmentId
import com.resqroute.data.local.ResQRouteDatabase
import com.resqroute.data.p2p.DecodeResult
import com.resqroute.data.p2p.PeerMessageCodec
import com.resqroute.data.repository.RoomHazardRepository
import com.resqroute.domain.hazard.DeviceId
import com.resqroute.domain.hazard.EventId
import com.resqroute.domain.hazard.HazardContext
import com.resqroute.domain.hazard.HazardMutation
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.HazardState
import com.resqroute.domain.hazard.MutationResult
import com.resqroute.domain.hazard.ObservationSource
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.domain.sync.PeerMessage
import com.resqroute.domain.sync.PeerProtocol
import com.resqroute.hazard.HazardFixtures
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bidirectional convergence, replay and relay, end to end (XPH-79).
 *
 * [ConvergenceTest] proves the order-independence of the *pure derivation*; this class proves the
 * consequence where reports actually arrive: **two or more devices, each with its own Room database,
 * each ingesting the other's reports through the real codec bytes and the single repository ingress,
 * ending on the same answer about every road.** That is the property the sync layer exists to provide.
 *
 * ## What converges, and what legitimately does not
 *
 * The committed event set converges, and so does every decision derived from it: the excluded set, the
 * reported verdict, the water level, the distinct-device count and the resolved closures. Two things are
 * local facts about a *device* rather than claims about a *road*, and must differ:
 *
 *  * [ObservationSource] — a report is first-hand on the device that authored it and relayed on every
 *    other device (ADR 0001 D5);
 *  * `receivedAtEpochMillis` — the local receipt stamp, the freshness basis, never distributed
 *    (ADR 0005 §6.2).
 *
 * ADR 0005 §7.3 states the contract exactly: **labels may differ between devices; excluded sets may
 * not.** The [decisionsOf] projection is that sentence turned into an assertion.
 *
 * Everything travels as real wire bytes: a batch is encoded at [PeerProtocol.VERSION] and decoded again
 * before it is ingested, so the fixture cannot hand a repository an object no peer could have sent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BidirectionalConvergenceTest {

    private lateinit var context: Context
    private val eventIds = AtomicInteger(0)
    private val opened = mutableListOf<Device>()

    /**
     * The dataset identity the wire carries. The wire does **not** carry the dataset format version —
     * the hash is the identity, and a decoded event comes back stamped with [PeerProtocol.VERSION] — so
     * building the context at the protocol version is what a real receiver sees.
     */
    private val incidentContext = HazardContext(
        DatasetIdentity(HazardFixtures.DATASET_HASH, PeerProtocol.VERSION),
        HazardFixtures.INCIDENT,
    )

    private val t0 = HazardFixtures.FIXED_CLOCK

    @Before
    fun setUp() {
        context = HazardFixtures.context()
        for (name in DEVICE_DATABASES) context.deleteDatabase(name)
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        for (name in DEVICE_DATABASES) context.deleteDatabase(name)
    }

    // -------------------------------------------------------------------- headline

    @Test
    fun twoDevicesThatExchangeInBothDirectionsConvergeOnEveryDecision() = runBlocking {
        val alice = openDevice("alice", "device-alice-0001")
        val bob = openDevice("bob", "device-bob-0002")

        // Each device authored reports for both segments: a flooded road both know about, and a road
        // Alice reports blocked while Bob merely advises avoiding it.
        val aliceFlood = alice.authored("evt-bi-a-0001", segment = 42, KNEE to CAUTION)
        val aliceBlock = alice.authored("evt-bi-a-0002", segment = 43, WAIST to IMPASSABLE, reason = HazardReason.BLOCKED)
        val bobDrained = bob.authored("evt-bi-b-0001", segment = 42, NO_VISIBLE_WATER to PASSABLE_REPORTED)
        val bobAvoid = bob.authored("evt-bi-b-0002", segment = 43, KNEE to AVOID)

        alice.commit(aliceFlood)
        alice.commit(aliceBlock)
        bob.commit(bobDrained)
        bob.commit(bobAvoid)

        // The exchange, both directions, as bytes. Bob replays his whole log, which by now already
        // contains Alice's two relayed reports — that is what a real full-mesh exchange looks like, and
        // it is where dedup has to hold.
        val bobSeesAlice = bob.receiveAll(alice)
        assertEquals("bob must accept alice's two reports", 2, bobSeesAlice.count { it is MutationResult.Accepted })
        assertTrue(
            "the two bob accepts are the ones alice authored",
            bobSeesAlice.filterIsInstance<MutationResult.Accepted>()
                .all { it.event.originDeviceId.value == "device-alice-0001" },
        )
        assertTrue(
            "a replayed echo of the same batch must dedupe on every event",
            bob.receiveAll(alice).all { it == MutationResult.Duplicate },
        )

        val aliceSeesBob = alice.receiveAll(bob)
        assertEquals("alice must accept exactly bob's two reports", 2, aliceSeesBob.count { it is MutationResult.Accepted })
        assertTrue(
            "the two she accepts are the ones bob authored",
            aliceSeesBob.filterIsInstance<MutationResult.Accepted>()
                .all { it.event.originDeviceId.value == "device-bob-0002" },
        )
        assertEquals(
            "her own two reports come back over the wire as duplicates, not as new rows",
            2,
            aliceSeesBob.count { it == MutationResult.Duplicate },
        )
        assertTrue("and a second replay still dedupes", alice.receiveAll(bob).all { it == MutationResult.Duplicate })

        val aliceStates = alice.awaitStates(segments = 2, reports = 4)
        val bobStates = bob.awaitStates(segments = 2, reports = 4)

        assertEquals(
            "two devices that exchanged their logs must answer every routing question identically",
            decisionsOf(aliceStates),
            decisionsOf(bobStates),
        )

        // Not vacuously equal: 42 is traversable, 43 is closed, on both devices.
        assertFalse(aliceStates.getValue(SegmentId(42)).excludesTraversal)
        assertTrue(aliceStates.getValue(SegmentId(43)).excludesTraversal)
        assertEquals(PassabilityStatus.CAUTION, aliceStates.getValue(SegmentId(42)).passability)
        assertEquals(WaterLevelCategory.KNEE, aliceStates.getValue(SegmentId(42)).worstWaterLevel)
        assertEquals(PassabilityStatus.IMPASSABLE, aliceStates.getValue(SegmentId(43)).passability)

        // The blocked set — what the router consumes — is identical, and is the one closed segment.
        assertEquals(alice.blockedSegments(), bob.blockedSegments())
        assertEquals(setOf(43), alice.blockedSegments().map { it.value }.toSet())

        // Support is the same *set* of origin devices on both devices: neither doubled by the exchange
        // nor reduced to the local author.
        for (segment in listOf(42, 43)) {
            assertEquals(
                "segment $segment must be supported by both origin devices on either device",
                setOf("device-alice-0001", "device-bob-0002"),
                aliceStates.getValue(SegmentId(segment)).reportingDeviceIds.map { it.value }.toSet(),
            )
            assertEquals(2, aliceStates.getValue(SegmentId(segment)).supportCount)
            assertEquals(2, bobStates.getValue(SegmentId(segment)).supportCount)
        }

        // The one thing that must differ, and does: attribution. Alice's own report is first-hand on
        // Alice and relayed on Bob, and the reverse. No decision above is affected — the whole point of
        // keeping provenance out of the derivation.
        assertSource(alice, segment = 42, eventId = "evt-bi-a-0001", expected = ObservationSource.DIRECT_OBSERVATION)
        assertSource(bob, segment = 42, eventId = "evt-bi-a-0001", expected = ObservationSource.RELAYED_ACCOUNT)
        assertNotEquals(
            "the state values are deliberately not identical, because attribution is a local fact",
            aliceStates.getValue(SegmentId(42)).reports.map { it.source },
            bobStates.getValue(SegmentId(42)).reports.map { it.source },
        )
    }

    @Test
    fun aRelayedEchoOfAnEventChangesNothingOnTheDeviceThatAuthoredIt() = runBlocking {
        val alice = openDevice("alice", "device-alice-0001")
        val bob = openDevice("bob", "device-bob-0002")

        val authored = alice.authored("evt-echo-0001", segment = 61, WAIST to IMPASSABLE)
        alice.commit(authored)

        // Bob receives it once (accepted), then Bob's retry loop re-sends it (duplicate), then Bob
        // relays it back to Alice — the echo the coordinator terminates.
        val firstSighting = bob.receiveAll(alice)
        assertTrue("first sighting must commit, was $firstSighting", firstSighting.single() is MutationResult.Accepted)
        assertEquals(listOf(MutationResult.Duplicate), bob.receiveAll(alice))
        assertEquals(listOf(MutationResult.Duplicate), bob.receiveAll(alice))
        assertEquals(
            "the author must dedupe its own event arriving back over the wire",
            listOf(MutationResult.Duplicate),
            alice.receiveAll(bob),
        )

        val aliceState = alice.awaitStates(segments = 1, reports = 1).getValue(SegmentId(61))
        val bobState = bob.awaitStates(segments = 1, reports = 1).getValue(SegmentId(61))
        assertEquals("one event, one row, one support entry on the author", 1, aliceState.reports.size)
        assertEquals(1, aliceState.supportCount)
        assertEquals("and the same on the relayed copy", 1, bobState.reports.size)
        assertEquals(1, bobState.supportCount)
        assertEquals(decisionsOf(mapOf(SegmentId(61) to aliceState)), decisionsOf(mapOf(SegmentId(61) to bobState)))
    }

    // ------------------------------------------------------------------ many phones

    @Test
    fun threeDevicesThatGatherTheSameSetInDifferentOrdersConvergeOnOneAnswer() = runBlocking {
        // The pure aggregator's 5040-orderings check lives in [ConvergenceTest]. This is its durable
        // twin: five reports from three authors delivered to three databases in three different orders,
        // one of them shuffled and duplicated, through the real codec and the real ingress.
        val alice = openDevice("alice", "device-alice-0001")
        val bob = openDevice("bob", "device-bob-0002")
        val carol = openDevice("carol", "device-carol-0003")

        val events = listOf(
            wireMutation("evt-mesh-0001", "device-alice-0001", segment = 51, KNEE to CAUTION, occurredAt = t0),
            wireMutation("evt-mesh-0002", "device-bob-0002", segment = 51, WAIST to IMPASSABLE, occurredAt = t0 + 1_000),
            wireMutation("evt-mesh-0003", "device-carol-0003", segment = 52, NO_VISIBLE_WATER to PASSABLE_REPORTED, occurredAt = t0),
            wireMutation("evt-mesh-0004", "device-alice-0001", segment = 52, ANKLE to AVOID, occurredAt = t0 + 2_000),
            wireMutation("evt-mesh-0005", "device-bob-0002", segment = 52, KNEE to CAUTION, occurredAt = t0 + 3_000),
        )

        val random = Random(79)
        val duplicatedShuffle = events.flatMap { event -> List(random.nextInt(1, 3)) { event } }.shuffled(random)

        alice.applyInOrder(events)
        bob.applyInOrder(events.reversed())
        carol.applyInOrder(duplicatedShuffle)

        val aliceStates = alice.awaitStates(segments = 2, reports = 5)
        val bobStates = bob.awaitStates(segments = 2, reports = 5)
        val carolStates = carol.awaitStates(segments = 2, reports = 5)

        assertEquals(decisionsOf(aliceStates), decisionsOf(bobStates))
        assertEquals(decisionsOf(aliceStates), decisionsOf(carolStates))
        assertEquals(alice.blockedSegments(), bob.blockedSegments())
        assertEquals(alice.blockedSegments(), carol.blockedSegments())

        // Not vacuous: 51 is closed by a maximum over verdicts; 52 is traversable, supported by three
        // devices, with the most restrictive of their verdicts and depths.
        assertTrue(aliceStates.getValue(SegmentId(51)).excludesTraversal)
        assertEquals(2, aliceStates.getValue(SegmentId(51)).supportCount)
        assertFalse(aliceStates.getValue(SegmentId(52)).excludesTraversal)
        assertEquals(3, aliceStates.getValue(SegmentId(52)).supportCount)
        assertEquals(PassabilityStatus.AVOID, aliceStates.getValue(SegmentId(52)).passability)
        assertEquals(WaterLevelCategory.KNEE, aliceStates.getValue(SegmentId(52)).worstWaterLevel)
        assertEquals(setOf(51), alice.blockedSegments().map { it.value }.toSet())
    }

    // -------------------------------------------------------------------- fixtures

    /**
     * One device: its own database, repository, scope and pinned identity.
     *
     * Reports are committed through [RoomHazardRepository.ingestRemote], the single ingress that
     * `blockLocal` and `recordLocal` also use; the test supplies the id and origin device explicitly so
     * a report can be authored "by" another device for the exchange.
     */
    private inner class Device(val name: String, val deviceId: String) {

        private val database: ResQRouteDatabase =
            HazardFixtures.openDatabase(context, DATABASE_PREFIX + name + ".db")

        private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

        private val repository: RoomHazardRepository = HazardFixtures.repository(
            database = database,
            scope = scope,
            deviceIdGenerator = { deviceId },
            eventIds = eventIds,
        )

        suspend fun initialize() {
            repository.initialize(incidentContext)
            HazardFixtures.awaitReady(repository)
        }

        /** A report this device authored, not yet committed. */
        fun authored(
            eventId: String,
            segment: Int,
            axes: Pair<WaterLevelCategory, PassabilityStatus>,
            reason: HazardReason = HazardReason.WATERLOGGED,
        ): HazardMutation = wireMutation(eventId, deviceId, segment, axes, reason = reason, occurredAt = t0)

        suspend fun commit(event: HazardMutation) {
            val result = repository.ingestRemote(event)
            assertTrue("committing ${event.eventId.value} returned $result", result is MutationResult.Accepted)
        }

        /**
         * Takes everything [other]'s log holds and ingests it as wire bytes, returning one result per
         * event in the order the log was replayed.
         */
        suspend fun receiveAll(other: Device): List<MutationResult> {
            val log = other.repository.persistedEvents(incidentContext).toList()
            assertTrue("there is nothing to exchange from ${other.name}", log.isNotEmpty())
            val decoded = PeerMessageCodec.decode(PeerMessageCodec.encode(PeerMessage.EventBatch(log)))
            val delivered = (decoded as DecodeResult.Ok).message as PeerMessage.EventBatch
            return delivered.events.map { repository.ingestRemote(it) }
        }

        /** Ingests [events] one at a time, each through the codec, accepting duplicates. */
        suspend fun applyInOrder(events: List<HazardMutation>) {
            for (event in events) {
                val bytes = PeerMessageCodec.encode(PeerMessage.EventBatch(listOf(event)))
                val decoded = (PeerMessageCodec.decode(bytes) as DecodeResult.Ok).message as PeerMessage.EventBatch
                val result = repository.ingestRemote(decoded.events.single())
                assertTrue(
                    "ingesting ${event.eventId.value} on $name returned $result",
                    result is MutationResult.Accepted || result == MutationResult.Duplicate,
                )
            }
        }

        suspend fun awaitStates(segments: Int, reports: Int): Map<SegmentId, SegmentObservationState> =
            HazardFixtures.awaitDerivedState(repository) { states ->
                states.size == segments && states.values.sumOf { it.reports.size } == reports
            }

        fun blockedSegments(): Set<SegmentId> =
            (repository.state.value as HazardState.Ready).snapshot.blockedSegments

        fun stateOf(segment: Int): SegmentObservationState =
            repository.observationStates.value.getValue(SegmentId(segment))

        fun close() {
            runCatching { scope.cancel() }
            runCatching { database.close() }
        }
    }

    private suspend fun openDevice(name: String, deviceId: String): Device =
        Device(name, deviceId).also { opened += it }.also { it.initialize() }

    /** A mutation as it would arrive from a peer: built at the protocol version, never committed here. */
    private fun wireMutation(
        eventId: String,
        originDeviceId: String,
        segment: Int,
        axes: Pair<WaterLevelCategory, PassabilityStatus>,
        reason: HazardReason = HazardReason.WATERLOGGED,
        occurredAt: Long,
    ): HazardMutation = HazardMutation(
        eventId = EventId(eventId),
        originDeviceId = DeviceId(originDeviceId),
        context = incidentContext,
        segmentId = SegmentId(segment),
        reason = reason,
        occurredAtEpochMillis = occurredAt,
        observation = HazardFixtures.observation(reason, axes.first, axes.second),
    )

    private fun assertSource(device: Device, segment: Int, eventId: String, expected: ObservationSource) {
        val report = device.stateOf(segment).reports.single { it.eventId.value == eventId }
        assertEquals("$eventId on ${device.name}", expected, report.source)
    }

    /**
     * Every device-independent decision the derivation makes, as comparable lines.
     *
     * This is ADR 0005 §7.3 as an assertion: the excluded set, the verdict, the depth, the
     * distinct-device count — the set, not a count of receptions — and the resolved closures must be
     * identical on every device holding the same events. Attribution ([ObservationSource]) and receipt
     * stamps are deliberately absent: they are local facts about a device, not claims about a road.
     */
    private fun decisionsOf(states: Map<SegmentId, SegmentObservationState>): List<String> =
        states.entries.sortedBy { it.key.value }.map { (segment, state) ->
            buildString {
                append(segment.value).append('|')
                append(state.excludesTraversal).append('|')
                append(state.passability.code).append('|')
                append(state.worstWaterLevel.code).append('|')
                append(state.supportCount).append('|')
                append(state.reportingDeviceIds.joinToString(",") { it.value }).append('|')
                append(state.resolvedClosures.joinToString(",") { it.eventId.value }).append('|')
                append(state.statedWaterLevels.joinToString(",") { it.code }).append('|')
                append(state.statedPassabilities.joinToString(",") { it.code }).append('|')
                append(
                    state.reports.joinToString(",") {
                        "${it.eventId.value}@${it.deviceId.value}@${it.occurredAtEpochMillis}"
                    },
                )
            }
        }

    private companion object {
        val KNEE = WaterLevelCategory.KNEE
        val WAIST = WaterLevelCategory.WAIST
        val ANKLE = WaterLevelCategory.ANKLE
        val NO_VISIBLE_WATER = WaterLevelCategory.NO_VISIBLE_WATER
        val CAUTION = PassabilityStatus.CAUTION
        val AVOID = PassabilityStatus.AVOID
        val IMPASSABLE = PassabilityStatus.IMPASSABLE
        val PASSABLE_REPORTED = PassabilityStatus.PASSABLE_REPORTED

        const val DATABASE_PREFIX = "resqroute-convergence-"
        val DEVICE_DATABASES = listOf(
            DATABASE_PREFIX + "alice.db",
            DATABASE_PREFIX + "bob.db",
            DATABASE_PREFIX + "carol.db",
        )
    }
}
