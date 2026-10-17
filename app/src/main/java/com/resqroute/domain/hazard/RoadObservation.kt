package com.resqroute.domain.hazard

/**
 * How much water was seen on a road segment.
 *
 * These are **ordinal labels, not measurements**. The app has no sensor and no
 * calibration, so a category must never be converted into a centimetre depth, a
 * probability, a speed or a time. `UNKNOWN` is a first-class answer meaning the
 * observer could not estimate; it must survive storage, transport and display
 * without being coerced into any other category.
 *
 * The codes are pinned: they are the spelling used by persistence and by the peer
 * protocol, so renaming one is a protocol change, not a refactor.
 *
 * See `docs/decisions/0001-v2-observation-and-risk-semantics.md` (decision D1).
 */
enum class WaterLevelCategory(val code: String) {
    NO_VISIBLE_WATER("NO_VISIBLE_WATER"),
    ANKLE("ANKLE"),
    KNEE("KNEE"),
    WAIST("WAIST"),

    /** Above the waist, or otherwise an unsafe depth. */
    ABOVE_WAIST("ABOVE_WAIST"),

    /** The observer could not estimate the water level. */
    UNKNOWN("UNKNOWN"),
    ;

    /** True when this value actually states something about the water. */
    val statesSomething: Boolean get() = this != UNKNOWN

    /**
     * Ordinal depth, so two observations can be compared; null when this states no depth.
     *
     * Higher is deeper. This is the app's one ordering for the question *"did the water get deeper
     * or shallower?"* — the per-segment maximum (ADR 0006) and the reported trend (ADR 0019) both
     * read it. It is an **ordinal comparison and not a measurement**: the gaps between the values
     * carry no meaning, and nothing may convert one into a depth, a speed, a time or a probability
     * (ADR 0001 D1).
     *
     * [UNKNOWN] has no rank. An observer who could not estimate the depth has stated no depth to
     * compare, so it is excluded from any ordering rather than placed at either end — placing it at
     * zero would make "depth not estimated" look like "no water", and placing it at the top would
     * make it look like an unsafe depth.
     *
     * Deliberately **not** the same thing as `RiskPenaltyPolicy.waterLevelWeight`: that is a
     * routing cost and may be retuned by measurement (ADR 0011), while this ordering is semantics
     * and may not. The two are monotone in the same direction by intention, and a test pins it.
     */
    val depthRank: Int? get() = when (this) {
        NO_VISIBLE_WATER -> 0
        ANKLE -> 1
        KNEE -> 2
        WAIST -> 3
        ABOVE_WAIST -> 4
        UNKNOWN -> null
    }

