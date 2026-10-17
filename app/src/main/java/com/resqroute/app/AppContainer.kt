package com.resqroute.app

import android.content.Context
import androidx.room.Room
import com.resqroute.data.dataset.BundledDatasetSource
import com.resqroute.data.local.ResQRouteDatabase
import com.resqroute.data.repository.BundledMapRepository
import com.resqroute.data.location.AndroidLocationSource
import com.resqroute.data.repository.RoomHazardRepository
import com.resqroute.domain.location.LocationSource
import com.resqroute.data.p2p.HazardSyncCoordinator
import com.resqroute.data.p2p.NearbyNetworkManager
import com.resqroute.domain.hazard.HazardContext
import com.resqroute.domain.hazard.HazardRepository
import com.resqroute.domain.map.DatasetState
import com.resqroute.domain.map.MapRepository
import com.resqroute.domain.sync.SyncConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-scoped object graph.
 *
 * The container owns the two sources of truth: the bundled map repository and
 * the Room hazard repository. No DAO is exposed; hazard writes can only happen
 * through [hazardRepository], so it stays the single mutation ingress.
 *
 * Application-owned scope: dataset loading and hazard observation live for the
 * whole process. Nothing here touches the Main thread.
 */
class AppContainer(context: Context) {

    private val applicationContext = context.applicationContext

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val mapRepository: MapRepository = BundledMapRepository(
        source = BundledDatasetSource(applicationContext),
    )

    /**
     * Durable hazard truth.
     *
     * Explicit migrations only — a destructive fallback is deliberately absent so
     * that a schema change can never silently erase reported blocks.
     */
    val database: ResQRouteDatabase = Room.databaseBuilder(
        applicationContext,
        ResQRouteDatabase::class.java,
        ResQRouteDatabase.NAME,
    )
        .addMigrations(*ResQRouteDatabase.MIGRATIONS)
        .build()

    /**
     * Segment-range validation needs to know the loaded graph. It is provided
     * lazily, because the database exists before the dataset finishes loading.
     */
    /**
     * Last-known location for location-assisted reporting (XPH-90).
     *
     * Declared here so the ViewModel never touches the location framework and every test can supply
     * a fake. Read-only, and only ever asked for a fix when a person taps "report where I am".
     */
    val locationSource: LocationSource = AndroidLocationSource(applicationContext)

    val hazardRepository: HazardRepository = RoomHazardRepository(
        database = database,
        segmentCountProvider = {
            (mapRepository.state.value as? DatasetState.Ready)?.dataset?.graph?.segmentCount
        },
        scope = applicationScope,
    )

    /**
     * Foreground peer synchronization (Phase 8).
     *
     * The transport is Nearby; the coordinator owns the protocol and feeds the
     * same [hazardRepository] used by local marks. Started/stopped by the screen
     * so the MVP session only exists in the foreground.
     */
    val syncCoordinator: HazardSyncCoordinator by lazy {
        HazardSyncCoordinator(
            transport = NearbyNetworkManager(
                context = applicationContext,
                installationIdProvider = { (hazardRepository as? RoomHazardRepository)?.deviceId?.value },
            ),
            repository = hazardRepository,
            scope = applicationScope,
            contextProvider = {
                (mapRepository.state.value as? DatasetState.Ready)?.let { ready ->
                    HazardContext(ready.dataset.identity, SyncConfig.INCIDENT_ID)
                }
            },
            deviceIdProvider = { (hazardRepository as? RoomHazardRepository)?.deviceId },
        )
    }

    /**
     * Starts the one-time dataset load.
     *
     * Called from [ResQRouteApplication.onCreate]; it only launches work, so
     * `Application.onCreate` itself never parses or builds the graph.
     */
    fun start() {
        applicationScope.launch { mapRepository.initialize() }
    }
}
