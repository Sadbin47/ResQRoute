package com.resqroute.feature.map.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.util.Log
import com.resqroute.core.graph.RoutingGraph
import com.resqroute.core.model.GeoCoordinate
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Identifies one rendered camera state. A change means the projected screen
 * path must be rebuilt; nothing else invalidates the static road layer.
 */
data class CameraKey(
    val zoom: Double,
    val latNorth: Double,
    val latSouth: Double,
    val lonEast: Double,
    val lonWest: Double,
    val width: Int,
    val height: Int,
) {
    companion object {
        fun from(mapView: MapView, box: BoundingBox): CameraKey = CameraKey(
            zoom = mapView.zoomLevelDouble,
            latNorth = box.latNorth,
            latSouth = box.latSouth,
            lonEast = box.lonEast,
            lonWest = box.lonWest,
            width = mapView.width,
            height = mapView.height,
        )
    }
}

/**
 * Single-entry cache for the projected road paths.
 *
 * Phase 5 acceptance criterion 3: a hazard update must not rebuild the static
 * road layer. Because the cache is keyed only by the camera, repainting due to a
 * hazard change reuses the existing paths and never calls the builder again.
 */
class RoadsPathCache {

    private var key: CameraKey? = null

    var rebuildCount: Int = 0
        private set

    /** Rebuilds only when [newKey] differs from the cached camera. */
    fun ensure(newKey: CameraKey, rebuild: () -> Unit): Boolean {
        if (newKey == key) return false
        key = newKey
        rebuild()
        rebuildCount++
        return true
    }

    fun invalidate() {
        key = null
    }
}

/**
 * Batched, viewport-culled vector layer for the static road network.
 *
 * The network is drawn as a **small number of Path chunks**, each bounded in
 * size. A single Path holding every visible segment can exceed what Skia/HWUI
 * will render, in which case the whole layer silently disappears — so each
 * chunk stays well under that limit (this is a real failure mode that produced a
 * blank map on a device).
 *
 * The projected paths are cached per camera and rebuilt only when the camera
 * changes. Below [DETAIL_ZOOM] only each segment's endpoints are drawn.
 */
