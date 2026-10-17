package com.resqroute.feature.map.presentation

import com.resqroute.core.model.SegmentId
import com.resqroute.core.routing.RoutePath
import com.resqroute.domain.hazard.ClosureFreshness
import com.resqroute.domain.hazard.FreshnessPolicy
import com.resqroute.domain.hazard.ObservationSource
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.domain.risk.RiskPenaltyPolicy

/**
 * One reported road on a route, with everything a person needs to judge the report.
 *
 * It is a **fact**, not a sentence: the wording lives in `RouteExplanationCopy`, so the same facts
 * can be rendered for a phone sheet, a log line or a test without three copies drifting apart.
 *
 * **Only priced reports appear here.** A row this build cannot read *excludes* the road (ADR 0004
 * §2), so the router never traverses it and it can never be a crossed hazard — which is why there is
 * no "unreadable" case to render. Such a row still counts as *observed* for coverage, because
 * somebody did report there.
 *
 * [ageMillis] is measured from local receipt, never from the origin's claimed time, and
 * [timeVerified] is false when that claimed time is not believable — the same rules as every other
 * age in the app (ADR 0005 §6.2, §7.1). An unverified age is still shown, labelled as unverified;
 * it is never repaired.
 */
data class RouteHazard(
    /** The graph's stable segment id, so a person can name the road they are looking at. */
    val segmentId: SegmentId,
    val waterLevel: WaterLevelCategory,
    val passability: PassabilityStatus,
    /** Distinct device IDs that reported this segment (ADR 0001 D5). */
    val supportCount: Int,
    val directSupportCount: Int,
    val relayedSupportCount: Int,
    /** Age of the newest report carrying the risk. */
    val ageMillis: Long?,
    val timeVerified: Boolean,
    /** Evidence freshness, using ADR 0005's display-only vocabulary. */
    val freshness: ClosureFreshness,
)

/**
 * What one offered route goes through, and how much of it nobody has looked at.
 *
 * [observedLengthMeters] and [unknownLengthMeters] always sum to the route's own distance: every
 * metre of the route is either covered by at least one committed report or it is not. That is the
 * quantitative disclosure ADR 0001 D6 requires, and the reason unknown is carried as a **length**
 * rather than a percentage — see `RouteExplanationCopy` for why no percentage is rendered.
 */
data class RouteReport(
    val route: RoutePath,
    val hazardsCrossed: List<RouteHazard>,
    val observedLengthMeters: Double,
    val unknownLengthMeters: Double,
) {
    val totalLengthMeters: Double get() = observedLengthMeters + unknownLengthMeters

    /** Fraction of the route with no observations at all. `0.0` for a zero-length route. */
    val unknownProportion: Double
        get() = if (totalLengthMeters <= 0.0) 0.0 else unknownLengthMeters / totalLengthMeters

    /**
     * True when the route crosses a road somebody reported as risky.
     *
     * Derived from the hazard list, so it cannot disagree with what the list shows. It is
     * deliberately *not* the same question as "is this route safe" — a route with none is
     * **unreported**, not clear (ADR 0001 D6).
     */
    val crossesReportedRisk: Boolean get() = hazardsCrossed.isNotEmpty()
}

/**
 * The whole explanation of one route decision (XPH-77).
 *
 * Built from `RouteStatus.Ready`'s [RouteOptions] plus the derived observation state that was
 * published with the same snapshot, so every fact here describes the same revision of reports that
 * the routes were computed against.
 *
 * It answers the four questions a person actually has: *which* routes are on offer, *how much
 * longer* the safer-looking one is, *what reported roads* each goes through, and *how much of it
 * nobody has reported on at all*. It does not answer "is this safe" — nothing in this app does.
 */
sealed interface RouteExplanation {

    /** One report per offered route, shortest first. */
    val reports: List<RouteReport>

    /** True when the lower-risk search found a different road, so there is a real choice. */
    val hasLowerRiskAlternative: Boolean

    /**
     * Reported roads the lower-risk route avoids that the shortest one crosses.
     *
     * Empty when the routes are identical, or when the alternative avoids nothing — an empty list
     * here is a fact worth stating plainly rather than a gap to fill with reassurance.
     */
    val hazardsAvoidedByLowerRisk: List<RouteHazard>

