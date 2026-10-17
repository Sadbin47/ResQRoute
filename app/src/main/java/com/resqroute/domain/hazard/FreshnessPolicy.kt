package com.resqroute.domain.hazard

/**
 * How fresh a closure's evidence is — **a label, never a restriction**.
 *
 * Implements ADR 0005 §6–§7 (accepted 2026-10-16). The whole point of this type is what
 * it *cannot* do: it cannot change a verdict, an exclusion, a revision or a route. A road
 * reported impassable and never rechecked stays excluded indefinitely; all that ages is
 * the wording and the prompt to go and look again.
 *
 * ## The policy, as a table
 *
 * | Constant | Value | Meaning | Rationale |
 * | --- | --- | --- | --- |
 * | [STALE_AFTER_MILLIS] | 12 h | Above this age a closure is labelled **needs recheck** | Roughly one operational period — a daylight working window and the overnight gap between them. Retained from the project's earlier vocabulary deliberately. Not a routing threshold: moving it cannot change a route |
 * | [RECHECK_DEADLINE_AFTER_MILLIS] | 72 h | Above this age a closure is labelled **not rechecked for `<duration>`** | Past three days of an evolving flood, a report describes a different situation and the app should say plainly that nobody has looked recently |
 * | [FUTURE_TIME_TOLERANCE_MILLIS] | 15 min | A claimed observation time further into the future than this is **not verified** | Offline device clocks drift in seconds per day, so 15 min is far beyond ordinary drift; and it is tiny next to the freshness window, so an honest clock is never flagged |
 * | [EARLIEST_PLAUSIBLE_OBSERVATION_MILLIS] | 2020-01-01 | A claimed observation time before this is **not verified** | Before the ResQRoute dataset and app existed, so such a claim is a sentinel or a corrupt clock. Deliberately an absolute floor and **not** "older than N days": a genuinely two-year-old report is not implausible, it is simply stale, and it must keep its staleness label |
 *
 * These are the **only** permitted meaning of "expiry" in this codebase, and they are named
 * so that nobody mistakes them for the rejected `TTL_IMPASSABLE` rule (ADR 0001 §3): no
 * constant here may be called `TTL`, `EXPIRY` or `TIMEOUT`.
 *
 * ## The two rules that make it safe
 *
 * 1. **Age is measured from the local receipt time**, `ObservedReport.receivedAtEpochMillis`,
 *    never from the origin's claimed time (§6.2). A claimed time is set by a peer; if it fed
 *    the label, a relaying device could forward a month-old closure with a current claim and
 *    every downstream phone would present it as new.
 * 2. **`now` is always injected** (§6.1). Nothing here reads the system clock, so the same
 *    report set evaluated at the same instant always produces the same label, and the tests
 *    are deterministic rather than time-of-day dependent.
 *
 * ## Not to be confused with
 *
 * The *verdict* (`SegmentObservationState.passability`, `excludesTraversal`) is derived from
 * the report set alone and is clock-free — see ADR 0006. Freshness is computed **on demand**
 * from that state; it is deliberately not stored in the published derived state, because a
 * stored label would make the derived state time-dependent and would need a ticker to stay
 * true.
 */
object FreshnessPolicy {

    /** 12 h. See the table above. Display only. */
    const val STALE_AFTER_MILLIS: Long = 12L * 60 * 60 * 1000

    /** 72 h. See the table above. Display only. */
    const val RECHECK_DEADLINE_AFTER_MILLIS: Long = 72L * 60 * 60 * 1000

    /** 15 min. Claimed observation times beyond this into the future are not verified. */
    const val FUTURE_TIME_TOLERANCE_MILLIS: Long = 15L * 60 * 1000

    /**
     * 2020-01-01T00:00:00Z — before the ResQRoute dataset and app existed.
     *
     * A claimed observation time earlier than this cannot be genuine: it is a sentinel
     * (`0`, `-1`) or a corrupt clock, so it is flagged rather than believed.
     *
     * Note carefully what this is **not**. It is not "older than N days", and the earlier
     * relative form of this rule was wrong: it labelled a genuinely two-year-old closure
     * "time not verified" instead of "very stale", conflating *old* with *implausible*.
     * In an app whose whole point is that reports persist until somebody rechecks the road,
     * an old report is the normal case, not a suspicious one. Only a time from before this
     * system existed is implausible.
     */
    const val EARLIEST_PLAUSIBLE_OBSERVATION_MILLIS: Long = 1_577_836_800_000L

    /**
     * The freshness of a segment's closure evidence, evaluated at [now].
     *
     * The basis is **the most recently received report that carries the exclusion** — the
     * corresponding observation, never the newest report of any kind on the segment
     * (plan §4.6, ADR 0001 §3). A mild report from an hour ago therefore cannot make an old
     * impassable report look freshly confirmed.
     *
     * Evaluation is a pure function of `(state, now)`: the same pair always yields the same
     * result, and no excluded segment is ever affected by the answer.
     */
    fun closureFreshness(state: SegmentObservationState, now: Long): SegmentFreshness =
        reportFreshness(state.reportsExcludingTraversal(), now)

