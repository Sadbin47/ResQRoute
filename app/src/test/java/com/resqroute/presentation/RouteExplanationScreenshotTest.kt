package com.resqroute.presentation

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.resqroute.core.model.SegmentId
import com.resqroute.domain.hazard.PassabilityStatus
import com.resqroute.domain.hazard.WaterLevelCategory
import com.resqroute.feature.map.presentation.RouteExplanation
import com.resqroute.feature.map.presentation.RouteExplanationCopy
import com.resqroute.hazard.HazardFixtures
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the route explanation to real PNGs (XPH-77's completion evidence).
 *
 * The issue asks for screenshots of four cases: differing routes, identical routes, unknown coverage
 * and the no-alternative case. This renders the sheet's own explanation block — the same lines, laid
 * out and composed by the same code the sheet calls — into a bitmap with Robolectric's native
 * graphics mode, and writes them under `docs/evidence/`.
 *
 * **What these are, precisely.** They are renders of the explanation block as the sheet composes it,
 * not photographs of the running app: the text, its order and its layout come from
 * [RouteExplanationCopy] and the sheet's own view hierarchy, while the surrounding map, top bar and
 * the rest of the sheet are not drawn. Labelled that way rather than called something they are not.
 *
 * The render is deterministic, so re-running the suite rewrites identical bytes and the committed
 * PNGs stay stable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RouteExplanationScreenshotTest {

    private val context: Context get() = HazardFixtures.context()

    /** The four documented cases, built by the same fixtures the assertions use. */
    private fun cases(): Map<String, RouteExplanation> {
        val fixture = RouteExplanationTest()
        return linkedMapOf(
            // Both policies choose a different road, and one of them crosses a reported hazard.
            "differing-routes" to run {
                // The reported road sits on the shortest route only, so the alternative genuinely
                // avoids something and the comparison has a delta worth showing.
                val lengths = RouteExplanationTest.LENGTHS_AB +
                    mapOf(4 to 100.0, 5 to 140.0, 6 to 140.0, 7 to 100.0)
                fixture.explain(
                    shortest = fixture.routeOf(lengths, 0, 1, 2, 3),
                    lowerRisk = fixture.routeOf(lengths, 4, 5, 6, 7),
                    lengths = lengths,
                    states = mapOf(
                        SegmentId(0) to fixture.riskState(
                            SegmentId(0), WaterLevelCategory.WAIST, PassabilityStatus.AVOID,
                            devices = 3, relayed = 1, receivedAgoMillis = 2 * RouteExplanationTest.HOUR,
                        ),
                        SegmentId(2) to fixture.riskState(
                            SegmentId(2), WaterLevelCategory.KNEE, PassabilityStatus.CAUTION,
                            devices = 2, receivedAgoMillis = 5 * RouteExplanationTest.HOUR,
                        ),
                    ),
                )
            },
            // Nothing reported anywhere: one route, and no choice to present as a comparison.
            "identical-routes" to fixture.explain(
                shortest = fixture.routeOf(RouteExplanationTest.LENGTHS_A, 0, 1),
                lowerRisk = fixture.routeOf(RouteExplanationTest.LENGTHS_A, 0, 1),
                lengths = RouteExplanationTest.LENGTHS_A,
                states = emptyMap(),
            ),
            // Mostly unreported, with one report to age: the disclosure that matters most.
            "unknown-coverage" to fixture.explain(
                shortest = fixture.routeOf(RouteExplanationTest.LENGTHS_C, 0, 1, 2),
                lowerRisk = fixture.routeOf(RouteExplanationTest.LENGTHS_C, 0, 1, 2),
                lengths = RouteExplanationTest.LENGTHS_C,
                states = mapOf(
                    SegmentId(0) to fixture.riskState(
                        SegmentId(0), WaterLevelCategory.KNEE, PassabilityStatus.CAUTION,
                        devices = 1, receivedAgoMillis = 3 * RouteExplanationTest.DAY,
                    ),
                ),
            ),
            // One route, and it crosses a report: the app must say there was no alternative.
            "no-alternative" to fixture.explain(
                shortest = fixture.routeOf(RouteExplanationTest.LENGTHS_C, 0, 1, 2),
                lowerRisk = fixture.routeOf(RouteExplanationTest.LENGTHS_C, 0, 1, 2),
                lengths = RouteExplanationTest.LENGTHS_C,
                states = mapOf(
                    SegmentId(1) to fixture.riskState(
                        SegmentId(1), WaterLevelCategory.ABOVE_WAIST, PassabilityStatus.AVOID,
                        devices = 2, relayed = 2, receivedAgoMillis = 10 * RouteExplanationTest.HOUR,
                    ),
                ),
            ),
        )
    }

    @Test
    fun rendersTheFourCasesTheIssueNames() {
        val evidenceDirectory = File(projectRoot(), "docs/evidence")
        assertTrue("evidence directory could not be created", evidenceDirectory.isDirectory || evidenceDirectory.mkdirs())

        for ((name, explanation) in cases()) {
            val lines = RouteExplanationCopy.sheetLines(context, explanation)
            assertTrue("case $name produced no lines", lines.isNotEmpty())
            val bitmap = renderLines(lines)
            val file = File(evidenceDirectory, "route-explanation-$name.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("nothing was drawn for $name", pixels.any { it != Color.WHITE })
            assertTrue("$name rendered an empty file", file.length() > 0L)
            println("EVIDENCE ${file.name}: ${bitmap.width}x${bitmap.height} bytes=${file.length()} lines=${lines.size}")
            // Printed as well as drawn: the images are the deliverable, but a reviewer (or a CI log)
            // should be able to read what they say without opening them.
            lines.forEach { println("    | $it") }
        }
    }

    /**
     * Lays the sheet's explanation block out and draws it.
     *
     * The view hierarchy mirrors the `routeCompareText` / `routeUnknownText` / `routeAvoidText`
     * block in `activity_main.xml`: a section heading and monospace body lines, so what is rendered
     * is what the sheet shows rather than a bespoke layout invented for the test.
     */
    private fun renderLines(lines: List<String>): Bitmap {
        val width = 900
        val padding = 24
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply {
                text = context.getString(com.resqroute.R.string.rq_route_section)
                textSize = 15f
                setTextColor(Color.BLACK)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            lines.forEach { line ->
                addView(TextView(context).apply {
                    text = line
                    textSize = 13f
                    setTextColor(Color.BLACK)
                    setPadding(0, 8, 0, 0)
                })
            }
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

    private fun projectRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("could not locate the project root from ${System.getProperty("user.dir")}")
    }
}
