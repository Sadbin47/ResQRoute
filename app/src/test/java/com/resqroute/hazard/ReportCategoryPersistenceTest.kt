package com.resqroute.hazard

import android.app.Application
import android.content.Context
import com.resqroute.core.model.SegmentId
import com.resqroute.data.local.ResQRouteDatabase
import com.resqroute.data.repository.RoomHazardRepository
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.MutationResult
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.WaterLevelCategory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the reporting picker's categories do once they are stored (XPH-70).
 *
 * Through the **real** Room repository and the real derivation, not a fake, because the acceptance
 * criteria are about persistence: each category must store the level the observer chose, and an
 * unknown depth must store no depth claim and must never close a road.
 *
 * The distinction that matters here, and it is the one easy to get wrong: `UNKNOWN` is stored as the
 * *value* `UNKNOWN`, not as a null. Null means "this report did not state an axis" — the v1 shape;
 * `UNKNOWN` means "the observer was asked and could not estimate", which is a stronger and different
 * statement (ADR 0001 D1: `UNKNOWN` is a first-class value, not a missing-value placeholder).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReportCategoryPersistenceTest {

    private lateinit var context: Context
    private lateinit var database: ResQRouteDatabase
    private lateinit var scope: CoroutineScope
    private val eventIds = AtomicInteger(0)

    @Before
    fun setUp() {
        context = HazardFixtures.context()
        context.deleteDatabase(HazardFixtures.DATABASE_NAME)
        database = HazardFixtures.openDatabase(context)
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    }

    @After
    fun tearDown() {
        runCatching { database.close() }
        context.deleteDatabase(HazardFixtures.DATABASE_NAME)
        scope.cancel()
    }

    @Test
    fun everyCategoryPersistsTheLevelTheObserverChoseAndNoneOfThemClosesTheRoad() = runBlocking {
        val repository = initialized()
        var segment = 100
        val checked = mutableListOf<WaterLevelCategory>()

        for (level in WaterLevelCategory.entries) {
            // CAUTION cannot exclude, so this isolates the water axis completely.
            val id = SegmentId(segment++)
            val result = repository.recordLocal(id, HazardReason.WATERLOGGED, level, PassabilityStatus.CAUTION)
            assertTrue("$level must be accepted, was $result", result is MutationResult.Accepted)
            checked += level

            val row = database.hazardDao().events(HazardFixtures.DATASET_HASH, HazardFixtures.INCIDENT)
                .single { it.segmentId == id.value.toLong() }
            assertEquals("$level must persist as its own code", level.code, row.levelCode)
            assertEquals(PassabilityStatus.CAUTION.code, row.passabilityCode)

            val state = HazardFixtures.awaitObservedState(repository, id.value)
            assertEquals(level, state.worstWaterLevel)
            assertFalse("a caution report must never close the road ($level)", state.excludesTraversal)
        }

        assertEquals("the sweep must cover every category", WaterLevelCategory.entries.toList(), checked)
        HazardFixtures.awaitDerivedState(repository) { states -> states.size == WaterLevelCategory.entries.size }
        assertEquals(
            "no category may appear in the excluded set",
            emptySet<SegmentId>(),
            HazardFixtures.awaitReady(repository).snapshot.blockedSegments,
        )
    }

    @Test
    fun anUnknownDepthIsStoredAsTheValueUnknownNotAsAMissingAxis() = runBlocking {
        val repository = initialized()
        val id = SegmentId(200)
        assertTrue(
            repository.recordLocal(id, HazardReason.WATERLOGGED, WaterLevelCategory.UNKNOWN, PassabilityStatus.CAUTION)
                is MutationResult.Accepted,
        )

        val row = database.hazardDao().events(HazardFixtures.DATASET_HASH, HazardFixtures.INCIDENT)
            .single { it.segmentId == id.value.toLong() }
        assertEquals(
            "UNKNOWN is a stated 'could not estimate', not a missing axis",
            WaterLevelCategory.UNKNOWN.code,
            row.levelCode,
        )
        val state = HazardFixtures.awaitObservedState(repository, id.value)
        assertEquals(WaterLevelCategory.UNKNOWN, state.worstWaterLevel)
        assertFalse("and it removes nothing from the search", state.excludesTraversal)
        HazardFixtures.awaitDerivedState(repository) { states -> states.size == 1 }
        assertTrue(HazardFixtures.awaitReady(repository).snapshot.blockedSegments.isEmpty())
    }

    @Test
    fun onlyTheImpassableVerdictClosesARoadWhateverTheDepth() = runBlocking {
        val repository = initialized()
        val closedByVerdict = SegmentId(300)
        val depthless = SegmentId(301)

        // A depth with an impassable verdict: the collapsed-bridge case from ADR 0002.
        assertTrue(
            repository.recordLocal(
                closedByVerdict, HazardReason.BLOCKED,
                WaterLevelCategory.NO_VISIBLE_WATER, PassabilityStatus.IMPASSABLE,
            ) is MutationResult.Accepted,
        )
        // No depth claim at all, and an impassable verdict: the v1 BLOCKED report, unchanged.
        assertTrue(
            repository.recordLocal(
                depthless, HazardReason.BLOCKED,
                WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE,
            ) is MutationResult.Accepted,
        )

        // Wait for the derivation, not merely for Ready: the repository was already Ready before
        // these reports, so awaitReady would hand back the previous snapshot.
        HazardFixtures.awaitDerivedState(repository) { states -> states.size == 2 }
        val blocked = HazardFixtures.awaitReady(repository).snapshot.blockedSegments.map { it.value }.toSet()
        assertEquals("the verdict, not the depth, decides", setOf(300, 301), blocked)

        val rows = database.hazardDao().events(HazardFixtures.DATASET_HASH, HazardFixtures.INCIDENT)
        val withDepth = rows.single { it.segmentId == 300L }
        val withoutDepth = rows.single { it.segmentId == 301L }
        assertEquals(WaterLevelCategory.NO_VISIBLE_WATER.code, withDepth.levelCode)
        assertEquals(PassabilityStatus.IMPASSABLE.code, withDepth.passabilityCode)
        assertNull(
            "a v1-shaped report keeps the v1 storage shape: no axes stated",
            withoutDepth.levelCode,
        )
    }

    @Test
    fun aReportThatStatesNothingIsRejectedAndNothingIsWritten() = runBlocking {
        val repository = initialized()
        val result = repository.recordLocal(
            SegmentId(400), HazardReason.WATERLOGGED,
            WaterLevelCategory.UNKNOWN, PassabilityStatus.UNKNOWN,
        )
        assertTrue("an empty report must be refused, was $result", result is MutationResult.Rejected)
        assertEquals(
            "and nothing may be persisted for it",
            0,
            database.hazardDao().eventCount(HazardFixtures.DATASET_HASH, HazardFixtures.INCIDENT),
        )
        HazardFixtures.awaitDerivedState(repository) { states -> states.isEmpty() }
        assertTrue(HazardFixtures.awaitReady(repository).snapshot.blockedSegments.isEmpty())
    }

    @Test
    fun theContradictoryPairIsRejectedAndNothingIsWritten() = runBlocking {
        val repository = initialized()
        val result = repository.recordLocal(
            SegmentId(500), HazardReason.WATERLOGGED,
            WaterLevelCategory.ABOVE_WAIST, PassabilityStatus.PASSABLE_REPORTED,
        )
        assertTrue("an above-waist road cannot be reported passable, was $result", result is MutationResult.Rejected)
        assertEquals(
            0,
            database.hazardDao().eventCount(HazardFixtures.DATASET_HASH, HazardFixtures.INCIDENT),
        )
    }

    private suspend fun initialized(): RoomHazardRepository {
        val repository = HazardFixtures.repository(database, scope, eventIds = eventIds)
        repository.initialize(HazardFixtures.contextAlpha)
        HazardFixtures.awaitReady(repository)
        return repository
    }
}
