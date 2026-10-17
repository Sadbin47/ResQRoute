package com.resqroute.domain.hazard

import com.resqroute.core.model.SegmentId

/**
 * What one committed row resolved to.
 *
 * [Observed] is a readable row: its two axes were validated by [RoadObservation.create]
 * or mapped by [RoadObservation.fromLegacy], so the axes are trustworthy.
 *
 * [Unreadable] is a row whose reason code this build does not recognise — a newer
 * build's code, or a damaged row. It **must still exclude** (ADR 0004 §2: v1 had no
 * reason filter, so an unknown reason was still an exclusion), but no water level and
 * no passability may be invented for it. Keeping the raw code preserves provenance and
 * lets the aggregate distinguish "reported impassable" from "cannot be read here".
 */
sealed interface ReportContent {

    /** A readable row, with both axes resolved. */
    data class Observed(val observation: RoadObservation) : ReportContent

    /**
     * An explicit clearance (ADR 0005 §5): the observer states the road **can be used**, which resolves
     * closures of the same segment that are strictly older and credible ([ReopeningPolicy]).
     *
     * It is a distinct content kind rather than a flag, so a normal observation cannot be promoted into
     * one by mistake or by a later refactor: the only way to obtain a [Clearance] is `Clearance.create`,
     * and nothing in persistence, the wire codec or the UI can produce one from a row.
     *
     * A clearance never excludes traversal — its verdict asserts usability by construction.
     */
    data class Cleared(val clearance: Clearance) : ReportContent

    /** A row this build cannot interpret. Fails closed; invents no axes. */
    data class Unreadable(val reasonCode: String) : ReportContent
}

/**
 * One committed report, as the aggregation sees it.
 *
 * Both timestamps are carried, unreduced, because *which* observation a time belongs to
 * is the whole point: [receivedAtEpochMillis] is this device's local stamp and is the
 * only freshness input no peer can influence (ADR 0005 §6.2), while
 * [occurredAtEpochMillis] is the origin's unverified claim and is audit/display only.
 *
 * [source] records how *this* device came to know the report. It is a property of the
 * report rather than of the row, because the same row is first-hand on the device that
 * created it and relayed everywhere else.
 */
data class ObservedReport(
    val eventId: EventId,
    val deviceId: DeviceId,
    val source: ObservationSource,
    val segmentId: SegmentId,
    val occurredAtEpochMillis: Long,
    val receivedAtEpochMillis: Long,
    val content: ReportContent,
) {

    /**
     * The axes this report states, or null when the row is [ReportContent.Unreadable].
     *
     * A clearance states axes exactly like any other observation, which is what lets it take part in
     * the severity reductions instead of being a second, parallel kind of evidence (ADR 0005 §8
     * requirement 7).
     */
    val observation: RoadObservation?
        get() = when (val c = content) {
            is ReportContent.Observed -> c.observation
            is ReportContent.Cleared -> c.clearance.observation
            is ReportContent.Unreadable -> null
        }

    /**
     * True when this report is an explicit clearance.
     *
     * Per-device support and every severity reduction read this as an ordinary statement about the
     * road; only [SegmentObservationState]'s resolution rule reads it as a clearance.
     */
    val isClearance: Boolean get() = content is ReportContent.Cleared

    /**
     * True when this report removes its segment from the search.
     *
     * An unreadable report fails closed (ADR 0004 §2): it excluded in v1 and it still
     * does. It is never downgraded to a finite penalty, because nothing about it is known.
     */
    val excludesTraversal: Boolean
        get() = when (val c = content) {
            is ReportContent.Observed -> c.observation.excludesTraversal
            // A clearance asserts usability, structurally: `Clearance.create` accepts only
            // passable/caution verdicts. It is therefore never an exclusion.
            is ReportContent.Cleared -> false
            is ReportContent.Unreadable -> true
        }
}

