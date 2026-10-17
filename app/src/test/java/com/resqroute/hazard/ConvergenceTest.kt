package com.resqroute.hazard

import com.resqroute.core.model.SegmentId
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.ObservationSource
import com.resqroute.domain.hazard.ObservedReport
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.SegmentObservationAggregator
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Convergence and replay properties of the derivation (XPH-79).
 *
 * The claim under test, stated once: **the derived state is a function of the committed event set** —
 * a maximum over severity plus a distinct-device count over a grow-only set, with the one documented
 * exception that an explicit, credible, strictly newer clearance resolves the closures older than it.
 *
 * Everything here is checked by *applying the same events in many orders* rather than by reading the
 * implementation, because that is the property the sync layer depends on: two phones that hold the same
 * events must answer "can I use this road?" identically, whatever each of them received first. Duplicates
 * are not tested here: they are absorbed at the single ingress by event id
 * (`RoomHazardRepository.ingestRemote`), so the aggregation's input is already a set — that property is
 * proven where duplicates are actually handled, in [ObservationAggregationPersistenceTest].
 */
class ConvergenceTest {

    private val t0 = 1_700_000_000_000L
    private val hour = 3_600_000L

    /**
     * A deliberately messy seven-event set, covering all three outcomes at once:
     *
     *  * **101 is reopened** — one closure, one credible clearance newer than it.
     *  * **202 stays closed** — its closure is resolved, but a row this build cannot read keeps failing
     *    closed (ADR 0004 §2), so a resolved closure is not the same as an open road.
     *  * **303 is untouched** — an ordinary mild observation, plus a clearance with nothing to clear,
     *    which ADR 0005 §8 requirement 7 says is a no-op recording an ordinarily passable observation.
     */
    private fun mixedEventSet(): List<ObservedReport> = listOf(
        impassable("evt-m-0001", 101, at = t0 - 2 * hour),
        HazardFixtures.clearedReport(
            "evt-m-0002", device = "device-d-0004", segmentId = 101, occurredAt = t0 + hour,
            source = ObservationSource.RELAYED_ACCOUNT,
        ),
        impassable("evt-m-0003", 202, at = t0 + 30 * hour, device = "device-f-0006"),
        HazardFixtures.clearedReport(
            "evt-m-0004", device = "device-g-0007", segmentId = 202, occurredAt = t0 + 31 * hour,
        ),
        HazardFixtures.unreadableReport("evt-m-0005", device = "device-e-0005", segmentId = 202, occurredAt = t0),
        HazardFixtures.observedReport(
            "evt-m-0006", "device-c-0003", 303, occurredAt = t0,
            observation = HazardFixtures.observation(
                HazardReason.WATERLOGGED, WaterLevelCategory.ANKLE, PassabilityStatus.CAUTION,
            ),
        ),
        HazardFixtures.clearedReport(
            "evt-m-0007", device = "device-b-0002", segmentId = 303, occurredAt = t0 + 2 * hour,
        ),
    )

    @Test
    fun everyOrderingOfAMixedEventSetDerivesTheSameState() {
        val events = mixedEventSet()
        val reference = SegmentObservationAggregator.aggregate(events)

        // Not vacuously true: one segment reopened, one still closed, one untouched.
        val reopened = reference.getValue(SegmentId(101))
        assertFalse("101 is reopened by its clearance", reopened.excludesTraversal)
        assertEquals(listOf("evt-m-0001"), reopened.resolvedClosures.map { it.eventId.value })

        val stillClosed = reference.getValue(SegmentId(202))
        assertTrue("202 stays closed: an unreadable row fails closed", stillClosed.excludesTraversal)
        assertEquals("its own closure was resolved", 1, stillClosed.resolvedClosures.size)
        assertTrue(stillClosed.hasUnreadableReports)

        val untouched = reference.getValue(SegmentId(303))
        assertFalse(untouched.excludesTraversal)
        assertEquals("a clearance with nothing to clear resolves nothing", 0, untouched.resolvedClosures.size)

        var orders = 0
        for (permutation in permutations(events)) {
            orders++
            assertEquals(
                "a different arrival order produced a different state",
                reference,
                SegmentObservationAggregator.aggregate(permutation),
            )
        }
        assertEquals("all orderings were actually exercised", 5040, orders) // 7!
    }

