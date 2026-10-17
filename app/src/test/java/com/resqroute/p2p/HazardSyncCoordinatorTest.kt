package com.resqroute.p2p

import com.resqroute.core.model.DatasetIdentity
import com.resqroute.core.model.SegmentId
import com.resqroute.data.p2p.HazardSyncCoordinator
import com.resqroute.data.p2p.TransportEvent
import com.resqroute.domain.hazard.DeviceId
import com.resqroute.domain.hazard.EventId
import com.resqroute.domain.hazard.HazardContext
import com.resqroute.domain.hazard.HazardMutation
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.MutationResult
import com.resqroute.domain.sync.PeerMessage
import com.resqroute.domain.sync.PeerProtocol
import com.resqroute.domain.sync.PeerSessionId
import com.resqroute.domain.sync.SyncState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end protocol behaviour through the real coordinator and codec, with a
 * fake transport and a recording hazard repository.
 *
 * Covers acceptance criteria 1–6 and 8: explicit acceptance, the compatibility
 * handshake, ACK-after-commit, retry, replay, dedup/echo termination, malformed
 * rejection and failure isolation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HazardSyncCoordinatorTest {

    private val hash = "a".repeat(64)
    /**
     * Built at the protocol version on purpose: the fake transport encodes and decodes, and the
     * wire does not carry the dataset **format** version (the hash is the identity), so a delivered
     * event necessarily arrives carrying the protocol version.
     */
    private val context = HazardContext(DatasetIdentity(hash, PeerProtocol.VERSION), "incident-a")
    private val device = DeviceId("device-fixed-0001")

    private val sessionA = PeerSessionId("endpoint-A")
    private val sessionB = PeerSessionId("endpoint-B")

    private fun mutation(
        id: String = "evt-00000001",
        segment: Int = 42,
        reason: HazardReason = HazardReason.WATERLOGGED,
    ) = HazardMutation(
        eventId = EventId(id),
        originDeviceId = DeviceId("device-fixed-0001"),
        context = context,
        segmentId = SegmentId(segment),
        reason = reason,
        occurredAtEpochMillis = 1,
    )

    private fun hello(
        dataset: String = hash,
        incident: String = "incident-a",
        version: Int = PeerProtocol.VERSION,
    ) = PeerMessage.Hello(version, DeviceId("peer-00000001"), dataset, incident)

    private class Harness(
        val transport: FakeP2PTransport,
        val repository: RecordingHazardRepository,
        val coordinator: HazardSyncCoordinator,
    )

    private fun TestScope.harness(persisted: List<HazardMutation> = emptyList()): Harness {
        val transport = FakeP2PTransport()
        val repository = RecordingHazardRepository(context).apply {
            this.persisted = persisted
            markReady()
        }
        val coordinator = HazardSyncCoordinator(
            transport = transport,
            repository = repository,
            // backgroundScope: the coordinator owns long-lived collectors; they
            // must not keep runTest waiting for completion.
            scope = backgroundScope,
            contextProvider = { context },
            deviceIdProvider = { device },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            retryIntervalMillis = 1_000,
            retryTimeoutMillis = 500,
            maxSendAttempts = 3,
            clock = { testScheduler.currentTime },
        )
        coordinator.start()
        runCurrent()
        return Harness(transport, repository, coordinator)
    }

    private fun TestScope.connect(h: Harness, sessionId: PeerSessionId = sessionA, name: String = "phone") {
        h.transport.deliver(TransportEvent.Connected(sessionId, name))
        runCurrent()
    }

    private fun TestScope.handshake(h: Harness, sessionId: PeerSessionId = sessionA) {
        h.transport.deliverMessage(sessionId, hello())
        runCurrent()
    }

    private fun Harness.lastError(): String? = (coordinator.state.value as? SyncState.Running)?.lastError

    // ------------------------------------------------------------------ AC2

    @Test
    fun helloIsSentOnConnectAndReplayFollowsTheHandshake() = runTest {
        val replay = mutation(id = "evt-00000009", segment = 7)
        val h = harness(persisted = listOf(replay))

        connect(h)
        assertTrue(
            "expected a Hello after connect",
            h.transport.messagesTo(sessionA).any { it is PeerMessage.Hello },
        )

        handshake(h)
        val messages = h.transport.messagesTo(sessionA)
        assertTrue("expected replay batch", messages.any { it is PeerMessage.EventBatch })
        assertTrue("expected SyncComplete", messages.any { it == PeerMessage.SyncComplete })
        assertTrue("replay must contain the persisted event", replay.eventId in h.transport.batchEventIds(sessionA))
    }

    @Test
    fun incompatiblePeerIsRejectedExplicitly() = runTest {
        val h = harness()
        connect(h)
        h.transport.deliverMessage(sessionA, hello(dataset = "b".repeat(64)))
        runCurrent()

        assertTrue("incompatible peer must be disconnected", sessionA in h.transport.disconnected)
        assertFalse(
            "no replay may be sent to an incompatible peer",
            h.transport.messagesTo(sessionA).any { it is PeerMessage.EventBatch },
        )
        assertTrue(h.lastError()!!.contains("incompatible"))
    }

    @Test
    fun protocolVersionMismatchIsRejected() = runTest {
        val h = harness()
        connect(h)
        h.transport.deliverMessage(sessionA, hello(version = PeerProtocol.VERSION + 1))
        runCurrent()
        assertTrue(sessionA in h.transport.disconnected)
    }

    // ------------------------------------------------------- AC1, AC3, AC4

    @Test
    fun remoteEventIsCommittedThenAcked() = runTest {
        val h = harness()
        connect(h)
        handshake(h)
        h.transport.clearSent()

        val incoming = mutation(id = "evt-00000020", segment = 5)
        h.transport.deliverMessage(sessionA, PeerMessage.EventBatch(listOf(incoming)))
        runCurrent()

        assertEquals(listOf(incoming), h.repository.ingested)
        assertTrue(
            "ACK must follow the commit",
            h.transport.messagesTo(sessionA).any { it == PeerMessage.Ack(incoming.eventId) },
        )
    }

    @Test
    fun noAckWhenCommitFails() = runTest {
        val h = harness()
        connect(h)
        handshake(h)
        h.transport.clearSent()

        h.repository.failure = MutationResult.PersistenceFailure("disk full")
        val incoming = mutation(id = "evt-00000021")
        h.transport.deliverMessage(sessionA, PeerMessage.EventBatch(listOf(incoming)))
        runCurrent()

        assertFalse(
            "a failed write must never be acknowledged",
            h.transport.messagesTo(sessionA).any { it is PeerMessage.Ack },
        )
        assertTrue(h.lastError()!!.contains("not persisted"))
    }

    @Test
    fun duplicateReceptionAcksButNeverRelaysAndTerminatesTheEcho() = runTest {
        val h = harness()
        connect(h)
        handshake(h)
        connect(h, sessionB, "phone-B")
        handshake(h, sessionB)
        h.transport.clearSent()

        val incoming = mutation(id = "evt-00000030")

        // First sighting from A: commit, ACK A, relay once to B.
        h.transport.deliverMessage(sessionA, PeerMessage.EventBatch(listOf(incoming)))
        runCurrent()
        assertTrue(incoming.eventId in h.transport.batchEventIds(sessionB))
        assertTrue(h.transport.messagesTo(sessionA).any { it == PeerMessage.Ack(incoming.eventId) })

        h.transport.clearSent()

        // The echo returns from B: duplicate -> ACK only, no relay back to A.
        h.transport.deliverMessage(sessionB, PeerMessage.EventBatch(listOf(incoming)))
        runCurrent()
        assertTrue(h.transport.messagesTo(sessionB).any { it == PeerMessage.Ack(incoming.eventId) })
        assertFalse(
            "a duplicate must not be relayed again; A received " +
                h.transport.batchEventIds(sessionA).map { it.value },
            h.transport.batchEventIds(sessionA).contains(incoming.eventId),
        )
        // The duplicate was received but committed exactly once.
        assertEquals(2, h.repository.ingested.size)
        assertEquals(1, h.repository.committed.size)
    }

    @Test
    fun relayExcludesTheIngressSession() = runTest {
        val h = harness()
        connect(h)
        handshake(h)
        h.transport.clearSent()

        val incoming = mutation(id = "evt-00000031")
        h.transport.deliverMessage(sessionA, PeerMessage.EventBatch(listOf(incoming)))
        runCurrent()

        assertFalse(
            "the ingress session must not receive its own event back",
            h.transport.batchEventIds(sessionA).contains(incoming.eventId),
        )
    }

    // ------------------------------------------------------------- AC5, AC6

    @Test
    fun malformedPayloadClosesTheSessionWithoutMutatingTheDatabase() = runTest {
        val h = harness()
        connect(h)
        handshake(h)

        h.transport.deliverBytes(sessionA, "{not json".toByteArray())
        runCurrent()

        assertTrue(h.repository.ingested.isEmpty())
        assertTrue(sessionA in h.transport.disconnected)
        assertTrue(h.lastError()!!.contains("malformed"))
    }

    @Test
    fun oversizedPayloadClosesTheSessionWithoutMutatingTheDatabase() = runTest {
        val h = harness()
        connect(h)
        handshake(h)

        h.transport.deliverBytes(sessionA, ByteArray(PeerProtocol.MAX_PAYLOAD_BYTES + 1))
        runCurrent()

        assertTrue(h.repository.ingested.isEmpty())
        assertTrue(sessionA in h.transport.disconnected)
        assertTrue(h.lastError()!!.contains("too large"))
    }

    // ------------------------------------------------------------- AC3 retry

    @Test
    fun unacknowledgedEventsAreRetriedUntilAcked() = runTest {
        val h = harness()
        connect(h)
        handshake(h)
        h.transport.clearSent()

        val local = mutation(id = "evt-00000040")
        h.coordinator.submitLocal(local)
        runCurrent()
        val initialSends = h.transport.batchEventIds(sessionA).count { it == local.eventId }
        assertEquals(1, initialSends)

        // No ACK yet: the retry loop resends after the timeout.
        advanceTimeBy(2_000)
        runCurrent()
        val afterRetry = h.transport.batchEventIds(sessionA).count { it == local.eventId }
        assertTrue("expected a retry, sends=$afterRetry", afterRetry > initialSends)

        // Once acknowledged, retries stop.
        h.transport.deliverMessage(sessionA, PeerMessage.Ack(local.eventId))
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(afterRetry, h.transport.batchEventIds(sessionA).count { it == local.eventId })
    }

    @Test
    fun submitLocalSendsOnlyToCompatibleHandshakenPeers() = runTest {
        val h = harness()
        connect(h)
        handshake(h)
        connect(h, sessionB, "phone-B") // connected but not handshaken
        h.transport.clearSent()

        val local = mutation(id = "evt-00000041")
        h.coordinator.submitLocal(local)
        runCurrent()

        assertTrue(h.transport.batchEventIds(sessionA).contains(local.eventId))
        assertFalse(h.transport.batchEventIds(sessionB).contains(local.eventId))
    }

    // ------------------------------------------------------------------ AC1

    @Test
    fun acceptanceIsAlwaysExplicitNeverAutomatic() = runTest {
        val h = harness()
        h.transport.deliver(
            TransportEvent.ConnectionRequested(sessionA, "phone-A", "1234", incoming = true),
        )
        runCurrent()

        assertEquals(1, h.coordinator.authRequests.value.size)
        assertTrue("the coordinator must not auto-accept", h.transport.accepted.isEmpty())

        h.coordinator.accept(sessionA)
        runCurrent()
        assertEquals(listOf(sessionA), h.transport.accepted)
        assertTrue(h.coordinator.authRequests.value.isEmpty())
    }

    @Test
    fun aDiscoveredPeerIsConnectedOnceButStillNeedsExplicitAcceptance() = runTest {
        val h = harness()
        h.transport.deliver(TransportEvent.EndpointFound(sessionA, "phone-A"))
        runCurrent()

        assertEquals(listOf(sessionA), h.transport.connected)
        // Discovery initiates a connection request; it never accepts for the user.
        assertTrue(h.transport.accepted.isEmpty())

        // Repeated discovery of the same endpoint does not spam connection requests.
        h.transport.deliver(TransportEvent.EndpointFound(sessionA, "phone-A"))
        runCurrent()
        assertEquals(1, h.transport.connected.size)
    }

    @Test
    fun onlyTheLexicographicallySmallerInstallationIdInitiates() = runTest {
        // Own id is device-fixed-0001 -> short form "device-f".
        val h = harness()

        // Peer id sorts AFTER ours: we dial.
        h.transport.deliver(TransportEvent.EndpointFound(sessionA, "RQR-zzzzzzzz"))
        runCurrent()
        assertEquals(listOf(sessionA), h.transport.connected)

        // Peer id sorts BEFORE ours: we stay quiet and let the peer dial us.
        h.transport.deliver(TransportEvent.EndpointFound(sessionB, "RQR-aaaaaaaa"))
        runCurrent()
        assertTrue("must not dial a peer that sorts before us", h.transport.connected.contains(sessionB).not())
    }

    @Test
    fun stoppingSyncLeavesNoSessionsAndStopsTheTransport() = runTest {
        val h = harness()
        connect(h)
        handshake(h)
        h.coordinator.stop()
        runCurrent()

        assertEquals(SyncState.Stopped, h.coordinator.state.value)
    }
}
