package com.resqroute.dataset

import com.resqroute.core.model.DatasetIdentity
import com.resqroute.data.dataset.BundledDatasetReader
import com.resqroute.data.dataset.PackedDataset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Candidate queries used by long-press selection, against the real dataset. */
class SpatialIndexNearbyTest {

    private val loaded: PackedDataset by lazy {
        val manifest = TestDatasets.manifest
        BundledDatasetReader.parse(
            manifest = manifest,
            bin = TestDatasets.datasetBytes,
            identity = DatasetIdentity(manifest.contentSha256, manifest.formatVersion),
            ioMillis = 0,
            digestMillis = 0,
        )
    }

    @Test
    fun returnsCandidatesSortedByDistanceWithinTheLimit() = runBlocking {
        val index = loaded.spatialIndex
        val shelter = loaded.shelters.first()

        val hits = index.nearbySegments(shelter.coordinate, 300.0, limit = 8)
        assertTrue("expected candidates near a shelter access node", hits.isNotEmpty())
        assertTrue(hits.size <= 8)

        // Sorted ascending by distance, ties by segment index.
        for (i in 1 until hits.size) {
            assertTrue(
                "hits not sorted: ${hits[i - 1].distanceMeters} then ${hits[i].distanceMeters}",
                hits[i - 1].distanceMeters <= hits[i].distanceMeters + 1e-9,
            )
        }
        hits.forEach { assertTrue(it.distanceMeters <= 300.0) }
    }

    @Test
    fun limitOneEqualsNearestSegment() = runBlocking {
        val index = loaded.spatialIndex
        val shelter = loaded.shelters.first()

        val nearest = index.nearestSegment(shelter.coordinate, 300.0)
        val limited = index.nearbySegments(shelter.coordinate, 300.0, limit = 1)
        assertEquals(nearest, limited.firstOrNull())
    }

    @Test
    fun zeroOrNegativeRadiusReturnsNothing() = runBlocking {
        val index = loaded.spatialIndex
        val shelter = loaded.shelters.first()
        assertTrue(index.nearbySegments(shelter.coordinate, 0.0, 8).isEmpty())
        assertTrue(index.nearbySegments(shelter.coordinate, -5.0, 8).isEmpty())
        assertTrue(index.nearbySegments(shelter.coordinate, 100.0, 0).isEmpty())
    }

    @Test
    fun farAwayQueryReturnsNothing() = runBlocking {
        val index = loaded.spatialIndex
        assertTrue(index.nearbySegments(com.resqroute.core.model.GeoCoordinate(0.0, 0.0), 100.0, 8).isEmpty())
    }
}
