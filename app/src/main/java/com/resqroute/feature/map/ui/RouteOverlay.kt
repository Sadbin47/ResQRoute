package com.resqroute.feature.map.ui

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.util.Log
import com.resqroute.core.graph.GeoBox
import com.resqroute.core.graph.RoutingGraph
import com.resqroute.core.graph.SegmentViewport
import com.resqroute.core.routing.RoutePath
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * The current route line.
 *
 * [render]'s `usable` flag distinguishes a route that is still valid (green,
 * solid) from one that a newer block has invalidated but which has not yet been
 * replaced (grey, dashed) — Phase 5 acceptance criterion 5, driven by Phase 7's
 * publication guard.
 *
 * Rendering rules, all of which exist because a green line must never appear to
 * traverse a blocked road:
 *  - geometry is culled to the camera viewport ([SegmentViewport]), so a
 *    segment that is entirely off-screen contributes no path points;
 *  - geometry is emitted as a small number of bounded Path chunks, because one
 *    Path holding every visible segment can silently fail to render;
 *  - the stroke width shrinks with zoom ([OverlayWidths]) so the line cannot be
 *    wider than the street spacing at district zoom;
 *  - the layer is drawn **below** the hazard layer (see [OsmdroidMapAdapter]),
 *    and the hazard strokes are wider, so a committed block is never hidden by
 *    the route.
 *
 * The route geometry itself is never rewritten, shortened or simplified — the
 * path is rebuilt only when the route identity, its usability or the camera
 * changes.
 */
class RouteOverlay(private val graph: RoutingGraph) : Overlay() {

    private var route: RoutePath? = null
    private var usable: Boolean = true

    private val chunks = ArrayList<Path>(16)
    private var cachedCamera: CameraKey? = null
    private var cachedRoute: RoutePath? = null
    private var cachedUsable: Boolean = true

    private val usablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = USABLE_COLOR
    }
    private val invalidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = INVALID_COLOR
        pathEffect = DashPathEffect(floatArrayOf(20f, 14f), 0f)
    }
    private val point = Point()

    /** Visible segments actually emitted on the last rebuild (diagnostics/tests). */
    var drawnSegmentCount: Int = 0
        private set

    fun render(route: RoutePath?, usable: Boolean) {
        if (this.route !== route || this.usable != usable) {
            this.route = route
            this.usable = usable
            invalidate()
        }
    }

    private fun invalidate() {
        cachedCamera = null
        cachedRoute = null
        chunks.clear()
        drawnSegmentCount = 0
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val current = route ?: return
        if (mapView.width == 0 || mapView.height == 0) return
        val box = mapView.boundingBox ?: return
        val camera = CameraKey.from(mapView, box)
        if (cachedCamera != camera || cachedRoute !== current || cachedUsable != usable) {
            cachedCamera = camera
            cachedRoute = current
            cachedUsable = usable
            rebuild(mapView, camera)
        }
        if (chunks.isEmpty()) return
        val density = mapView.resources.displayMetrics.density
        val paint = if (usable) usablePaint else invalidPaint
        paint.strokeWidth = OverlayWidths.routeDp(mapView.zoomLevelDouble) * density
        for (chunk in chunks) canvas.drawPath(chunk, paint)
    }

    private fun rebuild(mapView: MapView, camera: CameraKey) {
        // Drop the previous chunks without resetting them: a Path that was handed
        // to a canvas must never be mutated afterwards. Fresh Paths are allocated
        // below, so the old ones are simply left for the collector.
        chunks.clear()
        drawnSegmentCount = 0

        val current = route ?: return
        val viewport = GeoBox(
            south = camera.latSouth,
            west = camera.lonWest,
            north = camera.latNorth,
            east = camera.lonEast,
        )
        var path = Path()
        var segmentsInChunk = 0

        for (segmentId in current.segmentIds) {
            val segment = segmentId.value
            // Clip at the viewport, not by mutating geometry: a fully off-screen
            // segment contributes nothing and cannot corrupt the Path.
            if (!SegmentViewport.isVisible(graph, segment, viewport)) continue
            val pointCount = graph.geometryPointCount(segment)
            if (pointCount == 0) continue
            appendSegment(path, mapView, segment, pointCount)
            drawnSegmentCount++
            segmentsInChunk++
            if (segmentsInChunk >= SEGMENTS_PER_CHUNK) {
                chunks.add(path)
                path = Path()
                segmentsInChunk = 0
            }
        }
        if (segmentsInChunk > 0) chunks.add(path)

        Log.i(
            TAG,
            "route drawn: segments=${current.segmentIds.size} visible=$drawnSegmentCount " +
                "chunks=${chunks.size} zoom=${"%.1f".format(camera.zoom)} usable=$usable",
        )
    }

    private fun appendSegment(path: Path, mapView: MapView, segment: Int, pointCount: Int) {
        val stride = if (pointCount > MAX_POINTS_PER_SEGMENT) {
            (pointCount + MAX_POINTS_PER_SEGMENT - 1) / MAX_POINTS_PER_SEGMENT
        } else {
            1
        }
        var index = 0
        while (index < pointCount) {
            project(path, mapView, segment, index, first = index == 0)
            index += stride
        }
        if (stride > 1) {
            project(path, mapView, segment, pointCount - 1, first = false)
        }
    }

    private fun project(
        path: Path,
        mapView: MapView,
        segment: Int,
        pointIndex: Int,
        first: Boolean,
    ) {
        mapView.projection.toPixels(
            GeoPoint(
                graph.geometryLatitude(segment, pointIndex),
                graph.geometryLongitude(segment, pointIndex),
            ),
            point,
        )
        if (first) path.moveTo(point.x.toFloat(), point.y.toFloat())
        else path.lineTo(point.x.toFloat(), point.y.toFloat())
    }

    companion object {
        const val USABLE_COLOR = 0xFF43A047.toInt()
        const val INVALID_COLOR = 0xFF8A8F98.toInt()

        /** Segments per Path chunk: bounded so a chunk always renders. */
        const val SEGMENTS_PER_CHUNK = 96

        /** Rendering-only simplification cap for one segment's geometry. */
        const val MAX_POINTS_PER_SEGMENT = 96

        private const val TAG = "ResQRouteRoute"
    }
}
