package org.projectdrive.app.feature.telemetry

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import org.projectdrive.app.core.journey.*
import org.projectdrive.app.core.location.LocationFix
import org.projectdrive.app.core.convoy.Trip
import org.projectdrive.app.core.telemetry.LocalRecording

@HiltViewModel
class TelemetryViewModel @Inject constructor(private val recorder:TelemetryRecorder,private val runtime:JourneyRuntime,carConnection:org.projectdrive.app.core.car.CarConnectionMonitor):ViewModel() {
    val state get()=recorder.state
    val carConnection=carConnection.state
    fun resume()=recorder.resume()
    fun start()=runtime.start(JourneyAction.RECORD)
    fun onFix(fix:LocationFix)=recorder.onFix(fix)
    fun permissionDenied()=recorder.permissionDenied()
    fun locationUnavailable()=recorder.locationUnavailable()
    fun finish(reason:String="Recording finished",interrupted:Boolean=false)=recorder.finish(reason,interrupted)
    fun delete(recording:LocalRecording)=recorder.delete(recording)
    fun upload(recording:LocalRecording,trip:Trip)=recorder.upload(recording,trip)
    fun remote(trip:Trip)=recorder.remote(trip)
}
