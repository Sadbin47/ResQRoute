package com.resqroute.feature.map.presentation

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Which part of the civil day a report's claimed time falls in (XPH-71).
 *
 * Two labels, and they are **context for reading a history row**, never an explanation of it: the
 * app has no model that relates time of day to water depth, and no statement anywhere may suggest
 * that one causes the other. A band is shown next to a time so a person can picture the scene
 * ("knee-deep at 02:10" reads differently from "knee-deep at 14:10") and for no other reason.
 */
enum class TimeBand {

    /** Local time from 06:00 up to, but not including, 18:00. */
    DAYLIGHT,

    /** Local time from 18:00 up to, but not including, 06:00. */
    NIGHT,
}

/**
 * The time-band rule and its local-time arithmetic (XPH-71).
 *
 * ## The rule, stated once
 *
 * A report is [TimeBand.DAYLIGHT] when its **claimed observation time**, read in the zone it is
 * rendered in, falls in `[06:00, 18:00)`; otherwise [TimeBand.NIGHT]. The boundaries are civil
 * clock hours and are **deliberately not a sunrise/sunset calculation**: computing real daylight
 * would need the date and the location, the app is offline, and a band is context — spending
 * astronomy on it would imply a precision nobody asked for. The two constants below are the whole
 * rule.
 *
 * ## Whose local time
 *
 * The zone is **injected**, never `TimeZone.getDefault()`, for two reasons. It makes the label a
 * pure function of `(claimed time, zone)` — so it is testable at a boundary and cannot change
 * because a device's settings changed. And it lets the caller name the zone it is describing: this
 * app renders times in the **road's** zone (the incident's), not the phone's, because the flood and
 * the road are in Feni whoever is reading about them. A phone carried in from another timezone
 * therefore cannot relabel what happened on that road.
 *
 * ## Claimed time, and what that means
 *
 * The band uses [com.resqroute.domain.hazard.ObservedReport.occurredAtEpochMillis] — the origin's
 * *claim*, which is untrusted (ADR 0003 §3) — because "when did you see this" is the question a
 * history row answers. It is **display only**: no band, and no time derived from it, may reach a
 * verdict, an exclusion, a penalty or a route. Where a claim is not credible the row says so
 * instead of showing a band it cannot stand behind (see `SegmentHistory`).
 */
object TimeBands {

    /** First hour of [TimeBand.DAYLIGHT], inclusive. */
    const val DAYLIGHT_START_HOUR: Int = 6

    /** First hour of [TimeBand.NIGHT], exclusive end of daylight. */
    const val DAYLIGHT_END_HOUR: Int = 18

    /** The band for a local hour of day, `0..23`. */
    fun bandOf(hourOfDay: Int): TimeBand {
        require(hourOfDay in 0..23) { "hour of day must be 0..23, was $hourOfDay" }
        return if (hourOfDay in DAYLIGHT_START_HOUR until DAYLIGHT_END_HOUR) {
            TimeBand.DAYLIGHT
        } else {
            TimeBand.NIGHT
        }
    }
    /**
     * The zone the bundled dataset's roads are in: Feni, Bangladesh.
     *
     * Chosen deliberately **over the device's zone**. The question a history row answers is "when did
     * this happen on that road", and that answer does not depend on where the reader is standing or
     * what their phone's settings say — a phone carried in from another zone must not be able to
     * relabel what happened in Feni. A constant rather than a device setting also makes the label
     * reproducible on every phone.
     *
     * `TimeZone.getTimeZone` silently falls back to GMT for an id it does not know, which would move
     * every band by six hours without saying anything, so a test asserts this id resolves.
     */
    const val INCIDENT_ZONE_ID: String = "Asia/Dhaka"

    /** [INCIDENT_ZONE_ID] resolved. See the constant for why it is not the device's zone. */
    fun incidentZone(): TimeZone = TimeZone.getTimeZone(INCIDENT_ZONE_ID)

    /** The local hour of day of [epochMillis] in [zone]. */
    fun hourOfDay(epochMillis: Long, zone: TimeZone): Int =
        calendarAt(epochMillis, zone).get(Calendar.HOUR_OF_DAY)

    /**
     * A 24-hour clock reading, `HH:mm`, in [zone].
     *
     * Built from the calendar fields rather than a `SimpleDateFormat` so the digits are the Latin
     * ones the rest of the sheet uses whatever the device locale is, and so nothing about the
     * output depends on locale data.
     */
    fun clockText(epochMillis: Long, zone: TimeZone): String {
        val calendar = calendarAt(epochMillis, zone)
        return "%02d:%02d".format(
            Locale.US,
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
        )
    }

    /**
     * The zone's offset at [atMillis], as `UTC+06:00`.
     *
     * Rendered beside the zone id so the rule is stated, not just named: "Asia/Dhaka (UTC+06:00)".
     * The offset is evaluated at the instant being described, so a zone that ever changed its
     * offset would be described as it was for that observation.
     */
    fun offsetText(zone: TimeZone, atMillis: Long): String {
        val totalMinutes = zone.getOffset(atMillis) / 60_000
        val sign = if (totalMinutes < 0) "-" else "+"
        val absolute = kotlin.math.abs(totalMinutes)
        return "UTC%s%02d:%02d".format(Locale.US, sign, absolute / 60, absolute % 60)
    }

    private fun calendarAt(epochMillis: Long, zone: TimeZone): Calendar =
        Calendar.getInstance(zone, Locale.US).apply { timeInMillis = epochMillis }
}
