package com.resqroute.data.dataset

import com.resqroute.core.graph.PackedGridSegmentSpatialIndex
import com.resqroute.core.graph.PackedRoutingGraph
import com.resqroute.core.graph.SegmentFlags
import com.resqroute.core.graph.SegmentTraversal
import com.resqroute.core.model.DatasetIdentity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Parser for the one versioned binary artifact produced by
 * `tools/dataset/build_dataset.py` (format spec: `tools/dataset/DATASET_FORMAT.md`).
 *
 * Guarantees on success:
 *  * the bytes match the manifest fingerprint (checked by the repository before
 *    this parser runs, and re-asserted here through [identity]);
 *  * every section has the exact expected element count and byte length;
 *  * all topology references resolve (edge targets, segment membership, geometry
 *    slices, shelter access nodes, grid entries);
 *  * segment direction flags agree with the directed edges that were stored;
 *  * the result is one immutable [PackedDataset]; nothing mutable escapes.
 *
 * Any deviation throws [DatasetFormatException] with a precise message. The app
 * must then show a map-unavailable state — never a partially built map.
 */
object BundledDatasetReader {

    private const val MAGIC = "RQRDDS01"
    private const val SUPPORTED_FORMAT_VERSION = 1
    private const val SECTION_COUNT = 26

    // Fixed section order from the format spec.
    private const val S_NODE_LAT = 0
    private const val S_NODE_LON = 1
    private const val S_NODE_OSM_ID = 2
    private const val S_EDGE_OFFSET = 3
    private const val S_EDGE_TARGET = 4
    private const val S_EDGE_SEGMENT = 5
    private const val S_EDGE_COST = 6
    private const val S_SEG_GEO_OFFSET = 7
    private const val S_POINT_LAT = 8
    private const val S_POINT_LON = 9
    private const val S_SEG_LENGTH = 10
    private const val S_SEG_FLAGS = 11
    private const val S_SEG_WAY_ID = 12
    private const val S_SEG_NAME_OFFSET = 13
    private const val S_SEG_NAME_POOL = 14
    private const val S_SHELTER_LAT = 15
    private const val S_SHELTER_LON = 16
    private const val S_SHELTER_ACCESS_NODE = 17
    private const val S_SHELTER_FLAGS = 18
    private const val S_SHELTER_NAME_OFFSET = 19
    private const val S_SHELTER_NAME_POOL = 20
    private const val S_GRID_NX = 21
    private const val S_GRID_NY = 22
    private const val S_GRID_CELL_DEG = 23
    private const val S_GRID_CELL_OFFSET = 24
    private const val S_GRID_CELL_SEG = 25

    private const val E7_MIN_LAT = -900_000_000
    private const val E7_MAX_LAT = 900_000_000
    private const val E7_MIN_LON = -1_800_000_000
    private const val E7_MAX_LON = 1_800_000_000

    fun parse(
        manifest: DatasetManifest,
        bin: ByteArray,
        identity: DatasetIdentity,
        ioMillis: Long,
        digestMillis: Long,
    ): PackedDataset {
        if (identity.contentHash != manifest.contentSha256 || identity.formatVersion != manifest.formatVersion) {
            throw DatasetFormatException("identity does not match the manifest fingerprint")
        }
        if (bin.size.toLong() != manifest.byteCount) {
            throw DatasetFormatException(
                "dataset is ${bin.size} bytes but the manifest declares ${manifest.byteCount}",
            )
        }

        val parseStart = System.nanoTime()

        val buffer = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8)
        buffer.get(magic)
        if (String(magic, StandardCharsets.US_ASCII) != MAGIC) {
            throw DatasetFormatException(
                "bad magic: expected '$MAGIC', found '${String(magic, StandardCharsets.US_ASCII)}'",
            )
        }
        val formatVersion = buffer.short.toInt() and 0xFFFF
        if (formatVersion != SUPPORTED_FORMAT_VERSION) {
            throw DatasetFormatException(
                "unsupported format version $formatVersion (expected $SUPPORTED_FORMAT_VERSION)",
            )
        }
        if (formatVersion != manifest.formatVersion) {
            throw DatasetFormatException("binary format version $formatVersion != manifest ${manifest.formatVersion}")
        }
        val sectionCount = buffer.short.toInt() and 0xFFFF
        if (sectionCount != SECTION_COUNT) {
            throw DatasetFormatException("expected $SECTION_COUNT sections, found $sectionCount")
        }
        buffer.int // reserved

