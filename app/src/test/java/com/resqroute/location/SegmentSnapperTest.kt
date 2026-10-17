package com.resqroute.location

import com.resqroute.core.graph.SegmentHit
import com.resqroute.core.graph.SegmentSpatialIndex
import com.resqroute.core.model.GeoCoordinate
import com.resqroute.core.model.SegmentId
import com.resqroute.domain.location.SegmentSnap
import com.resqroute.domain.location.SegmentSnapper
import com.resqroute.domain.location.SnapConcern
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Snapping a fix to a road (XPH-90), as a pure function.
 *
 * Six cases from the issue's test plan, each a different answer: a good fix, a fix far from any road,
 * an ambiguous match, a fix whose own accuracy is poor, and nothing within range at all. The row that
 * matters most is that a doubtful snap is **offered with its doubt** — never dropped silently, never
 * presented as the road.
 */
class SegmentSnapperTest {

    private val here = GeoCoordinate(23.0, 91.4)

    @Test
    fun aGoodFixOnALoneRoadSuggestsItWithoutReservation() = runBlocking {
        val snap = snapWith(hits(12.0 to 100), accuracyMeters = 8.0)

        val suggest = snap as SegmentSnap.Suggest
        assertEquals(SegmentId(100), suggest.best.segmentId)
        assertEquals(12.0, suggest.best.distanceMeters, 1e-9)
        assertEquals(8.0, suggest.accuracyMeters!!, 1e-9)
        assertTrue("a lone road at a comfortable distance carries no doubt", suggest.concerns.isEmpty())
        assertTrue(suggest.alternatives.isEmpty())
    }

    @Test
    fun aFixFarFromTheNearestRoadIsOfferedWithThatConcern() = runBlocking {
        val snap = snapWith(hits(90.0 to 7), accuracyMeters = 10.0)

        val suggest = snap as SegmentSnap.Suggest
        assertEquals(SegmentId(7), suggest.best.segmentId)
        assertEquals(setOf(SnapConcern.FAR_FROM_ROAD), suggest.concerns)
    }

    @Test
    fun twoRoadsJustAsCloseAreAmbiguousRatherThanAChoiceTheAppMakes() = runBlocking {
        // 10 m and 14 m: inside the ambiguity margin, so this must not be resolved silently.
        val snap = snapWith(hits(10.0 to 1, 14.0 to 2), accuracyMeters = 5.0)

        val suggest = snap as SegmentSnap.Suggest
        assertEquals(SegmentId(1), suggest.best.segmentId)
        assertEquals(setOf(SnapConcern.AMBIGUOUS), suggest.concerns)
        assertEquals("the other road is offered, not discarded", listOf(SegmentId(2)), suggest.alternatives)
    }

    @Test
    fun aSecondRoadOutsideTheMarginIsNotAmbiguity() = runBlocking {
        val snap = snapWith(hits(10.0 to 1, 60.0 to 2), accuracyMeters = 5.0)
        val suggest = snap as SegmentSnap.Suggest
        assertTrue("60 m away is a different road, not a tie", suggest.concerns.isEmpty())
    }

    @Test
    fun aFixWithPoorAccuracyCarriesThatConcernEvenOnALoneNearbyRoad() = runBlocking {
        val snap = snapWith(hits(5.0 to 3), accuracyMeters = SegmentSnapper.TRUSTED_ACCURACY_METERS + 1)
        val suggest = snap as SegmentSnap.Suggest
        assertEquals(setOf(SnapConcern.POOR_ACCURACY), suggest.concerns)
    }

    @Test
    fun aFixWithNoReportedAccuracyIsNotTreatedAsPerfect() = runBlocking {
        // Accuracy unknown is not accuracy zero: the snap is offered with no accuracy line and no
        // accuracy concern, and the UI simply says less.
        val snap = snapWith(hits(5.0 to 4), accuracyMeters = null)
        val suggest = snap as SegmentSnap.Suggest
        assertEquals(null, suggest.accuracyMeters)
        assertTrue(suggest.concerns.isEmpty())
    }

    @Test
    fun severalConcernsAreAllReportedTogether() = runBlocking {
        val snap = snapWith(hits(80.0 to 5, 82.0 to 6), accuracyMeters = 200.0)
        val suggest = snap as SegmentSnap.Suggest
        assertEquals(
            setOf(SnapConcern.FAR_FROM_ROAD, SnapConcern.AMBIGUOUS, SnapConcern.POOR_ACCURACY),
            suggest.concerns,
        )
    }

    @Test
    fun noRoadWithinRangeIsNotAGuess() = runBlocking {
        assertEquals(SegmentSnap.NoRoadNearby, snapWith(emptyList(), accuracyMeters = 5.0))
    }

    @Test
    fun theSearchRadiusIsTheBoundaryThatDecidesThereIsNothingToOffer() = runBlocking {
        // The fake index enforces the radius, so what this pins is that the snapper asks for exactly
        // the documented radius - a wider query would be a guess at a road the person is not on.
        val index = RecordingIndex(hits(10.0 to 1))
        SegmentSnapper.snap(index, here, accuracyMeters = 5.0)
        assertEquals(SegmentSnapper.SEARCH_RADIUS_METERS, index.lastRadius!!, 1e-9)
        assertTrue("a small candidate limit keeps the query cheap", index.lastLimit!! in 2..16)
    }

    // -------------------------------------------------------------- fixture

    private fun hits(vararg entries: Pair<Double, Int>): List<SegmentHit> = entries
        .map { (distance, segment) -> SegmentHit(SegmentId(segment), here, distance) }
        .sortedBy { it.distanceMeters }

    private suspend fun snapWith(hits: List<SegmentHit>, accuracyMeters: Double?): SegmentSnap =
        SegmentSnapper.snap(FixedIndex(hits), here, accuracyMeters)

    private class FixedIndex(private val hits: List<SegmentHit>) : SegmentSpatialIndex {
        override suspend fun nearestSegment(coordinate: GeoCoordinate, maximumDistanceMeters: Double) =
            nearbySegments(coordinate, maximumDistanceMeters, 1).firstOrNull()

        override suspend fun nearbySegments(
            coordinate: GeoCoordinate,
            maximumDistanceMeters: Double,
            limit: Int,
        ): List<SegmentHit> = hits.filter { it.distanceMeters <= maximumDistanceMeters }.take(limit)
    }

    /** Remembers how the snapper asked, so the query itself can be asserted. */
    private class RecordingIndex(private val hits: List<SegmentHit>) : SegmentSpatialIndex {
        var lastRadius: Double? = null
        var lastLimit: Int? = null

        override suspend fun nearestSegment(coordinate: GeoCoordinate, maximumDistanceMeters: Double) =
            nearbySegments(coordinate, maximumDistanceMeters, 1).firstOrNull()

        override suspend fun nearbySegments(
            coordinate: GeoCoordinate,
            maximumDistanceMeters: Double,
            limit: Int,
        ): List<SegmentHit> {
            lastRadius = maximumDistanceMeters
            lastLimit = limit
            return hits.take(limit)
        }
    }
}
