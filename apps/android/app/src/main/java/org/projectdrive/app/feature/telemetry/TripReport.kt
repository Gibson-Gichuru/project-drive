package org.projectdrive.app.feature.telemetry

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.projectdrive.app.core.telemetry.*
import org.projectdrive.app.ui.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

private val ReportBackground=Color(0xFF101213)
private val ReportSurface=Color(0xFF202325)
private val ReportAccent=Color(0xFF67E0CD)
private val ReportWarm=Color(0xFFFFD7B3)

data class ReportEvidence(val batteryStart:Int?,val batteryEnd:Int?,val backgroundSeconds:Double,val screenOffSeconds:Double,val chargingSeconds:Double)
data class TripReportData(val summary:TelemetrySummary,val speedBuckets:List<SpeedBucket>,val historyRecorded:Boolean,val evidence:ReportEvidence?=null)

fun tripReportData(recording:LocalRecording):TripReportData {
    val json=JSONObject(recording.summaryJSON)
    val field=json.optJSONObject("field_test")
    fun percent(key:String)=field?.takeUnless{it.isNull(key)}?.optInt(key,-1)?.takeIf{it in 0..100}
    return TripReportData(summaryFromJSON(json),localSpeedBuckets(json),json.optJSONObject("local_report")?.optInt("version")==1,
        field?.let { ReportEvidence(percent("battery_start_percent"),percent("battery_end_percent"),it.optDouble("background_s",0.0),it.optDouble("screen_off_s",0.0),it.optDouble("charging_s",0.0)) })
}

internal fun reportDuration(seconds:Double):String {
    val whole=seconds.toLong().coerceAtLeast(0)
    return when {whole<60->"${whole}s";whole<3600->"${whole/60}m ${whole%60}s";else->"${whole/3600}h ${(whole%3600)/60}m"}
}
private fun atTime(millis:Long,pattern:String)=Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern,Locale.getDefault()))

