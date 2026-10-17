package com.resqroute.feature.map.presentation

import android.content.Context
import com.resqroute.R
import com.resqroute.domain.hazard.ClosureFreshness

/**
 * How old a report is, worded in exactly one place (XPH-71).
 *
 * The route explanation, the reported-history rows and anything else that ages a report all come
 * through here, so the same age cannot read one way on a route and another way on a road's history.
 * The rule is ADR 0005 §6.2 and §7.1, unchanged:
 *
 *  * the age is measured from **this device's receipt** of the report, never from the origin's
 *    claimed time, because a relaying device could otherwise decide how new a report looks;
 *  * an age that cannot be established, or a claimed time that is not believable, renders
 *    **"reported, time not verified"** — an admission, never repaired into a plausible-looking
 *    duration;
 *  * a report received "in the future" (a device clock that moved backwards) is likewise not
 *    presented as current.
 *
 * The wordings are the `rq_route_age_*` resources, kept under that name because they were introduced
 * with the route explanation; their text is generic ("reported 3 h ago") and sharing them is what
 * makes one vocabulary rather than two that agree today.
 */
object AgeCopy {

    fun text(
        context: Context,
        ageMillis: Long?,
        timeVerified: Boolean,
        freshness: ClosureFreshness,
    ): String = when {
        !timeVerified -> context.getString(R.string.rq_route_age_unverified)
        freshness == ClosureFreshness.TIME_NOT_VERIFIED -> context.getString(R.string.rq_route_age_unverified)
        ageMillis == null -> context.getString(R.string.rq_route_age_unknown)
        ageMillis <= 0L -> context.getString(R.string.rq_route_age_just_now)
        ageMillis < MINUTE_MILLIS -> context.getString(R.string.rq_route_age_seconds, ageMillis / 1_000)
        ageMillis < HOUR_MILLIS -> context.getString(R.string.rq_route_age_minutes, ageMillis / MINUTE_MILLIS)
        ageMillis < DAY_MILLIS -> context.getString(R.string.rq_route_age_hours, ageMillis / HOUR_MILLIS)
        else -> context.getString(R.string.rq_route_age_days, ageMillis / DAY_MILLIS)
    }

    private const val MINUTE_MILLIS = 60_000L
    private const val HOUR_MILLIS = 60L * MINUTE_MILLIS
    private const val DAY_MILLIS = 24L * HOUR_MILLIS
}
