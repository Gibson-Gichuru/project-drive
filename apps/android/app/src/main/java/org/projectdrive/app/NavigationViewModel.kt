package org.projectdrive.app

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.projectdrive.app.core.location.LocationFix
import org.projectdrive.app.core.location.preferredFix
import org.projectdrive.app.core.navigation.*

data class NavigationState(
    val originText: String = "-1.2864, 36.8200",
    val destinationText: String = "-1.3152, 36.8148",
    val destination: Coordinate = Coordinate(-1.3152, 36.8148),
    val route: Route? = null, val loading: Boolean = false, val error: String? = null,
    val gpsWanted: Boolean = false, val fix: LocationFix? = null,
    val locationStatus: String = "Location off · map preview available",
    val guiding: Boolean = false, val playback: Boolean = false,
    val simulatedPosition: Coordinate? = null, val progress: Progress? = null,
)

class NavigationViewModel : ViewModel() {
    private val engine: NavigationEngine = RemoteNavigationEngine(BuildConfig.MAP_SERVER_URL)
    private val mutable = MutableStateFlow(NavigationState())
    val state = mutable.asStateFlow()
    private var routeJob: Job? = null
    private var playbackJob: Job? = null
    private var useNextFixAsOrigin = false

    init {
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                val current = mutable.value
                val fix = current.fix
                if (current.gpsWanted && fix != null && !fix.isFresh(SystemClock.elapsedRealtime())) {
                    mutable.update { it.copy(fix = null, locationStatus = "Location stale · waiting for a fresh fix", progress = if (it.playback) it.progress else null) }
                }
            }
        }
    }

    private fun clearRoute() {
        routeJob?.cancel(); stopGuidance()
        mutable.update { it.copy(route = null, loading = false, error = null) }
    }
    fun editRoute() = clearRoute()
    fun setOrigin(text: String) { clearRoute(); mutable.update { it.copy(originText = text) } }
    fun setDestination(text: String) { clearRoute(); mutable.update { it.copy(destinationText = text) } }
    fun selectPin(coordinate: Coordinate) {
        if (mutable.value.guiding || mutable.value.playback) return
        clearRoute()
        mutable.update { it.copy(destination = coordinate, destinationText = "${coordinate.latitude}, ${coordinate.longitude}") }
    }

    fun previewRoute() {
        routeJob?.cancel(); stopGuidance()
        val input = mutable.value
        val origin = parseCoordinate(input.originText)
        val destination = parseCoordinate(input.destinationText)
        if (origin == null || destination == null) {
            mutable.update { it.copy(error = "Enter latitude, longitude for each point, or long-press the map for a destination.") }; return
        }
        mutable.update { it.copy(loading = true, error = null, route = null, destination = destination) }
        routeJob = viewModelScope.launch {
            try {
                val route = engine.route(origin, destination)
                mutable.update { it.copy(route = route, loading = false) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                mutable.update { it.copy(loading = false, error = "Route unavailable. Check the map-server connection and choose points on drivable roads in Kenya.") }
            }
        }
    }

    fun requestLocation() {
        useNextFixAsOrigin = true
        mutable.update { it.copy(gpsWanted = true, locationStatus = "Waiting for location · preview still available") }
    }
    fun locationDenied() {
        useNextFixAsOrigin = false
        mutable.update { it.copy(gpsWanted = false, guiding = false, fix = null, progress = null, locationStatus = "Location unavailable · map preview still available") }
    }
    fun locationUnavailable() {
        mutable.update { it.copy(fix = null, locationStatus = "Location unavailable · check device location settings", progress = if (it.playback) it.progress else null) }
    }
    fun onLocation(fix: LocationFix) {
        if (!mutable.value.gpsWanted) return
        val selected = preferredFix(mutable.value.fix, fix, SystemClock.elapsedRealtime()) ?: return
        if (selected !== fix) return
        if (useNextFixAsOrigin && fix.canGuide(SystemClock.elapsedRealtime())) {
            clearRoute(); useNextFixAsOrigin = false
            mutable.update { it.copy(originText = "${fix.coordinate.latitude}, ${fix.coordinate.longitude}") }
        }
        mutable.update { current -> current.copy(
            fix = fix,
            locationStatus = fix.accuracyStatus(),
            progress = if (current.guiding && fix.canGuide(SystemClock.elapsedRealtime())) current.route?.let { progressOnRoute(it, fix.coordinate) } else if (current.playback) current.progress else null,
        ) }
    }
    fun startGuidance() {
        val current = mutable.value; val fix = current.fix
        if (current.route == null || fix == null || !fix.canGuide(SystemClock.elapsedRealtime())) {
            mutable.update { it.copy(error = "Use your location and wait for a recent, accurate fix before starting guidance.") }; return
        }
        stopGuidance()
        mutable.update { it.copy(guiding = true, error = null, progress = progressOnRoute(current.route, fix.coordinate)) }
    }
    fun playRoute() {
        val route = mutable.value.route ?: return
        stopGuidance()
        playbackJob = viewModelScope.launch {
            mutable.update { it.copy(playback = true, error = null) }
            val stride = (route.geometry.size / 120).coerceAtLeast(1)
            for (i in route.geometry.indices step stride) {
                val position = route.geometry[i]
                mutable.update { it.copy(simulatedPosition = position, progress = progressOnRoute(route, position)) }; delay(500)
            }
            mutable.update { it.copy(simulatedPosition = route.geometry.last(), progress = progressOnRoute(route, route.geometry.last())) }
        }
    }
    fun stopGuidance() { playbackJob?.cancel(); mutable.update { it.copy(guiding = false, playback = false, simulatedPosition = null, progress = null) } }
    fun stopLocation() {
        useNextFixAsOrigin = false; stopGuidance()
        mutable.update { it.copy(gpsWanted = false, fix = null, locationStatus = "Location off · map preview available") }
    }
    fun resumeForeground() {
        if (mutable.value.gpsWanted) mutable.update { it.copy(locationStatus = "Waiting for location · preview still available") }
    }
    fun pauseForeground() {
        stopGuidance()
        mutable.update { it.copy(fix = null, locationStatus = if (it.gpsWanted) "Location paused while app is in the background" else it.locationStatus) }
    }
    companion object {
        fun parseCoordinate(text: String): Coordinate? = runCatching {
            val parts = text.split(','); require(parts.size == 2)
            Coordinate(parts[0].trim().toDouble(), parts[1].trim().toDouble())
        }.getOrNull()
    }
}
