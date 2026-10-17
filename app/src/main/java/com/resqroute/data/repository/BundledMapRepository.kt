package com.resqroute.data.repository

import android.util.Log
import com.resqroute.data.dataset.BundledDatasetReader
import com.resqroute.data.dataset.DatasetFormatException
import com.resqroute.data.dataset.DatasetSource
import com.resqroute.data.dataset.ManifestReader
import com.resqroute.data.dataset.PackedDataset
import com.resqroute.data.dataset.sha256Hex
import com.resqroute.core.model.DatasetIdentity
import com.resqroute.domain.map.DatasetState
import com.resqroute.domain.map.MapDataset
import com.resqroute.domain.map.MapRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loads the bundled artifact exactly once and publishes it as the sole
 * [MapDataset] for the process.
 *
 * Threading (acceptance criterion 3):
 *  * asset reads happen on [ioDispatcher];
 *  * manifest parsing, SHA-256 verification, section parsing, topology
 *    validation and index construction happen on [computeDispatcher].
 *
 * Failure is explicit: the state becomes [DatasetState.Failed] with a concrete
 * message and no partial dataset is ever published.
 */
class BundledMapRepository(
    private val source: DatasetSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MapRepository {

    private val loadMutex = Mutex()
    private val _state = MutableStateFlow<DatasetState>(DatasetState.NotLoaded)
    override val state: StateFlow<DatasetState> = _state.asStateFlow()

    override suspend fun initialize() {
        loadMutex.withLock {
            if (_state.value is DatasetState.Ready) return
            _state.value = DatasetState.Loading
            try {
                val packed = load()
                _state.value = DatasetState.Ready(packed.toMapDataset(), packed.metrics.totalMillis)
                Log.i(TAG, "dataset ready\n${packed.metrics.render()}")
            } catch (cancellation: CancellationException) {
                _state.value = DatasetState.NotLoaded
                throw cancellation
            } catch (error: Exception) {
                val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                _state.value = DatasetState.Failed(message)
                Log.e(TAG, "dataset load failed: $message", error)
            }
        }
    }

    private suspend fun load(): PackedDataset {
        val ioStart = System.nanoTime()
        val manifestBytes = withContext(ioDispatcher) { source.readManifestBytes() }
        val datasetBytes = withContext(ioDispatcher) { source.readDatasetBytes() }
        val ioMillis = (System.nanoTime() - ioStart) / 1_000_000

        val manifest = withContext(computeDispatcher) { ManifestReader.parse(manifestBytes) }

        val digestStart = System.nanoTime()
        val actualHash = withContext(computeDispatcher) { sha256Hex(datasetBytes) }
        val digestMillis = (System.nanoTime() - digestStart) / 1_000_000
        if (!actualHash.equals(manifest.contentSha256, ignoreCase = true)) {
            throw DatasetFormatException(
                "dataset fingerprint mismatch: file=$actualHash manifest=${manifest.contentSha256}. " +
                    "Refusing to load a corrupt map.",
            )
        }

        val identity = DatasetIdentity(
            contentHash = manifest.contentSha256,
            formatVersion = manifest.formatVersion,
        )
        return withContext(computeDispatcher) {
            BundledDatasetReader.parse(
                manifest = manifest,
                bin = datasetBytes,
                identity = identity,
                ioMillis = ioMillis,
                digestMillis = digestMillis,
            )
        }
    }

    private fun PackedDataset.toMapDataset(): MapDataset = MapDataset(
        identity = identity,
        topologyVersion = graph.topologyVersion,
        graph = graph,
        shelters = shelters,
        spatialIndex = spatialIndex,
        hasElevation = graph.hasElevation,
    )

    private companion object {
        const val TAG = "ResQRouteDataset"
    }
}
