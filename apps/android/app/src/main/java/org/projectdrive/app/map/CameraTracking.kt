package org.projectdrive.app.map

import org.projectdrive.app.core.navigation.Route
import kotlin.math.log2

/** Zoom out with uncertainty instead of giving a coarse fix street-level precision. */
internal fun currentLocationZoom(accuracyMeters:Float?):Double = when {
    accuracyMeters==null -> 16.0 // Explicit simulated positions have no reported GPS uncertainty.
    !accuracyMeters.isFinite()||accuracyMeters<=0f -> 12.0
    else -> (16.0-log2(accuracyMeters.coerceAtLeast(50f).toDouble()/50.0)).coerceIn(3.0,16.0)
}

/** User gestures own the camera until explicit recenter or a new journey. */
class CameraTracking(restoredExploration: Boolean = false) {
    var exploring: Boolean = restoredExploration
        private set
    private var initialized = false
    private var route: Route? = null
    private var followRequested = false
    val following get() = followRequested && !exploring

    fun update(nextRoute: Route?, follow: Boolean) {
        if (initialized && ((nextRoute != null && nextRoute !== route) || (follow && !followRequested))) exploring = false
        initialized = true
        route = nextRoute
        followRequested = follow
    }
    fun gesture() { exploring = true }
    fun recenter() { exploring = false }
}
