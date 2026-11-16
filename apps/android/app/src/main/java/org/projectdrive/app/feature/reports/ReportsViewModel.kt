package org.projectdrive.app.feature.reports

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.projectdrive.app.core.location.LocationFix
import org.projectdrive.app.core.navigation.*
import org.projectdrive.app.core.reports.*
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

data class ReportsUi(val signedIn:Boolean=false,val enabled:Boolean=false,val busy:Boolean=false,
    val reports:List<RoadReport> = emptyList(),val selected:String?=null,val pin:Coordinate?=null,
    val picking:Boolean=false,val category:ReportCategory=ReportCategory.Obstacle,val note:String="",
    val parked:Boolean=false,val moving:Boolean=false,val simulated:Boolean=false,val freshLocation:Boolean=false,
    val now:Long=System.currentTimeMillis(),val warning:RoadReport?=null,val openRequest:Long=0,
    val message:String="See what the road has in store.")

@HiltViewModel
class ReportsViewModel @Inject constructor(private val repository:ReportRepository):ViewModel() {
    private val mutable=MutableStateFlow(ReportsUi());val state=mutable.asStateFlow()
    private var userId:String?=null
    private var fix:LocationFix?=null;private var route:Route?=null;private var guiding=false
    private var active=false;private var request:Job?=null;private var lastAttempt=-30_000L
    private var fetchedAt:Long?=null;private var serverTime:Long=0
    private var pendingBody:JSONObject?=null;private var pendingKey:String?=null
    fun account(id:String?) {if(userId==id)return;request?.cancel();userId=id;fetchedAt=null;pendingBody=null;pendingKey=null;mutable.value=ReportsUi(signedIn=id!=null)}
    fun navigation(location:LocationFix?,route:Route?,guiding:Boolean,simulated:Boolean) {
        fix=location;this.route=route;this.guiding=guiding
        val fresh=location?.canGuide(SystemClock.elapsedRealtime())==true
        mutable.value=mutable.value.copy(freshLocation=fresh,moving=fresh&&(location.speedMps?:0f)>3f,simulated=simulated)
    }
    fun foreground(value:Boolean){active=value;if(!value){request?.cancel();mutable.value=mutable.value.copy(busy=false,warning=null)} }
    fun tick() {
        val elapsed=SystemClock.elapsedRealtime();val fetched=fetchedAt
        val now=if(fetched!=null)serverTime+(elapsed-fetched) else System.currentTimeMillis()
        val fresh=fetched!=null&&elapsed-fetched in 0..90_000
        val list=if(fresh)mutable.value.reports.filter{it.active(now)} else emptyList()
        val precise=fix?.takeIf{it.canGuide(elapsed)}
        mutable.value=mutable.value.copy(reports=list,now=now,freshLocation=precise!=null,
            moving=precise!=null&&(precise.speedMps?:0f)>3f,
            warning=if(active&&mutable.value.enabled&&guiding&&!mutable.value.simulated&&precise!=null&&fresh)upcomingReport(list,route,precise.coordinate,now) else null)
        if(active&&mutable.value.enabled&&elapsed-lastAttempt>=30_000)refresh()
    }
    fun enable(){if(userId==null||!mutable.value.freshLocation||mutable.value.simulated)return
        mutable.value=mutable.value.copy(enabled=true,message="Community observations · nearest 100 within 10 km");lastAttempt=-30_000L;refresh()}
    fun disable(){request?.cancel();fetchedAt=null;mutable.value=mutable.value.copy(enabled=false,busy=false,reports=emptyList(),selected=null,warning=null,message="Road reports off")}
    fun refresh(){
        if(!active||!mutable.value.enabled||request?.isActive==true||mutable.value.simulated)return
        val center=fix?.takeIf{it.canGuide(SystemClock.elapsedRealtime())}?.coordinate?:run {mutable.value=mutable.value.copy(message="Waiting for a fresh location within 50 m accuracy");return}
        lastAttempt=SystemClock.elapsedRealtime()
        launch {val snapshot=repository.nearby(center);serverTime=snapshot.serverTime;fetchedAt=SystemClock.elapsedRealtime()
            mutable.value=mutable.value.copy(reports=snapshot.reports,message="Community observations · nearest 100 within 10 km")}
    }
    fun choosePin(){if(!canEdit())return;mutable.value=mutable.value.copy(picking=true,selected=null,parked=false)}
    fun cancelPin(){mutable.value=mutable.value.copy(picking=false)}
    fun pin(value:Coordinate){if(!mutable.value.picking||!canEdit())return;pendingBody=null;pendingKey=null
        mutable.value=mutable.value.copy(pin=value,picking=false,openRequest=mutable.value.openRequest+1,message="Pin selected · check the map before publishing")}
    fun select(id:String){if(mutable.value.reports.none{it.id==id})return;mutable.value=mutable.value.copy(selected=id,openRequest=mutable.value.openRequest+1)}
    fun category(value:ReportCategory){if(mutable.value.busy)return;pendingBody=null;pendingKey=null;mutable.value=mutable.value.copy(category=value)}
    fun note(value:String){if(mutable.value.busy)return;pendingBody=null;pendingKey=null;mutable.value=mutable.value.copy(note=value.filterNot(Char::isISOControl).take(240))}
    fun parked(value:Boolean){mutable.value=mutable.value.copy(parked=value)}
    fun publish(){val s=mutable.value;if(!canEdit()||!s.parked||s.pin==null||s.busy)return
        val body=pendingBody?:JSONObject().put("category",s.category.wire).put("location",ReportRepository.point(s.pin)).put("note",s.note).put("observed_at",Instant.now().toString()).also{pendingBody=it}
        val key=pendingKey?:UUID.randomUUID().toString().also{pendingKey=it}
        launch {val report=repository.publish(body,key);pendingBody=null;pendingKey=null
            mutable.value=mutable.value.copy(pin=null,note="",parked=false,message="Report published · community observation, not a verified fact")
            replace(report)}
    }
    fun vote(id:String,vote:Int){if(!canEdit()||mutable.value.busy)return;launch{replace(repository.vote(id,vote))}}
    fun withdraw(id:String){if(!canEdit()||mutable.value.busy)return;launch{replace(repository.withdraw(id))}}
    private fun replace(report:RoadReport){
        // A successful write is not a fresh nearby snapshot and cannot refresh old warnings.
        mutable.value=mutable.value.copy(reports=(mutable.value.reports.filter{it.id!=report.id}+report).filter{it.active(mutable.value.now)},selected=report.id)
    }
    private fun canEdit()=userId!=null&&!mutable.value.moving&&!mutable.value.simulated
    private fun launch(block:suspend()->Unit){if(request?.isActive==true)return
        mutable.value=mutable.value.copy(busy=true)
        request=viewModelScope.launch{try{block()}catch(e:CancellationException){throw e}
            catch(e:Exception){mutable.value=mutable.value.copy(message=when((e as? ReportFailure)?.status){401->"Sign in again to use road reports";404->"This report expired or was withdrawn";409->"Report changed · choose the pin again";429->"Report limit reached · try again later";else->"Connection unavailable · retry; old reports stop showing after 90 seconds"})}
            finally{mutable.value=mutable.value.copy(busy=false)}}
    }
}
