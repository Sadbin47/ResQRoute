package com.resqroute.feature.map.presentation

import android.content.Context
import com.resqroute.R
import com.resqroute.domain.hazard.DeviceId
import com.resqroute.domain.hazard.ObservationSource
import com.resqroute.domain.hazard.ObservedReport
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.ReportContent
import com.resqroute.domain.hazard.RoadObservation
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory

/**
 * The words the app uses about road reports (XPH-73).
 *
 * This is the presentation-input layer the roadmap asks for: it turns the derived state into
 * the exact lines a screen shows, in one place, so the wording rules are **testable** rather
 * than aspirational. XPH-70 renders these; nothing here touches a route, a verdict or a count.
 *
 * ## The rules this file exists to keep
 *
 *  * A support count is a count of **device IDs**. Never "people", never a percentage, never
 *    "confidence", never "verified" (ADR 0001 D5). A device is not a person and two reports
 *    from one phone are one device.
 *  * A source is **provenance, not truth**. "Another device reported this" says who told us,
 *    never that the report is correct.
 *  * Reports that differ are shown **side by side, with their own sources**, and are never
 *    averaged into a single value. An average of ordinal labels would be a fabricated
 *    measurement (ADR 0001 D1).
 *  * A report this build cannot read is described as such. It still excludes the road, and
 *    its details are not invented for display.
 */
object HazardSupportCopy {

    /** Separates two fields of one report, matching the app's existing hint style. */
    private const val FIELD_SEPARATOR = " · "

    /** Separates two attributed counts. */
    private const val ATTRIBUTION_SEPARATOR = " · "

    /** "reports from 2 distinct device IDs" — the only sanctioned way to state support. */
    fun supportLine(context: Context, state: SegmentObservationState): String =
        supportLine(context, state.supportCount)

    /**
     * The same sentence for a caller that holds the count without the state (XPH-71's history).
     *
     * One sentence for both callers, so a road's history and its current state cannot word support
     * differently.
     */
    fun supportLine(context: Context, deviceCount: Int): String =
        context.resources.getQuantityString(
            R.plurals.rq_support_devices,
            deviceCount,
            deviceCount,
        )

    /**
     * "1 from this device · 2 relayed", listing only the ways that actually occurred.
     *
     * Null when nothing has been reported, because there is no attribution to show.
     */
    fun attributionLine(context: Context, state: SegmentObservationState): String? =
        attributionLine(context, state.reportingDeviceIdsBySource)

    /** The same sentence for a caller that holds the attribution without the state (XPH-71). */
    fun attributionLine(context: Context, bySource: Map<ObservationSource, Set<DeviceId>>): String? {
        if (bySource.values.all { it.isEmpty() }) return null
        val parts = mutableListOf<String>()
        bySource.getValue(ObservationSource.DIRECT_OBSERVATION).size
            .takeIf { it > 0 }
            ?.let { parts += context.getString(R.string.rq_support_attribution_direct, it) }
        bySource.getValue(ObservationSource.RELAYED_ACCOUNT).size
            .takeIf { it > 0 }
            ?.let { parts += context.getString(R.string.rq_support_attribution_relayed, it) }
        bySource.getValue(ObservationSource.OFFICIAL_STATEMENT).size
            .takeIf { it > 0 }
            ?.let { parts += context.getString(R.string.rq_support_attribution_official, it) }
        return parts.joinToString(ATTRIBUTION_SEPARATOR)
    }

    /**
     * The header and one line per report, or empty when the reports do not differ.
     *
     * Every report is listed, not just the extremes: the point is that the difference is
     * visible and attributable, not that it is resolved.
     */
    fun disagreementLines(context: Context, state: SegmentObservationState): List<String> {
        if (!state.hasDisagreement && !state.hasTraversalDisagreement) return emptyList()
        return buildList {
            add(context.getString(R.string.rq_disagreement_header))
            state.reports.forEach { add(reportLine(context, it)) }
        }
    }

    /** One report: "knee-deep · caution — another device". */
    fun reportLine(context: Context, report: ObservedReport): String =
        context.getString(
            R.string.rq_disagreement_entry,
            observationText(context, report),
            reporterText(context, report),
        )

    /**
     * What was observed, in plain words, or an honest admission that it cannot be read.
     *
     * An axis that states nothing is omitted rather than rendered as a value: "depth not
     * estimated" is information, but pairing it with nothing would read as a measurement.
     */
    fun observationText(context: Context, report: ObservedReport): String =
        when (val content = report.content) {
            is ReportContent.Unreadable -> context.getString(R.string.rq_unreadable_report)
            is ReportContent.Cleared -> axesText(context, content.clearance.observation) +
                FIELD_SEPARATOR + context.getString(R.string.rq_report_cleared)
            is ReportContent.Observed -> axesText(context, content.observation)
        }

    /**
     * Both axes of one observation, omitting an axis that states nothing.
     *
     * Shared by an ordinary report and a clearance, because a clearance states axes exactly like any
     * other observation — ADR 0005 §8 requirement 7 makes clearing a segment with no closures record an
     * ordinary passable observation, so the two must read the same way.
     */
    private fun axesText(context: Context, observation: RoadObservation): String = listOfNotNull(
        waterLevelText(context, observation.waterLevel)
            .takeIf { observation.waterLevel.statesSomething },
        passabilityText(context, observation.passability)
            .takeIf { observation.passability.statesSomething },
    ).joinToString(FIELD_SEPARATOR)

    /** Who told us. Provenance only. */
    fun reporterText(context: Context, report: ObservedReport): String = when (report.source) {
        ObservationSource.DIRECT_OBSERVATION -> context.getString(R.string.rq_reporter_this_device)
        ObservationSource.RELAYED_ACCOUNT -> context.getString(R.string.rq_reporter_another_device)
        ObservationSource.OFFICIAL_STATEMENT -> context.getString(R.string.rq_reporter_official)
    }

    fun waterLevelText(context: Context, level: WaterLevelCategory): String = when (level) {
        WaterLevelCategory.NO_VISIBLE_WATER -> context.getString(R.string.rq_water_none)
        WaterLevelCategory.ANKLE -> context.getString(R.string.rq_water_ankle)
        WaterLevelCategory.KNEE -> context.getString(R.string.rq_water_knee)
        WaterLevelCategory.WAIST -> context.getString(R.string.rq_water_waist)
        WaterLevelCategory.ABOVE_WAIST -> context.getString(R.string.rq_water_above_waist)
        WaterLevelCategory.UNKNOWN -> context.getString(R.string.rq_water_unknown)
    }

    fun passabilityText(context: Context, status: PassabilityStatus): String = when (status) {
        PassabilityStatus.PASSABLE_REPORTED -> context.getString(R.string.rq_pass_passable)
        PassabilityStatus.CAUTION -> context.getString(R.string.rq_pass_caution)
        PassabilityStatus.AVOID -> context.getString(R.string.rq_pass_avoid)
        PassabilityStatus.IMPASSABLE -> context.getString(R.string.rq_pass_impassable)
        PassabilityStatus.UNKNOWN -> context.getString(R.string.rq_pass_unknown)
    }

    /**
     * Everything the app would say about this segment, in reading order.
     *
     * This is the renderable form of the derived state: a screen shows these lines, and the
     * wording gate asserts they never claim more than the app knows.
     */
    fun summaryLines(context: Context, state: SegmentObservationState): List<String> = buildList {
        add(supportLine(context, state))
        attributionLine(context, state)?.let { add(it) }
        addAll(disagreementLines(context, state))
    }
}
