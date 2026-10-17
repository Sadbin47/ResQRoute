package com.resqroute.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Durable hazard event log.
 *
 * These functions are the only database access for hazard truth. The repository
 * wraps them in a validation + transaction boundary; no other component may call
 * this DAO to write.
 */
@Dao
interface HazardEventDao {

    /** Insert with IGNORE: a duplicate primary key returns -1 and changes nothing. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: HazardEventEntity): Long

    @Query("SELECT * FROM hazard_events WHERE eventId = :eventId")
    suspend fun findEvent(eventId: String): HazardEventEntity?

    /**
     * Live view of every committed row for one context.
     *
     * The effective blocked set is **not** queried here. It is derived from these rows by
     * `RoomHazardRepository` through the single resolution rule
     * (`observationFor`) and `SegmentObservationAggregator`, so there is exactly one
     * place that decides what a row means. A `SELECT DISTINCT segmentId` projection would
     * be a second, silently diverging derivation — and it could not honour the observation
     * axes or the fail-closed reading of an unrecognised code (ADR 0004 §2).
     */
    @Query(
        """
        SELECT * FROM hazard_events
        WHERE datasetHash = :datasetHash AND incidentId = :incidentId
        ORDER BY eventId
        """,
    )
    fun observeEvents(datasetHash: String, incidentId: String): Flow<List<HazardEventEntity>>

    /** Every committed row for one segment, for deriving whether a report changes routing. */
    @Query(
        """
        SELECT * FROM hazard_events
        WHERE datasetHash = :datasetHash AND incidentId = :incidentId AND segmentId = :segmentId
        ORDER BY eventId
        """,
    )
    suspend fun eventsForSegment(
        datasetHash: String,
        incidentId: String,
        segmentId: Long,
    ): List<HazardEventEntity>

    /**
     * Cold enumeration of every committed event for one context.
     *
     * Phase 8 pages this for replay; at MVP scale the whole incident is small.
     */
    @Query(
        """
        SELECT * FROM hazard_events
        WHERE datasetHash = :datasetHash AND incidentId = :incidentId
        ORDER BY eventId
        """,
    )
    suspend fun events(datasetHash: String, incidentId: String): List<HazardEventEntity>

    @Query(
        """
        SELECT COUNT(*) FROM hazard_events
        WHERE datasetHash = :datasetHash AND incidentId = :incidentId
        """,
    )
    suspend fun eventCount(datasetHash: String, incidentId: String): Int
}

/** Single-row installation identity. */
@Dao
interface DeviceIdentityDao {

    /** Insert with IGNORE so the first random id wins permanently. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(identity: DeviceIdentityEntity): Long

    @Query("SELECT * FROM device_identity WHERE singletonId = 1")
    suspend fun get(): DeviceIdentityEntity?
}