/**
 * Everything the app knows about one segment, derived from its committed reports.
 *
 * ## The derivation rule, in one paragraph
 *
 * A segment is **excluded** when any committed report excludes traversal — a resolved
 * `IMPASSABLE` observation, or a row this build cannot read, which fails closed. Every
 * other combination is traversable and may later attract a finite penalty. The reported
 * **passability** is the most restrictive verdict any report stated, and the reported
 * **water level** is the deepest category any report stated; a report that states nothing
 * on an axis never makes a segment look better *or* worse than one that does, so `UNKNOWN`
 * is reported only when no report stated that axis. **Support** is the number of distinct
 * origin device IDs — never "people", never a probability — attributed by how this device
 * came to know each report. Where the reports differ, **both views are kept** with their own
 * times and sources and nothing is averaged: [hasDisagreement] and
 * [hasTraversalDisagreement] say what differs. Nothing here reads the clock, so the same
 * report set always produces the same state; the aggregate is a pure function of the event
 * set, which is what makes replay and reordering idempotent.
 *
 * ## Reopening, and why it is the one exception to "grow-only"
 *
 * With no clearance in the log the reduction above is grow-only: adding a report can only ever add an
 * exclusion, a deeper level or a more restrictive verdict, never remove one. That is what makes the
 * state a maximum over severity plus a distinct-device count over a grow-only event set — convergent,
 * replayable, and immune to a skewed clock.
 *
 * A road leaves the excluded set **only** through [resolvedClosures]: an explicit, validated clearance
 * whose claimed time is strictly newer than the closure's and credible on both sides
 * ([ReopeningPolicy]). An ordinary newer report never resolves anything, however fresh and however many
 * devices sent it, and no timestamp can open a road on its own. The rule is a property of the *set*, not
 * of arrival order, so it keeps the convergence property rather than breaking it.
 *
 * A resolved closure is **superseded evidence, not deleted evidence**: it stays in [reports] for history
 * and audit, and stops contributing to the reductions — which is forced as well as chosen, because the
 * invariant `excludesTraversal == (passability == IMPASSABLE)` would otherwise be violated by a segment
 * that is traversable and still reports "impassable".
 *
 * ## Why there is no "latest timestamp"
 *
 * There is deliberately no `newestOccurredAt`, `latestTimestamp` or similar member. The
 * canonical plan (§4.6) rejects pairing a maximum severity with the newest timestamp of
 * *any* report on the segment: that makes an old severe report look freshly confirmed.
 * Any question of the form "how old is the impassable verdict?" is answered by [reports]
 * or by the `reportsWith…` accessors, which return the *reports that actually carry* the
 * value, each with its own timestamps. Severity and time are never separable here.
 *
 * ## Determinism
 *
 * [reports] is sorted by `(occurredAtEpochMillis, eventId)`, a total order that does not
 * depend on insertion or arrival order. Reported values are reductions over the set, so
 * they do not depend on order either. A skewed claimed time can therefore change the
 * *order* of the list but never a verdict, an exclusion or a count.
 */