/** Plain report boundary: no ViewModel, database, location or network dependency. */
@Composable
fun TripReportContent(report:TripReportData,onBack:()->Unit,modifier:Modifier=Modifier) {
    val s=report.summary
    var quality by rememberSaveable {mutableStateOf(false)}
    Column(modifier.fillMaxSize().background(ReportBackground).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            IconButton(onClick=onBack){DriveIcon(DriveSymbol.Back,"Back to journal",Modifier.size(24.dp))}
            Column(Modifier.weight(1f)) {
                Text("Trip details",style=MaterialTheme.typography.titleLarge,color=ReportWarm)
                Text("YOUR JOURNEY JOURNAL",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DriveIcon(DriveSymbol.Journal,modifier=Modifier.size(22.dp),tint=ReportAccent)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=18.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        ReportCard {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Surface(shape=RoundedCornerShape(16.dp),color=ReportAccent.copy(alpha=.13f)) {
                    Box(Modifier.padding(12.dp)){DriveIcon(DriveSymbol.Road,modifier=Modifier.size(26.dp),tint=ReportAccent)}
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(atTime(s.startedAtMillis,"EEEE, d MMMM"),style=MaterialTheme.typography.titleMedium)
                    Text(atTime(s.startedAtMillis,"yyyy"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(reportDuration(s.durationSeconds),style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.SemiBold)
            Text(String.format(Locale.ROOT,"%.2f km estimated distance",s.distanceMeters/1000),style=MaterialTheme.typography.bodyLarge,color=ReportAccent)
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
            TimelineRow("Recording started",atTime(s.startedAtMillis,"d MMM · HH:mm"),ReportAccent)
            TimelineRow(if(s.interrupted)"Last checkpoint" else "Recording finished",atTime(s.endedAtMillis,"d MMM · HH:mm"),MaterialTheme.colorScheme.onSurfaceVariant)
            if(s.interrupted)Text("Interrupted · saved portion only",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.error)
            Text("Saved on this phone · no location trail",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ReportCard {
            Text("Driving signals",style=MaterialTheme.typography.titleMedium)
            SignalRow("Rapid acceleration",s.acceleration,Color(0xFFFF9666))
            SignalRow("Hard braking",s.braking,Color(0xFFE687F1))
            SignalRow("Lateral motion",s.lateral,Color(0xFFB69CFF))
            Text("Candidates from GPS changes · uncalibrated estimates, not a driving score.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(s.droppedEvents>0)Text("${s.droppedEvents} event details omitted at the buffer limit; counts retained.",style=MaterialTheme.typography.bodySmall)
        }
        ReportCard {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text("Speed",style=MaterialTheme.typography.titleMedium)
                Text("km/h",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(s.speedSamples>0)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                SpeedMetric("Average",s.meanSpeedMps,ReportAccent,Modifier.weight(1f))
                SpeedMetric("Maximum",s.maxSpeedMps,ReportWarm,Modifier.weight(1f))
            }
            if(report.speedBuckets.isNotEmpty())SpeedChart(report.speedBuckets,s)
            else Surface(shape=RoundedCornerShape(18.dp),color=Color.White.copy(alpha=.035f)) {
                Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(if(report.historyRecorded)"No reliable speed history" else "Speed history wasn’t saved for this trip",style=MaterialTheme.typography.titleSmall)
                    Text(if(report.historyRecorded)"The recording did not contain enough usable speed estimates for a chart." else "New recordings include a local speed chart. Your original summary stays unchanged.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if(report.speedBuckets.isNotEmpty())Text("10-second averages and peaks · gaps mean no usable data. Estimates only.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            else if(s.speedSamples==0)Text("Speed unavailable · no reliable estimates",style=MaterialTheme.typography.bodySmall)
        }
        ReportCard {
            TextButton(onClick={quality=!quality},contentPadding=PaddingValues(0.dp)) {
                Text(if(quality)"Hide recording quality" else "Recording quality")
                Spacer(Modifier.width(8.dp));DriveIcon(DriveSymbol.Chevron,modifier=Modifier.size(18.dp))
            }
            if(quality) {
                Text(String.format(Locale.ROOT,"GPS time coverage · %.1f%%",if(s.durationSeconds>0)100*s.coveredSeconds/s.durationSeconds else 0.0),style=MaterialTheme.typography.bodyMedium)
                Text(String.format(Locale.ROOT,"Mean reported accuracy ±%.1f m · %d rejected fixes",s.meanAccuracyMeters,s.rejectedGPS),style=MaterialTheme.typography.bodySmall)
                Text("GPS ${s.gpsSamples} · accelerometer ${s.imuSamples} · rotation ${s.rotationSamples}",style=MaterialTheme.typography.bodySmall)
                Text("Orientation ${s.orientation} · uncalibrated",style=MaterialTheme.typography.bodySmall)
                report.evidence?.let { field ->
                    if(field.batteryStart!=null&&field.batteryEnd!=null)Text("Battery ${field.batteryStart}% → ${field.batteryEnd}%",style=MaterialTheme.typography.bodyMedium)
                    Text("Background ${reportDuration(field.backgroundSeconds)} · screen off ${reportDuration(field.screenOffSeconds)}",style=MaterialTheme.typography.bodySmall)
                    if(field.chargingSeconds>0)Text("Charging occurred · battery change is not a discharge-only measurement.",style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("Private by default. The local speed chart expires with this summary after seven days and is excluded from uploads.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(bottom=16.dp))
        }
    }
}

@Composable
private fun ReportCard(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(28.dp),color=ReportSurface) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp),content=content)
    }
}
@Composable
private fun TimelineRow(label:String,time:String,color:Color,modifier:Modifier=Modifier) {
    Row(modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(9.dp).background(color,CircleShape))
        Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(time,style=MaterialTheme.typography.bodyMedium)
            Text(label,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable
private fun SignalRow(label:String,count:Int,color:Color,modifier:Modifier=Modifier) {
    Row(modifier.fillMaxWidth().semantics(mergeDescendants=true){},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(11.dp).background(color,CircleShape))
        Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
        Text("$count",style=MaterialTheme.typography.titleMedium,color=color)
    }
}
@Composable
private fun SpeedMetric(label:String,mps:Double,color:Color,modifier:Modifier=Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(3.dp)) {
        Text(String.format(Locale.ROOT,"%.1f",mps*3.6),style=MaterialTheme.typography.headlineMedium,color=color)
        Text("$label estimate",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SpeedChart(buckets:List<SpeedBucket>,summary:TelemetrySummary,modifier:Modifier=Modifier) {
    val duration=summary.durationSeconds.coerceAtLeast(1.0)
    val ceiling=(ceil(buckets.maxOf{it.maxMps}*3.6/20)*20).coerceAtLeast(20.0)
    val grid=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f)
    val groups=remember(buckets) {
        buildList<List<SpeedBucket>> {
            var run=mutableListOf<SpeedBucket>()
            buckets.forEach { b -> if(run.isNotEmpty()&&b.startSeconds-run.last().startSeconds>10){add(run.toList());run=mutableListOf()};run.add(b) }
            if(run.isNotEmpty())add(run.toList())
        }
    }
    Column(modifier.semantics(mergeDescendants=true){contentDescription="Speed history chart, ${buckets.size} measured ten-second intervals. Average and peak speed in kilometres per hour; missing intervals are gaps."},verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Column(Modifier.height(180.dp),verticalArrangement=Arrangement.SpaceBetween) {
                listOf(ceiling,ceiling/2,0.0).forEach{Text("${it.toInt()}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
            Canvas(Modifier.weight(1f).height(180.dp)) {
                for(i in 0..4)drawLine(grid,Offset(0f,size.height*i/4),Offset(size.width,size.height*i/4),1.dp.toPx())
                fun point(b:SpeedBucket,peak:Boolean=false)=Offset(((minOf(duration,b.startSeconds+5.0)/duration)*size.width).toFloat(),(size.height*(1-(if(peak)b.maxMps else b.meanMps)*3.6/ceiling)).toFloat())
                groups.forEach { group ->
                    val mean=Path();val peak=Path();val fill=Path()
                    group.forEachIndexed { index,b ->
                        val p=point(b);val top=point(b,true)
                        if(index==0){mean.moveTo(p.x,p.y);peak.moveTo(top.x,top.y);fill.moveTo(p.x,size.height);fill.lineTo(p.x,p.y)}
                        else {mean.lineTo(p.x,p.y);peak.lineTo(top.x,top.y);fill.lineTo(p.x,p.y)}
                    }
                    fill.lineTo(point(group.last()).x,size.height);fill.close()
                    drawPath(fill,Brush.verticalGradient(listOf(ReportAccent.copy(alpha=.25f),ReportAccent.copy(alpha=.015f))))
                    drawPath(peak,ReportWarm.copy(alpha=.55f),style=Stroke(width=1.dp.toPx()))
                    drawPath(mean,ReportAccent,style=Stroke(width=2.5.dp.toPx()))
                    if(group.size==1) {drawCircle(ReportAccent,3.dp.toPx(),point(group.single()));drawCircle(ReportWarm,2.dp.toPx(),point(group.single(),true))}
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start=28.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(atTime(summary.startedAtMillis,"HH:mm"),style=MaterialTheme.typography.labelSmall)
            Text(atTime(summary.endedAtMillis,"HH:mm"),style=MaterialTheme.typography.labelSmall)
        }
        Text("Teal · average     Peach · peak",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
