package com.resqroute.domain.sync

import com.resqroute.domain.hazard.DeviceId
import com.resqroute.domain.hazard.EventId
import com.resqroute.domain.hazard.HazardMutation

/** Opaque per-connection handle. A Nearby endpoint id is not a device identity. */
@JvmInline
value class PeerSessionId(val value: String)

/**
 * Versioned wire protocol (blueprint section 11).
 *
 * Small, explicit JSON. Timestamps are audit fields only: they never reopen a
 * road and never decide which report wins.
 */
object PeerProtocol {

    /**
     * Protocol revision.
     *
     * **1 → 2 (XPH-78).** An event now carries the two observation axes as their own fields
     * ([FIELD_LEVEL], [FIELD_PASSABILITY]) instead of implying them from [FIELD_REASON]. v1 sent a
     * reason only, and its meaning was "hard exclusion" — which a receiving v2 device still reads
     * correctly, but only because [PeerMessageCodec] maps a reason-only event through the same
     * legacy mapping that reads a v1 stored row (ADR 0001 D2).
     *
     * The bump is not cosmetic. A report that states `KNEE` + `CAUTION` closes no road and is
     * traversable at a finite risk cost, so a v1 receiver that ignored the axes would treat the
     * event as its reason implies and **exclude a road the reporter said was usable** — the wrong
     * direction, and silently. So a mismatched build is refused rather than half-understood: a v1
     * payload fails [PeerMessageCodec.decode] as [DecodeResult.Unsupported], and
     * `HazardSyncCoordinator` fails the session on it. Neither side ever reads a message it only
     * partly understands.
     */
    const val VERSION = 2

    /** Application payload limit, well below Nearby's 32 KB bytes limit. */
    const val MAX_PAYLOAD_BYTES = 8 * 1024

    const val MAX_EVENTS_PER_BATCH = 32

    const val MIN_ID_LENGTH = 8
    const val MAX_ID_LENGTH = 64

    /*
     * Event field names. Pinned: a field name is part of the protocol, so renaming one is a
     * protocol change rather than a refactor - exactly like the enum codes it carries.
     */

    /** Provenance only; never a severity and never a cost. */
    const val FIELD_REASON = "reason"

    /** The water-level axis, carrying a [com.resqroute.domain.hazard.WaterLevelCategory] code. */
    const val FIELD_LEVEL = "level"

    /** The passability axis, carrying a [com.resqroute.domain.hazard.PassabilityStatus] code. */
    const val FIELD_PASSABILITY = "passability"
}

/** Messages that cross the wire. */
sealed interface PeerMessage {

    /** Compatibility handshake: protocol + dataset + incident must all match. */
    data class Hello(
        val protocolVersion: Int,
        val deviceId: DeviceId,
        val datasetHash: String,
        val incidentId: String,
    ) : PeerMessage

    /** One bounded batch of hazard reports. */
    data class EventBatch(val events: List<HazardMutation>) : PeerMessage

    /** Per-event acknowledgement, sent only after the receiver's Room commit. */
    data class Ack(val eventId: EventId) : PeerMessage

    /** Replay finished for this session. */
    data object SyncComplete : PeerMessage
}

/** A decoded message plus the session it arrived on. */
data class ReceivedPeerMessage(
    val sessionId: PeerSessionId,
    val message: PeerMessage,
)

/** Independent radio operation state; advertising success does not imply discovery. */
enum class RadioOperation { STOPPED, STARTING, ACTIVE, FAILED }

/** Transport-level state, owned by the adapter. */
sealed interface TransportState {

    data object Stopped : TransportState

    data class Running(
        val advertising: RadioOperation,
        val discovery: RadioOperation,
    ) : TransportState

    data class Unavailable(val reason: String) : TransportState
}

/** Protocol-level view of one connected peer. */
data class PeerConnection(
    val sessionId: PeerSessionId,
    val endpointName: String,
    val compatible: Boolean,
    val synchronized: Boolean,
    val pendingEvents: Int,
)

/** A connection waiting for the user to compare the authentication code. */
data class AuthRequest(
    val sessionId: PeerSessionId,
    val endpointName: String,
    val authCode: String,
)

/**
 * Everything the UI needs to describe peer synchronization.
 *
 * A failure here never changes local routing: [SyncState] is independent of the
 * dataset and hazard state.
 */
sealed interface SyncState {

    data object Stopped : SyncState

    data class Running(
        val transport: TransportState,
        val peers: List<PeerConnection>,
        val lastError: String? = null,
    ) : SyncState

    data class Unavailable(val reason: String) : SyncState
}