        val offsets = IntArray(sectionCount)
        val lengths = IntArray(sectionCount)
        val counts = IntArray(sectionCount)
        for (i in 0 until sectionCount) {
            val offset = buffer.int
            val length = buffer.int
            val count = buffer.int
            if (offset < 0 || length < 0 || count < 0) {
                throw DatasetFormatException("section $i has a negative header value")
            }
            if (offset.toLong() + length.toLong() > bin.size.toLong()) {
                throw DatasetFormatException("section $i extends past the end of the file")
            }
            offsets[i] = offset
            lengths[i] = length
            counts[i] = count
        }

        val elementBytes = IntArray(SECTION_COUNT) { 4 }
        elementBytes[S_NODE_OSM_ID] = 8
        elementBytes[S_SEG_WAY_ID] = 8
        elementBytes[S_SEG_FLAGS] = 1
        elementBytes[S_SEG_NAME_POOL] = 1
        elementBytes[S_SHELTER_FLAGS] = 1
        elementBytes[S_SHELTER_NAME_POOL] = 1
        for (i in 0 until SECTION_COUNT) {
            val expected = counts[i].toLong() * elementBytes[i]
            if (lengths[i].toLong() != expected) {
                throw DatasetFormatException(
                    "section $i byte length ${lengths[i]} does not match ${counts[i]} elements x ${elementBytes[i]}",
                )
            }
        }

        fun ints(i: Int): IntArray {
            val out = IntArray(counts[i])
            val reader = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            reader.position(offsets[i])
            reader.asIntBuffer().get(out)
            return out
        }

        fun floats(i: Int): FloatArray {
            val out = FloatArray(counts[i])
            val reader = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            reader.position(offsets[i])
            reader.asFloatBuffer().get(out)
            return out
        }

        fun longs(i: Int): LongArray {
            val out = LongArray(counts[i])
            val reader = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            reader.position(offsets[i])
            reader.asLongBuffer().get(out)
            return out
        }

        fun bytes(i: Int): ByteArray {
            val out = ByteArray(counts[i])
            val reader = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            reader.position(offsets[i])
            reader.get(out)
            return out
        }

        val nodeLatE7 = ints(S_NODE_LAT)
        val nodeLonE7 = ints(S_NODE_LON)
        val nodeOsmId = longs(S_NODE_OSM_ID)
        val edgeOffset = ints(S_EDGE_OFFSET)
        val edgeTarget = ints(S_EDGE_TARGET)
        val edgeSegment = ints(S_EDGE_SEGMENT)
        val edgeCostMeters = floats(S_EDGE_COST)
        val segGeoOffset = ints(S_SEG_GEO_OFFSET)
        val pointLatE7 = ints(S_POINT_LAT)
        val pointLonE7 = ints(S_POINT_LON)
        val segLengthMeters = floats(S_SEG_LENGTH)
        val segFlags = bytes(S_SEG_FLAGS)
        val segOsmWayId = longs(S_SEG_WAY_ID)
        val segNameOffset = ints(S_SEG_NAME_OFFSET)
        val segNamePool = bytes(S_SEG_NAME_POOL)
        val shelterLatE7 = ints(S_SHELTER_LAT)
        val shelterLonE7 = ints(S_SHELTER_LON)
        val shelterAccessNode = ints(S_SHELTER_ACCESS_NODE)
        val shelterFlags = bytes(S_SHELTER_FLAGS)
        val shelterNameOffset = ints(S_SHELTER_NAME_OFFSET)
        val shelterNamePool = bytes(S_SHELTER_NAME_POOL)
        val gridNx = ints(S_GRID_NX)
        val gridNy = ints(S_GRID_NY)
        val gridCellDegrees = floats(S_GRID_CELL_DEG)
        val gridCellOffset = ints(S_GRID_CELL_OFFSET)
        val gridCellSegments = ints(S_GRID_CELL_SEG)

