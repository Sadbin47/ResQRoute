package com.resqroute.core.graph

import com.resqroute.core.model.DatasetIdentity
import com.resqroute.core.model.EdgeId
import com.resqroute.core.model.GeoCoordinate
import com.resqroute.core.model.NodeId
import com.resqroute.core.model.SegmentId
import java.nio.charset.StandardCharsets
import java.util.Arrays

/**
 * Primitive-array implementation of [RoutingGraph] over the bundled dataset.
 *
 * All arrays are privately owned and never mutated or exposed after
 * construction. The class is safe to share across threads for reads.
 *
 * The loader (`data/dataset/BundledDatasetReader`) performs full format and
 * topology validation and also checks the cheap structural invariants declared
 * here in the `init` block, so any instance that exists is internally coherent.
 */
class PackedRoutingGraph internal constructor(
    override val identity: DatasetIdentity,
    override val topologyVersion: String,
    private val nodeLatE7: IntArray,
    private val nodeLonE7: IntArray,
    private val nodeOsmId: LongArray,
    private val edgeOffset: IntArray,
    private val edgeTarget: IntArray,
    private val edgeSegment: IntArray,
    private val edgeCostMeters: FloatArray,
    private val segGeoOffset: IntArray,
    private val pointLatE7: IntArray,
    private val pointLonE7: IntArray,
    private val segLengthMeters: FloatArray,
    private val segFlags: ByteArray,
    private val segOsmWayId: LongArray,
    private val segNameOffset: IntArray,
    private val segNamePool: ByteArray,
    private val shelterLatE7: IntArray,
    private val shelterLonE7: IntArray,
    private val shelterAccessNode: IntArray,
    private val shelterFlags: ByteArray,
    private val shelterNameOffset: IntArray,
    private val shelterNamePool: ByteArray,
    override val southLatitude: Double,
    override val westLongitude: Double,
    override val northLatitude: Double,
    override val eastLongitude: Double,
) : RoutingGraph {

    override val nodeCount: Int get() = nodeOsmId.size
    override val edgeCount: Int get() = edgeTarget.size
    override val segmentCount: Int get() = segFlags.size
    override val shelterCount: Int get() = shelterAccessNode.size

    override val hasElevation: Boolean = false
    override val elevationSource: String? = null

    /** Directed edge index -> source node index; derived from the CSR offsets. */
    private val edgeSource: IntArray = IntArray(edgeTarget.size)

    /** Shelters are resolved once at load (91 rows in the current dataset). */
    private val shelters: List<Shelter>

    init {
        require(nodeLatE7.size == nodeOsmId.size) { "node latitude/id count mismatch" }
        require(nodeLonE7.size == nodeOsmId.size) { "node longitude/id count mismatch" }
        require(edgeOffset.size == nodeCount + 1) { "edge_offset must hold nodeCount + 1 values" }
        require(edgeSegment.size == edgeTarget.size) { "edge_target/edge_segment count mismatch" }
        require(edgeCostMeters.size == edgeTarget.size) { "edge_cost count mismatch" }
        require(edgeOffset[0] == 0) { "edge_offset must start at 0" }
        require(edgeOffset[nodeCount] == edgeCount) {
            "edge_offset must end at edge_count (${edgeCount}) but ends at ${edgeOffset[nodeCount]}"
        }
        for (node in 0 until nodeCount) {
            require(edgeOffset[node] <= edgeOffset[node + 1]) {
                "edge_offset must be non-decreasing at node $node"
            }
        }
        require(segGeoOffset.size == segmentCount + 1) { "seg_geo_offset must hold segmentCount + 1 values" }
        require(segGeoOffset[0] == 0) { "seg_geo_offset must start at 0" }
        require(segGeoOffset[segmentCount] == pointLatE7.size) {
            "seg_geo_offset must end at the point count"
        }
        require(pointLonE7.size == pointLatE7.size) { "point latitude/longitude count mismatch" }
        require(segLengthMeters.size == segmentCount) { "segment length count mismatch" }
        require(segOsmWayId.size == segmentCount) { "segment way id count mismatch" }
        require(segNameOffset.size == segmentCount + 1) { "segment name offset count mismatch" }
        require(shelterLatE7.size == shelterCount) { "shelter latitude count mismatch" }
        require(shelterLonE7.size == shelterCount) { "shelter longitude count mismatch" }
        require(shelterFlags.size == shelterCount) { "shelter flags count mismatch" }
        require(shelterNameOffset.size == shelterCount + 1) { "shelter name offset count mismatch" }

        // Materialize the edge -> source mapping once (derived, not stored in the artifact).
        var cursor = 0
        for (node in 0 until nodeCount) {
            val begin = edgeOffset[node]
            val end = edgeOffset[node + 1]
            var slot = begin
            while (slot < end) {
                edgeSource[slot] = node
                slot++
            }
            cursor = end
        }
        check(cursor == edgeCount) { "CSR traversal did not consume every edge" }

        shelters = buildShelters()
    }

    private fun buildShelters(): List<Shelter> = List(shelterCount) { i ->
        Shelter(
            index = i,
            id = String.format(java.util.Locale.US, "shelter-%03d", i + 1),
            name = decode(shelterNamePool, shelterNameOffset, i) ?: "Shelter ${i + 1}",
            coordinate = GeoCoordinate(shelterLatE7[i] / E7, shelterLonE7[i] / E7),
            accessNodeId = NodeId(nodeOsmId[shelterAccessNode[i]]),
            accessNodeIndex = shelterAccessNode[i],
            kind = ShelterKind.of(shelterFlags[i].toInt() and 0xFF),
        )
    }

    // --------------------------------------------------------------- nodes

    override fun nodeId(nodeIndex: Int): NodeId = NodeId(nodeOsmId[nodeIndex])

    override fun nodeIndex(id: NodeId): Int? {
        val found = Arrays.binarySearch(nodeOsmId, id.value)
        return if (found >= 0) found else null
    }

    override fun latitude(nodeIndex: Int): Double = nodeLatE7[nodeIndex] / E7

    override fun longitude(nodeIndex: Int): Double = nodeLonE7[nodeIndex] / E7

    override fun coordinate(nodeIndex: Int): GeoCoordinate =
        GeoCoordinate(latitude(nodeIndex), longitude(nodeIndex))

    override fun elevationMeters(nodeIndex: Int): Float? {
        checkIndex(nodeIndex, nodeCount, "node")
        return null // dataset v1 carries no elevation; unknown is never zero/safe
    }

    // --------------------------------------------------------------- edges

    override fun edgeId(edgeIndex: Int): EdgeId = EdgeId(edgeIndex)

    override fun edgeFrom(edgeIndex: Int): Int = edgeSource[edgeIndex]

    override fun edgeTo(edgeIndex: Int): Int = edgeTarget[edgeIndex]

    override fun edgeSegmentIndex(edgeIndex: Int): Int = edgeSegment[edgeIndex]

    override fun edgeSegmentId(edgeIndex: Int): SegmentId = SegmentId(edgeSegment[edgeIndex])

    override fun edgeCostMeters(edgeIndex: Int): Double = edgeCostMeters[edgeIndex].toDouble()

    // ------------------------------------------------- compressed adjacency

    override fun outgoingBegin(nodeIndex: Int): Int = edgeOffset[nodeIndex]

    override fun outgoingEnd(nodeIndex: Int): Int = edgeOffset[nodeIndex + 1]

    /**
     * Adjacency slot -> directed edge index.
     *
     * Directed edges are stored in CSR order, so the slot is the edge index.
     * The mapping is kept explicit so a future layout change stays a local edit.
     */
    override fun outgoingEdgeIndex(adjacencyIndex: Int): Int = adjacencyIndex

    // ------------------------------------------------------------ segments

    override fun segmentFlags(segmentIndex: Int): Int = segFlags[segmentIndex].toInt() and 0xFF

    override fun segmentLengthMeters(segmentIndex: Int): Double = segLengthMeters[segmentIndex].toDouble()

    override fun segmentOsmWayId(segmentIndex: Int): Long = segOsmWayId[segmentIndex]

    override fun segmentName(segmentIndex: Int): String? = decode(segNamePool, segNameOffset, segmentIndex)

    override fun isSegmentEligible(segmentIndex: Int): Boolean =
        segmentFlags(segmentIndex) and SegmentFlags.ELIGIBLE != 0

    override fun segmentStableId(segmentIndex: Int): String = SegmentId(segmentIndex).stableId

    // ----------------------------------------------------------- geometry

    override fun geometryPointCount(segmentIndex: Int): Int =
        segGeoOffset[segmentIndex + 1] - segGeoOffset[segmentIndex]

    override fun geometryLatitude(segmentIndex: Int, pointIndex: Int): Double =
        pointLatE7[segGeoOffset[segmentIndex] + pointIndex] / E7

    override fun geometryLongitude(segmentIndex: Int, pointIndex: Int): Double =
        pointLonE7[segGeoOffset[segmentIndex] + pointIndex] / E7

    override fun copyGeometry(segmentIndex: Int, out: MutableList<GeoCoordinate>) {
        out.clear()
        val begin = segGeoOffset[segmentIndex]
        val end = segGeoOffset[segmentIndex + 1]
        var slot = begin
        while (slot < end) {
            out.add(GeoCoordinate(pointLatE7[slot] / E7, pointLonE7[slot] / E7))
            slot++
        }
    }

    // ----------------------------------------------------------- shelters

    override fun shelter(shelterIndex: Int): Shelter = shelters[shelterIndex]

    // ------------------------------------------------------------- helpers

    private fun decode(pool: ByteArray, offset: IntArray, index: Int): String? {
        val begin = offset[index]
        val end = offset[index + 1]
        if (end <= begin) return null
        return String(pool, begin, end - begin, StandardCharsets.UTF_8)
    }

    private fun checkIndex(index: Int, size: Int, kind: String) {
        if (index < 0 || index >= size) {
            throw IndexOutOfBoundsException("$kind index $index out of bounds (size $size)")
        }
    }

    private companion object {
        const val E7 = 1e7
    }
}
