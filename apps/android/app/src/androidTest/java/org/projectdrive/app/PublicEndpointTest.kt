package org.projectdrive.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.projectdrive.app.core.navigation.Coordinate
import org.projectdrive.app.core.navigation.RemoteNavigationEngine
import org.projectdrive.app.core.navigation.RemotePlaceSearch
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit opt-in network acceptance. Fixed public sample only; never reads GPS. */
@RunWith(AndroidJUnit4::class)
class PublicEndpointTest {
    @Test fun httpsMapsRoutingAndPrivateApiBoundary() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("publicEndpointChecks") == "true")
        assertTrue(BuildConfig.MAP_SERVER_URL.startsWith("https://"))
        assertTrue(BuildConfig.API_SERVER_URL.startsWith("https://"))
        assertFalse(BuildConfig.LOCAL_TEST_AUTH)
        val route = RemoteNavigationEngine(BuildConfig.MAP_SERVER_URL).route(Coordinate(-1.2864,36.82),Coordinate(-1.3152,36.8148))
        assertTrue(route.geometry.size > 1); assertTrue(route.distanceMeters > 0)
        for (path in listOf("/maps/tiles/6/38/32", "/maps/fonts/Noto%20Sans%20Regular/0-255")) {
            val connection = URL(BuildConfig.MAP_SERVER_URL + path).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout=10000;connection.readTimeout=15000
                assertEquals(200,connection.responseCode)
                connection.inputStream.use { assertTrue(it.read() >= 0) }
            } finally { connection.disconnect() }
        }
        val places=RemotePlaceSearch(BuildConfig.MAP_SERVER_URL).search("Nakuru")
        assertTrue(places.any {it.name.equals("Nakuru",ignoreCase=true)})
        val index=URL(BuildConfig.MAP_SERVER_URL+"/maps/places-index").openConnection() as HttpURLConnection
        try {
            index.connectTimeout=10000;index.readTimeout=15000
            assertEquals(200,index.responseCode)
            assertTrue(index.contentType.startsWith("application/json"))
            index.inputStream.use {assertTrue(it.read()>=0)}
        }finally{index.disconnect()}
        val client=OkHttpClient.Builder().callTimeout(20,TimeUnit.SECONDS).build()
        try {
            client.newCall(Request.Builder().url(BuildConfig.API_SERVER_URL+"/v1/trips/00000000-0000-4000-8000-000000000001").build()).execute().use {
                assertEquals(401,it.code);assertEquals("no-store",it.header("Cache-Control"))
            }
            for(path in listOf("/healthz","/readyz","/metrics")) {
                client.newCall(Request.Builder().url(BuildConfig.API_SERVER_URL+path).build()).execute().use { assertEquals(404,it.code) }
            }
            val done=CountDownLatch(1);var status:Int?=null
            val socket=client.newWebSocket(Request.Builder().url(BuildConfig.API_SERVER_URL+"/v1/trips/00000000-0000-4000-8000-000000000001/stream").header("Sec-WebSocket-Protocol","project-drive.v1").build(),object:WebSocketListener(){
                override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?){status=response?.code;done.countDown()}
                override fun onOpen(webSocket:WebSocket,response:Response){status=101;done.countDown()}
            })
            try{assertTrue(done.await(20,TimeUnit.SECONDS));assertEquals(401,status)}finally{socket.cancel()}
        } finally {client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
        Unit
    }
}
