package org.projectdrive.app.feature.convoy

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import org.projectdrive.app.core.journey.*
import org.projectdrive.app.core.navigation.Coordinate
import org.projectdrive.app.core.location.LocationFix

@HiltViewModel
class ConvoyViewModel @Inject constructor(private val session:ConvoySession,private val runtime:JourneyRuntime):ViewModel() {
    val state get()=session.state
    fun resume()=session.resume()
    fun importSession(bytes:ByteArray)=session.importSession(bytes)
    fun create(name:String,origin:Coordinate,destination:Coordinate)=session.create(name,origin,destination)
    fun join(token:String)=session.join(token)
    fun open(id:String)=session.open(id)
    fun refresh()=session.refresh()
    fun lifecycle(operation:String)=session.lifecycle(operation)
    fun invite()=session.invite()
    fun revokeInvite()=session.revokeInvite()
    fun remove(id:String)=session.remove(id)
    fun closeTrip()=session.closeTrip()
    fun startSharing()=runtime.start(JourneyAction.SHARE)
    fun stopSharing(message:String="Sharing off")=session.stopSharing(message)
    fun onFix(fix:LocationFix,permissionGranted:Boolean,playback:Boolean)=session.onFix(fix,permissionGranted,playback)
}
