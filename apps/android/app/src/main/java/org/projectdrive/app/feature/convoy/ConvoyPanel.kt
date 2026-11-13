package org.projectdrive.app.feature.convoy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.projectdrive.app.BuildConfig
import org.projectdrive.app.core.navigation.Coordinate
import kotlin.math.ceil

@Composable
fun ConvoyPanel(state: ConvoyState, model: ConvoyViewModel, origin: Coordinate?, destination: Coordinate?, locationEnabled: Boolean, playback: Boolean, importSession: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var joinCode by remember { mutableStateOf("") }
    var tripId by remember { mutableStateOf("") }
    var confirmShare by remember { mutableStateOf(false) }
    var confirmAction by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val trip = state.trip
    Text("Private convoy", style = MaterialTheme.typography.titleLarge)
    Text(state.message, style = MaterialTheme.typography.bodyMedium)
    if (state.pendingStop && !state.sharing) Text("Server cleanup pending · local publishing is off", color = MaterialTheme.colorScheme.error)
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (BuildConfig.DEBUG && !state.sharing) OutlinedButton(onClick = importSession, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Import local test session") }
    if (!state.signedIn) {
        Text("Convoys need an authenticated session. Phase 0 uses an isolated local test server; production sign-in is a later milestone.")
        return
    }
    if (trip == null) {
        Text("Set origin and destination in Navigation. Creating a trip stores its name and chosen endpoints on your server for its members. Manage convoys while parked or as a passenger.")
        OutlinedTextField(name, { name = it.take(120) }, label = { Text("Trip name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { if (origin != null && destination != null) model.create(name, origin, destination) }, enabled = !state.busy && name.isNotBlank() && origin != null && destination != null, modifier = Modifier.fillMaxWidth()) { Text("Create private trip") }
        OutlinedTextField(joinCode, { joinCode = it.take(100) }, label = { Text("Private invitation code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { model.join(joinCode.trim()); joinCode = "" }, enabled = !state.busy && joinCode.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Join trip") }
        OutlinedTextField(tripId, { tripId = it.take(36) }, label = { Text("Existing trip ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { model.open(tripId.trim()) }, enabled = !state.busy && tripId.length == 36, modifier = Modifier.fillMaxWidth()) { Text("Open existing trip") }
    } else {
        Text(trip.name, style = MaterialTheme.typography.titleMedium)
        Text("Trip ${trip.status} · ${if (trip.ownerId == state.userId) "owner" else "member"}")
        SelectionContainer { Text(trip.id, style = MaterialTheme.typography.bodySmall) }
        if (state.sharing) {
            if (state.participants.none { it.id == state.userId }) Text("Waiting for a publishable GPS fix · other members may still be visible", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { model.stopSharing() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Stop sharing now") }
        } else if (trip.status == "active") {
            Button(onClick = { confirmShare = true }, enabled = !state.busy && locationEnabled && !playback, modifier = Modifier.fillMaxWidth()) { Text("Share my live location") }
            if (!locationEnabled) Text("Enable location in Navigation first. Approximate fixes remain labelled; very broad fixes are not published.")
            if (playback) Text("Preview playback cannot be shared as a live location.")
        }
        TextButton(onClick = model::refresh, enabled = !state.busy) { Text("Refresh trip") }
        if (trip.ownerId == state.userId && trip.status in setOf("planned", "active")) {
            if (trip.status == "planned") Button(onClick = { model.lifecycle("start") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Start trip") }
            Button(onClick = model::invite, enabled = !state.busy && state.invitation == null, modifier = Modifier.fillMaxWidth()) { Text("Create 15-minute invitation") }
            state.invitation?.let { invite ->
                Text("Keep this code private · ${invite.expires}", style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text(invite.token) }
                OutlinedButton(onClick = model::revokeInvite, enabled = !state.busy) { Text("Revoke invitation") }
            }
            if (trip.status == "active") OutlinedButton(onClick = { confirmAction = "End trip for everyone?" to { model.lifecycle("end") } }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("End trip") }
            TextButton(onClick = { confirmAction = "Cancel trip for everyone?" to { model.lifecycle("cancel") } }, enabled = !state.busy) { Text("Cancel trip") }
        } else if (trip.ownerId != state.userId && trip.status in setOf("planned", "active")) {
            OutlinedButton(onClick = { confirmAction = "Leave this trip?" to { model.lifecycle("leave") } }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Leave trip") }
        }
        Text("Members · ${state.members.size}", style = MaterialTheme.typography.titleMedium)
        state.members.forEach { member ->
            Text("${member.name} · ${member.id.take(6)}${if (member.id == state.userId) " (you)" else ""} · ${if (member.owner) "owner" else "member"}")
            val marker = state.participants.find { it.id == member.id }
            Text(if (marker == null) "No current shared position" else "${if (marker.stale(state.now)) "STALE" else "Recent"} · ±${ceil(marker.accuracy).toInt()} m · ${marker.age(state.now) / 1000}s ago", style = MaterialTheme.typography.bodySmall)
            if (trip.ownerId == state.userId && !member.owner && trip.status in setOf("planned", "active")) TextButton(onClick = { confirmAction = "Remove ${member.name} from this trip?" to { model.remove(member.id) } }, enabled = !state.busy) { Text("Remove ${member.name}") }
        }
        state.participants.filter { marker -> state.members.none { it.id == marker.id } }.forEach { marker ->
            Text("Participant ${marker.id.take(6)} · ${if (marker.stale(state.now)) "STALE" else "Recent"} · ±${ceil(marker.accuracy).toInt()} m · ${marker.age(state.now) / 1000}s ago", style = MaterialTheme.typography.bodySmall)
        }
        Text("Green markers are recent; grey markers are stale. Last known positions expire automatically. Sharing stops when this app leaves the foreground.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = model::closeTrip, enabled = !state.busy) { Text("Close convoy view · stay a member") }
    }
    if (confirmShare) AlertDialog(
        onDismissRequest = { confirmShare = false }, title = { Text("Share with this convoy?") },
        text = { Text("While Project Drive is visible, your real GPS position, accuracy and timestamp go to this trip’s sharing members through your server. They are held in server memory for up to 30 seconds. Stop sharing or leave the app to stop publishing. Reopening the app never resumes sharing automatically.") },
        confirmButton = { TextButton(onClick = { confirmShare = false; model.startSharing() }) { Text("Enable sharing") } },
        dismissButton = { TextButton(onClick = { confirmShare = false }) { Text("Keep sharing off") } },
    )
    confirmAction?.let { (title, action) -> AlertDialog(onDismissRequest = { confirmAction = null }, title = { Text(title) }, text = { Text("This changes the private trip for its members.") },
        confirmButton = { TextButton(onClick = { confirmAction = null; action() }) { Text("Confirm") } }, dismissButton = { TextButton(onClick = { confirmAction = null }) { Text("Keep trip") } }) }
}