    /**
     * The freshness of a set of reports, evaluated at [now].
     *
     * The basis is **the most recently received report in the set**, so the same rule serves a
     * closure's evidence and a reported hazard's evidence: an age always describes the observation
     * whose receipt it came from, never an unrelated newer report on the same segment (plan §4.6).
     *
     * An empty set has nothing to age and yields [ClosureFreshness.NO_CLOSURE] with a null age. The
     * enum's names read as *evidence* freshness throughout: `STALE` means "this evidence is old",
     * and `NO_CLOSURE` means "there is no evidence here to age" — nothing milder.
     *
     * Like [closureFreshness] this is a pure function of `(reports, now)` and is display-only.
     */
    fun reportFreshness(reports: List<ObservedReport>, now: Long): SegmentFreshness {
        val basis = reports.maxWithOrNull(compareBy({ it.receivedAtEpochMillis }, { it.eventId.value }))
            ?: return SegmentFreshness(
                label = ClosureFreshness.NO_CLOSURE,
                basis = null,
                ageMillis = null,
                claimedTimeVerified = true,
            )

        val age = now - basis.receivedAtEpochMillis
        val claimedTimeVerified = isClaimedTimePlausible(basis.occurredAtEpochMillis, now)

        val label = when {
            // The device clock moved backwards, or the row was stamped in the future:
            // we cannot say how old this is, and we must never claim that it is current.
            age < 0 -> ClosureFreshness.TIME_NOT_VERIFIED
            // The report's own claimed time is not believable. The age from our receipt is
            // still real, but the label must not assert currency for a report whose
            // provenance does not add up (ADR 0005 §8 requirement 11).
            !claimedTimeVerified -> ClosureFreshness.TIME_NOT_VERIFIED
            age <= STALE_AFTER_MILLIS -> ClosureFreshness.CURRENT
            age <= RECHECK_DEADLINE_AFTER_MILLIS -> ClosureFreshness.STALE
            else -> ClosureFreshness.VERY_STALE
        }

        return SegmentFreshness(
            label = label,
            basis = basis,
            ageMillis = age,
            claimedTimeVerified = claimedTimeVerified,
        )
    }

    /**
     * Whether a claimed observation time is believable, judged against [now].
     *
     * A claim is plausible when it is not in the future beyond [FUTURE_TIME_TOLERANCE_MILLIS]
     * and not earlier than [EARLIEST_PLAUSIBLE_OBSERVATION_MILLIS]. A *merely old* claim is
     * plausible: it makes the closure stale, not unverified.
     *
     * The answer is used **only to label**. An implausible claim never rejects a report and
     * never removes an exclusion (ADR 0005 §7.2): rejecting would discard evidence and would
     * be trivially exploitable — send a closure with a nonsense timestamp and the road never
     * closes. The claim is flagged, not repaired; clamping would present a fabricated value
     * as if somebody had really reported it.
     */
    fun isClaimedTimePlausible(claimedAtEpochMillis: Long, now: Long): Boolean {
        if (claimedAtEpochMillis < EARLIEST_PLAUSIBLE_OBSERVATION_MILLIS) return false
        if (claimedAtEpochMillis > now + FUTURE_TIME_TOLERANCE_MILLIS) return false
        return true
    }
}

/**
 * The freshness states of ADR 0005 §6.3.
 *
 * [CLEARED] is deliberately absent: clearances are ADR 0005's reopening mechanism and do not
 * exist as an event type yet, so a cleared state would be unreachable. It arrives with the
 * clearance event, not before.
 */
enum class ClosureFreshness {

    /** Nothing is closed on this segment, so there is no closure evidence to age. */
    NO_CLOSURE,

    /** Reported, and reported recently: `age ≤ 12 h`. */
    CURRENT,

    /** `12 h < age ≤ 72 h`. The road is still avoided; somebody should look again. */
    STALE,

    /** `age > 72 h`. The road is still avoided; nobody has looked for a long time. */
    VERY_STALE,

    /**
     * The age cannot be established, or the report's own claimed time is not believable.
     *
     * Rendered as "time not verified" — **never** as current. This is the state that exists
     * so that uncertainty is displayed instead of being rounded to "fresh".
     */
    TIME_NOT_VERIFIED,
}

/**
 * The freshness of one closure, with the observation it describes.
 *
 * [basis] is the report the label was computed from, so a caller can attribute it ("reported
 * by another device") without ever pairing a severity with a different report's timestamp.
 */
data class SegmentFreshness(
    val label: ClosureFreshness,
    val basis: ObservedReport?,
    /** Age from local receipt. Null when there is no closure, or the age is not computable. */
    val ageMillis: Long?,
    /** Whether [basis]'s claimed observation time is believable. */
    val claimedTimeVerified: Boolean,
) {
    /** True when the age could be established at all. */
    val ageIsKnown: Boolean get() = ageMillis != null && ageMillis >= 0
}
