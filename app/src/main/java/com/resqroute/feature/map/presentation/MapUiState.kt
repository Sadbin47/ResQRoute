package com.resqroute.feature.map.presentation

import com.resqroute.core.model.SegmentId
import com.resqroute.core.routing.RoutePath
import com.resqroute.domain.location.SnapConcern
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.HazardState
import com.resqroute.domain.map.DatasetState
import com.resqroute.domain.risk.RiskPenaltyPolicy

/**
 * The one route the screen may display.
 *
 * There is deliberately no second `currentRoute` field beside this: a separate
 * value would allow inconsistent combinations (for example `Ready(path)` plus an
 * older route variable).
 */
sealed interface RouteStatus {

    /** No valid routing input yet (no dataset, no hazard truth, or no destination). */
    data object Idle : RouteStatus

    /** A search is running for the newest input. */
    data object Calculating : RouteStatus

    /** The newest, still-valid route. */
    /**
     * Routes were computed and passed the publication guard.
     *
     * Carries the whole decision — one option when both policies agreed, two when they differ —
     * because the pair is published atomically and must not be recombined from separate state.
     */
    data class Ready(val options: RouteOptions) : RouteStatus

    /** The destination is not reachable without crossing a blocked segment. */
    data object NoRoute : RouteStatus

    /** Routing could not run. Never used for cancellation. */
    data class Failed(val message: String) : RouteStatus
}

/**
 * A mark the user submitted that the repository has not yet confirmed.
 *
 * `Saving` is shown as a pending (dashed) mark so it is never confused with a
 * committed block; a failure moves to `reportError` and the pending mark
 * disappears, so a failed write can never look committed.
 */
sealed interface PendingReport {

    data class Saving(val segmentId: SegmentId, val reason: HazardReason) : PendingReport

    data class Failed(val segmentId: SegmentId, val reason: HazardReason, val message: String) : PendingReport
}

/**
 * One coherent screen state (blueprint section 5).
 *
 * Every field is derived from the dataset repository, the hazard repository or
 * local selection; nothing here duplicates route geometry, and the committed
 * blocked set is read from [hazardState] rather than copied into a parallel
 * field.
 */
/**
 * Why "report where I am" could not offer a road.
 *
 * A **typed** reason rather than a sentence: the view model has no context and does no wording, so
 * the failure paths cannot smuggle prose into the state, and each fallback message has one home in
 * `strings.xml` where the wording gate can see it.
 */
enum class LocationRefusal {
    /** Location is not granted. The manual long-press path is unaffected. */
    PERMISSION_DENIED,

    /** Granted, but the device has no fix yet. */
    NO_FIX,

    /** The provider itself failed. */
    PROVIDER_FAILED,

    /** A fix, but no road within range: not something the app may guess at. */
    NO_ROAD_NEARBY,
}

/**
 * A road the app believes the person is standing on, with the facts behind that belief (XPH-90).
 *
 * It is a **suggestion awaiting confirmation**, never a selection: nothing is highlighted and nothing
 * is written until the person accepts it. [concerns] empty is the only case that may be described
 * plainly; otherwise the doubt is carried through to the wording.
 */
data class LocationSuggestion(
    val segmentId: SegmentId,
    val distanceMeters: Double,
    val accuracyMeters: Double?,
    val concerns: Set<SnapConcern>,
    /** Other roads within the ambiguity margin, when the match was ambiguous. */
    val alternatives: List<SegmentId>,
)

data class MapUiState(
    val datasetState: DatasetState,
    val hazardState: HazardState,
    val routeStatus: RouteStatus,
    val startShelterIndex: Int?,
    val destinationShelterIndex: Int?,
    val highlightedSegment: SegmentId?,
    val ambiguousCandidates: List<SegmentId>,
    val pendingReport: PendingReport?,
    val reportError: String?,
    val notice: String?,
    /**
     * The route currently drawn on the map, if any.
     *
     * This stays the **distance-preferred** route, which is the route this app drew before risk
     * pricing existed. Which of [RouteOptions] to draw, and how to label it, are presentation rules
     * owned by XPH-77; the view model's job is to publish both options and their facts, which it
     * does in [routeStatus]. Changing what is drawn here would decide XPH-77's question by default.
     *
     * This is deliberately separate from [routeStatus]: a route whose input has
     * been superseded (a new block, a new destination, a new dataset) is still
     * drawn — grey and dashed — but [displayedRouteUsable] is false, so an
     * out-of-date route is never presented as the valid answer. Only a search
     * that passed the publication guard sets a usable route.
     */
    val displayedRoute: RoutePath? = null,
    val displayedRouteUsable: Boolean = false,
    /**
     * A location-assisted candidate awaiting the person's confirmation, or null.
     *
     * Present only between "report where I am" and the person's answer; it never coexists with a
     * [highlightedSegment], because confirming one is what creates the other.
     */
    val locationSuggestion: LocationSuggestion? = null,
    /** Set when "report where I am" could not offer a road; cleared when it can, or when dismissed. */
    val locationRefusal: LocationRefusal? = null,
) {
    /** Committed blocked segments, or empty while hazard truth is not ready. */
    val committedBlockedSegments: Set<SegmentId>
        get() = (hazardState as? HazardState.Ready)?.snapshot?.blockedSegments ?: emptySet()

    /**
     * Roads somebody reported that are **not** excluded but **do** carry a finite risk (XPH-92).
     *
     * This is the set the map draws in amber, and it is defined by the same rule the router pays:
     * traversable, and `RiskPenaltyPolicy.riskFactor > 0`. Defining it any other way would let the
     * map and the route disagreement disagree about which roads matter — the map would show a road
     * the router did not price, or hide one it did.
     *
     * Two consequences worth stating, because they are deliberate rather than accidental:
     *
     *  * a road reported "no water seen, passable" is **not** in this set. It is information with no
     *    risk, and painting it would imply a hazard that nobody observed;
     *  * an excluded road is **not** in this set either, because it is drawn as a block instead. An
     *    unreadable row therefore never appears here: it excludes by failing closed.
     */
    val pricedSegments: Set<SegmentId>
        get() = (hazardState as? HazardState.Ready)
            ?.observationStates
            ?.asSequence()
            ?.filter { (_, state) -> !state.excludesTraversal && RiskPenaltyPolicy.riskFactor(state) > 0.0 }
            ?.map { it.key }
            ?.toSet()
            ?: emptySet()

    /** True once routing is allowed to run: dataset and hazard truth both ready. */
    val routingReady: Boolean
        get() = datasetState is DatasetState.Ready && hazardState is HazardState.Ready

    companion object {
        val Initial = MapUiState(
            datasetState = DatasetState.NotLoaded,
            hazardState = HazardState.Restoring,
            routeStatus = RouteStatus.Idle,
            startShelterIndex = null,
            destinationShelterIndex = null,
            highlightedSegment = null,
            ambiguousCandidates = emptyList(),
            pendingReport = null,
            reportError = null,
            notice = null,
        )
    }
}
