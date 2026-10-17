package com.resqroute.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.resqroute.core.model.DatasetIdentity
import com.resqroute.core.model.SegmentId
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.HazardState
import com.resqroute.domain.map.DatasetState
import com.resqroute.domain.map.MapDataset
import com.resqroute.dataset.NodeSpec
import com.resqroute.dataset.SegmentSpec
import com.resqroute.dataset.ShelterSpec
import com.resqroute.dataset.buildTestGraph
import com.resqroute.feature.map.presentation.MapUiState
import com.resqroute.feature.map.presentation.MapViewModel
import com.resqroute.feature.map.presentation.RouteOptions
import com.resqroute.feature.map.presentation.RouteStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 7 acceptance criteria through the real [MapViewModel] with fake
 * repositories and a controllable routing engine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {

    private lateinit var mapRepository: FakeMapRepository
    private lateinit var hazardRepository: FakeHazardRepository
    private lateinit var engine: FakeRoutingEngine

    private val dataset: MapDataset = testMapDataset()

    @Before
    fun setUp() {
        mapRepository = FakeMapRepository()
        hazardRepository = FakeHazardRepository()
        engine = FakeRoutingEngine()
    }

    @After
    fun tearDown() {
        // No global Main dispatcher to reset: each test installs its own.
    }

    private fun testMapDataset(): MapDataset {
        val nodes = listOf(
            NodeSpec(101, 23.0000, 91.4000), // start node
            NodeSpec(202, 23.0010, 91.4010), // destination node
            NodeSpec(303, 23.0020, 91.4020), // second shelter node
        )
        val graph = buildTestGraph(
            nodes = nodes,
            segments = listOf(
                SegmentSpec(0, 1, 100.0, listOf(23.0000 to 91.4000, 23.0010 to 91.4010), name = "direct"),
                SegmentSpec(0, 2, 120.0, listOf(23.0000 to 91.4000, 23.0020 to 91.4020), name = "detour-a"),
                SegmentSpec(2, 1, 120.0, listOf(23.0020 to 91.4020, 23.0010 to 91.4010), name = "detour-b"),
            ),
            identity = DatasetIdentity("viewmodel-test-dataset", 1),
            shelters = listOf(
                ShelterSpec(accessNodeIndex = 0, name = "Start shelter"),
                ShelterSpec(accessNodeIndex = 1, name = "Destination shelter"),
                ShelterSpec(accessNodeIndex = 2, name = "Second shelter"),
            ),
        )
        return MapDataset(
            identity = graph.identity,
            topologyVersion = "test",
            graph = graph,
            shelters = (0 until graph.shelterCount).map { graph.shelter(it) },
            spatialIndex = NoopSpatialIndex,
            hasElevation = false,
        )
    }

    private fun viewModel(
        computeDispatcher: kotlinx.coroutines.CoroutineDispatcher,
        savedState: SavedStateHandle = SavedStateHandle(),
    ) = MapViewModel(
        mapRepository = mapRepository,
        hazardRepository = hazardRepository,
        routingEngine = engine,
        savedStateHandle = savedState,
        computeDispatcher = computeDispatcher,
    )

    private suspend fun MapViewModel.awaitState(predicate: (MapUiState) -> Boolean): MapUiState =
        uiState.first(predicate)

    @Test
    fun computesRouteOnceDatasetHazardAndDestinationAreReady() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel(UnconfinedTestDispatcher(testScheduler))
            mapRepository.set(DatasetState.Ready(dataset, coldLoadMillis = 5))
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue("expected Ready but was ${state.routeStatus}", state.routeStatus is RouteStatus.Ready)
            // The pair is published as one unit; the distance-preferred option is the route this
            // app has always drawn, so the assertions below are unchanged in substance.
            val path = (state.routeStatus as RouteStatus.Ready).options.offered.first().route
            assertEquals(listOf(SegmentId(FakeRoutingEngine.DIRECT_SEGMENT)), path.segmentIds)
            assertTrue(state.routingReady)
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun noRouteIsComputedWhileHazardTruthIsRestoring() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel(UnconfinedTestDispatcher(testScheduler))
            hazardRepository.holdRestoring = true
            mapRepository.set(DatasetState.Ready(dataset, coldLoadMillis = 5))
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()

            // The dataset is ready but hazard truth is still restoring, so routing
            // must not run against a temporary empty blocked set.
            assertEquals(RouteStatus.Idle, viewModel.uiState.value.routeStatus)
            assertTrue(engine.requests.isEmpty())
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aCommittedRemoteBlockReroutesAutomatically() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel(UnconfinedTestDispatcher(testScheduler))
            mapRepository.set(DatasetState.Ready(dataset, coldLoadMillis = 5))
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()
            assertEquals(
                listOf(SegmentId(FakeRoutingEngine.DIRECT_SEGMENT)),
                (viewModel.uiState.value.routeStatus as RouteStatus.Ready).options.offered.first().route.segmentIds,
            )

            // A peer report arrives through the same repository ingress.
            hazardRepository.simulateRemoteBlock(SegmentId(FakeRoutingEngine.DIRECT_SEGMENT))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.routeStatus is RouteStatus.Ready)
            val path = (state.routeStatus as RouteStatus.Ready).options.offered.first().route
            assertEquals(listOf(SegmentId(FakeRoutingEngine.DETOUR_SEGMENT)), path.segmentIds)
            assertFalse(path.segmentIds.any { it.value == FakeRoutingEngine.DIRECT_SEGMENT })
            assertEquals(1L, path.hazardRevision)
            // The receiving device displays exactly that rerouted path as valid.
            assertEquals(path, state.displayedRoute)
            assertTrue(state.displayedRouteUsable)
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aRouteIsNeverDisplayedAsValidWhileItIsBeingRecalculated() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel(UnconfinedTestDispatcher(testScheduler))
            mapRepository.set(DatasetState.Ready(dataset, coldLoadMillis = 5))
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()
            val original = viewModel.uiState.value.displayedRoute
            assertTrue(viewModel.uiState.value.displayedRouteUsable)

            // A block arrives while the replacement search is slow: for that
            // window the route on screen must not look valid.
            engine.delayMillis = 1_000
            hazardRepository.simulateRemoteBlock(SegmentId(FakeRoutingEngine.DIRECT_SEGMENT))
            advanceTimeBy(300)
            runCurrent()

            val inFlight = viewModel.uiState.value
            assertEquals(RouteStatus.Calculating, inFlight.routeStatus)
            assertFalse("a superseded route must not be marked usable", inFlight.displayedRouteUsable)
            assertEquals("the old geometry may stay for context", original, inFlight.displayedRoute)

            // Once the guarded result lands, the displayed route is the new one.
            engine.delayMillis = 0
            advanceUntilIdle()

            val settled = viewModel.uiState.value
            assertTrue(settled.routeStatus is RouteStatus.Ready)
            assertTrue(settled.displayedRouteUsable)
            assertFalse(
                "the displayed route must not use the blocked segment",
                settled.displayedRoute!!.segmentIds.any { it.value == FakeRoutingEngine.DIRECT_SEGMENT },
            )
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun noRouteClearsTheDisplayedRouteInsteadOfLeavingItLookingValid() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel(UnconfinedTestDispatcher(testScheduler))
            mapRepository.set(DatasetState.Ready(dataset, coldLoadMillis = 5))
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.displayedRouteUsable)

            // The only remaining way to the shelter is then reported blocked.
            engine.noRoute = true
            hazardRepository.simulateRemoteBlock(SegmentId(FakeRoutingEngine.DIRECT_SEGMENT))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(RouteStatus.NoRoute, state.routeStatus)
            assertNull(state.displayedRoute)
            assertFalse(state.displayedRouteUsable)
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun staleSearchIsSuppressedAndCancellationIsNeverAFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel(UnconfinedTestDispatcher(testScheduler))
            mapRepository.set(DatasetState.Ready(dataset, coldLoadMillis = 5))
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()

            // Make the next search slow, then change the input while it is running.
            engine.delayMillis = 1_000
            hazardRepository.simulateRemoteBlock(SegmentId(FakeRoutingEngine.DIRECT_SEGMENT))
            advanceTimeBy(500) // the slow search is now in-flight
            runCurrent()

            // A newer input arrives before the slow search completes, so the old
            // search is cancelled rather than allowed to publish.
            viewModel.onDestinationSelected(2)
            engine.delayMillis = 0
            advanceUntilIdle()

            val state = viewModel.uiState.value
            val status = state.routeStatus
            assertTrue("expected a route, got $status", status is RouteStatus.Ready)
            // Cancellation was swallowed by the guard, not surfaced as a failure.
            assertFalse(status is RouteStatus.Failed)
            // The destination-2 route uses its own segment, proving the stale
            // destination-1 result never replaced the newer state.
            assertEquals(
                listOf(SegmentId(FakeRoutingEngine.SECOND_DESTINATION_SEGMENT)),
                (status as RouteStatus.Ready).options.offered.first().route.segmentIds,
            )
            // The stale destination-1 result never replaced the displayed route.
            assertEquals(
                listOf(SegmentId(FakeRoutingEngine.SECOND_DESTINATION_SEGMENT)),
                viewModel.uiState.value.displayedRoute!!.segmentIds,
            )
            assertTrue(viewModel.uiState.value.displayedRouteUsable)
            assertEquals(2, viewModel.uiState.value.destinationShelterIndex)
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun pendingMarkThenCommitAndFailureIsNeverCommitted() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel(UnconfinedTestDispatcher(testScheduler))
            mapRepository.set(DatasetState.Ready(dataset, coldLoadMillis = 5))
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()

            // Pending, then committed. The v1 binary choice is expressed as its axes: a v1
            // WATERLOGGED or BLOCKED report is exactly unknown depth + impassable verdict, so these
            // call sites changed only because the API changed - the assertions below are untouched.
            viewModel.onSegmentSelected(SegmentId(FakeRoutingEngine.DIRECT_SEGMENT))
            viewModel.reportHighlightedSegment(WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE)
            advanceUntilIdle()

            val committed = viewModel.uiState.value
            assertTrue(committed.pendingReport == null)
            assertTrue(committed.committedBlockedSegments.contains(SegmentId(FakeRoutingEngine.DIRECT_SEGMENT)))
            assertTrue(committed.routeStatus is RouteStatus.Ready)

            // A failed write leaves no committed mark and reports the error.
            hazardRepository.failNextWrite = true
            viewModel.onSegmentSelected(SegmentId(2))
            viewModel.reportHighlightedSegment(WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE)
            advanceUntilIdle()

            val failed = viewModel.uiState.value
            assertTrue(failed.reportError!!.contains("Report not saved"))
            assertFalse(failed.committedBlockedSegments.contains(SegmentId(2)))
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun savedStateHandleRestoresSelectionOnRecreation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val saved = SavedStateHandle(
                mapOf(
                    MapViewModel.KEY_START to 0,
                    MapViewModel.KEY_DESTINATION to 2,
                ),
            )
            val viewModel = viewModel(UnconfinedTestDispatcher(testScheduler), savedState = saved)
            mapRepository.set(DatasetState.Ready(dataset, coldLoadMillis = 5))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(0, state.startShelterIndex)
            assertEquals(2, state.destinationShelterIndex)
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun hazardUnavailableKeepsMapAndDisablesRouting() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel(UnconfinedTestDispatcher(testScheduler))
            mapRepository.set(DatasetState.Ready(dataset, coldLoadMillis = 5))
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.routeStatus is RouteStatus.Ready)

            hazardRepository.markUnavailable("database corrupt")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.hazardState is HazardState.Unavailable)
            assertFalse(state.committedBlockedSegments.isNotEmpty())
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun initialUiStateHasNoRouteAndNoPendingBlock() {
        val state = MapUiState.Initial
        assertEquals(RouteStatus.Idle, state.routeStatus)
        assertEquals(null, state.pendingReport)
        assertTrue(state.committedBlockedSegments.isEmpty())
    }

    // ------------------------------- comparable routes, published as one unit (XPH-76)

    @Test
    fun aReportedHazardOffersTwoRoutesInsteadOfOneSilentChoice() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel(UnconfinedTestDispatcher(testScheduler))
            mapRepository.set(DatasetState.Ready(dataset, coldLoadMillis = 5))
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()

            // A report that does NOT close the road: before this phase it had no effect at all.
            hazardRepository.simulateReportedRisk(SegmentId(FakeRoutingEngine.DIRECT_SEGMENT))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            val status = state.routeStatus
            assertTrue("expected Ready but was $status", status is RouteStatus.Ready)
            val options = (status as RouteStatus.Ready).options
            assertTrue("expected two routes but was $options", options is RouteOptions.Divergent)
            options as RouteOptions.Divergent

            assertEquals(
                "the distance-preferred route ignores the report",
                listOf(SegmentId(FakeRoutingEngine.DIRECT_SEGMENT)),
                options.shortest.route.segmentIds,
            )
            assertEquals(
                "the lower-risk route avoids it",
                listOf(SegmentId(FakeRoutingEngine.DETOUR_SEGMENT)),
                options.lowerRisk.route.segmentIds,
            )
            assertTrue("the shortest route crosses reported risk", options.shortest.crossesReportedRisk)
            assertFalse("the lower-risk route does not", options.lowerRisk.crossesReportedRisk)
            // Both are offered: the app never hides the alternative behind the risky one.
            assertEquals(2, options.offered.size)
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun bothRoutesComeFromOneSnapshotAndOneGeneration() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel(UnconfinedTestDispatcher(testScheduler))
            mapRepository.set(DatasetState.Ready(dataset, coldLoadMillis = 5))
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()

            engine.requests.clear()
            hazardRepository.simulateReportedRisk(SegmentId(FakeRoutingEngine.DIRECT_SEGMENT))
            advanceUntilIdle()

            // Exactly two searches answered this decision, and they shared a generation.
            assertEquals("expected a pair of searches, got ${engine.requests}", 2, engine.requests.size)
            val (first, second) = engine.requests
            assertEquals("the pair must answer one generation", first.generation, second.generation)
            assertEquals("one search is distance-only", 1, engine.requests.count { it.policy.segmentPenaltyMeters.isEmpty() })
            assertEquals("one search is risk-priced", 1, engine.requests.count { it.policy.segmentPenaltyMeters.isNotEmpty() })

            val options = (viewModel.uiState.value.routeStatus as RouteStatus.Ready).options
            val paths = options.offered.map { it.route }
            assertEquals("both paths carry the request generation", 1, paths.map { it.requestGeneration }.distinct().size)
            assertEquals("both paths carry the hazard revision", 1, paths.map { it.hazardRevision }.distinct().size)
            assertEquals(first.generation, paths.first().requestGeneration)
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun identicalRoutesCollapseToASingleOption() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel(UnconfinedTestDispatcher(testScheduler))
            mapRepository.set(DatasetState.Ready(dataset, coldLoadMillis = 5))
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()

            // No reports at all: the two policies are the same question and the same answer.
            val options = (viewModel.uiState.value.routeStatus as RouteStatus.Ready).options
            assertTrue("expected one route but was $options", options is RouteOptions.Identical)
            options as RouteOptions.Identical
            assertEquals(1, options.offered.size)
            assertEquals(listOf(SegmentId(FakeRoutingEngine.DIRECT_SEGMENT)), options.only.route.segmentIds)
            assertFalse("nothing was reported, so nothing is claimed", options.only.crossesReportedRisk)
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun theOnlyRouteCrossingReportedRiskIsReportedAsSuch() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            // The risk search cannot avoid the report: there is one route and it crosses it.
            engine.policySensitive = false
            val viewModel = viewModel(UnconfinedTestDispatcher(testScheduler))
            mapRepository.set(DatasetState.Ready(dataset, coldLoadMillis = 5))
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()
            hazardRepository.simulateReportedRisk(SegmentId(FakeRoutingEngine.DIRECT_SEGMENT))
            advanceUntilIdle()

            val options = (viewModel.uiState.value.routeStatus as RouteStatus.Ready).options
            assertTrue("expected one route but was $options", options is RouteOptions.Identical)
            options as RouteOptions.Identical
            assertTrue(
                "a single route that crosses reported risk must say so; it must never look clean",
                options.only.crossesReportedRisk,
            )
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aSupersededPairIsNeverPublishedEvenPartially() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel(UnconfinedTestDispatcher(testScheduler))
            mapRepository.set(DatasetState.Ready(dataset, coldLoadMillis = 5))
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()

            // A slow pair for destination 1 is in flight when the destination changes.
            engine.delayMillis = 50
            hazardRepository.simulateReportedRisk(SegmentId(FakeRoutingEngine.DIRECT_SEGMENT))
            advanceTimeBy(10)
            viewModel.onDestinationSelected(2)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue("expected a settled route", state.routeStatus is RouteStatus.Ready)
            val options = (state.routeStatus as RouteStatus.Ready).options
            // Whatever is published belongs entirely to the newest decision.
            for (route in options.offered.map { it.route }) {
                assertEquals("a superseded destination leaked into the published pair", 303L, route.nodeIds.last().value)
            }
            assertEquals(
                "the guard accepts the pair as a unit, so the last two searches are one generation",
                engine.requests.takeLast(2).map { it.generation }.distinct().size,
                1,
            )
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ------------------------------------------- reporting categories (XPH-70)

    @Test
    fun everyCategoryIsSubmittedWithItsOwnAxes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel(UnconfinedTestDispatcher(testScheduler))
            mapRepository.set(DatasetState.Ready(dataset, coldLoadMillis = 5))
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()

            // Every representable pair, through the real report path, one segment each so the
            // submitted axes can be read back per report.
            var segment = 1000
            var checked = 0
            for (level in WaterLevelCategory.entries) {
                for (verdict in PassabilityStatus.entries) {
                    if (!viewModel.isReportable(level, verdict)) continue
                    viewModel.onSegmentSelected(SegmentId(segment))
                    viewModel.reportHighlightedSegment(level, verdict)
                    advanceUntilIdle()
                    segment++
                    checked++
                }
            }
            assertTrue("the sweep must cover the table: $checked", checked >= 28)
            assertEquals(
                "every submitted report must carry exactly the axes it was given",
                checked,
                hazardRepository.recordedReports.size,
            )
            assertTrue(
                "every category must reach the repository",
                WaterLevelCategory.entries.all { level ->
                    hazardRepository.recordedReports.any { it.second == level }
                },
            )
            assertTrue(
                "every passability verdict must reach the repository",
                PassabilityStatus.entries.all { verdict ->
                    hazardRepository.recordedReports.any { it.third == verdict }
                },
            )
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aReportThatStatesNothingIsNeverSubmittedAndTheRoadStaysOpen() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel(UnconfinedTestDispatcher(testScheduler))
            mapRepository.set(DatasetState.Ready(dataset, coldLoadMillis = 5))
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()

            // The documented "states nothing" pair (ADR 0002 R1) is not reportable, so the UI cannot
            // submit it and the ingress would refuse it if it did.
            assertFalse(viewModel.isReportable(WaterLevelCategory.UNKNOWN, PassabilityStatus.UNKNOWN))

            viewModel.onSegmentSelected(SegmentId(900))
            viewModel.reportHighlightedSegment(WaterLevelCategory.UNKNOWN, PassabilityStatus.UNKNOWN)
            advanceUntilIdle()
            assertTrue("nothing may be submitted for an empty report", hazardRepository.recordedReports.isEmpty())
            assertTrue(
                "and nothing may be excluded by it",
                viewModel.uiState.value.committedBlockedSegments.none { it.value == 900 },
            )
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun theContradictoryPairIsRefusedRatherThanRewritten() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel(UnconfinedTestDispatcher(testScheduler))
            mapRepository.set(DatasetState.Ready(dataset, coldLoadMillis = 5))
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()

            // ADR 0002 R2: above-waist depth cannot also be reported passable.
            assertFalse(viewModel.isReportable(WaterLevelCategory.ABOVE_WAIST, PassabilityStatus.PASSABLE_REPORTED))
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun anUnknownDepthWithACautionVerdictDoesNotCloseTheRoad() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = viewModel(UnconfinedTestDispatcher(testScheduler))
            mapRepository.set(DatasetState.Ready(dataset, coldLoadMillis = 5))
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()
            val before = viewModel.uiState.value.committedBlockedSegments

            viewModel.onSegmentSelected(SegmentId(901))
            viewModel.reportHighlightedSegment(WaterLevelCategory.UNKNOWN, PassabilityStatus.CAUTION)
            advanceUntilIdle()

            val after = viewModel.uiState.value
            assertTrue("the report was submitted", hazardRepository.recordedReports.isNotEmpty())
            assertTrue("no error", after.reportError == null)
            assertEquals(
                "a depth-less caution report must not change the excluded set",
                before,
                after.committedBlockedSegments,
            )
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }
}