    companion object {
        /** Null for an unknown or unsupported code; callers decide what that means. */
        fun fromCode(code: String): WaterLevelCategory? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Whether the segment could be used, recorded **separately** from the water level.
 *
 * The two axes are independent on purpose: a collapsed bridge is impassable with no
 * water at all, and a flooded road can have an unknown passability. Only
 * [IMPASSABLE] removes a segment from the search; every other value is traversable
 * and may, in a later phase, attract a finite risk penalty. This type is the single
 * place that decides "excluded, never merely expensive".
 *
 * The codes are pinned for persistence and the wire.
 *
 * See `docs/decisions/0001-v2-observation-and-risk-semantics.md` (decision D1).
 */
enum class PassabilityStatus(val code: String, val excludesTraversal: Boolean) {
    /** The observer believes the road can be used. */
    PASSABLE_REPORTED("PASSABLE_REPORTED", excludesTraversal = false),

    /** Usable, but the observer advises care. */
    CAUTION("CAUTION", excludesTraversal = false),

    /** The observer advises avoiding this road; not a confirmed block. */
    AVOID("AVOID", excludesTraversal = false),

    /**
     * The road cannot be used. This is the **only** value that removes a segment
     * from the search; it is never modelled as a large number.
     */
    IMPASSABLE("IMPASSABLE", excludesTraversal = true),

    /** The observer could not judge passability. */
    UNKNOWN("UNKNOWN", excludesTraversal = false),
    ;

    /** True when this value actually states something about passability. */
    val statesSomething: Boolean get() = this != UNKNOWN

    companion object {
        /** Null for an unknown or unsupported code; callers decide what that means. */
        fun fromCode(code: String): PassabilityStatus? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Outcome of validating a proposed observation.
 *
 * Mirrors the repository's [MutationResult] shape: the invalid branch carries a
 * human-readable problem description that a caller may surface, and no invalid
 * [RoadObservation] can be constructed.
 */
sealed interface ObservationValidation {

    /** The combination is representable; the observation is safe to store and show. */
    data class Valid(val observation: RoadObservation) : ObservationValidation

    /** The combination is contradictory or carries no information. Nothing is created. */
    data class Invalid(val problem: String) : ObservationValidation
}

/**
 * One road observation: which kind of report it is, how deep the water was, and
 * whether the road could be used.
 *
 * [reason] is provenance only — it is never a severity and never a cost.
 * [waterLevel] and [passability] are the two independent axes decided in ADR 0001
 * (D1); [excludesTraversal] is the single traversal decision the router consumes.
 *
 * Construction is validated through [create] or [fromLegacy], so a contradictory
 * pair cannot exist in the domain. Value equality is hand-written (as in
 * [HazardSnapshot]) so the type stays a plain value object without a generated
 * `copy` that could bypass validation.
 */
class RoadObservation private constructor(
    /** Provenance and audit only: what kind of report this is. */
    val reason: HazardReason,
    val waterLevel: WaterLevelCategory,
    val passability: PassabilityStatus,
) {

    /**
     * True when this observation removes the segment from the search.
     *
     * A traversable observation may later attract a finite risk penalty; an
     * excluded one never does, because it is not searched at all.
     */
    val excludesTraversal: Boolean get() = passability.excludesTraversal

    /** True when the report states something about the road on either axis. */
    val statesSomething: Boolean get() = waterLevel.statesSomething || passability.statesSomething

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RoadObservation) return false
        return reason == other.reason &&
            waterLevel == other.waterLevel &&
            passability == other.passability
    }

    override fun hashCode(): Int {
        var result = reason.hashCode()
        result = 31 * result + waterLevel.hashCode()
        result = 31 * result + passability.hashCode()
        return result
    }

    override fun toString(): String =
        "RoadObservation(reason=${reason.code}, water=${waterLevel.code}, passability=${passability.code})"

    companion object {

        /** Rule R1: a report that states nothing must not enter the log. */
        const val PROBLEM_STATES_NOTHING =
            "observation states nothing about the road: water level and passability are both unknown"

        /** Rule R2: this water level is defined as unsafe, so it cannot be reported passable. */
        const val PROBLEM_ABOVE_WAIST_IS_NOT_PASSABLE =
            "above-waist depth is defined as unsafe and cannot be reported passable"

        /**
         * Validates and builds an observation.
         *
         * Two combinations are rejected, both documented in
         * `docs/decisions/0002-observation-model-and-mapping.md`:
         *
         *  * **R1 — states nothing.** Both axes `UNKNOWN`. Storing it would add no
         *    information while still counting towards "reported by N distinct device
         *    IDs", inflating the support measure with an empty report (ADR 0001 D5).
         *  * **R2 — contradiction.** `ABOVE_WAIST` with `PASSABLE_REPORTED`: the
         *    category is defined as an unsafe depth, so it cannot also be reported
         *    as usable. This is rejected rather than silently rewritten, so the
         *    observer is asked to correct the report instead of the app guessing.
         *
         * Everything else is representable, including the two cases that the axes
         * exist to express: no visible water with an impassable verdict (a collapsed
         * bridge) and unknown water with an impassable verdict.
         *
         * Water level is **never** inferred from passability or the reverse: a
         * missing axis stays [WaterLevelCategory.UNKNOWN] / [PassabilityStatus.UNKNOWN]
         * because the app has no validated model that maps one to the other.
         */
        fun create(
            reason: HazardReason,
            waterLevel: WaterLevelCategory,
            passability: PassabilityStatus,
        ): ObservationValidation {
            if (!waterLevel.statesSomething && !passability.statesSomething) {
                return ObservationValidation.Invalid(PROBLEM_STATES_NOTHING)
            }
            if (waterLevel == WaterLevelCategory.ABOVE_WAIST &&
                passability == PassabilityStatus.PASSABLE_REPORTED
            ) {
                return ObservationValidation.Invalid(PROBLEM_ABOVE_WAIST_IS_NOT_PASSABLE)
            }
            return ObservationValidation.Valid(RoadObservation(reason, waterLevel, passability))
        }

        /**
         * The v1 mapping, unchanged in effect (ADR 0001 D2).
         *
         * In v1 the effective blocked set was a `SELECT DISTINCT segmentId` over
         * every committed report with **no reason filter**
         * (`data/local/HazardDao.kt:27-44`), so a `WATERLOGGED` report and a
         * `BLOCKED` report were both hard exclusions. Both therefore map to
         * [PassabilityStatus.IMPASSABLE], and nothing is invented: the water level
         * stays [WaterLevelCategory.UNKNOWN] because v1 never recorded a depth, and
         * no graded value exists for a legacy report — which is what makes a finite
         * penalty impossible for one.
         */
        fun fromLegacy(reason: HazardReason): RoadObservation =
            when (val outcome = create(reason, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE)) {
                is ObservationValidation.Valid -> outcome.observation
                is ObservationValidation.Invalid ->
                    error("legacy mapping must always be representable: ${outcome.problem}")
            }
    }
}
