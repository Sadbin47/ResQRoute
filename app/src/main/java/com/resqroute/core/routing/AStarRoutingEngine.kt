package com.resqroute.core.routing

import com.resqroute.core.graph.RoutingGraph
import com.resqroute.core.model.DatasetIdentity
import com.resqroute.core.model.EdgeId
import com.resqroute.core.model.GeoMath
import com.resqroute.core.model.NodeId
import com.resqroute.core.model.SegmentId
import kotlin.math.min
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Recorded result of the admissibility check run before the first search on a
 * given graph (Phase 4 acceptance criterion 8).
 *
 * @param minCostToHaversineRatio the smallest `edge_cost_m / haversine(u,v)`
 *   over all directed edges with a non-zero great-circle span. A value below
 *   `1.0` means fixed-point rounding lets the raw Haversine distance exceed a
 *   stored edge cost for some edges.
 * @param appliedScale the factor actually multiplied into `h(n)`; always
 *   `<= min(1.0, minCostToHaversineRatio)`, which makes the heuristic a true
 *   lower bound on every remaining path.
 */
data class HeuristicMeasurement(
    val graphIdentity: DatasetIdentity,
    val minCostToHaversineRatio: Double,
    val appliedScale: Double,
    val edgeCount: Int,
)

/**
 * Cancellable, deterministic A* over the immutable packed graph.
 *
 * Cost (g) is the preprocessed physical distance of each traversed edge, plus
 * any non-negative policy penalty. A blocked segment is excluded from
 * relaxation entirely (equivalent to infinite cost); graph arrays are never
 * mutated.
 *
 * Heuristic (h) is the Haversine distance to the destination, scaled by an
 * admissible factor measured on the actual graph. The scale guarantees
 * `h(u) <= cost(u,v) + h(v)` for every edge despite the dataset's fixed-point
 * coordinate rounding, so A* is optimal with respect to the declared cost.
 *
 * Ties break by `(f, g, nodeIndex)`, so equal-cost routes are stable.
 */
