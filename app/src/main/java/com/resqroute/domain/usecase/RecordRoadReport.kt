package com.resqroute.domain.usecase

import com.resqroute.core.model.SegmentId
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.HazardRepository
import com.resqroute.domain.hazard.MutationResult
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.WaterLevelCategory

/**
 * Records a road report a person made, through the single repository ingress (XPH-70).
 *
 * Supersedes `MarkRoadBlocked`, whose two-button Blocked/Waterlogged model could not express a depth
 * or a verdict (ADR 0001 D1). A report is now two independent axes plus a provenance reason, and this
 * is the domain entry point the reporting UI invokes: identity, validation, provenance and
 * persistence all stay inside the repository.
 *
 * ## The reason is derived, not asked for
 *
 * [HazardReason] records *what kind of report this is* — provenance only, never a severity
 * (ADR 0001 D1). Since the two-button choice is gone, the reason is derived from what the observer
 * actually said about the road: a report that finds it impassable is a `BLOCKED` report, and any
 * other is a `WATERLOGGED` one. Asking a person to classify their own report would be a question that
 * no longer decides anything, and answering it wrongly would only corrupt provenance.
 *
 * ## What is deliberately not rejected here
 *
 * Validation lives in the repository, not in this use case, so there is exactly one place that
 * decides whether a combination is representable. A contradictory report comes back as
 * [MutationResult.Rejected] with the observer-readable reason, and the UI shows it.
 */
class RecordRoadReport(
    private val repository: HazardRepository,
) {

    /**
     * Reports what was observed on [segmentId].
     *
     * [waterLevel] = [WaterLevelCategory.UNKNOWN] states **no depth claim**; it never excludes a road
     * by itself. Whether the road is excluded is decided solely by [passability]: only
     * [PassabilityStatus.IMPASSABLE] removes it from the search (ADR 0002 §1).
     */
    suspend operator fun invoke(
        segmentId: SegmentId,
        waterLevel: WaterLevelCategory,
        passability: PassabilityStatus,
    ): MutationResult = repository.recordLocal(
        segmentId = segmentId,
        reason = reasonFor(passability),
        waterLevel = waterLevel,
        passability = passability,
    )

    companion object {

        /**
         * Provenance for a report, from the traversal verdict the observer gave.
         *
         * Never a severity and never a cost — it exists so the log can say what kind of report this
         * was, and so a `BLOCKED` report keeps its v1 meaning. Public because the UI needs the same
         * answer for the pending marker, and two copies of this rule would be one too many.
         */
        fun reasonFor(passability: PassabilityStatus): HazardReason =
            if (passability == PassabilityStatus.IMPASSABLE) {
                HazardReason.BLOCKED
            } else {
                HazardReason.WATERLOGGED
            }
    }
}
