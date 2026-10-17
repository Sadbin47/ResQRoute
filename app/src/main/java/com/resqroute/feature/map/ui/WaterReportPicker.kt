package com.resqroute.feature.map.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.resqroute.R
import com.resqroute.core.model.SegmentId
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.feature.map.presentation.HazardSupportCopy

/**
 * The report sheet: what a person saw on a road, as two grids of cards (XPH-93).
 *
 * ## Why the shape changed
 *
 * The previous picker put six categories in one cramped row of small buttons. Measured on a 480×800
 * device the buttons had different heights and top edges, so the row had dead bands inside it and the
 * chosen state was an opacity change nobody could see — reported from a phone as "pressing those
 * buttons does nothing". Cards in a grid fix the geometry, and a filled card with a badge fixes the
 * feedback.
 *
 * ## What stayed the same, deliberately
 *
 *  * **Everything is generated from the enums.** Every [WaterLevelCategory] and [PassabilityStatus]
 *    gets exactly one card, so a category added to the domain appears here without anyone remembering
 *    to add a view, and a category that lost its card cannot happen.
 *  * **The labels come from [HazardSupportCopy]**, so the picker and the reading of a committed report
 *    use one vocabulary — categories, never centimetres — already covered by the wording gate.
 *  * **The two axes are chosen separately.** They are separate observations (ADR 0001 D1); the sheet
 *    never infers one from the other.
 *  * **It does not decide representability.** [isReportable] asks the same rule the ingress applies
 *    rather than restating it.
 */
