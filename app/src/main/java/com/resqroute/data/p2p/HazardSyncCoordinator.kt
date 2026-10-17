package com.resqroute.data.p2p

import com.resqroute.domain.hazard.DeviceId
import com.resqroute.domain.hazard.EventId
import com.resqroute.domain.hazard.HazardContext
import com.resqroute.domain.hazard.HazardMutation
import com.resqroute.domain.hazard.HazardRepository
import com.resqroute.domain.hazard.MutationResult
import com.resqroute.domain.sync.AuthRequest
import com.resqroute.domain.sync.PeerConnection
import com.resqroute.domain.sync.PeerMessage
import com.resqroute.domain.sync.PeerProtocol
import com.resqroute.domain.sync.PeerSessionId
import com.resqroute.domain.sync.SyncState
import com.resqroute.domain.sync.TransportState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reliable foreground synchronization (blueprint section 11).
 *
 * Responsibilities:
 *  * mutual authentication code (the transport surfaces it; acceptance is an
 *    explicit user action — this class never auto-accepts);
 *  * Hello compatibility handshake (protocol + dataset hash + incident);
 *  * bounded event batches, persist-before-send and persist-before-ACK;
 *  * per-session ACK tracking, retry and full event-log replay on connect;
 *  * dedup-based loop termination and relay-excluding-ingress;
 *  * decoded events enter the same [HazardRepository] as local marks.
 *
 * Failure isolation: transport, decode or session failures change only sync
 * state. Local routing and the hazard repository stay usable.
 *
 * Threading: all session mutation happens under [mutex]; the session map itself
 * is guarded by [sessions]' monitor. Callbacks from the transport are collected
 * on a single dispatcher so ordering is deterministic.
 */
