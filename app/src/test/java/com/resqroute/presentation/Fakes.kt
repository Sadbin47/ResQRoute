package com.resqroute.presentation

import com.resqroute.core.graph.SegmentHit
import com.resqroute.core.graph.SegmentSpatialIndex
import com.resqroute.core.model.EdgeId
import com.resqroute.core.model.GeoCoordinate
import com.resqroute.core.model.SegmentId
import com.resqroute.core.routing.RoutePath
import com.resqroute.core.routing.RouteRequest
import com.resqroute.core.routing.RouteResult
import com.resqroute.core.routing.RoutingEngine
import com.resqroute.core.routing.RoutingSnapshot
import com.resqroute.domain.hazard.DeviceId
import com.resqroute.domain.hazard.EventId
import com.resqroute.domain.hazard.HazardContext
import com.resqroute.domain.hazard.HazardMutation
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.HazardRepository
import com.resqroute.domain.hazard.HazardSnapshot
import com.resqroute.domain.hazard.HazardState
import com.resqroute.domain.hazard.ObservationValidation
import com.resqroute.domain.hazard.RoadObservation
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.SegmentObservationAggregator
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.hazard.HazardFixtures
import com.resqroute.domain.hazard.MutationResult
import com.resqroute.domain.map.DatasetState
import com.resqroute.domain.map.MapRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/** Test doubles for the Phase 7 ViewModel. Kept small and deterministic. */

internal class FakeMapRepository(initial: DatasetState = DatasetState.NotLoaded) : MapRepository {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<DatasetState> = _state.asStateFlow()
    override suspend fun initialize() = Unit
    fun set(state: DatasetState) { _state.value = state }
}

internal class FakeHazardRepository(
    deviceId: String = "device-fixed-0001",
) : HazardRepository {

    private val _state = MutableStateFlow<HazardState>(HazardState.Restoring)
    override val state: StateFlow<HazardState> = _state.asStateFlow()

    var failNextWrite: Boolean = false
    var holdRestoring: Boolean = false
    var context: HazardContext? = null
    val mutations = mutableListOf<HazardMutation>()

    private val blocked = linkedSetOf<SegmentId>()
    private var revision: Long = 0

    /**
     * Derived per-segment state, published atomically with the blocked set exactly as
     * `RoomHazardRepository` does. Without this the risk policy would always be empty and the
     * pairing could not be exercised at all.
     */
    private val reportedStates = linkedMapOf<SegmentId, SegmentObservationState>()

    private fun republish() {
        val active = context ?: return
        _state.value = HazardState.Ready(
            snapshot = HazardSnapshot(active, revision, blocked.toSet()),
            observationStates = reportedStates.toMap(),
        )
    }

    /**
     * Publishes a **non-excluding** report on [segmentId]: the road stays open and is priced as
     * risky. This is the case that had no effect at all before penalties were wired in.
     */
    fun simulateReportedRisk(
        segmentId: SegmentId,
        level: WaterLevelCategory = WaterLevelCategory.KNEE,
        verdict: PassabilityStatus = PassabilityStatus.CAUTION,
        devices: Int = 1,
    ) {
        reportedStates[segmentId] = SegmentObservationAggregator.aggregate(
            (1..devices).map { index ->
                HazardFixtures.observedReport(
                    eventId = "evt-risk-$segmentId-$index",
                    device = "peer-device-%04d".format(index),
                    segmentId = segmentId.value,
                    observation = HazardFixtures.observation(HazardReason.WATERLOGGED, level, verdict),
                )
            },
        ).getValue(segmentId)
        republish()
    }

    override suspend fun initialize(context: HazardContext) {
        this.context = context
        blocked.clear()
        revision = 0
        if (!holdRestoring) {
            reportedStates.clear()
            _state.value = HazardState.Ready(HazardSnapshot(context, revision, blocked.toSet()))
        }
    }

    /** Reports received with their axes, so a view-model test can assert what the UI submitted. */
    val recordedReports = mutableListOf<Triple<SegmentId, WaterLevelCategory, PassabilityStatus>>()

    /**
     * Mirrors the real ingress: the axes are validated (ADR 0002 R1/R2) and an impossible pair is
     * rejected rather than stored, so a view-model test cannot pass against a fake that accepts
     * everything the real repository would refuse.
     */
    override suspend fun recordLocal(
        segmentId: SegmentId,
        reason: HazardReason,
        waterLevel: WaterLevelCategory,
        passability: PassabilityStatus,
    ): MutationResult {
        // Faithful to the real ingress: validated first, and an impossible pair is rejected rather
        // than stored, so a view-model test cannot pass against a fake that accepts what the real
        // repository would refuse.
        val observation = when (val outcome = RoadObservation.create(reason, waterLevel, passability)) {
            is ObservationValidation.Valid -> outcome.observation
            is ObservationValidation.Invalid -> return MutationResult.Rejected(outcome.problem)
        }
        // Logged only once it would be stored, so "what reached the repository" means exactly that.
        recordedReports += Triple(segmentId, waterLevel, passability)

        if (observation.excludesTraversal) return blockLocal(segmentId, reason)

        // A report that does not exclude is committed and changes NO routing state and NO revision -
        // the real derivation's `excludesTraversal` is the only thing that closes a road, and this
        // double has to model that or it would let a caution report look like a closure.
        val active = context ?: return MutationResult.PersistenceFailure("not initialized")
        val mutation = HazardMutation(
            eventId = EventId("evt-local-${mutations.size + 1}"),
            originDeviceId = DeviceId("device-fixed-0001"),
            context = active,
            segmentId = segmentId,
            reason = reason,
            occurredAtEpochMillis = 0,
            observation = observation,
        )
        mutations += mutation
        return MutationResult.Accepted(mutation, effectiveStateChanged = false)
    }

    override suspend fun blockLocal(segmentId: SegmentId, reason: HazardReason): MutationResult {
        if (failNextWrite) {
            failNextWrite = false
            return MutationResult.PersistenceFailure("disk full")
        }
        return commit(segmentId, reason)
    }

    override suspend fun ingestRemote(mutation: HazardMutation): MutationResult =
        commit(mutation.segmentId, mutation.reason)

    override fun persistedEvents(context: HazardContext): Flow<HazardMutation> = flowOf()

    /** Simulates a peer report arriving through the same ingress. */
    fun simulateRemoteBlock(segmentId: SegmentId, reason: HazardReason = HazardReason.BLOCKED): MutationResult {
        val active = context ?: error("initialize() must run first")
        val mutation = HazardMutation(
            eventId = EventId("evt-remote-${mutations.size + 1}"),
            originDeviceId = DeviceId("peer-device"),
            context = active,
            segmentId = segmentId,
            reason = reason,
            occurredAtEpochMillis = 0,
        )
        mutations += mutation
        val changed = blocked.add(segmentId)
        if (changed) revision++
        _state.value = HazardState.Ready(HazardSnapshot(active, revision, blocked.toSet()))
        return MutationResult.Accepted(mutation, changed)
    }

    private fun commit(segmentId: SegmentId, reason: HazardReason): MutationResult {
        val active = context ?: return MutationResult.PersistenceFailure("not initialized")
        val changed = blocked.add(segmentId)
        val mutation = HazardMutation(
            eventId = EventId("evt-local-${mutations.size + 1}"),
            originDeviceId = DeviceId("device-fixed-0001"),
            context = active,
            segmentId = segmentId,
            reason = reason,
            occurredAtEpochMillis = 0,
        )
        mutations += mutation
        if (changed) revision++
        republish()
        return MutationResult.Accepted(mutation, changed)
    }

    fun markUnavailable(message: String) {
        _state.value = HazardState.Unavailable(message)
    }
}

