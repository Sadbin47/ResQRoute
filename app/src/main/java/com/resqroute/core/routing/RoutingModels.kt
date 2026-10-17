package com.resqroute.core.routing

import com.resqroute.core.graph.GraphBlocker
import com.resqroute.core.graph.RoutingGraph
import com.resqroute.core.model.DatasetIdentity
import com.resqroute.core.model.EdgeId
import com.resqroute.core.model.NodeId
import com.resqroute.core.model.SegmentId

/**
 * Travel policy for the MVP.
 *
 * The dataset carries no elevation and no road-type attributes, so the only
 * policy the engine applies by default is pure physical distance. The optional
 * [segmentPenaltyMeters] hook exists so a future policy can add a non-negative
 * per-segment cost; it is empty by default and penalties are never placed in
 * the heuristic.
 */
data class RoutingPolicy(
    val segmentPenaltyMeters: Map<SegmentId, Double> = emptyMap(),
) {
    init {
        require(segmentPenaltyMeters.values.all { it.isFinite() && it >= 0.0 }) {
            "segment penalties must be finite and non-negative"
        }
    }

    fun penaltyMeters(segmentIndex: Int): Double =
        if (segmentPenaltyMeters.isEmpty()) {
            0.0
        } else {
            segmentPenaltyMeters[SegmentId(segmentIndex)] ?: 0.0
        }

    companion object {
        /** MVP policy: cost equals distance; blocked segments are excluded. */
        val DISTANCE_ONLY = RoutingPolicy()
    }
}

/**
 * Immutable inputs for one routing decision.
 *
 * The graph and the blocked-segment view never change while a search runs, so
 * the result is a pure function of this snapshot plus the [RouteRequest].
 */
data class RoutingSnapshot(
    val dataset: DatasetIdentity,
    val incidentId: String,
    /** Publication guard input: revision of the effective blocked set. */
    val hazardRevision: Long,
    val graph: RoutingGraph,
    val blocker: GraphBlocker,
)

/** One routing request. [generation] is the caller's latest-wins guard. */
data class RouteRequest(
    val generation: Long,
    val startNodeId: NodeId,
    val destinationNodeId: NodeId,
    val policy: RoutingPolicy = RoutingPolicy.DISTANCE_ONLY,
)

/** Which endpoint could not be resolved. */
enum class RouteEndpoint { START, DESTINATION }

/** A complete, reconstructable route over one snapshot. */
data class RoutePath(
    val dataset: DatasetIdentity,
    val incidentId: String,
    val requestGeneration: Long,
    val hazardRevision: Long,
    val nodeIds: List<NodeId>,
    val edgeIds: List<EdgeId>,
    val segmentIds: List<SegmentId>,
    /** Sum of the preprocessed edge costs in meters (geometric length). */
    val distanceMeters: Double,
    /** Accumulated cost under the request policy (equals distance when distance-only). */
    val totalCost: Double,
)

/** Outcome of one search. Cancellation is never reported as a result. */
sealed interface RouteResult {

    data class Found(val path: RoutePath) : RouteResult

    data object NoRoute : RouteResult

    data class InvalidEndpoint(val endpoint: RouteEndpoint) : RouteResult

    data class Failure(val message: String) : RouteResult
}
