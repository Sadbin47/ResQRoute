package com.resqroute.presentation

import android.app.Application
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.resqroute.R
import com.resqroute.hazard.HazardFixtures
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The floating map zoom controls (XPH-91).
 *
 * They exist because the map ships no tile downloader and therefore no built-in zoom widget, and
 * because a pinch is not always available. Their **placement and size are the feature** — a control
 * that is hidden behind the status bar, sat on the wrong side, or too small to hit is not a smaller
 * version of the feature, it is a broken one — so those are what is asserted here rather than the
 * click handler, which is two lines and verified on a device.
 *
 * The tests inflate the real `activity_main.xml`, so a change to the layout that moves the controls
 * fails here instead of on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MapZoomControlsTest {

    private fun inflate(): View {
        val themed = ContextThemeWrapper(HazardFixtures.context(), R.style.Theme_ResQRoute)
        return LayoutInflater.from(themed).inflate(R.layout.activity_main, null, false)
    }

    private fun View.density(): Float = resources.displayMetrics.density

    private fun projectRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("could not locate the project root from ${System.getProperty("user.dir")}")
    }

    @Test
    fun bothControlsExistAndNameThemselvesForAScreenReader() {
        val root = inflate()
        val zoomIn = root.findViewById<ImageButton>(R.id.btnZoomIn)
        val zoomOut = root.findViewById<ImageButton>(R.id.btnZoomOut)

        assertNotNull("the zoom-in control must exist", zoomIn)
        assertNotNull("the zoom-out control must exist", zoomOut)
        // An icon button with no content description is unlabelled for TalkBack, so the
        // description is part of the feature rather than decoration.
        assertEquals(
            HazardFixtures.context().getString(R.string.rq_zoom_in),
            zoomIn.contentDescription.toString(),
        )
        assertEquals(
            HazardFixtures.context().getString(R.string.rq_zoom_out),
            zoomOut.contentDescription.toString(),
        )
    }

    @Test
    fun theControlsStartHiddenBecauseThereIsNoMapToZoomYet() {
        // A zoom button that does nothing is worse than no button: the adapter does not exist
        // until the dataset is attached, so the controls must not be offered before then.
        val root = inflate()
        assertEquals(View.GONE, root.findViewById<View>(R.id.rq_zoom_controls).visibility)
    }

    @Test
    fun theControlsSitBelowTheTopBarAndOnTheRightEdge() {
        val root = inflate()
        val stack = root.findViewById<LinearLayout>(R.id.rq_top_stack)
        val controls = root.findViewById<LinearLayout>(R.id.rq_zoom_controls)
        val topBar = root.findViewById<LinearLayout>(R.id.rq_top_bar)

        assertNotNull("the top stack must exist", stack)
        // Structurally below the pill, inside the same vertical stack. Asserting the parent and
        // the ordering is what makes "below the offline tag" a fact rather than an intention.
        assertTrue("the pill and the controls must share the top stack", stack === topBar.parent)
        assertTrue("the controls must share the top stack", stack === controls.parent)
        assertEquals(LinearLayout.VERTICAL, stack.orientation)
        assertEquals(
            "the pill comes first, so the controls hang under it",
            0,
            stack.indexOfChild(topBar),
        )
        assertEquals(1, stack.indexOfChild(controls))

        // Right-aligned: the request was "right of screen". Cross-axis gravity inside a vertical
        // LinearLayout is how that is expressed.
        val params = controls.layoutParams as LinearLayout.LayoutParams
        assertEquals(Gravity.END, params.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)

        // And the stack is the top-anchored child of the root, so "below the top bar" is below the
        // bar rather than somewhere down the screen. The root is a CoordinatorLayout, so its
        // children carry CoordinatorLayout.LayoutParams - which is where the gravity lives.
        val stackParams = stack.layoutParams as CoordinatorLayout.LayoutParams
        assertEquals(
            Gravity.TOP,
            stackParams.gravity and Gravity.VERTICAL_GRAVITY_MASK,
        )
    }

    @Test
    fun theControlsAreComfortablyTappableRatherThanTiny() {
        // "Small, but not that small": the size is the one `dimens.xml` documents, and this asserts
        // both that the buttons use it and that it is a genuinely hittable size — a future edit that
        // shrinks them fails here rather than being noticed by someone missing the button one-handed.
        val root = inflate()
        val context = HazardFixtures.context()
        val expectedPx = context.resources.getDimensionPixelSize(R.dimen.rq_map_control_size)
        val density = root.density()

        assertTrue(
            "the control is ${expectedPx / density}dp; below 40dp it stops being a comfortable touch target",
            expectedPx / density >= 40f,
        )
        for (id in listOf(R.id.btnZoomIn, R.id.btnZoomOut)) {
            val button = root.findViewById<ImageButton>(id)
            assertEquals("button $id width", expectedPx, button.layoutParams.width)
            assertEquals("button $id height", expectedPx, button.layoutParams.height)
        }
    }

    @Test
    fun theTopStackOwnsTheStatusBarInsetNotThePillAlone() {
        // The controls are positioned relative to the pill, so the inset must be applied to the view
        // they both live in. If it stayed on the pill, the controls would sit a status bar's height
        // too high on any device with a taller status bar or a cutout - and the pixel result cannot
        // be asserted by inflating the layout, because the listener is attached in the Activity,
        // which this test does not run. So it is asserted where it lives: in the source.
        val activity = File(projectRoot(), "app/src/main/java/com/resqroute/app/MainActivity.kt").readText()

        assertTrue(
            "MainActivity must apply the window insets to rq_top_stack, not rq_top_bar: " +
                "the zoom controls hang below the pill and must move with it",
            activity.contains("setOnApplyWindowInsetsListener(topStack)"),
        )
        assertTrue(
            "the base top margin must also be read from the stack, or the first inset would " +
                "compound onto the pill's own margin",
            activity.contains("(topStack.layoutParams as ViewGroup.MarginLayoutParams).topMargin"),
        )
        assertFalse(
            "the pill must no longer own the inset listener",
            activity.contains("setOnApplyWindowInsetsListener(topBar)"),
        )
    }

}

