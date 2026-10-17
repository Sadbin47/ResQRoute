package com.resqroute.data.p2p

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Runtime permissions required by Nearby Connections, per Android version.
 *
 * Implement runtime selection against the real OS version; the manifest
 * declares the matching union with the correct `maxSdkVersion` qualifiers.
 */
object NearbyPermissions {

    fun required(): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_ADVERTISE
            list += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        return list
    }

    fun missing(context: Context): List<String> = required().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
}

/**
 * The location permission contract for location-assisted reporting (XPH-90).
 *
 * ## Why this is separate from [NearbyPermissions]
 *
 * They have different requirements, and conflating them is what caused a real defect. Nearby needs
 * location only on API ≤ 30; the reporting feature needs it on **every** version. While the two
 * were the same list, the manifest declared location with `maxSdkVersion="30"` and `required()`
 * returned it only below API 31 — so on Android 12+ the app neither requested it nor could be
 * granted it, and "Report where I am" silently reported "Location is not allowed" forever.
 *
 * ## Approximate is accepted on purpose
 *
 * Android 12+ offers "approximate location", which grants [ACCESS_COARSE_LOCATION] without FINE.
 * Refusing that grant would reintroduce the same dead end for a person who made a deliberate,
 * reasonable choice. It is safe to accept because the suggestion path already treats accuracy as
 * uncertain: it shows the reported accuracy, raises a concern when the fix is poor or the match is
 * ambiguous, and **always asks for confirmation before anything is written**. A coarse fix
 * therefore hedges the suggestion rather than justifying it.
 */
object LocationPermissions {

    /** What the app asks for. Both, so the OS can offer precise or approximate. */
    fun required(): List<String> = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /**
     * The permissions still to ask for: **both** while neither is granted, and **none** once either is.
     *
     * Deliberately not "the ones not granted", which is how a request list normally reads. Location
     * is one permission group with one user decision, so a filter would keep proposing whichever
     * member the OS did not record — and because an answered group is not re-prompted, that turns
     * into a request raised on every launch that can never succeed. The question that matters is
     * "has this person answered the location question at all", and that is [granted].
     */
    fun missing(context: Context): List<String> =
        if (granted(context)) emptyList() else required()

    /**
     * True when a fix can be read at all: **either** precise or approximate was granted.
     *
     * This is the predicate the feature must use, not "FINE is granted" — see the class note.
     */
    fun granted(context: Context): Boolean = required().any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}


/** Snapshot of this phone's platform state, recorded for the device log. */
object SpikeDiagnostics {

    data class Snapshot(
        val manufacturer: String,
        val model: String,
        val androidRelease: String,
        val sdkInt: Int,
        val minSdk: Int,
        val appVersion: String,
        val playServices: String,
        val bluetoothEnabled: String,
        val wifiEnabled: String,
        val missingPermissions: List<String>,
    ) {
        fun render(): String = buildString {
            appendLine("device: $manufacturer $model | Android $androidRelease (API $sdkInt) | minSdk $minSdk")
            appendLine("app: $appVersion")
            appendLine("Play services: $playServices")
            appendLine("Bluetooth radio: $bluetoothEnabled | Wi-Fi radio: $wifiEnabled")
            append(
                if (missingPermissions.isEmpty()) "Permissions: all granted"
                else "Permissions missing: ${missingPermissions.joinToString { it.substringAfterLast('.') }}",
            )
        }
    }

    fun capture(context: Context): Snapshot {
        val pm = context.packageManager
        val appInfo = try {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(context.packageName, 0)
        } catch (_: Throwable) {
            null
        }
        val playServices = try {
            when (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)) {
                ConnectionResult.SUCCESS -> "available"
                ConnectionResult.SERVICE_MISSING -> "missing"
                ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED -> "update required"
                else -> "unavailable"
            }
        } catch (_: Throwable) {
            "unknown"
        }
        val bluetooth = try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothManager)?.adapter
            when {
                adapter == null -> "no adapter"
                adapter.isEnabled -> "on"
                else -> "off"
            }
        } catch (_: Throwable) {
            "unknown (permission)"
        }
        val wifi = try {
            val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            when {
                manager == null -> "no adapter"
                manager.isWifiEnabled -> "on"
                else -> "off"
            }
        } catch (_: Throwable) {
            "unknown"
        }
        return Snapshot(
            manufacturer = Build.MANUFACTURER ?: "?",
            model = Build.MODEL ?: "?",
            androidRelease = Build.VERSION.RELEASE ?: "?",
            sdkInt = Build.VERSION.SDK_INT,
            minSdk = appInfo?.minSdkVersion ?: 0,
            appVersion = try {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0).versionName ?: "?"
            } catch (_: Throwable) {
                "?"
            },
            playServices = playServices,
            bluetoothEnabled = bluetooth,
            wifiEnabled = wifi,
            // Both contracts: Nearby's (version-dependent) and location's (every version). A missing
            // location grant belongs here too — leaving it out is how the reporting feature looked
            // healthy in the log while being unreachable on Android 12+.
            missingPermissions = (NearbyPermissions.missing(context) + LocationPermissions.missing(context))
                .distinct(),
        )
    }
}
