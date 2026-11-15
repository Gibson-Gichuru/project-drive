package org.projectdrive.app

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import org.projectdrive.app.core.location.LocationFix
import org.projectdrive.app.core.navigation.*
import org.projectdrive.app.core.journey.*

@HiltViewModel
class NavigationViewModel @Inject constructor(private val session:NavigationSession, private val runtime:JourneyRuntime?) : ViewModel() {
    private var ownsSession=false
    constructor(search:PlaceSearch=RemotePlaceSearch(BuildConfig.MAP_SERVER_URL)):this(NavigationSession(search),null) { ownsSession=true }
    val state get()=session.state
    fun editRoute()=session.editRoute()
    fun setOrigin(text:String)=session.setOrigin(text)
    fun setDestination(text:String)=session.setDestination(text)
    fun selectPin(point:Coordinate)=session.selectPin(point)
    fun searchFrom(origin:Boolean)=session.searchFrom(origin)
    fun setSearchQuery(query:String)=session.setSearchQuery(query)
    fun searchPlaces()=session.searchPlaces()
    fun selectPlace(place:Place)=session.selectPlace(place)
    fun rejectSharedLocation()=session.rejectSharedLocation()
    fun acceptSharedText(text:String,origin:Boolean=false)=session.acceptSharedText(text,origin)
    fun previewRoute()=session.previewRoute()
    fun requestLocation(useAsOrigin:Boolean=true)=session.requestLocation(useAsOrigin)
    fun locationDenied()=session.locationDenied()
    fun locationUnavailable()=session.locationUnavailable()
    fun onLocation(fix:LocationFix)=session.onLocation(fix)
    fun startGuidance() { if(runtime!=null)runtime.start(JourneyAction.GUIDANCE) else session.startGuidance() }
    fun playRoute()=session.playRoute()
    fun stopGuidance()=session.stopGuidance()
    fun stopLocation() { if(runtime!=null)runtime.stopLocation() else session.stopLocation() }
    fun resumeForeground()=session.resumeForeground()
    fun pauseForeground()=session.pauseForeground()
    fun reportError(message:String)=session.reportError(message)
    override fun onCleared() { if(ownsSession)session.close();super.onCleared() }
    companion object { fun parseCoordinate(text:String)=NavigationSession.parseCoordinate(text) }
}
