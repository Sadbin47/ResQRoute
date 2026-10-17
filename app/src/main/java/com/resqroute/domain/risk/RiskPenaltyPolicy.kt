package com.resqroute.domain.risk

import com.resqroute.core.model.SegmentId
import com.resqroute.core.routing.RoutingPolicy
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory

/**
 * Turns reported road conditions into a non-negative routing cost.
 *
 * A pure builder and its constants, and nothing else: it reads a derived observation state and
 * returns an immutable [RoutingPolicy]. It touches no clock, no I/O and no UI, and it never
 * decides whether a road is usable — an impassable segment is the **exclusion** path's business
 * (ADR 0006), not a very large number here (ADR 0001 D1).
 *
 * ## The formula
 *
 * ```
 * penaltyMeters(segment) = segmentLengthMeters × LAMBDA × riskFactor(state)
 * effectiveWeight(risk)  = 1 + LAMBDA × risk
 * riskFactor(state)      = max(waterLevelWeight, passabilityWeight) × supportWeight(deviceCount)
 * ```
 *
 * `effectiveWeight` is how much more the segment costs than its own length, so a segment costs
 * `length × effectiveWeight`. Because every weight below is `>= 0` and `LAMBDA >= 0`, the
 * effective weight is always `>= 1`: **a report can only ever make a road more expensive than
 * its length, never cheaper.** That is what keeps the A* heuristic admissible — the Haversine
 * estimate is never penalised, so it can never overestimate the true cost.
 *
 * ## The constants, and what they are not
 *
 * | Constant | Value | Meaning | Rationale |
 * | --- | --- | --- | --- |
 * | [LAMBDA] | 1.5 | How much a unit of risk costs, relative to distance | **Measured** (XPH-84) on the real graph: the marginal exchange rate crosses about 1 m of detour per metre of reported risk avoided near 1.5, in both a moderate and a severe scenario. The defensible band is **[1.0, 2.0]**; `>= 4` is provably wasteful (the severe scenario saturates at 8 and buys nothing further), and `<= 0.25` leaves most moderate-risk avoidance unused. A **policy choice, not a safety guarantee** — see ADR 0011 |
 * | [CORROBORATION_STEP] | 0.25 | Risk added per extra reporting device | Each additional device corroborates the hazard, so the road is treated as more expensive. A heuristic, **not** a probability |
 * | [MAX_EXTRA_CORROBORATION_DEVICES] | 3 | Cap on that addition | Bounds the factor at `1 + 3 × 0.25 = 1.75`, so a large crowd cannot make a road arbitrarily expensive |
 *
 * ## What is deliberately absent
 *
 * **Recency.** XPH-74's sketch included a time-decay factor, and this implements none. ADR 0005
 * (D7, accepted) forbids it in as many words: *"age is display data — no function that computes an
 * excluded set, a revision, a penalty or a route may read an age"*, and its requirement 13 is
 * enforced by `ArchitectureGuardTest`. A penalty that faded with age would let the clock change a
 * route, which is the same error ADR 0005 §4.2 traced through the rejected TTL. If a recency term
 * is ever wanted, ADR 0005 must be amended first — it is the policy, not this file.
 *
 * **Unknown coverage.** A segment with no report at all gets no penalty here, because whether
 * unreported road should cost extra is XPH-89's separate decision (ADR 0001 D6). This builder only
 * prices what somebody reported.
 *
 * **Confidence.** There is no such quantity in this codebase. The device-count factor is named
 * [supportWeight] because a count of device IDs is support, not a calibrated probability
 * (ADR 0001 D5), and it must never be rendered as one.
 */
object RiskPenaltyPolicy {

    /**
     * Risk-to-distance exchange rate. See the table above.
     *
     * Measured, not chosen by intuition: XPH-84's sweep over the real Feni graph (ADR 0011).
     */
    const val LAMBDA: Double = 1.5

    /** Risk added per extra corroborating device. See the table above. */
    const val CORROBORATION_STEP: Double = 0.25

    /** Cap on corroboration. See the table above. */
    const val MAX_EXTRA_CORROBORATION_DEVICES: Int = 3

