package org.projectdrive.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.projectdrive.app.core.journey.JourneyRuntime
import org.projectdrive.app.core.journey.JourneyAction
import org.projectdrive.app.core.location.MapLocationPreference
import org.projectdrive.app.map.MapLibreRenderer

@dagger.hilt.android.AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private lateinit var model: NavigationViewModel
    private lateinit var telemetry: org.projectdrive.app.feature.telemetry.TelemetryViewModel
    private var telemetryPermission=false
    private var locationForOrigin=false
    private val mapLocationPreference by lazy { MapLocationPreference(this) }
    private lateinit var convoy: org.projectdrive.app.feature.convoy.ConvoyViewModel
    private lateinit var account: org.projectdrive.app.feature.convoy.AuthViewModel
    private var sessionImport = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            lifecycleScope.launch {
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        contentResolver.openInputStream(it)?.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(1024)
                            while (output.size() <= 8192) {
                                val count = input.read(buffer, 0, minOf(buffer.size, 8193 - output.size()))
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                    }.getOrNull()
                }
                if (bytes != null && bytes.size <= 8192) convoy.importSession(bytes)
            }
        }
    }
    private var mapExploring by mutableStateOf(false)
    private lateinit var renderer: MapLibreRenderer
    @javax.inject.Inject lateinit var runtime:JourneyRuntime
    private var pendingJourney:JourneyAction?=null
    private val notifications=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) dispatchPendingJourney()
        else { pendingJourney=null;runtime.unavailable("Allow Outset notifications in Android settings before starting a background journey") }
    }
    private fun beginJourney(action:JourneyAction) {
        pendingJourney=action
        if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else dispatchPendingJourney()
    }
    private fun dispatchPendingJourney() {
        if(!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))return
        val action=pendingJourney?:return
        pendingJourney=null;runtime.start(action)
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (telemetryPermission) {
            telemetryPermission=false
            if(grants.values.any { it }) beginJourney(JourneyAction.RECORD) else telemetry.permissionDenied()
        } else if (grants.values.any { it }) { enableMapLocation(locationForOrigin) }
        else model.locationDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime.activityCreated()
        telemetryPermission=savedInstanceState?.getBoolean("drive.permission.telemetry")?:false
        locationForOrigin=savedInstanceState?.getBoolean("drive.permission.origin")?:false
        pendingJourney=savedInstanceState?.getString("drive.permission.journey")?.let {name->JourneyAction.entries.firstOrNull {it.name==name}}
        enableEdgeToEdge()
        model = ViewModelProvider(this)[NavigationViewModel::class.java]
        telemetry = ViewModelProvider(this)[org.projectdrive.app.feature.telemetry.TelemetryViewModel::class.java]
        convoy = ViewModelProvider(this)[org.projectdrive.app.feature.convoy.ConvoyViewModel::class.java]
        account = ViewModelProvider(this)[org.projectdrive.app.feature.convoy.AuthViewModel::class.java]
        renderer = MapLibreRenderer(this, savedInstanceState, BuildConfig.MAP_SERVER_URL, model::selectPin) { mapExploring = it }
        mapExploring = renderer.exploring
        if(savedInstanceState==null || intent.dataString?.let(org.projectdrive.app.core.auth.GoogleCallback::matches)==true)openSharedLocation(intent)
        setContent {
            org.projectdrive.app.ui.DriveTheme {
                val state by model.state.collectAsState()
                val group by convoy.state.collectAsState()
                val recording by telemetry.state.collectAsState()
                DisposableEffect(state.guiding, state.playback, recording.recording) {
                    val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    if (state.guiding || state.playback || recording.recording) window.addFlags(flag) else window.clearFlags(flag)
                    onDispose { window.clearFlags(flag) }
                }
                var showLocationDisclosure by remember { mutableStateOf(false) }
                org.projectdrive.app.ui.DriveScreen(
                    state, model, group, convoy, recording, telemetry, renderer, mapExploring, account=account, locationEnabled = state.gpsWanted && hasLocationPermission(),
                    useLocation = { locationForOrigin=true;showLocationDisclosure=true },
                    locateMap = { locationForOrigin=false;showLocationDisclosure=true },
                    stopLocation = {
                        mapLocationPreference.enabled=false
                        model.stopLocation()
                    },
                    startRecording = {
                        if (hasLocationPermission()) beginJourney(JourneyAction.RECORD)
                        else { telemetryPermission = true; permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
                    },
                    finishRecording = { telemetry.finish() },
                    startGuidance = { beginJourney(JourneyAction.GUIDANCE) },
                    startSharing = { beginJourney(JourneyAction.SHARE) },
                    importSession = { sessionImport.launch("application/json") },
                )
                if (showLocationDisclosure) AlertDialog(
                    onDismissRequest = { showLocationDisclosure = false },
                    title = { Text("Use your location?") },
                    text = { Text("Your location centers the map while the app is visible. With permission, this resumes when you reopen the app; Turn location off remembers your choice. Starting guidance, recording or sharing keeps that journey running with the screen off, with an ongoing notification and Stop action. Map browsing alone does not start a background journey. Choosing Use my location in the planner also selects your route start. Routing sends the chosen endpoints to your self-hosted server. You can explore the map without location access.") },
                    confirmButton = { TextButton(onClick = {
                        showLocationDisclosure = false
                        if (hasLocationPermission()) enableMapLocation(locationForOrigin)
                        else { telemetryPermission=false;permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
                    }) { Text("Continue") } },
                    dismissButton = { TextButton(onClick = { showLocationDisclosure = false }) { Text(if(state.gpsWanted)"Not now" else "Keep location off") } },
                )
            }
        }
    }

    override fun onNewIntent(intent:android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openSharedLocation(intent)
    }
    private fun openSharedLocation(incoming:android.content.Intent) {
        if(incoming.action==android.content.Intent.ACTION_VIEW && incoming.dataString?.let(org.projectdrive.app.core.auth.GoogleCallback::matches)==true) {
            account.callback(incoming.dataString!!)
            incoming.data=null // Keep callback codes out of retained Activity intents.
            return
        }
        val text=when(incoming.action) {
            android.content.Intent.ACTION_VIEW -> incoming.dataString
            android.content.Intent.ACTION_SEND -> incoming.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT)?.toString()
            else -> null
        }?:return
        if(telemetry.state.value.recording||convoy.state.value.sharing)model.rejectSharedLocation()
        else model.acceptSharedText(text)
    }

    private fun hasLocationPermission() = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun enableMapLocation(useAsOrigin:Boolean) {
        mapLocationPreference.enabled=true
        model.requestLocation(useAsOrigin)

    }
    override fun onStart() { super.onStart(); renderer.onStart() }
    override fun onResume() {
        super.onResume();runtime.foreground(true);renderer.onResume()
        if(hasLocationPermission()) {
            if(!model.state.value.gpsWanted && mapLocationPreference.enabled) {
                model.requestLocation(useAsOrigin=false)
                // A fresh map session starts at the user, while rotation retains
                // an existing session's intentional manual exploration.
                renderer.recenter()
            }
            if(model.state.value.gpsWanted) model.resumeForeground()
        } else if(model.state.value.gpsWanted) model.locationDenied()
        dispatchPendingJourney()
    }
    override fun onPause() { runtime.foreground(false);renderer.onPause();super.onPause() }
    override fun onStop() { renderer.onStop();super.onStop() }
    override fun onDestroy() {runtime.activityDestroyed(isChangingConfigurations);renderer.onDestroy();super.onDestroy()}
    override fun onLowMemory() { super.onLowMemory(); renderer.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState);renderer.saveState(outState)
        outState.putBoolean("drive.permission.telemetry",telemetryPermission)
        outState.putBoolean("drive.permission.origin",locationForOrigin)
        outState.putString("drive.permission.journey",pendingJourney?.name)
    }
}
