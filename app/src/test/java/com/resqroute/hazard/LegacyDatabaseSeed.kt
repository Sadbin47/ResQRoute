package com.resqroute.hazard

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * A hazard database exactly as a v1 build left it.
 *
 * The v1 schema is frozen and this is its only copy in the test sources: the DDL below is
 * copied verbatim from the exported v1 schema (`app/schemas/…/1.json`) and is written with
 * raw SQLite, so Room has to perform a genuine migration when it later opens the file.
 *
 * [readV1BlockedSet] is the other half of the contract: it answers "which segments were
 * excluded" using **v1's own query**, executed by hand against the unmigrated file, before
 * Room is ever involved. Comparing that answer with the one the migrated app produces is
 * what makes the v1 → v2 guarantee observable rather than assumed — the expectation is not
 * hardcoded, it is asked of v1 itself.
 */
internal object LegacyDatabaseSeed {

    /** One v1 row, in v1's shape: a reason, and nothing else. */
    data class Row(
        val eventId: String,
        val segment: Long,
        val reason: String,
        val deviceId: String = "device-fixed-0001",
        val occurredAt: Long = HazardFixtures.FIXED_CLOCK,
        val receivedAt: Long = HazardFixtures.FIXED_CLOCK,
        val datasetHash: String = HazardFixtures.DATASET_HASH,
        val incidentId: String = HazardFixtures.INCIDENT,
    )

    /**
     * v1's effective blocked set, in v1's own words: distinct segments for the dataset and
     * incident, with no filter on the reason at all — which is exactly why `WATERLOGGED`
     * and `BLOCKED` were both hard exclusions.
     */
    const val V1_BLOCKED_SET_SQL =
        "SELECT DISTINCT segmentId FROM hazard_events " +
            "WHERE datasetHash = ? AND incidentId = ? ORDER BY segmentId"

    /**
     * Writes the file as v1 left it: the v1 tables, the v1 index, the rows, and
     * `PRAGMA user_version = 1`. The file must not already exist.
     */
    fun write(context: Context, databaseName: String, rows: List<Row>) {
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()
        val legacy = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            legacy.execSQL(HAZARD_EVENTS)
            legacy.execSQL(HAZARD_EVENTS_INDEX)
            legacy.execSQL(DEVICE_IDENTITY)
            for (row in rows) {
                legacy.execSQL(
                    "INSERT INTO `hazard_events` " +
                        "(`eventId`, `datasetHash`, `incidentId`, `segmentId`, `originDeviceId`, " +
                        "`reasonCode`, `occurredAtEpochMillis`, `receivedAtEpochMillis`) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        row.eventId,
                        row.datasetHash,
                        row.incidentId,
                        row.segment,
                        row.deviceId,
                        row.reason,
                        row.occurredAt,
                        row.receivedAt,
                    ),
                )
            }
            legacy.version = V1_VERSION
        } finally {
            legacy.close()
        }
    }

    /** Runs [V1_BLOCKED_SET_SQL] against the file, read-only and untouched. */
    @Suppress("DEPRECATION") // The File+OpenParams overload cannot open read-only without a builder.
    fun readV1BlockedSet(
        context: Context,
        databaseName: String,
        datasetHash: String,
        incidentId: String,
    ): Set<Long> {
        val file = context.getDatabasePath(databaseName)
        val legacy = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val blocked = LinkedHashSet<Long>()
            legacy.rawQuery(V1_BLOCKED_SET_SQL, arrayOf(datasetHash, incidentId)).use { cursor ->
                while (cursor.moveToNext()) blocked.add(cursor.getLong(0))
            }
            return blocked
        } finally {
            legacy.close()
        }
    }

    /** Distinct reason codes v1 recorded for one context — used to prove a fixture is mixed. */
    @Suppress("DEPRECATION") // Same overload constraint as [readV1BlockedSet].
    fun readV1Reasons(
        context: Context,
        databaseName: String,
        datasetHash: String,
        incidentId: String,
    ): Set<String> {
        val file = context.getDatabasePath(databaseName)
        val legacy = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val reasons = LinkedHashSet<String>()
            legacy.rawQuery(
                "SELECT DISTINCT reasonCode FROM hazard_events " +
                    "WHERE datasetHash = ? AND incidentId = ?",
                arrayOf(datasetHash, incidentId),
            ).use { cursor ->
                while (cursor.moveToNext()) reasons.add(cursor.getString(0))
            }
            return reasons
        } finally {
            legacy.close()
        }
    }

    /** `PRAGMA user_version` of a v1 build. */
    const val V1_VERSION = 1

    /** Frozen v1 schema, copied verbatim from `app/schemas/…/1.json`. */
    const val HAZARD_EVENTS =
        "CREATE TABLE IF NOT EXISTS `hazard_events` (" +
            "`eventId` TEXT NOT NULL, " +
            "`datasetHash` TEXT NOT NULL, " +
            "`incidentId` TEXT NOT NULL, " +
            "`segmentId` INTEGER NOT NULL, " +
            "`originDeviceId` TEXT NOT NULL, " +
            "`reasonCode` TEXT NOT NULL, " +
            "`occurredAtEpochMillis` INTEGER NOT NULL, " +
            "`receivedAtEpochMillis` INTEGER NOT NULL, " +
            "PRIMARY KEY(`eventId`))"

    const val HAZARD_EVENTS_INDEX =
        "CREATE INDEX IF NOT EXISTS " +
            "`index_hazard_events_datasetHash_incidentId_segmentId` " +
            "ON `hazard_events` (`datasetHash`, `incidentId`, `segmentId`)"

    const val DEVICE_IDENTITY =
        "CREATE TABLE IF NOT EXISTS `device_identity` (" +
            "`singletonId` INTEGER NOT NULL, " +
            "`deviceId` TEXT NOT NULL, " +
            "PRIMARY KEY(`singletonId`))"
}
