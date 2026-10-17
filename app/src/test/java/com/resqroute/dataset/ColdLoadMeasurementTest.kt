package com.resqroute.dataset

import com.resqroute.core.model.DatasetIdentity
import com.resqroute.data.dataset.BundledDatasetReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Records the cold-load cost of the real artifact on the JVM (acceptance
 * criterion 3: "cold-load time measured and recorded").
 *
 * This is parse + validate + publish — the same work the device performs after
 * the asset bytes are read. On-device cold start additionally includes asset IO
 * and SHA-256, which the repository measures and shows in the Phase 3 panel.
 */
class ColdLoadMeasurementTest {

    @Test
    fun reportsColdLoadBreakdown() {
        val manifest = TestDatasets.manifest
        val bytes = TestDatasets.datasetBytes
        val identity = DatasetIdentity(manifest.contentSha256, manifest.formatVersion)

        val wallStart = System.nanoTime()
        val packed = BundledDatasetReader.parse(manifest, bytes, identity, ioMillis = 0, digestMillis = 0)
        val wallMillis = (System.nanoTime() - wallStart) / 1_000_000

        println(
            "COLD LOAD (JVM, parse+validate+publish): total=${packed.metrics.totalMillis} ms, " +
                "parse=${packed.metrics.parseMillis} ms, validate=${packed.metrics.validateMillis} ms, " +
                "publish=${packed.metrics.graphPublishMillis} ms, wall=$wallMillis ms, " +
                "bytes=${packed.metrics.datasetBytes}",
        )

        assertEquals(manifest.nodeCount, packed.graph.nodeCount)
        assertEquals(manifest.edgeCount, packed.graph.edgeCount)
        // Generous ceiling: catches pathological regressions without being flaky.
        assertTrue("cold load took ${packed.metrics.totalMillis} ms", packed.metrics.totalMillis < 5_000)
    }
}
