package org.projectdrive.app.core.location

import kotlin.math.ceil
import org.projectdrive.app.core.navigation.Coordinate

const val FRESH_FIX_MILLIS = 15_000L
private const val PRECISE_FIX_PREFERENCE_MILLIS = 3_000L
const val GUIDANCE_ACCURACY_METERS = 50f

data class LocationFix(val coordinate: Coordinate, val accuracyMeters: Float, val elapsedRealtimeMillis: Long) {
    fun isFresh(nowMillis: Long) = nowMillis - elapsedRealtimeMillis in 0..FRESH_FIX_MILLIS
    // Android permits zero accuracy. For this trial it is insufficient evidence for guidance;
    // it must never be presented as a guarantee of a perfect fix.
    fun hasGuidanceAccuracy() = accuracyMeters.isFinite() && accuracyMeters > 0f && accuracyMeters <= GUIDANCE_ACCURACY_METERS
    fun canGuide(nowMillis: Long) = isFresh(nowMillis) && hasGuidanceAccuracy()
    fun accuracyStatus(): String = when {
        !accuracyMeters.isFinite() || accuracyMeters <= 0f -> "Location accuracy unknown · waiting for a reliable estimate"
        accuracyMeters < 1f -> "Location accuracy <1 m · provider estimate"
        else -> "Location accuracy ±${ceil(accuracyMeters.toDouble()).toInt()} m" +
            if (accuracyMeters > GUIDANCE_ACCURACY_METERS) " · too approximate for guidance" else ""
    }
}

// GPS and network callbacks can interleave. Keep a recent usable GPS fix rather than
// letting an immediate coarse callback disable it, but never keep it fresh artificially.
fun preferredFix(current: LocationFix?, candidate: LocationFix, nowMillis: Long): LocationFix? {
    if (!candidate.accuracyMeters.isFinite() || candidate.accuracyMeters < 0f || !candidate.isFresh(nowMillis)) return current?.takeIf { it.isFresh(nowMillis) }
    if (current != null && current.isFresh(nowMillis)) {
        if (candidate.elapsedRealtimeMillis < current.elapsedRealtimeMillis) return current
        if (current.hasGuidanceAccuracy() && !candidate.hasGuidanceAccuracy() &&
            nowMillis - current.elapsedRealtimeMillis <= PRECISE_FIX_PREFERENCE_MILLIS) return current
    }
    return candidate
}
