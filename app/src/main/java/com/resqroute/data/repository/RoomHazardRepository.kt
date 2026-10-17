package com.resqroute.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.resqroute.core.model.SegmentId
import com.resqroute.data.local.DeviceIdentityEntity
import com.resqroute.data.local.HazardEventEntity
import com.resqroute.data.local.ResQRouteDatabase
import com.resqroute.domain.hazard.DeviceId
import com.resqroute.domain.hazard.EventId
import com.resqroute.domain.hazard.HazardContext
import com.resqroute.domain.hazard.HazardMutation
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.HazardRepository
import com.resqroute.domain.hazard.HazardSnapshot
import com.resqroute.domain.hazard.HazardState
import com.resqroute.domain.hazard.MutationResult
import com.resqroute.domain.hazard.ObservationValidation
import com.resqroute.domain.hazard.ObservedReport
import com.resqroute.domain.hazard.ObservationSource
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.ReportContent
import com.resqroute.domain.hazard.RoadObservation
import com.resqroute.domain.hazard.SegmentObservationAggregator
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Room-backed implementation of the single hazard mutation ingress.
 *
 * Invariants enforced here (Phase 6 acceptance criteria):
 *  * validation happens before anything is written (dataset, incident, ids,
 *    segment range);
 *  * mutations are serialized by a mutex and committed in one transaction;
 *  * an identical duplicate is a no-op; the same event id with different
 *    content is rejected, never overwritten;
 *  * a new event for an already blocked segment is persisted with its
 *    provenance but does not change the effective set or the revision;
 *  * the effective blocked set is derived with a DISTINCT query, never stored;
 *  * [state] is published only from committed rows, so routing can never run on
 *    a fabricated empty set while restoration is still in progress;
 *  * a failed write becomes [MutationResult.PersistenceFailure] — the caller
 *    must not ACK it.
 *
 * Observation axes (v2, ADR 0001):
 *  * [HazardEventEntity.levelCode] and `passabilityCode` are persisted **only when
 *    the report states more than its reason already implies**, so a plain block keeps
 *    null axes and resolves back to exactly the v1 meaning;
 *  * resolving a stored row can never make a restriction weaker than v1: an
 *    unrecognised passability code fails closed, and a combination that is not
 *    representable falls back to [RoadObservation.fromLegacy].
 *
 * The derived state (Phase 14.2, XPH-69):
 *  * the effective blocked set and the per-segment [observationStates] are derived from
 *    the committed rows by one pure function, so the two can never disagree;
 *  * [observationStates] is the derived view that the risk policy, the freshness labels
 *    and the UI read; [state]'s snapshot stays the narrow routing contract.
 *
 * **Derivation rule, in one paragraph.** Every committed row for the context is resolved
 * through [observationFor], turned into an [ObservedReport], and grouped by segment by
 * [SegmentObservationAggregator]. A segment is excluded when *any* of its reports excludes
 * traversal — a resolved `IMPASSABLE` observation, or a row whose reason code this build
 * cannot read, which fails closed exactly as it did in v1 (ADR 0004 §2). A report that
 * does not exclude is stored, replayed and displayed but removes nothing from the search.
 * Nothing on this path reads the clock, and every reduction is order-free, so the same row
 * set always derives the same state — which is what makes replay, reordering and a
 * relaunch idempotent.
 *
 * Sequencing constraint (important): until the risk penalties land (Phase 15), a report
 * that does **not** exclude carries no cost, so the router treats its segment as though
 * nothing had been reported. Harmless today, because no production path can create such a
 * row — [blockLocal] records a block, and the wire carries no axes until XPH-78 — but
 * **XPH-70 and XPH-78 must not land before the penalty model**. Until then a non-excluding
 * report is visible in [observationStates] and changes no route.
 */
