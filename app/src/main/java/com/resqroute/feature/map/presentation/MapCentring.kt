package com.resqroute.feature.map.presentation

import com.resqroute.core.model.GeoCoordinate

/**
 * Where the camera should start, and the rule that keeps "where you are" from becoming a blank map
 * (XPH-92).
 *
 * ## The rule
 *
 * The map centres on the person's last known fix **only when that fix is plausibly inside the loaded
 * dataset**; otherwise it stays on the district it actually contains.
 *
 * That second half is the important one. The bundled graph is Feni, so a fix anywhere else — a phone
 * in Dhaka, a handed-over device, an emulator whose GPS defaults to California — would put the camera
 * over empty space with no roads, no shelters and nothing to route to. It would look like the app was
 * broken, and the person would have no way to tell that the map was simply looking somewhere they are
 * not. Staying on the district is the honest answer: the app is a map of Feni or it is nothing.
 *
 * The margin exists because a fix a few hundred metres outside the bounding box is still *at* the
 * incident — the box is the graph's extent, not the district border, and roads continue past it.
 */
object MapCentring {

    /**
     * How far outside the graph's extent a fix may be and still count as "at the incident".
     *
     * ~5 km at this latitude: enough to absorb a fix on the far bank of a river or in a neighbouring
     * upazila, far too little to accept a different city.
     */
    const val MARGIN_DEGREES = 0.05

    /**
     * Street-level zoom for a located start.
     *
     * Deliberately closer than the district-wide default. At the district camera a long-press in
     * dense areas resolves to "Ambiguous: N roads overlap", so starting on the person's own position
     * also starts them where a road can actually be selected.
     */
    const val STREET_ZOOM = 15.0

    /** True when [coordinate] is inside the graph's extent, within [MARGIN_DEGREES]. */
    fun isInsideDataset(
        coordinate: GeoCoordinate,
        southLatitude: Double,
        westLongitude: Double,
        northLatitude: Double,
        eastLongitude: Double,
    ): Boolean {
        val latitude = coordinate.latitude
        val longitude = coordinate.longitude
        // A corrupt or absurd coordinate is not a location: refuse it here rather than let it move
        // the camera somewhere meaningless.
        if (!latitude.isFinite() || !longitude.isFinite()) return false
        return latitude in (southLatitude - MARGIN_DEGREES)..(northLatitude + MARGIN_DEGREES) &&
            longitude in (westLongitude - MARGIN_DEGREES)..(eastLongitude + MARGIN_DEGREES)
    }
}
