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
import com.resqroute.core.model.SegmentId
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Dynamic hazard layer: committed blocks, the pending (not yet committed) mark,
 * the hovered selection and ambiguity candidates.
 *
 * This layer is separate from [RoadsOverlay] on purpose. Hazard state changes
 * only touch this overlay's fields; the static road path cache is never
 * invalidated (Phase 5 acceptance criterion 3).
 *
 * Styling keeps states visually distinct: a pending mark is a dashed amber line,
 * a confirmed block is solid red, ambiguity candidates are orange, and the
 * highlighted selection is a translucent cyan casing. Widths come from
 * [OverlayWidths], so a committed block is always **wider** than the route line
 * drawn beneath it and can never be hidden by it.
 *
 * Like the route layer, geometry is culled to the viewport and emitted as
 * bounded Path chunks so a long off-screen polyline can never break rendering.
 */
class HazardOverlay(private val graph: RoutingGraph) : Overlay() {

    private var blocked: Set<Int> = emptySet()
    private var reported: Set<Int> = emptySet()
    private var pending: Int? = null
    private var selected: Int? = null
    private var candidates: List<Int> = emptyList()

    private val candidateChunks = ArrayList<Path>(4)
    private val reportedChunks = ArrayList<Path>(4)
    private val blockedChunks = ArrayList<Path>(4)
    private val pendingChunks = ArrayList<Path>(4)
    private val selectionChunks = ArrayList<Path>(4)

    private var cachedCamera: CameraKey? = null
    private var dirty = true

    /** Visible committed blocks emitted on the last rebuild (diagnostics). */
    var drawnBlockedCount: Int = 0
        private set

    /** Visible reported-but-usable roads emitted on the last rebuild (diagnostics). */
    var drawnReportedCount: Int = 0
        private set

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = SELECTION_COLOR
        alpha = 160
    }
    private val reportedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = REPORTED_COLOR
    }
    private val blockedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = BLOCKED_COLOR
    }
    private val pendingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = PENDING_COLOR
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    private val candidatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = CANDIDATE_COLOR
        alpha = 190
    }
    private val point = Point()

    fun render(
        blocked: Collection<SegmentId>,
        reported: Collection<SegmentId>,
        pending: SegmentId?,
        selected: SegmentId?,
        candidates: List<SegmentId>,
    ) {
        this.blocked = blocked.mapTo(HashSet()) { it.value }
        this.reported = reported.mapTo(HashSet()) { it.value }
        this.pending = pending?.value
        this.selected = selected?.value
        this.candidates = candidates.map { it.value }
        dirty = true
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        if (blocked.isEmpty() && reported.isEmpty() && pending == null && selected == null && candidates.isEmpty()) return
        if (mapView.width == 0 || mapView.height == 0) return
        val box = mapView.boundingBox ?: return

        val camera = CameraKey.from(mapView, box)
        if (dirty || cachedCamera != camera) {
            cachedCamera = camera
            dirty = false
            rebuild(mapView, camera)
        }

        val density = mapView.resources.displayMetrics.density
        val zoom = mapView.zoomLevelDouble
        drawChunks(canvas, candidateChunks, candidatePaint, OverlayWidths.CANDIDATE_DP * density)
        // Reported-but-usable first, so a road that is BOTH reported and excluded shows as the
        // exclusion: red is the answer that must not be hidden, and it is drawn wider as well.
        drawChunks(canvas, reportedChunks, reportedPaint, OverlayWidths.reportedDp(zoom) * density)
        drawChunks(canvas, blockedChunks, blockedPaint, OverlayWidths.blockedDp(zoom) * density)
        drawChunks(canvas, pendingChunks, pendingPaint, OverlayWidths.pendingDp(zoom) * density)
        drawChunks(canvas, selectionChunks, selectionPaint, OverlayWidths.SELECTION_DP * density)
    }

    private fun drawChunks(canvas: Canvas, chunks: List<Path>, paint: Paint, width: Float) {
        if (chunks.isEmpty()) return
        paint.strokeWidth = width
        for (chunk in chunks) canvas.drawPath(chunk, paint)
    }

    private fun rebuild(mapView: MapView, camera: CameraKey) {
        val viewport = GeoBox(
            south = camera.latSouth,
            west = camera.lonWest,
            north = camera.latNorth,
            east = camera.lonEast,
        )
        buildChunks(candidateChunks, candidates, mapView, viewport)
        drawnReportedCount = buildChunks(reportedChunks, reported, mapView, viewport)
        drawnBlockedCount = buildChunks(blockedChunks, blocked, mapView, viewport)
        buildChunks(pendingChunks, listOfNotNull(pending), mapView, viewport)
        buildChunks(selectionChunks, listOfNotNull(selected), mapView, viewport)
        Log.i(
            TAG,
            "hazard drawn: committed=${blocked.size} visible=$drawnBlockedCount " +
                "reported=${reported.size} reportedVisible=$drawnReportedCount " +
                "pending=$pending selected=$selected zoom=${"%.1f".format(camera.zoom)}",
        )
    }

    private fun buildChunks(
        target: MutableList<Path>,
        segments: Collection<Int>,
        mapView: MapView,
        viewport: GeoBox,
    ): Int {
        for (chunk in target) chunk.reset()
        target.clear()
        var path = Path()
        var inChunk = 0
        var visible = 0
        for (segment in segments) {
            if (!SegmentViewport.isVisible(graph, segment, viewport)) continue
            val points = graph.geometryPointCount(segment)
            if (points == 0) continue
            mapView.projection.let { projection ->
                var index = 0
                while (index < points) {
                    projection.toPixels(
                        GeoPoint(graph.geometryLatitude(segment, index), graph.geometryLongitude(segment, index)),
                        point,
                    )
                    if (index == 0) path.moveTo(point.x.toFloat(), point.y.toFloat())
                    else path.lineTo(point.x.toFloat(), point.y.toFloat())
                    index++
                }
            }
            visible++
            inChunk++
            if (inChunk >= SEGMENTS_PER_CHUNK) {
                target.add(path)
                path = Path()
                inChunk = 0
            }
        }
        if (inChunk > 0) target.add(path)
        return visible
    }

    companion object {
        const val BLOCKED_COLOR = 0xFFE53935.toInt()

        /**
         * A road somebody reported that is **not** an exclusion (XPH-92).
         *
         * Solid amber, deliberately in the same family as the app's flood colour, and deliberately
         * not red: the distinction between "reported, priced, still usable" and "excluded" is the
         * whole of ADR 0001 D1, and a mark that looked like a closure would misstate a road the
         * reporter said could be used. It is also drawn **thinner** than a block, so where both
         * exist the red reads as the stronger statement.
         */
        const val REPORTED_COLOR = 0xFFF9AB00.toInt()
        const val PENDING_COLOR = 0xFFFFB300.toInt()
        const val SELECTION_COLOR = 0xFF29B6F6.toInt()
        const val CANDIDATE_COLOR = 0xFFFF7043.toInt()

        /** Segments per Path chunk: bounded so a chunk always renders. */
        const val SEGMENTS_PER_CHUNK = 96

        private const val TAG = "ResQRouteMap"
    }
}
