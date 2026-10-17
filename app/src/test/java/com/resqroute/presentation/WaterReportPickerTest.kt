package com.resqroute.presentation

import android.app.Application
import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.resqroute.R
import com.resqroute.core.model.SegmentId
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.feature.map.ui.SelectionCard
import com.resqroute.feature.map.ui.WaterReportPicker
import com.resqroute.hazard.HazardFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The report picker (XPH-70): every category is offered, and only a representable pair can be sent.
 *
 * The picker builds its buttons from the enums, so "each category persists the matching level" is
 * structural. This asserts that structure rather than trusting it: if a category is added to the
 * domain and loses its button, this fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WaterReportPickerTest {

    /**
     * A **themed** context, as the Activity provides in production.
     *
     * `MaterialButton` refuses to be constructed outside a Material theme, and the sheet builds it
     * with the Activity as its context. Using the app's own theme here keeps the test on the same
     * component configuration the app uses rather than a stand-in.
     */
    private val context: Context
        get() = androidx.appcompat.view.ContextThemeWrapper(
            HazardFixtures.context(),
            com.resqroute.R.style.Theme_ResQRoute,
        )

    @Test
    fun everyCategoryInTheDomainGetsExactlyOneButton() {
        val harness = harness()

        assertEquals(
            "every water level must be offered",
            WaterLevelCategory.entries.toList(),
            harness.picker.offeredWaterLevels(),
        )
        assertEquals(
            "every passability verdict must be offered",
            PassabilityStatus.entries.toList(),
            harness.picker.offeredPassabilities(),
        )
        assertEquals(WaterLevelCategory.entries.size, harness.waterCards.size)
        assertEquals(PassabilityStatus.entries.size, harness.passabilityCards.size)
    }

    @Test
    fun theChosenSegmentStaysVisibleWhileThePickerIsOpen() {
        val harness = harness()
        harness.picker.show(SegmentId(37_412))

        assertEquals(android.view.View.VISIBLE, harness.title.visibility)
        val text = harness.title.text.toString()
        assertTrue("the stable id must be on screen: $text", text.contains(SegmentId(37_412).stableId))

        harness.picker.hide()
        assertEquals(android.view.View.GONE, harness.title.visibility)
    }

    @Test
    fun nothingIsSubmittedUntilBothAxesAreChosenAndThePairIsRepresentable() {
        val harness = harness()
        harness.picker.show(SegmentId(1))

        // Nothing chosen: the button says what is missing and cannot be pressed.
        assertFalse(harness.submit.isEnabled)
        assertEquals(context.getString(R.string.rq_report_choose_level), harness.submit.text.toString())

        // A depth alone: still not a report, and the button asks for the missing half.
        harness.waterCards.getValue(WaterLevelCategory.KNEE).performClick()
        assertFalse(harness.submit.isEnabled)
        assertEquals(context.getString(R.string.rq_report_choose_passability), harness.submit.text.toString())

        // Both chosen and representable: now it can be sent, and its label says exactly what will be.
        harness.passabilityCards.getValue(PassabilityStatus.CAUTION).performClick()
        assertTrue(harness.submit.isEnabled)
        val label = harness.submit.text.toString()
        assertTrue("the label must name the depth: $label", label.contains("Knee-deep"))
        assertTrue("and the verdict: $label", label.contains("Caution"))

        harness.submit.performClick()
        assertEquals(listOf(WaterLevelCategory.KNEE), harness.reports.map { it.first })
        assertEquals(listOf(PassabilityStatus.CAUTION), harness.reports.map { it.second })
    }

    @Test
    fun theContradictoryPairCannotBeSubmittedAndSaysWhy() {
        val harness = harness()
        harness.picker.show(SegmentId(2))
        harness.waterCards.getValue(WaterLevelCategory.ABOVE_WAIST).performClick()
        harness.passabilityCards.getValue(PassabilityStatus.PASSABLE_REPORTED).performClick()

        assertFalse("an above-waist road cannot be reported passable", harness.submit.isEnabled)
        assertEquals(context.getString(R.string.rq_report_incomplete), harness.submit.text.toString())
        harness.submit.performClick()
        assertTrue("nothing may be sent for a contradictory pair", harness.reports.isEmpty())
    }

    @Test
    fun theEmptyReportCannotBeSubmitted() {
        val harness = harness()
        harness.picker.show(SegmentId(3))
        harness.waterCards.getValue(WaterLevelCategory.UNKNOWN).performClick()
        harness.passabilityCards.getValue(PassabilityStatus.UNKNOWN).performClick()

        assertFalse("a report that states nothing is not a report", harness.submit.isEnabled)
        harness.submit.performClick()
        assertTrue(harness.reports.isEmpty())
    }

    @Test
    fun theChosenButtonIsMarkedByTextNotOnlyByColour() {
        val harness = harness()
        harness.picker.show(SegmentId(4))
        val knee = harness.waterCards.getValue(WaterLevelCategory.KNEE)
        val ankle = harness.waterCards.getValue(WaterLevelCategory.ANKLE)

        knee.performClick()
        assertTrue(knee.isSelected)
        assertFalse(ankle.isSelected)
        // Accessibility, and screenshots read out of context: the state is in the text too.
        assertTrue(
            "the chosen button must say so: ${knee.contentDescription}",
            knee.contentDescription.toString().contains("chosen"),
        )
        assertEquals("ankle-deep", ankle.contentDescription.toString())
    }

    @Test
    fun anUnknownDepthStillNeedsAVerdictToBeAReport() {
        // The criterion that matters: "unknown" states no depth claim, so on its own it is not a
        // report at all - and once a verdict is chosen, the verdict alone decides exclusion.
        val harness = harness()
        harness.picker.show(SegmentId(5))
        harness.waterCards.getValue(WaterLevelCategory.UNKNOWN).performClick()
        assertFalse(harness.submit.isEnabled)

        harness.passabilityCards.getValue(PassabilityStatus.CAUTION).performClick()
        assertTrue(harness.submit.isEnabled)
        val label = harness.submit.text.toString()
        assertTrue("the depth is reported as unestimated: $label", label.contains("Not Estimated"))
        assertTrue("and the verdict is separate: $label", label.contains("Caution"))
    }

    // -------------------------------------------------------------- harness

    private class Harness(
        val picker: WaterReportPicker,
        val waterCards: Map<WaterLevelCategory, SelectionCard>,
        val passabilityCards: Map<PassabilityStatus, SelectionCard>,
        val title: TextView,
        val submit: MaterialButton,
        val reports: MutableList<Pair<WaterLevelCategory, PassabilityStatus>>,
        val waterGrid: LinearLayout,
        val passabilityGrid: LinearLayout,
    )

    @Test
    fun theGridsLayOutAsTheDesignAsksFor() {
        // The shape is asserted by MEASURING the real grids rather than by reading the spans back:
        // GridLayout keeps its Spec internals package-private, and the widths are what a person
        // actually sees. Two columns of depth, then a staggered row of three and a row of two.
        val harness = harness()
        val width = 900
        val tolerance = context.resources.displayMetrics.density * 14

        layOut(harness.waterGrid, width)
        layOut(harness.passabilityGrid, width)

        val water = WaterLevelCategory.entries.map { widthOf(harness.waterCards.getValue(it)) }
        assertEquals("two depth cards per row", width / 2f, water[0].toFloat(), tolerance)
        assertEquals("and the second column is the same width", water[0].toFloat(), water[1].toFloat(), 2f)

        val verdicts = PassabilityStatus.entries.map { widthOf(harness.passabilityCards.getValue(it)) }
        assertEquals("three verdict cards in the first row", width / 3f, verdicts[0].toFloat(), tolerance)
        assertEquals(verdicts[0].toFloat(), verdicts[1].toFloat(), 2f)
        assertEquals(verdicts[0].toFloat(), verdicts[2].toFloat(), 2f)
        assertEquals("and two wider cards in the second", width / 2f, verdicts[3].toFloat(), tolerance)
        assertEquals(verdicts[3].toFloat(), verdicts[4].toFloat(), 2f)
        assertTrue("the staggered second row must be wider than the first", verdicts[3] > verdicts[0])
    }

    @Test
    fun everyCardIsARealTapTargetWithARoundedSurface() {
        // The XPH-92 defect was a row with dead bands inside it. Cards fix the geometry by giving each
        // choice its own bordered surface; the minimum height and the corner radius are what make it
        // both hittable and visibly a card.
        val harness = harness()
        val minimum = context.resources.getDimensionPixelSize(R.dimen.rq_card_min_height)

        for (card in harness.waterCards.values + harness.passabilityCards.values) {
            assertTrue("a card must be at least $minimum px tall", card.minimumHeight >= minimum)
            assertTrue("cards are heavily rounded", card.radius >= context.resources.displayMetrics.density * 12)
            assertTrue("and hittable", card.isClickable)
        }
    }

    @Test
    fun choosingFillsTheCardAndRaisesABadge() {
        // The old feedback was an opacity change, reported from a device as "nothing happens". A
        // chosen card is now accent-filled with a visible badge, and the others stay on the surface.
        val harness = harness()
        harness.picker.show(SegmentId(42))
        // A real click on the real card, so this covers the path the person actually takes.
        assertTrue(harness.waterCards.getValue(WaterLevelCategory.KNEE).performClick())

        val chosen = harness.waterCards.getValue(WaterLevelCategory.KNEE)
        val other = harness.waterCards.getValue(WaterLevelCategory.WAIST)

        assertTrue("the chosen card is marked for accessibility too", chosen.isSelected)
        assertTrue(
            "and it says so in words, for a screen reader and for a screenshot read out of context",
            chosen.contentDescription.toString().contains("chosen"),
        )
        assertFalse(other.isSelected)
        assertEquals(
            "an unchosen card is described by its own label alone",
            "waist-deep",
            other.contentDescription.toString(),
        )
    }

    @Test
    fun onlyOneCardPerCategoryCanBeChosenAtATime() {
        // Strict radio behaviour, asserted rather than assumed: choosing a second depth must clear the
        // first, or the panel would show two answers to one question.
        val harness = harness()
        harness.picker.show(SegmentId(43))

        harness.waterCards.getValue(WaterLevelCategory.ANKLE).performClick()
        assertTrue(harness.waterCards.getValue(WaterLevelCategory.ANKLE).isSelected)

        harness.waterCards.getValue(WaterLevelCategory.WAIST).performClick()
        assertTrue(harness.waterCards.getValue(WaterLevelCategory.WAIST).isSelected)
        assertFalse(
            "the first depth must be cleared when a second is chosen",
            harness.waterCards.getValue(WaterLevelCategory.ANKLE).isSelected,
        )
    }

    private fun layOut(grid: LinearLayout, width: Int) {
        grid.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
        )
        grid.layout(0, 0, width, grid.measuredHeight)
    }

    private fun widthOf(card: SelectionCard): Int = card.measuredWidth

    private fun harness(): Harness {
        val reports = mutableListOf<Pair<WaterLevelCategory, PassabilityStatus>>()
        // The grids and the title mirror the ids the sheet layout provides.
        val waterGrid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val passabilityGrid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val prompt = TextView(context)
        val submit = MaterialButton(context)
        val picker = WaterReportPicker(
            context = context,
            waterGrid = waterGrid,
            passabilityGrid = passabilityGrid,
            title = prompt,
            submitButton = submit,
            onReport = { level, verdict -> reports += level to verdict },
            // The same rule the ingress applies, asked of the domain rather than restated here.
            isReportable = { level, verdict ->
                com.resqroute.domain.hazard.RoadObservation.create(
                    com.resqroute.domain.hazard.HazardReason.WATERLOGGED,
                    level,
                    verdict,
                ) is com.resqroute.domain.hazard.ObservationValidation.Valid
            },
        )
        return Harness(
            picker = picker,
            waterCards = WaterLevelCategory.entries.associateWith { picker.cardFor(it) },
            passabilityCards = PassabilityStatus.entries.associateWith { picker.cardFor(it) },
            title = prompt,
            submit = submit,
            reports = reports,
            waterGrid = waterGrid,
            passabilityGrid = passabilityGrid,
        )
    }
}
