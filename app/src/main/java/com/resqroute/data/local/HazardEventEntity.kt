package com.resqroute.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One durable hazard report.
 *
 * The event log is the source of truth: the effective blocked set is derived
 * from it with a distinct query rather than stored separately. All IDs are
 * stable strings so a forwarded event keeps its original identity.
 *
 * [occurredAtEpochMillis] is the origin's claimed time (untrusted, audit only);
 * [receivedAtEpochMillis] is local audit data and is never distributed.
 *
 * ## The v2 observation axes
 *
 * [levelCode] and [passabilityCode] carry the two independent axes decided in
 * ADR 0001 (D1). They are **nullable on purpose**, and `null` has exactly one
 * meaning: *this report did not state that axis*. It never means "safe", "zero"
 * or "no water".
 *
 * A report that carries only [reasonCode] — every v1 report, and every report
 * produced by a plain block — stores `null` here, and is read back as the v1
 * interpretation of its reason. That is what keeps a v1 database byte-identical
 * in effect after the v1 -> v2 migration, and it is why these columns can be
 * added without touching the derived blocked set.
 *
 * [sourceCode] and [accuracyMeters] are **reserved**: no code writes them yet.
 * Source typing and location-assisted reporting are Phase 14.4 (XPH-73) and
 * Phase 17.2 (XPH-90); until then they stay null, which again means "not
 * recorded" rather than a default value.
 */
@Entity(
    tableName = "hazard_events",
    indices = [
        Index(value = ["datasetHash", "incidentId", "segmentId"]),
    ],
)
data class HazardEventEntity(
    @PrimaryKey
    val eventId: String,

    val datasetHash: String,
    val incidentId: String,
    val segmentId: Long,
    val originDeviceId: String,
    val reasonCode: String,

    val occurredAtEpochMillis: Long,
    val receivedAtEpochMillis: Long,

    /** `WaterLevelCategory.code`; null when the report did not state a water level. */
    val levelCode: String? = null,

    /** `PassabilityStatus.code`; null when the report did not state passability. */
    val passabilityCode: String? = null,

    /** Reserved for Phase 14.4 source typing. Always null today. */
    val sourceCode: String? = null,

    /** Reserved for Phase 17.2 location-assisted reporting. Always null today. */
    val accuracyMeters: Double? = null,
)
