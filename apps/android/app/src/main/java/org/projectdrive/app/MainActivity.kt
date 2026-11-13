package org.projectdrive.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import java.util.Locale
import org.projectdrive.app.core.location.AndroidLocationSource
import org.projectdrive.app.map.MapLibreRenderer

class MainActivity : ComponentActivity() {
    private lateinit var model: NavigationViewModel
    private lateinit var renderer: MapLibreRenderer
    private lateinit var location: AndroidLocationSource
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) { model.requestLocation(); location.start() }
        else model.locationDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[NavigationViewModel::class.java]
        renderer = MapLibreRenderer(this, savedInstanceState, BuildConfig.MAP_SERVER_URL, model::selectPin)
        location = AndroidLocationSource(this, model::onLocation, model::locationUnavailable)
        setContent {
            MaterialTheme {
                val state by model.state.collectAsState()
                var showLocationDisclosure by remember { mutableStateOf(false) }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("Project Drive", style = MaterialTheme.typography.titleLarge)
                            Text(if (state.playback) "PREVIEW PLAYBACK · simulated position" else "Explore Kenya", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(state.locationStatus, style = MaterialTheme.typography.bodySmall)
                        }
                        AndroidView(factory = { renderer.view }, modifier = Modifier.fillMaxWidth().weight(1f), update = {
                            renderer.render(state.route, state.destination, state.simulatedPosition ?: state.fix?.coordinate, state.playback, state.playback || (state.guiding && state.progress != null))
                        })
                        Column(Modifier.fillMaxWidth().heightIn(max = 330.dp).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                    OutlinedButton(onClick = model::playRoute, modifier = Modifier.weight(1f)) { Text("Play preview") }
                                }
                            }
                            if (state.gpsWanted) TextButton(onClick = { location.stop(); model.stopLocation() }) { Text("Turn location off") }
                            Text("© OpenStreetMap contributors · © OpenMapTiles", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (showLocationDisclosure) AlertDialog(
                    onDismissRequest = { showLocationDisclosure = false },
                    title = { Text("Use your location?") },
                    text = { Text("Your location is used while this screen is open for route origin and guidance. It is not stored or shared live. Preview works without location access. Routing sends the chosen origin and destination to your self-hosted server.") },
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
    override fun onResume() { super.onResume(); renderer.onResume(); if (model.state.value.gpsWanted && hasLocationPermission()) { model.resumeForeground(); location.start() } }
    override fun onPause() { location.stop(); renderer.onPause(); super.onPause() }
    override fun onStop() { location.stop(); if (!isChangingConfigurations) model.pauseForeground(); renderer.onStop(); super.onStop() }
    override fun onDestroy() { location.stop(); renderer.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); renderer.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); renderer.saveState(outState) }
}
