package com.resqroute.benchmark

import android.app.Application
import android.content.Context
import com.resqroute.core.graph.GraphBlocker
import com.resqroute.core.graph.RoutingGraph
import com.resqroute.core.model.DatasetIdentity
import com.resqroute.core.model.NodeId
import com.resqroute.core.model.SegmentId
import com.resqroute.core.routing.AStarRoutingEngine
import com.resqroute.core.routing.RouteResult
import com.resqroute.core.routing.RoutePath
import com.resqroute.core.routing.RouteRequest
import com.resqroute.core.routing.RoutingPolicy
import com.resqroute.core.routing.RoutingSnapshot
import com.resqroute.data.dataset.BundledDatasetReader
import com.resqroute.data.dataset.PackedDataset
import com.resqroute.data.local.ResQRouteDatabase
import com.resqroute.dataset.TestDatasets
import com.resqroute.data.p2p.HazardSyncCoordinator
import com.resqroute.data.p2p.PeerMessageCodec
import com.resqroute.data.p2p.TransportEvent
import com.resqroute.domain.hazard.DeviceId
import com.resqroute.domain.hazard.HazardContext
import com.resqroute.domain.hazard.HazardMutation
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.MutationResult
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.SegmentObservationAggregator
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.domain.risk.RiskPenaltyPolicy
import com.resqroute.domain.sync.PeerMessage
import com.resqroute.domain.sync.PeerProtocol
import com.resqroute.domain.sync.PeerSessionId
import com.resqroute.p2p.FakeP2PTransport
import com.resqroute.p2p.RecordingHazardRepository
import com.resqroute.hazard.HazardFixtures
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The scale and latency baseline (XPH-80).
 *
 * There is no measured baseline for propagation, latency, duplicate handling or storage growth, so
 * improvements cannot be demonstrated and any target would be invented. This harness measures what
 * can be measured **on the JVM against the real bundled dataset and real Room**, records every number
 * without attaching a target to it, and writes a dated report to `docs/evidence/`.
 *
 * ## The instrument for each metric, stated because it matters
 *
 * | Metric | Instrument | Unit |
 * | --- | --- | --- |
 * | Message payload size | `PeerMessageCodec.encode` bytes | bytes, bytes/event |
 * | Message count to move N events | batches of [PeerProtocol.MAX_EVENTS_PER_BATCH] | messages |
 * | Report delivery success | coordinator + fake transport: forwarded ÷ submitted | ratio |
 * | Propagation (processing) latency | wall clock, submit → batch emitted, and ACK → settled | ms/event |
 * | Duplicate processing | wall clock for a repeat delivery, plus the result | ms, count |
 * | Reconnection convergence | full-log replay cost at volume: messages + bytes + ms | messages, ms |
 * | Route computation latency | A* on the real Feni graph, median of [ROUTE_RUNS] | ms |
 * | Penalty construction latency | `RiskPenaltyPolicy.forStates` | ms |
 * | Database growth | real Room: fixed overhead + marginal bytes/event | bytes, bytes/event |
 *
 * ## What this deliberately does not measure, and will not estimate
 *
 * * **Radio / end-to-end propagation latency.** There is no radio here. The numbers below are the
 *   *processing* component on a fake transport; the air-interface latency needs two devices
 *   (XPH-81) and is recorded as **unmeasured** rather than guessed.
 * * **Battery and thermal cost.** Not observable without hardware.
 * * **On-device cold start.** `ColdLoadMeasurementTest` already records the JVM parse/validate/
 *   publish breakdown; asset IO and SHA-256 are measured by the app's own Phase 3 panel on a phone.
 *
 * ## Running it
 *
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "com.resqroute.benchmark.PerformanceBaselineTest" --console=plain
 * ```
 *
 * Every line the report contains is prefixed `BENCH` so the run's output can be read or captured
 * without scraping prose, and the same content is written to
 * `docs/evidence/xph-80-baseline-run.txt` by the run itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PerformanceBaselineTest {

    private lateinit var context: Context

    private val engine = AStarRoutingEngine()

    /** The real artifact, not a fixture: the baseline describes what the app actually ships. */
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

    private fun context(): Context = HazardFixtures.context().also { context = it }

    // ------------------------------------------------------------------ the run

    @Test
    fun baselineRun() {
        context = context()
        val report = StringBuilder()
        report.header()

        val payload = measurePayloadSizes()
        val replay = measureReplayCostAtVolume()
        val sync = measurePeerSync()
        val routes = measureRouteLatency()
        val storage = measureDatabaseGrowth()

        report.record(payload, replay, sync, routes, storage)
        val file = report.write()
        println("BENCH REPORT WRITTEN: ${file.absolutePath} (${file.length()} bytes)")

        // The smoke test the issue asks for: the harness ran end to end on the bundled dataset,
        // without hardware, and produced usable numbers.
        assertTrue("no payload sizes measured", payload.points.isNotEmpty())
        assertTrue("the duplicate pass committed something", sync.secondPassCommitted == sync.firstPassCommitted)
        assertTrue("duplicates were not exercised", sync.duplicatesHandled > 0)
        assertTrue("no route latencies measured", routes.isNotEmpty())
        assertTrue("no storage points measured", storage.size >= 3)
        assertTrue("the report was not written", file.length() > 0L)
    }

    // ------------------------------------------------- payload size and count

    /** Pure and deterministic: encode a batch of N real events and count the bytes. */
    private fun measurePayloadSizes(): PayloadMeasurement {
        val points = listOf(1, 4, 8, 16, 24, 32).map { size ->
            val bytes = PeerMessageCodec.encode(PeerMessage.EventBatch(events(size, 0))).size
            PayloadPoint(events = size, bytes = bytes, bytesPerEvent = bytes.toDouble() / size)
        }
        // The two protocol ceilings are independent, and at this event size the byte ceiling binds
        // first: the largest batch that a receiver would actually accept is measured rather than
        // assumed. (The sender halves an oversized chunk, so this bounds the batch, not the sync.)
        val maxFitting = (1..PeerProtocol.MAX_EVENTS_PER_BATCH).last { size ->
            PeerMessageCodec.encode(PeerMessage.EventBatch(events(size, 0))).size <= PeerProtocol.MAX_PAYLOAD_BYTES
        }
        return PayloadMeasurement(points = points, maxFittingBatch = maxFitting)
    }

    /**
     * The largest-leading chunks whose encoded batch fits the payload limit.
     *
     * Mirrors `HazardSyncCoordinator.sendChunk`'s rule, which is the sender's contract: respect the
     * event ceiling **and** the byte ceiling. A benchmark that respected only the event ceiling would
     * hand the receiver an oversized payload, which is refused outright — and would measure a
     * rejection, not the duplicate path.
     */
    private fun fittingChunks(mutations: List<HazardMutation>): List<List<HazardMutation>> {
        val chunks = mutableListOf<List<HazardMutation>>()
        var index = 0
        while (index < mutations.size) {
            val limit = minOf(PeerProtocol.MAX_EVENTS_PER_BATCH, mutations.size - index)
            var size = 1
            while (size < limit) {
                val candidate = mutations.subList(index, index + size + 1)
                if (PeerMessageCodec.encode(PeerMessage.EventBatch(candidate)).size > PeerProtocol.MAX_PAYLOAD_BYTES) break
                size++
            }
            chunks += mutations.subList(index, index + size)
            index += size
        }
        return chunks
    }

    /** How many messages and bytes it takes to move N events through the wire format. */
    private fun measureReplayCostAtVolume(): List<ReplayPoint> = VOLUMES.map { volume ->
        val all = events(volume, 1000)
        val batches = all.chunked(PeerProtocol.MAX_EVENTS_PER_BATCH)
        val started = System.nanoTime()
        var bytes = 0
        for (batch in batches) {
            val encoded = PeerMessageCodec.encode(PeerMessage.EventBatch(batch))
            bytes += encoded.size
            // Decode as well: replay cost is encode + decode, and the receiver pays the decode.
            PeerMessageCodec.decode(encoded)
        }
        val millis = (System.nanoTime() - started) / 1_000_000.0
        ReplayPoint(
            events = volume,
            messages = batches.size,
            bytes = bytes,
            bytesPerEvent = bytes.toDouble() / volume,
            roundTripMillis = millis,
        )
    }

    // --------------------------------------------------------------- peer sync

    /**
     * Delivery, latency and duplicates through the real coordinator and codec.
     *
     * The transport is the in-memory double, so these are **processing** costs. A batch that never
     * arrives, a relayed echo and a re-sent retry all reach the same ingress, which is what makes
     * the duplicate number meaningful.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun measurePeerSync(): SyncMeasurement {
        lateinit var measured: SyncMeasurement
        runTest {
        val hash = "a".repeat(64)
        val hazardContext = HazardContext(DatasetIdentity(hash, PeerProtocol.VERSION), "incident-bench")
        val transport = FakeP2PTransport()
        val repository = RecordingHazardRepository(hazardContext).apply { markReady() }
        val session = PeerSessionId("endpoint-B")
        val coordinator = HazardSyncCoordinator(
            transport = transport,
            repository = repository,
            scope = backgroundScope,
            contextProvider = { hazardContext },
            deviceIdProvider = { DeviceId("device-bench-0001") },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            retryIntervalMillis = 1_000,
            retryTimeoutMillis = 500,
            maxSendAttempts = 3,
            clock = { testScheduler.currentTime },
        )
        coordinator.start()
        runCurrent()
        transport.deliver(TransportEvent.Connected(session, "phone-B"))
        runCurrent()
        transport.deliverMessage(
            session,
            PeerMessage.Hello(PeerProtocol.VERSION, DeviceId("peer-00000001"), hash, "incident-bench"),
        )
        runCurrent()
        transport.clearSent()

        // --- delivery success and processing latency (a warm-up round first, so the number is not
        // mostly JIT compilation of the codec)
        repeat(8) { coordinator.submitLocal(events(SYNC_EVENTS, 5_000)[it].copy(context = hazardContext)) }
        runCurrent()
        repeat(8) { coordinator.submitLocal(events(SYNC_EVENTS, 5_000)[it].copy(context = hazardContext)) }
        runCurrent()
        transport.clearSent()

        val committed = events(SYNC_EVENTS, 10_000).map { it.copy(context = hazardContext) }
        val started = System.nanoTime()
        committed.forEach { coordinator.submitLocal(it) }
        runCurrent()
        val emitMillis = (System.nanoTime() - started) / 1_000_000.0
        val forwarded = transport.batchEventIds(session).distinct()
        val deliverySuccess = forwarded.size.toDouble() / committed.size

        // --- ACK settling: deliver every ACK and count a local retry-free settle
        val ackStarted = System.nanoTime()
        committed.forEach { transport.deliverMessage(session, PeerMessage.Ack(it.eventId)) }
        runCurrent()
        val ackMillis = (System.nanoTime() - ackStarted) / 1_000_000.0

        // --- duplicate processing: deliver the whole set in payload-fitting chunks, then repeat.
        // Chunking mirrors the coordinator's own sendChunk rule (halve until it fits), because a
        // batch that respects only MAX_EVENTS_PER_BATCH can exceed MAX_PAYLOAD_BYTES — measured in
        // the payload table above — and the receiver refuses an oversized payload outright.
        val incoming = events(SYNC_EVENTS, 20_000).map { it.copy(context = hazardContext) }
        transport.clearSent()
        for (chunk in fittingChunks(incoming)) {
            transport.deliverMessage(session, PeerMessage.EventBatch(chunk))
        }
        runCurrent()
        val firstPass = repository.committed.size
        val duplicateStarted = System.nanoTime()
        for (chunk in fittingChunks(incoming)) {
            transport.deliverMessage(session, PeerMessage.EventBatch(chunk))
        }
        runCurrent()
        val duplicateMillis = (System.nanoTime() - duplicateStarted) / 1_000_000.0
        val secondPass = repository.committed.size
        val duplicatesHandled = repository.ingested.size - repository.committed.size

        // --- reconnection convergence: the full log is replayed on a fresh session
        val persisted = events(REPLAY_EVENTS, 30_000).map { it.copy(context = hazardContext) }
        repository.persisted = persisted
        val reconnectSession = PeerSessionId("endpoint-C")
        transport.clearSent()
        val reconnectStarted = System.nanoTime()
        transport.deliver(TransportEvent.Connected(reconnectSession, "phone-C"))
        runCurrent()
        transport.deliverMessage(
            reconnectSession,
            PeerMessage.Hello(PeerProtocol.VERSION, DeviceId("peer-00000002"), hash, "incident-bench"),
        )
        runCurrent()
        val reconnectMillis = (System.nanoTime() - reconnectStarted) / 1_000_000.0
        val replayed = transport.batchEventIds(reconnectSession).distinct()
        val reconnectMessages = transport.messagesTo(reconnectSession).count { it is PeerMessage.EventBatch }

        coordinator.stop()

        measured = SyncMeasurement(
            submitted = committed.size,
            forwarded = forwarded.size,
            deliverySuccess = deliverySuccess,
            emitMillis = emitMillis,
            ackMillis = ackMillis,
            submittedPerSecond = committed.size / (emitMillis / 1000.0).coerceAtLeast(1e-9),
            firstPassCommitted = firstPass,
            secondPassCommitted = secondPass,
            duplicatesHandled = duplicatesHandled,
            duplicateMillis = duplicateMillis,
            replayEvents = persisted.size,
            replayedEvents = replayed.size,
            replayMessages = reconnectMessages,
            reconnectMillis = reconnectMillis,
        )
        }

        // The type parameter is inferred from this property; keeping it explicit stops a future
        // refactor of the block above from silently returning Unit.
        return measured
    }

    // ----------------------------------------------------------- route latency

    /** A* on the real graph, with and without penalties, plus the penalty builder itself. */
    private fun measureRouteLatency(): List<RoutePoint> = runBlocking {
        val graph = loaded.graph
        val (start, destination) = routablePair(graph)
        val scenarios = listOf(
            "distance-only" to emptyMap<SegmentId, SegmentObservationState>(),
            "scattered-reports" to states(
                (0 until graph.segmentCount step 997).map { SegmentId(it) },
                WaterLevelCategory.ANKLE,
                PassabilityStatus.CAUTION,
                1,
            ),
        )

        val points = mutableListOf<RoutePoint>()
        for ((name, states) in scenarios) {
            val policy = if (states.isEmpty()) RoutingPolicy.DISTANCE_ONLY else RiskPenaltyPolicy.forStates(states, RiskPenaltyPolicy.LAMBDA, graph::segmentLengthMeters)
            val penaltyMillis = timeMillis(PENALTY_RUNS) {
                if (states.isEmpty()) RoutingPolicy.DISTANCE_ONLY else RiskPenaltyPolicy.forStates(states, RiskPenaltyPolicy.LAMBDA, graph::segmentLengthMeters)
            }
            val samples = measure(ROUTE_RUNS) { routeOnce(graph, start, destination, policy) }
            points += RoutePoint(
                name = name,
                reportedSegments = states.size,
                penaltyMillis = penaltyMillis,
                medianMillis = samples.median(),
                minMillis = samples.min(),
                maxMillis = samples.max(),
                distanceMeters = routeOnce(graph, start, destination, policy).distanceMeters,
            )
        }

        // The corridor cases from XPH-84 are the latency case that matters: they force a genuinely
        // different route, so the search expands a different node set.
        val shortest = routeOnce(graph, start, destination, RoutingPolicy.DISTANCE_ONLY)
        for ((name, level, verdict, devices) in corridorScenarios()) {
            val states = states(shortest.segmentIds.distinct(), level, verdict, devices)
            val policy = RiskPenaltyPolicy.forStates(states, RiskPenaltyPolicy.LAMBDA, graph::segmentLengthMeters)
            val samples = measure(ROUTE_RUNS) { routeOnce(graph, start, destination, policy) }
            points += RoutePoint(
                name = name,
                reportedSegments = states.size,
                penaltyMillis = timeMillis(PENALTY_RUNS) {
                    RiskPenaltyPolicy.forStates(states, RiskPenaltyPolicy.LAMBDA, graph::segmentLengthMeters)
                },
                medianMillis = samples.median(),
                minMillis = samples.min(),
                maxMillis = samples.max(),
                distanceMeters = routeOnce(graph, start, destination, policy).distanceMeters,
            )
        }
        points
    }

    private fun corridorScenarios(): List<Corridor> = listOf(
        Corridor("corridor-knee-caution", WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, 2),
        Corridor("corridor-above-waist-avoid", WaterLevelCategory.ABOVE_WAIST, PassabilityStatus.AVOID, 3),
    )

    // -------------------------------------------------------- database growth

    /**
     * Real Room growth: the fixed cost of a database and the marginal cost of an event.
     *
     * Measured as on-disk bytes after a WAL checkpoint, so the number is what a device's storage
     * would actually hold rather than a page-count estimate.
     */
    private fun measureDatabaseGrowth(): List<StoragePoint> = runBlocking {
        val name = "resqroute-bench-growth.db"
        context.deleteDatabase(name)
        val database = HazardFixtures.openDatabase(context, name)
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val repository = HazardFixtures.repository(database, scope, eventIds = AtomicInteger(0))
        repository.initialize(HazardFixtures.contextAlpha)
        HazardFixtures.awaitReady(repository)

        val points = mutableListOf<StoragePoint>()
        // The empty database first: the fixed overhead every device pays.
        points += storagePoint(name, rows = 0)
        var nextIndex = 0
        for (volume in STORAGE_STEPS) {
            while (nextIndex < volume) {
                val result = repository.ingestRemote(
                    HazardFixtures.mutation(
                        eventId = "evt-bench-%06d".format(nextIndex),
                        segmentId = 100 + (nextIndex % 500),
                    ),
                )
                check(result is MutationResult.Accepted) { "ingest failed: $result" }
                nextIndex++
            }
            checkpoint(database)
            points += storagePoint(name, rows = volume)
        }

        runCatching { scope.cancel() }
        runCatching { database.close() }
        context.deleteDatabase(name)
        points
    }

    /** Force the WAL into the main file so the measured bytes are the bytes a device would hold. */
    private fun checkpoint(database: ResQRouteDatabase) {
        // `wal_checkpoint` returns a result row, so it has to go through rawQuery rather than
        // execSQL. Guarded: a database that is not in WAL mode has nothing to checkpoint, and the
        // size measurement below sums the sidecar files either way.
        runCatching {
            // androidx.sqlite's SupportSQLiteDatabase exposes `query`, not `rawQuery`.
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
                cursor.moveToFirst()
            }
        }
    }

    /**
     * On-disk bytes of a database, split into the main file and its WAL sidecars.
     *
     * Split rather than summed because the two behave differently: the main file is what a device
     * keeps, while WAL growth is transient and depends on checkpointing. Reporting one number would
     * hide which of the two a change moved.
     */
    private fun storagePoint(name: String, rows: Int): StoragePoint {
        val base = context.getDatabasePath(name)
        val main = base.length()
        val sidecar = listOf("-wal", "-shm").sumOf { File(base.path + it).length() }
        return StoragePoint(
            rows = rows,
            mainBytes = main,
            sidecarBytes = sidecar,
            bytes = main + sidecar,
            bytesPerEvent = if (rows == 0) 0.0 else (main + sidecar).toDouble() / rows,
        )
    }

    // ------------------------------------------------------------------ report

    private fun StringBuilder.header() {
        val runtime = Runtime.getRuntime()
        appendLine("XPH-80 · Phase 21.1 — scale, latency and storage baseline")
        appendLine("=".repeat(78))
        appendLine("produced : ${nowText()}")
        appendLine("commit   : ${gitHead()}")
        appendLine("dataset  : hash=${loaded.identity.contentHash.take(16)}… format=${loaded.identity.formatVersion} nodes=${loaded.graph.nodeCount} edges=${loaded.graph.edgeCount} segments=${loaded.graph.segmentCount}")
        appendLine("runtime  : java=${System.getProperty("java.version")} os=${System.getProperty("os.name")} cores=${runtime.availableProcessors()}")
        appendLine("method   : JVM unit tests, no hardware, no radio. Payload/batch numbers are exact;")
        appendLine("           latency numbers are wall clock on this machine and are NOT targets.")
        appendLine("unmeasured: radio/air-interface latency, battery, thermal (need two devices — XPH-81)")
        appendLine()
    }

    private fun StringBuilder.record(
        payload: PayloadMeasurement,
        replay: List<ReplayPoint>,
        sync: SyncMeasurement,
        routes: List<RoutePoint>,
        storage: List<StoragePoint>,
    ) {
        appendLine("BENCH PAYLOAD SIZE (PeerMessageCodec, one EventBatch)")
        appendLine("| events | bytes | bytes/event |")
        appendLine("| ---: | ---: | ---: |")
        for (p in payload.points) appendLine("| ${p.events} | ${p.bytes} | ${"%.1f".format(p.bytesPerEvent)} |")
        appendLine("event ceiling ${PeerProtocol.MAX_EVENTS_PER_BATCH} · payload ceiling ${PeerProtocol.MAX_PAYLOAD_BYTES} bytes")
        appendLine(
            "largest batch a receiver accepts at this event size = ${payload.maxFittingBatch} events " +
                "(the byte ceiling binds first; the sender halves an oversized chunk — see peer sync)",
        )
        appendLine()

        appendLine("BENCH WIRE COST AT VOLUME (encode + decode, batched at the protocol limit)")
        appendLine("| events | messages | bytes | bytes/event | round-trip ms |")
        appendLine("| ---: | ---: | ---: | ---: | ---: |")
        for (r in replay) {
            appendLine("| ${r.events} | ${r.messages} | ${r.bytes} | ${"%.1f".format(r.bytesPerEvent)} | ${"%.2f".format(r.roundTripMillis)} |")
        }
        appendLine()

        appendLine("BENCH PEER SYNC (real coordinator + codec, in-memory transport)")
        appendLine("| metric | value |")
        appendLine("| --- | ---: |")
        appendLine("| submitted | ${sync.submitted} |")
        appendLine("| forwarded | ${sync.forwarded} |")
        appendLine("| delivery success | ${"%.4f".format(sync.deliverySuccess)} |")
        appendLine("| submit → emitted | ${"%.2f".format(sync.emitMillis)} ms (${"%.0f".format(sync.submittedPerSecond)} events/s) |")
        appendLine("| ACK settle | ${"%.2f".format(sync.ackMillis)} ms |")
        appendLine("| duplicates: committed 1st / 2nd pass | ${sync.firstPassCommitted} / ${sync.secondPassCommitted} |")
        appendLine("| duplicates handled (not committed) | ${sync.duplicatesHandled} |")
        appendLine("| duplicate pass | ${"%.2f".format(sync.duplicateMillis)} ms |")
        appendLine("| reconnect replay events / messages | ${sync.replayedEvents} / ${sync.replayMessages} |")
        appendLine("| reconnect replay of ${sync.replayEvents} events | ${"%.2f".format(sync.reconnectMillis)} ms |")
        appendLine()

        appendLine("BENCH ROUTE LATENCY (real Feni graph, median of $ROUTE_RUNS runs, $ROUTE_WARMUP warm-ups)")
        appendLine("| case | reported segments | penalty build ms | route median ms | min | max | distance m |")
        appendLine("| --- | ---: | ---: | ---: | ---: | ---: | ---: |")
        for (r in routes) {
            appendLine(
                "| ${r.name} | ${r.reportedSegments} | ${"%.3f".format(r.penaltyMillis)} | " +
                    "${"%.3f".format(r.medianMillis)} | ${"%.3f".format(r.minMillis)} | " +
                    "${"%.3f".format(r.maxMillis)} | ${"%.1f".format(r.distanceMeters)} |",
            )
        }
        appendLine()

        appendLine("BENCH DATABASE GROWTH (real Room, on-disk bytes after a WAL checkpoint)")
        appendLine("| events | main bytes | wal+shm bytes | total bytes | bytes/event | marginal bytes/event |")
        appendLine("| ---: | ---: | ---: | ---: | ---: | ---: |")
        var previous: StoragePoint? = null
        for (point in storage) {
            val marginal = previous?.let {
                if (point.rows == it.rows) null
                else (point.bytes - it.bytes).toDouble() / (point.rows - it.rows)
            }
            appendLine(
                "| ${point.rows} | ${point.mainBytes} | ${point.sidecarBytes} | ${point.bytes} | " +
                    "${"%.1f".format(point.bytesPerEvent)} | ${marginal?.let { "%.1f".format(it) } ?: "—"} |",
            )
            previous = point
        }
        appendLine("fixed overhead (empty database, schema only) = ${storage.first().bytes} bytes")
        appendLine(
            "Headline for capacity planning: the **marginal** column at volume, not bytes/event at " +
                "small counts — SQLite allocates whole pages, so the first rows carry page overhead.",
        )
        appendLine(
            "Caveat: the wal+shm column is journal overhead and is **environment-specific** (this " +
                "run's SQLite under Robolectric); the main-file deltas are the number to plan from. " +
                "WAL size also depends on checkpoint timing, which is why the two are reported apart.",
        )
        appendLine()
        appendLine("No targets are attached to any number above, by design: this is a baseline to")
        appendLine("measure against, not a budget to satisfy.")
    }

    private fun StringBuilder.write(): File {
        val root = projectRoot()
        val directory = File(root, "docs/evidence")
        assertTrue("evidence directory could not be created", directory.isDirectory || directory.mkdirs())
        val file = File(directory, "xph-80-baseline-run.txt")
        file.writeText(toString())
        println(this)
        return file
    }

    // ----------------------------------------------------------------- fixtures

    private fun events(count: Int, seed: Int): List<HazardMutation> = (0 until count).map { index ->
        HazardFixtures.mutation(
            eventId = "evt-bench-%08d".format(seed + index),
            segmentId = 100 + ((seed + index) % 900),
        )
    }

    private fun states(
        segments: List<SegmentId>,
        level: WaterLevelCategory,
        verdict: PassabilityStatus,
        devices: Int,
    ): Map<SegmentId, SegmentObservationState> = segments.distinct().associateWith { segment ->
        SegmentObservationAggregator.aggregate(
            (1..devices).map { index ->
                HazardFixtures.observedReport(
                    eventId = "evt-bench-%s-%03d".format(segment.value, index),
                    device = "device-%03d".format(index),
                    segmentId = segment.value,
                    observation = HazardFixtures.observation(HazardReason.WATERLOGGED, level, verdict),
                )
            },
        ).getValue(segment)
    }

    private suspend fun routeOnce(
        graph: RoutingGraph,
        start: NodeId,
        destination: NodeId,
        policy: RoutingPolicy,
    ): RoutePath {
        val result = engine.findRoute(
            RoutingSnapshot(graph.identity, "xph-80-bench", 1, graph, GraphBlocker.NONE),
            RouteRequest(1, start, destination, policy),
        )
        return (result as? RouteResult.Found)?.path ?: error("expected a route")
    }

    /** The first shelter pair (bounded search) that yields a non-trivial route. */
    private suspend fun routablePair(graph: RoutingGraph): Pair<NodeId, NodeId> {
        val shelters = minOf(graph.shelterCount, 12)
        for (i in 0 until shelters) {
            for (j in i + 1 until shelters) {
                val start = graph.shelter(i).accessNodeId
                val destination = graph.shelter(j).accessNodeId
                val path = runCatching { routeOnce(graph, start, destination, RoutingPolicy.DISTANCE_ONLY) }.getOrNull()
                if (path != null && path.distanceMeters > 1_000.0) return start to destination
            }
        }
        error("no routable shelter pair found")
    }

    private suspend fun measure(runs: Int, block: suspend () -> Unit): List<Double> {
        for (warmUp in 0 until ROUTE_WARMUP) block()
        return (1..runs).map { timeMillis(1, block) }
    }

    private suspend fun timeMillis(runs: Int, block: suspend () -> Unit): Double {
        val started = System.nanoTime()
        for (run in 1..runs) block()
        return (System.nanoTime() - started) / 1_000_000.0 / runs
    }

    private fun List<Double>.median(): Double = sorted()[size / 2]

    private fun projectRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("could not locate the project root from ${System.getProperty("user.dir")}")
    }

    private fun gitHead(): String = runCatching {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(projectRoot())
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        text.ifEmpty { "unknown" }
    }.getOrDefault("unknown")

    private fun nowText(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())

    // ------------------------------------------------------------------ records

    private data class PayloadPoint(val events: Int, val bytes: Int, val bytesPerEvent: Double)
    private data class PayloadMeasurement(val points: List<PayloadPoint>, val maxFittingBatch: Int)
    private data class ReplayPoint(val events: Int, val messages: Int, val bytes: Int, val bytesPerEvent: Double, val roundTripMillis: Double)
    private data class StoragePoint(
        val rows: Int,
        val mainBytes: Long,
        val sidecarBytes: Long,
        val bytes: Long,
        val bytesPerEvent: Double,
    )
    private data class RoutePoint(
        val name: String,
        val reportedSegments: Int,
        val penaltyMillis: Double,
        val medianMillis: Double,
        val minMillis: Double,
        val maxMillis: Double,
        val distanceMeters: Double,
    )

    private data class Corridor(
        val name: String,
        val level: WaterLevelCategory,
        val verdict: PassabilityStatus,
        val devices: Int,
    )

    private data class SyncMeasurement(
        val submitted: Int,
        val forwarded: Int,
        val deliverySuccess: Double,
        val emitMillis: Double,
        val ackMillis: Double,
        val submittedPerSecond: Double,
        val firstPassCommitted: Int,
        val secondPassCommitted: Int,
        val duplicatesHandled: Int,
        val duplicateMillis: Double,
        val replayEvents: Int,
        val replayedEvents: Int,
        val replayMessages: Int,
        val reconnectMillis: Double,
    )

    private companion object {
        /** The volumes the wire cost and the database growth are measured at. */
        val VOLUMES = listOf(32, 256, 1024, 4096)
        val STORAGE_STEPS = listOf(64, 256, 1024, 4096)

        /** Peer-sync fixture sizes: a realistic incident, not a stress test. */
        const val SYNC_EVENTS = 64
        const val REPLAY_EVENTS = 512

        /** Routing: warm-up then median, so a JIT hiccup cannot become the headline. */
        const val ROUTE_WARMUP = 3
        const val ROUTE_RUNS = 15
        const val PENALTY_RUNS = 200
    }
}
