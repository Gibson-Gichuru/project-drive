package org.projectdrive.app.ui


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.projectdrive.app.NavigationState
import org.projectdrive.app.NavigationViewModel
import org.projectdrive.app.feature.convoy.*
import org.projectdrive.app.feature.telemetry.*
import org.projectdrive.app.map.MapLibreRenderer
import java.util.Locale
import kotlin.math.ceil

private enum class DriveTab {
    Navigation, Convoy, Telemetry;
    val title get() = name
    val symbol get() = when(this) {
        Navigation -> DriveSymbol.Compass
        Convoy -> DriveSymbol.Convoy
        Telemetry -> DriveSymbol.Journal
    }
}

/** App wiring stays here; DriveContent owns only presentation state and slots. */
@Composable
fun DriveScreen(
    state:NavigationState, model:NavigationViewModel, group:ConvoyState, convoy:ConvoyViewModel,
    recording:TelemetryState, telemetry:TelemetryViewModel, renderer:MapLibreRenderer, exploring:Boolean, locationEnabled:Boolean,
    useLocation:()->Unit, stopLocation:()->Unit, startRecording:()->Unit, finishRecording:()->Unit, importSession:()->Unit,
) {
    SideEffect {
        renderer.render(state.route,state.destination,state.simulatedPosition?:state.fix?.coordinate,state.playback,state.playback||state.guiding,group.participants.filter{it.id!=group.userId},group.now)
    }
    DriveContent(
        state=state, exploring=exploring, recording=recording.recording, sharing=group.sharing,
        recenter=renderer::recenter, stopGuidance=model::stopGuidance, finishRecording=finishRecording,
        stopSharing={convoy.stopSharing()},
        mapContent={ modifier -> AndroidView(factory={renderer.view},modifier=modifier) },
        navigationContent={ backToMap -> NavigationPanel(state,model,recording.recording,useLocation,stopLocation,backToMap) },
        convoyContent={ ConvoyPanel(group,convoy,NavigationViewModel.parseCoordinate(state.originText),NavigationViewModel.parseCoordinate(state.destinationText),locationEnabled,state.playback,importSession) },
        telemetryContent={ TelemetryPanel(recording,telemetry,group.trip,state.playback,startRecording,finishRecording) },
    )
}

