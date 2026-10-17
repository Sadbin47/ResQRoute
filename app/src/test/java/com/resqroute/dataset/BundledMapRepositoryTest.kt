package com.resqroute.dataset

import com.resqroute.data.dataset.DatasetSource
import com.resqroute.data.repository.BundledMapRepository
import com.resqroute.domain.map.DatasetState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Repository behaviour: publish once, fail honestly, never publish a bad map. */
class BundledMapRepositoryTest {

    private class BytesDatasetSource(
        private val manifestBytes: ByteArray,
        private val datasetBytes: ByteArray,
    ) : DatasetSource {
        override suspend fun readManifestBytes(): ByteArray = manifestBytes
        override suspend fun readDatasetBytes(): ByteArray = datasetBytes
    }

    private fun repository(
        manifest: ByteArray = TestDatasets.manifestBytes,
        dataset: ByteArray = TestDatasets.datasetBytes,
    ) = BundledMapRepository(
        source = BytesDatasetSource(manifest, dataset),
        ioDispatcher = Dispatchers.Unconfined,
        computeDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun publishesOneImmutableDatasetWithMeasuredColdLoadTime() = runBlocking {
        val repository = repository()
        repository.initialize()

        val state = repository.state.value
        assertTrue("expected Ready but was $state", state is DatasetState.Ready)
        state as DatasetState.Ready

        assertEquals(TestDatasets.manifest.nodeCount, state.dataset.graph.nodeCount)
        assertEquals(TestDatasets.manifest.edgeCount, state.dataset.graph.edgeCount)
        assertEquals(TestDatasets.manifest.shelterCount, state.dataset.shelters.size)
        assertEquals(TestDatasets.manifest.contentSha256, state.dataset.identity.contentHash)
        assertFalse(state.dataset.hasElevation)
        assertTrue(state.coldLoadMillis >= 0)
    }

    @Test
    fun initializeIsIdempotent() = runBlocking {
        val repository = repository()
        repository.initialize()
        val first = repository.state.value as DatasetState.Ready

        repository.initialize()
        val second = repository.state.value
        assertTrue(second is DatasetState.Ready)
        // The same published instance is retained; the load is not repeated.
        assertTrue(first.dataset === (second as DatasetState.Ready).dataset)
    }

    @Test
    fun fingerprintMismatchFailsInsteadOfPublishingAMap() = runBlocking {
        val corrupted = TestDatasets.datasetBytes.copyOf().also { bytes ->
            bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        }
        val repository = repository(dataset = corrupted)
        repository.initialize()

        val state = repository.state.value
        assertTrue("expected Failed but was $state", state is DatasetState.Failed)
        assertTrue((state as DatasetState.Failed).message.contains("fingerprint mismatch"))
    }
}
