package com.resqroute.presentation

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.resqroute.core.model.SegmentId
import com.resqroute.domain.hazard.HazardReason
import com.resqroute.domain.hazard.ObservationSource
import com.resqroute.domain.hazard.ObservedReport
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.SegmentObservationAggregator
import com.resqroute.domain.hazard.SegmentObservationState
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.feature.map.presentation.SegmentHistory
import com.resqroute.feature.map.presentation.SegmentHistoryCopy
import com.resqroute.feature.map.presentation.TimeBands
import com.resqroute.hazard.HazardFixtures
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the reported-history block to real PNGs (XPH-71's completion evidence).
 *
 * The issue's definition of done asks for "a screenshot of a two-observation segment", so that is
 * the first case; the sparse cases are rendered beside it because "insufficient history" is a
 * deliverable too and a reviewer should be able to see that it reads as an admission rather than as
 * an empty panel.
 *
 * **What these are, precisely.** They are renders of the sheet's history block — section heading,
 * monospace body, caption — composed from the same `SegmentHistoryCopy` lines the sheet sets, and
 * from the same view hierarchy (`rq_history_text` / `rq_history_note_text` in `activity_main.xml`).
 * They are not photographs of the running app: the surrounding map, top bar and the rest of the
 * sheet are not drawn. Labelled that way rather than called something they are not.
 *
 * Deterministic: `now` and the zone are fixed, so re-running the suite rewrites identical bytes and
 * the committed PNGs stay stable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SegmentHistoryScreenshotTest {

    private val context: Context get() = HazardFixtures.context()

    private val zone: TimeZone = TimeBands.incidentZone()

    /** Noon the day after every fixture report, so the ages are short and the times unambiguous. */
    private val now: Long = local(2023, Calendar.NOVEMBER, 14, 12, 0)

    /** The blocked set of cases: the definition-of-done case, and the two sparse ones. */
    private fun cases(): Map<String, SegmentHistory> = linkedMapOf(
        // The definition of done: a segment with two real observations, from two devices, six hours
        // apart, so the block shows rows, support, attribution and one trend sentence.
        "two-observations" to historyOf(
            report("evt-hist-0001", "device-self", local(2023, Calendar.NOVEMBER, 13, 14, 10), WaterLevelCategory.ANKLE, PassabilityStatus.CAUTION, ObservationSource.DIRECT_OBSERVATION, now - 22 * HOUR),
            report("evt-hist-0002", "device-peer-0002", local(2023, Calendar.NOVEMBER, 13, 20, 40), WaterLevelCategory.KNEE, PassabilityStatus.CAUTION, ObservationSource.RELAYED_ACCOUNT, now - 16 * HOUR),
        ),
        // One report: the honest answer is that there is no history to compare.
        "single-report" to historyOf(
            report("evt-hist-0003", "device-self", local(2023, Calendar.NOVEMBER, 13, 19, 40), WaterLevelCategory.WAIST, PassabilityStatus.AVOID, ObservationSource.DIRECT_OBSERVATION, now - HOUR),
        ),
        // Nobody has reported: the missing-data case, which must not read as a clean road.
        "no-reports" to SegmentHistory.of(
            SegmentObservationState(SegmentId(SEGMENT), emptyList()),
            now,
            zone,
        ),
    )

    @Test
    fun rendersTheHistoryBlockForTheDefinitionOfDoneAndTheSparseCases() {
        val evidenceDirectory = File(projectRoot(), "docs/evidence")
        assertTrue("evidence directory could not be created", evidenceDirectory.isDirectory || evidenceDirectory.mkdirs())

        for ((name, history) in cases()) {
            val body = SegmentHistoryCopy.bodyLines(context, history)
            val caption = SegmentHistoryCopy.captionLine(context, history)
            assertTrue("case $name produced no lines", body.isNotEmpty())
            val bitmap = renderBlock(body, caption)
            val file = File(evidenceDirectory, "segment-history-$name.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("nothing was drawn for $name", pixels.any { it != Color.WHITE })
            assertTrue("$name rendered an empty file", file.length() > 0L)
            println("EVIDENCE ${file.name}: ${bitmap.width}x${bitmap.height} bytes=${file.length()} lines=${body.size + 1}")
            // Printed as well as drawn: the images are the deliverable, but a reviewer (or a CI log)
            // should be able to read what they say without opening them.
            (body + caption).forEach { println("    | $it") }
        }
    }

    /**
     * Lays the block out and draws it.
     *
     * Mirrors the `historySection` block in `activity_main.xml`: a section heading, monospace body
     * lines, then the caption, so what is rendered is what the sheet shows rather than a bespoke
     * layout invented for the test.
     */
    private fun renderBlock(body: List<String>, caption: String): Bitmap {
        val width = 900
        val padding = 24
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply {
                text = context.getString(com.resqroute.R.string.rq_section_history)
                textSize = 15f
                setTextColor(Color.BLACK)
                setTypeface(typeface, Typeface.BOLD)
            })
            body.forEach { line ->
                addView(TextView(context).apply {
                    text = line
                    textSize = 13f
                    setTextColor(Color.BLACK)
                    setPadding(0, 8, 0, 0)
                })
            }
            addView(TextView(context).apply {
                text = caption
                textSize = 11f
                setTextColor(Color.DKGRAY)
                setPadding(0, 14, 0, 0)
            })
        }
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        block.measure(widthSpec, heightSpec)
        val height = maxOf(block.measuredHeight, 1)
        block.layout(0, 0, width, height)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        block.draw(Canvas(bitmap))
        return bitmap
    }

    // ------------------------------------------------------------------ fixture

    private fun historyOf(vararg reports: ObservedReport): SegmentHistory =
        SegmentHistory.of(
            SegmentObservationAggregator.aggregateSegment(SegmentId(SEGMENT), reports.toList()),
            now,
            zone,
        )

    private fun report(
        eventId: String,
        device: String,
        occurredAt: Long,
        level: WaterLevelCategory,
        passability: PassabilityStatus,
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

    private fun local(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone, Locale.US).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis

    private fun projectRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("could not locate the project root from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val SEGMENT = 22_958
        const val HOUR = 3_600_000L
    }
}
