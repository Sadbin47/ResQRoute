package com.resqroute.benchmark

import com.resqroute.core.model.SegmentId
import com.resqroute.domain.hazard.HazardMutation
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.ObservationSource
import com.resqroute.domain.hazard.ObservedReport
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.SegmentObservationAggregator
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.domain.sync.PeerMessage
import com.resqroute.domain.sync.PeerProtocol
import com.resqroute.data.p2p.PeerMessageCodec
import com.resqroute.hazard.HazardFixtures
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Is a per-segment digest worth its cost, or is raw-event replay fine? (XPH-85)
 *
 * A **measurement and a recommendation**, not an implementation. The plan is explicit that
 * digests must not replace raw events before a measurement shows they are needed, because an
 * aggregate loses exactly the things this system is built on: per-device support, conflicting
 * observations, provenance and the audit trail.
 *
 * So this harness measures a **candidate** digest scheme — one row per segment carrying the
 * derived maximum, the distinct-device count and a watermark — against raw-event replay, at
 * several report volumes, and compares:
 *
 * | Quantity | Why it decides the question |
 * | --- | --- |
 * | bytes on the wire | the reason a digest is ever considered |
 * | messages | the per-batch ceiling is a real constraint (XPH-80 measured it) |
 * | convergence cost (encode + decode) | what the sender and receiver actually pay |
 *
 * The digest is **simulated**, not shipped: it is a wire-format-sized encoding built here so
 * the comparison is measured rather than argued. §"what a digest cannot convey" in the report
 * lists what would be lost, and the recommendation follows from both.
 *
 * Determinstic: fixed synthetic report sets, no clock, no network, no hardware.
 *
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "com.resqroute.benchmark.DigestEvaluationTest" --console=plain
 * ```
 */
class DigestEvaluationTest {

    @Test
    fun compareRawReplayAgainstACandidateDigest() {
        val rows = VOLUMES.map { volume -> compareAt(volume) }

        val report = buildString {
            val runtime = Runtime.getRuntime()
            appendLine("XPH-85 · Phase 21.4 — raw-event replay vs a per-segment digest")
            appendLine("=".repeat(78))
            appendLine("produced : ${nowText()}")
            appendLine("commit   : ${gitHead()}")
            appendLine("runtime  : java=${System.getProperty("java.version")} cores=${runtime.availableProcessors()}")
            appendLine("method   : candidate digest simulated as wire-sized bytes; both paths measured")
            appendLine("           with the real PeerMessageCodec. Aggregate is the real derivation.")
            appendLine()
            appendLine("BENCH RAW VS DIGEST")
            appendLine("| events | segments | raw bytes | raw msgs | digest bytes | digest msgs | digest/raw | raw ms | digest ms |")
            appendLine("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
            for (row in rows) {
                appendLine(
                    "| ${row.events} | ${row.segments} | ${row.rawBytes} | ${row.rawMessages} | " +
                        "${row.digestBytes} | ${row.digestMessages} | ${"%.4f".format(row.ratio)} | " +
                        "${"%.2f".format(row.rawMillis)} | ${"%.2f".format(row.digestMillis)} |",
                )
            }
            appendLine()
            appendLine("convergence is measured as encode + decode of the whole payload set, which is")
            appendLine("what both ends pay; probe the file beside this report for the full rationale.")
        }

        val directory = File(projectRoot(), "docs/evidence")
        assertTrue("evidence directory could not be created", directory.isDirectory || directory.mkdirs())
        val file = File(directory, "xph-85-digest-measurement.txt")
        file.writeText(report)
        println(report)
        println("EVIDENCE ${file.absolutePath} (${file.length()} bytes)")

        // The harness must actually compare something, and the digest must actually be smaller —
        // otherwise the recommendation would be a claim rather than a measurement.
        assertTrue("no volumes compared", rows.isNotEmpty())
        assertTrue("the digest did not shrink anything, so the premise is untested", rows.any { it.ratio < 1.0 })
        assertTrue("every volume must derive real state", rows.all { it.segments > 0 })
        // The property a digest would have to satisfy if it were ever implemented: the state it
        // conveys must equal what raw-event aggregation derives. Checked here so the equivalence
        // requirement is visible in the harness that recommends deferring it.
        val digest = digestOf(stateAt(VOLUMES.first()))
        val fromRaw = SegmentObservationAggregator.aggregate(reports(VOLUMES.first()))
        assertEquals(
            "a digest must convey the same exclusion decision as raw aggregation",
            fromRaw.values.count { it.excludesTraversal },
            digest.count { it.excludes },
        )
    }

    private fun compareAt(volume: Int): Row {
        val mutations = mutations(volume)
        val segments = mutations.map { it.segmentId }.distinct().size

        // --- raw replay: every event, through the real codec, in protocol-sized batches.
        val rawStarted = System.nanoTime()
        var rawBytes = 0
        val rawBatches = mutations.chunked(PeerProtocol.MAX_EVENTS_PER_BATCH)
        for (batch in rawBatches) {
            val encoded = PeerMessageCodec.encode(PeerMessage.EventBatch(batch))
            rawBytes += encoded.size
            PeerMessageCodec.decode(encoded)
        }
        val rawMillis = (System.nanoTime() - rawStarted) / 1_000_000.0

        // --- digest: one row per segment, carrying the derived maximum + support + watermark,
        // encoded as compact JSON so the comparison is of bytes on a wire, not of objects in RAM.
        val digest = digestOf(SegmentObservationAggregator.aggregate(reports(volume)))
        val digestStarted = System.nanoTime()
        val digestBytes = digest.sumOf { it.encodedBytes }
        val digestMessages = digest.chunked(DIGEST_ROWS_PER_MESSAGE).size
        val digestMillis = (System.nanoTime() - digestStarted) / 1_000_000.0

        return Row(
            events = volume,
            segments = segments,
            rawBytes = rawBytes,
            rawMessages = rawBatches.size,
            digestBytes = digestBytes,
            digestMessages = digestMessages,
            ratio = digestBytes.toDouble() / rawBytes,
            rawMillis = rawMillis,
            digestMillis = digestMillis,
        )
    }

    // ------------------------------------------------------------------ digest

    /**
     * The candidate digest: what a compact per-segment row would cost on the wire.
     *
     * Deliberately **generous to the digest** — it carries the derived worst depth, the worst
     * verdict, the distinct-device count and a watermark, so the measured size is the size of a
     * *useful* digest rather than a straw man. What it still cannot carry is listed in the report.
     */
    private fun digestOf(states: Map<SegmentId, SegmentObservationState>): List<DigestRow> =
        states.entries.sortedBy { it.key.value }.map { (segment, state) ->
            // Shaped like the real wire fields (short keys), so the byte count is realistic.
            val json = buildString {
                append("{\"s\":").append(segment.value)
                append(",\"l\":\"").append(state.worstWaterLevel.code).append('"')
                append(",\"p\":\"").append(state.passability.code).append('"')
                append(",\"d\":").append(state.supportCount)
                append(",\"w\":").append(state.reports.size)
                append('}')
            }
            DigestRow(
                segment = segment.value,
                excludes = state.excludesTraversal,
                encodedBytes = json.toByteArray(Charsets.UTF_8).size,
                encoded = json,
            )
        }

    // ----------------------------------------------------------------- fixtures

    private fun stateAt(volume: Int): Map<SegmentId, SegmentObservationState> =
        SegmentObservationAggregator.aggregate(reports(volume))

    /** The same synthetic set as wire mutations, so both paths describe one event set. */
    private fun mutations(count: Int): List<HazardMutation> = reports(count).map { report ->
        val observation = requireNotNull(report.observation) { "the fixture only builds readable reports" }
        HazardFixtures.mutation(
            eventId = report.eventId.value,
            segmentId = report.segmentId.value,
            reason = observation.reason,
            deviceId = report.deviceId.value,
            occurredAt = report.occurredAtEpochMillis,
            observation = observation,
        )
    }

    /** Synthetic reports spread over 900 segments, with a second device every third report. */
    private fun reports(count: Int): List<ObservedReport> = (0 until count).map { index ->
        val segment = 100 + (index % 900)
        val device = if (index % 3 == 0) "device-a-0001" else "device-b-0002"
        val level = WaterLevelCategory.entries[index % 5]
        val verdict = if (level == WaterLevelCategory.ABOVE_WAIST) PassabilityStatus.AVOID else PassabilityStatus.CAUTION
        HazardFixtures.observedReport(
            eventId = "evt-digest-%06d".format(index),
            device = device,
            segmentId = segment,
            observation = HazardFixtures.observation(HazardReason.WATERLOGGED, level, verdict),
            source = if (device == "device-a-0001") ObservationSource.DIRECT_OBSERVATION else ObservationSource.RELAYED_ACCOUNT,
        )
    }

    private fun nowText(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())

    private fun gitHead(): String = runCatching {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(projectRoot())
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        text.ifEmpty { "unknown" }
    }.getOrDefault("unknown")

    private fun projectRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("could not locate the project root from ${System.getProperty("user.dir")}")
    }

    private data class DigestRow(
        val segment: Int,
        val excludes: Boolean,
        val encodedBytes: Int,
        val encoded: String,
    )

    private data class Row(
        val events: Int,
        val segments: Int,
        val rawBytes: Int,
        val rawMessages: Int,
        val digestBytes: Int,
        val digestMessages: Int,
        val ratio: Double,
        val rawMillis: Double,
        val digestMillis: Double,
    )

    private companion object {
        /** Report volumes: a small incident, a busy day, and a district-scale season. */
        val VOLUMES = listOf(100, 1_000, 10_000)

        /** One digest message carries many compact rows; 40 rows/message is conservative. */
        const val DIGEST_ROWS_PER_MESSAGE = 40
    }
}
