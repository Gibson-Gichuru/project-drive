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
    val originText: String = "",
    val destinationText: String = "",
    val destination: Coordinate? = null,
    val originLabel:String="", val destinationLabel:String="",
    val searchQuery:String="", val searchOrigin:Boolean=false, val searching:Boolean=false,
    val places:List<Place> = emptyList(),val searchError:String?=null,
    val plannerRequest:Long=0, val focusRequest:Long=0,
    val route: Route? = null, val loading: Boolean = false, val error: String? = null,
    val gpsWanted: Boolean = false, val fix: LocationFix? = null,
    val locationStatus: String = "Location off · map preview available",
    val guiding: Boolean = false, val playback: Boolean = false,
    val simulatedPosition: Coordinate? = null, val progress: Progress? = null,
)

class NavigationViewModel(private val placeSearch:PlaceSearch=RemotePlaceSearch(BuildConfig.MAP_SERVER_URL)) : ViewModel() {
    private val engine: NavigationEngine = RemoteNavigationEngine(BuildConfig.MAP_SERVER_URL)
    private val mutable = MutableStateFlow(NavigationState())
    val state = mutable.asStateFlow()
    private val links=MapLinkResolver()
    private var searchJob:Job?=null
    private var searchGeneration=0L
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
    fun setOrigin(text: String) { useNextFixAsOrigin=false;clearRoute(); mutable.update { it.copy(originText = text,originLabel="Selected start") } }
    fun setDestination(text: String) { clearRoute(); mutable.update { it.copy(destinationText = text,destinationLabel="Selected destination") } }
    fun selectPin(coordinate: Coordinate) {
        if (mutable.value.guiding || mutable.value.playback) return
        clearRoute()
        mutable.update { it.copy(destination = coordinate, destinationText = "${coordinate.latitude}, ${coordinate.longitude}",destinationLabel="Map pin",searchError=null) }
    }

    fun searchFrom(origin:Boolean) {
        searchJob?.cancel();searchGeneration++
        mutable.update{it.copy(searchOrigin=origin,searchQuery="",places=emptyList(),searchError=null,searching=false)}
    }
    fun setSearchQuery(query:String) {
        searchJob?.cancel();searchGeneration++
        mutable.update{it.copy(searchQuery=query.take(4096),places=emptyList(),searchError=null,searching=false)}
    }
    fun searchPlaces() {
        val query=mutable.value.searchQuery.trim()
        if(query.startsWith("geo:")||query.contains("https://")||query.contains("http://")) { acceptSharedText(query,mutable.value.searchOrigin);return }
        if(query.length !in 2..120) {mutable.update{it.copy(searchError="Enter a place name between 2 and 120 characters.")};return}
        searchJob?.cancel();val generation=++searchGeneration
        mutable.update{it.copy(searching=true,searchError=null,places=emptyList())}
        searchJob=viewModelScope.launch {
            try {
                val results=placeSearch.search(query)
                if(generation==searchGeneration)mutable.update{it.copy(places=results,searching=false,searchError=if(results.isEmpty())"No matching name in the Kenya map data. Try a nearby landmark or choose a map pin." else null)}
            } catch(error:CancellationException){throw error}
            catch(_:Exception){if(generation==searchGeneration)mutable.update{it.copy(searching=false,searchError="Place search is unavailable. Retry when connected, or choose a map pin.")}}
        }
    }
    fun selectPlace(place:Place) {
        if(mutable.value.guiding||mutable.value.playback) return
        searchJob?.cancel();searchGeneration++;if(mutable.value.searchOrigin)useNextFixAsOrigin=false;clearRoute()
        mutable.update { current ->
            if(current.searchOrigin)current.copy(originText="${place.coordinate.latitude}, ${place.coordinate.longitude}",originLabel=place.name,places=emptyList(),searchQuery="",searching=false,searchError=null,focusRequest=current.focusRequest+1)
            else current.copy(destination=place.coordinate,destinationText="${place.coordinate.latitude}, ${place.coordinate.longitude}",destinationLabel=place.name,places=emptyList(),searchQuery="",searching=false,searchError=null,focusRequest=current.focusRequest+1)
        }
    }
    fun rejectSharedLocation() {mutable.update{it.copy(error="Stop guidance, recording and sharing before opening another destination.")}}
    fun acceptSharedText(text:String,origin:Boolean=false) {
        if(mutable.value.guiding||mutable.value.playback){rejectSharedLocation();return}
        searchJob?.cancel();val generation=++searchGeneration
        mutable.update{it.copy(searching=true,searchError=null,plannerRequest=it.plannerRequest+1)}
        searchJob=viewModelScope.launch {
            try {
                when(val result=links.resolve(text)) {
                    is MapLink.Pin -> if(generation==searchGeneration){
                        if(origin)useNextFixAsOrigin=false
                        clearRoute()
                        mutable.update {
                            if(origin)it.copy(originText="${result.coordinate.latitude}, ${result.coordinate.longitude}",originLabel=result.label,searching=false,searchOrigin=true,searchQuery="",places=emptyList(),focusRequest=it.focusRequest+1)
                            else it.copy(destination=result.coordinate,destinationText="${result.coordinate.latitude}, ${result.coordinate.longitude}",destinationLabel=result.label,searching=false,searchOrigin=false,searchQuery="",places=emptyList(),focusRequest=it.focusRequest+1)
                        }
                    }
                    is MapLink.Search -> if(generation==searchGeneration){mutable.update{it.copy(searchQuery=result.query,searchOrigin=origin,searching=false)};searchPlaces()}
                    else -> if(generation==searchGeneration)mutable.update{it.copy(searching=false,searchError="This link does not expose a usable location. Share a full location link, search its place name, or choose a map pin.")}
                }
            }catch(error:CancellationException){throw error}
            catch(_:Exception){if(generation==searchGeneration)mutable.update{it.copy(searching=false,searchError="Could not open that map link. Check your connection, or search its place name.")}}
        }
    }
    fun previewRoute() {
        routeJob?.cancel(); stopGuidance()
        val input = mutable.value
        val origin = parseCoordinate(input.originText)
        val destination = parseCoordinate(input.destinationText)
        if (origin == null || destination == null) {
            mutable.update { it.copy(error = "Choose a start and destination by place search, your location, or a map pin.") }; return
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
            mutable.update { it.copy(originText = "${fix.coordinate.latitude}, ${fix.coordinate.longitude}",originLabel="My location") }
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
