package org.projectdrive.app

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.projectdrive.app.core.auth.*
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** In-memory credentials and a loopback HTTP fixture, never the owner's vault. */
class AuthSessionTest {
    private class MemoryStore:AuthCredentialStore {
        var account:ByteArray?=null;var logout:ByteArray?=null
        override fun saveAccount(value:ByteArray){account=value.clone()}
        override fun loadAccount()=account?.clone()
        override fun clearAccount(){account=null}
        override fun saveLogout(value:ByteArray){logout=value.clone()}
        override fun loadLogout()=logout?.clone()
        override fun clearLogout(){logout=null}
    }
    private val id="11111111-1111-4111-8111-111111111111"
    private fun credentials(expires:Long=System.currentTimeMillis()+900_000)=JSONObject().put("access_token","synthetic-access").put("refresh_token","synthetic-refresh")
        .put("expires_at",expires/1000).put("user",JSONObject().put("id",id).put("display_name","Synthetic driver"))
    private class Server(private val reply:(String)->Pair<Int,String>):AutoCloseable {
        private val socket=ServerSocket(0,10,InetAddress.getByName("127.0.0.1"))
        val origin="http://127.0.0.1:${socket.localPort}"
        private val worker=thread(start=true,name="outset-auth-test-http") {
            while(!socket.isClosed)try{socket.accept().use{s->
                s.soTimeout=5000;val reader=s.getInputStream().bufferedReader();val request=reader.readLine()?:return@use
                var length=0
                while(true){val line=reader.readLine()?:break;if(line.isEmpty())break;if(line.startsWith("Content-Length:",true))length=line.substringAfter(':').trim().toInt()}
                require(length in 0..16384);repeat(length){reader.read()}
                val (status,body)=reply(request.split(' ')[1]);val bytes=body.toByteArray()
                s.getOutputStream().write(("HTTP/1.1 $status Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()+bytes)
            }}catch(_:java.io.IOException){if(socket.isClosed)break}
        }
        override fun close(){socket.close();worker.join(5000)}
    }
    @Test fun concurrentConsumersRotateOnlyOnceAndRestoreTheSameAccount()=runBlocking {
        val refresh=AtomicInteger();val store=MemoryStore()
        Server {path->200 to when(path){
            "/v1/auth/config"->"{\"available\":true,\"email_registration\":true}"
            "/v1/auth/me"->"{\"id\":\"$id\"}"
            "/v1/auth/refresh"->{refresh.incrementAndGet();credentials().toString()}
            else->credentials(System.currentTimeMillis()+30_000).toString()
        }}.use {server->
            val auth=AuthRepository(OkHttpClient(),store,server.origin)
            auth.signIn("synthetic@example.test","synthetic-password")
            coroutineScope{(0..7).map{async(Dispatchers.Default){auth.currentSession()}}.awaitAll().forEach{assertEquals(id,it.userId)}}
            assertEquals(1,refresh.get())
            val restored=AuthRepository(OkHttpClient(),store,server.origin);restored.restore()
            assertEquals(id,restored.state.value.userId);assertEquals(1,refresh.get())
        }
    }
    @Test fun offlineLogoutSurvivesRestartAndIsRevokedBeforeAnotherSignIn()=runBlocking {
        val online=AtomicBoolean(true);val logouts=AtomicInteger();val store=MemoryStore()
        Server {path->if(!online.get())503 to "{}" else 200 to when(path){
            "/v1/auth/config"->"{\"available\":true,\"email_registration\":false}"
            "/v1/auth/sign-out"->{logouts.incrementAndGet();"{}"}
            else->credentials().toString()
        }}.use {server->
            val auth=AuthRepository(OkHttpClient(),store,server.origin);auth.signIn("synthetic@example.test","synthetic-password")
            online.set(false);auth.signOut();assertNull(auth.state.value.userId);assertNull(store.account);assertNotNull(store.logout)
            val restored=AuthRepository(OkHttpClient(),store,server.origin)
            online.set(true);restored.restore();assertNull(restored.state.value.userId);assertFalse(restored.state.value.pendingLogout);assertNull(store.logout);assertEquals(1,logouts.get())
        }
    }
    @Test fun rejectedCurrentCredentialClearsLocalAccount()=runBlocking {
        val store=MemoryStore()
        Server{200 to credentials().toString()}.use{server->
            val auth=AuthRepository(OkHttpClient(),store,server.origin);auth.signIn("synthetic@example.test","synthetic-password")
            val session=auth.currentSession();auth.rejected(session)
            assertNull(auth.state.value.userId);assertNull(store.account)
        }
    }
}
