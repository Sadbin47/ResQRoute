package com.resqroute.dataset

import com.resqroute.core.model.DatasetIdentity
import com.resqroute.data.dataset.BundledDatasetReader
import com.resqroute.data.dataset.DatasetFormatException
import com.resqroute.data.dataset.sha256Hex
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Corrupt/mismatched artifacts must be rejected with a precise, actionable
 * message. The app then shows a map-unavailable state (acceptance criterion 1):
 * it never builds a partial or fabricated map.
 *
 * Each case rewrites the manifest hash/size so the corruption passes the
 * fingerprint gate and must be caught by the structural/topology validation.
 */
class CorruptDatasetTest {

    private fun expectRejected(modified: ByteArray, expectedFragment: String? = null) {
        val base = TestDatasets.manifest
        val manifest = base.copy(
            contentSha256 = sha256Hex(modified),
            byteCount = modified.size.toLong(),
        )
        val identity = DatasetIdentity(manifest.contentSha256, manifest.formatVersion)
        val error = assertThrows(DatasetFormatException::class.java) {
            BundledDatasetReader.parse(manifest, modified, identity, ioMillis = 0, digestMillis = 0)
        }
        if (expectedFragment != null) {
            assertTrue("message was: ${error.message}", error.message.orEmpty().contains(expectedFragment))
        }
    }

    private fun corrupt(mutate: (ByteArray) -> Unit): ByteArray =
        TestDatasets.datasetBytes.copyOf().also(mutate)

    @Test
    fun rejectsBadMagic() {
        expectRejected(corrupt { it[0] = 'X'.code.toByte() }, "bad magic")
    }

    @Test
    fun rejectsUnsupportedFormatVersion() {
        expectRejected(corrupt { it[8] = 99; it[9] = 0 }, "unsupported format version")
    }

    @Test
    fun rejectsWrongSectionCount() {
        expectRejected(corrupt { it[10] = 0; it[11] = 0 }, "sections")
    }

    @Test
    fun rejectsSectionLengthThatDisagreesWithItsElementCount() {
        val bin = corrupt { }
        // Section 0 descriptor: length field sits at 16 + 0*12 + 4.
        TestDatasets.writeIntLittleEndian(bin, 16 + 4, 3)
        expectRejected(bin)
    }

    @Test
    fun rejectsOutOfRangeNodeLatitude() {
        val bin = corrupt { }
        TestDatasets.writeIntLittleEndian(bin, TestDatasets.sectionOffset(bin, 0), Int.MAX_VALUE)
        expectRejected(bin, "node latitude")
    }

    @Test
    fun rejectsEdgeTargetOutsideTheGraph() {
        val bin = corrupt { }
        TestDatasets.writeIntLittleEndian(bin, TestDatasets.sectionOffset(bin, 4), Int.MAX_VALUE)
        expectRejected(bin, "out of range")
    }

    @Test
    fun rejectsGridEntryOutsideTheSegmentRange() {
        val bin = corrupt { }
        TestDatasets.writeIntLittleEndian(bin, TestDatasets.sectionOffset(bin, 25), Int.MAX_VALUE)
        expectRejected(bin, "grid references segment index")
    }

    @Test
    fun rejectsTruncatedFile() {
        val truncated = TestDatasets.datasetBytes.copyOf(TestDatasets.datasetBytes.size - 128)
        expectRejected(truncated, "extends past the end")
    }

    @Test
    fun rejectsIdentityThatDisagreesWithTheManifest() {
        val manifest = TestDatasets.manifest
        val identity = DatasetIdentity("0".repeat(64), manifest.formatVersion)
        assertThrows(DatasetFormatException::class.java) {
            BundledDatasetReader.parse(manifest, TestDatasets.datasetBytes, identity, 0, 0)
        }
    }
}