    @Test
    fun aClearanceResolvesTheSameClosuresInEveryOrdering() {
        // The reopening rule is the one place a claimed time is consulted, so it gets its own exhaustive
        // check: the *set* of resolved closures must not depend on arrival order either.
        val events = mixedEventSet()
        val resolvedByEach = mutableSetOf<String>()
        for (permutation in permutations(events)) {
            val states = SegmentObservationAggregator.aggregate(permutation)
            for ((segment, state) in states) {
                resolvedByEach += segment.value.toString() + ":" +
                    state.resolvedClosures.joinToString(",") { it.eventId.value }
                for (closure in state.resolvedClosures) {
                    val clearing = state.clearingClearance(closure)
                    assertTrue(
                        "a resolved closure must be attributable to a clearance in the same state",
                        clearing != null && clearing in state.reports,
                    )
                }
            }
        }
        assertEquals("one distinct answer per segment across every ordering", 3, resolvedByEach.size)
        assertEquals(
            setOf("101:evt-m-0001", "202:evt-m-0003", "303:"),
            resolvedByEach,
        )
    }

    @Test
    fun aHundredSeededShufflesOfALargerEventSetAgree() {
        // Same property, a bigger set, orders chosen by a fixed seed so a failure is reproducible.
        val events = largerEventSet()
        val reference = SegmentObservationAggregator.aggregate(events)
        val random = Random(20261016)

        repeat(100) { attempt ->
            val shuffled = events.shuffled(random)
            assertEquals(
                "shuffle #$attempt diverged",
                reference,
                SegmentObservationAggregator.aggregate(shuffled),
            )
        }
    }

    @Test
    fun arrivalStampsChangeNothingThatDecidesARoute() {
        // "Delayed application": the same events, received in a different order, carry different local
        // receipt stamps - so the *state value* legitimately differs (it holds the reports, each with its
        // own times, for display). What must not differ is every decision and count derived from them.
        val events = largerEventSet()
        val reference = decisionsOf(SegmentObservationAggregator.aggregate(events))
        val random = Random(79)

        repeat(50) { attempt ->
            val relabelled = events.mapIndexed { index, report ->
                report.copy(receivedAtEpochMillis = report.receivedAtEpochMillis + random.nextLong(0, 10 * hour) + index)
            }.shuffled(random)
            assertEquals(
                "arrival timing changed a decision (#$attempt)",
                reference,
                decisionsOf(SegmentObservationAggregator.aggregate(relabelled)),
            )
        }
        // And the fixture is doing what it claims: the display-level times really did move.
        val moved = events.mapIndexed { index, report -> report.copy(receivedAtEpochMillis = report.receivedAtEpochMillis + index + 1) }
        assertTrue(
            "the state value itself is expected to differ, because it carries the reports",
            SegmentObservationAggregator.aggregate(moved) != SegmentObservationAggregator.aggregate(events),
        )
    }

    @Test
    fun theGrowOnlyReductionIsMonotonic() {
        // The other half of the claim from the issue: with no clearance in the log, the derivation is a
        // maximum over severity plus a distinct-device count over a grow-only set. Adding an event to that
        // set can therefore never un-exclude a segment, never lighten its verdict and never deepen-then-
        // shallow its level.
        val base = listOf(
            HazardFixtures.observedReport(
                "evt-n-0001", "device-fixed-0001", 303, occurredAt = t0,
                observation = HazardFixtures.observation(
                    HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION,
                ),
            ),
        )
        val additions = listOf(
            HazardFixtures.observedReport(
                "evt-n-0002", "device-b-0002", 303, occurredAt = t0 + hour,
                observation = HazardFixtures.observation(
                    HazardReason.WATERLOGGED, WaterLevelCategory.NO_VISIBLE_WATER, PassabilityStatus.PASSABLE_REPORTED,
                ),
            ),
            HazardFixtures.observedReport(
                "evt-n-0003", "device-c-0003", 303, occurredAt = t0 + 2 * hour,
                observation = HazardFixtures.observation(
                    HazardReason.WATERLOGGED, WaterLevelCategory.ANKLE, PassabilityStatus.UNKNOWN,
                ),
            ),
            impassable("evt-n-0004", 303, at = t0 + 3 * hour, device = "device-d-0004"),
            HazardFixtures.unreadableReport("evt-n-0005", "device-e-0005", 303, occurredAt = t0 + 4 * hour),
        )

        var current = SegmentObservationAggregator.aggregateSegment(SegmentId(303), base)
        val baseLevel = current.worstWaterLevel
        for (addition in additions) {
            val next = SegmentObservationAggregator.aggregateSegment(SegmentId(303), base + addition)
            assertTrue(
                "adding ${addition.eventId.value} removed an exclusion",
                !current.excludesTraversal || next.excludesTraversal,
            )
            assertTrue(
                "adding ${addition.eventId.value} lightened the level",
                rank(next.worstWaterLevel.statedRank()) >= rank(baseLevel.statedRank()),
            )
            assertTrue(
                "adding ${addition.eventId.value} lightened the verdict",
                rank(next.passability.statedRank()) >= rank(current.passability.statedRank()),
            )
            assertTrue("support is grow-only", next.supportCount >= current.supportCount)
            assertEquals("and nothing was resolved", 0, next.resolvedClosures.size)
            current = next
        }
        assertTrue("the exercise actually reached an exclusion", current.excludesTraversal)
    }

