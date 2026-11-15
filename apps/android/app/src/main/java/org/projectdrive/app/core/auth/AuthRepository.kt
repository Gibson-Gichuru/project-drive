package org.projectdrive.app.core.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.logging.LogLevel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.projectdrive.app.BuildConfig
import org.projectdrive.app.core.convoy.Session
import org.projectdrive.app.core.convoy.SessionVault
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.inject.Inject
import javax.inject.Singleton

data class AuthState(val ready:Boolean=false,val available:Boolean=false,
    val userId:String?=null,val name:String="",val message:String="Checking sign-in service",val pendingLogout:Boolean=false)

interface AuthCredentialStore {
    fun saveAccount(value:ByteArray)
    fun loadAccount():ByteArray?
    fun clearAccount()
    fun saveLogout(value:ByteArray)
    fun loadLogout():ByteArray?
    fun clearLogout()
    fun savePkce(value:ByteArray)
    fun loadPkce():ByteArray?
    fun clearPkce()
}
class AuthFailure(val status:Int):Exception("Authentication request failed ($status)")

/** The caller owns each operation. Supabase owns OAuth, exchange and refresh. */
@Singleton
class AuthRepository internal constructor(private val client:OkHttpClient,private val vault:AuthCredentialStore,private val origin:String) {
    @Inject constructor(client:OkHttpClient,vault:SessionVault):this(client,vault,BuildConfig.API_SERVER_URL)
    private val mutex=Mutex()
    private val mutable=MutableStateFlow(AuthState());val state=mutable.asStateFlow()
    private var sdk:SupabaseClient?=null
    private var storage:SupabaseVault?=null
    private var configured=false

