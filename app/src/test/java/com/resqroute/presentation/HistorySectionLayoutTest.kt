package com.resqroute.presentation

import android.app.Application
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.widget.NestedScrollView
import com.resqroute.R
import com.resqroute.core.model.SegmentId
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.ObservationSource
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.SegmentObservationAggregator
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.feature.map.presentation.TimeBands
import com.resqroute.feature.map.ui.SegmentHistorySection
import com.resqroute.hazard.HazardFixtures
import com.resqroute.wording.WordingGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The reported-history block's place in the sheet's layout (XPH-71).
 *
 * No test inflated `activity_main.xml` before this phase, so the layout was only ever checked by
 * looking at the running app. That is not good enough for a change whose whole point is *where* the
 * block lives: the sheet shows the road actions in a fixed-height action panel and the technical
 * detail in a scrollable view, and a history that grew without bound inside the non-scrolling panel
 * would clip — the exact defect XPH-70 found on a real screen and fixed by measuring.
 *
 * So this asserts the structural facts the design depends on, on the JVM: the block exists, it
 * starts hidden, and it is a child of the **scrollable** sheet content so a road with many reports
 * can never be cut off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HistorySectionLayoutTest {

    private fun inflate(): View {
        val themed = ContextThemeWrapper(HazardFixtures.context(), R.style.Theme_ResQRoute)
        return LayoutInflater.from(themed).inflate(R.layout.activity_main, null, false)
    }

    @Test
    fun theHistoryBlockExistsAndStartsHidden() {
        val root = inflate()

        val section = root.findViewById<View>(R.id.historySection)
        val body = root.findViewById<TextView>(R.id.historyText)
        val note = root.findViewById<TextView>(R.id.historyNoteText)

        assertTrue("the section must exist", section != null)
        assertEquals("nothing is selected at launch", View.GONE, section.visibility)
        assertTrue("the body must exist", body != null)
        assertTrue("the caption must exist", note != null)
        // The body is empty until a road is selected, so the block cannot show a stale road's history.
        assertEquals("", body.text.toString())
        assertEquals("", note.text.toString())
    }

    @Test
    fun theHeadingNamesTheBlockTheWayTheCopyDoes() {
        val root = inflate()
        val heading = root.findViewById<View>(R.id.historySection)
            .let { (it as ViewGroup).getChildAt(0) as TextView }

        assertEquals(
            HazardFixtures.context().getString(R.string.rq_section_history),
            heading.text.toString(),
        )
    }

    @Test
    fun theHistoryBlockIsInsideTheScrollableSheetContent() {
        // The action panel is fixed height by design; a history of arbitrary length must not live
        // there. Walking the parents proves where it actually is rather than where it was intended.
        val root = inflate()
        var view: View? = root.findViewById(R.id.historySection)
        var foundScroller = false
        while (view != null) {
            if (view is NestedScrollView && view.id == R.id.rq_sheet_content) {
                foundScroller = true
                break
            }
            view = view.parent as? View
        }

        assertTrue(
            "the history block must be inside rq_sheet_content so many reports cannot clip",
            foundScroller,
        )
    }

    // --------------------------------------- the wiring, through the real layout

    @Test
    fun aSelectedRoadWithTwoReportsMakesTheBlockVisibleAndShowsTheTrend() {
        // The last mile, driven for real: the sheet's own views, the sheet's own binder, a road with
        // two comparable reports. This is what an emulator screenshot would show, asserted instead of
        // eyeballed, so it cannot regress unnoticed.
        val root = inflate()
        SegmentHistorySection.render(
            context = HazardFixtures.context(),
            section = root.findViewById(R.id.historySection),
            body = root.findViewById(R.id.historyText),
            note = root.findViewById(R.id.historyNoteText),
            state = twoObservations(),
            now = NOW,
            zone = TimeBands.incidentZone(),
        )

        val section = root.findViewById<View>(R.id.historySection)
        val body = root.findViewById<TextView>(R.id.historyText)
        val note = root.findViewById<TextView>(R.id.historyNoteText)
        assertEquals("a selected road must show the block", View.VISIBLE, section.visibility)
        assertTrue("the road is named: ${body.text}", body.text.toString().contains("S022959"))
        assertTrue("both reports are listed", body.text.toString().lines().count { it.contains("— this device") || it.contains("— another device") } == 2)
        assertTrue("the trend is stated: ${body.text}", body.text.toString().contains("Reported over time:"))
        assertTrue("the caption carries the timezone rule", note.text.toString().contains("Asia/Dhaka"))
        assertTrue(
            "and the block claims no more than reports support",
            WordingGate.renderedViolations(body.text.toString() + "\n" + note.text).isEmpty(),
        )
    }

    @Test
    fun aSelectedRoadNobodyReportedShowsTheBlockWithTheMissingDataAnswer() {
        val root = inflate()
        SegmentHistorySection.render(
            context = HazardFixtures.context(),
            section = root.findViewById(R.id.historySection),
            body = root.findViewById(R.id.historyText),
            note = root.findViewById(R.id.historyNoteText),
            state = SegmentObservationState(SegmentId(SEGMENT), emptyList()),
            now = NOW,
            zone = TimeBands.incidentZone(),
        )

        val body = root.findViewById<TextView>(R.id.historyText)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.historySection).visibility)
        assertTrue(
            "the missing-data answer, not an empty panel: ${body.text}",
            body.text.toString().contains("No reports on this road."),
        )
    }

    @Test
    fun clearingTheSelectionHidesAndEmptiesTheBlock() {
        // Hiding without emptying would let a later selection flash the previous road's history.
        val root = inflate()
        SegmentHistorySection.render(
            context = HazardFixtures.context(),
            section = root.findViewById(R.id.historySection),
            body = root.findViewById(R.id.historyText),
            note = root.findViewById(R.id.historyNoteText),
            state = twoObservations(),
            now = NOW,
            zone = TimeBands.incidentZone(),
        )
        SegmentHistorySection.render(
            context = HazardFixtures.context(),
            section = root.findViewById(R.id.historySection),
            body = root.findViewById(R.id.historyText),
            note = root.findViewById(R.id.historyNoteText),
            state = null,
            now = NOW,
            zone = TimeBands.incidentZone(),
        )

        assertEquals(View.GONE, root.findViewById<View>(R.id.historySection).visibility)
        assertEquals("", root.findViewById<TextView>(R.id.historyText).text.toString())
        assertEquals("", root.findViewById<TextView>(R.id.historyNoteText).text.toString())
    }

    /** Two reports on one road, six hours apart, from two devices: the definition-of-done case. */
    private fun twoObservations(): SegmentObservationState = SegmentObservationAggregator.aggregateSegment(
        SegmentId(SEGMENT),
        listOf(
            report("evt-layout-0001", "device-a", 14, 10, WaterLevelCategory.ANKLE, PassabilityStatus.CAUTION, ObservationSource.DIRECT_OBSERVATION),
            report("evt-layout-0002", "device-b", 20, 40, WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, ObservationSource.RELAYED_ACCOUNT),
        ),
    )

    private fun report(
        eventId: String,
        device: String,
        hour: Int,
        minute: Int,
        level: WaterLevelCategory,
        passability: PassabilityStatus,
        source: ObservationSource,
    ) = HazardFixtures.observedReport(
        eventId = eventId,
        device = device,
        segmentId = SEGMENT,
        observation = HazardFixtures.observation(HazardReason.WATERLOGGED, level, passability),
        source = source,
        occurredAt = local(hour, minute),
        receivedAt = NOW - (24 - hour) * 3_600_000L,
    )

    private fun local(hour: Int, minute: Int): Long =
        java.util.Calendar.getInstance(TimeBands.incidentZone(), java.util.Locale.US).apply {
            clear()
            set(2023, java.util.Calendar.NOVEMBER, 13, hour, minute, 0)
        }.timeInMillis

    private companion object {
        const val SEGMENT = 22_958

        /** Noon the next day, so both fixtures are in the past and their ages are known. */
        const val NOW = 1_700_000_000_000L
    }
}
