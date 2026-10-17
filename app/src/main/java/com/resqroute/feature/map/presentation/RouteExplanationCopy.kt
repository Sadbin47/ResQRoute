package com.resqroute.feature.map.presentation

import android.content.Context
import com.resqroute.R
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.WaterLevelCategory

/**
 * The words the app uses to explain a route (XPH-77).
 *
 * The facts live in [RouteExplanation]; this is the only place they become sentences, so the wording
 * rules are testable rather than aspirational. Nothing here ranks routes as "better", nothing
 * recommends one, and **nothing describes any route as safe**. Every hazard rendered here is a
 * *priced* report: a road the app could not read excludes the road instead, so it never appears as
 * something a route crosses.
 *
 * ## Why unknown coverage is not a percentage
 *
 * The disclosure must be quantitative, so a route that is mostly unreported is visibly such. It is
 * rendered as a **length of a total** — *"No reports for 4 200 m of this 28 490 m route"* — rather
 * than as a percentage, because the wording gate that protects every hazard string refuses a `%`
 * and the word "percent" anywhere in this flow. That gate exists to stop confidence claims, and it
 * is easier to keep it absolute than to carve an exception and hope the exception stays narrow. A
 * length of a total is just as quantitative and cannot be mistaken for a probability.
 *
 * A second line states plainly when most of the route is unreported, because "4 200 m" asks the
 * reader to do the comparison themselves and the point is that they should not have to.
 *
 * ## The register
 *
 * Reported roads are *reported* roads. A route that crosses none is **not** described as clear: the
 * app says how many were reported, and the unknown-coverage line says how much nobody has looked at.
 * The absence of reports is never presented as the absence of water.
 */
object RouteExplanationCopy {

    private const val SEPARATOR = " · "

    /**
     * A route's headline: how far it is.
     *
     * The unreported length is deliberately **not** repeated here; [unknownLine] owns that sentence,
     * and stating it twice invites the two copies to drift.
     */
    fun routeLine(context: Context, report: RouteReport): String =
        context.getString(R.string.rq_route_line, meters(report.route.distanceMeters))

    /**
     * The comparison between the two routes, or the statement that there is only one.
     *
     * For [RouteExplanation.Single] the honest sentence is that the lower-risk search chose the same
     * road — not that the road is free of reports.
     */
    fun comparisonLine(context: Context, explanation: RouteExplanation): String = when (explanation) {
        is RouteExplanation.Single -> context.getString(R.string.rq_route_same)
        is RouteExplanation.Comparison -> {
            val shortest = explanation.shortest.route.distanceMeters
            val lowerRisk = explanation.lowerRisk.route.distanceMeters
            val delta = lowerRisk - shortest
            when {
                delta <= 0.5 -> context.getString(R.string.rq_route_same_length)
                else -> context.getString(
                    R.string.rq_route_longer,
                    meters(delta),
                    meters(lowerRisk),
                )
            }
        }
    }

    /** What the lower-risk route avoids, as a count. Null when it avoids nothing. */
    fun avoidedLine(context: Context, explanation: RouteExplanation): String? =
        (explanation as? RouteExplanation.Comparison)
            ?.takeIf { it.hazardsAvoidedByLowerRisk.isNotEmpty() }
            ?.let {
                val count = it.hazardsAvoidedByLowerRisk.size
                context.resources.getQuantityString(R.plurals.rq_route_avoids, count, count)
            }

    /**
     * The reason a route crosses something, spelled out, or null when it crosses nothing.
     *
     * Stated for **every** route that crosses a report, including the recommended one: a route over
     * reported flooding must never be presented as the clean answer.
     */
    fun crossedLine(context: Context, report: RouteReport): String? =
        if (report.crossesReportedRisk) {
            val count = report.hazardsCrossed.size
            context.resources.getQuantityString(R.plurals.rq_route_crosses, count, count)
        } else {
            null
        }

    /**
     * The honest statement when there is nothing better to offer.
     *
     * Two distinct cases, both worth saying out loud: one route that crosses a report (which is the
     * moment the user most needs to know there was no alternative), and several routes where every
     * one of them crosses something.
     */
    fun noAlternativeLine(context: Context, explanation: RouteExplanation): String? = when {
        explanation.everyCandidateCrossesReportedRisk && explanation.hasLowerRiskAlternative ->
            context.getString(R.string.rq_route_all_cross)
        explanation.everyCandidateCrossesReportedRisk ->
            context.getString(R.string.rq_route_no_alternative)
        else -> null
    }

    /**
     * The header for the reported roads on a route.
     *
     * It names the route's own distance, because with two routes listed one after another a bare
     * "reported on this route" leaves the reader to work out which route "this" is.
     */
    fun avoidHeader(context: Context, report: RouteReport): String? =
        if (report.hazardsCrossed.isEmpty()) {
            null
        } else {
            context.getString(
                R.string.rq_route_reported_header,
                meters(report.route.distanceMeters),
                report.hazardsCrossed.size,
            )
        }

    /** One reported road: stable id, what was reported, how old, and how well supported. */
    fun avoidEntry(context: Context, hazard: RouteHazard): String = context.getString(
        R.string.rq_route_reported_entry,
        hazard.segmentId.stableId,
        category(context, hazard),
        age(context, hazard),
        support(context, hazard),
    )

