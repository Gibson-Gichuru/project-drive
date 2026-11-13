package org.projectdrive.app.core.navigation

import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Only an explicit shared short-map URL triggers an external redirect request. */
class MapLinkResolver(private val client:OkHttpClient=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5,TimeUnit.SECONDS).callTimeout(8,TimeUnit.SECONDS).build()) {
    suspend fun resolve(text:String):MapLink {
        var result=MapLinks.parse(text)
        repeat(5) {
            val short=result as? MapLink.Short?:return result
            require(MapLinks.allowedExpansion(short.url))
            val next=redirect(short.url)?:return MapLink.Unsupported
            val resolved=URI(short.url).resolve(next).toString()
            if(!MapLinks.allowedExpansion(resolved))return MapLink.Unsupported
            result=MapLinks.parse(resolved)
        }
        return MapLink.Unsupported
    }
    private suspend fun redirect(url:String):String? = suspendCancellableCoroutine { continuation ->
        val call=client.newCall(Request.Builder().url(url).get().build())
        continuation.invokeOnCancellation{call.cancel()}
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:java.io.IOException){if(continuation.isActive)continuation.resumeWithException(e)}
            override fun onResponse(call:Call,response:Response){
                val location=response.use { if(it.code in 300..399)it.header("Location") else null }
                if(continuation.isActive)continuation.resume(location)
            }
        })
    }
}