class WaterReportPicker(
    private val context: Context,
    private val waterGrid: LinearLayout,
    private val passabilityGrid: LinearLayout,
    private val title: TextView,
    private val submitButton: MaterialButton,
    private val onReport: (WaterLevelCategory, PassabilityStatus) -> Unit,
    private val isReportable: (WaterLevelCategory, PassabilityStatus) -> Boolean,
) {

    private var segment: SegmentId? = null
    private var waterLevel: WaterLevelCategory? = null
    private var passability: PassabilityStatus? = null

    private val waterCards = LinkedHashMap<WaterLevelCategory, SelectionCard>()
    private val passabilityCards = LinkedHashMap<PassabilityStatus, SelectionCard>()

    private val gapPx: Int = context.resources.getDimensionPixelSize(R.dimen.rq_grid_gap)

    init {
        // Two columns of depth: six categories, three even rows.
        WaterLevelCategory.entries.chunked(2).forEach { rowItems ->
            val row = addRow(waterGrid)
            rowItems.forEach { level ->
                waterCards[level] = addCard(
                    row = row,
                    icon = depthIcon(level),
                    label = HazardSupportCopy.waterLevelText(context, level),
                    semanticColor = R.color.rq_icon_water,
                ) { onWaterLevelChosen(level) }
            }
        }
        // Staggered passability: three across, then two - which gives five uneven categories an even
        // shape instead of a row with a ragged end.
        listOf(PassabilityStatus.entries.take(3), PassabilityStatus.entries.drop(3)).forEach { rowItems ->
            val row = addRow(passabilityGrid)
            rowItems.forEach { verdict ->
                passabilityCards[verdict] = addCard(
                    row = row,
                    icon = passabilityIcon(verdict),
                    label = HazardSupportCopy.passabilityText(context, verdict),
                    semanticColor = passabilityColor(verdict),
                ) { onPassabilityChosen(verdict) }
            }
        }
        submitButton.setOnClickListener {
            val level = waterLevel ?: return@setOnClickListener
            val verdict = passability ?: return@setOnClickListener
            if (isReportable(level, verdict)) onReport(level, verdict)
        }
        refresh()
    }

    /**
     * Shows the sheet for [segmentId], with nothing chosen yet.
     *
     * The title carries the chosen segment's **stable id**, which is what a person reading it out or
     * matching it to the map needs.
     */
    fun show(segmentId: SegmentId) {
        segment = segmentId
        waterLevel = null
        passability = null
        title.visibility = android.view.View.VISIBLE
        title.text = context.getString(R.string.rq_report_title, segmentId.stableId)
        refresh()
    }

    fun hide() {
        segment = null
        waterLevel = null
        passability = null
        title.visibility = android.view.View.GONE
        refresh()
    }

    /** The categories offered, for a caller that wants to assert totality. */
    fun offeredWaterLevels(): List<WaterLevelCategory> = waterCards.keys.toList()

    fun offeredPassabilities(): List<PassabilityStatus> = passabilityCards.keys.toList()

    /** The card for one category, so a test can drive the real view rather than a copy of it. */
    fun cardFor(level: WaterLevelCategory): SelectionCard = waterCards.getValue(level)

    fun cardFor(verdict: PassabilityStatus): SelectionCard = passabilityCards.getValue(verdict)

    private fun onWaterLevelChosen(level: WaterLevelCategory) {
        waterLevel = level
        refresh()
    }

    private fun onPassabilityChosen(verdict: PassabilityStatus) {
        passability = verdict
        refresh()
    }

    /**
     * Rebinds every card and the action button.
     *
     * The button is enabled only for a pair the ingress would accept, and its label names both
     * choices, so nobody sends a report they did not mean. Colour is never the only signal: the chosen
     * card carries a badge and a content description as well as the accent fill.
     */
    private fun refresh() {
        val shown = segment != null
        waterGrid.visibility = if (shown) android.view.View.VISIBLE else android.view.View.GONE
        passabilityGrid.visibility = if (shown) android.view.View.VISIBLE else android.view.View.GONE
        for ((level, card) in waterCards) {
            card.bind(
                iconRes = depthIcon(level),
                text = HazardSupportCopy.waterLevelText(context, level),
                semanticColor = R.color.rq_icon_water,
                selected = level == waterLevel,
            )
        }
        for ((verdict, card) in passabilityCards) {
            card.bind(
                iconRes = passabilityIcon(verdict),
                text = HazardSupportCopy.passabilityText(context, verdict),
                semanticColor = passabilityColor(verdict),
                selected = verdict == passability,
            )
        }

        val level = waterLevel
        val verdict = passability
        val ready = level != null && verdict != null && isReportable(level, verdict)
        submitButton.isEnabled = ready
        submitButton.text = when {
            level == null -> context.getString(R.string.rq_report_choose_level)
            verdict == null -> context.getString(R.string.rq_report_choose_passability)
            !isReportable(level, verdict) -> context.getString(R.string.rq_report_incomplete)
            else -> context.getString(
                R.string.rq_report_submit_selected,
                titleCased(HazardSupportCopy.waterLevelText(context, level)),
                titleCased(HazardSupportCopy.passabilityText(context, verdict)),
            )
        }
    }

    // ---------------------------------------------------------------- icons

    /**
     * One icon per category, exhaustive so a new category is a compile error rather than a blank card.
     *
     * The depth icons share a container and differ only in how full it is: an ordinal position, never
     * a measurement, which is the same claim the categories themselves make.
     */
    private fun depthIcon(level: WaterLevelCategory): Int = when (level) {
        WaterLevelCategory.NO_VISIBLE_WATER -> R.drawable.rq_ic_depth_none
        WaterLevelCategory.ANKLE -> R.drawable.rq_ic_depth_ankle
        WaterLevelCategory.KNEE -> R.drawable.rq_ic_depth_knee
        WaterLevelCategory.WAIST -> R.drawable.rq_ic_depth_waist
        WaterLevelCategory.ABOVE_WAIST -> R.drawable.rq_ic_depth_above
        WaterLevelCategory.UNKNOWN -> R.drawable.rq_ic_depth_unknown
    }

    private fun passabilityIcon(verdict: PassabilityStatus): Int = when (verdict) {
        PassabilityStatus.PASSABLE_REPORTED -> R.drawable.rq_ic_pass_passable
        PassabilityStatus.CAUTION -> R.drawable.rq_ic_pass_caution
        PassabilityStatus.AVOID -> R.drawable.rq_ic_pass_avoid
        PassabilityStatus.IMPASSABLE -> R.drawable.rq_ic_pass_impassable
        PassabilityStatus.UNKNOWN -> R.drawable.rq_ic_pass_unknown
    }

    /**
     * The semantic colour of a verdict while it is unchosen.
     *
     * These answer "how bad is this option", not "is it selected": green can be walked or driven,
     * amber needs care, red is not to be taken. Impassable shares the avoid colour because they are
     * one severity apart and the barricade icon carries the difference; the label carries it too.
     */
    private fun passabilityColor(verdict: PassabilityStatus): Int = when (verdict) {
        PassabilityStatus.PASSABLE_REPORTED -> R.color.rq_semantic_passable
        PassabilityStatus.CAUTION -> R.color.rq_semantic_caution
        PassabilityStatus.AVOID -> R.color.rq_semantic_avoid
        PassabilityStatus.IMPASSABLE -> R.color.rq_semantic_avoid
        PassabilityStatus.UNKNOWN -> R.color.rq_icon_neutral
    }

    // ---------------------------------------------------------------- layout

    /**
     * A horizontal row inside the grid container; every row's cards share the width equally.
     *
     * The row's minimum height is what makes the rows uniform. `CardView` ignores a minimum height set
     * on itself - measured on a device, where one row came out 70px tall against its siblings' 126px
     * and the label was clipped out of the card entirely. A `LinearLayout` respects it, and the cards
     * stretch to whatever the row decides.
     */
    private fun addRow(container: LinearLayout): LinearLayout {
        val rowHeight = context.resources.getDimensionPixelSize(R.dimen.rq_card_min_height)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                // An explicit height, not wrap_content plus a minimum. Two things were tried and
                // measured on a device before this: `CardView` ignores a minimum height set on
                // itself, and a `minHeight` on the row does not stretch its cards either - the last
                // row came out 70px against its siblings' 126px, which clipped its label out of the
                // card. One height per row is what makes every card in the panel the same size.
                rowHeight,
            ).apply { if (container.childCount > 0) topMargin = gapPx }
        }
        container.addView(row)
        return row
    }

    /**
     * One choice card, weighted to fill an equal share of its row.
     *
     * Weights rather than fixed widths: the grid then divides whatever width the panel is given, so the
     * two- and three-column rows stay even on any screen instead of at one assumed size.
     */
    private fun addCard(
        row: LinearLayout,
        icon: Int,
        label: String,
        semanticColor: Int,
        onClick: () -> Unit,
    ): SelectionCard {
        val card = SelectionCard(context)
        // MATCH_PARENT height against the row's own height, so every card in a row is the same size
        // and the row's minimum is what they all take. WRAP_CONTENT here is what produced ragged rows.
        card.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = gapPx / 2
            marginEnd = gapPx / 2
        }
        card.bind(iconRes = icon, text = label, semanticColor = semanticColor, selected = false)
        card.setOnClickListener { onClick() }
        row.addView(card)
        return card
    }

    /** "above waist" → "Above Waist", for the action label only; the cards keep their own case. */
    private fun titleCased(text: String): String =
        text.split(' ').joinToString(" ") { word ->
            word.replaceFirstChar { character -> character.uppercase() }
        }

}