/**
 * Engine with a controllable delay. It returns the direct segment (0) unless the
 * snapshot blocks it, in which case it returns the detour segment (1), which is
 * enough to observe reroute behaviour without running real A*.
 */
internal class FakeRoutingEngine : RoutingEngine {

    var delayMillis: Long = 0
    /** When true the engine reports the destination as unreachable. */
    var noRoute: Boolean = false
    /**
     * When true a risk-priced request avoids the direct segment. Turn it off to model the case
     * where the only route crosses reported risk and there is no alternative to offer.
     */
    var policySensitive: Boolean = true
    val requests = mutableListOf<RouteRequest>()

    override suspend fun findRoute(snapshot: RoutingSnapshot, request: RouteRequest): RouteResult {
        requests += request
        if (delayMillis > 0) delay(delayMillis)
        if (noRoute) return RouteResult.NoRoute

        val directBlocked = snapshot.blocker.isBlockedSegment(DIRECT_SEGMENT)
        // Results differ per destination so a stale result is distinguishable.
        val segment = when {
            request.destinationNodeId.value == SECOND_DESTINATION_NODE -> SECOND_DESTINATION_SEGMENT
            directBlocked -> DETOUR_SEGMENT
            // A request priced with reported risk takes the detour instead of the direct segment,
            // which is what makes the two routes diverge. With no reports the policy is
            // distance-only, so this branch is not taken and the answer is unchanged.
            policySensitive && request.policy.segmentPenaltyMeters.isNotEmpty() -> DETOUR_SEGMENT
            else -> DIRECT_SEGMENT
        }
        val distance = when (segment) {
            DIRECT_SEGMENT -> 100.0
            DETOUR_SEGMENT -> 200.0
            else -> 300.0
        }
        return RouteResult.Found(
            RoutePath(
                dataset = snapshot.dataset,
                incidentId = snapshot.incidentId,
                requestGeneration = request.generation,
                hazardRevision = snapshot.hazardRevision,
                nodeIds = listOf(request.startNodeId, request.destinationNodeId),
                edgeIds = listOf(EdgeId(segment)),
                segmentIds = listOf(SegmentId(segment)),
                distanceMeters = distance,
                totalCost = distance,
            ),
        )
    }

    companion object {
        const val DIRECT_SEGMENT = 0
        const val DETOUR_SEGMENT = 1
        const val SECOND_DESTINATION_SEGMENT = 2
        const val SECOND_DESTINATION_NODE = 303L
    }
}

/** Spatial index is not used by the ViewModel; the UI owns it. */
internal object NoopSpatialIndex : SegmentSpatialIndex {
    override suspend fun nearestSegment(
        coordinate: GeoCoordinate,
        maximumDistanceMeters: Double,
    ): SegmentHit? = null

    override suspend fun nearbySegments(
        coordinate: GeoCoordinate,
        maximumDistanceMeters: Double,
        limit: Int,
    ): List<SegmentHit> = emptyList()
}
