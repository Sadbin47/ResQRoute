package com.resqroute.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The durable hazard truth.
 *
 * Two tables only: the event log and the installation identity. There is no
 * persisted `blocked_edges` table — the effective blocked set is derived from
 * the log with a distinct query, which removes a second consistency obligation.
 *
 * Migration policy: migrations are explicit. A destructive fallback is never
 * configured, because silently erasing hazard history would make a flooded road
 * look safe again.
 */
@Database(
    entities = [HazardEventEntity::class, DeviceIdentityEntity::class],
    version = ResQRouteDatabase.VERSION,
    exportSchema = true,
)
abstract class ResQRouteDatabase : RoomDatabase() {

    abstract fun hazardDao(): HazardEventDao

    abstract fun deviceIdentityDao(): DeviceIdentityDao

    companion object {
        /** v2 adds the observation axes; see [MIGRATION_1_2]. */
        const val VERSION = 2
        const val NAME = "resqroute-hazard.db"

        /**
         * v1 -> v2: the two observation axes plus two reserved columns.
         *
         * **Additive and nullable only.** Four `ALTER TABLE ... ADD COLUMN`
         * statements that append columns at the end of the table, each with no
         * default and no `NOT NULL`, so every existing row survives untouched and
         * every new column reads as null on those rows. Null means "this report did
         * not state that axis", which the reader resolves to the v1 interpretation
         * of the row's reason — the effective blocked set is therefore unchanged.
         *
         * No table is rebuilt, no column is dropped or retyped, no index changes and
         * no data is rewritten. A destructive fallback is deliberately not
         * configured anywhere in this project: erasing a report would make a
         * flooded road look safe again.
         *
         * Column order matches a fresh v2 `CREATE TABLE`, because both the migration
         * and the entity append these fields after `receivedAtEpochMillis`.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `hazard_events` ADD COLUMN `levelCode` TEXT")
                db.execSQL("ALTER TABLE `hazard_events` ADD COLUMN `passabilityCode` TEXT")
                db.execSQL("ALTER TABLE `hazard_events` ADD COLUMN `sourceCode` TEXT")
                db.execSQL("ALTER TABLE `hazard_events` ADD COLUMN `accuracyMeters` REAL")
            }
        }

        /**
         * Ordered schema migrations, applied in order by Room.
         *
         * Callers must pass this to `Room.databaseBuilder(...).addMigrations(*MIGRATIONS)`
         * and must never add `fallbackToDestructiveMigration()`.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
    }
}
