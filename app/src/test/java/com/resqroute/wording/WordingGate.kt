package com.resqroute.wording

/**
 * The wording gate: one place that decides what the app may and may not say.
 *
 * Every user-facing hazard and route string is checked against this by tests. It exists because
 * ADR 0001 D5's rule — a device count is not a probability, a report is not a verification — is the
 * kind of rule that erodes the moment it lives only in prose.
 *
 * ## Why the token list is absolute, with one narrow carve-out
 *
 * The list refuses the vocabulary of certainty: *people*, *confidence*, *percent*, *probability*,
 * *verified*, *confirmed*, *certain*, *safe*, *guarantee*. A route with no reports is **unreported**,
 * not safe, and a report is **provenance**, not truth.
 *
 * [APPROVED_ADMISSIONS] is the single exception, and it exists because ADR 0005 §6.3 *mandates* one
 * of those words: the sanctioned label for an undecidable age is **"time not verified"**. That phrase
 * is an admission of uncertainty, which is the opposite of the claim the ban targets, so it is
 * stripped before the check rather than the ban being weakened. Everything else still fails:
 * "time not verified" passes, "this route is verified" does not.
 */
internal object WordingGate {

    /**
     * Words and phrases that claim more than the evidence can support.
     *
     * The scan is case-insensitive and runs on the **rendered** text as well as the string
     * resources, so a smuggled phrase cannot hide behind a format placeholder.
     */
    val FORBIDDEN_TOKENS = listOf(
        "people",
        "person",
        "confidence",
        "percent",
        "probab",
        "chance",
        "verified",
        "confirmed",
        "certain",
        "safe",
        "guarantee",
    )

    /**
     * Mandated admissions of uncertainty that happen to contain a forbidden token.
     *
     * Kept to the shortest exact phrases, so the carve-out cannot grow into a loophole: see
     * [violations] for how they are removed, and `WordingGateTest` for the proof that a claim of
     * verification is still refused.
     */
    val APPROVED_ADMISSIONS = listOf("time not verified")

    /** Forbidden tokens present in [text], after removing the approved admissions. */
    fun violations(text: String): List<String> {
        val checked = text.lowercase().let { lower ->
            APPROVED_ADMISSIONS.fold(lower) { acc, phrase -> acc.replace(phrase, "") }
        }
        return FORBIDDEN_TOKENS.filter { checked.contains(it) }
    }

    /**
     * A `%` anywhere in rendered text is refused.
     *
     * Run only on rendered output, never on raw resources, where `%1$s` is a format placeholder and
     * not a claim. Unknown coverage is therefore disclosed as a length of a total rather than a
     * percentage — just as quantitative, and impossible to mistake for a probability.
     */
    fun renderedViolations(text: String): List<String> {
        val violations = violations(text).toMutableList()
        if (text.contains("%")) violations += "%"
        return violations
    }
}
