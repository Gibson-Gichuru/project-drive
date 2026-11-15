package org.projectdrive.app.ui

import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import org.projectdrive.app.R
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.projectdrive.app.core.car.CarConnectionState
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
    useLocation:()->Unit, locateMap:()->Unit, stopLocation:()->Unit, startRecording:()->Unit, finishRecording:()->Unit, startGuidance:()->Unit, startSharing:()->Unit, importSession:()->Unit,
) {
    val carConnection by telemetry.carConnection.collectAsState()
    var focusedRequest by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(state.focusRequest) {
        if(state.focusRequest>0 && focusedRequest!=state.focusRequest) {
            val coordinate=if(state.searchOrigin) NavigationViewModel.parseCoordinate(state.originText) else state.destination
            coordinate?.let(renderer::focus)
            focusedRequest=state.focusRequest
        }
    }
    SideEffect {
        renderer.render(state.route,state.destination,state.simulatedPosition?:state.fix?.coordinate,state.playback,
            state.playback||state.guiding||(state.gpsWanted&&state.route==null),
            group.participants.filter{it.id!=group.userId},group.now,NavigationViewModel.parseCoordinate(state.originText),
            if(state.playback)null else state.fix?.accuracyMeters)
    }
    DriveContent(
        state=state, exploring=exploring, recording=recording.recording, sharing=group.sharing,carConnection=carConnection,
        recenter={if(state.route==null&&!state.gpsWanted&&!state.playback)locateMap() else renderer.recenter()}, stopGuidance=model::stopGuidance, finishRecording=finishRecording,
        stopSharing={convoy.stopSharing()},
        mapContent={ modifier -> AndroidView(factory={renderer.view},modifier=modifier) },
        navigationContent={ backToMap -> NavigationPanel(state,model,recording.recording,useLocation,stopLocation,startGuidance,backToMap) },
        convoyContent={ ConvoyPanel(group,convoy,NavigationViewModel.parseCoordinate(state.originText),NavigationViewModel.parseCoordinate(state.destinationText),locationEnabled,state.playback,importSession,startSharing) },
        telemetryContent={ TelemetryPanel(recording,telemetry,group.trip,state.playback,startRecording,finishRecording,carConnection) },
    )
}

