package com.resqroute.hazard

import android.app.Application
import android.content.Context
import com.resqroute.data.local.HazardEventEntity
import com.resqroute.data.local.ResQRouteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The v1 -> v2 Room migration, run for real.
 *
 * The database is created by [LegacyDatabaseSeed] as v1 would have left it — raw SQL,
 * `user_version = 1`, rows already committed — and then opened through Room with the
 * production [ResQRouteDatabase.MIGRATIONS]. Room therefore performs the actual upgrade path:
 * run the migration, validate the resulting schema against the expected v2 schema, and only
 * then let a query through. A schema mismatch would fail the test, which is the point.
 *
 * This class covers the *mechanics* of the upgrade (rows survive, new columns are null, the
 * version moves). The *meaning* of the upgrade — that v1's excluded set is unchanged through
 * migration, restart and replay — is pinned by [LegacySemanticsRegressionTest].
 *
 * Covers XPH-68 acceptance criteria and rows 3 and 4 of the mandatory test matrix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HazardMigrationTest {

    private lateinit var context: Context
    private lateinit var database: ResQRouteDatabase

    @Before
    fun setUp() {
        context = HazardFixtures.context()
        context.deleteDatabase(HazardFixtures.DATABASE_NAME)
    }

    @After
    fun tearDown() {
        runCatching { database.close() }
        context.deleteDatabase(HazardFixtures.DATABASE_NAME)
    }

    // ------------------------------------------------------------- the test

    @Test
    fun migrationFromV1PreservesEveryRowAndLeavesTheNewColumnsNull() = runBlocking {
        seed(
            LegacyDatabaseSeed.Row("evt-v1-blocked-0001", segment = 31, reason = "BLOCKED", occurredAt = 1_600_000_000_001L),
            LegacyDatabaseSeed.Row("evt-v1-flooded-0002", segment = 32, reason = "WATERLOGGED", occurredAt = 1_600_000_000_002L),
            LegacyDatabaseSeed.Row("evt-v1-flooded-0003", segment = 33, reason = "WATERLOGGED", occurredAt = 1_600_000_000_003L),
        )

        val dao = openMigratedDatabase().hazardDao()

        // Every row survived, with its identity, provenance and timestamps intact.
        val rows = dao.events(HazardFixtures.DATASET_HASH, HazardFixtures.INCIDENT)
        assertEquals(3, rows.size)
        assertEquals(
            setOf("evt-v1-blocked-0001", "evt-v1-flooded-0002", "evt-v1-flooded-0003"),
            rows.map { it.eventId }.toSet(),
        )
        assertEquals(setOf(31L, 32L, 33L), rows.map { it.segmentId }.toSet())
        assertEquals(
            mapOf(31L to "BLOCKED", 32L to "WATERLOGGED", 33L to "WATERLOGGED"),
            rows.associate { it.segmentId to it.reasonCode },
        )
        assertEquals(1_600_000_000_002L, rows.first { it.segmentId == 32L }.occurredAtEpochMillis)
        assertEquals(
            HazardFixtures.FIXED_CLOCK,
            rows.first { it.segmentId == 32L }.receivedAtEpochMillis,
        )

        // The four new columns exist and are null on every pre-existing row: "not
        // stated", which the reader resolves to the v1 interpretation of the reason.
        for (row in rows) {
            assertNull("levelCode must be null after migration", row.levelCode)
            assertNull("passabilityCode must be null after migration", row.passabilityCode)
            assertNull("sourceCode must be null after migration", row.sourceCode)
            assertNull("accuracyMeters must be null after migration", row.accuracyMeters)
        }
    }

    @Test
    fun theMigratedDatabaseIsAtVersionTwo() = runBlocking {
        seed(LegacyDatabaseSeed.Row("evt-v1-blocked-0001", segment = 31, reason = "BLOCKED"))
        val database = openMigratedDatabase()
        // Forces the open (and therefore the migration) to happen.
        database.hazardDao().eventCount(HazardFixtures.DATASET_HASH, HazardFixtures.INCIDENT)
        assertEquals(2, database.openHelper.writableDatabase.version)
    }

    @Test
    fun theEffectiveBlockedSetIsUnchangedByTheMigration() = runBlocking {
        seed(
            LegacyDatabaseSeed.Row("evt-v1-blocked-0001", segment = 31, reason = "BLOCKED"),
            LegacyDatabaseSeed.Row("evt-v1-flooded-0002", segment = 32, reason = "WATERLOGGED"),
        )
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            // Through the repository, because "effective blocked set" is now a derivation
            // rather than a query projection (XPH-69).
            val repository = HazardFixtures.repository(openMigratedDatabase(), scope)
            repository.initialize(HazardFixtures.contextAlpha)
            val ready = HazardFixtures.awaitReady(repository)

            // v1 excluded every reported segment regardless of reason; so does v2 for a
            // v1-shaped row. LegacySemanticsRegressionTest owns the full sweep.
            assertEquals(setOf(31, 32), ready.snapshot.blockedSegments.map { it.value }.toSet())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theMigratedColumnsAreUsableBeforeAndAfterRowsThatPredateThem() = runBlocking {
        seed(LegacyDatabaseSeed.Row("evt-v1-blocked-0001", segment = 31, reason = "BLOCKED"))
        val dao = openMigratedDatabase().hazardDao()

        // A v2 row can be written next to the migrated v1 row, and reads back with axes.
        dao.insert(
            HazardEventEntity(
                eventId = "evt-v2-knee-0002",
                datasetHash = HazardFixtures.DATASET_HASH,
                incidentId = HazardFixtures.INCIDENT,
                segmentId = 40L,
                originDeviceId = "device-fixed-0001",
                reasonCode = "WATERLOGGED",
                occurredAtEpochMillis = HazardFixtures.FIXED_CLOCK,
                receivedAtEpochMillis = HazardFixtures.FIXED_CLOCK,
                levelCode = "KNEE",
                passabilityCode = "CAUTION",
            ),
        )

        val rows = dao.events(HazardFixtures.DATASET_HASH, HazardFixtures.INCIDENT).associateBy { it.eventId }
        assertEquals(2, rows.size)
        assertNull(rows.getValue("evt-v1-blocked-0001").levelCode)
        assertEquals("KNEE", rows.getValue("evt-v2-knee-0002").levelCode)
        assertEquals("CAUTION", rows.getValue("evt-v2-knee-0002").passabilityCode)
    }

    // ---------------------------------------------------------------- helpers

    private fun openMigratedDatabase(): ResQRouteDatabase =
        HazardFixtures.openDatabase(context, HazardFixtures.DATABASE_NAME).also { database = it }

    private fun seed(vararg rows: LegacyDatabaseSeed.Row) {
        LegacyDatabaseSeed.write(context, HazardFixtures.DATABASE_NAME, rows.toList())
    }
}
