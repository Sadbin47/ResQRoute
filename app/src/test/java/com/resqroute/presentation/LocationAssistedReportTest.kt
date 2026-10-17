package com.resqroute.presentation

import com.resqroute.core.graph.SegmentHit
import com.resqroute.core.graph.SegmentSpatialIndex
import com.resqroute.core.model.DatasetIdentity
import com.resqroute.core.model.GeoCoordinate
import com.resqroute.core.model.NodeId
import com.resqroute.core.model.SegmentId
import com.resqroute.data.p2p.PeerMessageCodec
import com.resqroute.dataset.NodeSpec
import com.resqroute.dataset.SegmentSpec
import com.resqroute.dataset.buildTestGraph
import com.resqroute.domain.hazard.MutationResult
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.domain.location.LocationFix
import com.resqroute.domain.location.LocationOutcome
import com.resqroute.domain.location.LocationSource
import com.resqroute.domain.location.SnapConcern
import com.resqroute.domain.map.DatasetState
import com.resqroute.domain.map.MapDataset
import com.resqroute.domain.sync.PeerMessage
import com.resqroute.domain.sync.PeerProtocol
import com.resqroute.feature.map.presentation.LocationRefusal
import com.resqroute.feature.map.presentation.MapViewModel
import com.resqroute.dataset.ShelterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope

