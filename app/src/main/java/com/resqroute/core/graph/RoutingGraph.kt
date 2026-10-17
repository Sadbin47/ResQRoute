package com.resqroute.core.graph

import com.resqroute.core.model.DatasetIdentity
import com.resqroute.core.model.EdgeId
import com.resqroute.core.model.GeoCoordinate
import com.resqroute.core.model.NodeId
import com.resqroute.core.model.SegmentId

/**
 * Immutable, packed routing graph over one bundled dataset.
 *
 * Contract: contents never change after publication, and no mutable buffer is
 * ever handed out. The hot path (Phase 4 A*) reads through dense indices and
 * primitive accessors, so no object is created per node or per neighbor.
 *
 * Ownership: [DatasetIdentity] identifies the exact bytes this graph was built
 * from. Route results must carry the same identity or they are stale.
 */
interface RoutingGraph {

    val identity: DatasetIdentity

    /** Topology version string from the manifest (traceability only). */
    val topologyVersion: String

    val nodeCount: Int
    val edgeCount: Int
    val segmentCount: Int
    val shelterCount: Int

    // --------------------------------------------------------------- nodes

    fun nodeId(nodeIndex: Int): NodeId

    /** Dense index for a stable node id, or null when the node is absent. */
    fun nodeIndex(id: NodeId): Int?

    fun latitude(nodeIndex: Int): Double

    fun longitude(nodeIndex: Int): Double

    fun coordinate(nodeIndex: Int): GeoCoordinate

    /**
     * Elevation in meters, or null when the dataset carries no elevation.
     *
     * Dataset v1 has no elevation sections, so this always returns null.
     * Callers MUST treat null as "unknown": never as zero and never as safe.
     */
    fun elevationMeters(nodeIndex: Int): Float?

    /** Whether this dataset carries any elevation coverage at all. */
    val hasElevation: Boolean

    /** Human-readable provenance of elevation data, or null when absent. */
    val elevationSource: String?

    // --------------------------------------------------------------- edges

    fun edgeId(edgeIndex: Int): EdgeId

    fun edgeFrom(edgeIndex: Int): Int

    fun edgeTo(edgeIndex: Int): Int

    /** Physical segment this directed edge belongs to. */
    fun edgeSegmentIndex(edgeIndex: Int): Int

    fun edgeSegmentId(edgeIndex: Int): SegmentId

    /** Traversal cost in meters (preprocessed segment length). */
    fun edgeCostMeters(edgeIndex: Int): Double

    // ------------------------------------------- compressed sparse adjacency

    fun outgoingBegin(nodeIndex: Int): Int

    fun outgoingEnd(nodeIndex: Int): Int

    /** Directed edge index stored at an adjacency slot. */
    fun outgoingEdgeIndex(adjacencyIndex: Int): Int

    // ------------------------------------------------------------ segments

    /** Bit flags: see [SegmentFlags]. */
    fun segmentFlags(segmentIndex: Int): Int

    fun segmentLengthMeters(segmentIndex: Int): Double

    fun segmentOsmWayId(segmentIndex: Int): Long

    fun segmentName(segmentIndex: Int): String?

    /** A physical segment is routable only when it produced directed edges. */
    fun isSegmentEligible(segmentIndex: Int): Boolean

    /** Stable Phase 0 id, e.g. `S000001`. */
    fun segmentStableId(segmentIndex: Int): String

    // ---------------------------------------------------------- geometry

    /** Number of polyline points stored for a segment (rendering geometry). */
    fun geometryPointCount(segmentIndex: Int): Int

    fun geometryLatitude(segmentIndex: Int, pointIndex: Int): Double

    fun geometryLongitude(segmentIndex: Int, pointIndex: Int): Double

    /** Appends a segment polyline to [out] (clearing it first) without reallocation. */
    fun copyGeometry(segmentIndex: Int, out: MutableList<GeoCoordinate>)

    // ---------------------------------------------------------- shelters

    fun shelter(shelterIndex: Int): Shelter

    // -------------------------------------------------- spatial index access

    val southLatitude: Double
    val westLongitude: Double
    val northLatitude: Double
    val eastLongitude: Double
}

/** Bit meanings of `segmentFlags` (see `tools/dataset/DATASET_FORMAT.md`). */
object SegmentFlags {
    const val FORWARD = 1
    const val BACKWARD = 2
    const val BRIDGE = 4
    const val TUNNEL = 8
    const val ELIGIBLE = 16
}

/** Directional traversability derived from [SegmentFlags]. */
enum class SegmentTraversal {
    FORWARD,
    BACKWARD,
    BOTH,
    NONE;

    companion object {
        fun of(flags: Int): SegmentTraversal {
            val forward = flags and SegmentFlags.FORWARD != 0
            val backward = flags and SegmentFlags.BACKWARD != 0
            return when {
                forward && backward -> BOTH
                forward -> FORWARD
                backward -> BACKWARD
                else -> NONE
            }
        }
    }
}

/**
 * Read-only view of the currently blocked physical segments.
 *
 * Phase 3 introduces the boundary so A* never depends on Room. Phase 4 uses
 * `isBlockedSegment` during edge relaxation; Phase 6 provides a snapshot-backed
 * implementation from the hazard repository. Blocking by physical segment is
 * what lets one exclusion remove both directed edges.
 */
interface GraphBlocker {

    /** True when the physical segment is blocked and must never be traversed. */
    fun isBlockedSegment(segmentIndex: Int): Boolean

    companion object {
        /** Nothing blocked. Safe default before hazard state is ready. */
        val NONE: GraphBlocker = object : GraphBlocker {
            override fun isBlockedSegment(segmentIndex: Int): Boolean = false
        }
    }
}

/** Shelter candidate with a build-verified routing access node. */
data class Shelter(
    val index: Int,
    val id: String,
    val name: String,
    val coordinate: GeoCoordinate,
    val accessNodeId: NodeId,
    val accessNodeIndex: Int,
    val kind: ShelterKind,
) {
    val stableId: String get() = id
}

/** Shelter provenance flag (`shelter_flags` in the dataset). */
enum class ShelterKind {
    SHELTER_TAGGED,
    COMMUNITY_CENTRE,
    SCHOOL,
    UNKNOWN;

    companion object {
        fun of(flag: Int): ShelterKind = when (flag) {
            0 -> SHELTER_TAGGED
            1 -> COMMUNITY_CENTRE
            2 -> SCHOOL
            else -> UNKNOWN
        }
    }
}
