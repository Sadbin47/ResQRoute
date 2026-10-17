package com.resqroute.core.model

import java.util.Locale

/** Stable node identity: the source OpenStreetMap node id. */
@JvmInline
value class NodeId(val value: Long)

/**
 * Dense directed-edge index inside one published routing graph.
 *
 * Stable for as long as the owning [DatasetIdentity] does not change, which is
 * the lifetime of the process. Persistence and the wire protocol use physical
 * segments, not edge ids.
 */
@JvmInline
value class EdgeId(val value: Int)

/**
 * Physical road segment: 0-based index in the bundled dataset.
 *
 * The Phase 0 stable id is `"S%06d" % (index + 1)` (see
 * `tools/dataset/DATASET_FORMAT.md`). Blocking one segment excludes every
 * directed edge that belongs to it.
 */
@JvmInline
value class SegmentId(val value: Int) {
    /** Phase 0 stable id, e.g. index 0 -> `S000001`. */
    val stableId: String get() = String.format(Locale.US, "S%06d", value + 1)
}

/** Fingerprint + format version of the immutable bundled dataset. */
data class DatasetIdentity(
    val contentHash: String,
    val formatVersion: Int,
)