class HazardSyncCoordinator(
    private val transport: P2PNetworkManager,
    private val repository: HazardRepository,
    private val scope: CoroutineScope,
    private val contextProvider: () -> HazardContext?,
    private val deviceIdProvider: () -> DeviceId?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val retryIntervalMillis: Long = 2_000,
    private val retryTimeoutMillis: Long = 4_000,
    private val maxSendAttempts: Int = 6,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private class Session(
        val sessionId: PeerSessionId,
        val endpointName: String,
        var compatible: Boolean = false,
        var synchronized: Boolean = false,
        var helloSent: Boolean = false,
        var replayStarted: Boolean = false,
        val pending: LinkedHashMap<EventId, Pending> = LinkedHashMap(),
        val acknowledged: MutableSet<EventId> = mutableSetOf(),
    )

    private class Pending(
        val mutation: HazardMutation,
        var attempts: Int = 0,
        var lastSentAt: Long = 0,
    )

    private val started = AtomicBoolean(false)
    private val mutex = Mutex()
    private val sessions = LinkedHashMap<PeerSessionId, Session>()
    private val discovered = LinkedHashMap<PeerSessionId, String>()
    private val connecting = mutableSetOf<PeerSessionId>()
    private val jobs = mutableListOf<Job>()

    @Volatile
    private var lastError: String? = null

    private val _state = MutableStateFlow<SyncState>(SyncState.Stopped)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _authRequests = MutableStateFlow<List<AuthRequest>>(emptyList())
    val authRequests: StateFlow<List<AuthRequest>> = _authRequests.asStateFlow()

    // ------------------------------------------------------------- lifecycle

    fun start() {
        if (!started.compareAndSet(false, true)) return
        jobs += scope.launch(dispatcher) { transport.state.collect { publish() } }
        jobs += scope.launch(dispatcher) { transport.events.collect { onTransportEvent(it) } }
        jobs += scope.launch(dispatcher) { transport.incoming.collect { onPayload(it) } }
        jobs += scope.launch(dispatcher) { retryLoop() }
        transport.start()
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        jobs.forEach { it.cancel() }
        jobs.clear()
        transport.stop()
        synchronized(sessions) { sessions.clear() }
        connecting.clear()
        discovered.clear()
        _authRequests.value = emptyList()
        lastError = null
        _state.value = SyncState.Stopped
    }

    fun accept(sessionId: PeerSessionId) {
        transport.accept(sessionId)
        removeAuthRequest(sessionId)
    }

    fun reject(sessionId: PeerSessionId) {
        transport.reject(sessionId)
        removeAuthRequest(sessionId)
        synchronized(sessions) { sessions.remove(sessionId) }
        publish()
    }

    /**
     * Submits a **already committed** local report for delivery.
     *
     * Called only after the repository confirmed the write, which is what makes
     * "persist before send" true by construction.
     */
    fun submitLocal(mutation: HazardMutation) {
        if (!started.get()) return
        scope.launch(dispatcher) {
            mutex.withLock {
                for (session in compatibleSessions()) {
                    enqueue(session, listOf(mutation))
                    sendPending(session)
                }
            }
            publish()
        }
    }

    // -------------------------------------------------------- transport flow

    private suspend fun onTransportEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.EndpointFound -> {
                discovered[event.sessionId] = event.endpointName
                // Deterministic initiator rule: only the lexicographically smaller
                // installation id dials. This prevents both peers from requesting a
                // connection at the same time, which Nearby resolves by failing both.
                // Peers without an id in their name (older builds) are dialed as before.
                val peerIdentity = peerIdentity(event.endpointName)
                val ownIdentity = deviceIdProvider()?.value?.take(8)
                val shouldInitiate = peerIdentity == null ||
                    ownIdentity == null ||
                    ownIdentity == peerIdentity ||
                    ownIdentity < peerIdentity

                if (shouldInitiate &&
                    started.get() &&
                    synchronized(sessions) { !sessions.containsKey(event.sessionId) } &&
                    !connecting.contains(event.sessionId)
                ) {
                    connecting += event.sessionId
                    transport.connect(event.sessionId)
                }
            }
            is TransportEvent.EndpointLost -> {
                discovered.remove(event.sessionId)
                connecting.remove(event.sessionId)
            }
            is TransportEvent.ConnectionRequested -> {
                _authRequests.update { requests ->
                    if (requests.any { it.sessionId == event.sessionId }) {
                        requests
                    } else {
                        requests + AuthRequest(event.sessionId, event.endpointName, event.authCode)
                    }
                }
            }
            is TransportEvent.Connected -> {
                val session = Session(event.sessionId, event.endpointName)
                synchronized(sessions) { sessions[event.sessionId] = session }
                connecting.remove(event.sessionId)
                removeAuthRequest(event.sessionId)
                mutex.withLock { sendHello(session) }
                publish()
            }
            is TransportEvent.Disconnected -> {
                synchronized(sessions) { sessions.remove(event.sessionId) }
                connecting.remove(event.sessionId)
                removeAuthRequest(event.sessionId)
                publish()
            }
            is TransportEvent.Failure -> {
                lastError = "${event.stage}: ${event.message}"
                event.sessionId?.let { failed ->
                    synchronized(sessions) { sessions.remove(failed) }
                    connecting.remove(failed)
                    removeAuthRequest(failed)
                }
                publish()
            }
        }
    }

    private suspend fun onPayload(payload: IncomingPayload) {
        mutex.withLock {
            val session = synchronized(sessions) { sessions[payload.sessionId] } ?: return
            when (val decoded = PeerMessageCodec.decode(payload.bytes)) {
                is DecodeResult.Ok -> handleMessage(session, decoded.message)
                is DecodeResult.TooLarge -> failSession(session, "payload too large (${decoded.bytes} bytes)")
                is DecodeResult.Malformed -> failSession(session, "malformed payload: ${decoded.reason}")
                is DecodeResult.Unsupported -> failSession(session, "unsupported payload: ${decoded.reason}")
            }
        }
        publish()
    }

    private suspend fun handleMessage(session: Session, message: PeerMessage) {
        when (message) {
            is PeerMessage.Hello -> onHello(session, message)
            is PeerMessage.EventBatch -> onEventBatch(session, message)
            is PeerMessage.Ack -> session.pending.remove(message.eventId)
            PeerMessage.SyncComplete -> session.synchronized = true
        }
    }

    private suspend fun onHello(session: Session, hello: PeerMessage.Hello) {
        val context = contextProvider()
        // The protocol-version arm is currently unreachable, and that is deliberate rather than
        // leftover. `PeerMessageCodec.decode` refuses any payload whose `v` differs from
        // PeerProtocol.VERSION before a Hello can be constructed, and `decodeHello` stamps the
        // decoded message with that same constant - so a mismatched build never reaches this
        // comparison; its session is already failed on the payload ("unsupported protocol version").
        //
        // It is kept as a second, independent guard: if the codec ever loosens to accept a RANGE of
        // versions - the only reason to touch that check - this is what stops a peer the codec
        // tolerated from being treated as compatible.
        val compatible = context != null &&
            hello.protocolVersion == PeerProtocol.VERSION &&
            hello.datasetHash == context.dataset.contentHash &&
            hello.incidentId == context.incidentId
        session.compatible = compatible
        if (!compatible) {
            failSession(session, "incompatible peer (protocol/dataset/incident mismatch)")
            return
        }
        if (!session.helloSent) sendHello(session)
        if (!session.replayStarted) {
            session.replayStarted = true
            replay(session)
        }
    }

    private suspend fun onEventBatch(session: Session, batch: PeerMessage.EventBatch) {
        if (!session.compatible) {
            failSession(session, "event batch arrived before the compatibility handshake")
            return
        }
        val toAck = ArrayList<HazardMutation>(batch.events.size)
        val toRelay = ArrayList<HazardMutation>(batch.events.size)
        for (mutation in batch.events) {
            when (val result = repository.ingestRemote(mutation)) {
                is MutationResult.Accepted -> {
                    // ACK only after the Room commit, then relay once to other peers.
                    toAck += mutation
                    toRelay += mutation
                }
                MutationResult.Duplicate -> {
                    // Duplicate: ACK only, never relay again. This terminates echoes.
                    toAck += mutation
                }
                is MutationResult.Rejected -> lastError = "rejected remote event: ${result.reason}"
                is MutationResult.PersistenceFailure -> lastError = "remote event not persisted: ${result.message}"
            }
        }
        for (mutation in toAck) {
            send(session, PeerMessage.Ack(mutation.eventId))
        }
        relay(toRelay, ingress = session.sessionId)
    }

    // --------------------------------------------------------------- sending

    private fun sendHello(session: Session) {
        val context = contextProvider() ?: return
        val device = deviceIdProvider() ?: return
        val hello = PeerMessage.Hello(
            protocolVersion = PeerProtocol.VERSION,
            deviceId = device,
            datasetHash = context.dataset.contentHash,
            incidentId = context.incidentId,
        )
        if (send(session, hello)) session.helloSent = true
    }

    private suspend fun replay(session: Session) {
        val context = contextProvider() ?: return
        val persisted = repository.persistedEvents(context).toList()
        enqueue(session, persisted)
        sendPending(session)
        if (send(session, PeerMessage.SyncComplete)) session.synchronized = true
    }

    private fun relay(mutations: List<HazardMutation>, ingress: PeerSessionId) {
        if (mutations.isEmpty()) return
        for (session in compatibleSessions()) {
            if (session.sessionId == ingress) continue
            enqueue(session, mutations)
            sendPending(session)
        }
    }

    private fun enqueue(session: Session, mutations: List<HazardMutation>) {
        for (mutation in mutations) {
            if (mutation.eventId in session.acknowledged) continue
            session.pending.putIfAbsent(mutation.eventId, Pending(mutation))
        }
    }

    private fun sendPending(session: Session) {
        if (session.pending.isEmpty()) return
        val now = clock()
        val due = session.pending.values.filter {
            it.attempts == 0 || now - it.lastSentAt >= retryTimeoutMillis
        }
        if (due.isEmpty()) return

        for (chunk in due.chunked(PeerProtocol.MAX_EVENTS_PER_BATCH)) {
            sendChunk(session, chunk, now)
        }
    }

    private fun sendChunk(session: Session, chunk: List<Pending>, now: Long) {
        val message = PeerMessage.EventBatch(chunk.map { it.mutation })
        val bytes = PeerMessageCodec.encode(message)
        if (bytes.size > PeerProtocol.MAX_PAYLOAD_BYTES) {
            if (chunk.size == 1) {
                lastError = "event does not fit in one payload"
                return
            }
            val half = chunk.size / 2
            sendChunk(session, chunk.subList(0, half), now)
            sendChunk(session, chunk.subList(half, chunk.size), now)
            return
        }
        if (transport.send(session.sessionId, bytes)) {
            for (pending in chunk) {
                pending.attempts++
                pending.lastSentAt = now
            }
        } else {
            lastError = "send failed on ${session.sessionId.value}"
        }
    }

    private fun send(session: Session, message: PeerMessage): Boolean {
        val bytes = PeerMessageCodec.encode(message)
        if (bytes.size > PeerProtocol.MAX_PAYLOAD_BYTES) {
            lastError = "message too large to send"
            return false
        }
        return transport.send(session.sessionId, bytes)
    }

    private suspend fun retryLoop() {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            delay(retryIntervalMillis)
            if (!started.get()) return
            mutex.withLock {
                for (session in synchronized(sessions) { sessions.values.toList() }) {
                    sendPending(session)
                    val exhausted = session.pending.values.filter { it.attempts > maxSendAttempts }
                    if (exhausted.isNotEmpty()) {
                        lastError = "peer did not acknowledge ${exhausted.size} event(s); will replay on reconnect"
                        for (pending in exhausted) session.pending.remove(pending.mutation.eventId)
                    }
                }
            }
            publish()
        }
    }

    // -------------------------------------------------------------- helpers

    private fun compatibleSessions(): List<Session> =
        synchronized(sessions) { sessions.values.filter { it.compatible } }

    /**
     * Extracts the short installation id from an advertising alias (`RQR-xxxxxxxx`).
     * Returns null for names that do not carry one, so older peers still connect.
     */
    private fun peerIdentity(endpointName: String): String? {
        val marker = "RQR-"
        val index = endpointName.indexOf(marker)
        if (index < 0) return null
        val value = endpointName.substring(index + marker.length).takeWhile { it.isLetterOrDigit() }
        return value.takeIf { it.length == 8 }
    }

    private fun failSession(session: Session, reason: String) {
        lastError = reason
        synchronized(sessions) { sessions.remove(session.sessionId) }
        connecting.remove(session.sessionId)
        removeAuthRequest(session.sessionId)
        transport.disconnect(session.sessionId)
    }

    private fun removeAuthRequest(sessionId: PeerSessionId) {
        _authRequests.update { requests -> requests.filterNot { it.sessionId == sessionId } }
    }

    private fun publish() {
        val peers = synchronized(sessions) {
            sessions.values.map {
                PeerConnection(
                    sessionId = it.sessionId,
                    endpointName = it.endpointName,
                    compatible = it.compatible,
                    synchronized = it.synchronized,
                    pendingEvents = it.pending.size,
                )
            }
        }
        val transportState = transport.state.value
        _state.value = when {
            !started.get() -> SyncState.Stopped
            transportState is TransportState.Unavailable -> SyncState.Unavailable(transportState.reason)
            else -> SyncState.Running(transportState, peers, lastError)
        }
    }
}
