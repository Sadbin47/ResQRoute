package com.resqroute.domain.map

import com.resqroute.core.graph.RoutingGraph
import com.resqroute.core.graph.SegmentSpatialIndex
import com.resqroute.core.graph.Shelter
import com.resqroute.core.model.DatasetIdentity
import kotlinx.coroutines.flow.StateFlow

/**
 * One immutable map dataset for the lifetime of the process: packed routing
 * graph, rendering geometry, shelters and the spatial index.
 *
 * Everything here comes from the same fingerprint ([identity]); a route or a
 * selection is only valid while the dataset identity matches.
 */
data class MapDataset(
    val identity: DatasetIdentity,
    val topologyVersion: String,
    val graph: RoutingGraph,
    val shelters: List<Shelter>,
    val spatialIndex: SegmentSpatialIndex,
    /** True only when the dataset actually carries elevation values. */
    val hasElevation: Boolean,
)

/** Loading state of the bundled dataset. */
sealed interface DatasetState {

    /** No load has been requested yet. */
    data object NotLoaded : DatasetState

    /** Loading is in progress; no partial dataset is published. */
    data object Loading : DatasetState

    /**
     * The one immutable dataset, with the measured cold-load time in
     * milliseconds (acceptance criterion 3).
     */
    data class Ready(
        val dataset: MapDataset,
        val coldLoadMillis: Long,
    ) : DatasetState

    /**
     * The dataset is unusable. The UI must show a map-unavailable state and
     * must never fabricate roads or routes.
     */
    data class Failed(val message: String) : DatasetState
}

/**
 * Process-scoped owner of the bundled dataset.
 *
 * [initialize] is idempotent: concurrent callers share one load, and a
 * completed load is never repeated.
 */
interface MapRepository {

    val state: StateFlow<DatasetState>

    /** Starts (or returns early from) the one-time dataset load. */
    suspend fun initialize()
}
