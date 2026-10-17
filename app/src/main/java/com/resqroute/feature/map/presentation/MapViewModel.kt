package com.resqroute.feature.map.presentation

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.resqroute.app.AppContainer
import com.resqroute.core.model.DatasetIdentity
import com.resqroute.core.model.NodeId
import com.resqroute.core.model.GeoCoordinate
import com.resqroute.core.model.SegmentId
import com.resqroute.core.routing.AStarRoutingEngine
import com.resqroute.core.routing.RoutePath
import com.resqroute.core.routing.RouteRequest
import com.resqroute.core.routing.RouteResult
import com.resqroute.core.routing.RoutingEngine
import com.resqroute.core.routing.RoutingPolicy
import com.resqroute.core.routing.RoutingSnapshot
import com.resqroute.domain.hazard.HazardContext
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.RoadObservation
import com.resqroute.domain.hazard.ObservationValidation
import com.resqroute.domain.usecase.RecordRoadReport
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.HazardRepository
import com.resqroute.domain.hazard.HazardMutation
import com.resqroute.domain.hazard.HazardState
import com.resqroute.domain.hazard.MutationResult
import com.resqroute.domain.location.LocationFix
import com.resqroute.domain.location.LocationOutcome
import com.resqroute.domain.location.LocationSource
import com.resqroute.domain.location.SegmentSnap
import com.resqroute.domain.location.SegmentSnapper
import com.resqroute.domain.map.DatasetState
import com.resqroute.domain.map.MapDataset
import com.resqroute.domain.risk.RiskPenaltyPolicy
import com.resqroute.domain.map.MapRepository
import com.resqroute.domain.sync.SyncConfig
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything the engine needs for one search, plus the metadata the publication
 * guard compares (generation, dataset, incident, hazard revision).
 */
internal data class RoutingInput(
    val generation: Long,
    val dataset: DatasetIdentity,
    val incidentId: String,
    val hazardRevision: Long,
    val startNodeId: NodeId,
    val destinationNodeId: NodeId,
    val snapshot: RoutingSnapshot,
    /** The request that minimises distance, and the one the guard is keyed to. */
    val request: RouteRequest,
    /**
     * The same decision priced with reported risk, from the **same** [snapshot] and generation.
     *
     * Defaults to [request], which is the honest reading of "no separate risk policy": the two
     * searches then coincide and the answer collapses to a single route.
     */
    val lowerRiskRequest: RouteRequest = request,
) {
    /** Identity of the routing decision, independent of the generation counter. */
    val key: RoutingInputKey
        get() = RoutingInputKey(
            datasetHash = dataset.contentHash,
            incidentId = incidentId,
            hazardRevision = hazardRevision,
            startNode = startNodeId,
            destination = destinationNodeId,
            reportedRisk = lowerRiskRequest.policy.segmentPenaltyMeters,
        )
}

internal data class RoutingInputKey(
    val datasetHash: String,
    val incidentId: String,
    val hazardRevision: Long,
    val startNode: NodeId,
    val destination: NodeId,
    /**
     * The risk pricing in force for this decision.
     *
     * Part of the identity on purpose. [hazardRevision] moves only when the **blocked set** changes,
     * and a report that does not close a road leaves it untouched — so without this a newly
     * reported hazard would be priced into a policy that `distinctUntilChanged` then discarded as
     * "the same decision", and the route would silently never be re-costed. The state of the world
     * that decides a route includes what the reports cost, not only what they close.
     */
    val reportedRisk: Map<SegmentId, Double>,
)

/**
 * Publication guard (Phase 7 acceptance criteria 2 and 6).
 *
 * A result may be published only if it was produced for the newest request and
 * its dataset, incident and hazard revision are all still the current ones. A
 * search that finishes after a newer request, a new block, or a dataset/incident
 * change is dropped instead of overwriting newer state.
 */
internal fun isRoutePublishable(
    input: RoutingInput,
    currentGeneration: Long,
    currentDataset: DatasetIdentity?,
    currentIncidentId: String?,
    currentHazardRevision: Long,
): Boolean =
    input.generation == currentGeneration &&
        input.dataset == currentDataset &&
        input.incidentId == currentIncidentId &&
        input.hazardRevision == currentHazardRevision

