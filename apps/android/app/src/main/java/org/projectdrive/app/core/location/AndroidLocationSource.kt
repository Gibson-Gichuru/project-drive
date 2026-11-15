package org.projectdrive.app.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import org.projectdrive.app.core.navigation.Coordinate

class AndroidLocationSource(context: Context, private val onFix: (LocationFix) -> Unit, private val onUnavailable: () -> Unit) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy < 0) return
            val recorded = location.elapsedRealtimeNanos / 1_000_000
            if (SystemClock.elapsedRealtime() - recorded !in 0..15_000) return
            // Framework locations normally validate coordinates; reject malformed provider data safely.
            val coordinate = runCatching { Coordinate(location.latitude, location.longitude) }.getOrNull() ?: return
            onFix(LocationFix(coordinate, location.accuracy, recorded, location.speed.takeIf { location.hasSpeed() }, location.speedAccuracyMetersPerSecond.takeIf { location.hasSpeedAccuracy() }, location.bearing.takeIf { location.hasBearing() }, location.bearingAccuracyDegrees.takeIf { location.hasBearingAccuracy() }))
        }
        override fun onProviderDisabled(provider: String) { onUnavailable() }
        override fun onProviderEnabled(provider: String) {}
        @Deprecated("Required for LocationListener implementations on Android 8–10")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    fun start() {
        stop()
        var registered = false
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            try {
                if (!manager.isProviderEnabled(provider)) return@forEach
                manager.getLastKnownLocation(provider)?.let { listener.onLocationChanged(it) }
                // Keep fresh fixes at a standstill; a distance filter can suppress stationary updates.
                manager.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
                registered = true
            } catch (_: SecurityException) {
                // A fine-only provider must not prevent an approximate provider
                // from registering when the user granted coarse location only.
            }
        }
        if (!registered) onUnavailable()
    }

    fun stop() { manager.removeUpdates(listener) }
}
