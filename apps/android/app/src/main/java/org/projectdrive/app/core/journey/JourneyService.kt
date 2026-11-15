package org.projectdrive.app.core.journey

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*
import org.projectdrive.app.MainActivity
import org.projectdrive.app.R
import org.projectdrive.app.core.telemetry.MAX_RECORDING_MILLIS

/** Explicit, visible, non-sticky location session. No boot receiver or silent restart. */
@AndroidEntryPoint
class JourneyService:Service() {
    @Inject lateinit var runtime:JourneyRuntime
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var observer:Job?=null
    private var wakeLock:PowerManager.WakeLock?=null
    private var stopping=false
    override fun onCreate() {
        super.onCreate()
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Active journeys",NotificationManager.IMPORTANCE_LOW))
        try {
            val notification=notification("Preparing journey")
            if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIFICATION,notification)
            wakeLock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Outset:journey").apply {
                setReferenceCounted(false);acquire(MAX_RECORDING_MILLIS+10_000)
            }
            runtime.attached()
            scope.launch {
                val deadline=SystemClock.elapsedRealtime()+MAX_RECORDING_MILLIS
                while(SystemClock.elapsedRealtime()<deadline&&!stopping) {
                    delay(2_000)
                    if(!runtime.notificationsAllowed()) {stopping=true;runtime.stopAll("Journey notifications disabled",true).join();stopSelf();return@launch}
                }
                if(!stopping) {stopping=true;runtime.stopAll("Four-hour journey limit reached").join();stopSelf()}
            }
        } catch(_:SecurityException) {runtime.unavailable("Journey permissions changed · open the app and retry");stopSelf()}
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(!runtime.serviceRunning.value){stopSelf();return START_NOT_STICKY}
        if(stopping){runtime.unavailable("Finishing the previous journey · retry once it has saved");return START_NOT_STICKY}
        if(intent?.action==STOP) {
            stopping=true
            scope.launch {runtime.stopAll().join();stopSelf()}
        }else {
            val action=JourneyAction.entries.firstOrNull {it.name==intent?.action}
            if(action==null){stopSelf();return START_NOT_STICKY}
            runtime.execute(action)
            if(observer==null)observer=scope.launch {
                runtime.status.collect {
                    val status=runtime.currentStatus()
                    if(!stopping) {
                        if(status.active) {
                            if(!runtime.notificationsAllowed()){stopping=true;runtime.stopAll("Journey notifications disabled",true).join();stopSelf()}
                            else getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification(status.label))
                        }else stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(label:String):Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,JourneyService::class.java).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_outset_notification)
            .setContentTitle("Outset journey active").setContentText(label).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,android.R.drawable.ic_menu_close_clear_cancel),"Stop journey",stop).build())
            .apply {if(Build.VERSION.SDK_INT>=31)setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)}.build()
    }
    override fun onBind(intent:Intent?)=null
    override fun onDestroy() {
        observer?.cancel();scope.cancel();runtime.detached()
        wakeLock?.let {if(it.isHeld)it.release()};wakeLock=null
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
    companion object {
        const val CHANNEL="outset_journey"
        const val NOTIFICATION=41
        const val STOP="org.projectdrive.app.STOP_JOURNEY"
    }
}
