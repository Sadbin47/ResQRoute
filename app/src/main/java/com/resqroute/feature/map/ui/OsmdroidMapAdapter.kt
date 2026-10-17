package com.resqroute.feature.map.ui

import android.content.Context
import android.graphics.Point
import android.util.Log
import com.resqroute.core.model.GeoCoordinate
import com.resqroute.core.model.SegmentId
import com.resqroute.feature.map.presentation.MapCentring
import com.resqroute.core.routing.RoutePath
import com.resqroute.domain.map.MapDataset
import java.io.File
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay

/** Callbacks the map host delivers to the screen. */
interface MapInteractionListener {

    fun onSegmentSelection(result: RoadSelectionResult)

    fun onShelterSelected(shelterIndex: Int)
}

/**
 * osmdroid configuration and a tile provider with **no downloader module**.
 *
 * The provider is a [MapTileProviderArray] with an empty module list, so it can
 * never issue a network request: `getMapTile` finds no provider and reports the
 * tile as failed. Combined with `MapView.setUseDataConnection(false)`, an
 * explicit dark background and the removed `INTERNET` permission, this is the
 * offline guarantee of acceptance criterion 1.
 */
internal object OfflineTiles {

    val DARK_BACKGROUND: Int = 0xFF0B0F14.toInt()

    @Volatile
    private var configured = false

    fun configure(context: Context) {
        if (configured) return
        synchronized(this) {
            if (configured) return
            val configuration = Configuration.getInstance()
            configuration.userAgentValue = context.packageName
            // App-internal cache only: no external-storage permission is required.
            configuration.osmdroidBasePath = File(context.cacheDir, "osmdroid")
            configuration.osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
            configuration.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            configured = true
        }
    }

    /** Tile provider with zero modules: no cache writer, no downloader. */
    fun provider(): MapTileProviderBase {
        val source = XYTileSource(
            "resqroute-offline",
            0,
            22,
            256,
            ".png",
            emptyArray(),
            "offline bundled vector dataset",
        )
        return MapTileProviderArray(source, null, emptyArray<MapTileModuleProviderBase>())
    }
}

/**
 * Isolates every osmdroid type behind one adapter (blueprint section 4).
 *
 * The Android drawing objects live only here and in the overlays; the rest of
 * the app depends on [MapDataset] and domain routing types. Phase 7 can drive
 * the same adapter from a ViewModel without touching osmdroid.
 */
