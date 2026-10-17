package com.resqroute.feature.map.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.feature.map.presentation.SegmentHistory
import com.resqroute.feature.map.presentation.SegmentHistoryCopy
import com.resqroute.feature.map.presentation.TimeBands
import java.util.TimeZone

/**
 * Binds one road's reported history to the sheet's block (XPH-71).
 *
 * It lives outside the Activity for a specific reason: the last mile of a presentation feature is
 * exactly where an untested bug hides — the copy can be perfect while the block is never made
 * visible, or stays visible showing the previous road. With this as a plain function over three
 * views, a JVM test can inflate the **real** layout and assert what the screen would show, which is
 * far stronger than trusting an Activity that only a human with a map gesture can reach.
 *
 * Two rules it keeps, both about not lying to the reader:
 *
 *  * a road with no reports still shows the block — "No reports on this road." is the answer, and it
 *    is not the same as *not knowing what to show*;
 *  * when nothing is selected the block is **emptied as well as hidden**, so a later selection can
 *    never flash the previous road's history.
 */
object SegmentHistorySection {

    /**
     * Renders [state] into the block.
     *
     * [state] is null when nothing is selected. [now] and [zone] are supplied by the caller — the UI
     * boundary — so nothing below reads a clock or the device's timezone (ADR 0005 §6.1, ADR 0019).
     */
    fun render(
        context: Context,
        section: View,
        body: TextView,
        note: TextView,
        state: SegmentObservationState?,
        now: Long,
        zone: TimeZone = TimeBands.incidentZone(),
    ) {
        if (state == null) {
            hide(section, body, note)
            return
        }
        val history = SegmentHistory.of(state, now, zone)
        section.visibility = View.VISIBLE
        body.text = SegmentHistoryCopy.bodyLines(context, history).joinToString("\n")
        note.text = SegmentHistoryCopy.captionLine(context, history)
    }

    /** Hide the block and clear it, so a hidden block never describes a road that is no longer selected. */
    fun hide(section: View, body: TextView, note: TextView) {
        section.visibility = View.GONE
        body.text = ""
        note.text = ""
    }
}
