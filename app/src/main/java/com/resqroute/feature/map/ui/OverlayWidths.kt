package com.resqroute.feature.map.ui

/**
 * Zoom-aware stroke widths for the route and hazard layers.
 *
 * At the district-wide default zoom a 6 dp route line is wider than the spacing
 * between two parallel streets, so it visually covers roads the router never
 * traversed — including blocked ones. Two rules fix that:
 *
 *  1. the route line gets thinner as the camera zooms out, and
 *  2. every hazard stroke stays **strictly wider** than the route stroke, so a
 *     blocked road is always visible wherever the two overlap.
 *
 * Pure arithmetic, so the policy is unit-testable without Android.
 */
object OverlayWidths {

    /** Thinnest route line, used at district zoom. */
    const val ROUTE_MIN_DP = 3.5f

    /** Thickest route line, used at street zoom. */
    const val ROUTE_MAX_DP = 6.0f

    /** Below this zoom the route uses [ROUTE_MIN_DP]. */
    const val ZOOM_AT_MIN_WIDTH = 12.0

    /** Above this zoom the route uses [ROUTE_MAX_DP]. */
    const val ZOOM_AT_MAX_WIDTH = 16.0

    /** How much wider a committed block is than the route that crosses it. */
    const val BLOCKED_OVERHANG_DP = 3.0f

    /** Reported-but-usable roads sit just above the route: visible, but never as loud as a block. */
    const val REPORTED_OVERHANG_DP = 1.0f

    /** Pending marks sit between the route and a committed block. */
    const val PENDING_OVERHANG_DP = 2.0f

    /** Ambiguity candidates never cover the route they may block. */
    const val CANDIDATE_DP = 4.0f

    /** Selection casing, drawn widest so the highlighted road stays readable. */
    const val SELECTION_DP = 9.0f

    fun routeDp(zoom: Double): Float = when {
        zoom <= ZOOM_AT_MIN_WIDTH -> ROUTE_MIN_DP
        zoom >= ZOOM_AT_MAX_WIDTH -> ROUTE_MAX_DP
        else -> {
            val t = ((zoom - ZOOM_AT_MIN_WIDTH) / (ZOOM_AT_MAX_WIDTH - ZOOM_AT_MIN_WIDTH)).toFloat()
            ROUTE_MIN_DP + (ROUTE_MAX_DP - ROUTE_MIN_DP) * t
        }
    }

    /** Committed block width; always wider than the route at the same zoom. */
    fun blockedDp(zoom: Double): Float = routeDp(zoom) + BLOCKED_OVERHANG_DP

    /** Pending (uncommitted) mark width. */
    fun pendingDp(zoom: Double): Float = routeDp(zoom) + PENDING_OVERHANG_DP

    /**
     * Reported-but-usable road width.
     *
     * Wider than the route so it is not hidden by the line drawn over it, and narrower than a
     * pending or committed mark so the visual order matches the meaning: a road somebody reported
     * and the router merely made expensive must not shout as loudly as one that is being blocked.
     * The ordering `route < reported < pending < blocked` is asserted in the tests.
     */
    fun reportedDp(zoom: Double): Float = routeDp(zoom) + REPORTED_OVERHANG_DP
}
