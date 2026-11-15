package org.projectdrive.app.core.journey

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import org.json.JSONArray
import org.json.JSONObject
import org.projectdrive.app.BuildConfig
import org.projectdrive.app.core.location.LocationFix

/** Bounded aggregate test evidence. No coordinates, network names or device IDs. */
class FieldDiagnostics(private val context:Context) {
    private val started=SystemClock.elapsedRealtime()
    private var last=started
    private var backgroundMillis=0L
    private var screenOffMillis=0L
    private var chargingMillis=0L
    private var lastFix:Long?=null
    private var maxGap=0L
    private var speedQualitySamples=0
    private var maxBatteryTemperature:Double?=null
    private var maxThermalStatus=0
    private var powerSaveSeen=false
    private val networks=mutableSetOf<String>()
    private fun battery()=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    private fun percent(intent:Intent?):Int? {
        val level=intent?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)?:-1
        val scale=intent?.getIntExtra(BatteryManager.EXTRA_SCALE,-1)?:-1
        return if(level>=0&&scale>0)(level*100/scale).coerceIn(0,100) else null
    }
    private val initialBattery=percent(battery())
    private var interactive=context.getSystemService(PowerManager::class.java).isInteractive
    private var charging=(battery()?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)?:0)!=0
    fun sample(now:Long,visible:Boolean) {
        val dt=(now-last).coerceAtLeast(0);last=now
        if(!visible)backgroundMillis+=dt
        if(!interactive)screenOffMillis+=dt
        if(charging)chargingMillis+=dt
        interactive=context.getSystemService(PowerManager::class.java).isInteractive
        charging=(battery()?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)?:0)!=0
        battery()?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,Int.MIN_VALUE)?.takeIf{it!=Int.MIN_VALUE}?.let { value ->
            maxBatteryTemperature=maxOf(maxBatteryTemperature?:Double.NEGATIVE_INFINITY,value/10.0)
        }
        val power=context.getSystemService(PowerManager::class.java)
        powerSaveSeen=powerSaveSeen||power.isPowerSaveMode
        if(Build.VERSION.SDK_INT>=29)maxThermalStatus=maxOf(maxThermalStatus,power.currentThermalStatus)
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val caps=manager.getNetworkCapabilities(manager.activeNetwork)
        networks+=when {
            caps==null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }
    fun fix(fix:LocationFix) {
        lastFix?.let {maxGap=maxOf(maxGap,fix.elapsedRealtimeMillis-it)}
        lastFix=fix.elapsedRealtimeMillis
        if(fix.isFresh(SystemClock.elapsedRealtime())&&fix.accuracyMeters>0&&fix.accuracyMeters<=25&&fix.speedMps?.let {it.isFinite()&&it in 0f..100f}==true&&fix.speedAccuracyMps?.let {it>0&&it<=2}==true)speedQualitySamples++
    }
    fun json(now:Long,visible:Boolean,sensors:Set<String>):JSONObject {
        sample(now,visible)
        lastFix?.let {maxGap=maxOf(maxGap,now-it)}
        return JSONObject().put("version",1).put("app_version",BuildConfig.VERSION_NAME).put("build_type",BuildConfig.BUILD_TYPE)
            .put("android_api",Build.VERSION.SDK_INT).put("device_model",Build.MODEL)
            .put("battery_start_percent",initialBattery?:JSONObject.NULL).put("battery_end_percent",percent(battery())?:JSONObject.NULL)
            .put("background_s",backgroundMillis/1000.0).put("screen_off_s",screenOffMillis/1000.0).put("charging_s",chargingMillis/1000.0)
            .put("max_battery_temperature_c",maxBatteryTemperature?:JSONObject.NULL).put("max_thermal_status",maxThermalStatus).put("power_save_seen",powerSaveSeen)
            .put("sampled_device_state_interval_s",1).put("max_gps_callback_gap_s",maxGap/1000.0)
            .put("event_quality_speed_samples",speedQualitySamples).put("network_types",JSONArray(networks.sorted()))
            .put("sensors",JSONArray(sensors.sorted())).put("fine_permission",context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)
            .put("interruption_policy","checkpoint recovery; no automatic restart")
    }
}
