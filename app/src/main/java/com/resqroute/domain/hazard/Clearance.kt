package com.resqroute.domain.hazard

/**
 * Outcome of validating a proposed clearance (ADR 0005 §5.3).
 *
 * Mirrors [ObservationValidation]: rejected, never silently normalised. A clearance is the one event
 * that lifts a restriction, so a malformed one must fail loudly at the ingress rather than be repaired
 * into something permissive.
 */
sealed interface ClearanceValidation {

    /** The claim is representable and safe to store, display and act on. */
    data class Valid(val clearance: Clearance) : ClearanceValidation

    /** The claim contradicts itself. Nothing is created, and the caller surfaces [problem]. */
    data class Invalid(val problem: String) : ClearanceValidation
}

/**
 * A validated clearance: an explicit statement that a road **can be used** (ADR 0005 §5.2).
 *
 * ## Why this is a type, and not a flag
 *
 * ADR 0005 §5.3 requires that "auto-promotion of any normal observation to a clearance" be
 * *structurally impossible by design, not merely discouraged*. A boolean on a report would not do
 * that: one defaulted field away from reopening a road. This is a distinct type with a private
 * constructor, so the only way to obtain one is [create], which applies the two rules below. Nothing
 * in persistence, the wire codec or the UI can produce the other contents of [ReportContent] *as* a
 * clearance, because the conversion would not typecheck.
 *
 * ## The two rules
 *
 *  * **The verdict asserts usability.** Exactly [PassabilityStatus.PASSABLE_REPORTED] or
 *    [PassabilityStatus.CAUTION]. `AVOID` advises against the road and `IMPASSABLE` closes it, so
 *    neither can be a clearance; `UNKNOWN` is rejected because "we could not tell" is not a clearance.
 *  * **The water level cannot be an unsafe depth.** [WaterLevelCategory.ABOVE_WAIST] is defined as
 *    unsafe (ADR 0001 D1), so "above waist, and passable" is the contradiction R2 already rejects —
 *    and a clearance may not smuggle it in under a different verdict, e.g. `CAUTION`.
 *
 * Water level is never inferred from the verdict or the reverse: a clearance that states no depth
 * carries `UNKNOWN` and states nothing about water, which is a legitimate clearance ("the road is
 * usable, I cannot say how deep it is") and is not turned into a depth by this type.
 */
class Clearance private constructor(val observation: RoadObservation) {

    val waterLevel: WaterLevelCategory get() = observation.waterLevel
    val passability: PassabilityStatus get() = observation.passability

    /** Provenance of the claim, carried exactly as any other observation carries it. */
    val reason: HazardReason get() = observation.reason

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Clearance) return false
        return observation == other.observation
    }

    override fun hashCode(): Int = observation.hashCode()

    override fun toString(): String =
        "Clearance(water=${waterLevel.code}, passability=${passability.code}, reason=${reason.code})"

    companion object {

        /** Rule: a clearance asserts usability, so its verdict must say so. */
        const val PROBLEM_PASSABILITY_DOES_NOT_ASSERT_USABILITY =
            "a clearance must state passable or caution: 'avoid', 'impassable' and 'not judged' are not " +
                "statements that the road can be used"

        /** Rule: an unsafe depth cannot be part of a claim that the road is usable. */
        const val PROBLEM_WATER_LEVEL_IS_UNSAFE =
            "above-waist depth is defined as unsafe and cannot be part of a clearance"

        /** The only verdicts a clearance may carry (ADR 0005 §5.2). */
        val RESOLVING_VERDICTS: Set<PassabilityStatus> =
            setOf(PassabilityStatus.PASSABLE_REPORTED, PassabilityStatus.CAUTION)

        /**
         * Validates and builds a clearance. The only way to create one.
         *
         * The axes are built through [RoadObservation.create] after these rules, so a clearance's
         * observation is an ordinarily valid observation as well — which is what lets it take part in
         * the severity reductions like any other statement about the road (ADR 0005 §8 requirement 7:
         * clearing a segment with no closures records an ordinary passable observation).
         */
        fun create(
            reason: HazardReason,
            waterLevel: WaterLevelCategory,
            passability: PassabilityStatus,
        ): ClearanceValidation {
            if (passability !in RESOLVING_VERDICTS) {
                return ClearanceValidation.Invalid(PROBLEM_PASSABILITY_DOES_NOT_ASSERT_USABILITY)
            }
            if (waterLevel == WaterLevelCategory.ABOVE_WAIST) {
                return ClearanceValidation.Invalid(PROBLEM_WATER_LEVEL_IS_UNSAFE)
            }
            return when (val outcome = RoadObservation.create(reason, waterLevel, passability)) {
                is ObservationValidation.Valid -> ClearanceValidation.Valid(Clearance(outcome.observation))
                is ObservationValidation.Invalid -> ClearanceValidation.Invalid(outcome.problem)
            }
        }
    }
}

