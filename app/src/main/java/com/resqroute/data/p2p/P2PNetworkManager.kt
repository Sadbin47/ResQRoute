package com.resqroute.data.p2p

import com.resqroute.domain.sync.PeerSessionId
import com.resqroute.domain.sync.TransportState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Transport-level events the coordinator reacts to.
 *
 * Kept at the bytes/connection level: decoding, validation and hazard semantics
 * belong to the coordinator and its codec, so a fake transport can drive the
 * whole protocol in JVM tests.
 */
sealed interface TransportEvent {

    /** A peer was discovered; the coordinator decides whether to connect. */
    data class EndpointFound(
        val sessionId: PeerSessionId,
        val endpointName: String,
    ) : TransportEvent

    /** A previously discovered peer disappeared. */
    data class EndpointLost(val sessionId: PeerSessionId) : TransportEvent

    /** An incoming connection request. Acceptance is always an explicit user action. */
    data class ConnectionRequested(
        val sessionId: PeerSessionId,
        val endpointName: String,
        val authCode: String,
        val incoming: Boolean,
    ) : TransportEvent

    data class Connected(
        val sessionId: PeerSessionId,
        val endpointName: String,
    ) : TransportEvent

    data class Disconnected(val sessionId: PeerSessionId) : TransportEvent

    /**
     * A recoverable transport/session problem. The session is dropped so
     * reconnect reconciliation can recover; messages are never silently lost.
     */
    data class Failure(
        val stage: String,
        val message: String,
        val sessionId: PeerSessionId? = null,
    ) : TransportEvent
}

/** One received bytes payload plus its session. */
class IncomingPayload(
    val sessionId: PeerSessionId,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is IncomingPayload && sessionId == other.sessionId && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * sessionId.hashCode() + bytes.contentHashCode()
}

/**
 * Real transport contract (blueprint section 7).
 *
 * The implementation owns radios, endpoints and bytes; it never knows about
 * hazard semantics. [incoming] must be a bounded stream: on overflow the
 * transport reports a [TransportEvent.Failure] and drops the session instead of
 * silently discarding commands.
 */
interface P2PNetworkManager {

    /** Advertising/discovery/availability state, independent per operation. */
    val state: StateFlow<TransportState>

    /** Connection lifecycle for the coordinator. */
    val events: Flow<TransportEvent>

    /** Bounded incoming payload stream; exactly one consumer (the coordinator). */
    val incoming: Flow<IncomingPayload>

    /** Begin advertising and discovery for the foreground session. */
    fun start()

    /** Stop advertising, discovery and all endpoints; release session state. */
    fun stop()

    /** Request a connection to a discovered endpoint (one attempt per endpoint). */
    fun connect(sessionId: PeerSessionId)

    /** Accept an incoming connection after the user confirmed the code. */
    fun accept(sessionId: PeerSessionId)

    /** Reject an incoming connection. */
    fun reject(sessionId: PeerSessionId)

    /** Close an established session (protocol violation or unrecoverable error). */
    fun disconnect(sessionId: PeerSessionId)

    /** Send bytes. `true` means transport acceptance, not remote commit. */
    fun send(sessionId: PeerSessionId, bytes: ByteArray): Boolean
}