class SegmentObservationState(
    val segmentId: SegmentId,
    reports: List<ObservedReport>,
) {

    /** The segment's reports, oldest claimed time first, ties broken by event id. */
    val reports: List<ObservedReport> = reports.sortedWith(REPORT_ORDER)

    /** Every explicit clearance in the log, oldest claimed time first. */
    val clearances: List<ObservedReport> = this.reports.filter { it.isClearance }

    /** True when any clearance exists for this segment. */
    val hasClearance: Boolean get() = clearances.isNotEmpty()

    /**
     * The closures a credible, strictly newer clearance has resolved (ADR 0005 §5.1).
     *
     * Only a **readable** closure is resolvable. A row this build cannot read excludes by failing closed
     * (ADR 0004 §2), which is a conservative default rather than a statement about the road — and the
     * app cannot claim a clearance has superseded evidence whose meaning it never understood. Such a row
     * therefore keeps its segment closed until it can be read, and [hasUnreadableReports] says so.
     */
    val resolvedClosures: List<ObservedReport> = run {
        val claims = clearances.map { it.occurredAtEpochMillis }
        this.reports.filter { report ->
            report.content is ReportContent.Observed &&
                report.excludesTraversal &&
                claims.any { ReopeningPolicy.resolves(it, report.occurredAtEpochMillis) }
        }
    }

    private val resolvedEventIds: Set<EventId> = resolvedClosures.mapTo(mutableSetOf()) { it.eventId }

    /**
     * Every report except a resolved closure: the evidence currently in force.
     *
     * The reductions below — exclusion, verdict, water level, distinct-device support — read this set,
     * so a resolved closure neither closes the road nor colours its verdict nor keeps amplifying its
     * risk. [reports] remains the full log.
     */
    val effectiveReports: List<ObservedReport> =
        this.reports.filterNot { it.eventId in resolvedEventIds }

    /**
     * The clearance that resolved [closure], or null when it is not resolved.
     *
     * The **earliest** clearance that resolves it, which is the one that actually did: a later clearance
     * of the same segment supersedes nothing about the closure, it merely also resolves it. Deterministic
     * — the candidates are ordered by `(claimed time, event id)` — so a display can name the act that
     * reopened the road without depending on arrival order.
     */
    fun clearingClearance(closure: ObservedReport): ObservedReport? =
        if (closure.eventId !in resolvedEventIds) {
            null
        } else {
            clearances.firstOrNull {
                ReopeningPolicy.resolves(it.occurredAtEpochMillis, closure.occurredAtEpochMillis)
            }
        }

    /**
     * Distinct origin device IDs that reported on this segment (ADR 0001 D5).
     *
     * Sorted by id so iteration is deterministic; a relayed copy or a re-sent duplicate of
     * one device's event contributes one entry, because it is the same device id.
     */
    val reportingDeviceIds: Set<DeviceId> = LinkedHashSet(
        effectiveReports.map { it.deviceId }.distinct().sortedBy { it.value },
    )

    /** Size of [reportingDeviceIds]; "reports from N distinct device IDs", never a probability. */
    val supportCount: Int get() = reportingDeviceIds.size

    /**
     * The same device ids, attributed by how this device came to know each report.
     *
     * Every [ObservationSource] is present, in enum order, so a caller can render "0 official"
     * without a null check, and each set is sorted by id so iteration is deterministic.
     *
     * This is **attribution, not weight**: it may be shown ("2 relayed"), and it must never be
     * turned into a percentage, a confidence or a score. See [ObservationSource] and ADR 0001 D5.
     */
    val reportingDeviceIdsBySource: Map<ObservationSource, Set<DeviceId>> = LinkedHashMap(
        ObservationSource.entries.associateWith { source ->
            effectiveReports.asSequence()
                .filter { it.source == source }
                .map { it.deviceId }
                .distinct()
                .sortedBy { it.value }
                .toCollection(LinkedHashSet())
        },
    )

    /** How many devices reported first-hand, as opposed to through a peer. */
    val directSupportCount: Int get() = reportingDeviceIdsBySource.getValue(ObservationSource.DIRECT_OBSERVATION).size

    /** How many devices we know of only through a relay. */
    val relayedSupportCount: Int get() = reportingDeviceIdsBySource.getValue(ObservationSource.RELAYED_ACCOUNT).size

    /** Every report that reached this device the given way. */
    fun reportsFrom(source: ObservationSource): List<ObservedReport> =
        reports.filter { it.source == source }

    /** The single traversal decision the router and the snapshot consume. */
    val excludesTraversal: Boolean = effectiveReports.any { it.excludesTraversal }

    /**
     * True when at least one report could not be read by this build.
     *
     * Read over the whole log, and it makes no difference today: an unreadable row is never resolved
     * (see [resolvedClosures]), so it is always in force.
     */
    val hasUnreadableReports: Boolean =
        this.reports.any { it.content is ReportContent.Unreadable }

    /**
     * The most restrictive verdict this segment's reports stated.
     *
     * `IMPASSABLE` whenever [excludesTraversal] — including when the only reason is an
     * unreadable report, which fails closed. The invariant
     * `excludesTraversal == (passability == IMPASSABLE)` therefore always holds;
     * [hasUnreadableReports] distinguishes "reported impassable" from "unreadable here".
     */
    val passability: PassabilityStatus = run {
        if (excludesTraversal) return@run PassabilityStatus.IMPASSABLE
        var worst: PassabilityStatus? = null
        var worstRank = -1
        for (report in effectiveReports) {
            val verdict = report.observation?.passability ?: continue
            val rank = verdict.severityRank() ?: continue
            if (rank > worstRank) {
                worst = verdict
                worstRank = rank
            }
        }
        worst ?: PassabilityStatus.UNKNOWN
    }

    /**
     * The deepest water level any report stated.
     *
     * `UNKNOWN` only when no report stated a level. Ranks are unique per category, so the
     * reduction is independent of report order.
     */
    val worstWaterLevel: WaterLevelCategory = run {
        var worst: WaterLevelCategory? = null
        var worstRank = -1
        for (report in effectiveReports) {
            val level = report.observation?.waterLevel ?: continue
            // The category owns its own ordering (`WaterLevelCategory.depthRank`), so this
            // reduction and the reported trend cannot drift apart.
            val rank = level.depthRank ?: continue
            if (rank > worstRank) {
                worst = level
                worstRank = rank
            }
        }
        worst ?: WaterLevelCategory.UNKNOWN
    }

    /**
     * The distinct water levels any report stated, shallowest first. Empty when none stated.
     *
     * Exposed so a caller can show a spread instead of a single number: reports that differ
     * are **never averaged**, because an average of ordinal labels would be a fabricated
     * measurement (ADR 0001 D1).
     */
    val statedWaterLevels: List<WaterLevelCategory> = effectiveReports
        .mapNotNull { it.observation?.waterLevel }
        .filter { it.statesSomething }
        .distinct()
        .sortedBy { it.statedRank() }

    /** The distinct passability verdicts any report stated, most permissive first. */
    val statedPassabilities: List<PassabilityStatus> = effectiveReports
        .mapNotNull { it.observation?.passability }
        .filter { it.statesSomething }
        .distinct()
        .sortedBy { it.statedRank() }

    /**
     * True when the reports do not all say the same thing: more than one distinct stated
     * level, or more than one distinct stated verdict.
     *
     * A report that states nothing on an axis does not create a disagreement on it. This is
     * a statement about the *reports*, not about the road: two observers can both be right
     * about different moments, which is exactly why the app shows them side by side with
     * their own times instead of resolving them into one value.
     */
    val hasDisagreement: Boolean
        get() = statedWaterLevels.size > 1 || statedPassabilities.size > 1

    /**
     * True when at least one report closes the road and at least one states that it can be
     * used — the disagreement that matters for safety.
     *
     * A report that only states a depth, or says nothing about passability, is not a
     * competing claim about usability, so it does not create one. The closure still wins the
     * routing decision; this flag exists so the app can say *why* the two sides differ
     * instead of presenting a quiet single answer.
     */
    val hasTraversalDisagreement: Boolean = run {
        var excludes = false
        var permits = false
        for (report in effectiveReports) {
            if (report.excludesTraversal) {
                excludes = true
                continue
            }
            if (report.observation?.passability?.statesSomething == true) permits = true
        }
        excludes && permits
    }

    /**
     * The reports in force that stated [level], each with its own device, source and timestamps.
     *
     * The queries in this family — "which evidence says this?" — read the **effective** set, because
     * that is what a caller is asking about. A resolved closure is history: it is in [reports], and a
     * caller that wants the whole log reads that.
     */
    fun reportsWithWaterLevel(level: WaterLevelCategory): List<ObservedReport> =
        effectiveReports.filter { it.observation?.waterLevel == level }

    /**
     * The reports in force that carry [worstWaterLevel], each with its own timestamps.
     *
     * This — not a scalar timestamp — is how a caller asks how fresh the deepest observed
     * water is. Empty when no report stated a level. A resolved closure's depth is not returned: the
     * water it described was superseded, so ageing it would date evidence that no longer applies.
     */
    fun reportsWithWorstWaterLevel(): List<ObservedReport> =
        if (worstWaterLevel == WaterLevelCategory.UNKNOWN) {
            emptyList()
        } else {
            effectiveReports.filter { it.observation?.waterLevel == worstWaterLevel }
        }

    /** The reports in force that stated [status], each with its own timestamps. */
    fun reportsWithPassability(status: PassabilityStatus): List<ObservedReport> =
        effectiveReports.filter { it.observation?.passability == status }

    /**
     * Every report in force that excludes traversal, unreadable ones included.
     *
     * A resolved closure is **not** returned, which is what makes the freshness of a closure the
     * freshness of a closure that still applies: a segment whose only closure was cleared has no
     * closure evidence to age, and its freshness is `NO_CLOSURE` rather than that of superseded
     * evidence.
     */
    fun reportsExcludingTraversal(): List<ObservedReport> =
        effectiveReports.filter { it.excludesTraversal }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SegmentObservationState) return false
        return segmentId == other.segmentId && reports == other.reports
    }

    override fun hashCode(): Int = 31 * segmentId.hashCode() + reports.hashCode()

    override fun toString(): String =
        "SegmentObservationState(segment=${segmentId.value}, reports=${reports.size}, " +
            "devices=$supportCount, passability=${passability.code}, " +
            "water=${worstWaterLevel.code}, excluded=$excludesTraversal)"

    companion object {

        /**
         * Total order for [reports]: oldest claimed time first, ties broken by event id.
         *
         * A *presentation* order. It never decides a verdict or an exclusion — those are
         * order-free reductions — so a device with a skewed clock can reorder this list but
         * cannot change what the segment means.
         */
        private val REPORT_ORDER: Comparator<ObservedReport> =
            compareBy<ObservedReport> { it.occurredAtEpochMillis }.thenBy { it.eventId.value }

        /**
         * Severity order for the water axis.
         *
         * The ordering lives on the category itself ([WaterLevelCategory.depthRank]) so the
         * derivation, the trend display and anything else that compares depths share one definition
         * rather than three that happen to agree; `null` means the value states nothing.
         */
        private fun WaterLevelCategory.severityRank(): Int? = depthRank

        /** Severity order for the passability axis; `null` when the value states nothing. */
        private fun PassabilityStatus.severityRank(): Int? = when (this) {
            PassabilityStatus.PASSABLE_REPORTED -> 0
            PassabilityStatus.CAUTION -> 1
            PassabilityStatus.AVOID -> 2
            PassabilityStatus.IMPASSABLE -> 3
            PassabilityStatus.UNKNOWN -> null
        }

        /**
         * Rank of a value already known to state something.
         *
         * Fails loudly rather than returning a sentinel: reaching here with an unstating
         * value means a caller forgot the [statesSomething] filter, and silently sorting it
         * to the front or back would misreport a severity.
         */
        private fun WaterLevelCategory.statedRank(): Int =
            severityRank() ?: error("$name states nothing and has no rank")

        /** Rank of a verdict already known to state something; fails loudly otherwise. */
        private fun PassabilityStatus.statedRank(): Int =
            severityRank() ?: error("$name states nothing and has no rank")
    }
}