/**
 * The reopening rule: when an explicit clearance resolves a closure (ADR 0005 §5.1).
 *
 * ```
 * resolves(clearance c, closure r)  =  c.claimedAt > r.claimedAt
 *                                   ∧  credible(r.claimedAt)
 *                                   ∧  credible(c.claimedAt)
 * credible(t)                       =  EARLIEST_CREDIBLE_CLAIM_MILLIS <= t <= LATEST_CREDIBLE_CLAIM_MILLIS
 * ```
 *
 * ## The direction of every failure is "still closed"
 *
 * Equal claims: closed. Older clearance: closed. A missing, zero, negative or absurd claim on either
 * side: closed. A tie means "we cannot tell which situation this belongs to", and "we cannot tell" is
 * never "open".
 *
 * ## Why this is not "the newest report wins"
 *
 * ADR 0005 §4.3 rejects ordering truth by recency, and rightly: device clocks are not orderable, and a
 * single pathological clock would decide road safety. Two things keep this rule different.
 *
 *  * **Existence, not recency, is the primary fact.** An ordinary newer report — however fresh, however
 *    many devices sent it — resolves nothing at all. A road stops being closed only when a *clearance*
 *    exists, which is a distinct, validated act (see [Clearance]) that a report cannot be promoted into.
 *  * **The comparison is bounded and only ever removes one specific closure.** A claim is credible only
 *    inside an absolute window, and only the closures strictly older than a given clearance are
 *    resolved by it. No timestamp can resurrect a road, because no timestamp is consulted unless a
 *    clearance exists in the first place.
 *
 * ## Why this reads no clock — and the honest limit of that
 *
 * This is a pure comparison of two values that arrived in the event set. It reads no `now`, no age and
 * no staleness window, so two devices holding the same events compute the same excluded set — the
 * convergence property ADR 0005 §7.3 requires ("labels may differ between devices; excluded sets may
 * not"). ADR 0005 §8 requirement 13 asks that no function computing an excluded set read a timestamp
 * at all; §5.1 is the one sanctioned exception, and this object keeps it the *only* one.
 *
 * The limit, stated plainly: whether a claim is *further into the future than a device's clock allows*
 * cannot be judged here without breaking that convergence, because it needs a clock — and a
 * clock-dependent answer would make two devices disagree about a road. So futurity is handled in two
 * parts:
 *
 *  * A claim outside the **absolute** credibility window is undecidable here, and the closure wins.
 *    That covers the sentinel values (§8 requirement 5: zero, negative, absurdly old) and the absurd
 *    future (a claim in the year 3000 cannot be a claim about this flood).
 *  * A claim that is merely ahead of one device's clock but inside the window is **not** treated as
 *    undecidable by the derivation. It is still labelled "time not verified" wherever ages are shown
 *    (ADR 0005 §7.1, display-only), and the closure it resolves had to be explicitly cleared by a
 *    person. The residual risk is that a device with a fast clock can resolve closures up to that skew
 *    older than it should — bounded by the window, and confined to the one event that already requires
 *    an explicit human assertion.
 */
object ReopeningPolicy {

    /**
     * A claim before this cannot be genuine: it is a sentinel or a corrupt clock.
     *
     * Deliberately the same instant as the display policy's absolute floor for a plausible observation
     * time — equal **by intent, never by reference**, because a function that decides an excluded set must
     * not read a display constant (ADR 0005 §8 requirement 13, enforced by `ArchitectureGuardTest`). A
     * test asserts the two agree, so they cannot drift apart silently.
     */
    const val EARLIEST_CREDIBLE_CLAIM_MILLIS: Long = 1_577_836_800_000L

    /**
     * A claim after this is absurd: it is not a claim about this flood.
     *
     * **2100-01-01T00:00:00Z.** An absolute ceiling, not "more than N from now": the point is to reject
     * a fabricated or corrupt far-future value without consulting a clock, and to leave every honest
     * claim — including one from a device whose clock is hours fast, and including a genuine clearance
     * years after the closure — resolving its closures.
     */
    const val LATEST_CREDIBLE_CLAIM_MILLIS: Long = 4_102_444_800_000L

    /** True when a claimed observation time could describe an observation of this flood. */
    fun isCredibleClaim(claimedAtEpochMillis: Long): Boolean =
        claimedAtEpochMillis in EARLIEST_CREDIBLE_CLAIM_MILLIS..LATEST_CREDIBLE_CLAIM_MILLIS

    /**
     * True when a clearance with [clearanceClaimedAt] resolves a closure with [closureClaimedAt].
     *
     * Pure, symmetric in nothing, and clock-free — see the class documentation for the direction of
     * each failure and for what it deliberately does not judge.
     */
    fun resolves(clearanceClaimedAt: Long, closureClaimedAt: Long): Boolean =
        isCredibleClaim(clearanceClaimedAt) &&
            isCredibleClaim(closureClaimedAt) &&
            clearanceClaimedAt > closureClaimedAt
}
