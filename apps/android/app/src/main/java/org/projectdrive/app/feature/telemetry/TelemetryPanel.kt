package org.projectdrive.app.feature.telemetry

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.projectdrive.app.ui.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.projectdrive.app.core.convoy.Trip
import org.projectdrive.app.core.telemetry.*
import org.json.JSONObject
import java.util.Locale
import java.time.Instant

@Composable
fun TelemetryPanel(state: TelemetryState,model: TelemetryViewModel,trip: Trip?,playback: Boolean,start: ()->Unit,stop: ()->Unit) {
    var disclosure by remember { mutableStateOf(false) }
    var upload by remember { mutableStateOf<LocalRecording?>(null) }
    var delete by remember { mutableStateOf<LocalRecording?>(null) }
    SectionHeading("YOUR JOURNEY JOURNAL","Trip telemetry",DriveSymbol.Journal)
    Text("A little story of your drive.",style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
    StatusNote(state.message,active=state.recording)
    if(!state.recording && state.recordings.isEmpty()) JourneyCard(modifier=Modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.primaryContainer) {
        DriveIcon(DriveSymbol.Road,modifier=Modifier.size(32.dp),tint=MaterialTheme.colorScheme.primary)
        Text("Every kilometre has a story.",style=MaterialTheme.typography.titleLarge)
        Text("Save a local journey summary, with distance, time and estimated speed. Nothing is shared or uploaded automatically.",style=MaterialTheme.typography.bodyMedium)
    }
    Text("Estimates and candidate events only · not calibrated driving measurements",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.saving || state.uploading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (state.recording) {
        Button(onClick=stop,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text("Finish recording")}
        Text("Sensors: ${state.sensors.ifEmpty { setOf("none available · GPS only") }.joinToString()}",style=MaterialTheme.typography.bodySmall)
        state.current?.let { SummaryCard(it) }
    }else{
        Button(onClick={disclosure=true},enabled=!state.saving && !state.uploading && !playback,modifier=Modifier.fillMaxWidth()){Text("Start local recording")}
        Text("Works without an account or internet. Keep the app visible. Local summaries expire after seven days; at most 20 are kept.",style=MaterialTheme.typography.bodySmall)
        if(playback) Text("Stop preview playback before recording. Simulated route positions are never recorded.")
    }
    if(trip!=null) OutlinedButton(onClick={model.remote(trip)},enabled=!state.uploading && !state.recording){Text("Load my server summaries")}
    state.remoteCount?.let { Text("Your retained server recordings: $it") }
    state.recordings.forEach { recording ->
        val summary=remember(recording.summaryJSON){summaryFromJSON(JSONObject(recording.summaryJSON))}
        JourneyCard {
        Text(Instant.ofEpochMilli(summary.startedAtMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm")),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.secondary)
        if(summary.interrupted) Text("Interrupted · saved portion only",color=MaterialTheme.colorScheme.error)
        SummaryCard(summary)
        Text(if(recording.uploaded) "Uploaded · detailed local events cleared" else if(recording.uploadDeadlineMillis<=System.currentTimeMillis()) "Upload window expired · detailed events cleared" else "Local only · up to 100 derived events · upload within 24 hours",style=MaterialTheme.typography.bodySmall)
        if(trip!=null && trip.status in setOf("active","completed") && !recording.uploaded && recording.uploadDeadlineMillis>System.currentTimeMillis())
            OutlinedButton(onClick={upload=recording},enabled=!state.recording && !state.uploading && !state.saving){Text("Upload to ${trip.name}")}
        TextButton(onClick={delete=recording},enabled=!state.recording && !state.uploading && !state.saving){Text("Delete local summary")}
        }
    }
    if(disclosure) AlertDialog(onDismissRequest={disclosure=false},title={Text("Record this journey locally?")},text={Text("While this screen is visible, Project Drive reads GPS speed/position and supported motion sensors. Raw samples stay briefly in memory. Only estimates, quality metadata and up to 100 candidate events are saved locally for seven days. Nothing is uploaded or shared automatically. Leaving the app or rotating stops recording. Operate controls while parked or as a passenger.")},confirmButton={TextButton(onClick={disclosure=false;start()}){Text("Record locally")}},dismissButton={TextButton(onClick={disclosure=false}){Text("Keep recording off")}})
    upload?.let { recording -> AlertDialog(onDismissRequest={upload=null},title={Text("Upload your derived telemetry?")},text={Text("Send this summary, timestamps, quality metadata and candidate events to the selected private trip. No GPS path or raw sensor samples are sent. Only your authenticated account can read this telemetry through the API. Server retention is seven days; this action is separate from live convoy sharing.")},confirmButton={TextButton(onClick={upload=null;if(trip!=null)model.upload(recording,trip)}){Text("Upload estimates")}},dismissButton={TextButton(onClick={upload=null}){Text("Keep local")}}) }
    delete?.let { recording -> AlertDialog(onDismissRequest={delete=null},title={Text("Delete local summary?")},text={Text("This removes the saved summary and local event details. An already uploaded copy expires separately on the server after seven days.")},confirmButton={TextButton(onClick={delete=null;model.delete(recording)}){Text("Delete locally")}},dismissButton={TextButton(onClick={delete=null}){Text("Keep summary")}}) }
}
@Composable
private fun SummaryCard(s: TelemetrySummary) {
    var quality by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
        JourneyMetric(String.format(Locale.ROOT,"%.1f km",s.distanceMeters/1000),"Distance",Modifier.weight(1f))
        JourneyMetric(String.format(Locale.ROOT,"%.0f min",s.durationSeconds/60),"Time recorded",Modifier.weight(1f))
    }
    if(s.speedSamples>0) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
        JourneyMetric(String.format(Locale.ROOT,"%.1f km/h",s.meanSpeedMps*3.6),"Mean estimated speed",Modifier.weight(1f))
        JourneyMetric(String.format(Locale.ROOT,"%.1f km/h",s.maxSpeedMps*3.6),"Max estimated speed",Modifier.weight(1f))
    }else Text("Speed unavailable · no reliable speed estimates",style=MaterialTheme.typography.bodyMedium)
    Text("Candidate signals · uncalibrated",style=MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        JourneyMetric(s.acceleration.toString(),"Acceleration",Modifier.weight(1f))
        JourneyMetric(s.braking.toString(),"Braking",Modifier.weight(1f))
        JourneyMetric(s.lateral.toString(),"Lateral",Modifier.weight(1f))
    }
    TextButton(onClick={quality=!quality},contentPadding=PaddingValues(0.dp)) { Text(if(quality) "Hide recording quality" else "Recording quality");Spacer(Modifier.width(6.dp));DriveIcon(DriveSymbol.Chevron,modifier=Modifier.size(18.dp),tint=MaterialTheme.colorScheme.primary) }
    if(quality) {
        Text(String.format(Locale.ROOT,"GPS coverage %.0f s / %.0f s · mean reported accuracy ±%.0f m · %d rejected fixes",s.coveredSeconds,s.durationSeconds,s.meanAccuracyMeters,s.rejectedGPS),style=MaterialTheme.typography.bodySmall)
        Text("GPS ${s.gpsSamples} · accelerometer ${s.imuSamples} · rotation ${s.rotationSamples} · orientation ${s.orientation} · uncalibrated",style=MaterialTheme.typography.bodySmall)
    }
    if(s.droppedEvents>0)Text("${s.droppedEvents} candidate details omitted at the buffer limit; counts retained",style=MaterialTheme.typography.bodySmall)
}
