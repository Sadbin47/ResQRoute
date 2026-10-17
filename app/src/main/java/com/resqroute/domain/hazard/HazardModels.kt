package com.resqroute.domain.hazard

import com.resqroute.core.graph.GraphBlocker
import com.resqroute.core.model.DatasetIdentity
import com.resqroute.core.model.SegmentId

/** Stable installation identity; never derived from a Nearby endpoint id. */
@JvmInline
value class DeviceId(val value: String)

/** Persistent identity of one hazard report; the ACK and dedup key. */
@JvmInline
value class EventId(val value: String)

/**
 * What kind of report this is.
 *
 * Provenance and audit only: it is never a severity and never a cost. Whether a
 * segment may be traversed is decided by the observation's [PassabilityStatus]; see
 * [RoadObservation].
 *
 * Historical note: in v1 **both** values were hard exclusions — the effective
 * blocked set was a `SELECT DISTINCT segmentId` over every committed report with no
 * reason filter (`data/local/HazardDao.kt:27-44`). v1 reports are therefore still
 * exclusions, and [RoadObservation.fromLegacy] is the single mapping that reads
 * them (ADR 0001 D2). Reclassifying historical reports is a separate, auditable
 * decision and never a side effect of a migration.
 *
 * The codes are pinned: they are already persisted and already on the wire, so
 * renaming one is a protocol change.
 */
enum class HazardReason(val code: String) {
    WATERLOGGED("WATERLOGGED"),
    BLOCKED("BLOCKED"),
    ;

    companion object {
        fun fromCode(code: String): HazardReason? = entries.firstOrNull { it.code == code }
    }
}

/** The dataset + incident a hazard report belongs to. */
data class HazardContext(
    val dataset: DatasetIdentity,
    val incidentId: String,
)

/**
 * One immutable hazard report.
 *
 * [occurredAtEpochMillis] is the origin's claimed observation time. It is audit
 * data only: it never decides ordering, safety or which report wins.
 *
 * [observation] is what the reporter stated about the road, across the two axes
 * decided in ADR 0001 (D1). It defaults to the v1 interpretation of [reason], so a
 * report that carries only a reason keeps exactly the v1 meaning: excluded from
 * routing, with a water level that was never recorded. Callers that have real axis
 * evidence supply it here; everything downstream (persistence, dedup, replay) then
 * carries it unchanged.
 */
data class HazardMutation(
    val eventId: EventId,
    val originDeviceId: DeviceId,
    val context: HazardContext,
    val segmentId: SegmentId,
    val reason: HazardReason,
    val occurredAtEpochMillis: Long,
    val observation: RoadObservation = RoadObservation.fromLegacy(reason),
)

/**
 * Immutable, revisioned view of the committed blocked set.
 *
 * Implements [GraphBlocker], so it is passed straight into the Phase 4 routing
 * snapshot. A dense lookup array is built once so `isBlockedSegment` is O(1)
 * during A* relaxation; the `Set` is the authoritative value.
 */
class HazardSnapshot(
    val context: HazardContext,
    /** Local, process-scoped publication revision; bumps only when the set changes. */
    val revision: Long,
    blockedSegments: Set<SegmentId>,
) : GraphBlocker {

    val blockedSegments: Set<SegmentId> = blockedSegments.toSet()

    private val blockedLookup: BooleanArray = BooleanArray(
        (this.blockedSegments.maxOfOrNull { it.value } ?: -1) + 1,
    ).also { lookup ->
        for (segment in this.blockedSegments) {
            if (segment.value >= 0) lookup[segment.value] = true
        }
    }

    override fun isBlockedSegment(segmentIndex: Int): Boolean =
        segmentIndex >= 0 && segmentIndex < blockedLookup.size && blockedLookup[segmentIndex]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HazardSnapshot) return false
        return context == other.context &&
            revision == other.revision &&
            blockedSegments == other.blockedSegments
    }

    override fun hashCode(): Int {
        var result = context.hashCode()
        result = 31 * result + revision.hashCode()
        result = 31 * result + blockedSegments.hashCode()
        return result
    }

    override fun toString(): String =
        "HazardSnapshot(incident=${context.incidentId}, revision=$revision, blocked=${blockedSegments.size})"
}

/** Restoration/publication state of hazard truth. */
sealed interface HazardState {

    /** Reports are being read from Room; routing must not run yet. */
    data object Restoring : HazardState

    /**
     * The committed blocked set, together with the derived per-segment state it was
     * computed from.
     *
     * The two travel as **one value on purpose.** A route is computed from the blocker
     * (which segments are closed) and from the observation state (which segments are
     * reported risky), and the publication guard checks the revision that came with this
     * snapshot. If a caller read the blocker from one derivation and the observations from
     * another, it could compute a route against a mixture of two revisions and still pass
     * the guard — so they are published atomically or not at all.
     */
    data class Ready(
        val snapshot: HazardSnapshot,
        /** Derived per-segment state for the same rows that produced [snapshot]. */
        val observationStates: Map<SegmentId, SegmentObservationState> = emptyMap(),
    ) : HazardState

    /** Durable hazard truth is unusable; hazard-aware routing is unavailable. */
    data class Unavailable(val message: String) : HazardState
}

/** Outcome of one repository mutation command. */
sealed interface MutationResult {

    /**
     * The event is durably committed.
     *
     * [effectiveStateChanged] is true only when this report **changed which segments the
     * router must avoid**: it excludes traversal and the segment was not already excluded.
     * A report that does not exclude is committed, replayed and displayed, but changes no
     * route, so this is false for it (XPH-69).
     */
    data class Accepted(
        val event: HazardMutation,
        val effectiveStateChanged: Boolean,
    ) : MutationResult

    /** The identical event already exists. Safe to ACK; no state change. */
    data object Duplicate : MutationResult

    /** Validation failed; nothing was written. */
    data class Rejected(val reason: String) : MutationResult

    /** The write failed. No commit happened, so no ACK may be sent. */
    data class PersistenceFailure(val message: String) : MutationResult
}
