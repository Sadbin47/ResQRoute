package com.resqroute.dataset

import com.resqroute.core.graph.GraphBlocker
import com.resqroute.core.graph.PackedRoutingGraph
import com.resqroute.core.graph.RoutingGraph
import com.resqroute.core.graph.SegmentFlags
import com.resqroute.core.model.DatasetIdentity
import com.resqroute.core.model.NodeId
import com.resqroute.core.routing.RoutingPolicy
import java.nio.charset.StandardCharsets
import java.util.PriorityQueue

/**
 * Deterministic synthetic graphs for routing tests.
 *
 * Uses the real [PackedRoutingGraph] so the engine is exercised against the
 * same CSR layout as the bundled dataset, while the topology stays small and
 * hand-computable.
 */
internal data class NodeSpec(
    val osmId: Long,
    val latitude: Double,
    val longitude: Double,
)

internal data class SegmentSpec(
    val from: Int,
    val to: Int,
    val costMeters: Double,
    val coordinates: List<Pair<Double, Double>>,
    val forward: Boolean = true,
    val backward: Boolean = true,
    val eligible: Boolean = true,
    val name: String? = null,
    val wayId: Long = 0L,
)

/** One shelter whose access node is a graph node index. */
internal data class ShelterSpec(
    val accessNodeIndex: Int,
    val name: String,
    val kindFlag: Int = 0,
)

internal fun buildTestGraph(
    nodes: List<NodeSpec>,
    segments: List<SegmentSpec>,
    identity: DatasetIdentity = DatasetIdentity("test-graph", 1),
    shelters: List<ShelterSpec> = emptyList(),
): PackedRoutingGraph {
    val nodeLat = IntArray(nodes.size) { e7(nodes[it].latitude) }
    val nodeLon = IntArray(nodes.size) { e7(nodes[it].longitude) }
    val nodeOsm = LongArray(nodes.size) { nodes[it].osmId }

    data class Edge(val from: Int, val to: Int, val segment: Int, val cost: Float)

    val edges = ArrayList<Edge>()
    segments.forEachIndexed { index, segment ->
        if (segment.forward) edges.add(Edge(segment.from, segment.to, index, segment.costMeters.toFloat()))
        if (segment.backward) edges.add(Edge(segment.to, segment.from, index, segment.costMeters.toFloat()))
    }
    edges.sortWith(compareBy({ it.from }, { it.to }, { it.segment }))

    val edgeOffset = IntArray(nodes.size + 1)
    for (edge in edges) edgeOffset[edge.from + 1]++
    for (node in 1..nodes.size) edgeOffset[node] += edgeOffset[node - 1]
    val edgeTarget = IntArray(edges.size) { edges[it].to }
    val edgeSegment = IntArray(edges.size) { edges[it].segment }
    val edgeCost = FloatArray(edges.size) { edges[it].cost }

    val segGeoOffset = IntArray(segments.size + 1)
    segments.forEachIndexed { index, segment ->
        require(segment.coordinates.size >= 2) { "segment $index needs at least two points" }
        segGeoOffset[index + 1] = segGeoOffset[index] + segment.coordinates.size
    }
    val allPoints = segments.flatMap { it.coordinates }
    val pointLat = IntArray(allPoints.size) { e7(allPoints[it].first) }
    val pointLon = IntArray(allPoints.size) { e7(allPoints[it].second) }
    val segLength = FloatArray(segments.size) { segments[it].costMeters.toFloat() }
    val segFlags = ByteArray(segments.size) { index ->
        val segment = segments[index]
        var value = 0
        if (segment.forward) value = value or SegmentFlags.FORWARD
        if (segment.backward) value = value or SegmentFlags.BACKWARD
        if (segment.eligible) value = value or SegmentFlags.ELIGIBLE
        (value and 0xFF).toByte()
    }
    val segWay = LongArray(segments.size) { segments[it].wayId }

    val namePool = java.io.ByteArrayOutputStream()
    val nameOffset = IntArray(segments.size + 1)
    segments.forEachIndexed { index, segment ->
        nameOffset[index] = namePool.size()
        segment.name?.let { namePool.write(it.toByteArray(StandardCharsets.UTF_8)) }
    }
    nameOffset[segments.size] = namePool.size()

    val shelterNamePool = java.io.ByteArrayOutputStream()
    val shelterNameOffset = IntArray(shelters.size + 1)
    shelters.forEachIndexed { index, shelter ->
        shelterNameOffset[index] = shelterNamePool.size()
        shelterNamePool.write(shelter.name.toByteArray(StandardCharsets.UTF_8))
    }
    shelterNameOffset[shelters.size] = shelterNamePool.size()

    return PackedRoutingGraph(
        identity = identity,
        topologyVersion = "test",
        nodeLatE7 = nodeLat,
        nodeLonE7 = nodeLon,
        nodeOsmId = nodeOsm,
        edgeOffset = edgeOffset,
        edgeTarget = edgeTarget,
        edgeSegment = edgeSegment,
        edgeCostMeters = edgeCost,
        segGeoOffset = segGeoOffset,
        pointLatE7 = pointLat,
        pointLonE7 = pointLon,
        segLengthMeters = segLength,
        segFlags = segFlags,
        segOsmWayId = segWay,
        segNameOffset = nameOffset,
        segNamePool = namePool.toByteArray(),
        shelterLatE7 = IntArray(shelters.size) { e7(nodes[shelters[it].accessNodeIndex].latitude) },
        shelterLonE7 = IntArray(shelters.size) { e7(nodes[shelters[it].accessNodeIndex].longitude) },
        shelterAccessNode = IntArray(shelters.size) { shelters[it].accessNodeIndex },
        shelterFlags = ByteArray(shelters.size) { (shelters[it].kindFlag and 0xFF).toByte() },
        shelterNameOffset = shelterNameOffset,
        shelterNamePool = shelterNamePool.toByteArray(),
        southLatitude = nodes.minOf { it.latitude },
        westLongitude = nodes.minOf { it.longitude },
        northLatitude = nodes.maxOf { it.latitude },
        eastLongitude = nodes.maxOf { it.longitude },
    )
}