/** Full-screen map with a small tool dock. Detail controls appear only on request. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveContent(
    state:NavigationState, exploring:Boolean, recording:Boolean, sharing:Boolean,
    recenter:()->Unit, stopGuidance:()->Unit, finishRecording:()->Unit, stopSharing:()->Unit,
    mapContent:@Composable (Modifier)->Unit,
    navigationContent:@Composable ColumnScope.(backToMap:()->Unit)->Unit,
    convoyContent:@Composable ColumnScope.()->Unit,
    telemetryContent:@Composable ColumnScope.()->Unit,
    modifier:Modifier=Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(DriveTab.Navigation) }
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    val panelState=rememberSaveableStateHolder()
    val active=state.guiding||state.playback
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        mapContent(Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal)).padding(16.dp).widthIn(max=380.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.96f),shadowElevation=3.dp) {
                Row(Modifier.padding(horizontal=14.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    DriveIcon(DriveSymbol.Road,tint=MaterialTheme.colorScheme.primary)
                    Column {
                        Text("Project Drive",style=MaterialTheme.typography.titleMedium)
                        Text(if(state.playback)"Simulated preview" else if(state.guiding)"One turn at a time" else "Take the scenic way",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if(state.loading) StatusNote("Finding your route…",active=true)
            state.error?.let { message -> Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.errorContainer) { Text(message,Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onErrorContainer) } }
            StatusNote(if(recording && !state.gpsWanted) "Local recording · location in use" else state.locationStatus,active=state.gpsWanted||recording)
            if(recording) Button(onClick=finishRecording,colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error),modifier=Modifier.heightIn(min=48.dp)) { Text("Finish recording") }
            if(sharing) Button(onClick=stopSharing,colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error),modifier=Modifier.heightIn(min=48.dp)) { Text("Stop sharing now") }
        }
        Column(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom+WindowInsetsSides.Horizontal)).padding(horizontal=16.dp,vertical=10.dp).widthIn(max=560.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                Button(onClick=recenter,modifier=Modifier.heightIn(min=48.dp),colors=ButtonDefaults.buttonColors(containerColor=if(exploring)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,contentColor=if(exploring)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary),elevation=ButtonDefaults.buttonElevation(defaultElevation=3.dp)) {
                    DriveIcon(DriveSymbol.Locate,tint=if(exploring)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp));Text("Recenter")
                }
            }
            if(state.route!=null) Surface(onClick={tab=DriveTab.Navigation;toolsOpen=true},shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surface,shadowElevation=3.dp) {
                Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    DriveIcon(DriveSymbol.Road,tint=MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(if(active) state.progress?.let { if(it.arrived) if(state.playback)"Preview complete" else "You have arrived" else if(it.distanceToRouteMeters>60)"Off route · stop safely before choosing a new route" else it.maneuver.instruction } ?: "Waiting for location" else "Your route is ready",style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(if(exploring&&active)"Exploring · tap Recenter to follow" else String.format(Locale.ROOT,"%.1f km · %d min full drive",(state.progress?.remainingMeters?:state.route.distanceMeters)/1000,ceil(state.route.durationSeconds/60).toInt()),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(active) TextButton(onClick=stopGuidance) { Text("Stop",color=MaterialTheme.colorScheme.error) }
                    else DriveIcon(DriveSymbol.Chevron,tint=MaterialTheme.colorScheme.primary)
                }
            }
            Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface,shadowElevation=4.dp) {
                DriveTabs(tab,{tab=it;toolsOpen=true},Modifier.fillMaxWidth())
            }
            Surface(shape=RoundedCornerShape(8.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.94f)) {
                Text("© OpenStreetMap contributors · © OpenMapTiles",Modifier.padding(horizontal=8.dp,vertical=2.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if(toolsOpen) ModalBottomSheet(onDismissRequest={toolsOpen=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("Road-trip tools",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
            TextButton(onClick={toolsOpen=false}) { Text("Back to map") }
        }
        DriveTabs(tab,{tab=it},Modifier.fillMaxWidth())
        panelState.SaveableStateProvider(tab) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                when(tab) {
                    DriveTab.Navigation -> navigationContent { toolsOpen=false }
                    DriveTab.Convoy -> convoyContent()
                    DriveTab.Telemetry -> telemetryContent()
                }
            }
        }
    }
}

@Composable
private fun DriveTabs(selected:DriveTab,select:(DriveTab)->Unit,modifier:Modifier=Modifier) {
    Row(modifier.padding(6.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        DriveTab.entries.forEach { tab ->
            val active=tab==selected
            Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(if(active)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                .selectable(selected=active,role=Role.Tab,onClick={select(tab)}).heightIn(min=56.dp).padding(horizontal=4.dp,vertical=7.dp),
                horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(3.dp)) {
                DriveIcon(tab.symbol,modifier=Modifier.size(21.dp),tint=if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if(tab==DriveTab.Telemetry)"Journal" else tab.title,style=MaterialTheme.typography.labelMedium,color=if(active)MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NavigationPanel(state:NavigationState,model:NavigationViewModel,recording:Boolean,useLocation:()->Unit,stopLocation:()->Unit,backToMap:()->Unit) {
    var coordinates by rememberSaveable { mutableStateOf(false) }
    val route=state.route
    val active=state.guiding||state.playback
    if(route==null) {
        SectionHeading("THE OPEN ROAD", "Go somewhere good.",DriveSymbol.Compass)
        Text(if(state.destinationText=="-1.3152, 36.8148") "Try the Nairobi sample, or hold the map to choose your own destination." else "Destination selected. Preview the road ahead when you're ready.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }else {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Text(if(state.playback) "SIMULATED PREVIEW" else if(state.guiding) "ON THE ROAD" else "THE ROAD AHEAD",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.secondary)
            if(!active) TextButton(onClick=model::editRoute,contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)) { DriveIcon(DriveSymbol.Edit,modifier=Modifier.size(16.dp),tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(6.dp));Text("Change route") }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
            JourneyMetric(String.format(Locale.ROOT,"%.1f km",(state.progress?.remainingMeters?:route.distanceMeters)/1000),if(active)"Remaining distance" else "Road distance",Modifier.weight(1f))
            JourneyMetric("${ceil(route.durationSeconds/60).toInt()} min","Estimated full drive",Modifier.weight(1f))
        }
        val progress=state.progress
        val instruction=when {
            progress?.arrived==true -> if(state.playback) "Preview complete" else "You have arrived"
            progress!=null && progress.distanceToRouteMeters>60 -> "Off route · stop safely before choosing a new route"
            progress!=null -> progress.maneuver.instruction
            state.guiding -> "Waiting for a fresh, accurate location"
            else -> route.maneuvers.first().instruction
        }
        Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.primaryContainer) {
            Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                DriveIcon(DriveSymbol.Road,modifier=Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onPrimaryContainer)
                Text(instruction,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
    state.error?.let { error -> Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.errorContainer) { Text(error,Modifier.padding(12.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onErrorContainer) } }
    if(state.loading) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Finding your route…",style=MaterialTheme.typography.bodySmall)
    }
    if(route==null) {
        TextButton(onClick={coordinates=!coordinates},contentPadding=PaddingValues(horizontal=0.dp)) {
            DriveIcon(DriveSymbol.Edit,modifier=Modifier.size(18.dp),tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(8.dp));Text(if(coordinates) "Hide route coordinates" else "Edit route coordinates")
        }
        if(coordinates) {
            OutlinedTextField(state.originText,model::setOrigin,label={Text("From · latitude, longitude")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(state.destinationText,model::setDestination,label={Text("To · latitude, longitude")},singleLine=true,modifier=Modifier.fillMaxWidth())
        }
        Button(onClick={model.previewRoute();backToMap()},enabled=!state.loading,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) { Text("Preview route");Spacer(Modifier.width(10.dp));DriveIcon(DriveSymbol.Arrow,tint=MaterialTheme.colorScheme.onPrimary) }
    }else if(active) {
        Button(onClick=model::stopGuidance,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)) { DriveIcon(DriveSymbol.Pause,tint=MaterialTheme.colorScheme.onError);Spacer(Modifier.width(8.dp));Text("Stop guidance") }
        Text(if(state.playback) "Simulated route playback · no live location is shared." else "Explore the map freely. Tap Recenter to follow your position again.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }else {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick={model.startGuidance();if(model.state.value.guiding)backToMap()},modifier=Modifier.weight(1f).heightIn(min=52.dp)) { Text("Start guidance") }
            OutlinedButton(onClick={model.playRoute();backToMap()},enabled=!recording,modifier=Modifier.weight(1f).heightIn(min=52.dp)) { Text("Play preview") }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick=useLocation) { Text("Use my location") }
            TextButton(onClick={model.previewRoute();backToMap()},enabled=!state.loading) { Text("Preview route") }
        }
    }
    if(route==null || active) TextButton(onClick=useLocation) { DriveIcon(DriveSymbol.Locate,modifier=Modifier.size(18.dp),tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(8.dp));Text("Use my location") }
    if(state.gpsWanted) TextButton(onClick=stopLocation) { Text("Turn location off") }
}