    /**
     * What was reported, in plain words.
     *
     * An axis that states nothing is omitted rather than rendered as a value. There is no
     * "unreadable" case: a row this build cannot read excludes the road, so it is never traversed
     * and never listed here.
     */
    fun category(context: Context, hazard: RouteHazard): String {
        val parts = listOfNotNull(
            waterLevel(context, hazard.waterLevel).takeIf { hazard.waterLevel.statesSomething },
            passability(context, hazard.passability).takeIf { hazard.passability.statesSomething },
        )
        return if (parts.isEmpty()) {
            context.getString(R.string.rq_route_entry_unstated)
        } else {
            parts.joinToString(SEPARATOR)
        }
    }

    /**
     * How old the evidence is, from local receipt, or an admission that it cannot be established.
     *
     * An unverified claimed time is labelled, never repaired into a plausible-looking age. The
     * wording ladder lives in [AgeCopy] so a route hazard and a history row age identically.
     */
    fun age(context: Context, hazard: RouteHazard): String =
        AgeCopy.text(context, hazard.ageMillis, hazard.timeVerified, hazard.freshness)

    /**
     * Support, as a count of **device IDs**, with who told us.
     *
     * Never "people", never a probability (ADR 0001 D5). A device is not a person, and two reports
     * from one phone are one device.
     */
    fun support(context: Context, hazard: RouteHazard): String {
        val base = context.resources.getQuantityString(
            R.plurals.rq_route_support,
            hazard.supportCount,
            hazard.supportCount,
        )
        val attribution = buildList {
            if (hazard.directSupportCount > 0) {
                add(context.getString(R.string.rq_support_attribution_direct, hazard.directSupportCount))
            }
            if (hazard.relayedSupportCount > 0) {
                add(context.getString(R.string.rq_support_attribution_relayed, hazard.relayedSupportCount))
            }
        }
        return if (attribution.isEmpty()) base else base + " (" + attribution.joinToString(SEPARATOR) + ")"
    }

    /** The unknown-coverage disclosure for one route. */
    fun unknownLine(context: Context, report: RouteReport): String = context.getString(
        R.string.rq_route_unknown,
        meters(report.unknownLengthMeters),
        meters(report.totalLengthMeters),
    )

    /**
     * A plain statement when most of a route is unreported, so the reader does not have to compare
     * two numbers to notice.
     */
    fun mostlyUnknownLine(context: Context, report: RouteReport): String? =
        if (report.unknownProportion > MOSTLY_UNKNOWN_PROPORTION && report.unknownProportion < 1.0) {
            context.getString(R.string.rq_route_mostly_unknown)
        } else {
            null
        }

    /** Everything the sheet shows for this decision, in reading order. */
    fun sheetLines(context: Context, explanation: RouteExplanation): List<String> = buildList {
        add(comparisonLine(context, explanation))
        avoidedLine(context, explanation)?.let { add(it) }
        noAlternativeLine(context, explanation)?.let { add(it) }
        for (report in explanation.reports) {
            add(routeLine(context, report))
            crossedLine(context, report)?.let { add(it) }
            avoidHeader(context, report)?.let { add(it) }
            report.hazardsCrossed.forEach { add(avoidEntry(context, it)) }
            add(unknownLine(context, report))
            mostlyUnknownLine(context, report)?.let { add(it) }
        }
    }

    fun waterLevel(context: Context, level: WaterLevelCategory): String = when (level) {
        WaterLevelCategory.NO_VISIBLE_WATER -> context.getString(R.string.rq_water_none)
        WaterLevelCategory.ANKLE -> context.getString(R.string.rq_water_ankle)
        WaterLevelCategory.KNEE -> context.getString(R.string.rq_water_knee)
        WaterLevelCategory.WAIST -> context.getString(R.string.rq_water_waist)
        WaterLevelCategory.ABOVE_WAIST -> context.getString(R.string.rq_water_above_waist)
        WaterLevelCategory.UNKNOWN -> context.getString(R.string.rq_water_unknown)
    }

    fun passability(context: Context, status: PassabilityStatus): String = when (status) {
        PassabilityStatus.PASSABLE_REPORTED -> context.getString(R.string.rq_pass_passable)
        PassabilityStatus.CAUTION -> context.getString(R.string.rq_pass_caution)
        PassabilityStatus.AVOID -> context.getString(R.string.rq_pass_avoid)
        PassabilityStatus.IMPASSABLE -> context.getString(R.string.rq_pass_impassable)
        PassabilityStatus.UNKNOWN -> context.getString(R.string.rq_pass_unknown)
    }

    /** Metres, grouped, no decimal noise. */
    private fun meters(value: Double): String = "%,.0f".format(value)

    /**
     * Above this share of a route with no reports, say so in words as well as in metres.
     *
     * A *wholly* unreported route is excluded from the emphasis line: "No reports for 480 m of this
     * 480 m route" already says it completely, and repeating it adds noise rather than weight.
     */
    private const val MOSTLY_UNKNOWN_PROPORTION = 0.5
}