    // -------------------------------------------------------------- tiny helpers

    private fun impassable(eventId: String, segment: Int, at: Long, device: String = "device-fixed-0001") =
        HazardFixtures.observedReport(
            eventId, device, segment, occurredAt = at,
            observation = HazardFixtures.observation(
                HazardReason.WATERLOGGED, WaterLevelCategory.WAIST, PassabilityStatus.IMPASSABLE,
            ),
        )

    /** Twelve events across three segments: closures, mild reports, clearances, an unreadable row. */
    private fun largerEventSet(): List<ObservedReport> = buildList {
        addAll(mixedEventSet())
        addAll(
            listOf(
                impassable("evt-p-0001", 303, at = t0 - hour),
                HazardFixtures.observedReport(
                    "evt-p-0002", "device-h-0008", 303, occurredAt = t0 + 5 * hour,
                    source = ObservationSource.OFFICIAL_STATEMENT,
                    observation = HazardFixtures.observation(
                        HazardReason.WATERLOGGED, WaterLevelCategory.WAIST, PassabilityStatus.AVOID,
                    ),
                ),
                HazardFixtures.clearedReport(
                    "evt-p-0003", device = "device-i-0009", segmentId = 303, occurredAt = t0 + 6 * hour,
                    clearance = HazardFixtures.clearance(
                        waterLevel = WaterLevelCategory.NO_VISIBLE_WATER,
                        passability = PassabilityStatus.PASSABLE_REPORTED,
                    ),
                ),
                impassable("evt-p-0004", 404, at = t0 + 7 * hour, device = "device-j-0010"),
                HazardFixtures.clearedReport(
                    "evt-p-0005", device = "device-k-0011", segmentId = 404, occurredAt = t0 + 8 * hour,
                    source = ObservationSource.RELAYED_ACCOUNT,
                    clearance = HazardFixtures.clearance(
                        waterLevel = WaterLevelCategory.ANKLE,
                        passability = PassabilityStatus.CAUTION,
                    ),
                ),
            ),
        )
    }

    /** Every decision-bearing value of a derived state, for comparing across arrival timing. */
    private fun decisionsOf(states: Map<SegmentId, SegmentObservationState>): List<String> =
        states.entries
            .sortedBy { it.key.value }
            .map { (segment, state) ->
                buildString {
                    append(segment.value).append('|')
                    append(state.excludesTraversal).append('|')
                    append(state.passability.code).append('|')
                    append(state.worstWaterLevel.code).append('|')
                    append(state.supportCount).append('|')
                    append(state.statedWaterLevels.joinToString(",") { it.code }).append('|')
                    append(state.statedPassabilities.joinToString(",") { it.code }).append('|')
                    append(state.resolvedClosures.joinToString(",") { it.eventId.value }).append('|')
                    append(state.reports.joinToString(",") { it.eventId.value })
                }
            }

    private fun WaterLevelCategory.statedRank(): Int = when (this) {
        WaterLevelCategory.NO_VISIBLE_WATER -> 0
        WaterLevelCategory.ANKLE -> 1
        WaterLevelCategory.KNEE -> 2
        WaterLevelCategory.WAIST -> 3
        WaterLevelCategory.ABOVE_WAIST -> 4
        WaterLevelCategory.UNKNOWN -> -1
    }

    private fun PassabilityStatus.statedRank(): Int = when (this) {
        PassabilityStatus.PASSABLE_REPORTED -> 0
        PassabilityStatus.CAUTION -> 1
        PassabilityStatus.AVOID -> 2
        PassabilityStatus.IMPASSABLE -> 3
        PassabilityStatus.UNKNOWN -> -1
    }

    private fun rank(rank: Int): Int = rank

    /** Every ordering of [items]. Only used on small sets: 7! = 5040. */
    private fun <T> permutations(items: List<T>): Sequence<List<T>> = sequence {
        if (items.size <= 1) {
            yield(items)
            return@sequence
        }
        for (index in items.indices) {
            val head = items[index]
            val rest = items.filterIndexed { i, _ -> i != index }
            for (tail in permutations(rest)) {
                yield(listOf(head) + tail)
            }
        }
    }
}
