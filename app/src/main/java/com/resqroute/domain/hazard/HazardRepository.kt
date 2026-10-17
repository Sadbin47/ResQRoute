package com.resqroute.domain.hazard

import com.resqroute.core.model.SegmentId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The **single mutation ingress** for hazard state.
 *
 * Every report — a local long-press and a peer message alike — enters through
 * this interface. Implementations validate, serialize and commit transactionally,
 * deduplicate, and only then publish the effective blocked set. Routing and the
 * UI observe [state]; they never write hazard state themselves.
 *
 * A report is never published as committed before it is persisted, so there is
 * no path that could acknowledge a write that did not happen.
 */
interface HazardRepository {

    /** Committed blocked set, or [HazardState.Restoring]/[HazardState.Unavailable]. */
    val state: StateFlow<HazardState>

    /**
     * Restores durable hazard truth for [context] and starts publishing the
     * effective blocked set. Suspends until the first snapshot is published, so
     * callers can gate routing on [state] being [HazardState.Ready].
     *
     * Idempotent for the same context.
     */
    suspend fun initialize(context: HazardContext)

    /**
     * Records a locally observed block. Identity and provenance are generated
     * inside the repository, so a UI never fabricates an event id.
     */
    suspend fun blockLocal(
        segmentId: SegmentId,
        reason: HazardReason,
    ): MutationResult

    /**
     * Records a locally observed report, stating both axes (XPH-70).
     *
     * This is what the reporting UI calls. [blockLocal] remains the "it is blocked" shortcut whose
     * meaning is unchanged; this entry point exists because a report is no longer a binary choice —
     * a depth and a verdict are separate observations (ADR 0001 D1), and a report may carry one,
     * the other, or both.
     *
     * Validation happens inside the implementation, at the ingress, and an impossible combination is
     * **[rejected rather than normalised]** (ADR 0002 R1/R2): the observer is asked to correct the
     * report instead of the app guessing. [waterLevel] = [WaterLevelCategory.UNKNOWN] states no depth
     * and is never interpreted as a verdict of its own.
     */
    suspend fun recordLocal(
        segmentId: SegmentId,
        reason: HazardReason,
        waterLevel: WaterLevelCategory,
        passability: PassabilityStatus,
    ): MutationResult

    /** Ingests a peer report. Identical validation path to [blockLocal]. */
    suspend fun ingestRemote(mutation: HazardMutation): MutationResult

    /**
     * Cold enumeration of durably committed events for reconciliation.
     *
     * This is not a live command stream: reconnection runs another enumeration,
     * and newly committed events are sent by the Phase 8 coordinator.
     */
    fun persistedEvents(context: HazardContext): Flow<HazardMutation>
}
