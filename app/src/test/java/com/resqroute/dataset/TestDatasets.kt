package com.resqroute.dataset

import com.resqroute.data.dataset.DatasetManifest
import com.resqroute.data.dataset.ManifestReader
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Access to the real bundled artifact for JVM unit tests.
 *
 * The dataset is the actual Phase 2 output, not a fixture, so these tests
 * verify the reader against the artifact the app ships. Locates the repository
 * root by walking up from the test working directory; override with the
 * `RESQROUTE_REPO_ROOT` environment variable if needed.
 */
internal object TestDatasets {

    val repoRoot: File = findRepoRoot()

    val datasetFile: File get() = File(repoRoot, "app/src/main/assets/feni_dataset.bin")
    val manifestFile: File get() = File(repoRoot, "app/src/main/assets/feni_dataset_manifest.json")

    val manifest: DatasetManifest by lazy { ManifestReader.parse(manifestFile.readBytes()) }
    val datasetBytes: ByteArray by lazy { datasetFile.readBytes() }
    val manifestBytes: ByteArray by lazy { manifestFile.readBytes() }

    /** Byte offset of a section descriptor's payload (header is 16 + 12*N). */
    fun sectionOffset(bin: ByteArray, index: Int): Int {
        val header = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
        header.position(16 + index * 12)
        return header.int
    }

    fun writeIntLittleEndian(bin: ByteArray, offset: Int, value: Int) {
        bin[offset] = value.toByte()
        bin[offset + 1] = (value ushr 8).toByte()
        bin[offset + 2] = (value ushr 16).toByte()
        bin[offset + 3] = (value ushr 24).toByte()
    }

    private fun findRepoRoot(): File {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            val hasProject = File(directory, "settings.gradle.kts").isFile &&
                File(directory, "app/src/main/assets/feni_dataset.bin").isFile
            if (hasProject) return directory
            directory = directory.parentFile ?: break
        }
        val override = System.getenv("RESQROUTE_REPO_ROOT")
        if (override != null) {
            val candidate = File(override)
            if (candidate.isDirectory) return candidate
        }
        error("cannot locate the ResQRoute repo root from ${System.getProperty("user.dir")}")
    }
}
