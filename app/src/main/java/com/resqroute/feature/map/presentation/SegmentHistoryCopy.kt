package com.resqroute.feature.map.presentation

import android.content.Context
import com.resqroute.R

/**
 * The words a road's reported history is read in (XPH-71).
 *
 * The facts live in [SegmentHistory]; this is the only place they become sentences, so the wording
 * rules are testable rather than aspirational — the same shape as `RouteExplanationCopy` for routes
 * and `HazardSupportCopy` for a segment's current reports.
 *
 * ## What this file exists to keep
 *
 *  * **History, not a prediction.** Every line says what was reported and when. The trend sentence
 *    is stated about the *reports* ("Reported over time: … (rising)") and never about the road, and
 *    nothing here says or implies that the road is usable now.
 *  * **Time of day is context.** A band ("daylight" / "night") is attached to a time so a person can
 *    picture the scene. It never explains a depth: no line joins the two with a causal word, because
 *    the app has no model that relates them.
 *  * **The timezone rule is stated, not assumed.** The times are rendered in the road's zone and the
 *    caption says which one and what its offset was, so a reader in another zone knows what they are
 *    reading ([TimeBands] explains why the road's zone and not the phone's).
 *  * **An unverifiable time is labelled.** A row whose claimed time is not believable shows no clock
 *    and no band; it says "time not verified" and ages through the same ladder as every other report
 *    ([AgeCopy]).
 *  * **Sparse data is stated.** No reports, one report, or reports that cannot be compared each get
 *    their own honest sentence instead of an invented direction.
 *  * **A device count is a count of device IDs** — never people, never a probability (ADR 0001 D5).
 *    The support line comes from `HazardSupportCopy`, so this file cannot invent a second vocabulary.
 */
object SegmentHistoryCopy {

    /** "Road S012733" — the stable graph id, so a person can name the road they are reading about. */
    fun headerLine(context: Context, history: SegmentHistory): String =
        context.getString(R.string.rq_history_road, history.segmentId.stableId)

    /**
     * How many distinct device IDs reported, and how we came to know them.
     *
     * Empty when nothing has been reported: "reports from 0 distinct device IDs" is noise, and the
     * sparse-data sentence says it better.
     */
    fun supportLines(context: Context, history: SegmentHistory): List<String> {
        if (!history.hasAnyReport) return emptyList()
        return buildList {
            add(HazardSupportCopy.supportLine(context, history.supportCount))
            HazardSupportCopy.attributionLine(context, history.reportingDeviceIdsBySource)?.let { add(it) }
        }
    }

    /** One line per committed report, oldest first. */
    fun rowLines(context: Context, history: SegmentHistory): List<String> =
        history.entries.map { entryLine(context, it) }

    /**
     * One history row: when, what was stated, who told us, and how old it is.
     *
     * "What was stated" comes from `HazardSupportCopy.observationText`, which already knows how to
     * describe an unreadable row and a clearance, and omits an axis that states nothing — so a row
     * here cannot present an axis the report never stated.
     */
    fun entryLine(context: Context, entry: HistoryEntry): String = context.getString(
        R.string.rq_history_entry,
        timeText(context, entry),
        HazardSupportCopy.observationText(context, entry.report),
        HazardSupportCopy.reporterText(context, entry.report),
        AgeCopy.text(context, entry.ageMillis, entry.timeVerified, entry.freshness),
    )

    /** "14:10 daylight", or the admission that the claimed time cannot be used. */
    fun timeText(context: Context, entry: HistoryEntry): String =
        if (entry.timeVerified) {
            context.getString(R.string.rq_history_time, entry.localTime, bandText(context, entry.band))
        } else {
            context.getString(R.string.rq_history_time_unverified)
        }

    /** The band label. Context for the time; never an explanation of the depth. */
    fun bandText(context: Context, band: TimeBand): String = when (band) {
        TimeBand.DAYLIGHT -> context.getString(R.string.rq_history_band_daylight)
        TimeBand.NIGHT -> context.getString(R.string.rq_history_band_night)
    }

    /**
     * The one sentence the log supports about how the reported depth changed, or why it cannot.
     *
     * The direction word is scoped to the reports by the sentence's own opening ("Reported over
     * time"), so "rising" can never read as a statement about the water now. See [SegmentHistory]
     * for the rule that decides which two reports are compared.
     */
    fun trendLine(context: Context, history: SegmentHistory): String = when (val trend = history.trend) {
        ReportedTrend.Insufficient -> when {
            !history.hasAnyReport -> context.getString(R.string.rq_history_none)
            history.entries.size == 1 -> context.getString(R.string.rq_history_one)
            else -> context.getString(R.string.rq_history_not_comparable)
        }

        is ReportedTrend.Change -> context.getString(
            R.string.rq_history_trend_change,
            HazardSupportCopy.waterLevelText(context, trend.older.waterLevel),
            trend.older.localTime,
            HazardSupportCopy.waterLevelText(context, trend.newer.waterLevel),
            trend.newer.localTime,
            directionText(context, trend.direction),
        )
    }

    /** The direction word in the trend sentence. */
    fun directionText(context: Context, direction: TrendDirection): String = when (direction) {
        TrendDirection.RISING -> context.getString(R.string.rq_history_trend_rising)
        TrendDirection.FALLING -> context.getString(R.string.rq_history_trend_falling)
        TrendDirection.STEADY -> context.getString(R.string.rq_history_trend_steady)
    }

    /**
     * The timezone rule, as a sentence: which zone the times are in and what its offset was.
     *
     * Null when there are no times on screen, because a rule about nothing is noise.
     */
    fun zoneLine(context: Context, history: SegmentHistory): String? =
        if (!history.hasAnyReport) {
            null
        } else {
            context.getString(R.string.rq_history_zone, history.zoneId, history.zoneOffsetText)
        }

    /**
     * The caption under the block: what this block is not, and the timezone the times use.
     *
     * Both halves matter. The first is the issue's own rule (never a prediction, never evidence that
     * the road is usable now); the second states the timezone rule the bands were computed under.
     */
    fun captionLine(context: Context, history: SegmentHistory): String = buildString {
        append(context.getString(R.string.rq_history_note))
        zoneLine(context, history)?.let { append(' ').append(it) }
    }

    /**
     * The block's body, in reading order, below the section heading.
     *
     * The caption is deliberately **not** included: the sheet renders it as a separate caption line
     * (see [captionLine]), and a caller that wants the whole block composes the two.
     */
    fun bodyLines(context: Context, history: SegmentHistory): List<String> = buildList {
        add(headerLine(context, history))
        addAll(supportLines(context, history))
        addAll(rowLines(context, history))
        add(trendLine(context, history))
    }
}