/**
 * Reduces a report set to per-segment state.
 *
 * Pure: **no clock parameter, no I/O, no shared state.** [aggregate] is a function of the report
 * *set*, so shuffled arrival, duplicated delivery and full replay all produce the same value — the
 * property the canonical plan's determinism exit criterion demands, and the property the clearing
 * resolution preserves (a resolved closure is decided by two claimed times in the set, never by an
 * arrival order or a local clock).
 *
 * The absence of a clock parameter is the strongest form of the guarantee: no code path reachable from
 * here *can* consult `now`, an age or a staleness window, because none is passed in.
 */
object SegmentObservationAggregator {

    /**
     * Groups [reports] by segment.
     *
     * The returned map is ordered by segment id so iteration is deterministic; the segment
     * order carries no meaning beyond stability.
     */
    fun aggregate(reports: Iterable<ObservedReport>): Map<SegmentId, SegmentObservationState> {
        val grouped = LinkedHashMap<SegmentId, MutableList<ObservedReport>>()
        for (report in reports) {
            grouped.getOrPut(report.segmentId) { mutableListOf() } += report
        }
        val ordered = LinkedHashMap<SegmentId, SegmentObservationState>(grouped.size)
        for (segment in grouped.keys.sortedBy { it.value }) {
            ordered[segment] = SegmentObservationState(segment, grouped.getValue(segment))
        }
        return ordered
    }

    /** Convenience for one segment's reports. */
    fun aggregateSegment(segmentId: SegmentId, reports: List<ObservedReport>): SegmentObservationState =
        SegmentObservationState(segmentId, reports)
}
