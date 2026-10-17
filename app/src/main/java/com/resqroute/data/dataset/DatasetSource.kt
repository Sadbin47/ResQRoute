package com.resqroute.data.dataset

import android.content.Context
import com.resqroute.core.graph.PackedRoutingGraph
import com.resqroute.core.graph.SegmentSpatialIndex
import com.resqroute.core.graph.Shelter
import com.resqroute.core.model.DatasetIdentity

/**
 * Raw bytes of the bundled artifact, read without interpretation.
 *
 * Kept separate from parsing so IO and CPU work can run on different
 * dispatchers and so tests can supply fixtures without Android assets.
 */
interface DatasetSource {

    suspend fun readManifestBytes(): ByteArray

    suspend fun readDatasetBytes(): ByteArray
}

/** Loads the artifact that ships inside the APK. Nothing is downloaded. */
class BundledDatasetSource(private val context: Context) : DatasetSource {

    override suspend fun readManifestBytes(): ByteArray =
        context.assets.open(MANIFEST_ASSET).use { it.readBytes() }

    override suspend fun readDatasetBytes(): ByteArray =
        context.assets.open(DATASET_ASSET).use { it.readBytes() }

    companion object {
        const val DATASET_ASSET = "feni_dataset.bin"
        const val MANIFEST_ASSET = "feni_dataset_manifest.json"
    }
}

/** Fully loaded, immutable dataset plus how long loading took. */
data class PackedDataset(
    val identity: DatasetIdentity,
    val graph: PackedRoutingGraph,
    val shelters: List<Shelter>,
    val spatialIndex: SegmentSpatialIndex,
    val metrics: LoadMetrics,
)

/**
 * Evidence for Phase 3 acceptance criterion 3 (cold-load time measured and
 * recorded). Values are wall-clock milliseconds except the byte count.
 */
data class LoadMetrics(
    val totalMillis: Long,
    val ioMillis: Long,
    val digestMillis: Long,
    val parseMillis: Long,
    val validateMillis: Long,
    val graphPublishMillis: Long,
    val datasetBytes: Long,
    val nodeCount: Int,
    val edgeCount: Int,
    val segmentCount: Int,
    val pointCount: Int,
    val shelterCount: Int,
) {
    fun render(): String = buildString {
        append("dataset ${datasetBytes / 1024} KiB · nodes=$nodeCount edges=$edgeCount ")
        append("segments=$segmentCount points=$pointCount shelters=$shelterCount\n")
        append(
            "cold load ${totalMillis} ms (io ${ioMillis} + sha256 ${digestMillis} + " +
                "parse ${parseMillis} + validate ${validateMillis} + index ${graphPublishMillis})",
        )
    }
}