        val nodeCount = nodeLatE7.size
        val edgeCount = edgeTarget.size
        val segmentCount = segFlags.size
        val pointCount = pointLatE7.size
        val shelterCount = shelterFlags.size

        // ------------------------------------------------ structural relations
        if (counts[S_NODE_LON] != nodeCount || counts[S_NODE_OSM_ID] != nodeCount) {
            throw DatasetFormatException("node sections disagree on the node count")
        }
        if (counts[S_EDGE_OFFSET] != nodeCount + 1) {
            throw DatasetFormatException("edge_offset must hold nodeCount + 1 values")
        }
        if (counts[S_EDGE_SEGMENT] != edgeCount || counts[S_EDGE_COST] != edgeCount) {
            throw DatasetFormatException("edge sections disagree on the edge count")
        }
        if (counts[S_SEG_GEO_OFFSET] != segmentCount + 1) {
            throw DatasetFormatException("seg_geo_offset must hold segmentCount + 1 values")
        }
        if (counts[S_POINT_LON] != pointCount) {
            throw DatasetFormatException("point sections disagree on the point count")
        }
        if (counts[S_SEG_LENGTH] != segmentCount ||
            counts[S_SEG_WAY_ID] != segmentCount ||
            counts[S_SEG_NAME_OFFSET] != segmentCount + 1
        ) {
            throw DatasetFormatException("segment sections disagree on the segment count")
        }
        if (counts[S_SHELTER_LAT] != shelterCount ||
            counts[S_SHELTER_LON] != shelterCount ||
            counts[S_SHELTER_ACCESS_NODE] != shelterCount ||
            counts[S_SHELTER_NAME_OFFSET] != shelterCount + 1
        ) {
            throw DatasetFormatException("shelter sections disagree on the shelter count")
        }
        if (counts[S_GRID_NX] != 1 || counts[S_GRID_NY] != 1 || counts[S_GRID_CELL_DEG] != 1) {
            throw DatasetFormatException("grid scalar sections must each hold exactly one value")
        }

        // Manifest counts must describe exactly what the binary contains.
        if (nodeCount != manifest.nodeCount ||
            edgeCount != manifest.edgeCount ||
            segmentCount != manifest.segmentCount ||
            pointCount != manifest.pointCount ||
            shelterCount != manifest.shelterCount
        ) {
            throw DatasetFormatException(
                "binary counts (nodes=$nodeCount edges=$edgeCount segments=$segmentCount " +
                    "points=$pointCount shelters=$shelterCount) do not match the manifest " +
                    "(nodes=${manifest.nodeCount} edges=${manifest.edgeCount} segments=${manifest.segmentCount} " +
                    "points=${manifest.pointCount} shelters=${manifest.shelterCount})",
            )
        }
        if (nodeCount <= 0 || edgeCount <= 0 || segmentCount <= 0 || shelterCount <= 0) {
            throw DatasetFormatException("the dataset must contain nodes, edges, segments and shelters")
        }

        val parseMillis = (System.nanoTime() - parseStart) / 1_000_000

        // ------------------------------------------------------------ validation
        val validateStart = System.nanoTime()

        checkRanges(nodeLatE7, E7_MIN_LAT, E7_MAX_LAT, "node latitude")
        checkRanges(nodeLonE7, E7_MIN_LON, E7_MAX_LON, "node longitude")
        checkRanges(pointLatE7, E7_MIN_LAT, E7_MAX_LAT, "geometry point latitude")
        checkRanges(pointLonE7, E7_MIN_LON, E7_MAX_LON, "geometry point longitude")
        checkRanges(shelterLatE7, E7_MIN_LAT, E7_MAX_LAT, "shelter latitude")
        checkRanges(shelterLonE7, E7_MIN_LON, E7_MAX_LON, "shelter longitude")

