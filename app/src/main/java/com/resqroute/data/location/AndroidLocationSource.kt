package com.resqroute.data.location

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import com.resqroute.core.model.GeoCoordinate
import com.resqroute.data.p2p.LocationPermissions
import com.resqroute.domain.location.LocationFix
import com.resqroute.domain.location.LocationOutcome
import com.resqroute.domain.location.LocationSource

/**
 * The system's last known location, for location-assisted reporting (XPH-90).
 *
 * ## Last known only
 *
 * This reads [LocationManager.getLastKnownLocation] and never requests updates. A person reporting a
 * flooded road is about to choose a water level, which takes several seconds: asking for a fresh fix,
 * or holding the radio open, would add cost and delay for a selection that a last-known fix already
 * answers well enough. It also means this call never blocks on a fix that may never arrive.
 *
 * ## Why the accuracy is honest about being poor
 *
 * The provider's accuracy radius is returned as reported, including when it is null. It is never
 * clamped into a tidy range and never treated as a guarantee: [com.resqroute.domain.location.SegmentSnapper]
 * uses it to *hedge* the suggestion, not to justify one.
 *
 * ## What it never does
 *
 * It never stores, logs or transmits a coordinate. The fix exists for as long as it takes to resolve
 * a segment id, and the report carries that id only — the wire format has no place for a position
 * (`PeerProtocol`), and this class does not add one.
 */
class AndroidLocationSource(context: Context) : LocationSource {

    private val appContext = context.applicationContext

    override suspend fun lastKnownFix(): LocationOutcome {
        // Either grant is usable: Android 12+ offers "approximate location", which grants COARSE
        // without FINE. Requiring FINE specifically would refuse a deliberate, reasonable choice
        // and would strand the feature again (see `LocationPermissions`).
        if (!LocationPermissions.granted(appContext)) return LocationOutcome.PermissionDenied

        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return LocationOutcome.Failed("no location service")

        // Best first: a GPS fix is the most precise, a network fix is a fallback. Any of them answers
        // "which road am I on" far better than no fix at all.
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var best: Location? = null
        for (provider in providers) {
            val candidate = try {
                if (!manager.isProviderEnabled(provider)) null else manager.getLastKnownLocation(provider)
            } catch (security: SecurityException) {
                // Permission was revoked between the check above and here.
                return LocationOutcome.PermissionDenied
            } catch (error: IllegalArgumentException) {
                // The provider does not exist on this device.
                null
            } ?: continue
            if (best == null || candidate.accuracy < best.accuracy) best = candidate
        }

        val location = best ?: return LocationOutcome.NoFix
        return LocationOutcome.Fix(
            LocationFix(
                coordinate = GeoCoordinate(location.latitude, location.longitude),
                // `hasAccuracy` distinguishes "reported zero" from "did not report"; a location with
                // no accuracy is passed through as unknown rather than as perfect.
                accuracyMeters = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            ),
        )
    }
}
