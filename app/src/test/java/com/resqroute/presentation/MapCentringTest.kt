package com.resqroute.presentation

import com.resqroute.core.model.GeoCoordinate
import com.resqroute.feature.map.presentation.MapCentring
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether the map starts on the person or on the district (XPH-92).
 *
 * The negative cases carry the weight here. Centring on a fix that is **not** in the dataset puts the
 * camera over empty space — no roads, no shelters, nothing to route to — and the person has no way to
 * tell that the map is looking somewhere they are not. That failure is silent, which is why the rule
 * refuses instead.
 *
 * The bounds used below are the real bundled dataset's extent, so the fixtures describe the map the
 * app actually ships.
 */
class MapCentringTest {

    // The Feni dataset extent, from the bundled manifest.
    private val south = 22.6881785
    private val west = 91.1995937
    private val north = 23.3363632
    private val east = 91.634685

    private fun inside(latitude: Double, longitude: Double) = MapCentring.isInsideDataset(
        coordinate = GeoCoordinate(latitude, longitude),
        southLatitude = south,
        westLongitude = west,
        northLatitude = north,
        eastLongitude = east,
    )

    @Test
    fun aFixInFeniIsAccepted() {
        // Feni town, inside the graph.
        assertTrue(inside(23.0159, 91.3976))
    }

    @Test
    fun aFixJustOutsideTheGraphExtentIsStillAccepted() {
        // The graph's bounding box is the road extent, not the district border, and roads run past
        // it: a fix a few kilometres beyond is still at the incident rather than in another district.
        assertTrue("just south of the box", inside(south - MapCentring.MARGIN_DEGREES / 2, 91.4))
        assertTrue("just east of the box", inside(23.0, east + MapCentring.MARGIN_DEGREES / 2))
    }

    @Test
    fun aFixBeyondTheMarginIsRefused() {
        assertFalse("further south than the margin allows", inside(south - MapCentring.MARGIN_DEGREES * 2, 91.4))
        assertFalse("further east than the margin allows", inside(23.0, east + MapCentring.MARGIN_DEGREES * 2))
        assertFalse("north of the box", inside(north + MapCentring.MARGIN_DEGREES * 2, 91.4))
        assertFalse("west of the box", inside(23.0, west - MapCentring.MARGIN_DEGREES * 2))
    }

    @Test
    fun aFixInAnotherCityOrContinentIsRefused() {
        // The three that actually happen: a phone in Dhaka, a handed-over device, and an emulator
        // whose GPS defaults to the Googleplex.
        assertFalse("Dhaka", inside(23.8103, 90.4125))
        assertFalse("Chattogram", inside(22.3569, 91.7832))
        assertFalse("emulator default", inside(37.421998, -122.084))
    }

    @Test
    fun anAbsurdCoordinateCannotEvenReachTheCentringRule() {
        // The first guard is the type: GeoCoordinate refuses anything that is not a latitude or a
        // longitude, so a NaN or infinite fix cannot exist by the time the camera is asked to move.
        // Asserting that here records where the protection lives, instead of duplicating it.
        assertThrows(IllegalArgumentException::class.java) { GeoCoordinate(Double.NaN, 91.4) }
        assertThrows(IllegalArgumentException::class.java) { GeoCoordinate(23.0, Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { GeoCoordinate(91.0, 91.4) }
        assertThrows(IllegalArgumentException::class.java) { GeoCoordinate(23.0, 181.0) }

        // And a syntactically valid but meaningless coordinate — null island, where a fresh GPS or a
        // sentinel value often lands — is refused by the bounds rule itself.
        assertFalse("null island", inside(0.0, 0.0))
    }

    @Test
    fun theStreetZoomIsCloserThanTheDistrictDefault() {
        // Starting on the person is only useful if a road can be selected where they landed: at the
        // district camera a long-press in dense areas comes back "Ambiguous: N roads overlap".
        assertTrue(
            "street zoom must be closer than the district default of 11.5",
            MapCentring.STREET_ZOOM > 11.5,
        )
        assertTrue("and within the map's 6..20 clamp", MapCentring.STREET_ZOOM in 6.0..20.0)
    }
}
