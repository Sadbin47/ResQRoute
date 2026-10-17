package com.resqroute.feature.map.ui

import android.widget.FrameLayout
import android.widget.TextView

/**
 * The map surface.
 *
 * The container and its placeholder are declared in `res/layout/activity_main.xml`
 * (`@id/mapContainer` + `@id/mapPlaceholder`) so the whole screen can be previewed
 * in the IDE. The real [org.osmdroid.views.MapView] is added by
 * [OsmdroidMapAdapter] once the immutable dataset is ready; until then (and on a
 * dataset failure) the placeholder stays visible, so the screen is never a
 * fabricated map.
 */
object MapScreen {

    private const val PLACEHOLDER_TAG = "map-placeholder"

    /** The "waiting for the dataset" label inside [frame], or null. */
    fun placeholder(frame: FrameLayout): TextView? = frame.findViewWithTag(PLACEHOLDER_TAG)
}
