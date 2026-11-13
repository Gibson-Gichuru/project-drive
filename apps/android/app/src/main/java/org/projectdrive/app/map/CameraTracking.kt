package org.projectdrive.app.map

import org.projectdrive.app.core.navigation.Route

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