/** Chain graph: node i - node i+1, one forward segment each; forces long searches. */
internal fun buildChainGraph(nodeCount: Int): PackedRoutingGraph {
    val nodes = List(nodeCount) { NodeSpec(it.toLong(), 23.0 + it * 1e-5, 91.4) }
    val segments = List(nodeCount - 1) { index ->
        SegmentSpec(
            from = index,
            to = index + 1,
            costMeters = 1.0,
            coordinates = listOf(
                nodes[index].latitude to nodes[index].longitude,
                nodes[index + 1].latitude to nodes[index + 1].longitude,
            ),
            backward = false,
        )
    }
    return buildTestGraph(nodes, segments)
}

internal fun blockerFor(vararg segments: Int): GraphBlocker {
    val blocked = segments.toHashSet()
    return object : GraphBlocker {
        override fun isBlockedSegment(segmentIndex: Int): Boolean = segmentIndex in blocked
    }
}

/**
 * Reference Dijkstra used to prove A* optimality (acceptance criteria 1 and 2).
 * Returns the optimal cost, or null when the destination is unreachable.
 */
internal fun dijkstraCost(
    graph: RoutingGraph,
    start: NodeId,
    destination: NodeId,
    blocker: GraphBlocker,
    policy: RoutingPolicy,
): Double? {
    val startIndex = graph.nodeIndex(start) ?: return null
    val destinationIndex = graph.nodeIndex(destination) ?: return null
    if (startIndex == destinationIndex) return 0.0

    val distance = DoubleArray(graph.nodeCount) { Double.POSITIVE_INFINITY }
    distance[startIndex] = 0.0
    val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
    queue.add(0.0 to startIndex)

    while (queue.isNotEmpty()) {
        val head = queue.remove()
        val current = head.first
        val node = head.second
        if (current > distance[node] + 1e-12) continue
        if (node == destinationIndex) return current
        for (slot in graph.outgoingBegin(node) until graph.outgoingEnd(node)) {
            val edge = graph.outgoingEdgeIndex(slot)
            val segment = graph.edgeSegmentIndex(edge)
            if (blocker.isBlockedSegment(segment)) continue
            val neighbour = graph.edgeTo(edge)
            val candidate = current + graph.edgeCostMeters(edge) + policy.penaltyMeters(segment)
            if (candidate + 1e-12 < distance[neighbour]) {
                distance[neighbour] = candidate
                queue.add(candidate to neighbour)
            }
        }
    }
    return null
}

private fun e7(degrees: Double): Int = Math.round(degrees * 1e7).toInt()