    /** True when **every** route on offer crosses at least one reported road. */
    val everyCandidateCrossesReportedRisk: Boolean
        get() = reports.isNotEmpty() && reports.all { it.crossesReportedRisk }

    /** Both policies chose the same road: one route, no choice to present. */
    data class Single(val only: RouteReport) : RouteExplanation {
        override val reports: List<RouteReport> get() = listOf(only)
        override val hasLowerRiskAlternative: Boolean get() = false
        override val hazardsAvoidedByLowerRisk: List<RouteHazard> get() = emptyList()
    }

    /** The policies chose different roads. */
    data class Comparison(
        val shortest: RouteReport,
        val lowerRisk: RouteReport,
        override val hazardsAvoidedByLowerRisk: List<RouteHazard>,
    ) : RouteExplanation {
        override val reports: List<RouteReport> get() = listOf(shortest, lowerRisk)
        override val hasLowerRiskAlternative: Boolean get() = true
    }

    companion object {

        /**
         * Explains a decision.
         *
         * [segmentLengthMeters] supplies a segment's geometric length; the real caller passes
         * `RoutingGraph::segmentLengthMeters`. [now] is injected — nothing here reads a clock — so
         * the explanation is a pure function of the decision, the reports and the moment it is
         * shown.
         *
         * A segment counts as **observed** when any report exists for it, whether or not that
         * report priced any risk: a road somebody looked at and found passable is not unreported.
         * The hazards listed are the ones the risk policy actually priced, which is the same set the
         * router paid to avoid.
         */
        fun of(
            options: RouteOptions,
            states: Map<SegmentId, SegmentObservationState>,
            segmentLengthMeters: (segmentIndex: Int) -> Double,
            now: Long,
        ): RouteExplanation {
            val reports = options.offered.map { offered ->
                reportFor(offered, states, segmentLengthMeters, now)
            }
            return when (options) {
                is RouteOptions.Identical -> Single(reports.single())
                is RouteOptions.Divergent -> {
                    val shortest = reports[0]
                    val lowerRisk = reports[1]
                    val avoided = shortest.hazardsCrossed.filter { hazard ->
                        lowerRisk.hazardsCrossed.none { it.segmentId == hazard.segmentId }
                    }
                    Comparison(shortest, lowerRisk, avoided)
                }
            }
        }

        private fun reportFor(
            offered: OfferedRoute,
            states: Map<SegmentId, SegmentObservationState>,
            segmentLengthMeters: (segmentIndex: Int) -> Double,
            now: Long,
        ): RouteReport {
            var observed = 0.0
            var unknown = 0.0
            // Distinct segments: a path that revisits a segment is still one reported road, while
            // the length accounting below counts each traversal, because that is the distance
            // actually travelled.
            val pricedSegments = LinkedHashSet<SegmentId>()
            for (segment in offered.route.segmentIds) {
                val state = states[segment]
                if (state == null) {
                    unknown += segmentLengthMeters(segment.value)
                } else {
                    observed += segmentLengthMeters(segment.value)
                    if (!state.excludesTraversal && RiskPenaltyPolicy.riskFactor(state) > 0.0) {
                        pricedSegments += segment
                    }
                }
            }
            return RouteReport(
                route = offered.route,
                hazardsCrossed = pricedSegments.map { hazardFor(it, states.getValue(it), now) },
                observedLengthMeters = observed,
                unknownLengthMeters = unknown,
            )
        }

        private fun hazardFor(
            segmentId: SegmentId,
            state: SegmentObservationState,
            now: Long,
        ): RouteHazard {
            // Age the reports that carry the risk, using the same clock rules as every other age in
            // the app. An unreadable row still prices risk, so it is aged too — its timestamps are
            // real even though its axes cannot be read here.
            val freshness = FreshnessPolicy.reportFreshness(state.reports, now)
            val bySource = state.reportingDeviceIdsBySource
            return RouteHazard(
                segmentId = segmentId,
                waterLevel = state.worstWaterLevel,
                passability = state.passability,
                supportCount = state.supportCount,
                directSupportCount = bySource.getValue(ObservationSource.DIRECT_OBSERVATION).size,
                relayedSupportCount = bySource.getValue(ObservationSource.RELAYED_ACCOUNT).size,
                ageMillis = freshness.ageMillis,
                timeVerified = freshness.claimedTimeVerified,
                freshness = freshness.label,
            )
        }
    }
}