class OsmdroidMapAdapter(
    context: Context,
    private val dataset: MapDataset,
    private val listener: MapInteractionListener,
) {

    init {
        // Configuration must exist before the MapView constructor runs.
        OfflineTiles.configure(context)
    }

    private val scope = MainScope()

    private val roadsOverlay = RoadsOverlay(dataset.graph)
    private val hazardOverlay = HazardOverlay(dataset.graph)
    private val routeOverlay = RouteOverlay(dataset.graph)
    private val endpointOverlay = EndpointOverlay(dataset.shelters)
    private val selectionAdapter = RoadSelectionAdapter(dataset.graph, dataset.spatialIndex)

    private val point = Point()
    private val density = context.resources.displayMetrics.density

    /**
     * A camera target supplied before the map has a size (XPH-92).
     *
     * The camera is applied on the first layout, and a location fix can arrive either side of that
     * moment. Holding it here means the located start wins whichever order they happen in, instead
     * of being overwritten by the district camera a frame later.
     */
    private var pendingCentre: GeoCoordinate? = null
    private var pendingZoom: Double? = null

    val view: MapView = MapView(context).apply {
        setBackgroundColor(OfflineTiles.DARK_BACKGROUND)
        tileProvider = OfflineTiles.provider()
        setUseDataConnection(false)
        setMultiTouchControls(true)
        // The map lives inside a ScrollView; keep pan gestures on the map.
        setOnTouchListener { v, _ ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        overlayManager.tilesOverlay.setLoadingBackgroundColor(OfflineTiles.DARK_BACKGROUND)
        overlayManager.tilesOverlay.setLoadingLineColor(OfflineTiles.DARK_BACKGROUND)
        // The no-downloader tile provider exposes no usable zoom range, which
        // leaves the map's clamps inverted (min 29, max 0) and forces every
        // camera request to maximum zoom. Pin an explicit range.
        setMinZoomLevel(MIN_ZOOM)
        setMaxZoomLevel(MAX_ZOOM)
    }

    /** Tap selects a shelter; long-press selects a road segment. */
    private val receiver = object : MapEventsReceiver {

        override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
            handleShelterTap(p)
            return true
        }

        override fun longPressHelper(p: GeoPoint): Boolean {
            handleLongPress(p)
            return true
        }
    }

    init {
        // Fixed overlay stack: dark canvas (MapView background), roads, route,
        // **hazards**, endpoints, then a gesture receiver on top.
        //
        // Hazards are deliberately drawn AFTER the route: a committed blocked
        // road must stay visible wherever the line and the mark share geometry.
        // Combined with the wider hazard stroke in OverlayWidths, the red mark
        // can never be painted over by the green line.
        view.overlays.add(roadsOverlay)
        view.overlays.add(routeOverlay)
        view.overlays.add(hazardOverlay)
        view.overlays.add(endpointOverlay)
        view.overlays.add(MapEventsOverlay(receiver))

        // Centre on the middle of the dataset extent: the shelter list is spread
        // across the district and its first entry sits in a road-sparse corner.
        // A MapView that has not been measured yet silently keeps its default
        // (maximum) zoom, which makes every viewport-culled overlay draw nothing,
        // so the camera is re-applied as soon as the map has a real size.
        val centerLatitude = (dataset.graph.southLatitude + dataset.graph.northLatitude) / 2.0
        val centerLongitude = (dataset.graph.westLongitude + dataset.graph.eastLongitude) / 2.0
        val center = GeoPoint(centerLatitude, centerLongitude)
        view.setExpectedCenter(center)
        applyCameraWhenLaidOut(center)
    }

    private fun applyCameraWhenLaidOut(center: GeoPoint) {
        // `addOnFirstLayoutListener` is one-shot and can fire while the map is
        // still zero-sized (it is added to the hierarchy later), leaving the
        // camera at its default maximum zoom. Watch layout until the view really
        // has a size, then set the camera once and stop watching.
        view.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (view.width <= 0 || view.height <= 0) return
                    // A located start, if one arrived first, is the camera that wins.
                    val target = pendingCentre?.let { GeoPoint(it.latitude, it.longitude) } ?: center
                    view.controller.setZoom(pendingZoom ?: DEFAULT_ZOOM)
                    view.controller.setCenter(target)
                    view.invalidate()
                    Log.i(
                        TAG,
                        "camera applied: requested=$DEFAULT_ZOOM now=${view.zoomLevelDouble} " +
                            "min=${view.minZoomLevel} max=${view.maxZoomLevel} " +
                            "center=${"%.6f".format(center.latitude)},${"%.6f".format(center.longitude)}",
                    )
                    view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            },
        )
    }

    // ------------------------------------------------------------ rendering

    fun renderHazards(
        blocked: Collection<SegmentId>,
        reported: Collection<SegmentId> = emptyList(),
        pending: SegmentId?,
        selected: SegmentId?,
        candidates: List<SegmentId> = emptyList(),
    ) {
        hazardOverlay.render(blocked, reported, pending, selected, candidates)
        view.invalidate()
    }

    /**
     * One step closer, from the on-screen zoom control (XPH-91).
     *
     * The control exists because the map has no tile downloader and therefore no built-in zoom
     * widget, and because a pinch gesture is not always available — one hand while walking, wet
     * gloves, or unfamiliarity with the gesture. Zoom is not cosmetic here: the long-press hit test
     * runs in **screen space**, so at the default zoom a point in the district centre resolves to
     * "Ambiguous: N roads overlap", which means zoom decides whether the road a person wants to
     * report is selectable at all.
     *
     * Bounded by the map's own `setMinZoomLevel`/`setMaxZoomLevel` (6..20), which exist because the
     * no-downloader tile provider exposes no usable zoom range and would otherwise leave the clamps
     * inverted. The controller enforces those bounds, so pressing the button at either end is a
     * no-op rather than an excursion into a range the tile provider cannot render.
     */
    fun zoomIn() {
        view.controller.zoomIn()
    }

    /** One step further out. See [zoomIn] for why these exist and what bounds them. */
    fun zoomOut() {
        view.controller.zoomOut()
    }

    /**
     * Moves the camera to [coordinate], defaulting to street-level zoom (XPH-92).
     *
     * Applied immediately when the map already has a size, and held for the first layout when it does
     * not — see [pendingCentre].
     */
    fun centreOn(coordinate: GeoCoordinate, zoom: Double = MapCentring.STREET_ZOOM) {
        pendingCentre = coordinate
        pendingZoom = zoom
        if (view.width > 0 && view.height > 0) {
            view.controller.setZoom(zoom)
            view.controller.setCenter(GeoPoint(coordinate.latitude, coordinate.longitude))
            view.invalidate()
        }
    }

    fun renderRoute(route: RoutePath?, usable: Boolean = true) {
        routeOverlay.render(route, usable)
        view.invalidate()
    }

    fun renderEndpoints(start: GeoCoordinate?, destinationShelterIndex: Int?) {
        endpointOverlay.render(start, destinationShelterIndex)
        view.invalidate()
    }

    // ------------------------------------------------------------- lifecycle

    fun onResume() = view.onResume()

    fun onPause() = view.onPause()

    fun onDestroy() {
        scope.cancel()
        view.onDetach()
    }

    // ----------------------------------------------------------- diagnostics

    fun visibleRoadSegments(): Int = roadsOverlay.visibleSegmentCount

    /** Number of times the static road path was rebuilt (camera changes only). */
    fun roadLayerRebuilds(): Int = roadsOverlay.cache.rebuildCount

    // -------------------------------------------------------------- gestures

    private fun handleShelterTap(p: GeoPoint) {
        val projection = view.projection
        val tap = Point()
        projection.toPixels(GeoPoint(p.latitude, p.longitude), tap)
        val tolerance = SHELTER_TAP_TOLERANCE_DP * density
        var bestIndex = -1
        var bestSquared = tolerance * tolerance
        dataset.shelters.forEachIndexed { index, shelter ->
            projection.toPixels(GeoPoint(shelter.coordinate.latitude, shelter.coordinate.longitude), point)
            val dx = (point.x - tap.x).toDouble()
            val dy = (point.y - tap.y).toDouble()
            val squared = dx * dx + dy * dy
            if (squared <= bestSquared) {
                bestSquared = squared
                bestIndex = index
            }
        }
        if (bestIndex >= 0) listener.onShelterSelected(bestIndex)
    }

    private fun handleLongPress(p: GeoPoint) {
        val projection = view.projection
        projection.toPixels(GeoPoint(p.latitude, p.longitude), point)
        val touchX = point.x.toDouble()
        val touchY = point.y.toDouble()
        val metersPerPixel = TileSystem.GroundResolution(p.latitude, view.zoomLevelDouble)
        val tolerancePixels = LONG_PRESS_TOLERANCE_DP * density
        val touch = GeoCoordinate(p.latitude, p.longitude)
        val scratch = Point()

        scope.launch {
            val result = selectionAdapter.select(
                touch = touch,
                touchX = touchX,
                touchY = touchY,
                maximumPixelDistance = tolerancePixels,
                metersPerPixel = metersPerPixel,
            ) { coordinate ->
                view.projection.toPixels(
                    GeoPoint(coordinate.latitude, coordinate.longitude),
                    scratch,
                )
                ScreenPoint(scratch.x.toDouble(), scratch.y.toDouble())
            }
            listener.onSegmentSelection(result)
        }
    }

    // IGeoPoint exposes latitude/longitude; the long-press handler projects the
    // tap once and refines candidates in screen space via RoadSelectionAdapter.

    companion object {
        const val DEFAULT_ZOOM = 11.5
        const val MIN_ZOOM = 6.0
        const val MAX_ZOOM = 20.0
        private const val TAG = "ResQRouteMap"
        const val LONG_PRESS_TOLERANCE_DP = 28.0
        const val SHELTER_TAP_TOLERANCE_DP = 24.0
    }
}
