package org.projectdrive.app.core.journey

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton
import org.projectdrive.app.NavigationSession
import org.projectdrive.app.core.location.AndroidLocationSource
import org.projectdrive.app.feature.telemetry.TelemetryRecorder
import org.projectdrive.app.feature.convoy.ConvoySession

enum class JourneyAction { GUIDANCE, RECORD, SHARE }
data class JourneyStatus(val guidance:Boolean=false,val recording:Boolean=false,val sharing:Boolean=false,val busy:Boolean=false) {
    val active get()=guidance||recording||sharing||busy
    val label get()=listOfNotNull(if(guidance)"Navigation" else null,if(recording)"Local recording" else null,if(sharing)"Convoy sharing" else null).joinToString(" · ").ifBlank{"Preparing or saving journey"}
}

/** One process-owned session; Activity replacement never owns active collection. */
@Singleton
class JourneyRuntime @Inject constructor(
    @param:ApplicationContext private val context:Context,
    private val navigation:NavigationSession,private val telemetry:TelemetryRecorder,private val convoy:ConvoySession,
    private val carConnection:org.projectdrive.app.core.car.CarConnectionMonitor,
) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val visible=MutableStateFlow(false)
    private val running=MutableStateFlow(false)
    val serviceRunning=running.asStateFlow()
    private var starting=false
    private var sharingRequested=false
    private var listening=false
    private var activities=0
    fun activityCreated() {activities++}
    fun activityDestroyed(changing:Boolean) {
        activities=(activities-1).coerceAtLeast(0)
        if(!changing&&activities==0&&!running.value&&!starting)navigation.resetIdle()
    }
    val status=combine(navigation.state,telemetry.state,convoy.state) { nav,record,group ->
        JourneyStatus(nav.guiding,record.recording,group.sharing,
            record.starting||(record.saving&&record.current!=null)||(sharingRequested&&group.busy))
    }.stateIn(scope,SharingStarted.Eagerly,JourneyStatus())
    internal fun currentStatus():JourneyStatus {
        val nav=navigation.state.value;val record=telemetry.state.value;val group=convoy.state.value
        return JourneyStatus(nav.guiding,record.recording,group.sharing,record.starting||(record.saving&&record.current!=null)||(sharingRequested&&group.busy))
    }
    private val location=AndroidLocationSource(context,{ fix ->
        navigation.onLocation(fix)
        if(!navigation.state.value.playback)telemetry.onFix(fix)
        if(navigation.state.value.gpsWanted&&navigation.state.value.fix===fix)
            convoy.onFix(fix,hasLocationPermission(),navigation.state.value.playback)
    },{
        navigation.locationUnavailable();telemetry.locationUnavailable()
        convoy.stopSharing("Sharing off · location unavailable")
        if(!hasLocationPermission()) { navigation.locationDenied();stopAll("Location permission unavailable",true) }
    })
    init {
        scope.launch { combine(status,navigation.state,visible,running) { _,_,_,_ -> Unit }.collect { syncLocation() } }
    }
    fun hasLocationPermission()=listOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
        .any {context.checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED}
    fun notificationsAllowed():Boolean {
        val manager=context.getSystemService(NotificationManager::class.java)
        return (Build.VERSION.SDK_INT<33||context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)&&
            manager.areNotificationsEnabled()&&manager.getNotificationChannel(JourneyService.CHANNEL)?.importance!=NotificationManager.IMPORTANCE_NONE
    }
    fun unavailable(message:String) { navigation.reportError(message);telemetry.unavailable(message);convoy.unavailable(message) }
    fun start(action:JourneyAction) {
        if(action!=JourneyAction.GUIDANCE&&navigation.state.value.playback) {unavailable("Stop simulated preview before recording or sharing");return}
        if(!visible.value||!hasLocationPermission()) { unavailable("Open the app and enable location before starting a journey");return }
        if(!notificationsAllowed()) { unavailable("Allow Outset notifications to use the visible journey controls");return }
        starting=true
        try { context.startForegroundService(Intent(context,JourneyService::class.java).setAction(action.name)) }
        catch(_:IllegalStateException) { starting=false;unavailable("Could not start the journey service · reopen the app and retry") }
        catch(_:SecurityException) { starting=false;unavailable("Journey service permission unavailable · check location and notifications") }
    }
    internal fun attached() { running.value=true;telemetry.resume();convoy.resume();syncLocation() }
    internal fun execute(action:JourneyAction) {
        starting=false
        when(action) {
            JourneyAction.GUIDANCE -> navigation.startGuidance()
            JourneyAction.RECORD -> telemetry.start()
            JourneyAction.SHARE -> { sharingRequested=true;convoy.startSharing() }
        }
        syncLocation()
    }
    internal fun detached() {
        running.value=false;starting=false;sharingRequested=false
        if(currentStatus().active)stopAll("Journey service interrupted",true)
        if(!visible.value) {telemetry.pause();convoy.pause();navigation.pauseForeground()}
        if(activities==0)navigation.resetIdle()
        syncLocation()
    }
    fun foreground(value:Boolean) {
        visible.value=value
        telemetry.setVisible(value)
        if(value||running.value||starting) {telemetry.resume();convoy.resume()}
        else {telemetry.pause();convoy.pause();navigation.pauseForeground()}
        if(!value&&navigation.state.value.playback)navigation.stopGuidance()
        syncLocation()
    }
    private fun syncLocation() {
        carConnection.setMonitoring(visible.value||running.value)
        val active=navigation.state.value.guiding||telemetry.state.value.recording||telemetry.state.value.starting||convoy.state.value.sharing
        val wanted=hasLocationPermission()&&((visible.value&&navigation.state.value.gpsWanted)||(running.value&&active))
        if(wanted&&!listening){listening=true;location.start()}
        else if(!wanted&&listening){listening=false;location.stop();if(!visible.value)navigation.pauseForeground()}
    }
    fun stopLocation() { navigation.stopLocation();stopAll("Journey stopped when location was turned off",true);syncLocation() }
    fun stopAll(reason:String="Journey stopped",interrupted:Boolean=false):Job {
        navigation.stopGuidance();sharingRequested=false
        val recording=telemetry.finish(reason,interrupted);val sharing=convoy.stopSharing("Sharing off · $reason")
        return scope.launch { withTimeoutOrNull(10_000) { recording?.join();sharing.join() };syncLocation() }
    }
}
