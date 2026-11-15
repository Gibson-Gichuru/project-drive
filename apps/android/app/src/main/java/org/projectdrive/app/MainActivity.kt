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
import org.projectdrive.app.core.location.AndroidLocationSource
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
    private lateinit var location: AndroidLocationSource
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (telemetryPermission) {
            telemetryPermission=false
            if(grants.values.any { it }) telemetry.start() else telemetry.permissionDenied()
        } else if (grants.values.any { it }) { enableMapLocation(locationForOrigin) }
        else model.locationDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        telemetryPermission=savedInstanceState?.getBoolean("drive.permission.telemetry")?:false
        locationForOrigin=savedInstanceState?.getBoolean("drive.permission.origin")?:false
        enableEdgeToEdge()
        model = ViewModelProvider(this)[NavigationViewModel::class.java]
        telemetry = ViewModelProvider(this)[org.projectdrive.app.feature.telemetry.TelemetryViewModel::class.java]
        convoy = ViewModelProvider(this)[org.projectdrive.app.feature.convoy.ConvoyViewModel::class.java]
        renderer = MapLibreRenderer(this, savedInstanceState, BuildConfig.MAP_SERVER_URL, model::selectPin) { mapExploring = it }
        mapExploring = renderer.exploring
        if(savedInstanceState==null)openSharedLocation(intent)
        location = AndroidLocationSource(this, { fix ->
            model.onLocation(fix)
            if(!model.state.value.playback)telemetry.onFix(fix)
            if (model.state.value.gpsWanted && model.state.value.fix === fix) convoy.onFix(fix, hasLocationPermission(), model.state.value.playback)
        }, { model.locationUnavailable(); telemetry.locationUnavailable(); convoy.stopSharing("Sharing off · location unavailable") })
        setContent {
            org.projectdrive.app.ui.DriveTheme {
                val state by model.state.collectAsState()
                val group by convoy.state.collectAsState()
                val recording by telemetry.state.collectAsState()
                LaunchedEffect(recording.recording, state.gpsWanted) {
                    if((recording.recording || state.gpsWanted) && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && hasLocationPermission()) location.start()
                    else if(!recording.recording && !state.gpsWanted) location.stop()
                }
                DisposableEffect(state.guiding, state.playback, recording.recording) {
                    val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    if (state.guiding || state.playback || recording.recording) window.addFlags(flag) else window.clearFlags(flag)
                    onDispose { window.clearFlags(flag) }
                }
                var showLocationDisclosure by remember { mutableStateOf(false) }
                org.projectdrive.app.ui.DriveScreen(
                    state, model, group, convoy, recording, telemetry, renderer, mapExploring, locationEnabled = state.gpsWanted && hasLocationPermission(),
                    useLocation = { locationForOrigin=true;showLocationDisclosure=true },
                    locateMap = { locationForOrigin=false;showLocationDisclosure=true },
                    stopLocation = {
                        convoy.stopSharing("Sharing off · location turned off")
                        telemetry.finish("Recording stopped when location was turned off", true)
                        mapLocationPreference.enabled=false
                        location.stop(); model.stopLocation()
                    },
                    startRecording = {
                        if (hasLocationPermission()) telemetry.start()
                        else { telemetryPermission = true; permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
                    },
                    finishRecording = { telemetry.finish(); if (!state.gpsWanted) location.stop() },
                    importSession = { sessionImport.launch("application/json") },
                )
                if (showLocationDisclosure) AlertDialog(
                    onDismissRequest = { showLocationDisclosure = false },
                    title = { Text("Use your location?") },
                    text = { Text("Your location centers the map while the app is visible. With permission, this resumes when you reopen the app; Turn location off remembers your choice. It does not start recording, sharing or guidance. Choosing Use my location in the planner also selects your route start. Routing sends the chosen endpoints to your self-hosted server. You can explore the map without location access.") },
                    confirmButton = { TextButton(onClick = {
                        showLocationDisclosure = false
                        if (hasLocationPermission()) enableMapLocation(locationForOrigin)
                        else { telemetryPermission=false;permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
                    }) { Text("Continue") } },
                    dismissButton = { TextButton(onClick = { showLocationDisclosure = false }) { Text("Keep location off") } },
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
        location.start()
    }
    override fun onStart() { super.onStart(); renderer.onStart() }
    override fun onResume() {
        super.onResume();telemetry.resume();convoy.resume();renderer.onResume()
        if(hasLocationPermission()) {
            if(!model.state.value.gpsWanted && mapLocationPreference.enabled) {
                model.requestLocation(useAsOrigin=false)
                // A fresh map session starts at the user, while rotation retains
                // an existing session's intentional manual exploration.
                renderer.recenter()
            }
            if(model.state.value.gpsWanted) { model.resumeForeground();location.start() }
        } else if(model.state.value.gpsWanted) model.locationDenied()
    }
    override fun onPause() { telemetry.pause(); convoy.pause(); location.stop(); renderer.onPause(); super.onPause() }
    override fun onStop() { location.stop(); if (!isChangingConfigurations) model.pauseForeground(); renderer.onStop(); super.onStop() }
    override fun onDestroy() { location.stop(); renderer.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); renderer.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState);renderer.saveState(outState)
        outState.putBoolean("drive.permission.telemetry",telemetryPermission)
        outState.putBoolean("drive.permission.origin",locationForOrigin)
    }
}