class RoomHazardRepository(
    private val database: ResQRouteDatabase,
    private val segmentCountProvider: () -> Int?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val eventIdGenerator: () -> String = { UUID.randomUUID().toString() },
    private val deviceIdGenerator: () -> String = { UUID.randomUUID().toString() },
) : HazardRepository {

    private val mutationMutex = Mutex()

    private val _state = MutableStateFlow<HazardState>(HazardState.Restoring)
    override val state: StateFlow<HazardState> = _state.asStateFlow()

    /**
     * The derived per-segment view of committed reports (Phase 14.2, XPH-69).
     *
     * A read-only projection of [state]'s [HazardState.Ready.observationStates], so there is
     * exactly one source of truth: the value cannot differ from the one published atomically
     * with the blocked set. Empty until [initialize] has restored the log.
     *
     * This is deliberately on the implementation rather than on [HazardRepository], which is the
     * mutation-ingress contract; the same pattern as [deviceId]. Note that it can change
     * **without** [HazardSnapshot.revision] moving: a report that does not exclude changes what
     * is displayed and what a route costs, never which segments a router must avoid.
     */
    val observationStates: StateFlow<Map<SegmentId, SegmentObservationState>> = _state
        .map { ready -> (ready as? HazardState.Ready)?.observationStates ?: emptyMap() }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    @Volatile
    private var activeContext: HazardContext? = null

    /** Pinned installation identity; persisted on first initialization. */
    @Volatile
    var deviceId: DeviceId? = null
        private set

    private var observationJob: Job? = null

    override suspend fun initialize(context: HazardContext) {
        mutationMutex.withLock {
            val current = _state.value
            if (current is HazardState.Ready && activeContext == context) return

            _state.value = HazardState.Restoring
            try {
                deviceId = ensureDeviceIdentity()
                val restored = derive(
                    database.hazardDao().events(context.dataset.contentHash, context.incidentId),
                )
                activeContext = context
                publish(context, restored)

                observationJob?.cancel()
                observationJob = scope.launch {
                    database.hazardDao()
                        .observeEvents(context.dataset.contentHash, context.incidentId)
                        .collect { rows -> publish(context, derive(rows)) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                val message = error.message?.takeIf { it.isNotBlank() } ?: "hazard restore failed"
                _state.value = HazardState.Unavailable(message)
                Log.e(TAG, "hazard restore failed: $message", error)
            }
        }
    }

    override suspend fun blockLocal(segmentId: SegmentId, reason: HazardReason): MutationResult {
        val context = activeContext
            ?: return MutationResult.PersistenceFailure("hazard repository is not initialized")
        val identity = deviceId
            ?: return MutationResult.PersistenceFailure("device identity unavailable")

        // Identity and provenance are generated here, never by the UI.
        val mutation = HazardMutation(
            eventId = EventId(eventIdGenerator()),
            originDeviceId = identity,
            context = context,
            segmentId = segmentId,
            reason = reason,
            occurredAtEpochMillis = clock(),
        )
        // Local and remote reports share exactly one commit path.
        return ingestRemote(mutation)
    }

    override suspend fun recordLocal(
        segmentId: SegmentId,
        reason: HazardReason,
        waterLevel: WaterLevelCategory,
        passability: PassabilityStatus,
    ): MutationResult {
        // Rejected, never normalised: an impossible pair means the observer's report is
        // contradictory or empty, and asking them again beats inventing an answer (ADR 0002 R1/R2).
        val observation = when (val outcome = RoadObservation.create(reason, waterLevel, passability)) {
            is ObservationValidation.Valid -> outcome.observation
            is ObservationValidation.Invalid -> return MutationResult.Rejected(outcome.problem)
        }
        val context = activeContext
            ?: return MutationResult.PersistenceFailure("hazard repository is not initialized")
        val identity = deviceId
            ?: return MutationResult.PersistenceFailure("device identity unavailable")

        // Identity and provenance are generated here, never by the UI, and the commit goes through
        // the same single ingress as every other report.
        return ingestRemote(
            HazardMutation(
                eventId = EventId(eventIdGenerator()),
                originDeviceId = identity,
                context = context,
                segmentId = segmentId,
                reason = reason,
                occurredAtEpochMillis = clock(),
                observation = observation,
            ),
        )
    }

    override suspend fun ingestRemote(mutation: HazardMutation): MutationResult =
        mutationMutex.withLock {
            val context = activeContext
                ?: return MutationResult.PersistenceFailure("hazard repository is not initialized")

            validationError(mutation, context)?.let { return MutationResult.Rejected(it) }

            try {
                var outcome: MutationResult =
                    MutationResult.PersistenceFailure("transaction produced no result")

                database.withTransaction {
                    val dao = database.hazardDao()
                    val existing = dao.findEvent(mutation.eventId.value)
                    if (existing != null) {
                        outcome = if (existing.matches(mutation)) {
                            MutationResult.Duplicate
                        } else {
                            MutationResult.Rejected("event id already exists with different content")
                        }
                        return@withTransaction
                    }

                    // Whether this report changes routing is a question for the *derivation*,
                    // not for "does this segment already have a row". Those two were the same
                    // question while every row was an exclusion; since XPH-69 a row can be
                    // present without excluding, so a row-count probe would report a routing
                    // change for a report that changes nothing.
                    val wasExcluded = derive(
                        dao.eventsForSegment(
                            datasetHash = mutation.context.dataset.contentHash,
                            incidentId = mutation.context.incidentId,
                            segmentId = mutation.segmentId.value.toLong(),
                        ),
                    ).values.any { it.excludesTraversal }

                    val inserted = dao.insert(mutation.toEntity(receivedAt = clock()))
                    outcome = if (inserted == -1L) {
                        MutationResult.Duplicate
                    } else {
                        MutationResult.Accepted(
                            event = mutation,
                            // A report changes the effective state only when it excludes and
                            // the segment was not already excluded. A non-excluding report is
                            // stored, replayed and shown, and changes no route.
                            effectiveStateChanged =
                                mutation.observation.excludesTraversal && !wasExcluded,
                        )
                    }
                }

                val result = outcome
                when (result) {
                    is MutationResult.Accepted -> Log.i(
                        TAG,
                        "committed ${mutation.eventId.value} segment=${mutation.segmentId.stableId} " +
                            "reason=${mutation.reason.code} changed=${result.effectiveStateChanged}",
                    )
                    MutationResult.Duplicate -> Log.i(TAG, "duplicate ${mutation.eventId.value} ignored")
                    is MutationResult.Rejected -> Log.w(TAG, "rejected ${mutation.eventId.value}: ${result.reason}")
                    is MutationResult.PersistenceFailure -> Log.e(TAG, "write failed: ${result.message}")
                }
                result
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                val message = error.message?.takeIf { it.isNotBlank() } ?: "hazard write failed"
                Log.e(TAG, "hazard write failed: $message", error)
                MutationResult.PersistenceFailure(message)
            }
        }

    override fun persistedEvents(context: HazardContext): Flow<HazardMutation> = flow {
        val rows = database.hazardDao().events(context.dataset.contentHash, context.incidentId)
        for (row in rows) {
            row.toDomain(context)?.let { emit(it) }
        }
    }

    /**
     * Publishes one derived view of the committed log.
     *
     * The revision increments only when the effective blocked set actually changes, so an
     * extra report for an already blocked segment cannot invalidate routes. The derived
     * observation states are published **even when the routing set is unchanged**: a
     * non-excluding report is new information for a person and none for a router.
     *
     * The blocked set and the states are put into a single [HazardState.Ready] so no reader
     * can observe one revision's blocker beside another's reports.
     */
    private fun publish(context: HazardContext, states: Map<SegmentId, SegmentObservationState>) {
        val blocked = HashSet<SegmentId>()
        for ((segment, state) in states) {
            if (state.excludesTraversal) blocked.add(segment)
        }
        synchronized(this) {
            val ready = _state.value as? HazardState.Ready
            if (ready != null && ready.snapshot.context == context) {
                val sameRoutingSet = ready.snapshot.blockedSegments == blocked
                // Even when the routing set is unchanged the states are republished, because they
                // are the record of what is known and they feed the risk policy. Only a genuinely
                // identical publication is skipped.
                if (sameRoutingSet && ready.observationStates == states) return
                _state.value = HazardState.Ready(
                    snapshot = HazardSnapshot(
                        context = context,
                        // The revision is the router's reroute trigger: it moves only when the set
                        // of segments a router must avoid actually changes.
                        revision = if (sameRoutingSet) ready.snapshot.revision else ready.snapshot.revision + 1L,
                        blockedSegments = blocked,
                    ),
                    observationStates = states,
                )
                return
            }
            _state.value = HazardState.Ready(HazardSnapshot(context, 0L, blocked), states)
        }
    }

    private fun validationError(mutation: HazardMutation, active: HazardContext): String? {
        // The dataset hash is the identity that matters on the wire (format
        // versions may differ between installs of the same dataset).
        if (mutation.context.dataset.contentHash != active.dataset.contentHash) {
            return "event belongs to a different dataset"
        }
        if (mutation.context.incidentId != active.incidentId) return "event belongs to a different incident"

        val eventId = mutation.eventId.value
        if (eventId.length !in MIN_ID_LENGTH..MAX_ID_LENGTH || !SAFE_ID.matches(eventId)) {
            return "invalid event id"
        }
        val device = mutation.originDeviceId.value
        if (device.length !in MIN_ID_LENGTH..MAX_ID_LENGTH || !SAFE_ID.matches(device)) {
            return "invalid device id"
        }

        val segmentCount = segmentCountProvider() ?: return "dataset is not loaded"
        if (mutation.segmentId.value !in 0 until segmentCount) {
            return "unknown segment ${mutation.segmentId.value}"
        }
        return null
    }

    private suspend fun ensureDeviceIdentity(): DeviceId {
        val dao = database.deviceIdentityDao()
        dao.get()?.let { return DeviceId(it.deviceId) }

        val generated = deviceIdGenerator()
        dao.insert(DeviceIdentityEntity(deviceId = generated))
        // Re-read: if another writer won the race, the stored row is authoritative.
        val stored = dao.get() ?: throw IllegalStateException("device identity could not be persisted")
        return DeviceId(stored.deviceId)
    }

    private fun HazardMutation.toEntity(receivedAt: Long): HazardEventEntity {
        val statesAxes = observation.statesBeyondProvenance
        return HazardEventEntity(
            eventId = eventId.value,
            datasetHash = context.dataset.contentHash,
            incidentId = context.incidentId,
            segmentId = segmentId.value.toLong(),
            originDeviceId = originDeviceId.value,
            reasonCode = reason.code,
            occurredAtEpochMillis = occurredAtEpochMillis,
            receivedAtEpochMillis = receivedAt,
            levelCode = if (statesAxes) observation.waterLevel.code else null,
            passabilityCode = if (statesAxes) observation.passability.code else null,
            // Reserved columns. Written by the source-typing phase (XPH-73) and the
            // location-assisted reporting phase (XPH-90), never synthesised here.
            sourceCode = null,
            accuracyMeters = null,
        )
    }

    /**
     * True when the report states axes beyond what its [HazardReason] already implies.
     *
     * A plain block *is* the v1 interpretation, so persisting its derived codes would
     * store a constant that merely repeats [HazardReason], and would blur "the
     * observer stated a depth" into "a depth was synthesised for storage". Those rows
     * therefore keep null codes and resolve back through
     * [RoadObservation.fromLegacy].
     */
    private val RoadObservation.statesBeyondProvenance: Boolean
        get() = this != RoadObservation.fromLegacy(reason)

    /**
     * Compares the **resolved** observation rather than the raw columns.
     *
     * A v1 row stores nulls; a re-sent copy of that same event carries the legacy
     * interpretation. Those are the same event and must dedupe as one, while an
     * event whose axes genuinely differ must be rejected as conflicting content.
     */
    private fun HazardEventEntity.matches(mutation: HazardMutation): Boolean {
        if (eventId != mutation.eventId.value) return false
        if (datasetHash != mutation.context.dataset.contentHash) return false
        if (incidentId != mutation.context.incidentId) return false
        if (segmentId != mutation.segmentId.value.toLong()) return false
        if (originDeviceId != mutation.originDeviceId.value) return false
        if (reasonCode != mutation.reason.code) return false
        if (occurredAtEpochMillis != mutation.occurredAtEpochMillis) return false
        return observationFor(mutation.reason) == mutation.observation
    }

    /**
     * Resolves the observation stored on this row.
     *
     * Null axes mean "this report did not state that axis", so a row with neither
     * code resolves to the v1 interpretation of its reason — the same value
     * [RoadObservation.fromLegacy] produces. That is how a v1 database keeps its exact
     * meaning after the v1 -> v2 migration.
     *
     * A stored code this build does not recognise is a forward-compatibility case (a
     * newer build wrote it). Each axis falls back independently and always toward the
     * conservative reading: an unparseable passability becomes impassable, an
     * unparseable water level becomes unknown. If the resulting combination is not
     * representable at all, the row resolves to the legacy interpretation of its
     * reason. Nothing on this path may make a row weaker than v1.
     */
    private fun HazardEventEntity.observationFor(reason: HazardReason): RoadObservation {
        if (levelCode == null && passabilityCode == null) return RoadObservation.fromLegacy(reason)

        val level = if (levelCode == null) {
            WaterLevelCategory.UNKNOWN
        } else {
            WaterLevelCategory.fromCode(levelCode) ?: run {
                Log.w(TAG, "unknown water level code '$levelCode' on $eventId; treating it as unknown")
                WaterLevelCategory.UNKNOWN
            }
        }
        val passability = if (passabilityCode == null) {
            PassabilityStatus.UNKNOWN
        } else {
            PassabilityStatus.fromCode(passabilityCode) ?: run {
                Log.w(TAG, "unknown passability code '$passabilityCode' on $eventId; failing closed")
                PassabilityStatus.IMPASSABLE
            }
        }

        return when (val outcome = RoadObservation.create(reason, level, passability)) {
            is ObservationValidation.Valid -> outcome.observation
            is ObservationValidation.Invalid -> {
                Log.w(
                    TAG,
                    "stored observation on $eventId is not representable (${outcome.problem}); failing closed",
                )
                RoadObservation.fromLegacy(reason)
            }
        }
    }

    /**
     * The one derivation: committed rows in, per-segment state out.
     *
     * Both the routing snapshot and [observationStates] come from this call, so they cannot
     * disagree with each other or with the resolution rule in [observationFor].
     */
    private fun derive(rows: List<HazardEventEntity>): Map<SegmentId, SegmentObservationState> =
        SegmentObservationAggregator.aggregate(rows.mapNotNull { it.toObservedReport() })

    /**
     * Resolves a persisted row for the aggregation.
     *
     * An unreadable reason code becomes [ReportContent.Unreadable] rather than being
     * dropped, so the row keeps excluding exactly as it did in v1 (ADR 0004 §2) and its
     * device and timestamps still count. A row whose segment id cannot be represented as a
     * graph index is skipped: it names no segment that could be excluded.
     *
     * Note the deliberate asymmetry with [toDomain], which returns null for an unreadable
     * reason: the local derivation must fail closed and keep the exclusion, while replay to
     * a peer cannot forward an event whose reason this build cannot even name — the codec
     * rejects unknown codes, and an older peer would reject the session (ADR 0004 §8).
     */
    private fun HazardEventEntity.toObservedReport(): ObservedReport? {
        if (segmentId < 0 || segmentId > Int.MAX_VALUE.toLong()) return null
        val reason = HazardReason.fromCode(reasonCode)
        val content = if (reason == null) {
            ReportContent.Unreadable(reasonCode)
        } else {
            ReportContent.Observed(observationFor(reason))
        }
        return ObservedReport(
            eventId = EventId(eventId),
            deviceId = DeviceId(originDeviceId),
            // Derived, never stored: a report is first-hand on the device that created it and
            // relayed everywhere else, so this is a local fact about a shared row.
            source = ObservationSource.of(originDeviceId, deviceId?.value),
            segmentId = SegmentId(segmentId.toInt()),
            occurredAtEpochMillis = occurredAtEpochMillis,
            receivedAtEpochMillis = receivedAtEpochMillis,
            content = content,
        )
    }

    private fun HazardEventEntity.toDomain(context: HazardContext): HazardMutation? {
        val reason = HazardReason.fromCode(reasonCode) ?: return null
        if (datasetHash != context.dataset.contentHash) return null
        if (segmentId < 0) return null
        return HazardMutation(
            eventId = EventId(eventId),
            originDeviceId = DeviceId(originDeviceId),
            context = HazardContext(context.dataset, incidentId),
            segmentId = SegmentId(segmentId.toInt()),
            reason = reason,
            occurredAtEpochMillis = occurredAtEpochMillis,
            observation = observationFor(reason),
        )
    }

    private companion object {
        const val TAG = "ResQRouteHazard"
        const val MIN_ID_LENGTH = 8
        const val MAX_ID_LENGTH = 64
        val SAFE_ID = Regex("^[A-Za-z0-9._:-]+$")
    }
}
