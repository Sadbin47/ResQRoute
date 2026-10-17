package com.resqroute.permissions

import android.Manifest
import android.app.Application
import android.content.Context
import com.resqroute.data.p2p.LocationPermissions
import com.resqroute.hazard.HazardFixtures
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The location permission contract, and the declaration bug it exists to prevent (XPH-90).
 *
 * ## The defect this file is named after
 *
 * `ACCESS_FINE_LOCATION` was declared with `android:maxSdkVersion="30"`, which was correct while the
 * only consumer was Nearby Connections' legacy discovery (API ≤ 30). When location-assisted
 * reporting was added it needed a fix on **every** version, and the cap was never revisited. On
 * Android 12+ the consequence was not "denied by the user" but **"impossible to grant"**: the
 * permission was absent from the installed app's requested set, `checkSelfPermission` was
 * permanently denied, `pm grant` silently did nothing, and the button reported *"Location is not
 * allowed — long-press the road instead."* on a device whose location permission had been granted
 * by hand.
 *
 * ## Why the existing tests did not catch it
 *
 * `LocationAssistedReportTest` drives a **fake** `LocationSource`, so it exercises every branch of
 * the feature while the packaged app cannot reach any of them. Nothing in the suite read the
 * manifest. These tests do: the declaration is asserted against the real file, which is the only
 * place this class of bug is visible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocationPermissionTest {

    private val context: Context get() = HazardFixtures.context()

    // ------------------------------------------------ the declaration (the regression)

    @Test
    fun locationIsDeclaredForEveryApiLevelNotJustLegacyNearby() {
        val manifest = manifestText()

        for (permission in listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
        )) {
            val element = declarationOf(manifest, permission)
            assertTrue(
                "$permission is not declared in the manifest, so the app can never be granted it " +
                    "and location-assisted reporting is unreachable",
                element != null,
            )
            assertFalse(
                "$permission must NOT carry a maxSdkVersion: it is needed on " +
                    "every API level by \"report where I am\", not only by legacy Nearby discovery. " +
                    "Found: ${element!!.trim()}",
                element.contains("maxSdkVersion"),
            )
        }
    }

    @Test
    fun theLegacyOnlyPermissionsStillCarryTheirCaps() {
        // The fix must not have been "remove every maxSdkVersion": BLUETOOTH and BLUETOOTH_ADMIN
        // really are legacy-only, and dropping their caps would request permissions Android 12+
        // refuses to grant.
        for (permission in listOf("android.permission.BLUETOOTH", "android.permission.BLUETOOTH_ADMIN")) {
            val element = declarationOf(manifestText(), permission)
            assertTrue("$permission must still be declared", element != null)
            assertTrue(
                "$permission is legacy-only and must keep its maxSdkVersion cap: ${element!!.trim()}",
                element.contains("maxSdkVersion=\"30\""),
            )
        }
    }

    // -------------------------------------------------------------- the contract

    @Test
    fun bothGrantsAreRequestedSoTheSystemCanOfferPreciseOrApproximate() {
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            LocationPermissions.required(),
        )
    }

    @Test
    fun anApproximateGrantIsAcceptedRatherThanRefused() {
        // Android 12+ "approximate location" grants COARSE without FINE. Refusing it would be the
        // same dead end the declaration bug produced, for a person who made a reasonable choice.
        grant(Manifest.permission.ACCESS_COARSE_LOCATION)

        assertTrue("a coarse fix is usable for a suggestion the person confirms", LocationPermissions.granted(context))
        assertTrue("and nothing should be re-requested", LocationPermissions.missing(context).isEmpty())
    }

    @Test
    fun nothingIsGrantedSoBothAreAskedFor() {
        assertFalse(LocationPermissions.granted(context))
        assertEquals(LocationPermissions.required(), LocationPermissions.missing(context))
    }

    @Test
    fun aPreciseGrantAlsoStopsTheRequest() {
        // The request list must empty itself once the person has answered the location question,
        // or the app would raise a dialog on every launch that the OS will not show again.
        grant(Manifest.permission.ACCESS_FINE_LOCATION)

        assertTrue(LocationPermissions.granted(context))
        assertTrue(LocationPermissions.missing(context).isEmpty())
    }

    // ------------------------------------------------------------------ helpers

    private fun grant(permission: String) {
        shadowOf(context as Application).grantPermissions(permission)
    }

    /**
     * The `<uses-permission>` element text for [permission], or null when it is not declared.
     *
     * Comments are stripped first: this file's own KDoc explains the defect by naming the
     * attribute, and a naive scan would match the explanation instead of the declaration. The name
     * is matched with its closing quote so `BLUETOOTH` cannot match `BLUETOOTH_SCAN`.
     */
    private fun declarationOf(manifest: String, permission: String): String? {
        val withoutComments = manifest.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        return Regex("<uses-permission\\b[^>]*>").findAll(withoutComments)
            .map { it.value }
            .firstOrNull { it.contains("android:name=\"$permission\"") }
    }

    private fun manifestText(): String =
        File(projectRoot(), "app/src/main/AndroidManifest.xml").readText()

    private fun projectRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("could not locate the project root from ${System.getProperty("user.dir")}")
    }
}
