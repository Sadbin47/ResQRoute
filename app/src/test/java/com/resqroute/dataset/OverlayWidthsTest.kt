package com.resqroute.dataset

import com.resqroute.feature.map.ui.OverlayWidths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stroke-width policy for the route and hazard layers.
 *
 * The rule that matters: at equal zoom a committed block is always drawn wider
 * than the route, so the red mark is visible wherever the line and the mark
 * share geometry — the green line can never paint over a blocked road.
 */
class OverlayWidthsTest {

    private val zooms = listOf(6.0, 8.0, 11.5, 12.0, 13.0, 14.0, 15.0, 16.0, 18.0, 20.0)

    @Test
    fun routeWidthIsClampedAtBothEnds() {
        assertEquals(OverlayWidths.ROUTE_MIN_DP, OverlayWidths.routeDp(6.0), 1e-6f)
        assertEquals(OverlayWidths.ROUTE_MIN_DP, OverlayWidths.routeDp(11.5), 1e-6f)
        assertEquals(OverlayWidths.ROUTE_MAX_DP, OverlayWidths.routeDp(16.0), 1e-6f)
        assertEquals(OverlayWidths.ROUTE_MAX_DP, OverlayWidths.routeDp(20.0), 1e-6f)
    }

    @Test
    fun routeGetsThickerAsTheCameraZoomsIn() {
        var previous = 0f
        for (zoom in zooms) {
            val width = OverlayWidths.routeDp(zoom)
            assertTrue("width decreased at zoom $zoom", width >= previous)
            previous = width
        }
    }

    @Test
    fun blockedIsAlwaysWiderThanTheRouteAtTheSameZoom() {
        for (zoom in zooms) {
            val route = OverlayWidths.routeDp(zoom)
            val blocked = OverlayWidths.blockedDp(zoom)
            assertTrue(
                "blocked $blocked must exceed route $route at zoom $zoom",
                blocked > route + 1.0f,
            )
        }
    }

    @Test
    fun aReportedRoadIsVisibleButQuieterThanABlock() {
        // XPH-92. A road that was reported and is still usable must be visible over the route line,
        // and must not shout as loudly as a road that is being blocked: the whole ordering
        // route < reported < pending < blocked is what keeps the map readable at a glance.
        for (zoom in listOf(6.0, 11.5, 14.5, 18.0, 20.0)) {
            val route = OverlayWidths.routeDp(zoom)
            val reported = OverlayWidths.reportedDp(zoom)
            val pending = OverlayWidths.pendingDp(zoom)
            val blocked = OverlayWidths.blockedDp(zoom)

            assertTrue("reported must be wider than the route at zoom $zoom", reported > route)
            assertTrue("reported must be thinner than a pending mark at $zoom", reported < pending)
            assertTrue("reported must be thinner than a block at $zoom", reported < blocked)
        }
    }

    @Test
    fun pendingSitsBetweenTheRouteAndACommittedBlock() {
        for (zoom in zooms) {
            val route = OverlayWidths.routeDp(zoom)
            val pending = OverlayWidths.pendingDp(zoom)
            val blocked = OverlayWidths.blockedDp(zoom)
            assertTrue("pending must exceed route at zoom $zoom", pending > route)
            assertTrue("blocked must exceed pending at zoom $zoom", blocked > pending)
        }
    }

    @Test
    fun candidatesAndSelectionNeverHideTheBlockedStroke() {
        for (zoom in zooms) {
            // Candidate and selection casing are drawn first (below the committed
            // block), so only their ordering matters; assert the intended sizes.
            assertTrue(OverlayWidths.CANDIDATE_DP < OverlayWidths.SELECTION_DP)
            assertTrue(OverlayWidths.CANDIDATE_DP < OverlayWidths.blockedDp(zoom))
        }
    }
}