/**
 * Owns selection, routing generation and the displayed route.
 *
 * Pipeline: dataset + hazard state + selection produce a [RoutingInput]; every
 * effective hazard-set change yields a new input, `mapLatest` cancels the
 * obsolete search, and [isRoutePublishable] is the final gate. Cancellation is
 * rethrown, never converted into a user-visible failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModel(
    private val mapRepository: MapRepository,
    private val hazardRepository: HazardRepository,
    private val routingEngine: RoutingEngine,
    private val savedStateHandle: SavedStateHandle,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Called with a report **after** the repository committed it, so the peer
     * coordinator only ever sends durable events (persist-before-send).
     */
    private val onLocalCommitted: (HazardMutation) -> Unit = {},
    /**
     * Last-known location, for the "report where I am" path (XPH-90). Defaults to a source that
     * never has a fix, so a caller that does not care about location — every existing test — needs
     * no change, and the manual long-press path is unaffected.
     */
    private val locationSource: LocationSource = LocationSource.Unavailable,
) : ViewModel() {

    /**
     * The domain entry point for a person's report. Constructed here from the same repository the
     * view model holds, so there is no second ingress and no new constructor dependency to thread
     * through every caller and test.
     */
    private val recordRoadReport = RecordRoadReport(hazardRepository)

    private val generationCounter = AtomicLong(0)

    @Volatile
    private var hazardRestoreRequested = false

    @Volatile
    private var latestGeneration: Long = 0

    @Volatile
    private var latestDataset: DatasetIdentity? = null

    @Volatile
    private var latestIncidentId: String? = null

    @Volatile
    private var latestHazardRevision: Long = -1

    private val selection = MutableStateFlow(
        Selection(
            startShelterIndex = savedStateHandle[KEY_START],
            destinationShelterIndex = savedStateHandle[KEY_DESTINATION],
        ),
    )

    private val local = MutableStateFlow(LocalOverlay())

    private val _uiState = MutableStateFlow(
        MapUiState.Initial.copy(
            startShelterIndex = savedStateHandle[KEY_START],
            destinationShelterIndex = savedStateHandle[KEY_DESTINATION],
        ),
    )
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    init {
        observeBaseState()
        observeRouting()
    }

    // ------------------------------------------------------------- observers

    private fun observeBaseState() {
        viewModelScope.launch {
            combine(
                mapRepository.state,
                hazardRepository.state,
                selection,
                local,
            ) { dataset, hazard, selection, local ->
                Frame(dataset, hazard, selection, local)
            }.collect { frame ->
                val readyDataset = (frame.dataset as? DatasetState.Ready)?.dataset
                if (readyDataset != null && !hazardRestoreRequested) {
                    // Restoring from Room is what makes hazard truth durable across
                    // process death; it happens once per ViewModel lifetime.
                    hazardRestoreRequested = true
                    viewModelScope.launch {
                        hazardRepository.initialize(HazardContext(readyDataset.identity, INCIDENT_ID))
                    }
                }
                latestDataset = readyDataset?.identity
                latestHazardRevision = (frame.hazard as? HazardState.Ready)?.snapshot?.revision ?: -1
                latestIncidentId = INCIDENT_ID
                _uiState.update { current ->
                    current.copy(
                        datasetState = frame.dataset,
                        hazardState = frame.hazard,
                        startShelterIndex = frame.selection.startShelterIndex,
                        destinationShelterIndex = frame.selection.destinationShelterIndex,
                        highlightedSegment = frame.local.highlightedSegment,
                        ambiguousCandidates = frame.local.ambiguousCandidates,
                        pendingReport = frame.local.pendingReport,
                        reportError = frame.local.reportError,
                    )
                }
            }
        }
    }

    private fun observeRouting() {
        viewModelScope.launch {
            routingInputs()
                .mapLatest { input ->
                    if (input == null) {
                        _uiState.update {
                            it.copy(
                                routeStatus = RouteStatus.Idle,
                                displayedRoute = null,
                                displayedRouteUsable = false,
                            )
                        }
                        return@mapLatest null
                    }
                    // A new request supersedes everything older. The route still on
                    // screen may cross a segment that was blocked a moment ago, so
                    // it is demoted to "not yet validated" (dashed grey) until the
                    // replacement publishes; it is never left looking valid.
                    latestGeneration = input.generation
                    _uiState.update {
                        it.copy(
                            routeStatus = RouteStatus.Calculating,
                            displayedRouteUsable = false,
                        )
                    }
                    input to search(input)
                }
                .collect { pair ->
                    if (pair != null) publish(pair.first, pair.second)
                }
        }
    }

    private fun routingInputs(): Flow<RoutingInput?> =
        combine(
            mapRepository.state,
            hazardRepository.state,
            selection,
        ) { dataset, hazard, selection ->
            buildInput(dataset, hazard, selection)
        }.distinctUntilChanged { previous, next ->
            keyOf(previous) == keyOf(next)
        }

    private fun keyOf(input: RoutingInput?): RoutingInputKey? = input?.key

    private fun buildInput(
        datasetState: DatasetState,
        hazardState: HazardState,
        selection: Selection,
    ): RoutingInput? {
        val dataset = (datasetState as? DatasetState.Ready)?.dataset ?: return null
        // Routing must never run against a temporary empty hazard set.
        val readyHazard = (hazardState as? HazardState.Ready) ?: return null
        val snapshotHazard = readyHazard.snapshot

        val startIndex = selection.startShelterIndex ?: return null
        val destinationIndex = selection.destinationShelterIndex ?: return null
        val start = dataset.shelters.getOrNull(startIndex) ?: return null
        val destination = dataset.shelters.getOrNull(destinationIndex) ?: return null

        val generation = generationCounter.incrementAndGet()
        val snapshot = RoutingSnapshot(
            dataset = dataset.identity,
            incidentId = INCIDENT_ID,
            hazardRevision = snapshotHazard.revision,
            graph = dataset.graph,
            blocker = snapshotHazard,
        )
        // The risk policy is built from the observation state that was published **with** this
        // snapshot, not from whatever the repository holds a moment later: a route priced against
        // one revision of reports and guarded against another's revision is a route nobody asked
        // for. Both requests then share one snapshot and one generation.
        val riskPolicy = RiskPenaltyPolicy.forStates(readyHazard.observationStates) {
            dataset.graph.segmentLengthMeters(it)
        }
        return RoutingInput(
            generation = generation,
            dataset = dataset.identity,
            incidentId = INCIDENT_ID,
            hazardRevision = snapshotHazard.revision,
            startNodeId = start.accessNodeId,
            destinationNodeId = destination.accessNodeId,
            snapshot = snapshot,
            request = RouteRequest(
                generation = generation,
                startNodeId = start.accessNodeId,
                destinationNodeId = destination.accessNodeId,
                policy = RoutingPolicy.DISTANCE_ONLY,
            ),
            lowerRiskRequest = RouteRequest(
                generation = generation,
                startNodeId = start.accessNodeId,
                destinationNodeId = destination.accessNodeId,
                policy = riskPolicy,
            ),
        )
    }

    /**
     * Runs both searches over one snapshot.
     *
     * Sequentially rather than concurrently: the two runs share a snapshot and a generation, so
     * their order cannot matter, and running them in series keeps the cost obvious and the failure
     * modes simple. Cancellation is rethrown, never converted into a route failure.
     */
    private suspend fun search(input: RoutingInput): RouteSearchResults =
        withContext(computeDispatcher) {
            val shortest = findRoute(input.snapshot, input.request)
            // With no separate risk request the searches are the same question, so ask it once.
            val lowerRisk = if (input.lowerRiskRequest == input.request) {
                shortest
            } else {
                findRoute(input.snapshot, input.lowerRiskRequest)
            }
            RouteSearchResults(shortest, lowerRisk)
        }

    private suspend fun findRoute(snapshot: RoutingSnapshot, request: RouteRequest): RouteResult =
        try {
            routingEngine.findRoute(snapshot, request)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            RouteResult.Failure(error.message ?: "routing failed")
        }

    private fun publish(input: RoutingInput, results: RouteSearchResults) {
        // One guard check for the whole decision. The pair was computed from one snapshot and one
        // generation, so publishing half of it is not a state the UI may ever observe.
        if (!isRoutePublishable(
                input = input,
                currentGeneration = latestGeneration,
                currentDataset = latestDataset,
                currentIncidentId = latestIncidentId,
                currentHazardRevision = latestHazardRevision,
            )
        ) {
            return
        }
        val status = statusFor(input, results)
        Log.i(
            TAG,
            "route published: ${describe(status)} " +
                "hazardRevision=${input.hazardRevision} " +
                "blockedInSnapshot=${_uiState.value.committedBlockedSegments.size}",
        )
        // Only a route that passed the guard and the invariants is ever marked usable. Anything
        // else (no route, failure, rejected invariant) clears the line instead of leaving a stale
        // one that looks valid. The geometry drawn stays the distance-preferred route; which option
        // to present is a presentation rule owned by XPH-77, and the whole decision is in
        // `routeStatus` for it to use.
        val displayed = (status as? RouteStatus.Ready)?.options?.let { options ->
            (options.offered.firstOrNull() ?: return@let null).route
        }
        _uiState.update {
            it.copy(
                routeStatus = status,
                displayedRoute = displayed,
                displayedRouteUsable = status is RouteStatus.Ready,
            )
        }
    }

    /** Maps both results to the one status the UI is allowed to see. */
    private fun statusFor(input: RoutingInput, results: RouteSearchResults): RouteStatus {
        val shortest = results.shortest as? RouteResult.Found
        val lowerRisk = results.lowerRisk as? RouteResult.Found

        // Penalties are finite and non-negative, so they can never remove an edge and cannot change
        // reachability: both searches must reach the same conclusion. A disagreement means the
        // engine or the policy is broken, and publishing either route would be publishing a guess.
        if ((shortest == null) != (lowerRisk == null)) {
            Log.e(
                TAG,
                "ROUTE INVARIANT VIOLATED: searches disagreed: " +
                    "shortest=${results.shortest} lowerRisk=${results.lowerRisk}",
            )
            return RouteStatus.Failed("route searches disagreed")
        }

        if (shortest == null) {
            return when (val result = results.shortest) {
                RouteResult.NoRoute -> RouteStatus.NoRoute
                is RouteResult.InvalidEndpoint -> RouteStatus.Failed("invalid ${result.endpoint.name.lowercase()} endpoint")
                is RouteResult.Failure -> RouteStatus.Failed(result.message)
                // Unreachable: the disagreement check above already returned for a lone Found.
                is RouteResult.Found -> RouteStatus.Failed("route searches disagreed")
            }
        }
        val lowerRiskPath = lowerRisk?.path ?: return RouteStatus.Failed("route searches disagreed")

        // Core product invariant: a published route must never cross a committed block. A* excludes
        // blocked segments, so this should never fire for either route; if it ever does, surface it
        // instead of drawing a route over a red mark.
        for (path in listOf(shortest.path, lowerRiskPath)) {
            val blockedHit = path.segmentIds.firstOrNull {
                input.snapshot.blocker.isBlockedSegment(it.value)
            }
            if (blockedHit != null) {
                Log.e(TAG, "ROUTE INVARIANT VIOLATED: crosses blocked ${blockedHit.stableId}")
                return RouteStatus.Failed("route crosses a blocked segment")
            }
        }

        val priced = input.lowerRiskRequest.policy.segmentPenaltyMeters.keys
        return RouteStatus.Ready(
            RouteOptions.of(
                shortest = OfferedRoute(shortest.path, shortest.path.crossesReportedRisk(priced)),
                lowerRisk = OfferedRoute(lowerRiskPath, lowerRiskPath.crossesReportedRisk(priced)),
            ),
        )
    }

    /**
     * True when a route traverses a segment some report priced as risky.
     *
     * Read from the risk policy's own map, so this is exactly the set of segments the risk search
     * was willing to pay to avoid — not a second, independently derived opinion about what counts
     * as reported risk.
     */
    private fun RoutePath.crossesReportedRisk(pricedSegments: Set<SegmentId>): Boolean =
        segmentIds.any { it in pricedSegments }

    /** Both searches for one decision, from one snapshot. */
    private data class RouteSearchResults(
        val shortest: RouteResult,
        val lowerRisk: RouteResult,
    )

    private fun describe(status: RouteStatus): String = when (status) {
        is RouteStatus.Ready -> when (val options = status.options) {
            is RouteOptions.Identical ->
                "identical distance=${"%.0f".format(options.only.route.distanceMeters)}m " +
                    "segments=${options.only.route.segmentIds.size} " +
                    "crossesReportedRisk=${options.only.crossesReportedRisk}"
            is RouteOptions.Divergent ->
                "divergent shortest=${"%.0f".format(options.shortest.route.distanceMeters)}m " +
                    "(risk=${options.shortest.crossesReportedRisk}) " +
                    "lowerRisk=${"%.0f".format(options.lowerRisk.route.distanceMeters)}m " +
                    "(risk=${options.lowerRisk.crossesReportedRisk})"
        }
        RouteStatus.Idle -> "idle"
        RouteStatus.Calculating -> "calculating"
        RouteStatus.NoRoute -> "no route"
        is RouteStatus.Failed -> "failed: ${status.message}"
    }

    // ------------------------------------------------- location-assisted report

    /**
     * Offers the road the person appears to be standing on, for confirmation (XPH-90).
     *
     * Nothing is selected and nothing is written here: the suggestion is published to the state and
     * the person either accepts it, picks a different road, or long-presses instead. Every failure —
     * permission, no fix, provider error, no road within range — leaves the manual path untouched and
     * says what happened.
     */
    fun reportHere() {
        if (_uiState.value.hazardState !is HazardState.Ready) return
        viewModelScope.launch {
            _uiState.update { it.copy(locationSuggestion = null, locationRefusal = null) }
            when (val outcome = locationSource.lastKnownFix()) {
                is LocationOutcome.Fix -> offerSnap(outcome.fix)
                LocationOutcome.PermissionDenied -> refuse(LocationRefusal.PERMISSION_DENIED)
                LocationOutcome.NoFix -> refuse(LocationRefusal.NO_FIX)
                is LocationOutcome.Failed -> refuse(LocationRefusal.PROVIDER_FAILED)
            }
        }
    }

    /**
     * The coordinate the camera should start on, or null to stay on the district (XPH-92).
     *
     * Null for every failure — no permission, no fix, a provider error — and for a fix outside the
     * loaded dataset, because "where you are" is only a useful starting point when the roads you are
     * standing on are in the map. See [MapCentring] for why refusing is the safe direction.
     */
    suspend fun locatedStart(): GeoCoordinate? {
        val dataset = (mapRepository.state.value as? DatasetState.Ready)?.dataset ?: return null
        val fix = when (val outcome = locationSource.lastKnownFix()) {
            is LocationOutcome.Fix -> outcome.fix.coordinate
            LocationOutcome.PermissionDenied, LocationOutcome.NoFix, is LocationOutcome.Failed -> return null
        }
        val graph = dataset.graph
        return fix.takeIf {
            MapCentring.isInsideDataset(
                coordinate = it,
                southLatitude = graph.southLatitude,
                westLongitude = graph.westLongitude,
                northLatitude = graph.northLatitude,
                eastLongitude = graph.eastLongitude,
            )
        }
    }

    private suspend fun offerSnap(fix: LocationFix) {
        val index = (mapRepository.state.value as? DatasetState.Ready)?.dataset?.spatialIndex
            ?: return
        when (val snap = SegmentSnapper.snap(index, fix.coordinate, fix.accuracyMeters)) {
            is SegmentSnap.Suggest -> _uiState.update {
                it.copy(
                    locationSuggestion = LocationSuggestion(
                        segmentId = snap.best.segmentId,
                        distanceMeters = snap.best.distanceMeters,
                        accuracyMeters = snap.accuracyMeters,
                        concerns = snap.concerns,
                        alternatives = snap.alternatives,
                    ),
                    locationRefusal = null,
                )
            }
            SegmentSnap.NoRoadNearby -> refuse(LocationRefusal.NO_ROAD_NEARBY)
        }
    }

    /**
     * Accepts the suggestion, which is what turns it into a selection.
     *
     * The report itself still goes through the picker and the single ingress; accepting a location
     * chooses a *road*, never a depth and never a verdict.
     */
    fun confirmLocationSuggestion() {
        val suggestion = _uiState.value.locationSuggestion ?: return
        updateSelection(suggestion.segmentId)
        clearFeedback()
    }

    /** Declines the suggestion and leaves the person to long-press the road they meant. */
    fun dismissLocationSuggestion() {
        clearFeedback()
    }

    private fun updateSelection(segmentId: SegmentId) {
        local.update {
            it.copy(highlightedSegment = segmentId, ambiguousCandidates = emptyList(), reportError = null)
        }
    }

    private fun clearFeedback() {
        _uiState.update { it.copy(locationSuggestion = null, locationRefusal = null) }
    }

    /** Publishes a refusal. The wording is the UI's, from `strings.xml`. */
    private fun refuse(refusal: LocationRefusal) {
        _uiState.update { it.copy(locationSuggestion = null, locationRefusal = refusal) }
    }

    // ------------------------------------------------------------ interaction

    fun onDestinationSelected(shelterIndex: Int) {
        savedStateHandle[KEY_DESTINATION] = shelterIndex
        selection.update { it.copy(destinationShelterIndex = shelterIndex) }
    }

    fun onStartSelected(shelterIndex: Int) {
        savedStateHandle[KEY_START] = shelterIndex
        selection.update { it.copy(startShelterIndex = shelterIndex) }
    }

    fun onSegmentSelected(segmentId: SegmentId) {
        local.update {
            it.copy(highlightedSegment = segmentId, ambiguousCandidates = emptyList(), reportError = null)
        }
    }

    fun onAmbiguousSelection(candidates: List<SegmentId>) {
        local.update {
            it.copy(highlightedSegment = null, ambiguousCandidates = candidates, reportError = null)
        }
    }

    fun onSelectionCleared() {
        local.update { it.copy(highlightedSegment = null, ambiguousCandidates = emptyList()) }
    }

    fun dismissReportError() {
        local.update { it.copy(reportError = null) }
    }

    /**
     * Submits a mark for the highlighted segment through the single repository
     * ingress. The pending marker is shown first; it is replaced by the committed
     * block (via the hazard StateFlow) only after the repository confirms the
     * write, and on failure it becomes an error — never a committed mark.
     */
    /**
     * Reports the highlighted segment with the two axes the observer chose (XPH-70).
     *
     * The verdict is what decides whether the road is excluded; the depth is what the observer saw.
     * An [WaterLevelCategory.UNKNOWN] depth states no claim and never excludes anything by itself.
     */
    fun reportHighlightedSegment(
        waterLevel: WaterLevelCategory,
        passability: PassabilityStatus,
    ) {
        // Read the latest local highlight rather than the derived UI state, which
        // is updated asynchronously by the state collector.
        val segment = local.value.highlightedSegment ?: return
        submitReport(segment, RecordRoadReport.reasonFor(passability)) {
            recordRoadReport(segment, waterLevel, passability)
        }
    }

    /**
     * True when the chosen pair is a report at all.
     *
     * A pair that states nothing on either axis carries no information and is refused by the
     * ingress (ADR 0002 R1), so the UI must not offer to submit it; and `ABOVE_WAIST` with
     * `PASSABLE_REPORTED` is the documented contradiction (R2), rejected rather than rewritten.
     * Asking the ingress makes this the same rule the repository will apply, not a copy of it.
     */
    fun isReportable(waterLevel: WaterLevelCategory, passability: PassabilityStatus): Boolean =
        RoadObservation.create(HazardReason.WATERLOGGED, waterLevel, passability) is ObservationValidation.Valid

    /**
     * Debug/test hook: commits the same kind of report a long-press would, using
     * a segment from the displayed route (or the first known segment).
     *
     * Exists so the end-to-end path — repository commit, persist-before-send,
     * peer transfer, remote commit and reroute — can be exercised without the
     * map gesture. It fakes nothing: the report goes through the real
     * HazardRepository and the real coordinator; only the gesture is skipped.
     */
    fun injectDemoBlock(reason: HazardReason = HazardReason.BLOCKED): SegmentId? {
        if (_uiState.value.hazardState !is HazardState.Ready) return null
        val state = _uiState.value
        val route = state.displayedRoute?.takeIf { state.displayedRouteUsable }
        val segment = route?.segmentIds?.getOrNull(route.segmentIds.size / 2)
            ?: state.committedBlockedSegments.firstOrNull()
            ?: SegmentId(0)
        // Deliberately the v1 shortcut, unchanged: the device evidence scripts tap this hook and
        // depend on a v1-shaped row (unknown depth, impassable verdict, the stated reason).
        submitReport(segment, reason) { hazardRepository.blockLocal(segment, reason) }
        return segment
    }

    /**
     * The one report state machine: mark pending, commit, then clear, fail or reject.
     *
     * [reason] is only the provenance shown while pending; [commit] performs the write. Both entry
     * points — the picker and the debug hook — share this, so "the pending-to-committed marker
     * behaviour is unchanged" is literally one code path rather than two that agree today.
     */
    private fun submitReport(
        segment: SegmentId,
        reason: HazardReason,
        commit: suspend () -> MutationResult,
    ) {
        local.update {
            it.copy(
                highlightedSegment = null,
                ambiguousCandidates = emptyList(),
                pendingReport = PendingReport.Saving(segment, reason),
                reportError = null,
            )
        }
        viewModelScope.launch {
            when (val result = commit()) {
                is MutationResult.Accepted -> {
                    local.update { it.copy(pendingReport = null) }
                    // Persist-before-send: only committed events go to peers.
                    onLocalCommitted(result.event)
                }
                MutationResult.Duplicate -> {
                    local.update { it.copy(pendingReport = null) }
                }
                is MutationResult.Rejected -> {
                    local.update {
                        it.copy(
                            pendingReport = PendingReport.Failed(segment, reason, result.reason),
                            reportError = "Report rejected — ${result.reason}",
                        )
                    }
                }
                is MutationResult.PersistenceFailure -> {
                    local.update {
                        it.copy(
                            pendingReport = PendingReport.Failed(segment, reason, result.message),
                            reportError = "Report not saved — ${result.message}",
                        )
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------- internals

    private data class Selection(
        val startShelterIndex: Int?,
        val destinationShelterIndex: Int?,
    )

    private data class LocalOverlay(
        val highlightedSegment: SegmentId? = null,
        val ambiguousCandidates: List<SegmentId> = emptyList(),
        val pendingReport: PendingReport? = null,
        val reportError: String? = null,
    )

    private data class Frame(
        val dataset: DatasetState,
        val hazard: HazardState,
        val selection: Selection,
        val local: LocalOverlay,
    )

    companion object {
        private const val TAG = "ResQRouteRoute"

        /** One shared incident for the demo; Phase 8 negotiates it on the wire. */
        const val INCIDENT_ID = SyncConfig.INCIDENT_ID

        internal const val KEY_START = "startShelterIndex"
        internal const val KEY_DESTINATION = "destinationShelterIndex"

        /** Default start when nothing is saved: the first shelter access node. */
        private const val DEFAULT_START_SHELTER = 0

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MapViewModel(
                    mapRepository = container.mapRepository,
                    hazardRepository = container.hazardRepository,
                    routingEngine = AStarRoutingEngine(),
                    savedStateHandle = createSavedStateHandle(),
                    computeDispatcher = Dispatchers.Default,
                    onLocalCommitted = { container.syncCoordinator.submitLocal(it) },
                    // The real last-known-location source, wired here rather than inside the
                    // ViewModel so tests never touch the location framework.
                    locationSource = container.locationSource,
                )
            }
        }
    }

    /** Applies the default start once a dataset is available, if none was saved. */
    fun ensureStart(dataset: MapDataset) {
        if (savedStateHandle.get<Int>(KEY_START) == null && dataset.shelters.isNotEmpty()) {
            onStartSelected(DEFAULT_START_SHELTER)
        }
    }
}
