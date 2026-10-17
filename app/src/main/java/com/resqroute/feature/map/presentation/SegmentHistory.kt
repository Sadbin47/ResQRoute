package com.resqroute.feature.map.presentation

import com.resqroute.core.model.SegmentId
import com.resqroute.domain.hazard.ClosureFreshness
import com.resqroute.domain.hazard.DeviceId
import com.resqroute.domain.hazard.FreshnessPolicy
import com.resqroute.domain.hazard.ObservationSource
import com.resqroute.domain.hazard.ObservedReport
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory
import java.util.TimeZone

/**
 * One report in a segment's history, with the time facts a reader needs (XPH-71).
 *
 * It carries the domain report itself rather than copying its axes out, so the wording layer can
 * render it through the same `HazardSupportCopy` functions the disagreement list and the report
 * picker already use: one vocabulary for "what did somebody say", wherever it appears.
 *
 * The time facts are all derived from the **claimed** observation time and the local receipt stamp,
 * with the same rules as every other age in the app: the reading is `HH:mm` in the injected zone
 * ([TimeBands]), the band is context, the age is measured from receipt (ADR 0005 §6.2) and
 * [timeVerified] is false when the claim is not believable (ADR 0005 §7.1) — an unverified claim is
 * labelled, never repaired into a plausible-looking time.
 */
data class HistoryEntry(
    val report: ObservedReport,
    /** `HH:mm` local reading of the claimed observation time. */
    val localTime: String,
    /** Which half of the civil day that local time falls in. Context only. */
    val band: TimeBand,
    /** Age from this device's receipt of the report; null when there is no report to age. */
    val ageMillis: Long?,
    /** False when the claimed observation time is not believable, or the age cannot be established. */
    val timeVerified: Boolean,
    /** Evidence freshness, using ADR 0005's display-only vocabulary. */
    val freshness: ClosureFreshness,
) {

    /** The claimed observation time — what the row is ordered and banded by. */
    val occurredAtEpochMillis: Long get() = report.occurredAtEpochMillis

    /**
     * True when this report states a depth at all.
     *
     * False for a row this build cannot read (no axis may be invented for it) and for a report that
     * genuinely stated no depth.
     */
    val statesADepth: Boolean get() = report.observation?.waterLevel?.statesSomething == true

    /**
     * True when this report may take part in a trend: it states a depth **and** its time can be
     * ordered against another ([timeVerified]).
     *
     * Three things are excluded, each for its own reason. An unreadable row states no depth to
     * compare. A report whose *claim* is not believable cannot be sorted on a value the app has
     * already refused to trust (`FreshnessPolicy.isClaimedTimePlausible`). And a row whose age from
     * receipt is negative means this device's own clock moved backwards, so it cannot say when
     * anything happened. In every case the conservative answer is no trend, which is why [timeVerified]
     * is the one flag for both ordering and display rather than two that could disagree.
     */
    val isComparable: Boolean get() = statesADepth && timeVerified

    /** Ordinal depth for comparison; null when this states no depth. */
    val depthRank: Int? get() = if (statesADepth) report.observation?.waterLevel?.depthRank else null

    /**
     * The stated depth, or [WaterLevelCategory.UNKNOWN] when the row states none.
     *
     * Convenience for rendering only. It invents nothing: a caller that must not present an unstated
     * axis as a value filters on [statesADepth] first, which is exactly what the wording layer does.
     */
    val waterLevel: WaterLevelCategory
        get() = report.observation?.waterLevel ?: WaterLevelCategory.UNKNOWN
}

/** The direction a water-level trend ran between two comparable reports. */
enum class TrendDirection {

    /** The newer report stated a deeper level than the older one. */
    RISING,

    /** The newer report stated a shallower level than the older one. */
    FALLING,

    /** Both reports stated the same level. */
    STEADY,
}

/**
 * What the log says about how this segment's reported water level changed (XPH-71).
 *
 * [Insufficient] is a first-class answer, not an error: most roads will have one report, and the
 * honest thing to show is that there is no history to compare rather than a trend invented from a
 * single observation.
 */
sealed interface ReportedTrend {

    /** Fewer than two reports which can be ordered and compared. */
    data object Insufficient : ReportedTrend

    /** Two comparable reports, older first in the log's own order. */
    data class Change(
        val older: HistoryEntry,
        val newer: HistoryEntry,
        val direction: TrendDirection,
    ) : ReportedTrend
}

