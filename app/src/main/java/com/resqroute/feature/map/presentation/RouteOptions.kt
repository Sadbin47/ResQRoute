package com.resqroute.feature.map.presentation

import com.resqroute.core.routing.RoutePath

/**
 * One offered route, with the facts a presentation layer needs to describe it honestly.
 *
 * [crossesReportedRisk] is computed by the view model from the same snapshot that produced the
 * route, so no caller has to re-derive it — and no caller can quietly forget to. It says only that
 * the route passes a segment **somebody reported** as risky; it is not a safety statement, and a
 * route with `false` is not thereby safe (ADR 0001 D6: unreported is unknown, not clear).
 */
data class OfferedRoute(
    val route: RoutePath,
    /** True when the route crosses at least one segment a report priced as risky. */
    val crossesReportedRisk: Boolean,
)

/**
 * The routes offered for one decision (XPH-76).
 *
 * The view model computes two routes from **one** snapshot and **one** generation — the one that
 * minimises distance, and the one that minimises distance plus reported risk — and publishes them
 * as a single guarded unit, so a caller can never hold a lower-risk route from one revision beside
 * a shortest route from another.
 *
 * ## Why a sealed type rather than a pair with a nullable second element
 *
 * [Identical] is a meaningful outcome, not a degenerate case. When both policies choose the same
 * path there is **one** route and no choice to present; modelling it as two equal options would let
 * a presentation layer draw a comparison that does not exist and imply the user is choosing between
 * alternatives. Modelling it as (`route`, `null`) would instead leave the caller to remember which
 * element may be absent.
 *
 * Two paths are treated as the same route when they traverse the same segments — the road the user
 * would actually travel. Their *costs* may still differ, because the same path priced with penalties
 * is more expensive; the geometry is what makes them one answer.
 *
 * ## What this type deliberately does not say
 *
 * It carries no wording, no ranking and no recommendation: which route to draw, how to label it and
 * what to warn about are the presentation rules owned by XPH-77. It carries no safety claim either
 * — in particular, neither option is described as safe, and the absence of a second option never
 * means the single option avoids reported risk; [OfferedRoute.crossesReportedRisk] carries that
 * fact so a caller cannot present a risky route as a clean one.
 */
sealed interface RouteOptions {

    /** The routes on offer, shortest first. One element for [Identical], two for [Divergent]. */
    val offered: List<OfferedRoute>

    /** Both policies chose the same path. */
    data class Identical(val only: OfferedRoute) : RouteOptions {
        override val offered: List<OfferedRoute> get() = listOf(only)
    }

    /** The policies chose different paths, so the user has a real choice. */
    data class Divergent(
        /** Minimises distance; ignores reported risk. */
        val shortest: OfferedRoute,
        /** Minimises distance plus reported risk, from the same snapshot. */
        val lowerRisk: OfferedRoute,
    ) : RouteOptions {
        override val offered: List<OfferedRoute> get() = listOf(shortest, lowerRisk)
    }

    companion object {
        /**
         * Collapses two computed routes into the outcome they represent.
         *
         * Compares the segments travelled rather than the whole path, because the same road reached
         * by a different sequence of graph edges is still the same road to the person walking it.
         */
        fun of(shortest: OfferedRoute, lowerRisk: OfferedRoute): RouteOptions =
            if (shortest.route.segmentIds == lowerRisk.route.segmentIds) {
                Identical(shortest)
            } else {
                Divergent(shortest, lowerRisk)
            }
    }
}
