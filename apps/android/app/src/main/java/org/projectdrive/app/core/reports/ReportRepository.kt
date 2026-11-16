package org.projectdrive.app.core.reports

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.projectdrive.app.BuildConfig
import org.projectdrive.app.core.auth.AuthRepository
import org.projectdrive.app.core.navigation.Coordinate
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ReportFailure(val status:Int):Exception("Road report request failed ($status)")
data class ReportSnapshot(val reports:List<RoadReport>,val serverTime:Long)
class ReportRepository @Inject constructor(private val client:OkHttpClient,private val auth:AuthRepository) {
    suspend fun nearby(center:Coordinate):ReportSnapshot {
        val json=request("POST","/nearby",JSONObject().put("center",point(center)).put("radius_m",10000))
        val list=json.getJSONArray("reports");require(list.length()<=100)
        return ReportSnapshot((0 until list.length()).map{parse(list.getJSONObject(it))},Instant.parse(json.getString("server_time")).toEpochMilli())
    }
    suspend fun publish(body:JSONObject,key:String)=parse(request("POST","",body,key))
    suspend fun vote(id:String,vote:Int)=parse(request("PUT","/${UUID.fromString(id)}/vote",JSONObject().put("vote",vote)))
    suspend fun withdraw(id:String)=parse(request("DELETE","/${UUID.fromString(id)}"))
    private suspend fun request(method:String,path:String,body:JSONObject?=null,key:String?=null):JSONObject {
        val session=auth.currentSession()
        val request=Request.Builder().url(BuildConfig.API_SERVER_URL+"/v1/reports"+path)
            .header("Authorization","Bearer ${session.token}").header("Cache-Control","no-store")
        key?.let{request.header("Idempotency-Key",UUID.fromString(it).toString())}
        request.method(method,if(method=="POST"||method=="PUT")checkNotNull(body).toString().toRequestBody("application/json".toMediaType()) else null)
        try {
            return suspendCancellableCoroutine { continuation ->
                val call=client.newCall(request.build());continuation.invokeOnCancellation{call.cancel()}
                call.enqueue(object:Callback {
                    override fun onFailure(call:Call,e:java.io.IOException){if(continuation.isActive)continuation.resumeWithException(e)}
                    override fun onResponse(call:Call,response:Response){
                        try {val result=response.use {
                            if(!it.isSuccessful)throw ReportFailure(it.code)
                            val source=it.body.source();val buffer=okio.Buffer()
                            while(buffer.size<=131072){if(source.read(buffer,8192)==-1L)break};require(buffer.size<=131072)
                            JSONObject(buffer.readUtf8())
                        };if(continuation.isActive)continuation.resume(result)}
                        catch(e:Exception){if(continuation.isActive)continuation.resumeWithException(e)}
                    }
                })
            }
        }catch(e:ReportFailure){if(e.status==401)auth.rejected(session);throw e}
    }
    private fun parse(j:JSONObject):RoadReport {
        val p=j.getJSONObject("location")
        return RoadReport(UUID.fromString(j.getString("id")).toString(),ReportCategory.from(j.getString("category")),
            Coordinate(p.getDouble("latitude"),p.getDouble("longitude")),j.getString("note").take(240),
            Instant.parse(j.getString("observed_at")).toEpochMilli(),Instant.parse(j.getString("expires_at")).toEpochMilli(),
            j.getBoolean("mine"),j.getInt("confirmations"),j.getInt("dismissals"),j.getInt("my_vote"),j.getString("confidence"),j.getString("status"))
    }
    companion object { fun point(p:Coordinate)=JSONObject().put("latitude",p.latitude).put("longitude",p.longitude) }
}
