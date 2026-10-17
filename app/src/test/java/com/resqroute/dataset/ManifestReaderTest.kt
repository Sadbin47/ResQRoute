package com.resqroute.dataset

import com.resqroute.data.dataset.DatasetFormatException
import com.resqroute.data.dataset.ManifestReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Manifest parsing rules: accept exactly what the loader can trust. */
class ManifestReaderTest {

    private fun validJson(
        format: String = "resqroute-dataset",
        formatVersion: Int = 1,
        sha: String = "a".repeat(64),
        bytes: Long = 100,
        nodes: Int = 1,
        edges: Int = 1,
        segments: Int = 1,
        points: Int = 2,
        shelters: Int = 1,
        bbox: String = "[1.0,2.0,3.0,4.0]",
    ) = """
        {
          "format": "$format",
          "format_version": $formatVersion,
          "content_sha256": "$sha",
          "bytes": $bytes,
          "topology_version": "feni-road-graph-v0.1-prototype",
          "counts": {
            "nodes": $nodes,
            "edges": $edges,
            "segments": $segments,
            "points": $points,
            "shelters": $shelters
          },
          "bbox_S_W_N_E": $bbox,
          "notes": ["ignored", "by the reader"]
        }
    """.trimIndent()

    private fun parse(json: String) = ManifestReader.parse(json.toByteArray(Charsets.UTF_8))

    @Test
    fun parsesValidManifestAndIgnoresUnknownKeys() {
        val manifest = parse(validJson())
        assertEquals("resqroute-dataset", manifest.format)
        assertEquals(1, manifest.formatVersion)
        assertEquals("a".repeat(64), manifest.contentSha256)
        assertEquals(100L, manifest.byteCount)
        assertEquals(1, manifest.nodeCount)
        assertEquals(1, manifest.edgeCount)
        assertEquals(1, manifest.segmentCount)
        assertEquals(2, manifest.pointCount)
        assertEquals(1, manifest.shelterCount)
        assertEquals(4, manifest.bboxSouthWestNorthEast.size)
        assertEquals(1.0, manifest.bboxSouthWestNorthEast[0], 0.0)
    }

    @Test
    fun parsesTheShippedManifest() {
        val manifest = parse(String(TestDatasets.manifestBytes, Charsets.UTF_8))
        assertEquals("resqroute-dataset", manifest.format)
        assertEquals(1, manifest.formatVersion)
        assertEquals(TestDatasets.datasetFile.length(), manifest.byteCount)
        assertTrue(manifest.nodeCount > 0)
        assertTrue(manifest.edgeCount > manifest.nodeCount)
        assertTrue(manifest.segmentCount > 0)
        assertTrue(manifest.shelterCount > 0)
    }

    @Test
    fun rejectsWrongFormatTag() {
        val error = assertThrows(DatasetFormatException::class.java) {
            parse(validJson(format = "something-else"))
        }
        assertTrue(error.message!!.contains("unsupported manifest format"))
    }

    @Test
    fun rejectsNonHexOrShortHash() {
        assertThrows(DatasetFormatException::class.java) { parse(validJson(sha = "not-a-hash")) }
        assertThrows(DatasetFormatException::class.java) { parse(validJson(sha = "abc123")) }
    }

    @Test
    fun rejectsNonPositiveByteCount() {
        assertThrows(DatasetFormatException::class.java) { parse(validJson(bytes = 0)) }
        assertThrows(DatasetFormatException::class.java) { parse(validJson(bytes = -5)) }
    }

    @Test
    fun rejectsInvalidFormatVersion() {
        assertThrows(DatasetFormatException::class.java) { parse(validJson(formatVersion = 0)) }
    }

    @Test
    fun rejectsDegenerateBoundingBox() {
        assertThrows(DatasetFormatException::class.java) {
            parse(validJson(bbox = "[3.0,2.0,1.0,4.0]"))
        }
    }

    @Test
    fun rejectsMalformedJsonAndTrailingContent() {
        assertThrows(DatasetFormatException::class.java) { parse("{") }
        assertThrows(DatasetFormatException::class.java) { parse(validJson() + "{}") }
    }

    @Test
    fun readsEscapedStringsAndNumbers() {
        val json = validJson().replace(
            "\"topology_version\": \"feni-road-graph-v0.1-prototype\"",
            "\"topology_version\": \"line\\nbreak \\u0041\"",
        )
        val manifest = parse(json)
        assertEquals("line\nbreak A", manifest.topologyVersion)
    }
}
