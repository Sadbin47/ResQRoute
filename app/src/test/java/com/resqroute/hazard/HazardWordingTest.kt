package com.resqroute.hazard

import android.app.Application
import android.content.Context
import com.resqroute.core.model.SegmentId
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.ObservationSource
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.RoadObservation
import com.resqroute.domain.hazard.SegmentObservationAggregator
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.feature.map.presentation.HazardSupportCopy
import java.io.File
import com.resqroute.wording.WordingGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The wording gate (XPH-73).
 *
 * ADR 0001 D5 and the canonical plan both forbid describing a device count as confidence: a
 * device is not a person, and two reports from one phone are one device. Until now that rule
 * lived only in prose, which is the kind of rule that erodes. These tests hold the **rendered
 * copy** — not a comment — to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HazardWordingTest {

    private val context: Context get() = HazardFixtures.context()

    // ------------------------------------------------------------- support

    @Test
    fun supportIsAlwaysCountedInDeviceIds() {
        assertEquals(
            "report from 1 distinct device ID",
            HazardSupportCopy.supportLine(context, stateOf(report("evt-1", "device-a"))),
        )
        assertEquals(
            "reports from 3 distinct device IDs",
            HazardSupportCopy.supportLine(
                context,
                stateOf(report("evt-1", "device-a"), report("evt-2", "device-b"), report("evt-3", "device-c")),
            ),
        )
    }

    @Test
    fun supportCountsOnePhoneWithThreeReportsAsOneDevice() {
        val line = HazardSupportCopy.supportLine(
            context,
            stateOf(report("evt-1", "device-a"), report("evt-2", "device-a"), report("evt-3", "device-a")),
        )
        assertEquals("report from 1 distinct device ID", line)
    }

    @Test
    fun attributionNamesOnlyTheWaysThatOccurred() {
        val mixed = stateOf(
            report("evt-1", "device-self", source = ObservationSource.DIRECT_OBSERVATION),
            report("evt-2", "device-b", source = ObservationSource.RELAYED_ACCOUNT),
            report("evt-3", "device-c", source = ObservationSource.RELAYED_ACCOUNT),
        )
        val line = HazardSupportCopy.attributionLine(context, mixed)
        assertEquals("1 from this device · 2 relayed", line)
        assertFalse("nothing came from an official source yet", line!!.contains("official"))

        assertEquals(
            "1 from this device",
            HazardSupportCopy.attributionLine(context, stateOf(report("evt-1", "device-self"))),
        )
        assertNull("no reports, no attribution", HazardSupportCopy.attributionLine(context, stateOf()))
    }

    // -------------------------------------------------------- disagreement

    @Test
    fun aDifferenceIsShownWithBothSidesAndTheirOwnSources() {
        // The issue's example: waist then ankle, from two devices, at two times.
        val state = stateOf(
            report(
                "evt-waist", "device-b",
                observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.WAIST, PassabilityStatus.AVOID),
                source = ObservationSource.RELAYED_ACCOUNT,
            ),
            report(
                "evt-ankle", "device-self",
                observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.ANKLE, PassabilityStatus.CAUTION),
                source = ObservationSource.DIRECT_OBSERVATION,
            ),
        )

        val lines = HazardSupportCopy.disagreementLines(context, state)
        assertEquals(3, lines.size)
        assertEquals("Reports on this road differ", lines[0])
        // Order follows the aggregate's deterministic order (oldest claimed time first); in
        // this fixture both claims are equal, so the tie breaks on event id.
        assertTrue("each entry carries its own view and its own source: $lines", lines[1].contains("ankle-deep") && lines[1].contains("this device"))
        assertTrue(lines[2].contains("waist-deep") && lines[2].contains("another device"))
    }

    @Test
    fun aClosureAgainstAPassableReportIsShownAsDiffering() {
        val state = stateOf(
            report("evt-closed", "device-b", observation = HazardFixtures.observation(HazardReason.BLOCKED, WaterLevelCategory.UNKNOWN, PassabilityStatus.IMPASSABLE)),
            report(
                "evt-open", "device-c",
                observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.ANKLE, PassabilityStatus.PASSABLE_REPORTED),
                source = ObservationSource.RELAYED_ACCOUNT,
            ),
        )
        val lines = HazardSupportCopy.disagreementLines(context, state)
        assertEquals(3, lines.size)
        assertTrue(lines.any { it.contains("impassable") })
        // Note the substring trap this avoids: "impassable" contains "passable", so match the
        // whole entry rather than the word.
        assertTrue(lines.any { it.endsWith("passable — another device") })
    }

    @Test
    fun agreeingReportsProduceNoDisagreementLines() {
        val state = stateOf(
            report("evt-1", "device-b", observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION)),
            report("evt-2", "device-c", observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION)),
        )
        assertTrue(HazardSupportCopy.disagreementLines(context, state).isEmpty())
    }

    @Test
    fun anUnreadableReportIsDescribedHonestlyRatherThanInvented() {
        val state = stateOf(HazardFixtures.unreadableReport("evt-unreadable", "device-b", 10))
        val line = HazardSupportCopy.reportLine(context, state.reports.single())
        assertTrue(line, line.contains("cannot read the details"))
        // No axis is invented for it, so no water or passability word appears.
        assertFalse(line, line.contains("deep"))
        assertFalse(line, line.contains("passable"))
        assertFalse(line, line.contains("impassable"))
    }

    @Test
    fun anAxisThatStatesNothingIsOmittedRatherThanRenderedAsAValue() {
        val depthOnly = stateOf(
            report("evt-1", "device-b", observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.KNEE, PassabilityStatus.UNKNOWN)),
        )
        val line = HazardSupportCopy.reportLine(context, depthOnly.reports.single())
        assertTrue(line, line.contains("knee-deep"))
        assertFalse("an unjudged passability is not stated as a value", line.contains("not judged"))
    }

    @Test
    fun aConflictingSegmentRendersExactlyAsExpected() {
        // The rendered copy for one conflicting segment, pinned end to end. XPH-70 renders these
        // lines; this is the reviewable form of "a screenshot of a conflicting-report segment",
        // and it is the assertion that fails if the wording drifts.
        val state = stateOf(
            report(
                "evt-ankle", "device-self",
                observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.ANKLE, PassabilityStatus.CAUTION),
                source = ObservationSource.DIRECT_OBSERVATION,
            ),
            report(
                "evt-waist", "device-b",
                observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.WAIST, PassabilityStatus.AVOID),
                source = ObservationSource.RELAYED_ACCOUNT,
            ),
        )

        assertEquals(
            """
            reports from 2 distinct device IDs
            1 from this device · 1 relayed
            Reports on this road differ
            ankle-deep · caution — this device
            waist-deep · avoid — another device
            """.trimIndent(),
            HazardSupportCopy.summaryLines(context, state).joinToString("\n"),
        )
    }

    // ------------------------------------------------------- the wording gate

    @Test
    fun noComposedLineClaimsConfidenceOrPeople() {
        val states = listOf(
            stateOf(),
            stateOf(report("evt-1", "device-a")),
            stateOf(report("evt-1", "device-a"), report("evt-2", "device-b")),
            stateOf(
                report("evt-1", "device-self", source = ObservationSource.DIRECT_OBSERVATION),
                report("evt-2", "device-b", source = ObservationSource.RELAYED_ACCOUNT),
                report("evt-3", "device-c", source = ObservationSource.RELAYED_ACCOUNT),
            ),
            stateOf(
                report("evt-waist", "device-b", observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.WAIST, PassabilityStatus.AVOID)),
                report("evt-ankle", "device-self", observation = HazardFixtures.observation(HazardReason.WATERLOGGED, WaterLevelCategory.ANKLE, PassabilityStatus.CAUTION)),
            ),
            stateOf(HazardFixtures.unreadableReport("evt-unreadable", "device-b", 10)),
        )

        for ((index, state) in states.withIndex()) {
            val rendered = HazardSupportCopy.summaryLines(context, state).joinToString("\n").lowercase()
            val violations = WordingGate.renderedViolations(rendered)
            assertTrue(
                "state $index said $violations, which claims more than a device count can support:\n$rendered",
                violations.isEmpty(),
            )
        }
    }

    @Test
    fun theSupportCopyInStringsXmlCarriesNoConfidenceVocabulary() {
        val source = File(projectRoot(), "app/src/main/res/values/strings.xml").readText()
        val scanned = extractCopy(source)

        assertTrue("the scan found nothing, so it proves nothing: $scanned", scanned.size >= 20)
        assertTrue(
            "the route-explanation strings must be covered by this gate too: $scanned",
            scanned.keys.any { it.startsWith("rq_route_") },
        )
        for ((name, value) in scanned) {
            val violations = WordingGate.violations(value)
            assertFalse("$name says $violations: $value", violations.isNotEmpty())
        }
        // The positive half of the rule, asserted rather than assumed.
        assertTrue(
            "the support string must name device IDs: $scanned",
            scanned.values.any { it.contains("device ID") },
        )
    }

    // -------------------------------------------------------------- fixture

    private fun stateOf(vararg reports: com.resqroute.domain.hazard.ObservedReport): SegmentObservationState =
        SegmentObservationAggregator.aggregate(reports.toList())[SegmentId(10)]
            ?: SegmentObservationState(SegmentId(10), emptyList())

    private fun report(
        eventId: String,
        device: String,
        observation: RoadObservation = HazardFixtures.observation(
            HazardReason.BLOCKED,
            WaterLevelCategory.UNKNOWN,
            PassabilityStatus.IMPASSABLE,
        ),
        source: ObservationSource = ObservationSource.DIRECT_OBSERVATION,
    ) = HazardFixtures.observedReport(
        eventId = eventId,
        device = device,
        segmentId = 10,
        observation = observation,
        source = source,
    )

    /** The `rq_*` copy these phases introduce: support, disagreement, reporters, the axes, history. */
    private fun extractCopy(source: String): Map<String, String> {
        val strings = Regex("""<string name="(rq_(?:support|disagreement|reporter|water|pass|route|report|history)\w*|rq_unreadable_report)">([^<]*)</string>""")
            .findAll(source)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val plurals = Regex("""<plurals name="(rq_(?:support|route|report)\w*)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(source)
            .associate { match ->
                val items = Regex("""<item quantity="\w+">([^<]*)</item>""")
                    .findAll(match.groupValues[2])
                    .joinToString(" | ") { it.groupValues[1] }
                match.groupValues[1] to items
            }
        return strings + plurals
    }

    private fun projectRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("could not locate the project root from ${System.getProperty("user.dir")}")
    }

    private companion object {
        /** The shared gate's policy; see [WordingGate] for what it refuses and why. */
        val FORBIDDEN_TOKENS = WordingGate.FORBIDDEN_TOKENS
    }
}
