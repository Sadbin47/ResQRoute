package com.resqroute.p2p

import com.resqroute.data.p2p.DecodeResult
import com.resqroute.data.p2p.IncomingPayload
import com.resqroute.data.p2p.P2PNetworkManager
import com.resqroute.data.p2p.PeerMessageCodec
import com.resqroute.data.p2p.TransportEvent
import com.resqroute.domain.hazard.DeviceId
import com.resqroute.domain.hazard.EventId
import com.resqroute.domain.hazard.HazardContext
import com.resqroute.domain.hazard.HazardMutation
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.HazardRepository
import com.resqroute.domain.hazard.HazardSnapshot
import com.resqroute.domain.hazard.HazardState
import com.resqroute.domain.hazard.MutationResult
import com.resqroute.domain.sync.PeerMessage
import com.resqroute.domain.sync.PeerSessionId
import com.resqroute.domain.sync.RadioOperation
import com.resqroute.domain.sync.TransportState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * In-memory transport. Lets the whole protocol be exercised on the JVM with no
 * radios, exactly like the real Nearby adapter would drive the coordinator.
 */
internal class FakeP2PTransport : P2PNetworkManager {

    private val _state = MutableStateFlow<TransportState>(TransportState.Stopped)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 128)
    override val events: Flow<TransportEvent> = _events

    private val _incoming = MutableSharedFlow<IncomingPayload>(extraBufferCapacity = 128)
    override val incoming: Flow<IncomingPayload> = _incoming

    val sent = mutableListOf<Pair<PeerSessionId, ByteArray>>()
    val accepted = mutableListOf<PeerSessionId>()
    val rejected = mutableListOf<PeerSessionId>()
    val disconnected = mutableListOf<PeerSessionId>()
    val connected = mutableListOf<PeerSessionId>()

    var sendSucceeds: Boolean = true

    override fun start() {
        _state.value = TransportState.Running(RadioOperation.ACTIVE, RadioOperation.ACTIVE)
    }

    override fun stop() {
        _state.value = TransportState.Stopped
    }

    override fun connect(sessionId: PeerSessionId) {
        connected += sessionId
    }

    override fun accept(sessionId: PeerSessionId) {
        accepted += sessionId
    }

    override fun reject(sessionId: PeerSessionId) {
        rejected += sessionId
    }

    override fun disconnect(sessionId: PeerSessionId) {
        disconnected += sessionId
    }

    override fun send(sessionId: PeerSessionId, bytes: ByteArray): Boolean {
        if (!sendSucceeds) return false
        sent += sessionId to bytes
        return true
    }

    // ------------------------------------------------------------- test hooks

    fun deliver(event: TransportEvent) {
        _events.tryEmit(event)
    }

    fun deliverBytes(sessionId: PeerSessionId, bytes: ByteArray) {
        _incoming.tryEmit(IncomingPayload(sessionId, bytes))
    }

    fun deliverMessage(sessionId: PeerSessionId, message: PeerMessage) {
        deliverBytes(sessionId, PeerMessageCodec.encode(message))
    }

    fun messagesTo(sessionId: PeerSessionId): List<PeerMessage> = sent
        .filter { it.first == sessionId }
        .mapNotNull { (PeerMessageCodec.decode(it.second) as? DecodeResult.Ok)?.message }

    fun batchEventIds(sessionId: PeerSessionId): List<EventId> = messagesTo(sessionId)
        .filterIsInstance<PeerMessage.EventBatch>()
        .flatMap { batch -> batch.events.map { it.eventId } }

    fun clearSent() {
        sent.clear()
    }
}

/**
 * Hazard repository double that behaves like the real ingress for the cases the
 * coordinator cares about: first sighting commits, repeat is a duplicate, and a
 * scripted failure can be injected.
 */
internal class RecordingHazardRepository(
    private val active: HazardContext,
) : HazardRepository {

    private val blocked = linkedSetOf<Int>()
    private val _state = MutableStateFlow<HazardState>(HazardState.Restoring)
    override val state: StateFlow<HazardState> = _state.asStateFlow()

    val ingested = mutableListOf<HazardMutation>()
    val committed = mutableListOf<HazardMutation>()
    var persisted = listOf<HazardMutation>()
    var failure: MutationResult.PersistenceFailure? = null

    fun markReady() {
        _state.value = HazardState.Ready(HazardSnapshot(active, revision = 0, blockedSegments = emptySet()))
    }

    override suspend fun initialize(context: HazardContext) {
        markReady()
    }

    override suspend fun blockLocal(segmentId: com.resqroute.core.model.SegmentId, reason: HazardReason): MutationResult =
        MutationResult.Duplicate

    override suspend fun recordLocal(
        segmentId: com.resqroute.core.model.SegmentId,
        reason: HazardReason,
        waterLevel: com.resqroute.domain.hazard.WaterLevelCategory,
        passability: com.resqroute.domain.hazard.PassabilityStatus,
    ): MutationResult = MutationResult.Duplicate

    override suspend fun ingestRemote(mutation: HazardMutation): MutationResult {
        failure?.let { failure = null; return it }
        ingested += mutation
        val isNew = blocked.add(mutation.segmentId.value)
        if (isNew) {
            committed += mutation
            _state.value = HazardState.Ready(
                HazardSnapshot(active, revision = _state.value.let { (it as? HazardState.Ready)?.snapshot?.revision ?: 0 } + 1, blocked.map { com.resqroute.core.model.SegmentId(it) }.toSet()),
            )
            return MutationResult.Accepted(mutation, effectiveStateChanged = true)
        }
        return MutationResult.Duplicate
    }

    override fun persistedEvents(context: HazardContext): Flow<HazardMutation> = flow {
        for (event in persisted) emit(event)
    }

    companion object {
        fun devices() = DeviceId("device-fixed-0001")
    }
}
