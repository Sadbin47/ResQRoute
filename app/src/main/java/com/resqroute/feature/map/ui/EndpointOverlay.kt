package com.resqroute.feature.map.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import com.resqroute.core.graph.Shelter
import com.resqroute.core.model.GeoCoordinate
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Shelter pins plus the selected start and destination.
 *
 * 91 markers is a small, fixed set, so individual draw calls are acceptable at
 * this scale; the blueprint allows a limited number of endpoint markers.
 */
class EndpointOverlay(private val shelters: List<Shelter>) : Overlay() {

    private var start: GeoCoordinate? = null
    private var destinationIndex: Int? = null

    private val shelterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = SHELTER_COLOR
    }
    private val destinationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = DESTINATION_COLOR
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = START_COLOR
    }
    private val point = Point()

    fun render(start: GeoCoordinate?, destinationIndex: Int?) {
        this.start = start
        this.destinationIndex = destinationIndex
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val density = mapView.resources.displayMetrics.density
        val projection = mapView.projection

        shelters.forEachIndexed { index, shelter ->
            projection.toPixels(GeoPoint(shelter.coordinate.latitude, shelter.coordinate.longitude), point)
            val radius = if (index == destinationIndex) DESTINATION_RADIUS_DP * density else SHELTER_RADIUS_DP * density
            if (index == destinationIndex) {
                destinationPaint.strokeWidth = 3f * density
                canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), radius, destinationPaint)
            } else {
                canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), radius, shelterPaint)
            }
        }

        start?.let { coordinate ->
            projection.toPixels(GeoPoint(coordinate.latitude, coordinate.longitude), point)
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), START_RADIUS_DP * density, startPaint)
        }
    }

    companion object {
        const val SHELTER_COLOR = 0xFF8BC34A.toInt()
        const val DESTINATION_COLOR = 0xFFFFC107.toInt()
        const val START_COLOR = 0xFF2196F3.toInt()

        const val SHELTER_RADIUS_DP = 3.5f
        const val DESTINATION_RADIUS_DP = 8f
        const val START_RADIUS_DP = 6f
    }
}
