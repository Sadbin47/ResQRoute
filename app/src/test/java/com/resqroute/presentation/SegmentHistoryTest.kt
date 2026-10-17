package com.resqroute.presentation

import android.app.Application
import android.content.Context
import com.resqroute.core.model.SegmentId
import com.resqroute.domain.hazard.ClosureFreshness
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.ObservationSource
import com.resqroute.domain.hazard.ObservedReport
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.SegmentObservationAggregator
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.feature.map.presentation.AgeCopy
import com.resqroute.feature.map.presentation.HistoryEntry
import com.resqroute.feature.map.presentation.ReportedTrend
import com.resqroute.feature.map.presentation.RouteExplanationCopy
import com.resqroute.feature.map.presentation.RouteHazard
import com.resqroute.feature.map.presentation.SegmentHistory
import com.resqroute.feature.map.presentation.SegmentHistoryCopy
import com.resqroute.feature.map.presentation.TimeBand
import com.resqroute.feature.map.presentation.TimeBands
import com.resqroute.feature.map.presentation.TrendDirection
import com.resqroute.hazard.HazardFixtures
import com.resqroute.wording.WordingGate
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A road's reported history and its trend sentence (XPH-71).
 *
 * The issue's test plan in one class: single, multiple, equal and out-of-order timestamps; the
 * sparse-data case; the timezone boundary. Everything is asserted against the **rendered** lines, not
 * against internal fields alone, because the deliverable is what a person reads — and the negative
 * assertions (no trend from one report, no causal word, no wording-gate violation) are swept over
 * every case the block can produce rather than stated once as an example.
 *
 * `now` and the zone are always injected, so these tests are deterministic rather than dependent on
 * when or where the suite runs (ADR 0005 §6.1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SegmentHistoryTest {

    private val context: Context get() = HazardFixtures.context()

    /** The zone the road is in. Fixed offset since 2009, so the assertions are exact. */
    private val zone: TimeZone = TimeBands.incidentZone()

    /** The moment the block is rendered at: noon the day after every fixture report. */
    private val now: Long = local(2023, Calendar.NOVEMBER, 14, 12, 0)

    // ------------------------------------------------------------------ ordering

    @Test
    fun oneObservationShowsOneRowAndNoTrend() {
        val history = historyOf(
            ankle("evt-1", at = local(2023, Calendar.NOVEMBER, 13, 14, 10), receivedAt = now - 22 * HOUR),
        )

        assertEquals(1, history.entries.size)
        assertEquals(ReportedTrend.Insufficient, history.trend)
        assertFalse(history.hasTrend)

        val lines = SegmentHistoryCopy.bodyLines(context, history)
        assertTrue(
            "the row must carry the time, the band, both axes and the age: $lines",
            lines.any {
                it.contains("14:10 daylight") && it.contains("ankle-deep") && it.contains("caution") &&
                    it.contains("reported 22 h ago")
            },
        )
        assertEquals(
            "One report so far — not enough history for a trend.",
            SegmentHistoryCopy.trendLine(context, history),
        )
    }

    @Test
    fun historyIsOrderedByObservationTimeNotByArrivalOrReceipt() {
        // Deliberately handed to the aggregator newest-first, and with receipt stamps that run the
        // other way, so neither input order nor arrival order can be what the history is ordered by.
        val newest = knee("evt-newest", at = local(2023, Calendar.NOVEMBER, 13, 19, 40), receivedAt = now - 1 * HOUR)
        // 05:05, i.e. before the 06:00 daylight boundary, so the rows span both bands.
        val oldest = ankle("evt-oldest", at = local(2023, Calendar.NOVEMBER, 13, 5, 5), receivedAt = now - 3 * HOUR)
        val middle = knee("evt-middle", at = local(2023, Calendar.NOVEMBER, 13, 12, 0), receivedAt = now - 2 * HOUR)

        val history = historyOf(newest, oldest, middle)

        assertEquals(
            "history must read forwards in observation time",
            listOf("evt-oldest", "evt-middle", "evt-newest"),
            history.entries.map { it.report.eventId.value },
        )
        assertEquals(
            listOf("05:05 night", "12:00 daylight", "19:40 night"),
            history.entries.map { SegmentHistoryCopy.timeText(context, it) },
        )
        assertTrue(
            "the reported times must ascend",
            history.entries.zipWithNext().all { (a, b) -> a.occurredAtEpochMillis < b.occurredAtEpochMillis },
        )
    }

    @Test
    fun equalTimestampsKeepOneDeterministicOrderAndMakeNoTrend() {
        // Two reports claiming the same instant cannot be ordered against each other, so the honest
        // answer is "no trend" rather than an arbitrary direction. The *rows* still have one
        // deterministic order (claimed time, then event id).
        val at = local(2023, Calendar.NOVEMBER, 13, 14, 10)
        val history = historyOf(
            knee("evt-b", at = at),
            ankle("evt-a", at = at),
        )

        assertEquals(listOf("evt-a", "evt-b"), history.entries.map { it.report.eventId.value })
        assertEquals(ReportedTrend.Insufficient, history.trend)
        assertEquals(
            "Not enough comparable reports for a trend.",
            SegmentHistoryCopy.trendLine(context, history),
        )
    }

    // -------------------------------------------------------------------- trend

    @Test
    fun aRisingTrendIsStatedAboutTheTwoReports() {
        val history = historyOf(
            ankle("evt-old", at = local(2023, Calendar.NOVEMBER, 13, 14, 10)),
            knee("evt-new", at = local(2023, Calendar.NOVEMBER, 13, 19, 40)),
        )

        val trend = history.trend as ReportedTrend.Change
        assertEquals(TrendDirection.RISING, trend.direction)
        assertEquals(
            "Reported over time: ankle-deep 14:10 → knee-deep 19:40 (rising).",
            SegmentHistoryCopy.trendLine(context, history),
        )
    }

    @Test
    fun aFallingTrendIsStatedAsFalling() {
        val history = historyOf(
            waist("evt-old", at = local(2023, Calendar.NOVEMBER, 13, 9, 0)),
            ankle("evt-new", at = local(2023, Calendar.NOVEMBER, 13, 15, 30)),
        )
        assertEquals(
            "Reported over time: waist-deep 09:00 → ankle-deep 15:30 (falling).",
            SegmentHistoryCopy.trendLine(context, history),
        )
    }

    @Test
    fun equalDepthsAreNotWordedAsRisingOrFalling() {
        val history = historyOf(
            knee("evt-old", at = local(2023, Calendar.NOVEMBER, 13, 9, 0)),
            knee("evt-new", at = local(2023, Calendar.NOVEMBER, 13, 18, 0)),
        )
        assertEquals(TrendDirection.STEADY, (history.trend as ReportedTrend.Change).direction)
        val line = SegmentHistoryCopy.trendLine(context, history)
        assertTrue(line, line.contains("same depth reported both times"))
        assertFalse("a steady reading must not be called a rise: $line", line.contains("rising"))
        assertFalse("nor a fall: $line", line.contains("falling"))
    }

    @Test
    fun onlyTheTwoMostRecentComparableReportsAreCompared() {
        // Three observations: the sentence names the last two, never an average and never the first.
        val history = historyOf(
            ankle("evt-first", at = local(2023, Calendar.NOVEMBER, 13, 10, 0)),
            waist("evt-second", at = local(2023, Calendar.NOVEMBER, 13, 14, 0)),
            knee("evt-third", at = local(2023, Calendar.NOVEMBER, 13, 18, 0)),
        )
        val line = SegmentHistoryCopy.trendLine(context, history)
        assertEquals(
            "Reported over time: waist-deep 14:00 → knee-deep 18:00 (falling).",
            line,
        )
        assertFalse("the oldest report is not part of the comparison: $line", line.contains("ankle"))
    }

    @Test
    fun aReportThatStatesNoDepthCannotTakePartInADepthTrend() {
        val state = stateOf(
            knee("evt-knee", at = local(2023, Calendar.NOVEMBER, 13, 14, 0)),
            // States a verdict only: real evidence, but no depth to compare.
            HazardFixtures.observedReport(
                "evt-verdict-only", "device-b", SEGMENT,
                HazardFixtures.observation(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE),
                occurredAt = local(2023, Calendar.NOVEMBER, 13, 18, 0),
                receivedAt = now - HOUR,
            ),
        )
        val history = SegmentHistory.of(state, now, zone)

        assertEquals(2, history.entries.size)
        assertEquals(ReportedTrend.Insufficient, history.trend)
        assertEquals(
            "Not enough comparable reports for a trend.",
            SegmentHistoryCopy.trendLine(context, history),
        )
        // And the depth-less row must not be given a depth it never stated.
        val row = SegmentHistoryCopy.entryLine(context, history.entries.last())
        assertFalse("no depth may be invented: $row", row.contains("deep"))
        assertTrue("its verdict is shown: $row", row.contains("impassable"))
    }

    @Test
    fun anUnbelievableClaimIsLabelledAndCannotMakeATrend() {
        val history = historyOf(
            ankle("evt-ok", at = local(2023, Calendar.NOVEMBER, 13, 10, 0)),
            // A claim ten days in the future: not a claim about this flood (ADR 0005 §7.1).
            knee("evt-future", at = now + 10 * DAY, receivedAt = now - HOUR),
        )

        val future = history.entries.single { it.report.eventId.value == "evt-future" }
        assertFalse("the claim is not believable", future.timeVerified)
        assertEquals(ClosureFreshness.TIME_NOT_VERIFIED, future.freshness)
        assertFalse("so it cannot be compared", future.isComparable)

        assertEquals(ReportedTrend.Insufficient, history.trend)
        val row = SegmentHistoryCopy.entryLine(context, future)
        assertTrue("the row admits it rather than showing a time: $row", row.contains("time not verified"))
        assertFalse(
            "a time must not be invented for it: $row",
            row.contains(TimeBands.clockText(future.occurredAtEpochMillis, zone)),
        )
        assertFalse("nor a band: $row", row.contains("night") || row.contains("daylight"))
    }

    @Test
    fun aBackwardsDeviceClockAlsoStopsTheTimeBeingUsed() {
        // Our own clock moved backwards relative to the receipt: we cannot say how old this is, so the
        // row says so and the trend does not use it (ADR 0005 §7.1, "age undecidable").
        val history = historyOf(
            ankle("evt-ok", at = local(2023, Calendar.NOVEMBER, 13, 10, 0)),
            knee("evt-received-in-the-future", at = local(2023, Calendar.NOVEMBER, 13, 14, 0), receivedAt = now + HOUR),
        )
        val backwards = history.entries.last()
        assertFalse(backwards.timeVerified)
        assertEquals(ClosureFreshness.TIME_NOT_VERIFIED, backwards.freshness)
        assertTrue(SegmentHistoryCopy.entryLine(context, backwards).contains("time not verified"))
        assertEquals(ReportedTrend.Insufficient, history.trend)
    }

    // ----------------------------------------------------------------- time bands

    @Test
    fun theBandBoundariesAreSixAndEighteenLocalTime() {
        assertEquals(TimeBand.NIGHT, TimeBands.bandOf(5))
        assertEquals(TimeBand.DAYLIGHT, TimeBands.bandOf(6))
        assertEquals(TimeBand.DAYLIGHT, TimeBands.bandOf(17))
        assertEquals(TimeBand.NIGHT, TimeBands.bandOf(18))
        assertEquals(TimeBand.NIGHT, TimeBands.bandOf(23))
        assertEquals(TimeBand.NIGHT, TimeBands.bandOf(0))
    }

    @Test
    fun theTimezoneBoundaryIsWhereTheBandChanges() {
        // 17:59 local is still daylight; 18:00 local is night. The instants are built in UTC so the
        // conversion into the road's zone is what is being asserted, not the fixture arithmetic.
        val justBefore = historyOf(ankle("evt-day", at = utc(2023, Calendar.NOVEMBER, 13, 11, 59)))
        val exactly = historyOf(ankle("evt-night", at = utc(2023, Calendar.NOVEMBER, 13, 12, 0)))

        assertEquals("17:59 daylight", SegmentHistoryCopy.timeText(context, justBefore.entries.single()))
        assertEquals("18:00 night", SegmentHistoryCopy.timeText(context, exactly.entries.single()))
        assertEquals(TimeBand.DAYLIGHT, justBefore.entries.single().band)
        assertEquals(TimeBand.NIGHT, exactly.entries.single().band)
    }

    @Test
    fun timesAreRenderedInTheInjectedZoneAndTheRuleIsStated() {
        val at = utc(2023, Calendar.NOVEMBER, 13, 12, 0)
        val inRoadZone = historyOf(ankle("evt-1", at = at))
        // November is EST, so the same instant reads five hours earlier there.
        val inAnotherZone = SegmentHistory.of(
            stateOf(ankle("evt-1", at = at)),
            now,
            TimeZone.getTimeZone("America/New_York"),
        )

        assertEquals("18:00", inRoadZone.entries.single().localTime)
        assertEquals("07:00", inAnotherZone.entries.single().localTime)
        assertEquals(
            "the road's zone, named with its offset, is the rule stated on screen",
            "Times are Asia/Dhaka local (UTC+06:00), the road's timezone.",
            SegmentHistoryCopy.zoneLine(context, inRoadZone),
        )
        assertEquals(
            "the history reports the zone it was rendered in, so a caller cannot be misled",
            "Asia/Dhaka",
            inRoadZone.zoneId,
        )
        assertEquals("America/New_York", inAnotherZone.zoneId)
    }

    @Test
    fun theIncidentZoneIdResolvesRatherThanFallingBackToGmt() {
        // TimeZone.getTimeZone silently returns GMT for an unknown id, which would move every band by
        // six hours without a word, so the id is asserted rather than trusted.
        assertEquals(TimeBands.INCIDENT_ZONE_ID, TimeBands.incidentZone().id)
        assertEquals("UTC+06:00", TimeBands.offsetText(TimeBands.incidentZone(), now))
    }

    // --------------------------------------------------------------- sparse data

    @Test
    fun aRoadNobodyReportedSaysSoAndIsNeverWordedAsClean() {
        val history = SegmentHistory.of(SegmentObservationState(SegmentId(SEGMENT), emptyList()), now, zone)

        assertFalse(history.hasAnyReport)
        assertTrue(history.entries.isEmpty())
        assertEquals("No reports on this road.", SegmentHistoryCopy.trendLine(context, history))
        assertEquals(
            "no support line for a road with no reports",
            emptyList<String>(),
            SegmentHistoryCopy.supportLines(context, history),
        )
        assertNull("and no timezone rule about times that are not shown", SegmentHistoryCopy.zoneLine(context, history))

        val rendered = SegmentHistoryCopy.bodyLines(context, history).joinToString("\n").lowercase()
        for (token in listOf("clear", "safe", "no hazards", "nothing reported", "open")) {
            assertFalse("missing data was worded as \"$token\": $rendered", rendered.contains(token))
        }
    }

    @Test
    fun supportIsCountedInDeviceIdsAndAttributionNamesTheWaysItArrived() {
        val history = historyOf(
            ankle("evt-1", at = local(2023, Calendar.NOVEMBER, 13, 10, 0), device = "device-self", source = ObservationSource.DIRECT_OBSERVATION),
            knee("evt-2", at = local(2023, Calendar.NOVEMBER, 13, 14, 0), device = "device-b", source = ObservationSource.RELAYED_ACCOUNT),
        )

        assertEquals(2, history.supportCount)
        assertEquals(
            listOf("reports from 2 distinct device IDs", "1 from this device · 1 relayed"),
            SegmentHistoryCopy.supportLines(context, history),
        )
    }

    // ------------------------------------------------------------- whole log

    @Test
    fun aResolvedClosureStaysInHistoryAndTheClearanceIsShownAsOne() {
        // ADR 0005 §8 requirement 6: a superseded closure is never deleted or hidden. It is history —
        // and a clearance is an ordinary observation with its own axes, labelled as what it is.
        val history = historyOf(
            waist("evt-closure", at = local(2023, Calendar.NOVEMBER, 13, 9, 0), device = "device-a"),
            HazardFixtures.clearedReport(
                "evt-clearance", device = "device-b", segmentId = SEGMENT,
                occurredAt = local(2023, Calendar.NOVEMBER, 13, 15, 0),
                receivedAt = now - HOUR,
                clearance = HazardFixtures.clearance(
                    waterLevel = WaterLevelCategory.NO_VISIBLE_WATER,
                    passability = PassabilityStatus.PASSABLE_REPORTED,
                ),
            ),
        )

        assertEquals(2, history.entries.size)
        assertEquals(
            "Reported over time: waist-deep 09:00 → no water seen 15:00 (falling).",
            SegmentHistoryCopy.trendLine(context, history),
        )
        assertTrue(
            "the clearance row says it is one: ${SegmentHistoryCopy.entryLine(context, history.entries.last())}",
            SegmentHistoryCopy.entryLine(context, history.entries.last()).contains("cleared"),
        )
        assertTrue(
            "and the superseded closure is still listed",
            SegmentHistoryCopy.rowLines(context, history).any { it.contains("waist-deep") },
        )
    }

    @Test
    fun anUnreadableRowIsListedWithoutInventingAnAxis() {
        val state = stateOf(HazardFixtures.unreadableReport("evt-unreadable", "device-b", SEGMENT, occurredAt = local(2023, Calendar.NOVEMBER, 13, 14, 0)))
        val history = SegmentHistory.of(state, now, zone)
        val row = SegmentHistoryCopy.entryLine(context, history.entries.single())

        assertTrue(history.hasUnreadableReports)
        assertTrue(row, row.contains("cannot read the details"))
        assertFalse(row, row.contains("deep"))
        assertFalse(row, row.contains("passable"))
        assertEquals(ReportedTrend.Insufficient, history.trend)
    }

    // ------------------------------------------------------------- wording gates

    @Test
    fun everyRenderedLinePassesTheWordingGate() {
        val cases = listOf(
            historyOf(),
            historyOf(ankle("evt-1", at = local(2023, Calendar.NOVEMBER, 13, 10, 0))),
            historyOf(
                ankle("evt-1", at = local(2023, Calendar.NOVEMBER, 13, 10, 0)),
                knee("evt-2", at = local(2023, Calendar.NOVEMBER, 13, 14, 0), device = "device-b", source = ObservationSource.RELAYED_ACCOUNT),
            ),
            historyOf(
                knee("evt-a", at = local(2023, Calendar.NOVEMBER, 13, 14, 0)),
                knee("evt-b", at = local(2023, Calendar.NOVEMBER, 13, 14, 0)),
            ),
            historyOf(knee("evt-future", at = now + 10 * DAY, receivedAt = now - HOUR)),
            SegmentHistory.of(stateOf(HazardFixtures.unreadableReport("evt-u", "device-b", SEGMENT)), now, zone),
        )

        for ((index, history) in cases.withIndex()) {
            val rendered = (
                SegmentHistoryCopy.bodyLines(context, history) + SegmentHistoryCopy.captionLine(context, history)
                ).joinToString("\n")
            val violations = WordingGate.renderedViolations(rendered)
            assertTrue(
                "case $index said $violations, which claims more than reports can support:\n$rendered",
                violations.isEmpty(),
            )
        }
    }

    @Test
    fun noLineRelatesTheTimeOfDayToTheWaterLevel() {
        // The acceptance criterion in its negative form. A band is context; a causal reading of it
        // would be exactly the unsupported claim the plan forbids.
        val history = historyOf(
            ankle("evt-1", at = local(2023, Calendar.NOVEMBER, 13, 6, 30)),
            knee("evt-2", at = local(2023, Calendar.NOVEMBER, 13, 19, 0)),
        )
        val rendered = (
            SegmentHistoryCopy.bodyLines(context, history) + SegmentHistoryCopy.captionLine(context, history)
            ).joinToString("\n").lowercase()

        for (token in listOf("because", "due to", "caused", "therefore", "as a result", "so the water", "overnight")) {
            assertFalse("the copy suggests time of day explains the depth (\"$token\"):\n$rendered", rendered.contains(token))
        }
        assertTrue("the bands are still shown as context", rendered.contains("daylight") && rendered.contains("night"))
    }

    @Test
    fun theHistoryRowAgesIdenticallyToTheSameReportOnARoute() {
        // One age ladder, two callers: a report cannot read as older in the history than on a route.
        val report = knee("evt-1", at = local(2023, Calendar.NOVEMBER, 13, 14, 0), receivedAt = now - 2 * HOUR)
        val entry: HistoryEntry = historyOf(report).entries.single()

        val asIfOnARoute = RouteHazard(
            segmentId = SegmentId(SEGMENT),
            waterLevel = WaterLevelCategory.KNEE,
            passability = PassabilityStatus.CAUTION,
            supportCount = 1,
            directSupportCount = 1,
            relayedSupportCount = 0,
            ageMillis = entry.ageMillis,
            timeVerified = entry.timeVerified,
            freshness = entry.freshness,
        )

        assertEquals(RouteExplanationCopy.age(context, asIfOnARoute), AgeCopy.text(context, entry.ageMillis, entry.timeVerified, entry.freshness))
        assertTrue(SegmentHistoryCopy.entryLine(context, entry).contains(RouteExplanationCopy.age(context, asIfOnARoute)))
    }

    @Test
    fun theTrendSentenceNeverClaimsToKnowWhatTheWaterIsDoingNow() {
        val history = historyOf(
            ankle("evt-1", at = local(2023, Calendar.NOVEMBER, 13, 10, 0)),
            waist("evt-2", at = local(2023, Calendar.NOVEMBER, 13, 16, 0)),
        )
        val line = SegmentHistoryCopy.trendLine(context, history)

        assertTrue("the sentence is scoped to reports: $line", line.startsWith("Reported over time:"))
        for (token in listOf("now", "currently", "will ", "predict", "expect", "forecast")) {
            assertFalse("the trend predicts (\"$token\"): $line", line.lowercase().contains(token))
        }
        assertNotNull(history.trend as? ReportedTrend.Change)
    }

    // ------------------------------------------------------------------ fixture

    private fun historyOf(vararg reports: ObservedReport): SegmentHistory =
        SegmentHistory.of(stateOf(*reports), now, zone)

    private fun stateOf(vararg reports: ObservedReport): SegmentObservationState =
        SegmentObservationAggregator.aggregateSegment(SegmentId(SEGMENT), reports.toList())

    private fun ankle(eventId: String, at: Long, device: String = "device-a", source: ObservationSource = ObservationSource.DIRECT_OBSERVATION, receivedAt: Long = now - HOUR) =
        report(eventId, device, WaterLevelCategory.ANKLE, PassabilityStatus.CAUTION, at, source, receivedAt)

    private fun knee(eventId: String, at: Long, device: String = "device-a", source: ObservationSource = ObservationSource.DIRECT_OBSERVATION, receivedAt: Long = now - HOUR) =
        report(eventId, device, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, at, source, receivedAt)

    private fun waist(eventId: String, at: Long, device: String = "device-a", source: ObservationSource = ObservationSource.DIRECT_OBSERVATION, receivedAt: Long = now - HOUR) =
        report(eventId, device, WaterLevelCategory.WAIST, PassabilityStatus.AVOID, at, source, receivedAt)

    private fun report(
        eventId: String,
        device: String,
        level: WaterLevelCategory,
        passability: PassabilityStatus,
        occurredAt: Long,
        source: ObservationSource,
        receivedAt: Long,
    ): ObservedReport = HazardFixtures.observedReport(
        eventId = eventId,
        device = device,
        segmentId = SEGMENT,
        observation = HazardFixtures.observation(HazardReason.WATERLOGGED, level, passability),
        source = source,
        occurredAt = occurredAt,
        receivedAt = receivedAt,
    )

    /** An epoch built from a local wall-clock reading in the road's zone. */
    private fun local(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone, Locale.US).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis

    /** An epoch built from a UTC wall-clock reading, for the boundary conversion test. */
    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis

    private companion object {
        const val SEGMENT = 42
        const val HOUR = 3_600_000L
        const val DAY = 24L * HOUR
    }
}
