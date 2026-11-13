package org.projectdrive.app.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import org.projectdrive.app.core.navigation.Coordinate

data class LocationFix(val coordinate: Coordinate, val accuracyMeters: Float, val elapsedRealtimeMillis: Long)

class AndroidLocationSource(context: Context, private val onFix: (LocationFix) -> Unit, private val onUnavailable: () -> Unit) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!location.hasAccuracy() || location.accuracy < 0 || !location.accuracy.isFinite()) return
            val recorded = location.elapsedRealtimeNanos / 1_000_000
            if (SystemClock.elapsedRealtime() - recorded !in 0..15_000) return
            onFix(LocationFix(Coordinate(location.latitude, location.longitude), location.accuracy, recorded))
        }
        override fun onProviderDisabled(provider: String) { onUnavailable() }
        override fun onProviderEnabled(provider: String) {}
        @Deprecated("Required for LocationListener implementations on Android 8–10")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    fun start() {
        stop()
        try {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { manager.isProviderEnabled(it) }
            if (providers.isEmpty()) { onUnavailable(); return }
            providers.forEach { provider ->
                manager.getLastKnownLocation(provider)?.let { listener.onLocationChanged(it) }
                // Keep fresh fixes at a standstill; a distance filter can suppress stationary updates.
                manager.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
            }
        } catch (_: SecurityException) { onUnavailable() }
    }

    fun stop() { manager.removeUpdates(listener) }
}
