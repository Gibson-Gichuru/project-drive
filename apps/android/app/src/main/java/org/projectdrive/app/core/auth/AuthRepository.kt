package org.projectdrive.app.core.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.projectdrive.app.BuildConfig
import org.projectdrive.app.core.convoy.Session
import org.projectdrive.app.core.convoy.SessionVault
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class AuthState(val ready:Boolean=false,val available:Boolean=false,val emailEnabled:Boolean=false,
    val userId:String?=null,val name:String="",val message:String="Checking sign-in service",val pendingLogout:Boolean=false)

interface AuthCredentialStore {
    fun saveAccount(value:ByteArray)
    fun loadAccount():ByteArray?
    fun clearAccount()
    fun saveLogout(value:ByteArray)
    fun loadLogout():ByteArray?
    fun clearLogout()
}

// No generated toString/copy/component methods can accidentally expose credentials.
internal class Credentials(val access:String,val refresh:String,val id:String,val name:String,val expires:Long) {
    fun session()=Session(access,id,expires)
    fun bytes()=JSONObject().put("version",1).put("origin",BuildConfig.API_SERVER_URL).put("access_token",access)
        .put("refresh_token",refresh).put("expires_at",expires/1000).put("user",JSONObject().put("id",id).put("display_name",name)).toString().toByteArray()
    companion object {
        fun parse(json:JSONObject):Credentials {
            if(json.has("version"))require(json.getInt("version")==1 && json.getString("origin")==BuildConfig.API_SERVER_URL)
            val access=json.getString("access_token");val refresh=json.getString("refresh_token")
            require(access.length in 1..4090 && refresh.length in 1..4096 && !access.contains('\n') && !refresh.contains('\n'))
            val user=json.getJSONObject("user");val id=user.getString("id");require(UUID.fromString(id).toString()==id)
            val expires=Math.multiplyExact(json.getLong("expires_at"),1000L)
            require(expires>0 && expires<=System.currentTimeMillis()+3_600_000)
            return Credentials(access,refresh,id,user.optString("display_name","Driver").take(80),expires)
        }
    }
}
class AuthFailure(val status:Int):Exception("Authentication request failed ($status)")

/** Suspending repository. UI or an active journey owns each call; no hidden timer. */
@Singleton
class AuthRepository internal constructor(private val client:OkHttpClient,private val vault:AuthCredentialStore,private val origin:String) {
    @Inject constructor(client:OkHttpClient,vault:SessionVault):this(client,vault,BuildConfig.API_SERVER_URL)
    private val mutex=Mutex()
    private val mutable=MutableStateFlow(AuthState());val state=mutable.asStateFlow()
    private var credentials:Credentials?=null
    private var loaded=false

