package org.projectdrive.app.feature.reports

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.projectdrive.app.core.reports.*
import org.projectdrive.app.ui.*
import kotlin.math.ceil

/** Plain content: immutable state and explicit callbacks, no location/account dependencies. */
@Composable
fun ReportsContent(state:ReportsUi,onEnable:()->Unit,onDisable:()->Unit,onRefresh:()->Unit,
    onChoosePin:()->Unit,onCategory:(ReportCategory)->Unit,onNote:(String)->Unit,onParked:(Boolean)->Unit,
    onPublish:()->Unit,onVote:(String,Int)->Unit,onWithdraw:(String)->Unit,
    onFocus:(RoadReport)->Unit,modifier:Modifier=Modifier) {
    var confirmWithdrawal by remember { mutableStateOf<String?>(null) }
    Column(modifier,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        SectionHeading("LOOKING OUT FOR EACH OTHER","Road ahead",DriveSymbol.Road)
        Text(state.message,style=MaterialTheme.typography.bodyMedium)
        if(!state.signedIn){Text("Sign in with Google in Convoy to read or publish community reports.");return@Column}
        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(!state.enabled)JourneyCard {
            Text("Know before you go",style=MaterialTheme.typography.titleLarge)
            Text("Showing nearby reports sends your current location to Outset’s server while this app is visible. The query is not saved as a journey or shared with convoy members. Reports you publish, including their pin and note, are visible to other signed-in drivers.")
            Button(onClick=onEnable,enabled=state.freshLocation&&!state.simulated&&!state.busy,modifier=Modifier.fillMaxWidth()){Text("Show nearby road reports")}
            if(!state.freshLocation)Text("Enable location from the map and wait for a fresh fix within 50 m accuracy.",style=MaterialTheme.typography.bodySmall)
        }else Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick=onRefresh,enabled=!state.busy&&!state.simulated){Text("Refresh reports")}
            TextButton(onClick=onDisable){Text("Turn reports off")}
        }
        if(state.moving||state.simulated)StatusNote(if(state.simulated)"Preview mode · reporting is unavailable" else "Reporting controls pause while moving. Stop safely to make changes.")
        val edit=!state.moving&&!state.simulated&&!state.busy
        JourneyCard {
            Text("Leave a helpful heads-up",style=MaterialTheme.typography.titleLarge)
            Text("Report an observation while parked or ask a passenger. Keep personal details and number plates out of your note.",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick=onChoosePin,enabled=edit,modifier=Modifier.fillMaxWidth()){Text(if(state.pin==null)"Choose report pin on map" else "Change report pin")}
            if(state.pin!=null) {
                Text("Pin selected on map",style=MaterialTheme.typography.labelMedium)
                ReportCategory.entries.chunked(2).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    row.forEach{category->FilterChip(selected=state.category==category,onClick={onCategory(category)},enabled=edit,label={Text(category.label)},modifier=Modifier.weight(1f))}
                }}
                OutlinedTextField(state.note,onNote,label={Text("Helpful note (optional)")},supportingText={Text("${state.note.length}/240 · observation expires in ${state.category.lifetime}")},enabled=edit,modifier=Modifier.fillMaxWidth(),maxLines=3)
                Row {Checkbox(state.parked,onParked,enabled=edit);Text("I’m parked or a passenger, and this pin marks what I observed.",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)}
                Button(onClick=onPublish,enabled=edit&&state.parked,modifier=Modifier.fillMaxWidth()){Text("Publish community report")}
            }
        }
        Text("Community observations can be incomplete or incorrect. Pins are not verified facts. Camera pins have no navigation warnings pending jurisdiction review.",style=MaterialTheme.typography.bodySmall)
        if(state.enabled&&state.reports.isEmpty())Text("No fresh reports nearby. This does not mean the road is clear.")
        state.reports.sortedBy{it.id!=state.selected}.forEach{report->JourneyCard {
            Text(report.category.label,style=MaterialTheme.typography.titleMedium)
            Text("Community · ${report.confidence} · ${ceil((state.now-report.observedAt).coerceAtLeast(0)/60000.0).toInt()} min ago",style=MaterialTheme.typography.labelMedium)
            if(report.note.isNotBlank())Text(report.note,style=MaterialTheme.typography.bodyMedium)
            Text("${report.confirmations} confirmations · ${report.dismissals} dismissals · expires in ${ceil((report.expiresAt-state.now).coerceAtLeast(0)/60000.0).toInt()} min",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={onFocus(report)}){Text("Show on map")}
            if(report.mine)TextButton(onClick={confirmWithdrawal=report.id},enabled=edit){Text("Withdraw my report")}
            else Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                FilterChip(selected=report.myVote==1,onClick={onVote(report.id,1)},enabled=edit,label={Text("Still there")})
                FilterChip(selected=report.myVote==-1,onClick={onVote(report.id,-1)},enabled=edit,label={Text("Not there")})
            }
        }}
    }
    confirmWithdrawal?.let{id->AlertDialog(onDismissRequest={confirmWithdrawal=null},title={Text("Withdraw your report?")},text={Text("It will leave the map and active warnings for everyone.")},confirmButton={TextButton(onClick={confirmWithdrawal=null;onWithdraw(id)}){Text("Withdraw")}},dismissButton={TextButton(onClick={confirmWithdrawal=null}){Text("Keep report")}})}
}