        for (i in 1 until nodeCount) {
            if (nodeOsmId[i] <= nodeOsmId[i - 1]) {
                throw DatasetFormatException("node OSM ids must be strictly ascending (index $i)")
            }
        }

        checkCsr(edgeOffset, edgeCount, "edge_offset")
        checkCsr(segGeoOffset, pointCount, "seg_geo_offset")
        checkCsr(segNameOffset, segNamePool.size, "seg_name_offset")
        checkCsr(shelterNameOffset, shelterNamePool.size, "shelter_name_offset")

        val nx = gridNx[0]
        val ny = gridNy[0]
        val cellDegrees = gridCellDegrees[0]
        if (nx <= 0 || ny <= 0) throw DatasetFormatException("spatial grid dimensions must be positive")
        if (!cellDegrees.isFinite() || cellDegrees <= 0f) {
            throw DatasetFormatException("spatial grid cell size must be positive and finite")
        }
        if (gridCellOffset.size.toLong() != nx.toLong() * ny + 1L) {
            throw DatasetFormatException("grid_cell_offset must hold nx * ny + 1 values")
        }
        checkCsr(gridCellOffset, gridCellSegments.size, "grid_cell_offset")
        for (value in gridCellSegments) {
            if (value < 0 || value >= segmentCount) {
                throw DatasetFormatException("grid references segment index $value out of range")
            }
        }

        // Directed edges: targets, segment membership, finite positive cost.
        val segmentEdgeCount = IntArray(segmentCount)
        for (edge in 0 until edgeCount) {
            val target = edgeTarget[edge]
            if (target < 0 || target >= nodeCount) {
                throw DatasetFormatException("edge $edge targets node index $target out of range")
            }
            val segment = edgeSegment[edge]
            if (segment < 0 || segment >= segmentCount) {
                throw DatasetFormatException("edge $edge references segment index $segment out of range")
            }
            val cost = edgeCostMeters[edge]
            if (!cost.isFinite() || cost <= 0f) {
                throw DatasetFormatException("edge $edge has a non-positive or non-finite cost ($cost)")
            }
            segmentEdgeCount[segment]++
        }

        for (segment in 0 until segmentCount) {
            val flags = segFlags[segment].toInt() and 0xFF
            val eligible = flags and SegmentFlags.ELIGIBLE != 0
            val traversal = SegmentTraversal.of(flags)
            val directionCount = when (traversal) {
                SegmentTraversal.FORWARD, SegmentTraversal.BACKWARD -> 1
                SegmentTraversal.BOTH -> 2
                SegmentTraversal.NONE -> 0
            }
            val actualEdges = segmentEdgeCount[segment]
            if (eligible) {
                // A routable segment must carry a traversal direction and one
                // directed edge per direction.
                if (directionCount == 0) {
                    throw DatasetFormatException(
                        "eligible segment $segment declares no traversal direction",
                    )
                }
                if (actualEdges != directionCount) {
                    throw DatasetFormatException(
                        "segment $segment has $actualEdges directed edge(s) but its flags imply $directionCount",
                    )
                }
            } else {
                // Excluded segments keep their source oneway bits but must never
                // produce directed edges: they can be drawn but never routed.
                if (actualEdges != 0) {
                    throw DatasetFormatException(
                        "ineligible segment $segment unexpectedly owns $actualEdges directed edge(s)",
                    )
                }
            }
            val length = segLengthMeters[segment]
            if (!length.isFinite() || length <= 0f) {
                throw DatasetFormatException("segment $segment has a non-positive or non-finite length")
            }
            val geometryPoints = segGeoOffset[segment + 1] - segGeoOffset[segment]
            if (geometryPoints < 1) {
                throw DatasetFormatException("segment $segment has no geometry points")
            }
            if (eligible && geometryPoints < 2) {
                throw DatasetFormatException("routable segment $segment needs at least two geometry points")
            }
        }

