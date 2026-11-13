package org.projectdrive.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale
import org.projectdrive.app.core.location.AndroidLocationSource
import org.projectdrive.app.map.MapLibreRenderer

@dagger.hilt.android.AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private lateinit var model: NavigationViewModel
    private lateinit var telemetry: org.projectdrive.app.feature.telemetry.TelemetryViewModel
    private var telemetryPermission=false
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
    private lateinit var renderer: MapLibreRenderer
    private lateinit var location: AndroidLocationSource
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (telemetryPermission) {
            telemetryPermission=false
            if(grants.values.any { it }) telemetry.start() else telemetry.permissionDenied()
        } else if (grants.values.any { it }) { model.requestLocation(); location.start() }
        else model.locationDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[NavigationViewModel::class.java]
        telemetry = ViewModelProvider(this)[org.projectdrive.app.feature.telemetry.TelemetryViewModel::class.java]
        convoy = ViewModelProvider(this)[org.projectdrive.app.feature.convoy.ConvoyViewModel::class.java]
        renderer = MapLibreRenderer(this, savedInstanceState, BuildConfig.MAP_SERVER_URL, model::selectPin)
        location = AndroidLocationSource(this, { fix ->
            model.onLocation(fix)
            if(!model.state.value.playback)telemetry.onFix(fix)
            if (model.state.value.gpsWanted && model.state.value.fix === fix) convoy.onFix(fix, hasLocationPermission(), model.state.value.playback)
        }, { model.locationUnavailable(); telemetry.locationUnavailable(); convoy.stopSharing("Sharing off · location unavailable") })
        setContent {
            MaterialTheme {
                val state by model.state.collectAsState()
                val group by convoy.state.collectAsState()
                val recording by telemetry.state.collectAsState()
                var tab by remember { mutableStateOf("Navigation") }
                LaunchedEffect(recording.recording) {
                    if(recording.recording && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && hasLocationPermission()) location.start()
                    else if(!state.gpsWanted) location.stop()
                }
                DisposableEffect(state.guiding, state.playback, recording.recording) {
                    val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    if (state.guiding || state.playback || recording.recording) window.addFlags(flag) else window.clearFlags(flag)
                    onDispose { window.clearFlags(flag) }
                }
                var showLocationDisclosure by remember { mutableStateOf(false) }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("Project Drive", style = MaterialTheme.typography.titleLarge)
                            Text(if (state.playback) "PREVIEW PLAYBACK · simulated position" else "Explore Kenya", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(if(recording.recording && !state.gpsWanted) "Local telemetry recording · location in use" else state.locationStatus, style = MaterialTheme.typography.bodySmall)
                            if(recording.recording) Button(onClick={telemetry.finish();if(!state.gpsWanted)location.stop()},modifier=Modifier.fillMaxWidth()){Text("Finish recording")}
                            if (group.sharing) Button(onClick = { convoy.stopSharing() }, modifier = Modifier.fillMaxWidth()) { Text("Stop sharing now") }
                            Row {
                                TextButton(onClick = { tab = "Navigation" }) { Text("Navigation") }
                                TextButton(onClick = { tab = "Convoy" }) { Text("Convoy") }
                                TextButton(onClick={tab="Telemetry"}){Text("Telemetry")}
                            }
                        }
                        AndroidView(factory = { renderer.view }, modifier = Modifier.fillMaxWidth().weight(1f), update = {
                            renderer.render(state.route, state.destination, state.simulatedPosition ?: state.fix?.coordinate, state.playback, state.playback || (state.guiding && state.progress != null), group.participants.filter { it.id != group.userId }, group.now)
                        })
                        Column(Modifier.fillMaxWidth().heightIn(max = 330.dp).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if(tab=="Telemetry") {
                                org.projectdrive.app.feature.telemetry.TelemetryPanel(recording,telemetry,group.trip,state.playback,{
                                    if(hasLocationPermission())telemetry.start() else {telemetryPermission=true;permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))}
                                },{telemetry.finish();if(!state.gpsWanted)location.stop()})
                            } else if (tab=="Convoy") {
                                org.projectdrive.app.feature.convoy.ConvoyPanel(group, convoy, NavigationViewModel.parseCoordinate(state.originText), NavigationViewModel.parseCoordinate(state.destinationText), state.gpsWanted && hasLocationPermission(), state.playback) { sessionImport.launch("application/json") }
                            } else {
                                if (state.route == null && !state.guiding && !state.playback) {
                                    OutlinedTextField(state.originText, model::setOrigin, label = { Text("From · latitude, longitude") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(state.destinationText, model::setDestination, label = { Text("To · latitude, longitude") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    Text("Long-press the map to choose a destination.", style = MaterialTheme.typography.bodySmall)
                                }
                                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                state.route?.let { route ->
                                    Text(String.format(Locale.ROOT, "%.1f km · about %d min", route.distanceMeters / 1000, kotlin.math.ceil(route.durationSeconds / 60).toInt()), style = MaterialTheme.typography.titleMedium)
                                    val progress = state.progress
                                    if (progress != null) {
                                        Text(when {
                                            progress.arrived -> if (state.playback) "Preview complete" else "You have arrived"
                                            progress.distanceToRouteMeters > 60 -> "Off route · stop safely before choosing a new route"
                                            else -> progress.maneuver.instruction
                                        }, style = MaterialTheme.typography.titleMedium)
                                        Text(String.format(Locale.ROOT, "%.1f km remaining", progress.remainingMeters / 1000), style = MaterialTheme.typography.bodySmall)
                                    } else if (state.guiding) Text("Waiting for a fresh, accurate location")
                                    else Text(route.maneuvers.first().instruction, style = MaterialTheme.typography.bodyMedium)
                                }
                                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                                if (state.guiding || state.playback) {
                                    Button(onClick = model::stopGuidance, modifier = Modifier.fillMaxWidth()) { Text("Stop guidance") }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = model::previewRoute, enabled = !state.loading, modifier = Modifier.weight(1f)) { Text("Preview route") }
                                        OutlinedButton(onClick = { showLocationDisclosure = true }, modifier = Modifier.weight(1f)) { Text("Use my location") }
                                    }
                                    if (state.route != null) TextButton(onClick = model::editRoute) { Text("Change route") }
                                    if (state.route != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = model::startGuidance, modifier = Modifier.weight(1f)) { Text("Start guidance") }
                                        OutlinedButton(onClick = model::playRoute, enabled = !recording.recording, modifier = Modifier.weight(1f)) { Text("Play preview") }
                                    }
                                }
                                if (state.gpsWanted) TextButton(onClick = { convoy.stopSharing("Sharing off · location turned off"); telemetry.finish("Recording stopped when location was turned off",true); location.stop(); model.stopLocation() }) { Text("Turn location off") }
                            }
                            Text("© OpenStreetMap contributors · © OpenMapTiles", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (showLocationDisclosure) AlertDialog(
                    onDismissRequest = { showLocationDisclosure = false },
                    title = { Text("Use your location?") },
                    text = { Text("Your location is used while this screen is open for route origin and guidance. It is not stored as a live track. Convoy sharing is off unless you explicitly enable it separately. Preview works without location access. Routing sends the chosen origin and destination to your self-hosted server.") },
                    confirmButton = { TextButton(onClick = {
                        showLocationDisclosure = false
                        if (hasLocationPermission()) { model.requestLocation(); location.start() }
                        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }) { Text("Continue") } },
                    dismissButton = { TextButton(onClick = { showLocationDisclosure = false }) { Text("Keep location off") } },
                )
            }
        }
    }

    private fun hasLocationPermission() = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    override fun onStart() { super.onStart(); renderer.onStart() }
    override fun onResume() { super.onResume(); telemetry.resume(); convoy.resume(); renderer.onResume(); if (model.state.value.gpsWanted && hasLocationPermission()) { model.resumeForeground(); location.start() } }
    override fun onPause() { telemetry.pause(); convoy.pause(); location.stop(); renderer.onPause(); super.onPause() }
    override fun onStop() { location.stop(); if (!isChangingConfigurations) model.pauseForeground(); renderer.onStop(); super.onStop() }
    override fun onDestroy() { location.stop(); renderer.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); renderer.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); renderer.saveState(outState) }
}