    @OptIn(io.github.jan.supabase.annotations.SupabaseInternal::class)
    private suspend fun initialize() {
        if(configured)return
        val config=request("config",method="GET")
        mutable.value=mutable.value.copy(available=config.optBoolean("google"))
        if(!mutable.value.available){mutable.value=mutable.value.copy(message="Google sign-in is being configured · maps and local recording are ready");return}
        val address=config.getString("supabase_url");val uri=URI(address)
        val localFixture=BuildConfig.DEBUG && URI(origin).host=="127.0.0.1" && uri.scheme=="http" && uri.host=="127.0.0.1"
        require(((uri.scheme=="https" && uri.host?.endsWith(".supabase.co")==true)||localFixture) && uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null && uri.rawPath.isNullOrEmpty())
        val key=config.getString("publishable_key");require(key.startsWith("sb_publishable_") && key.length<256 && key.none(Char::isISOControl))
        val store=SupabaseVault(vault,address+"|"+origin)
        val next=createSupabaseClient(address,key) {
            defaultLogLevel=LogLevel.NONE
            httpConfig { followRedirects=false }
            install(Auth) {
                flowType=FlowType.PKCE
                defaultRedirectUrl=GoogleCallback.REDIRECT
                sessionManager=store
                codeVerifierCache=store
                autoLoadFromStorage=false
                alwaysAutoRefresh=false
                enableLifecycleCallbacks=false
            }
        }
        next.auth.awaitInitialization()
        sdk=next;storage=store;configured=true
        // A durable logout intent wins over a previously persisted account.
        if(withContext(Dispatchers.IO){vault.loadLogout()}!=null) {
            next.auth.clearSession();mutable.value=mutable.value.copy(pendingLogout=true)
        } else next.auth.loadFromStorage(autoRefresh=false)
    }
    suspend fun restore()=mutex.withLock {
        try {
            initialize()
            if(!configured || !flushLogout())return@withLock
            if(sdk!!.auth.currentSessionOrNull()!=null) {
                val value=currentLocked()
                val user=request("me",bearer=value.token,method="GET")
                require(user.getString("id")==value.userId)
                publish("Signed in · sharing stays off until you choose it")
            } else mutable.value=mutable.value.copy(message="Your people. Your journey. Continue with Google for private convoys.")
        }catch(e:CancellationException){throw e}
        catch(e:Exception){if(e is AuthFailure && e.status in setOf(400,401,403)){forget();mutable.value=mutable.value.copy(message="Please sign in again")}else mutable.value=mutable.value.copy(message="Sign-in unavailable · maps and local recording still work")}
        finally {mutable.value=mutable.value.copy(ready=true)}
    }
    suspend fun googleUrl():String=mutex.withLock {
        initialize();check(configured && flushLogout())
        check(sdk!!.auth.currentSessionOrNull()==null)
        val saved=storage!!.prepare()
        val url=sdk!!.auth.getOAuthUrl(Google,redirectUrl=GoogleCallback.REDIRECT) { queryParams["prompt"]="select_account" }
        // The SDK persists PKCE asynchronously. Do not launch the browser before it is durable.
        withTimeout(5000){saved.await()}
        mutable.value=mutable.value.copy(message="Finish sign-in in your browser, then return to Outset")
        url
    }
    suspend fun callback(value:String)=mutex.withLock {
        initialize();check(configured && !mutable.value.pendingLogout)
        val code=GoogleCallback.code(value)
        check(sdk!!.auth.currentSessionOrNull()==null && storage!!.loadCodeVerifier()!=null)
        val session=sdk!!.auth.exchangeCodeForSession(code,saveSession=false)
        sdk!!.auth.importSession(session.copy(providerToken=null,providerRefreshToken=null),autoRefresh=false)
        // Application authorization is still checked by Go, not inferred from the callback.
        val current=currentLocked();val user=request("me",bearer=current.token,method="GET")
        require(user.getString("id")==current.userId)
        publish("Welcome aboard · sharing is off")
    }
    suspend fun currentSession():Session=mutex.withLock {initialize();check(configured && !mutable.value.pendingLogout);currentLocked()}
    private suspend fun currentLocked():Session {
        val auth=checkNotNull(sdk).auth
        var session=auth.currentSessionOrNull()?:throw AuthFailure(401)
        if(session.expiresAt.toEpochMilliseconds()<=System.currentTimeMillis()+60_000) {
            try {auth.refreshCurrentSession();session=checkNotNull(auth.currentSessionOrNull());publish("Signed in")}
            catch(e:CancellationException){throw e}
            catch(e:Exception){if(e is io.github.jan.supabase.exceptions.RestException && e.statusCode in setOf(400,401,403)){forget()};throw e}
        }
        val id=checkNotNull(session.user).id
        require(session.accessToken.length in 1..4090 && session.expiresAt.toEpochMilliseconds()>System.currentTimeMillis())
        return Session(session.accessToken,id,session.expiresAt.toEpochMilliseconds())
    }
    suspend fun signOut()=mutex.withLock {
        initialize()
        sdk?.auth?.currentSessionOrNull()?.let {session ->
            withContext(NonCancellable+Dispatchers.IO){vault.saveLogout(storage!!.encode(session))}
        }
        forget();mutable.value=mutable.value.copy(pendingLogout=withContext(Dispatchers.IO){vault.loadLogout()!=null},message="Signed out on this phone · sharing is off")
        if(configured)flushLogout()
    }
    private suspend fun flushLogout():Boolean {
        val bytes=withContext(Dispatchers.IO){vault.loadLogout()}?:run{mutable.value=mutable.value.copy(pendingLogout=false);return true}
        forget();mutable.value=mutable.value.copy(pendingLogout=true)
        try {
            var session=storage!!.decode(bytes)
            if(session.expiresAt.toEpochMilliseconds()<=System.currentTimeMillis()+10_000) {
                session=sdk!!.auth.refreshSession(session.refreshToken)
                withContext(NonCancellable+Dispatchers.IO){vault.saveLogout(storage!!.encode(session))}
            }
            request("sign-out",bearer=session.accessToken)
        }catch(e:CancellationException){throw e}
        catch(e:Exception){
            val denied=(e is AuthFailure && e.status in setOf(400,401,403)) || (e is io.github.jan.supabase.exceptions.RestException && e.statusCode in setOf(400,401,403))
            if(!denied){mutable.value=mutable.value.copy(message="Signed out on this phone · reconnect to finish server sign-out");return false}
        }
        withContext(NonCancellable+Dispatchers.IO){vault.clearLogout()}
        mutable.value=mutable.value.copy(pendingLogout=false,message="Signed out · sharing is off");return true
    }
    suspend fun rejected(session:Session)=mutex.withLock {
        if(sdk?.auth?.currentSessionOrNull()?.accessToken==session.token){forget();mutable.value=mutable.value.copy(message="Session ended · sign in again")}
    }
    private suspend fun forget(){sdk?.auth?.clearSession();mutable.value=mutable.value.copy(userId=null,name="")}
    private fun publish(message:String){
        val s=sdk!!.auth.currentSessionOrNull()?:return
        val user=s.user?:return
        val name=listOf("display_name","full_name","name").firstNotNullOfOrNull {user.userMetadata?.get(it)?.jsonPrimitive?.content?.takeIf(String::isNotBlank)}?.take(80)?:"Driver"
        mutable.value=mutable.value.copy(userId=user.id,name=name,message=message,pendingLogout=false,ready=true)
    }
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
                    val source=it.body.source();val buffer=okio.Buffer()
                    while(buffer.size<=16384){if(source.read(buffer,4096)==-1L)break};require(buffer.size<=16384)
                    if(!it.isSuccessful)throw AuthFailure(it.code)
                    JSONObject(buffer.readUtf8())
                };if(continuation.isActive)continuation.resume(value)}
                catch(e:Exception){if(continuation.isActive)continuation.resumeWithException(e)}
            }
        })
    }
}