/**
 * A segment's reported history, oldest claimed time first (XPH-71).
 *
 * ## What this is
 *
 * Every committed report for one segment, in the order they claim to have happened, plus the one
 * sentence the log can support about how the reported depth changed. It exists because a single
 * derived maximum cannot tell a person whether a report is a one-off, a repeat or a situation that
 * has changed — the question the plan's Phase 8 exists to answer.
 *
 * ## The order
 *
 * [entries] follows [SegmentObservationState.reports], which is sorted by `(claimed time, event
 * id)`. That order is deliberate and total: two reports claiming the same instant still have one
 * deterministic order, and a device with a skewed clock can reorder the list but can never change a
 * verdict ([SegmentObservationState]'s own contract). Oldest-first is chosen because a history read
 * top-to-bottom should move forward in time, and because it makes the trend sentence's `→` mean
 * exactly what it looks like.
 *
 * The list is the **whole log**, not the evidence in force: a closure that an explicit clearance
 * resolved is still part of what happened on that road and stays visible here (ADR 0005 §5.4, §8
 * requirement 6). What changed is only whether it still *excludes* the road, which is
 * `SegmentObservationState`'s job, not this one's.
 *
 * ## The trend rule, stated once
 *
 * A report is **comparable** when it states a depth (`statesADepth` — so an unreadable row and a
 * depth-less report are out) **and** its claimed time is believable ([HistoryEntry.isComparable] —
 * an unbelievable time cannot be ordered). Then:
 *
 * > Take the newest comparable report, and the newest comparable report whose claimed time is
 * > **strictly older**. If either does not exist, the trend is [ReportedTrend.Insufficient].
 * > Otherwise compare their depths: deeper → [TrendDirection.RISING], shallower →
 * > [TrendDirection.FALLING], equal → [TrendDirection.STEADY].
 *
 * Four consequences, each deliberate and each tested:
 *
 *  * **Two observations at the same instant are not a trend.** A trend is a change *over time*, so
 *    it needs two distinct instants; reports that share a timestamp cannot be ordered against each
 *    other and yield [ReportedTrend.Insufficient] rather than an arbitrary direction.
 *  * **One comparable report among many is still insufficient.** The others must state depths and
 *    have believable times, or there is nothing to compare against.
 *  * **Only the newest pair is compared.** Three reports do not make an average, a rate or a
 *    slope: the app shows what the last two comparable reports said. A rate would be a fabricated
 *    measurement over untrusted timestamps.
 *  * **Only the water axis trends.** Passability has no ordinal depth — `AVOID` is not "deeper"
 *    than `CAUTION` — so a verdict change is shown per row and never worded as a rise or a fall.
 *
 * ## What it is not
 *
 * Not a prediction, and not evidence about the road now. It says what reports said and when. The
 * timestamps are the reporters' untrusted claims, so a wrong clock can invert a direction: the
 * trend is therefore about the *reports*, the wording commits to nothing else, and any row whose
 * claim is not credible is labelled rather than trusted.
 */
data class SegmentHistory(
    val segmentId: SegmentId,
    /** Every committed report for the segment, oldest claimed time first. */
    val entries: List<HistoryEntry>,
    /** The direction the newest comparable pair moved, or why it cannot be said. */
    val trend: ReportedTrend,
    /** Distinct origin device IDs (ADR 0001 D5) — a count of devices, never of people. */
    val supportCount: Int,
    /**
     * Those device IDs, attributed by how *this* device came to know each report.
     *
     * Carried so the history block renders support through `HazardSupportCopy`, the same functions
     * the sheet's other report lines use, instead of a second copy of the same sentence.
     */
    val reportingDeviceIdsBySource: Map<ObservationSource, Set<DeviceId>>,
    /** The zone the local readings are in, and its offset, so the rule can be stated on screen. */
    val zoneId: String,
    val zoneOffsetText: String,
) {

    /** True when anything at all has been reported for this segment. */
    val hasAnyReport: Boolean get() = entries.isNotEmpty()

    /** True when at least one row could not be read by this build (it still excludes the road). */
    val hasUnreadableReports: Boolean get() = entries.any { it.report.observation == null }

    /** True when the log supports a direction. */
    val hasTrend: Boolean get() = trend is ReportedTrend.Change

    companion object {

        /**
         * Builds a segment's history.
         *
         * [now] and [zone] are injected: nothing here reads a clock or the device's timezone, so
         * the result is a pure function of `(state, now, zone)` and the tests are deterministic
         * rather than dependent on when or where they run (ADR 0005 §6.1).
         */
        fun of(state: SegmentObservationState, now: Long, zone: TimeZone): SegmentHistory {
            val entries = state.reports.map { entryFor(it, now, zone) }
            return SegmentHistory(
                segmentId = state.segmentId,
                entries = entries,
                trend = trendOf(entries),
                supportCount = state.supportCount,
                reportingDeviceIdsBySource = state.reportingDeviceIdsBySource,
                zoneId = zone.id,
                zoneOffsetText = TimeBands.offsetText(zone, now),
            )
        }

        private fun entryFor(report: ObservedReport, now: Long, zone: TimeZone): HistoryEntry {
            // One row's freshness, through the same policy every other age in the app uses, so a
            // row here and the same report on a route cannot disagree about how old it is.
            val freshness = FreshnessPolicy.reportFreshness(listOf(report), now)
            return HistoryEntry(
                report = report,
                localTime = TimeBands.clockText(report.occurredAtEpochMillis, zone),
                band = TimeBands.bandOf(TimeBands.hourOfDay(report.occurredAtEpochMillis, zone)),
                ageMillis = freshness.ageMillis,
                // Both halves of "we know when this happened": the claim is believable, and the age
                // we can compute from our own receipt is not negative. Either failure and the row
                // shows "time not verified" instead of a time, a band or a trend.
                timeVerified = freshness.ageMillis != null &&
                    freshness.ageMillis >= 0L &&
                    freshness.claimedTimeVerified,
                freshness = freshness.label,
            )
        }

        private fun trendOf(entries: List<HistoryEntry>): ReportedTrend {
            // `entries` is oldest-first, so filtering preserves order and `.last()` is the newest.
            val comparable = entries.filter { it.isComparable }
            val newest = comparable.lastOrNull() ?: return ReportedTrend.Insufficient
            val older = comparable.lastOrNull { it.occurredAtEpochMillis < newest.occurredAtEpochMillis }
                ?: return ReportedTrend.Insufficient

            val newerDepth = newest.depthRank ?: return ReportedTrend.Insufficient
            val olderDepth = older.depthRank ?: return ReportedTrend.Insufficient
            val direction = when {
                newerDepth > olderDepth -> TrendDirection.RISING
                newerDepth < olderDepth -> TrendDirection.FALLING
                else -> TrendDirection.STEADY
            }
            return ReportedTrend.Change(older = older, newer = newest, direction = direction)
        }
    }
}
