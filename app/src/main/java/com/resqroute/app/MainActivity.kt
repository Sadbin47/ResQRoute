package com.resqroute.app

import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.viewModels
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.resqroute.BuildConfig
import com.resqroute.R
import com.resqroute.data.p2p.LocationPermissions
import com.resqroute.data.p2p.NearbyPermissions
import com.resqroute.data.p2p.SpikeDiagnostics
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.HazardState
import com.resqroute.domain.map.DatasetState
import com.resqroute.domain.map.MapDataset
import com.resqroute.domain.sync.AuthRequest
import com.resqroute.domain.sync.SyncState
import com.resqroute.domain.sync.TransportState
import com.resqroute.domain.location.SnapConcern
import com.resqroute.feature.map.presentation.LocationRefusal
import com.resqroute.feature.map.presentation.LocationSuggestion
import com.resqroute.feature.map.presentation.MapUiState
import com.resqroute.feature.map.presentation.MapViewModel
import com.resqroute.feature.map.presentation.PendingReport
import com.resqroute.feature.map.presentation.RouteExplanation
import com.resqroute.feature.map.presentation.RouteExplanationCopy
import com.resqroute.feature.map.presentation.RouteStatus
import com.resqroute.feature.map.presentation.TimeBands
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.feature.map.ui.SegmentHistorySection
import com.resqroute.feature.map.ui.WaterReportPicker
import com.resqroute.feature.map.ui.MapInteractionListener
import com.resqroute.feature.map.ui.MapScreen
import com.resqroute.feature.map.ui.OsmdroidMapAdapter
import com.resqroute.feature.map.ui.RoadSelectionResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * The one screen of ResQRoute.
 *
 * The layout lives in `res/layout/activity_main.xml` (dark night-ops theme with a
 * rescue-amber accent) so it can be previewed in the IDE; this Activity inflates
 * it and renders exactly one [MapUiState]. It still never computes a route, never
 * writes hazard state and never keeps a second copy of the current route.
 *
 * The debug-only test hooks are added in code behind `if (BuildConfig.DEBUG)`, so
 * a release build ships none of their strings.
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val REQ_NEARBY_PERMISSIONS = 1001
        val CLOCK = SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    private val viewModel: MapViewModel by viewModels {
        MapViewModel.factory((application as ResQRouteApplication).container)
    }

    // Peer sync (Phase 8)
    private lateinit var syncStatusText: TextView
    private lateinit var authContainer: LinearLayout
    private var syncRequested = false

    private val syncCoordinator get() = (application as ResQRouteApplication).container.syncCoordinator

    // Screen
    private lateinit var root: View
    private lateinit var topStack: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var zoomControls: LinearLayout
    private lateinit var btnZoomIn: ImageButton
    private lateinit var btnZoomOut: ImageButton
    private lateinit var sheet: LinearLayout
    private lateinit var sheetPeek: LinearLayout
    private lateinit var sheetContent: androidx.core.widget.NestedScrollView
    private var hadSelection = false
    private lateinit var mapContainer: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var routeCompareText: TextView
    private lateinit var routeUnknownText: TextView
    private lateinit var routeAvoidText: TextView
    private lateinit var historySection: View
    private lateinit var historyText: TextView
    private lateinit var historyNoteText: TextView
    private lateinit var markActions: LinearLayout
    private lateinit var btnCancel: MaterialButton
    private lateinit var markPrompt: TextView
    private lateinit var waterLevelGrid: LinearLayout
    private lateinit var passabilityGrid: LinearLayout
    private lateinit var btnSubmitReport: MaterialButton
    private lateinit var picker: WaterReportPicker
    private lateinit var btnReportHere: MaterialButton
    private lateinit var locationSuggestionText: TextView
    private lateinit var locationConfirmActions: LinearLayout
    private lateinit var btnConfirmLocation: MaterialButton
    private lateinit var btnDeclineLocation: MaterialButton
    private lateinit var submitRow: LinearLayout
    private lateinit var btnStartSync: MaterialButton
    private lateinit var btnStopSync: MaterialButton
    private lateinit var datasetText: TextView
    private lateinit var diagnosticsText: TextView
    private lateinit var debugContainer: LinearLayout
    private lateinit var logText: TextView
    private var mapAdapter: OsmdroidMapAdapter? = null
    private var attachedDataset: MapDataset? = null
    private var startEnsured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        applyWindowInsets()

        observeSync()
        observeUiState()
        setupDebugHooks()

        if (missingRuntimePermissions().isNotEmpty()) {
            requestRuntimePermissions()
        }
        refreshDiagnostics()
    }

    // ------------------------------------------------------------------ view

    private fun bindViews() {
        root = findViewById(R.id.rq_root)
        topStack = findViewById(R.id.rq_top_stack)
        topBar = findViewById(R.id.rq_top_bar)
        zoomControls = findViewById(R.id.rq_zoom_controls)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)
        sheet = findViewById(R.id.rq_sheet)
        sheetPeek = findViewById(R.id.rq_sheet_peek)
        sheetContent = findViewById(R.id.rq_sheet_content)
        mapContainer = findViewById(R.id.mapContainer)
        statusText = findViewById(R.id.statusText)
        routeCompareText = findViewById(R.id.routeCompareText)
        routeUnknownText = findViewById(R.id.routeUnknownText)
        routeAvoidText = findViewById(R.id.routeAvoidText)
        historySection = findViewById(R.id.historySection)
        historyText = findViewById(R.id.historyText)
        historyNoteText = findViewById(R.id.historyNoteText)
        markActions = findViewById(R.id.markActions)
        datasetText = findViewById(R.id.datasetText)
        diagnosticsText = findViewById(R.id.diagnosticsText)
        syncStatusText = findViewById(R.id.syncStatusText)
        authContainer = findViewById(R.id.authContainer)
        debugContainer = findViewById(R.id.debugContainer)
        logText = findViewById(R.id.logText)

        btnCancel = markActions.findViewById(R.id.btnCancel)
        markPrompt = markActions.findViewById(R.id.markPrompt)
        waterLevelGrid = markActions.findViewById(R.id.waterLevelGrid)
        passabilityGrid = markActions.findViewById(R.id.passabilityGrid)
        // The choices list lives inside a bottom sheet, and the sheet's own drag handling was winning
        // the gesture: dragging the list did nothing at all - measured on a device, where the last row
        // of depth cards stayed clipped instead of scrolling into view. Tells the sheet to stand down
        // while a finger is on the list, which is what makes the categories below the fold reachable.
        markActions.findViewById<androidx.core.widget.NestedScrollView>(R.id.reportChoicesScroll)
            .setOnTouchListener { view, _ ->
                view.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        btnSubmitReport = markActions.findViewById(R.id.btnSubmitReport)
        submitRow = markActions.findViewById(R.id.submitRow)
        locationSuggestionText = markActions.findViewById(R.id.locationSuggestionText)
        locationConfirmActions = markActions.findViewById(R.id.locationConfirmActions)
        btnReportHere = findViewById(R.id.btnReportHere)
        btnConfirmLocation = markActions.findViewById(R.id.btnConfirmLocation)
        btnDeclineLocation = markActions.findViewById(R.id.btnDeclineLocation)
        btnStartSync = findViewById(R.id.btnStartSync)
        btnStopSync = findViewById(R.id.btnStopSync)

        // Every category is offered because the picker builds its buttons from the enums; the labels
        // come from HazardSupportCopy so the picker and a committed report share one vocabulary.
        picker = WaterReportPicker(
            context = this,
            waterGrid = waterLevelGrid,
            passabilityGrid = passabilityGrid,
            title = markPrompt,
            submitButton = btnSubmitReport,
            onReport = { level, verdict -> viewModel.reportHighlightedSegment(level, verdict) },
            isReportable = viewModel::isReportable,
        )
        btnCancel.setOnClickListener { viewModel.onSelectionCleared() }
        // XPH-90: optional, and every outcome — including every failure — leaves the long-press path
        // exactly as it was.
        btnReportHere.setOnClickListener { viewModel.reportHere() }
        // Map zoom (XPH-91). Guarded on the adapter: the controls are hidden until the map is
        // attached, so this null check is belt-and-braces rather than the primary guard.
        btnZoomIn.setOnClickListener { mapAdapter?.zoomIn() }
        btnZoomOut.setOnClickListener { mapAdapter?.zoomOut() }
        btnConfirmLocation.setOnClickListener { viewModel.confirmLocationSuggestion() }
        btnDeclineLocation.setOnClickListener { viewModel.dismissLocationSuggestion() }

        btnStartSync.setOnClickListener {
            syncRequested = true
            syncCoordinator.start()
            appendLog("Nearby sync started (foreground only)")
        }
        btnStopSync.setOnClickListener {
            syncRequested = false
            syncCoordinator.stop()
            appendLog("Nearby sync stopped")
        }

        // Row buttons must never clip or wrap: size the label to the screen once.
        applyResponsiveButtonText()
        styleSyncButtons(running = false)
        // A bottom sheet that only responds to a drag is a trap on a real phone
        // (and impossible one-handed while walking). Tapping the peek opens and
        // closes it, like Apple Maps; the drag still works too.
        val sheetBehavior = BottomSheetBehavior.from(sheet)
        sheetPeek.setOnClickListener {
            sheetBehavior.state = if (sheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                BottomSheetBehavior.STATE_COLLAPSED
            } else {
                BottomSheetBehavior.STATE_EXPANDED
            }
        }

        findViewById<MaterialButton>(R.id.btnPermissions).setOnClickListener {
            if (missingRuntimePermissions().isEmpty()) {
                appendLog("Permissions already granted")
            } else {
                requestRuntimePermissions()
            }
            refreshDiagnostics()
        }
    }

    /**
     * Keeps the content clear of the status and navigation bars. Android 15+
     * draws apps edge to edge by default, so the root scroll view owns the
     * insets and the phone never overlaps the title or the last card.
     */
    private fun applyWindowInsets() {
        // The map draws edge to edge, so only the floating chrome needs insets:
        // the top bar clears the status bar, the sheet content clears the
        // navigation bar.
        // The stack, not the pill: the zoom controls hang below the pill, so both must shift
        // together when the status bar inset changes.
        val baseTopMargin = (topStack.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        ViewCompat.setOnApplyWindowInsetsListener(topStack) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            (view.layoutParams as ViewGroup.MarginLayoutParams).topMargin = baseTopMargin + top
            view.requestLayout()
            insets
        }

        val baseBottomPadding = sheetContent.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(sheetContent) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                baseBottomPadding + bottom,
            )
            insets
        }
    }

    // --------------------------------------------------------------- state

    private fun observeUiState() {
        lifecycleScope.launch {
            viewModel.uiState.collect { render(it) }
        }
    }

    private fun render(state: MapUiState) {
        renderDataset(state)
        attachMapIfReady(state.datasetState)
        renderMap(state)
        renderStatus(state)
    }

    private fun renderDataset(state: MapUiState) {
        datasetText.text = when (val datasetState = state.datasetState) {
            DatasetState.NotLoaded -> "Dataset: not loaded"
            DatasetState.Loading -> "Dataset: loading packed graph (off Main thread)…"
            is DatasetState.Failed -> "MAP UNAVAILABLE — ${datasetState.message}"
            is DatasetState.Ready -> with(datasetState.dataset) {
                buildString {
                    appendLine("Dataset: READY — cold load ${datasetState.coldLoadMillis} ms")
                    appendLine("sha256   : ${identity.contentHash.take(16)}… (format v${identity.formatVersion})")
                    appendLine("topology : $topologyVersion")
                    appendLine(
                        "graph    : ${graph.nodeCount} nodes · ${graph.edgeCount} directed edges · " +
                            "${graph.segmentCount} segments",
                    )
                    appendLine("shelters : ${shelters.size}")
                    append(
                        "elevation: ${if (hasElevation) "present" else "unavailable (dataset v1) — never treated as safe"}",
                    )
                }
            }
        }
    }

    private fun attachMapIfReady(datasetState: DatasetState) {
        val dataset = (datasetState as? DatasetState.Ready)?.dataset ?: return
        if (mapAdapter != null && attachedDataset === dataset) return

        val adapter = OsmdroidMapAdapter(this, dataset, object : MapInteractionListener {
            override fun onSegmentSelection(result: RoadSelectionResult) {
                when (result) {
                    is RoadSelectionResult.Selected -> viewModel.onSegmentSelected(result.segmentId)
                    is RoadSelectionResult.Ambiguous -> viewModel.onAmbiguousSelection(result.candidates)
                    RoadSelectionResult.None -> viewModel.onSelectionCleared()
                }
            }

            override fun onShelterSelected(shelterIndex: Int) = viewModel.onDestinationSelected(shelterIndex)
        })
        mapContainer.addView(
            adapter.view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        MapScreen.placeholder(mapContainer)?.visibility = View.GONE
        mapAdapter = adapter
        attachedDataset = dataset
        // A zoom button that does nothing is worse than no button, so they appear only once
        // there is a map to zoom.
        zoomControls.visibility = View.VISIBLE

        if (!startEnsured) {
            startEnsured = true
            viewModel.ensureStart(dataset)
        }
        // Start on the person when we can, on the district when we cannot (XPH-92). Asked for here
        // rather than in the adapter because it needs the location source and the dataset, and the
        // answer decides the camera instead of the other way round.
        lifecycleScope.launch {
            val start = viewModel.locatedStart()
            if (mapAdapter !== adapter) return@launch
            if (start != null) {
                adapter.centreOn(start)
                appendLog("Map centred on your location")
            } else {
                appendLog("Map centred on the district (no usable location fix)")
            }
        }
        appendLog("Map ready: ${dataset.shelters.size} shelters · no network")
    }

    private fun renderMap(state: MapUiState) {
        val adapter = mapAdapter ?: return
        val pending = (state.pendingReport as? PendingReport.Saving)?.segmentId
        adapter.renderHazards(
            blocked = state.committedBlockedSegments,
            // Roads reported and still usable: the map must not show them as closures, and must not
            // hide them either. See MapUiState.pricedSegments for why this is the risk policy's own
            // set rather than a second opinion.
            reported = state.pricedSegments,
            pending = pending,
            selected = state.highlightedSegment,
            candidates = state.ambiguousCandidates,
        )

        // A route that has not (yet) passed the publication guard for the newest
        // hazard revision is drawn dashed grey, never as a valid green line.
        adapter.renderRoute(state.displayedRoute, usable = state.displayedRouteUsable)

        val dataset = (state.datasetState as? DatasetState.Ready)?.dataset
        val start = state.startShelterIndex?.let { dataset?.shelters?.getOrNull(it)?.coordinate }
        adapter.renderEndpoints(start, state.destinationShelterIndex)
    }

    private fun renderStatus(state: MapUiState) {
        val hasSelection = state.highlightedSegment != null
        val suggestion = state.locationSuggestion
        val showingSuggestion = suggestion != null
        val showingActions = hasSelection || showingSuggestion
        markActions.visibility = if (showingActions) View.VISIBLE else View.GONE
        val selected = state.highlightedSegment
        if (selected != null) picker.show(selected) else picker.hide()

        // The picker's own rows and the location confirmation are mutually exclusive: accepting a
        // suggestion is what creates a selection, and until then nothing is chosen.
        submitRow.visibility = if (hasSelection) View.VISIBLE else View.GONE
        locationSuggestionText.visibility = if (showingSuggestion) View.VISIBLE else View.GONE
        locationConfirmActions.visibility = if (showingSuggestion) View.VISIBLE else View.GONE
        if (suggestion != null) locationSuggestionText.text = suggestionText(suggestion)
        // The entry point steps aside while an action is on screen, so it never competes with the
        // confirmation it produced.
        btnReportHere.visibility = if (showingActions) View.GONE else View.VISIBLE
        // Selecting a road must reveal the actions WITHOUT covering the map: the
        // user is about to judge what the red mark covers. So the peek grows
        // instead of the sheet expanding.
        if (showingActions != hadSelection) {
            // A selected road opens the report panel, which is a tall grid: it gets nearly the whole
            // sheet on a small handset and about a third of a modern display, while a location
            // suggestion (no picker) still gets only the short action peek.
            BottomSheetBehavior.from(sheet).peekHeight = resources.getDimensionPixelSize(
                when {
                    hasSelection -> R.dimen.rq_sheet_peek_report
                    showingSuggestion -> R.dimen.rq_sheet_peek_action
                    else -> R.dimen.rq_sheet_peek
                },
            )
        }
        hadSelection = showingActions

        // The message is identical to before; only its colour now carries the
        // state (green = route ready, amber = needs attention, red = error).
        renderRouteExplanation(state)
        renderSegmentHistory(state)
        statusText.setTextColor(statusColor(state))
        statusText.text = when {
            state.reportError != null -> state.reportError

            state.pendingReport is PendingReport.Saving -> {
                val pending = state.pendingReport as PendingReport.Saving
                "Saving ${pending.segmentId.stableId} · ${pending.reason.code}…"
            }

            state.locationRefusal != null -> refusalText(state.locationRefusal)

            state.highlightedSegment != null ->
                getString(R.string.rq_report_prompt, state.highlightedSegment.stableId)

            state.ambiguousCandidates.isNotEmpty() ->
                "Ambiguous: ${state.ambiguousCandidates.size} roads overlap — zoom in (nothing selected)."

            !state.routingReady -> when (state.hazardState) {
                is HazardState.Unavailable ->
                    "Hazard data unavailable: ${state.hazardState.message} — routing disabled, map visible."
                else -> getString(R.string.hazard_restoring)
            }

            state.destinationShelterIndex == null ->
                "Tap a shelter to route to it. Long-press a road to report flooding."

            else -> when (val status = state.routeStatus) {
                RouteStatus.Idle -> "Preparing route…"
                RouteStatus.Calculating -> "Calculating route…"
                RouteStatus.NoRoute -> "No known route to this shelter."
                is RouteStatus.Failed -> "Route unavailable: ${status.message}"
                is RouteStatus.Ready -> {
                    // The line describes the distance-preferred route. Ranking, labels and the
                    // full explanation belong to XPH-77; what matters here is that a route which
                    // crosses reported risk says so instead of reading like a clean one.
                    val option = status.options.offered.first()
                    val path = option.route
                    val risk = if (option.crossesReportedRisk) " · crosses reported flooding" else ""
                    "Route: ${path.nodeIds.size} nodes · ${"%.0f".format(path.distanceMeters)} m · " +
                        "revision ${path.hazardRevision} (offline)$risk"
                }
            }
        }
    }

    /**
     * The route explanation: the comparison, the reported roads and the unknown coverage (XPH-77).
     *
     * All wording comes from [RouteExplanationCopy], which is the single place these sentences are
     * composed and the thing the wording tests hold to account; none is written here. When there is
     * no decision to explain the block is emptied rather than left showing the previous route's
     * explanation.
     */
    private fun renderRouteExplanation(state: MapUiState) {
        val ready = (state.routeStatus as? RouteStatus.Ready)?.options
        val hazardReady = state.hazardState as? HazardState.Ready
        val graph = (state.datasetState as? DatasetState.Ready)?.dataset?.graph
        if (ready == null || hazardReady == null || graph == null) {
            routeCompareText.text = ""
            routeUnknownText.text = ""
            routeAvoidText.text = ""
            return
        }
        val explanation = RouteExplanation.of(
            options = ready,
            states = hazardReady.observationStates,
            segmentLengthMeters = { graph.segmentLengthMeters(it) },
            // `now` is supplied here, at the UI boundary, and injected into a pure function:
            // nothing below this line reads a clock (ADR 0005 §6.1).
            now = System.currentTimeMillis(),
        )
        routeCompareText.text = RouteExplanationCopy.comparisonLine(this, explanation)
        routeUnknownText.text = (
            RouteExplanationCopy.noAlternativeLine(this, explanation)
                ?: RouteExplanationCopy.mostlyUnknownLine(this, explanation.reports.first())
                ?: RouteExplanationCopy.unknownLine(this, explanation.reports.first())
            )
        routeAvoidText.text = explanation.reports
            .mapNotNull { report -> RouteExplanationCopy.avoidHeader(this, report) }
            .joinToString("\n")
            .ifEmpty { getString(R.string.rq_route_nothing_reported) }
    }

    /**
     * The selected road's reported history (XPH-71): what was reported, when, by whom, and the one
     * trend sentence the log supports.
     *
     * This is where a report that prices risk but closes nothing becomes visible. The overlay draws
     * exclusions only, deliberately — a mark that could be read as a closure would misstate a road
     * the reporter said was usable (ADR 0001 D1) — so the sheet is where the rest of the log is read.
     * The block appears for any selected road, including one nobody has reported: "No reports on this
     * road" is the missing-data answer the plan asks for, and nothing words it as a clean road.
     *
     * The binding itself lives in [SegmentHistorySection] so a JVM test can drive the real layout;
     * `now` is supplied here, at the UI boundary, and injected into pure functions — nothing below
     * this line reads a clock or the device's timezone (ADR 0005 §6.1, ADR 0019).
     */
    private fun renderSegmentHistory(state: MapUiState) {
        val selected = state.highlightedSegment
        val observationState = selected
            ?.let { (state.hazardState as? HazardState.Ready)?.observationStates?.get(it) }
            // A road nobody has reported has no derived state. The empty state is the honest input:
            // it lets the block say "no reports" rather than disappearing and implying there is
            // nothing to know.
            ?: selected?.let { SegmentObservationState(it, emptyList()) }
        SegmentHistorySection.render(
            context = this,
            section = historySection,
            body = historyText,
            note = historyNoteText,
            state = observationState,
            now = System.currentTimeMillis(),
            zone = TimeBands.incidentZone(),
        )
    }

    /**
     * The confirmation line: which road, how far, and every doubt the snap carried.
     *
     * The doubts are shown as their own lines rather than folded into one sentence, so a person
     * skimming it cannot read a hedged match as a certain one.
     */
    private fun suggestionText(suggestion: LocationSuggestion): String = buildString {
        append(
            getString(
                R.string.rq_report_here_candidate,
                suggestion.segmentId.stableId,
                "%.0f".format(suggestion.distanceMeters),
            ),
        )
        suggestion.accuracyMeters?.let {
            append("\n").append(getString(R.string.rq_report_here_accuracy, "%.0f".format(it)))
        }
        for (concern in suggestion.concerns.sortedBy { it.ordinal }) {
            append("\n").append(
                getString(
                    when (concern) {
                        SnapConcern.FAR_FROM_ROAD -> R.string.rq_report_here_concern_far
                        SnapConcern.AMBIGUOUS -> R.string.rq_report_here_concern_ambiguous
                        SnapConcern.POOR_ACCURACY -> R.string.rq_report_here_concern_accuracy
                    },
                ),
            )
        }
    }

    /** Why location could not offer a road. Always ends by pointing back at the long-press path. */
    private fun refusalText(refusal: LocationRefusal): String = getString(
        when (refusal) {
            LocationRefusal.PERMISSION_DENIED -> R.string.rq_report_here_denied
            LocationRefusal.NO_FIX -> R.string.rq_report_here_no_fix
            LocationRefusal.PROVIDER_FAILED -> R.string.rq_report_here_failed
            LocationRefusal.NO_ROAD_NEARBY -> R.string.rq_report_here_no_road
        },
    )

    private fun statusColor(state: MapUiState): Int = when {
        state.reportError != null -> color(R.color.rq_blocked_red)
        state.pendingReport is PendingReport.Saving -> color(R.color.rq_pending_amber)
        state.highlightedSegment != null -> color(R.color.rq_blue)
        state.ambiguousCandidates.isNotEmpty() -> color(R.color.rq_pending_amber)
        !state.routingReady -> color(R.color.rq_text_secondary)
        state.routeStatus is RouteStatus.Ready -> color(R.color.rq_route_green)
        state.routeStatus is RouteStatus.NoRoute -> color(R.color.rq_pending_amber)
        state.routeStatus is RouteStatus.Failed -> color(R.color.rq_blocked_red)
        else -> color(R.color.rq_text_primary)
    }

    // ------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        mapAdapter?.onResume()
        if (syncRequested) syncCoordinator.start()
    }

    override fun onPause() {
        super.onPause()
        mapAdapter?.onPause()
        // The MVP sync session is foreground-only; backgrounding disconnects cleanly.
        if (syncRequested) syncCoordinator.stop()
    }

    override fun onDestroy() {
        mapAdapter?.onDestroy()
        mapAdapter = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ peer sync

    private fun observeSync() {
        lifecycleScope.launch {
            syncCoordinator.state.collect { renderSyncState(it) }
        }
        lifecycleScope.launch {
            syncCoordinator.authRequests.collect { renderAuthRequests(it) }
        }
    }

    private fun renderSyncState(state: SyncState) {
        styleSyncButtons(running = state is SyncState.Running)
        syncStatusText.text = when (state) {
            SyncState.Stopped -> getString(R.string.rq_sync_stopped)
            is SyncState.Unavailable -> "Peer sync unavailable: ${state.reason}"
            is SyncState.Running -> buildString {
                val transportText = when (val transport = state.transport) {
                    is TransportState.Running ->
                        "advertising=${transport.advertising} discovery=${transport.discovery}"
                    is TransportState.Unavailable -> "unavailable: ${transport.reason}"
                    TransportState.Stopped -> "stopped"
                }
                appendLine("Peer sync: $transportText")
                if (state.peers.isEmpty()) {
                    append("No peers connected")
                } else {
                    state.peers.forEach { peer ->
                        appendLine(
                            "${peer.endpointName}: compatible=${peer.compatible} " +
                                "synced=${peer.synchronized} pending=${peer.pendingEvents}",
                        )
                    }
                    state.lastError?.let { append("last error: $it") }
                }
            }
        }
    }

    /**
     * One card per pending pairing request: the code to compare out loud, then
     * Accept or Reject. Nothing is written until Accept.
     */
    private fun renderAuthRequests(requests: List<AuthRequest>) {
        authContainer.removeAllViews()
        for (request in requests) {
            val card = MaterialCardView(this).apply {
                setCardBackgroundColor(color(R.color.rq_surface_variant))
                strokeWidth = 0
                radius = dp(16).toFloat()
                cardElevation = 0f
            }
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            column.addView(
                textView("Auth code · ${request.endpointName}", R.style.Text_ResQRoute_Section),
            )
            column.addView(
                textView(
                    "${request.authCode}",
                    R.style.Text_ResQRoute_Status,
                    colorRes = R.color.rq_blue,
                    bold = true,
                ).apply { letterSpacing = 0.3f },
            )
            column.addView(
                textView(
                    "Confirm both phones show the same code before accepting.",
                    R.style.Text_ResQRoute_Caption,
                ),
            )

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            row.addView(
                materialButton("Accept ${request.endpointName}", filled = true) {
                    appendLog("Accepted ${request.endpointName} after code confirmation")
                    syncCoordinator.accept(request.sessionId)
                }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) },
            )
            row.addView(
                materialButton("Reject", filled = false) {
                    appendLog("Rejected ${request.endpointName}")
                    syncCoordinator.reject(request.sessionId)
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = dp(8) }
                },
            )
            column.addView(row)
            card.addView(column)
            authContainer.addView(
                card,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
            )
        }
    }

    /**
     * Debug builds only. These run the real commit → send path without map
     * gestures, so end-to-end propagation can be verified on two devices.
     *
     * Built in code (not in the layout) on purpose: a release build contains none
     * of these strings, in the compiled code or in the resources.
     */
    private fun setupDebugHooks() {
        if (!BuildConfig.DEBUG) return
        debugContainer.visibility = View.VISIBLE

        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(color(R.color.rq_surface_variant))
            strokeWidth = 0
            radius = dp(16).toFloat()
            cardElevation = 0f
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        column.addView(textView("Debug test hooks", R.style.Text_ResQRoute_Section))
        column.addView(
            textView(
                "Debug builds only. These run the real commit → send path without map " +
                    "gestures, so end-to-end propagation can be verified on two devices.",
                R.style.Text_ResQRoute_Caption,
            ),
        )
        column.addView(
            materialButton("Debug: route to shelter #2", filled = false) {
                viewModel.onDestinationSelected(1)
                appendLog("Debug: destination set to shelter #2")
            }.stacked(topMarginDp = 10),
        )
        column.addView(
            materialButton("Debug: block a route segment", filled = false) {
                appendLog("Debug: block → ${viewModel.injectDemoBlock(HazardReason.BLOCKED)?.stableId ?: "no segment"}")
            }.stacked(topMarginDp = 8),
        )
        column.addView(
            materialButton("Debug: waterlog a route segment", filled = false) {
                appendLog("Debug: waterlog → ${viewModel.injectDemoBlock(HazardReason.WATERLOGGED)?.stableId ?: "no segment"}")
            }.stacked(topMarginDp = 8),
        )
        card.addView(column)
        debugContainer.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(22) },
        )
    }

    // ------------------------------------------------------------- helpers

    private fun refreshDiagnostics() {
        diagnosticsText.text = SpikeDiagnostics.capture(this).render()
    }

    /**
     * Everything the app needs at runtime: Nearby's set (version-dependent) **and** location
     * (every version, for "report where I am").
     *
     * Both in one request because they are asked for together at first launch, and keeping them in
     * separate flows is how the location grant came to be missing while the log said only Nearby
     * permissions were involved.
     */
    private fun missingRuntimePermissions(): List<String> =
        (NearbyPermissions.missing(this) + LocationPermissions.missing(this)).distinct()

    private fun requestRuntimePermissions() {
        val missing = missingRuntimePermissions()
        appendLog("Requesting permissions: ${missing.joinToString { it.substringAfterLast('.') }}")
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_NEARBY_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NEARBY_PERMISSIONS) {
            val denied = permissions.filterIndexed { i, _ ->
                grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED
            }
            appendLog(
                if (denied.isEmpty()) {
                    "Permissions granted"
                } else {
                    "Denied: ${denied.joinToString { it.substringAfterLast('.') }} — local map still usable"
                },
            )
            refreshDiagnostics()
        }
    }

    private fun appendLog(line: String) {
        val stamped = "${CLOCK.format(Date())}  $line"
        logText.append(if (logText.text.isNullOrEmpty()) stamped else "\n$stamped")
    }

    /**
     * Row labels shrink on narrow phones so the two sync buttons stay exactly the
     * same size and the three mark buttons stay on one line.
     */
    private fun applyResponsiveButtonText() {
        val widthDp = resources.configuration.screenWidthDp
        val textSize = when {
            widthDp < 340 -> 9f
            widthDp < 400 -> 10f
            widthDp < 600 -> 12f
            else -> 13f
        }
        listOf(btnCancel, btnSubmitReport, btnStartSync, btnStopSync).forEach {
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
        }
    }

    /**
     * Peer-sync toggle colours. Start is green, Stop is grey while there is
     * nothing to stop and turns red once sync is actually running, so the state
     * is readable without reading the text.
     */
    private fun styleSyncButtons(running: Boolean) {
        btnStartSync.backgroundTintList = ColorStateList.valueOf(
            color(if (running) R.color.rq_button_grey else R.color.rq_sync_green),
        )
        btnStartSync.setTextColor(color(if (running) R.color.rq_text_secondary else R.color.rq_white))

        btnStopSync.backgroundTintList = ColorStateList.valueOf(
            color(if (running) R.color.rq_blocked_fill else R.color.rq_button_grey),
        )
        btnStopSync.setTextColor(color(if (running) R.color.rq_white else R.color.rq_text_secondary))
    }

    private fun color(colorRes: Int): Int = ContextCompat.getColor(this, colorRes)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun LinearLayout.LayoutParams.setMargins(start: Int, top: Int): LinearLayout.LayoutParams =
        apply { marginStart = start; topMargin = top }

    /** Full-width row inside a vertical stack, with a top margin. */
    private fun MaterialButton.stacked(topMarginDp: Int): MaterialButton = apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(topMarginDp) }
    }

    private fun textView(
        value: String? = null,
        @StyleRes styleRes: Int = R.style.Text_ResQRoute_Body,
        colorRes: Int? = null,
        bold: Boolean = false,
    ): TextView = TextView(this, null, 0, styleRes).apply {
        value?.let { text = it }
        colorRes?.let { setTextColor(color(it)) }
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    /**
     * Buttons created at runtime (auth rows, debug hooks) still follow the theme:
     * `MaterialButton(context)` picks up the Material3 theme's button style, so a
     * filled button is rescue-amber automatically; the outlined variant is the
     * same button with a transparent tint and a stroke.
     */
    private fun materialButton(
        label: String,
        filled: Boolean,
        onClick: () -> Unit,
    ): MaterialButton = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        minHeight = dp(50)
        cornerRadius = dp(14)
        if (!filled) {
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(color(R.color.rq_blue))
        }
        setOnClickListener { onClick() }
    }
}
