package org.projectdrive.app.core.navigation

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Provider-neutral, self-hosted place search. Requests never include device location. */
data class Place(val id:String,val name:String,val kind:String,val locality:String,val coordinate:Coordinate)
interface PlaceSearch { suspend fun search(query:String):List<Place> }
class RemotePlaceSearch(private val server:String):PlaceSearch {
    private val client=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build()
    override suspend fun search(query:String):List<Place> = suspendCancellableCoroutine { continuation ->
        val request=Request.Builder().url("${server.trimEnd('/')}/v1/navigation/search")
            .post(JSONObject().put("query",query).toString().toRequestBody("application/json".toMediaType())).build()
        val call=client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:java.io.IOException) { if(continuation.isActive)continuation.resumeWithException(e) }
            override fun onResponse(call:Call,response:Response) {
                try {
                    val places=response.use {
                        check(it.isSuccessful){"Place search unavailable"}
                        val body=checkNotNull(it.body)
                        val bytes=body.byteStream().use { input -> val output=java.io.ByteArrayOutputStream()
                            val buffer=ByteArray(8192)
                            while(true) { val count=input.read(buffer);if(count<0)break;require(output.size()+count<=1024*1024);output.write(buffer,0,count) }
                            output.toByteArray() }
                        require(bytes.size<=1024*1024)
                        val result=JSONObject(String(bytes,Charsets.UTF_8)).getJSONArray("places")
                        require(result.length()<=10)
                        List(result.length()){i->result.getJSONObject(i).let{p->
                            val coordinate=Coordinate(p.getDouble("latitude"),p.getDouble("longitude"))
                            require(coordinate.latitude.isFinite()&&coordinate.longitude.isFinite()&&coordinate.latitude in -90.0..90.0&&coordinate.longitude in -180.0..180.0)
                            Place(p.getString("id"),p.getString("name"),p.getString("kind"),p.getString("locality"),coordinate)
                        }}
                    }
                    if(continuation.isActive)continuation.resume(places)
                } catch(error:Exception) { if(continuation.isActive)continuation.resumeWithException(error) }
            }
        })
    }
}