        for (shelter in 0 until shelterCount) {
            val node = shelterAccessNode[shelter]
            if (node < 0 || node >= nodeCount) {
                throw DatasetFormatException("shelter $shelter references node index $node out of range")
            }
            if (shelterNameOffset[shelter + 1] < shelterNameOffset[shelter]) {
                throw DatasetFormatException("shelter $shelter name offsets are inverted")
            }
        }

        val validateMillis = (System.nanoTime() - validateStart) / 1_000_000

        // -------------------------------------------------------------- publish
        val publishStart = System.nanoTime()
        val graph = PackedRoutingGraph(
            identity = identity,
            topologyVersion = manifest.topologyVersion,
            nodeLatE7 = nodeLatE7,
            nodeLonE7 = nodeLonE7,
            nodeOsmId = nodeOsmId,
            edgeOffset = edgeOffset,
            edgeTarget = edgeTarget,
            edgeSegment = edgeSegment,
            edgeCostMeters = edgeCostMeters,
            segGeoOffset = segGeoOffset,
            pointLatE7 = pointLatE7,
            pointLonE7 = pointLonE7,
            segLengthMeters = segLengthMeters,
            segFlags = segFlags,
            segOsmWayId = segOsmWayId,
            segNameOffset = segNameOffset,
            segNamePool = segNamePool,
            shelterLatE7 = shelterLatE7,
            shelterLonE7 = shelterLonE7,
            shelterAccessNode = shelterAccessNode,
            shelterFlags = shelterFlags,
            shelterNameOffset = shelterNameOffset,
            shelterNamePool = shelterNamePool,
            southLatitude = manifest.bboxSouthWestNorthEast[0],
            westLongitude = manifest.bboxSouthWestNorthEast[1],
            northLatitude = manifest.bboxSouthWestNorthEast[2],
            eastLongitude = manifest.bboxSouthWestNorthEast[3],
        )
        val spatialIndex = PackedGridSegmentSpatialIndex(
            south = manifest.bboxSouthWestNorthEast[0],
            west = manifest.bboxSouthWestNorthEast[1],
            gridNx = nx,
            gridNy = ny,
            cellDegrees = cellDegrees.toDouble(),
            cellOffset = gridCellOffset,
            cellSegments = gridCellSegments,
            graph = graph,
        )
        val shelters = List(shelterCount) { graph.shelter(it) }
        val publishMillis = (System.nanoTime() - publishStart) / 1_000_000

        return PackedDataset(
            identity = identity,
            graph = graph,
            shelters = shelters,
            spatialIndex = spatialIndex,
            metrics = LoadMetrics(
                totalMillis = ioMillis + digestMillis + parseMillis + validateMillis + publishMillis,
                ioMillis = ioMillis,
                digestMillis = digestMillis,
                parseMillis = parseMillis,
                validateMillis = validateMillis,
                graphPublishMillis = publishMillis,
                datasetBytes = bin.size.toLong(),
                nodeCount = nodeCount,
                edgeCount = edgeCount,
                segmentCount = segmentCount,
                pointCount = pointCount,
                shelterCount = shelterCount,
            ),
        )
    }

    private fun checkRanges(values: IntArray, min: Int, max: Int, what: String) {
        for (index in values.indices) {
            val value = values[index]
            if (value < min || value > max) {
                throw DatasetFormatException("$what $index out of range ($value)")
            }
        }
    }

    private fun checkCsr(offsets: IntArray, end: Int, what: String) {
        if (offsets.isEmpty()) throw DatasetFormatException("$what is empty")
        if (offsets[0] != 0) throw DatasetFormatException("$what must start at 0")
        if (offsets[offsets.size - 1] != end) {
            throw DatasetFormatException("$what must end at $end but ends at ${offsets[offsets.size - 1]}")
        }
        for (i in 1 until offsets.size) {
            if (offsets[i] < offsets[i - 1]) {
                throw DatasetFormatException("$what must be non-decreasing (index $i)")
            }
        }
    }
}
