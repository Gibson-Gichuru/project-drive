package org.projectdrive.app.feature.telemetry

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray
import org.projectdrive.app.core.telemetry.*
import org.projectdrive.app.core.location.LocationFix
import org.projectdrive.app.core.convoy.*
import java.util.UUID
import javax.inject.Inject

data class TelemetryState(
    val recording: Boolean=false, val saving: Boolean=false, val uploading: Boolean=false,
    val current: TelemetrySummary?=null, val recordings: List<LocalRecording> = emptyList(),
    val message: String="Recording off · no telemetry upload", val sensors: Set<String> = emptySet(),
    val remoteCount: Int?=null,
)
@HiltViewModel
class TelemetryViewModel @Inject constructor(
    private val source: TelemetrySource, private val store: LocalTelemetryStore,
    private val api: TripRepository, private val vault: SessionVault,
) : ViewModel() {
    private val mutable=MutableStateFlow(TelemetryState(saving=true));val state=mutable.asStateFlow()
    private var foreground=false
    private var generation=0L
    fun resume(){foreground=true}
    private var processor: TelemetryProcessor?=null
    private var id: String?=null
    private var checkpoint: Job?=null
    private val persistence=Mutex()
    private val finishing=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    init { viewModelScope.launch {
        try { mutable.update { it.copy(recordings=store.recover(),saving=false) } }
        catch (_:Exception) { mutable.update { it.copy(saving=false,message="Local storage unavailable · recording remains off") } }
        while(true){delay(60_000);try{persistence.withLock{mutable.update { it.copy(recordings=store.list()) }}}catch(e:CancellationException){throw e}catch(_:Exception){mutable.update { it.copy(message="Local cleanup unavailable · check device storage") }}}
    } }
    fun start() {
        if (!foreground || processor!=null || mutable.value.saving || mutable.value.uploading) return
        val epoch=++generation
        mutable.update { it.copy(saving=true) }
        viewModelScope.launch {
            persistence.withLock {
                try {
                    val now=SystemClock.elapsedRealtime();val value=TelemetryProcessor(now,System.currentTimeMillis());val recordingId=UUID.randomUUID().toString()
                    store.save(local(recordingId,value.result(now),true))
                    if(!foreground || generation!=epoch){store.save(local(recordingId,value.result(SystemClock.elapsedRealtime(),true),false));mutable.update { it.copy(saving=false,recordings=store.list()) };return@withLock}
                    processor=value;id=recordingId
                    val sensors=source.start { sample -> processor?.onMotion(sample,SystemClock.elapsedRealtime()) }
                    mutable.update { it.copy(recording=true,saving=false,current=value.result(now).summary,sensors=sensors,message="Recording locally · sharing and upload remain separate") }
                    checkpoint=viewModelScope.launch {
                        var tick=0
                        while (processor===value) {
                            delay(1000);val time=SystemClock.elapsedRealtime();value.expireRaw(time)
                            val result=value.result(time)
                            mutable.update { it.copy(current=result.summary) }
                            if (result.summary.durationSeconds>=14400) { finish("Four-hour recording limit reached");return@launch }
                            if (++tick%5==0) {
                                try { persistence.withLock { if (processor===value) store.save(local(recordingId,result,true)) } }
                                catch(e:CancellationException){throw e}
                                catch(_:Exception){finish("Checkpoint unavailable · recording stopped",true);return@launch}
                            }
                        }
                    }
                } catch (_:Exception) { source.stop();processor=null;id=null;mutable.update { it.copy(recording=false,saving=false,message="Could not start recording · check storage or delete an old local summary") } }
            }
        }
    }
    fun onFix(fix: LocationFix) { processor?.onFix(fix,SystemClock.elapsedRealtime()) }
    fun permissionDenied() { mutable.update { it.copy(message="Location permission denied · no recording started") } }
    fun locationUnavailable() { if (processor!=null) mutable.update { it.copy(message="Recording sensors · GPS unavailable; distance/speed may be incomplete") } }
    fun finish(reason: String="Recording finished", interrupted: Boolean=false): Job? {
        val value=processor?:return null;val recordingId=checkNotNull(id)
        processor=null;id=null;checkpoint?.cancel();source.stop()
        val result=value.result(SystemClock.elapsedRealtime(),interrupted)
        mutable.update { it.copy(recording=false,saving=true,current=result.summary,message=reason) }
        return finishing.launch {
            persistence.withLock {
                try { store.save(local(recordingId,result,false));mutable.update { it.copy(recordings=store.list(),saving=false) } }
                catch (_:Exception) { mutable.update { it.copy(saving=false,message="Final save failed · the last checkpoint will be recovered as interrupted") } }
            }
        }
    }
    fun pause() { foreground=false;generation++;finish("Recording stopped when app left the foreground",true) }
    fun delete(recording: LocalRecording) { if (mutable.value.recording || mutable.value.saving || mutable.value.uploading) return
        viewModelScope.launch { try { persistence.withLock { store.delete(recording.id);mutable.update { it.copy(recordings=store.list()) } } }
            catch(e:CancellationException){throw e}
            catch(_:Exception){mutable.update { it.copy(message="Could not delete local summary · check device storage") }} }
    }
    fun upload(recording: LocalRecording,trip: Trip) {
        if (mutable.value.recording || mutable.value.uploading || mutable.value.saving) return
        mutable.update { it.copy(uploading=true) }
        viewModelScope.launch {
            try {
                val session=withContext(Dispatchers.IO){vault.load()}?:error("Session unavailable")
                val bound=store.bind(recording.id,trip.id,session.userId)
                val body=JSONObject().put("version",1).put("recording_id",bound.id).put("summary",JSONObject(bound.summaryJSON)).put("events",JSONArray(bound.eventsJSON))
                api.request(session,"POST","/v1/trips/${trip.id}/telemetry",body)
                store.acknowledge(bound.id)
                refreshRecords()
                mutable.update { it.copy(message="Derived summary uploaded · local event details cleared; server expiry is seven days") }
            } catch (e:CancellationException){throw e}
            catch (_:Exception) { mutable.update { it.copy(message="Upload unavailable · check membership/session and retry the same trip within 24 hours") } }
            finally { mutable.update { it.copy(uploading=false) } }
        }
    }
    fun remote(trip: Trip) { if (mutable.value.uploading) return
        mutable.update { it.copy(uploading=true) };viewModelScope.launch {
            try { val session=withContext(Dispatchers.IO){vault.load()}?:error("Session unavailable")
                val response=api.request(session,"GET","/v1/trips/${trip.id}/summary")
                mutable.update { it.copy(remoteCount=response.getJSONArray("recordings").length(),message="Loaded only your own server summaries") }
            }catch (_:Exception){mutable.update { it.copy(message="Server summary unavailable · check session and membership") }}
            finally{mutable.update { it.copy(uploading=false) }}
        }
    }
    private suspend fun refreshRecords(){persistence.withLock{val records=store.list();mutable.update { it.copy(recordings=records) }}}
    private fun local(recordingId: String,result: TelemetryResult,active: Boolean)=LocalRecording(recordingId,result.summary.toJSON().toString(),result.eventsJSON().toString(),active,result.summary.endedAtMillis+LOCAL_RETENTION_MILLIS,result.summary.endedAtMillis+UPLOAD_WINDOW_MILLIS)
    override fun onCleared() { finish("Recording interrupted",true);source.stop();finishing.launch{delay(15_000);finishing.cancel()};super.onCleared() }
}
