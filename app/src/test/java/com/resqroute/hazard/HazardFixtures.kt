package com.resqroute.hazard

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.resqroute.core.model.DatasetIdentity
import com.resqroute.core.model.SegmentId
import com.resqroute.data.local.ResQRouteDatabase
import com.resqroute.data.repository.RoomHazardRepository
import com.resqroute.domain.hazard.Clearance
import com.resqroute.domain.hazard.ClearanceValidation
import com.resqroute.domain.hazard.DeviceId
import com.resqroute.domain.hazard.EventId
import com.resqroute.domain.hazard.HazardContext
import com.resqroute.domain.hazard.HazardMutation
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.HazardState
import com.resqroute.domain.hazard.ObservationSource
import com.resqroute.domain.hazard.ObservationValidation
import com.resqroute.domain.hazard.ObservedReport
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.ReportContent
import com.resqroute.domain.hazard.RoadObservation
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Shared fixtures for the Phase 6 Room tests.
 *
 * These run against an actual Room database on the JVM (Robolectric), which is
 * what the blueprint's testing plan requires for repository/Room behaviour.
 */
internal object HazardFixtures {

    const val DATABASE_NAME = "resqroute-hazard-test.db"
    const val SEGMENT_COUNT = 39_923
    const val DATASET_HASH = "1111111111111111111111111111111111111111111111111111111111111111"
    const val OTHER_DATASET_HASH = "2222222222222222222222222222222222222222222222222222222222222222"
    const val INCIDENT = "incident-alpha"
    const val OTHER_INCIDENT = "incident-beta"
    const val FIXED_CLOCK = 1_700_000_000_000L

    val dataset: DatasetIdentity = DatasetIdentity(DATASET_HASH, 1)
    val otherDataset: DatasetIdentity = DatasetIdentity(OTHER_DATASET_HASH, 1)

    val contextAlpha: HazardContext = HazardContext(dataset, INCIDENT)
    val contextOtherDataset: HazardContext = HazardContext(otherDataset, INCIDENT)
    val contextOtherIncident: HazardContext = HazardContext(dataset, OTHER_INCIDENT)

    fun context(): Context = ApplicationProvider.getApplicationContext()

    fun openDatabase(
        context: Context,
        name: String = DATABASE_NAME,
    ): ResQRouteDatabase = Room.databaseBuilder(context, ResQRouteDatabase::class.java, name)
        .addMigrations(*ResQRouteDatabase.MIGRATIONS)
        .allowMainThreadQueries()
        .build()

    fun repository(
        database: ResQRouteDatabase,
        scope: CoroutineScope,
        segmentCount: Int? = SEGMENT_COUNT,
        deviceIdGenerator: () -> String = { "device-fixed-0001" },
        eventIds: AtomicInteger = AtomicInteger(0),
        clock: () -> Long = { FIXED_CLOCK },
    ): RoomHazardRepository = RoomHazardRepository(
        database = database,
        segmentCountProvider = { segmentCount },
        scope = scope,
        clock = clock,
        eventIdGenerator = { "evt-%08d".format(eventIds.incrementAndGet()) },
        deviceIdGenerator = deviceIdGenerator,
    )

    fun mutation(
        eventId: String,
        segmentId: Int,
        reason: HazardReason = HazardReason.WATERLOGGED,
        context: HazardContext = contextAlpha,
        deviceId: String = "device-fixed-0001",
        occurredAt: Long = FIXED_CLOCK,
        observation: RoadObservation = RoadObservation.fromLegacy(reason),
    ): HazardMutation = HazardMutation(
        eventId = EventId(eventId),
        originDeviceId = DeviceId(deviceId),
        context = context,
        segmentId = SegmentId(segmentId),
        reason = reason,
        occurredAtEpochMillis = occurredAt,
        observation = observation,
    )

    /** Builds a validated observation, failing the test when the pair is invalid. */
    fun observation(
        reason: HazardReason,
        waterLevel: WaterLevelCategory,
        passability: PassabilityStatus,
    ): RoadObservation {
        val outcome = RoadObservation.create(reason, waterLevel, passability)
        check(outcome is ObservationValidation.Valid) { "fixture observation is invalid: $outcome" }
        return outcome.observation
    }

