package com.resqroute.data.dataset

import java.nio.charset.StandardCharsets

/**
 * Parsed subset of `feni_dataset_manifest.json`.
 *
 * Only the fields the phone actually needs are retained; unknown keys are
 * ignored so an additive manifest change cannot break loading.
 */
data class DatasetManifest(
    val format: String,
    val formatVersion: Int,
    val contentSha256: String,
    val byteCount: Long,
    val topologyVersion: String,
    val nodeCount: Int,
    val edgeCount: Int,
    val segmentCount: Int,
    val pointCount: Int,
    val shelterCount: Int,
    /** `[south, west, north, east]`; origin of the spatial grid. */
    val bboxSouthWestNorthEast: DoubleArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DatasetManifest) return false
        return format == other.format &&
            formatVersion == other.formatVersion &&
            contentSha256 == other.contentSha256 &&
            byteCount == other.byteCount &&
            topologyVersion == other.topologyVersion &&
            nodeCount == other.nodeCount &&
            edgeCount == other.edgeCount &&
            segmentCount == other.segmentCount &&
            pointCount == other.pointCount &&
            shelterCount == other.shelterCount &&
            bboxSouthWestNorthEast.contentEquals(other.bboxSouthWestNorthEast)
    }

    override fun hashCode(): Int {
        var result = format.hashCode()
        result = 31 * result + formatVersion
        result = 31 * result + contentSha256.hashCode()
        result = 31 * result + byteCount.hashCode()
        result = 31 * result + topologyVersion.hashCode()
        result = 31 * result + nodeCount
        result = 31 * result + edgeCount
        result = 31 * result + segmentCount
        result = 31 * result + pointCount
        result = 31 * result + shelterCount
        result = 31 * result + bboxSouthWestNorthEast.contentHashCode()
        return result
    }
}

/**
 * Manifest reader built on the dependency-free [JsonParser].
 *
 * Rejects anything the loader cannot trust: wrong format tag, unsupported
 * version, malformed hash, non-positive size, incomplete counts or a degenerate
 * bounding box.
 */
object ManifestReader {

    private const val EXPECTED_FORMAT = "resqroute-dataset"

    fun parse(bytes: ByteArray): DatasetManifest {
        val root = try {
            JsonParser.parse(String(bytes, StandardCharsets.UTF_8))
        } catch (error: JsonSyntaxException) {
            throw DatasetFormatException("manifest is not valid JSON: ${error.message}", error)
        }

        val obj = root as? JsonValue.ObjectValue
            ?: throw DatasetFormatException("manifest root must be a JSON object")

        fun stringValue(name: String): String? = (obj.values[name] as? JsonValue.StringValue)?.value
        fun numberValue(name: String): Double? = (obj.values[name] as? JsonValue.NumberValue)?.value

        val format = stringValue("format")
            ?: throw DatasetFormatException("manifest is missing 'format'")
        if (format != EXPECTED_FORMAT) {
            throw DatasetFormatException("unsupported manifest format '$format'")
        }

        val formatVersion = numberValue("format_version")?.toInt()
            ?: throw DatasetFormatException("manifest is missing 'format_version'")
        if (formatVersion <= 0) {
            throw DatasetFormatException("manifest has an invalid format_version ($formatVersion)")
        }

        val hash = stringValue("content_sha256")?.trim()?.lowercase()
            ?: throw DatasetFormatException("manifest is missing 'content_sha256'")
        if (hash.length != 64 || !hash.all { it in '0'..'9' || it in 'a'..'f' }) {
            throw DatasetFormatException("manifest content_sha256 is not a 256-bit hex digest")
        }

        val byteCount = numberValue("bytes")?.toLong()
            ?: throw DatasetFormatException("manifest is missing 'bytes'")
        if (byteCount <= 0) throw DatasetFormatException("manifest 'bytes' must be positive")

        val topologyVersion = stringValue("topology_version") ?: ""

        val counts = obj.values["counts"] as? JsonValue.ObjectValue
            ?: throw DatasetFormatException("manifest 'counts' block is missing")
        fun count(name: String): Int = (counts.values[name] as? JsonValue.NumberValue)?.value?.toInt()
            ?: throw DatasetFormatException("manifest 'counts' is missing '$name'")

        val nodeCount = count("nodes")
        val edgeCount = count("edges")
        val segmentCount = count("segments")
        val pointCount = count("points")
        val shelterCount = count("shelters")
        if (nodeCount < 0 || edgeCount < 0 || segmentCount < 0 || pointCount < 0 || shelterCount < 0) {
            throw DatasetFormatException("manifest 'counts' contains negative values")
        }

        val bboxArray = obj.values["bbox_S_W_N_E"] as? JsonValue.ArrayValue
            ?: throw DatasetFormatException("manifest is missing 'bbox_S_W_N_E'")
        val bbox = bboxArray.values.map { (it as? JsonValue.NumberValue)?.value }
        if (bbox.size != 4 || bbox.any { it == null }) {
            throw DatasetFormatException("manifest bbox must hold four numbers")
        }
        val southWestNorthEast = DoubleArray(4) { bbox[it]!! }
        if (!(southWestNorthEast[0] < southWestNorthEast[2]) ||
            !(southWestNorthEast[1] < southWestNorthEast[3])
        ) {
            throw DatasetFormatException("manifest bbox is degenerate")
        }

        return DatasetManifest(
            format = format,
            formatVersion = formatVersion,
            contentSha256 = hash,
            byteCount = byteCount,
            topologyVersion = topologyVersion,
            nodeCount = nodeCount,
            edgeCount = edgeCount,
            segmentCount = segmentCount,
            pointCount = pointCount,
            shelterCount = shelterCount,
            bboxSouthWestNorthEast = southWestNorthEast,
        )
    }
}