class RoadsOverlay(private val graph: RoutingGraph) : Overlay() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ROAD_COLOR
    }
    private val reusablePoint = Point()

    private val minLatitude = FloatArray(graph.segmentCount)
    private val maxLatitude = FloatArray(graph.segmentCount)
    private val minLongitude = FloatArray(graph.segmentCount)
    private val maxLongitude = FloatArray(graph.segmentCount)

    /** Bounded chunks of the projected network. */
    private val chunks = ArrayList<Path>(32)

    val cache = RoadsPathCache()

    var visibleSegmentCount: Int = 0
        private set

    var drawnPointCount: Int = 0
        private set

    init {
        for (segment in 0 until graph.segmentCount) {
            var minLat = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            var minLon = Double.MAX_VALUE
            var maxLon = -Double.MAX_VALUE
            val points = graph.geometryPointCount(segment)
            var point = 0
            while (point < points) {
                val latitude = graph.geometryLatitude(segment, point)
                val longitude = graph.geometryLongitude(segment, point)
                if (latitude < minLat) minLat = latitude
                if (latitude > maxLat) maxLat = latitude
                if (longitude < minLon) minLon = longitude
                if (longitude > maxLon) maxLon = longitude
                point++
            }
            if (points == 0) {
                minLat = 0.0; maxLat = -1.0; minLon = 0.0; maxLon = -1.0
            }
            minLatitude[segment] = minLat.toFloat()
            maxLatitude[segment] = maxLat.toFloat()
            minLongitude[segment] = minLon.toFloat()
            maxLongitude[segment] = maxLon.toFloat()
        }
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val box = mapView.boundingBox ?: return
        if (mapView.width == 0 || mapView.height == 0) return

        val key = CameraKey.from(mapView, box)
        cache.ensure(key) { rebuild(mapView, box) }
        if (chunks.isEmpty()) {
            // Never cache an empty result: the next frame retries once the map
            // has a usable projection.
            cache.invalidate()
            return
        }

        paint.strokeWidth = BASE_WIDTH_DP * mapView.resources.displayMetrics.density
        for (chunk in chunks) {
            canvas.drawPath(chunk, paint)
        }
    }

    private fun rebuild(mapView: MapView, box: BoundingBox) {
        for (chunk in chunks) chunk.reset()
        chunks.clear()
        visibleSegmentCount = 0
        drawnPointCount = 0

        val projection = mapView.projection
        val point = reusablePoint
        val detailed = mapView.zoomLevelDouble >= DETAIL_ZOOM
        var current = Path()
        var segmentsInChunk = 0
        var visible = 0

        for (segment in 0 until graph.segmentCount) {
            if (maxLatitude[segment] < box.latSouth ||
                minLatitude[segment] > box.latNorth ||
                maxLongitude[segment] < box.lonWest ||
                minLongitude[segment] > box.lonEast
            ) {
                continue
            }
            visible++
            val pointCount = graph.geometryPointCount(segment)
            if (pointCount == 0) continue

            if (detailed) {
                // Bound per-segment complexity on very detailed geometry.
                val stride = if (pointCount > MAX_POINTS_PER_SEGMENT) {
                    (pointCount + MAX_POINTS_PER_SEGMENT - 1) / MAX_POINTS_PER_SEGMENT
                } else {
                    1
                }
                var index = 0
                while (index < pointCount) {
                    projection.toPixels(
                        GeoPoint(
                            graph.geometryLatitude(segment, index),
                            graph.geometryLongitude(segment, index),
                        ),
                        point,
                    )
                    if (index == 0) current.moveTo(point.x.toFloat(), point.y.toFloat())
                    else current.lineTo(point.x.toFloat(), point.y.toFloat())
                    drawnPointCount++
                    index += stride
                }
                // Ensure the final point is included when striding.
                if (stride > 1) {
                    val last = pointCount - 1
                    projection.toPixels(
                        GeoPoint(
                            graph.geometryLatitude(segment, last),
                            graph.geometryLongitude(segment, last),
                        ),
                        point,
                    )
                    current.lineTo(point.x.toFloat(), point.y.toFloat())
                    drawnPointCount++
                }
            } else {
                projection.toPixels(
                    GeoPoint(graph.geometryLatitude(segment, 0), graph.geometryLongitude(segment, 0)),
                    point,
                )
                current.moveTo(point.x.toFloat(), point.y.toFloat())
                projection.toPixels(
                    GeoPoint(
                        graph.geometryLatitude(segment, pointCount - 1),
                        graph.geometryLongitude(segment, pointCount - 1),
                    ),
                    point,
                )
                current.lineTo(point.x.toFloat(), point.y.toFloat())
                drawnPointCount += 2
            }

            segmentsInChunk++
            if (segmentsInChunk >= SEGMENTS_PER_CHUNK) {
                chunks.add(current)
                current = Path()
                segmentsInChunk = 0
            }
        }
        if (segmentsInChunk > 0) chunks.add(current)

        visibleSegmentCount = visible
        Log.i(
            TAG,
            "roads rebuilt: visible=$visible chunks=${chunks.size} points=$drawnPointCount " +
                "zoom=${"%.1f".format(mapView.zoomLevelDouble)} detailed=$detailed",
        )
    }

    companion object {
        const val ROAD_COLOR = 0xFF7F8FA8.toInt()
        const val DETAIL_ZOOM = 15.0
        const val BASE_WIDTH_DP = 2.0f

        /** Segments per Path chunk: keeps each Path small enough to render. */
        const val SEGMENTS_PER_CHUNK = 96

        /** Rendering-only simplification cap for one segment's geometry. */
        const val MAX_POINTS_PER_SEGMENT = 48

        private const val TAG = "ResQRouteMap"

        internal fun boundsOf(graph: RoutingGraph, segment: Int): GeoBounds {
            var minLat = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            var minLon = Double.MAX_VALUE
            var maxLon = -Double.MAX_VALUE
            val points = graph.geometryPointCount(segment)
            for (point in 0 until points) {
                val latitude = graph.geometryLatitude(segment, point)
                val longitude = graph.geometryLongitude(segment, point)
                if (latitude < minLat) minLat = latitude
                if (latitude > maxLat) maxLat = latitude
                if (longitude < minLon) minLon = longitude
                if (longitude > maxLon) maxLon = longitude
            }
            return GeoBounds(minLat, maxLat, minLon, maxLon)
        }
    }
}

/** Geographic bounding box of one segment's geometry. */
data class GeoBounds(
    val minLatitude: Double,
    val maxLatitude: Double,
    val minLongitude: Double,
    val maxLongitude: Double,
) {
    fun intersects(
        otherMinLatitude: Double,
        otherMinLongitude: Double,
        otherMaxLatitude: Double,
        otherMaxLongitude: Double,
    ): Boolean = !(maxLatitude < otherMinLatitude ||
        minLatitude > otherMaxLatitude ||
        maxLongitude < otherMinLongitude ||
        minLongitude > otherMaxLongitude)

    fun contains(coordinate: GeoCoordinate): Boolean =
        coordinate.latitude in minLatitude..maxLatitude && coordinate.longitude in minLongitude..maxLongitude
}