/** Map canvas with a compact journey card; tools remain in a dismissible sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveContent(
    state:NavigationState, exploring:Boolean, recording:Boolean, sharing:Boolean,
    recenter:()->Unit, stopGuidance:()->Unit, finishRecording:()->Unit, stopSharing:()->Unit,
    mapContent:@Composable (Modifier)->Unit,
    navigationContent:@Composable ColumnScope.(backToMap:()->Unit)->Unit,
    convoyContent:@Composable ColumnScope.()->Unit,
    telemetryContent:@Composable ColumnScope.()->Unit,
    carConnection:CarConnectionState=CarConnectionState.Checking,
    modifier:Modifier=Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(DriveTab.Navigation) }
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    val panelState=rememberSaveableStateHolder()
    val landscape=LocalConfiguration.current.orientation==Configuration.ORIENTATION_LANDSCAPE
    var openedRequest by rememberSaveable { mutableStateOf(0L) }
    fun openTools(selected:DriveTab) { tab=selected;toolsOpen=true }
    LaunchedEffect(state.plannerRequest) {
        if(state.plannerRequest>0 && openedRequest!=state.plannerRequest) {
            openTools(DriveTab.Navigation);openedRequest=state.plannerRequest
        }
    }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        mapContent(Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal))
                .padding(16.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                MapAction(DriveSymbol.Back,"Navigation",{openTools(DriveTab.Navigation)})
                MapAction(DriveSymbol.More,"More tools",{toolsOpen=true})
            }
            if(carConnection==CarConnectionState.AndroidAuto||carConnection==CarConnectionState.Automotive) StatusNote(carConnection.label,active=true)
            if(state.loading) Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface) {
                Column(Modifier.widthIn(max=360.dp).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Finding your route…",style=MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            state.error?.let { message -> Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.errorContainer) {
                Text(message,Modifier.widthIn(max=360.dp).padding(12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onErrorContainer)
            } }
            if(recording || sharing) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(recording) Button(onClick=finishRecording,colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error,contentColor=MaterialTheme.colorScheme.onError),modifier=Modifier.weight(1f,fill=false).heightIn(min=48.dp)) { Text("Finish recording") }
                if(sharing) Button(onClick=stopSharing,colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error,contentColor=MaterialTheme.colorScheme.onError),modifier=Modifier.weight(1f,fill=false).heightIn(min=48.dp)) { Text("Stop sharing now") }
            }
        }
        Column(
            Modifier.align(if(landscape)Alignment.BottomStart else Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom+WindowInsetsSides.Horizontal))
                .padding(horizontal=16.dp,vertical=8.dp)
                .widthIn(max=if(landscape)400.dp else 560.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp),
        ) {
            if(!landscape) Column(Modifier.align(Alignment.End),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                MapAction(DriveSymbol.Locate,"Recenter",recenter,highlighted=exploring)
                MapAction(DriveSymbol.Convoy,"Convoy",{openTools(DriveTab.Convoy)})
            }
            JourneyDock(state,exploring,recording,sharing,stopGuidance,
                navigation={openTools(DriveTab.Navigation)},locate=recenter,journal={openTools(DriveTab.Telemetry)},more={toolsOpen=true})
            Surface(modifier=Modifier.padding(end=40.dp),shape=RoundedCornerShape(6.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.92f)) {
                Text("© OpenStreetMap contributors · © OpenMapTiles",Modifier.padding(horizontal=6.dp,vertical=2.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(landscape) Column(
            Modifier.align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom+WindowInsetsSides.Horizontal))
                .padding(end=16.dp,bottom=36.dp),
            verticalArrangement=Arrangement.spacedBy(10.dp),
        ) {
            MapAction(DriveSymbol.Locate,"Recenter",recenter,highlighted=exploring)
            MapAction(DriveSymbol.Convoy,"Convoy",{openTools(DriveTab.Convoy)})
        }
    }
    if(toolsOpen) ModalBottomSheet(onDismissRequest={toolsOpen=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
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
private fun MapAction(symbol:DriveSymbol,label:String,onClick:()->Unit,modifier:Modifier=Modifier,highlighted:Boolean=false) {
    Surface(onClick=onClick,modifier=modifier.size(48.dp),shape=CircleShape,
        color=if(highlighted)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        shadowElevation=4.dp) {
        Box(contentAlignment=Alignment.Center) {
            DriveIcon(symbol,description=label,modifier=Modifier.size(23.dp),tint=if(highlighted)MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun JourneyDock(
    state:NavigationState,exploring:Boolean,recording:Boolean,sharing:Boolean,stopGuidance:()->Unit,
    navigation:()->Unit,locate:()->Unit,journal:()->Unit,more:()->Unit,modifier:Modifier=Modifier,
) {
    val active=state.guiding||state.playback
    val compact=LocalConfiguration.current.orientation==Configuration.ORIENTATION_LANDSCAPE
    val title=when {
        state.playback -> "Simulated preview"
        state.guiding -> state.progress?.let { if(it.arrived)"You have arrived" else if(it.distanceToRouteMeters>60)"Off route · stop safely to replan" else it.maneuver.instruction } ?: "Waiting for location"
        state.route!=null -> state.destinationLabel.ifBlank{"Your route is ready"}
        recording -> "Your drive is recording"
        state.gpsWanted&&state.fix==null -> "Finding your location"
        state.gpsWanted -> "Ready when you are"
        else -> "Your next adventure"
    }
    val subtitle=when {
        exploring&&active -> "Exploring · Recenter to follow"
        state.route!=null -> String.format(Locale.ROOT,"%.1f km · %d min full drive",(state.progress?.remainingMeters?:state.route.distanceMeters)/1000,ceil(state.route.durationSeconds/60).toInt())
        recording -> "Journal saved on this phone"
        sharing -> "Live sharing on"
        state.gpsWanted -> state.locationStatus
        else -> "Enable location to see your area."
    }
    Surface(modifier=modifier.fillMaxWidth(),shape=RoundedCornerShape(28.dp),color=MaterialTheme.colorScheme.surface,shadowElevation=8.dp) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(50.dp)) {
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center) {
                        DriveIcon(DriveSymbol.Convoy,modifier=Modifier.size(27.dp))
                    }
                    if(recording||sharing) Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary))
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(title,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                    if(!compact&&!state.gpsWanted&&!recording&&!sharing) Text("Location off · explore freely",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(active) TextButton(onClick=stopGuidance,contentPadding=PaddingValues(horizontal=4.dp)) { Text("Stop",color=MaterialTheme.colorScheme.error) }
                if(compact) MapAction(DriveSymbol.Compass,"Route details",navigation)
            }
            if(!compact) Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                val needsLocation=state.route==null&&!state.gpsWanted&&!recording
                Button(onClick=if(needsLocation)locate else navigation,modifier=Modifier.weight(1f).heightIn(min=48.dp),shape=CircleShape,
                    contentPadding=PaddingValues(horizontal=12.dp,vertical=10.dp),
                    colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.surfaceVariant,contentColor=MaterialTheme.colorScheme.onSurface)) {
                    DriveIcon(if(needsLocation)DriveSymbol.Locate else DriveSymbol.Compass,modifier=Modifier.size(20.dp));Spacer(Modifier.width(8.dp))
                    Text(if(needsLocation)"Use my location" else if(state.route!=null)"Route details" else "Plan a drive",maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                MapAction(DriveSymbol.Journal,"Journal",journal,highlighted=recording)
                MapAction(DriveSymbol.More,"More tools",more)
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
private fun NavigationPanel(state:NavigationState,model:NavigationViewModel,recording:Boolean,useLocation:()->Unit,stopLocation:()->Unit,startGuidance:()->Unit,backToMap:()->Unit) {
    val route=state.route
    val active=state.guiding||state.playback
    if(route==null) {
        SectionHeading("THE OPEN ROAD", "Go somewhere good.",DriveSymbol.Compass)
        Text("Find a place, open a shared location, or hold the map to choose a destination.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
        RoutePlaces(state,model)
        Button(onClick={model.previewRoute();backToMap()},enabled=!state.loading&&state.originText.isNotBlank()&&state.destination!=null,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) { Text("Preview route");Spacer(Modifier.width(10.dp));DriveIcon(DriveSymbol.Arrow,tint=MaterialTheme.colorScheme.onPrimary) }
    }else if(active) {
        Button(onClick=model::stopGuidance,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)) { DriveIcon(DriveSymbol.Pause,tint=MaterialTheme.colorScheme.onError);Spacer(Modifier.width(8.dp));Text("Stop guidance") }
        Text(if(state.playback) "Simulated route playback · no live location is shared." else "Explore the map freely. Tap Recenter to follow your position again.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }else {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick={startGuidance();backToMap()},modifier=Modifier.weight(1f).heightIn(min=52.dp)) { Text("Start guidance") }
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


@Composable
private fun RoutePlaces(state:NavigationState,model:NavigationViewModel) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick={model.searchFrom(true)},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {
            Text("From",Modifier.width(52.dp),style=MaterialTheme.typography.labelMedium)
            Text(state.originLabel.ifBlank{"Choose a start"},Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
            DriveIcon(DriveSymbol.Edit,tint=MaterialTheme.colorScheme.primary)
        }
        OutlinedButton(onClick={model.searchFrom(false)},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {
            Text("To",Modifier.width(52.dp),style=MaterialTheme.typography.labelMedium)
            Text(state.destinationLabel.ifBlank{"Where to?"},Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
            DriveIcon(DriveSymbol.Edit,tint=MaterialTheme.colorScheme.primary)
        }
        Text(if(state.searchOrigin)"Find your starting point" else "Find your destination",style=MaterialTheme.typography.titleSmall)
        OutlinedTextField(state.searchQuery,model::setSearchQuery,label={Text("Place name or map link")},placeholder={Text("e.g. Nakuru, a landmark, or a shared link")},
            singleLine=true,modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={model.searchPlaces()}))
        Button(onClick=model::searchPlaces,enabled=!state.searching&&state.searchQuery.isNotBlank(),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
            Text(if(state.searching)"Finding places…" else "Search places / open link")
        }
        state.searchError?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
        state.places.forEach { place ->
            Surface(onClick={model.selectPlace(place)},shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(place.name,style=MaterialTheme.typography.titleSmall)
                    Text("${place.kind.replace('_',' ')} · ${place.locality}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("Place search covers named towns, roads and landmarks in the Kenya OSM snapshot. Check the selected pin before starting.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