class AStarRoutingEngine(
    private val safetyFactor: Double = DEFAULT_SAFETY_FACTOR,
) : RoutingEngine {

    init {
        require(safetyFactor > 0.0 && safetyFactor <= 1.0) {
            "safetyFactor must be in (0, 1] but was $safetyFactor"
        }
    }

    /** Set after the first search (or explicit [measureAdmissibility]). */
    @Volatile
    var lastHeuristicMeasurement: HeuristicMeasurement? = null
        private set

    override suspend fun findRoute(
        snapshot: RoutingSnapshot,
        request: RouteRequest,
    ): RouteResult {
        val graph = snapshot.graph

        val start = graph.nodeIndex(request.startNodeId)
            ?: return RouteResult.InvalidEndpoint(RouteEndpoint.START)
        val destination = graph.nodeIndex(request.destinationNodeId)
            ?: return RouteResult.InvalidEndpoint(RouteEndpoint.DESTINATION)

        if (start == destination) {
            return RouteResult.Found(zeroLengthPath(snapshot, request, graph, start))
        }

        val scale = admissibleScale(graph)
        val destinationLatitude = graph.latitude(destination)
        val destinationLongitude = graph.longitude(destination)

        val nodeCount = graph.nodeCount
        val gScore = DoubleArray(nodeCount) { Double.POSITIVE_INFINITY }
        val predecessorEdge = IntArray(nodeCount) { NO_EDGE }
        val closed = BooleanArray(nodeCount)
        val heap = NodeHeap(nodeCount)

        gScore[start] = 0.0
        heap.insertOrDecrease(
            nodeIndex = start,
            priority = scale * heuristic(graph, start, destinationLatitude, destinationLongitude),
            cost = 0.0,
        )

        var expansions = 0
        while (!heap.isEmpty) {
            val node = heap.popMin()
            if (closed[node]) continue
            closed[node] = true

            if (node == destination) {
                currentCoroutineContext().ensureActive()
                return RouteResult.Found(
                    reconstruct(snapshot, request, graph, start, destination, predecessorEdge, gScore),
                )
            }

            val begin = graph.outgoingBegin(node)
            val end = graph.outgoingEnd(node)
            var slot = begin
            while (slot < end) {
                val edge = graph.outgoingEdgeIndex(slot)
                val segment = graph.edgeSegmentIndex(edge)
                if (!snapshot.blocker.isBlockedSegment(segment)) {
                    val neighbour = graph.edgeTo(edge)
                    if (!closed[neighbour]) {
                        val step = graph.edgeCostMeters(edge) + request.policy.penaltyMeters(segment)
                        val candidate = gScore[node] + step
                        if (candidate + COST_EPSILON < gScore[neighbour]) {
                            gScore[neighbour] = candidate
                            predecessorEdge[neighbour] = edge
                            heap.insertOrDecrease(
                                nodeIndex = neighbour,
                                priority = candidate +
                                    scale * heuristic(graph, neighbour, destinationLatitude, destinationLongitude),
                                cost = candidate,
                            )
                        }
                    }
                }
                slot++
            }

            expansions++
            if (expansions and CANCEL_CHECK_MASK == 0) {
                currentCoroutineContext().ensureActive()
            }
        }

        return RouteResult.NoRoute
    }

    /**
     * Measures `min(edge_cost / haversine)` over the real graph and returns the
     * admissible heuristic scale. Cached per dataset identity.
     */
    fun measureAdmissibility(graph: RoutingGraph): HeuristicMeasurement {
        val cached = lastHeuristicMeasurement
        if (cached != null && cached.graphIdentity == graph.identity) return cached
        return synchronized(this) {
            val recheck = lastHeuristicMeasurement
            if (recheck != null && recheck.graphIdentity == graph.identity) {
                recheck
            } else {
                var minRatio = Double.POSITIVE_INFINITY
                for (edge in 0 until graph.edgeCount) {
                    val from = graph.edgeFrom(edge)
                    val to = graph.edgeTo(edge)
                    val straight = GeoMath.haversineMeters(
                        graph.latitude(from),
                        graph.longitude(from),
                        graph.latitude(to),
                        graph.longitude(to),
                    )
                    if (straight > 0.0) {
                        val ratio = graph.edgeCostMeters(edge) / straight
                        if (ratio < minRatio) minRatio = ratio
                    }
                }
                if (!minRatio.isFinite()) minRatio = 1.0
                val measurement = HeuristicMeasurement(
                    graphIdentity = graph.identity,
                    minCostToHaversineRatio = minRatio,
                    appliedScale = min(1.0, minRatio) * safetyFactor,
                    edgeCount = graph.edgeCount,
                )
                lastHeuristicMeasurement = measurement
                measurement
            }
        }
    }

    private fun admissibleScale(graph: RoutingGraph): Double =
        measureAdmissibility(graph).appliedScale

    private fun heuristic(
        graph: RoutingGraph,
        nodeIndex: Int,
        destinationLatitude: Double,
        destinationLongitude: Double,
    ): Double = GeoMath.haversineMeters(
        graph.latitude(nodeIndex),
        graph.longitude(nodeIndex),
        destinationLatitude,
        destinationLongitude,
    )

    private fun zeroLengthPath(
        snapshot: RoutingSnapshot,
        request: RouteRequest,
        graph: RoutingGraph,
        nodeIndex: Int,
    ): RoutePath = RoutePath(
        dataset = snapshot.dataset,
        incidentId = snapshot.incidentId,
        requestGeneration = request.generation,
        hazardRevision = snapshot.hazardRevision,
        nodeIds = listOf(graph.nodeId(nodeIndex)),
        edgeIds = emptyList(),
        segmentIds = emptyList(),
        distanceMeters = 0.0,
        totalCost = 0.0,
    )

    private fun reconstruct(
        snapshot: RoutingSnapshot,
        request: RouteRequest,
        graph: RoutingGraph,
        start: Int,
        destination: Int,
        predecessorEdge: IntArray,
        gScore: DoubleArray,
    ): RoutePath {
        val nodeIndexes = ArrayList<Int>()
        val edgeIndexes = ArrayList<Int>()
        var current = destination
        var guard = 0
        while (current != start) {
            val edge = predecessorEdge[current]
            check(edge != NO_EDGE) { "predecessor chain broke at node $current" }
            nodeIndexes.add(current)
            edgeIndexes.add(edge)
            current = graph.edgeFrom(edge)
            guard++
            check(guard <= graph.nodeCount) { "predecessor chain did not reach the start node" }
        }
        nodeIndexes.add(start)
        nodeIndexes.reverse()
        edgeIndexes.reverse()

        var distance = 0.0
        val nodeIds = ArrayList<NodeId>(nodeIndexes.size)
        val edgeIds = ArrayList<EdgeId>(edgeIndexes.size)
        val segmentIds = ArrayList<SegmentId>(edgeIndexes.size)
        for (index in nodeIndexes.indices) {
            nodeIds.add(graph.nodeId(nodeIndexes[index]))
        }
        for (edge in edgeIndexes) {
            distance += graph.edgeCostMeters(edge)
            edgeIds.add(graph.edgeId(edge))
            segmentIds.add(graph.edgeSegmentId(edge))
        }

        return RoutePath(
            dataset = snapshot.dataset,
            incidentId = snapshot.incidentId,
            requestGeneration = request.generation,
            hazardRevision = snapshot.hazardRevision,
            nodeIds = nodeIds,
            edgeIds = edgeIds,
            segmentIds = segmentIds,
            distanceMeters = distance,
            totalCost = gScore[destination],
        )
    }

    /**
     * Primitive indexed binary heap with decrease-key.
     *
     * Ordered by `(f, g, nodeIndex)` for deterministic ties. One entry per
     * node, so no stale duplicates accumulate.
     */
    private class NodeHeap(capacity: Int) {

        private val node = IntArray(capacity)
        private val priority = DoubleArray(capacity)
        private val cost = DoubleArray(capacity)
        private val position = IntArray(capacity) { ABSENT }
        private var size = 0

        val isEmpty: Boolean get() = size == 0

        fun insertOrDecrease(nodeIndex: Int, priority: Double, cost: Double) {
            val existing = position[nodeIndex]
            if (existing >= 0) {
                this.priority[existing] = priority
                this.cost[existing] = cost
                siftUp(existing)
            } else {
                val index = size
                size++
                node[index] = nodeIndex
                this.priority[index] = priority
                this.cost[index] = cost
                position[nodeIndex] = index
                siftUp(index)
            }
        }

        fun popMin(): Int {
            val top = node[0]
            position[top] = ABSENT
            size--
            if (size > 0) {
                node[0] = node[size]
                priority[0] = priority[size]
                cost[0] = cost[size]
                position[node[0]] = 0
                siftDown(0)
            }
            return top
        }

        private fun less(left: Int, right: Int): Boolean {
            if (priority[left] != priority[right]) return priority[left] < priority[right]
            if (cost[left] != cost[right]) return cost[left] < cost[right]
            return node[left] < node[right]
        }

        private fun swap(left: Int, right: Int) {
            val nodeLeft = node[left]
            node[left] = node[right]
            node[right] = nodeLeft
            val priorityLeft = priority[left]
            priority[left] = priority[right]
            priority[right] = priorityLeft
            val costLeft = cost[left]
            cost[left] = cost[right]
            cost[right] = costLeft
            position[node[left]] = left
            position[node[right]] = right
        }

        private fun siftUp(from: Int) {
            var index = from
            while (index > 0) {
                val parent = (index - 1) ushr 1
                if (!less(index, parent)) break
                swap(index, parent)
                index = parent
            }
        }

        private fun siftDown(from: Int) {
            var index = from
            while (true) {
                val left = 2 * index + 1
                if (left >= size) break
                val right = left + 1
                var smallest = left
                if (right < size && less(right, left)) smallest = right
                if (!less(smallest, index)) break
                swap(index, smallest)
                index = smallest
            }
        }

        private companion object {
            const val ABSENT = -1
        }
    }

    private companion object {
        const val NO_EDGE = -1
        const val COST_EPSILON = 1e-9

        /**
         * Safety margin below the measured admissible ratio. The ratio itself
         * already guarantees the bound; the factor absorbs future rounding.
         */
        const val DEFAULT_SAFETY_FACTOR = 1.0 - 1e-6

        /** Check cancellation every 1024 node expansions. */
        const val CANCEL_CHECK_MASK = 0x3FF
    }
}