    /** One report as the aggregation sees it, for the pure tests. */
    fun observedReport(
        eventId: String,
        device: String,
        segmentId: Int,
        observation: RoadObservation,
        source: ObservationSource = ObservationSource.DIRECT_OBSERVATION,
        occurredAt: Long = FIXED_CLOCK,
        receivedAt: Long = FIXED_CLOCK,
    ): ObservedReport = ObservedReport(
        eventId = EventId(eventId),
        deviceId = DeviceId(device),
        source = source,
        segmentId = SegmentId(segmentId),
        occurredAtEpochMillis = occurredAt,
        receivedAtEpochMillis = receivedAt,
        content = ReportContent.Observed(observation),
    )

    /** A legal clearance, failing the test when the axes would not be one (ADR 0005 §5.2). */
    fun clearance(
        reason: HazardReason = HazardReason.BLOCKED,
        waterLevel: WaterLevelCategory = WaterLevelCategory.NO_VISIBLE_WATER,
        passability: PassabilityStatus = PassabilityStatus.PASSABLE_REPORTED,
    ): Clearance {
        val outcome = Clearance.create(reason, waterLevel, passability)
        check(outcome is ClearanceValidation.Valid) { "fixture clearance is invalid: $outcome" }
        return outcome.clearance
    }

    /** One clearance as the aggregation sees it. */
    fun clearedReport(
        eventId: String,
        device: String = "device-fixed-0001",
        segmentId: Int,
        clearance: Clearance = clearance(),
        source: ObservationSource = ObservationSource.DIRECT_OBSERVATION,
        occurredAt: Long = FIXED_CLOCK,
        receivedAt: Long = FIXED_CLOCK,
    ): ObservedReport = ObservedReport(
        eventId = EventId(eventId),
        deviceId = DeviceId(device),
        source = source,
        segmentId = SegmentId(segmentId),
        occurredAtEpochMillis = occurredAt,
        receivedAtEpochMillis = receivedAt,
        content = ReportContent.Cleared(clearance),
    )

    /** One unreadable report, as a newer build's reason code would arrive. */
    fun unreadableReport(
        eventId: String,
        device: String,
        segmentId: Int,
        reasonCode: String = "FLOODED_DEEPER",
        source: ObservationSource = ObservationSource.RELAYED_ACCOUNT,
        occurredAt: Long = FIXED_CLOCK,
        receivedAt: Long = FIXED_CLOCK,
    ): ObservedReport = ObservedReport(
        eventId = EventId(eventId),
        deviceId = DeviceId(device),
        source = source,
        segmentId = SegmentId(segmentId),
        occurredAtEpochMillis = occurredAt,
        receivedAtEpochMillis = receivedAt,
        content = ReportContent.Unreadable(reasonCode),
    )

    /** Waits for the committed snapshot to include [segmentIndex]. */
    suspend fun awaitBlocked(repository: RoomHazardRepository, segmentIndex: Int): HazardState.Ready =
        withTimeout(5_000) {
            repository.state.first { state ->
                state is HazardState.Ready && state.snapshot.isBlockedSegment(segmentIndex)
            } as HazardState.Ready
        }

    suspend fun awaitReady(repository: RoomHazardRepository): HazardState.Ready =
        withTimeout(5_000) { repository.state.first { it is HazardState.Ready } as HazardState.Ready }

    /** Waits for the derived state of [segmentIndex], optionally matching [predicate]. */
    suspend fun awaitObservedState(
        repository: RoomHazardRepository,
        segmentIndex: Int,
        predicate: (SegmentObservationState) -> Boolean = { true },
    ): SegmentObservationState = withTimeout(5_000) {
        repository.observationStates
            .first { states -> states[SegmentId(segmentIndex)]?.let(predicate) == true }
            .getValue(SegmentId(segmentIndex))
    }

    /** Waits until the derived state covers at least [count] segments. */
    suspend fun awaitObservedSegmentCount(
        repository: RoomHazardRepository,
        count: Int,
    ): Map<SegmentId, SegmentObservationState> = withTimeout(5_000) {
        repository.observationStates.first { it.size >= count }
    }

    /**
     * Waits until the derived state satisfies [predicate].
     *
     * The [RoomHazardRepository.observationStates] flow is fed from Room's invalidation
     * tracker, so a read taken immediately after a commit can still describe the previous
     * row set. Waiting on the predicate is what makes these assertions about the state
     * rather than about timing.
     */
    suspend fun awaitDerivedState(
        repository: RoomHazardRepository,
        predicate: (Map<SegmentId, SegmentObservationState>) -> Boolean,
    ): Map<SegmentId, SegmentObservationState> = withTimeout(5_000) {
        repository.observationStates.first(predicate)
    }
}
