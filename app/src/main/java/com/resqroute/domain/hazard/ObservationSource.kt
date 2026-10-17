package com.resqroute.domain.hazard

/**
 * How this device came to know about a report.
 *
 * This is the **provenance of knowledge, not a measure of truth**. It answers "did we see
 * this ourselves, or did somebody tell us?", never "is this correct?". Nothing here may be
 * rendered as verification, and a report from a stronger source is not thereby true.
 *
 * ## How it is decided
 *
 * [DIRECT_OBSERVATION] means *this device holds first-hand knowledge*: it is the origin of
 * the report. [RELAYED_ACCOUNT] means the report reached us from a peer.
 *
 * That is derived, not stored: `originDeviceId == this device's id` is exactly the question,
 * and it stays right across replay, reinstall and relay chains because the origin identity
 * travels with the event. Persisting it would be a second source of truth for something the
 * event already says — and it would be a *local* fact stored in a *shared* row, so a relayed
 * copy would carry the wrong value on every device that re-stored it.
 *
 * `HazardEventEntity.sourceCode` therefore stays reserved and null: it exists for the one
 * source that **cannot** be derived, below.
 *
 * ## Not reachable today
 *
 * [OFFICIAL_STATEMENT] is reserved for preloaded or imported official data. XPH-86 owns
 * whether such a source exists at all, how it is distributed offline, and whether it may
 * clear a road without human confirmation (ADR 0005 §8 leaves that open). Until then no code
 * path produces it, and this enum is added now only because a clearance must carry a source
 * (ADR 0005 §5.2) and XPH-74's weighting will want the taxonomy.
 *
 * ## A caveat worth stating
 *
 * A peer can claim any `originDeviceId`, including ours. A report we did not observe could
 * therefore be labelled [DIRECT_OBSERVATION]. The consequence is bounded to attribution:
 * the origin device id is unchanged either way, so the distinct-device count is unaffected,
 * and no verdict, exclusion or route reads the source. Authenticating a peer's identity is
 * out of scope here and would be a protocol decision.
 *
 * See `docs/decisions/0001-v2-observation-and-risk-semantics.md` (D5) and
 * `docs/decisions/0008-support-source-typing-and-disagreement.md`.
 */
enum class ObservationSource(val code: String) {

    /** This device is the origin of the report: first-hand, unrelayed knowledge. */
    DIRECT_OBSERVATION("DIRECT_OBSERVATION"),

    /** The report reached this device from a peer. Its origin device is named on the report. */
    RELAYED_ACCOUNT("RELAYED_ACCOUNT"),

    /** Reserved for imported official data. **Not reachable today** — see XPH-86. */
    OFFICIAL_STATEMENT("OFFICIAL_STATEMENT"),
    ;

    companion object {
        /**
         * Null for an unknown code; callers decide what that means. Codes are pinned for
         * persistence and the wire, so renaming one is a protocol change, not a refactor.
         */
        fun fromCode(code: String): ObservationSource? = entries.firstOrNull { it.code == code }

        /**
         * Classifies a stored report by comparing its origin device with ours.
         *
         * False for a null [localDeviceId]: a device that does not know its own identity
         * cannot claim authorship of anything, so the conservative answer is "relayed".
         */
        fun of(originDeviceId: String, localDeviceId: String?): ObservationSource =
            if (localDeviceId != null && originDeviceId == localDeviceId) {
                DIRECT_OBSERVATION
            } else {
                RELAYED_ACCOUNT
            }
    }
}