    suspend fun restore()=mutex.withLock {
        try {
            val config=request("config",method="GET")
            mutable.value=mutable.value.copy(available=config.getBoolean("available"),emailEnabled=config.getBoolean("email_registration"))
            load()
            if(!flushLogout())return@withLock
            if(credentials!=null) {
                val value=currentLocked()
                val user=request("me",bearer=value.token,method="GET")
                require(user.getString("id")==value.userId)
                publish("Signed in · sharing stays off until you choose it")
            } else mutable.value=mutable.value.copy(message="Your people. Your journey. Sign in for private convoys.")
        }catch(e:CancellationException){throw e}
        catch(e:Exception){if(e is AuthFailure && e.status in setOf(400,401,403)){forget();mutable.value=mutable.value.copy(message="Please sign in again")}else mutable.value=mutable.value.copy(message="Sign-in service unavailable · maps and local recording still work")}
        finally {mutable.value=mutable.value.copy(ready=true)}
    }
    suspend fun currentSession():Session=mutex.withLock {load();check(!mutable.value.pendingLogout);currentLocked()}
    private suspend fun load(){if(!loaded){credentials=withContext(Dispatchers.IO){vault.loadAccount()?.let{Credentials.parse(JSONObject(it.toString(Charsets.UTF_8)))}};loaded=true}}
    private suspend fun currentLocked():Session {
        var value=credentials?:throw AuthFailure(401)
        if(value.expires<=System.currentTimeMillis()+60_000) {
            try {val next=Credentials.parse(request("refresh",JSONObject().put("refresh_token",value.refresh)));require(next.id==value.id)
                withContext(Dispatchers.IO){vault.saveAccount(next.bytes())};credentials=next;value=next;publish("Signed in")
            }catch(e:CancellationException){throw e}
            catch(e:Exception){if(e is AuthFailure && e.status in setOf(400,401,403)){forget();mutable.value=mutable.value.copy(message="Session ended · sign in again")};throw e}
        }
        return value.session()
    }
    suspend fun signIn(email:String,password:String)=mutex.withLock {
        load();check(flushLogout())
        val next=Credentials.parse(request("sign-in",JSONObject().put("email",email.trim()).put("password",password)))
        withContext(Dispatchers.IO){vault.saveAccount(next.bytes())};credentials=next;publish("Welcome back · sharing is off")
    }
    suspend fun signUp(email:String,password:String,name:String)=mutex.withLock {
        request("sign-up",JSONObject().put("email",email.trim()).put("password",password).put("display_name",name.trim()))
        mutable.value=mutable.value.copy(message="Check your email for a verification code")
    }
    suspend fun recover(email:String)=mutex.withLock {request("recover",JSONObject().put("email",email.trim()));mutable.value=mutable.value.copy(message="If that account exists, a recovery code is on its way")}
    suspend fun verify(email:String,code:String,recovery:Boolean)=mutex.withLock {
        check(flushLogout())
        val next=Credentials.parse(request("verify",JSONObject().put("email",email.trim()).put("token",code.trim()).put("type",if(recovery)"recovery" else "signup")))
        withContext(Dispatchers.IO){vault.saveAccount(next.bytes())};credentials=next;publish(if(recovery)"Email verified · set a new password" else "Email verified · sharing is off")
    }
    suspend fun password(value:String)=mutex.withLock {request("password",JSONObject().put("password",value),currentLocked().token);mutable.value=mutable.value.copy(message="Password updated")}
    suspend fun signOut()=mutex.withLock {
        load();credentials?.let{withContext(Dispatchers.IO){vault.saveLogout(it.bytes())}}
        forget();mutable.value=mutable.value.copy(pendingLogout=true,message="Signed out on this phone · finishing server sign-out")
        flushLogout()
    }
    private suspend fun flushLogout():Boolean {
        val bytes=withContext(Dispatchers.IO){vault.loadLogout()}?:run{mutable.value=mutable.value.copy(pendingLogout=false);return true}
        forget() // A crash between durable logout intent and credential removal stays signed out.
        mutable.value=mutable.value.copy(pendingLogout=true)
        try {
            var value=Credentials.parse(JSONObject(bytes.toString(Charsets.UTF_8)))
            if(value.expires<=System.currentTimeMillis()+10_000) {
                value=Credentials.parse(request("refresh",JSONObject().put("refresh_token",value.refresh)))
                withContext(Dispatchers.IO){vault.saveLogout(value.bytes())}
            }
            request("sign-out",bearer=value.access)
        }catch(e:CancellationException){throw e}
        catch(e:Exception){if(e !is AuthFailure || e.status !in setOf(400,401,403)){mutable.value=mutable.value.copy(message="Signed out on this phone · reconnect to finish server sign-out");return false}}
        withContext(Dispatchers.IO){vault.clearLogout()}
        mutable.value=mutable.value.copy(pendingLogout=false,message="Signed out · sharing is off");return true
    }
    suspend fun rejected(session:Session)=mutex.withLock {
        if(credentials?.access==session.token){forget();mutable.value=mutable.value.copy(message="Session ended · sign in again")}
    }
    private suspend fun forget(){credentials=null;loaded=true;withContext(Dispatchers.IO){vault.clearAccount()};mutable.value=mutable.value.copy(userId=null,name="")}
    private fun publish(message:String){val c=checkNotNull(credentials);mutable.value=mutable.value.copy(userId=c.id,name=c.name,message=message,pendingLogout=false,ready=true)}
    private suspend fun request(path:String,body:JSONObject=JSONObject(),bearer:String?=null,method:String="POST"):JSONObject=suspendCancellableCoroutine { continuation ->
        val builder=Request.Builder().url(origin+"/v1/auth/"+path).header("Cache-Control","no-store")
        bearer?.let{builder.header("Authorization","Bearer $it")}
        builder.method(method,if(method=="GET")null else body.toString().toRequestBody("application/json".toMediaType()))
        val call=client.newCall(builder.build())
        continuation.invokeOnCancellation{call.cancel()}
        call.enqueue(object:okhttp3.Callback {
            override fun onFailure(call:okhttp3.Call,e:java.io.IOException){if(continuation.isActive)continuation.resumeWithException(e)}
            override fun onResponse(call:okhttp3.Call,response:okhttp3.Response){
                try{val value=response.use {
                    val source=it.body?.source()?:throw AuthFailure(503);val buffer=okio.Buffer()
                    while(buffer.size<=16384){if(source.read(buffer,4096)==-1L)break};require(buffer.size<=16384)
                    if(!it.isSuccessful)throw AuthFailure(it.code)
                    JSONObject(buffer.readUtf8())
                };if(continuation.isActive)continuation.resume(value)}
                catch(e:Exception){if(continuation.isActive)continuation.resumeWithException(e)}
            }
        })
    }
}