/**
 * Location-assisted reporting (XPH-90): the six failure paths, the confirmation step, and the privacy
 * claim that a fix never becomes part of a report.
 *
 * The location provider is a **fake**, so every branch is an ordinary value rather than a framework
 * edge case — including the ones that matter most on a phone: no permission, no fix, and a road the
 * app should not choose on its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationAssistedReportTest {

    private lateinit var mapRepository: FakeMapRepository
    private lateinit var hazardRepository: FakeHazardRepository
    private lateinit var engine: FakeRoutingEngine

    /** A fix that snaps cleanly: 12 m from segment 0. */
    private val goodFix = LocationOutcome.Fix(LocationFix(GeoCoordinate(23.0005, 91.4005), 8.0))

    /** Distinctive coordinates, so a leaked position is unmistakable in encoded bytes. */
    private val distinctiveFix = LocationOutcome.Fix(
        LocationFix(GeoCoordinate(23.456789, 91.987654), 6.0),
    )

    @After
    fun tearDown() {
        // Each test installs and resets its own Main dispatcher.
    }

    // ------------------------------------------------------- the happy path

    @Test
    fun aGoodFixIsOfferedForConfirmationAndNothingIsWrittenYet() = runTest {
        install()
        try {
            val viewModel = viewModel(location = { goodFix })
            mapRepository.set(DatasetState.Ready(dataset(hits = listOf(12.0 to SEGMENT)), coldLoadMillis = 1))
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()

            viewModel.reportHere()
            advanceUntilIdle()

            val suggestion = viewModel.uiState.value.locationSuggestion
            assertTrue("expected a suggestion but was $suggestion", suggestion != null)
            assertEquals(SegmentId(SEGMENT), suggestion!!.segmentId)
            assertEquals(12.0, suggestion.distanceMeters, 1e-9)
            assertEquals(8.0, suggestion.accuracyMeters!!, 1e-9)
            assertTrue(suggestion.concerns.isEmpty())

            // The critical part: offered, not chosen, and nothing written.
            assertNull("a suggestion must not select anything by itself", viewModel.uiState.value.highlightedSegment)
            assertTrue("and nothing may be committed yet", hazardRepository.recordedReports.isEmpty())
            assertNull(viewModel.uiState.value.locationRefusal)
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun confirmingTurnsTheSuggestionIntoASelectionAndThenThePickerWritesIt() = runTest {
        install()
        try {
            val viewModel = viewModel(location = { goodFix })
            mapRepository.set(DatasetState.Ready(dataset(hits = listOf(12.0 to SEGMENT)), coldLoadMillis = 1))
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()

            viewModel.reportHere()
            advanceUntilIdle()
            viewModel.confirmLocationSuggestion()
            advanceUntilIdle()

            assertEquals(SegmentId(SEGMENT), viewModel.uiState.value.highlightedSegment)
            assertNull("the suggestion is spent once accepted", viewModel.uiState.value.locationSuggestion)

            // From here the flow is the ordinary one - the picker and the single ingress.
            viewModel.reportHighlightedSegment(WaterLevelCategory.KNEE, PassabilityStatus.CAUTION)
            advanceUntilIdle()

            assertEquals(
                "the report must carry the snapped segment and its own axes",
                listOf(SegmentId(SEGMENT)),
                hazardRepository.recordedReports.map { it.first },
            )
            assertEquals(WaterLevelCategory.KNEE, hazardRepository.recordedReports.single().second)
            assertEquals(PassabilityStatus.CAUTION, hazardRepository.recordedReports.single().third)
            assertNull(viewModel.uiState.value.reportError)
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun decliningLeavesTheManualPathUntouched() = runTest {
        install()
        try {
            val viewModel = viewModel(location = { goodFix })
            mapRepository.set(DatasetState.Ready(dataset(hits = listOf(12.0 to SEGMENT)), coldLoadMillis = 1))
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()

            viewModel.reportHere()
            advanceUntilIdle()
            viewModel.dismissLocationSuggestion()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.locationSuggestion)
            assertNull(viewModel.uiState.value.highlightedSegment)

            // The long-press path still works, exactly as before.
            viewModel.onSegmentSelected(SegmentId(SEGMENT + 1))
            viewModel.reportHighlightedSegment(WaterLevelCategory.ANKLE, PassabilityStatus.IMPASSABLE)
            advanceUntilIdle()
            assertEquals(listOf(SegmentId(SEGMENT + 1)), hazardRepository.recordedReports.map { it.first })
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ------------------------------------------------------ the failure paths

    @Test
    fun permissionDeniedSaysSoAndTheManualPathStillWorks() = runTest {
        install()
        try {
            val viewModel = viewModel(location = { LocationOutcome.PermissionDenied })
            mapRepository.set(DatasetState.Ready(dataset(hits = listOf(12.0 to SEGMENT)), coldLoadMillis = 1))
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()

            viewModel.reportHere()
            advanceUntilIdle()

            assertEquals(LocationRefusal.PERMISSION_DENIED, viewModel.uiState.value.locationRefusal)
            assertNull(viewModel.uiState.value.locationSuggestion)
            assertTrue("nothing may be written when location is refused", hazardRepository.recordedReports.isEmpty())

            // No crash, and the manual path is unaffected.
            viewModel.onSegmentSelected(SegmentId(SEGMENT))
            viewModel.reportHighlightedSegment(WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE)
            advanceUntilIdle()
            assertEquals(listOf(SegmentId(SEGMENT)), hazardRepository.recordedReports.map { it.first })
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun noFixAndProviderFailureBothFallBackInWords() = runTest {
        install()
        try {
            mapRepository.set(DatasetState.Ready(dataset(hits = listOf(12.0 to SEGMENT)), coldLoadMillis = 1))

            for ((outcome, expected) in listOf(
                LocationOutcome.NoFix to LocationRefusal.NO_FIX,
                LocationOutcome.Failed("provider off") to LocationRefusal.PROVIDER_FAILED,
            )) {
                val viewModel = viewModel(location = { outcome })
                viewModel.onStartSelected(0)
                viewModel.onDestinationSelected(1)
                advanceUntilIdle()

                viewModel.reportHere()
                advanceUntilIdle()
                assertEquals("for $outcome", expected, viewModel.uiState.value.locationRefusal)
                assertNull(viewModel.uiState.value.locationSuggestion)
                assertTrue(hazardRepository.recordedReports.isEmpty())
                viewModel.viewModelScope.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aFixWithNoRoadInRangeOffersNothingRatherThanAGuess() = runTest {
        install()
        try {
            val viewModel = viewModel(location = { goodFix })
            // The index knows about no road at all within the search radius.
            mapRepository.set(DatasetState.Ready(dataset(hits = emptyList()), coldLoadMillis = 1))
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()

            viewModel.reportHere()
            advanceUntilIdle()

            assertEquals(LocationRefusal.NO_ROAD_NEARBY, viewModel.uiState.value.locationRefusal)
            assertNull(viewModel.uiState.value.locationSuggestion)
            assertNull("and nothing is selected on a guess", viewModel.uiState.value.highlightedSegment)
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ------------------------------------------- doubtful snaps are hedged

    @Test
    fun anAmbiguousOrFarSnapIsOfferedWithItsConcernAndItsAlternatives() = runTest {
        install()
        try {
            // 70 m to segment 0, with segment 1 only 4 m further: far AND ambiguous.
            val viewModel = viewModel(location = { goodFix })
            mapRepository.set(
                DatasetState.Ready(dataset(hits = listOf(70.0 to SEGMENT, 74.0 to (SEGMENT + 1))), coldLoadMillis = 1),
            )
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()

            viewModel.reportHere()
            advanceUntilIdle()

            val suggestion = viewModel.uiState.value.locationSuggestion!!
            assertEquals(SegmentId(SEGMENT), suggestion.segmentId)
            assertTrue(SnapConcern.FAR_FROM_ROAD in suggestion.concerns)
            assertTrue(SnapConcern.AMBIGUOUS in suggestion.concerns)
            assertEquals(listOf(SegmentId(SEGMENT + 1)), suggestion.alternatives)
            assertNull("the app still chooses nothing on its own", viewModel.uiState.value.highlightedSegment)
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aPoorFixCarriesItsAccuracyConcern() = runTest {
        install()
        try {
            val viewModel = viewModel(
                location = { LocationOutcome.Fix(LocationFix(GeoCoordinate(23.0005, 91.4005), 400.0)) },
            )
            mapRepository.set(DatasetState.Ready(dataset(hits = listOf(5.0 to SEGMENT)), coldLoadMillis = 1))
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()

            viewModel.reportHere()
            advanceUntilIdle()

            val suggestion = viewModel.uiState.value.locationSuggestion!!
            assertEquals(setOf(SnapConcern.POOR_ACCURACY), suggestion.concerns)
            assertEquals(400.0, suggestion.accuracyMeters!!, 1e-9)
            viewModel.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ------------------------------------------------------------- privacy

    @Test
    fun aLocationAssistedReportCarriesASegmentIdAndNoUserCoordinates() = runTest {
        install()
        try {
            val viewModel = viewModel(location = { distinctiveFix })
            mapRepository.set(DatasetState.Ready(dataset(hits = listOf(6.0 to SEGMENT)), coldLoadMillis = 1))
            viewModel.onStartSelected(0)
            viewModel.onDestinationSelected(1)
            advanceUntilIdle()

            viewModel.reportHere()
            advanceUntilIdle()
            viewModel.confirmLocationSuggestion()
            advanceUntilIdle()
            viewModel.reportHighlightedSegment(WaterLevelCategory.KNEE, PassabilityStatus.AVOID)
            advanceUntilIdle()

            val mutation = hazardRepository.mutations.single()
            assertEquals("the report is about a road", SegmentId(SEGMENT), mutation.segmentId)

            // 1. Nothing on the domain event could carry a position.
            val eventFields = mutation::class.java.declaredFields.map { it.name.lowercase() }
            for (forbidden in listOf("latitude", "longitude", "coordinate", "location", "accuracy")) {
                assertFalse(
                    "the hazard event must have nowhere to put a position: $forbidden in $eventFields",
                    eventFields.any { it.contains(forbidden) },
                )
            }

            // 2. Nor could the stored row.
            val rowFields = com.resqroute.data.local.HazardEventEntity::class.java.declaredFields
                .map { it.name.lowercase() }
            for (forbidden in listOf("latitude", "longitude", "coordinate", "position")) {
                assertFalse("the stored row must have nowhere to put a position: $forbidden", rowFields.any { it.contains(forbidden) })
            }

            // 3. And the bytes that would go to a peer contain no trace of the fix. This is the
            //    assertion that fails if anybody later plumbs a fix into the event.
            val payload = PeerMessageCodec.encode(PeerMessage.EventBatch(listOf(mutation))).decodeToString()
            assertTrue("the report must travel", payload.contains(mutation.eventId.value))
            for (leak in listOf("23.45", "91.98", "23.456789", "91.987654")) {
                assertFalse("a coordinate reached the wire: $leak", payload.contains(leak))
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun locationAssistanceAddsNothingToTheProtocol() {
        // A report produced with a fix travels as the same event as one typed by hand: no new field,
        // and the version is the one XPH-78 raised for the observation axes - nothing about location.
        assertEquals(2, PeerProtocol.VERSION)
    }

    // -------------------------------------------------------------- fixture

    private fun install() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mapRepository = FakeMapRepository()
        hazardRepository = FakeHazardRepository()
        engine = FakeRoutingEngine()
    }

    private fun viewModel(location: LocationSource) = MapViewModel(
        mapRepository = mapRepository,
        hazardRepository = hazardRepository,
        routingEngine = engine,
        savedStateHandle = SavedStateHandle(),
        computeDispatcher = UnconfinedTestDispatcher(),
        locationSource = location,
    )

    /** A dataset whose spatial index answers with the given distances from the fix. */
    private fun dataset(hits: List<Pair<Double, Int>>): MapDataset {
        val nodes = listOf(
            NodeSpec(101, 23.0000, 91.4000),
            NodeSpec(202, 23.0010, 91.4010),
        )
        val graph = buildTestGraph(
            nodes = nodes,
            segments = listOf(
                SegmentSpec(0, 1, 100.0, listOf(23.0000 to 91.4000, 23.0010 to 91.4010), name = "direct"),
                SegmentSpec(0, 1, 120.0, listOf(23.0000 to 91.4000, 23.0010 to 91.4010), name = "alt"),
            ),
            identity = DatasetIdentity("location-test-dataset", 1),
            shelters = listOf(
                ShelterSpec(accessNodeIndex = 0, name = "Start"),
                ShelterSpec(accessNodeIndex = 1, name = "Destination"),
            ),
        )
        return MapDataset(
            identity = graph.identity,
            topologyVersion = "test",
            graph = graph,
            shelters = (0 until graph.shelterCount).map { graph.shelter(it) },
            spatialIndex = FixedIndex(hits),
            hasElevation = false,
        )
    }

    private class FixedIndex(private val hits: List<Pair<Double, Int>>) : SegmentSpatialIndex {
        override suspend fun nearestSegment(coordinate: GeoCoordinate, maximumDistanceMeters: Double) =
            nearbySegments(coordinate, maximumDistanceMeters, 1).firstOrNull()

        override suspend fun nearbySegments(
            coordinate: GeoCoordinate,
            maximumDistanceMeters: Double,
            limit: Int,
        ): List<SegmentHit> = hits
            .filter { it.first <= maximumDistanceMeters }
            .sortedBy { it.first }
            .map { (distance, segment) -> SegmentHit(SegmentId(segment), coordinate, distance) }
            .take(limit)
    }

    private companion object {
        const val SEGMENT = 0
    }
}