    /**
     * Builds the policy for a derived state.
     *
     * [segmentLengthMeters] supplies the geometric length of a segment index; the real caller
     * passes `RoutingGraph::segmentLengthMeters`. Taking it as a function rather than the graph
     * keeps this builder pure and testable, and only observed segments are ever looked up, so the
     * cost is proportional to the number of reports rather than to the size of the graph.
     *
     * [lambda] defaults to [LAMBDA] and exists so the exchange rate can be **swept** — XPH-84
     * measures the detour cost of each value on the real graph with one formula rather than a
     * reimplementation per experiment. Production passes nothing and gets the tuned constant.
     *
     * It sits **before** [segmentLengthMeters] on purpose: a trailing lambda binds to the last
     * parameter, so a function-typed parameter must stay last or every existing
     * `forStates(states) { … }` call site would quietly start binding its lambda to the wrong
     * argument.
     *
     * Segments whose state **excludes** traversal are skipped: they are removed from the search by
     * the blocker, and pricing them here as well would be a second, silently diverging answer to
     * the same question. A state that carries no risk produces no entry, so a log of only
     * passable reports yields no penalties at all.
     */
    fun forStates(
        states: Map<SegmentId, SegmentObservationState>,
        lambda: Double = LAMBDA,
        segmentLengthMeters: (segmentIndex: Int) -> Double,
    ): RoutingPolicy {
        // Tuning is a supported use of this builder (XPH-84 sweeps it), so the invalid range is
        // refused here with a clear message rather than surfacing later as a penalty that
        // `RoutingPolicy` rejects without saying why.
        require(lambda >= 0.0 && lambda.isFinite()) {
            "lambda must be finite and non-negative: a negative exchange rate would pay a route " +
                "for taking on risk, and would break the admissibility proof"
        }
        if (states.isEmpty()) return RoutingPolicy.DISTANCE_ONLY

        val penalties = LinkedHashMap<SegmentId, Double>()
        for ((segment, state) in states) {
            if (state.excludesTraversal) continue
            val risk = riskFactor(state)
            if (risk <= 0.0) continue

            val penalty = segmentLengthMeters(segment.value) * lambda * risk
            // `RoutingPolicy` already refuses a non-finite or negative penalty, so an
            // impossible length must not be smuggled past it as a NaN. Dropping the entry keeps
            // the policy valid and costs only the caution for one malformed segment; the
            // alternative, throwing, would take routing down for the whole map.
            if (penalty.isFinite() && penalty > 0.0) {
                penalties[segment] = penalty
            }
        }

        return if (penalties.isEmpty()) RoutingPolicy.DISTANCE_ONLY else RoutingPolicy(penalties)
    }

    /**
     * How risky one segment's reported state is, as a factor `>= 0`.
     *
     * This is **not** a probability and **not** a cost: the cost is
     * `length × LAMBDA × this`. It is monotone in both axes and in the number of reporting
     * devices, so more evidence or deeper water can only ever raise it.
     *
     * An excluded segment returns its full pre-exclusion risk, which is honest — it *is* the
     * riskiest state — but [forStates] never turns it into a penalty, and the engine skips a
     * blocked segment before it reads a penalty anyway.
     */
    fun riskFactor(state: SegmentObservationState): Double {
        val base = maxOf(
            waterLevelWeight(state.worstWaterLevel),
            passabilityWeight(state.passability),
        )
        return base * supportWeight(state.supportCount)
    }

    /**
     * How much more a segment costs than its own length: `1 + LAMBDA × risk`.
     *
     * The acceptance criterion of this phase is that this is `>= 1` for every level, device count
     * and age. It is `>= 1` by construction, and asserted by a sweep.
     */
    fun effectiveWeight(risk: Double): Double = 1.0 + LAMBDA * risk

    /**
     * Risk contributed by the deepest water level any report stated.
     *
     * `UNKNOWN` contributes nothing rather than a guess: an observer who could not estimate the
     * depth has told us nothing about the depth. `ABOVE_WAIST` is the ceiling because ADR 0002
     * defines it as an unsafe depth.
     */
    fun waterLevelWeight(level: WaterLevelCategory): Double = when (level) {
        WaterLevelCategory.NO_VISIBLE_WATER -> 0.0
        WaterLevelCategory.ANKLE -> 0.15
        WaterLevelCategory.KNEE -> 0.35
        WaterLevelCategory.WAIST -> 0.60
        WaterLevelCategory.ABOVE_WAIST -> 1.0
        WaterLevelCategory.UNKNOWN -> 0.0
    }

    /**
     * Risk contributed by the most restrictive passability verdict any report stated.
     *
     * `PASSABLE_REPORTED` contributes nothing: somebody looked at the road and believed it
     * usable, which is information, not a hazard. `UNKNOWN` contributes nothing for the same
     * reason as an unstated depth. `IMPASSABLE` carries the ceiling so the table is total, but a
     * segment in that state is never priced — see [forStates].
     */
    fun passabilityWeight(status: PassabilityStatus): Double = when (status) {
        PassabilityStatus.PASSABLE_REPORTED -> 0.0
        PassabilityStatus.CAUTION -> 0.25
        PassabilityStatus.AVOID -> 0.50
        PassabilityStatus.IMPASSABLE -> 1.0
        PassabilityStatus.UNKNOWN -> 0.0
    }

    /**
     * Risk multiplier from the number of distinct device IDs that reported.
     *
     * One device leaves the risk exactly as its observation described it; each further device adds
     * [CORROBORATION_STEP], up to [MAX_EXTRA_CORROBORATION_DEVICES]. Four phones and forty phones
     * therefore cost the same, so a crowd cannot close a road through arithmetic.
     *
     * This is a documented, unvalidated heuristic. It is **not** the "probability that the road is
     * flooded", it is not calibrated against anything, and it must never be shown as a percentage
     * or as confidence (ADR 0001 D5).
     */
    fun supportWeight(deviceCount: Int): Double {
        if (deviceCount <= 0) return 1.0
        val extra = minOf(deviceCount - 1, MAX_EXTRA_CORROBORATION_DEVICES)
        return 1.0 + extra * CORROBORATION_STEP
    }
}
