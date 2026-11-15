package org.projectdrive.app.core.auth

import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.CodeVerifierCache
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.json.JSONObject

/** SDK storage adapter: credentials and the short-lived PKCE verifier stay encrypted. */
internal class SupabaseVault(private val vault:AuthCredentialStore,private val project:String):SessionManager,CodeVerifierCache {
    private val json=Json{ignoreUnknownKeys=true}
    private var prepared=CompletableDeferred<Unit>()
    fun prepare():Deferred<Unit>{prepared=CompletableDeferred();return prepared}
    fun encode(session:UserSession):ByteArray=JSONObject().put("version",2).put("project",project)
        .put("session",json.encodeToString(UserSession.serializer(),session.copy(providerToken=null,providerRefreshToken=null))).toString().toByteArray()
    fun decode(bytes:ByteArray):UserSession {
        val value=JSONObject(bytes.toString(Charsets.UTF_8));require(value.getInt("version")==2 && value.getString("project")==project)
        return json.decodeFromString(UserSession.serializer(),value.getString("session"))
    }
    override suspend fun saveSession(session:UserSession)=withContext(Dispatchers.IO){vault.saveAccount(encode(session))}
    override suspend fun loadSession():UserSession=withContext(Dispatchers.IO){vault.loadAccount()?.let(::decode)?:throw NoSessionFoundException()}
    override suspend fun deleteSession()=withContext(NonCancellable+Dispatchers.IO){vault.clearAccount()}
    override suspend fun saveCodeVerifier(codeVerifier:String) {
        try {
            withContext(Dispatchers.IO){vault.savePkce(JSONObject().put("project",project).put("created",System.currentTimeMillis()).put("verifier",codeVerifier).toString().toByteArray())}
            prepared.complete(Unit)
        }catch(e:CancellationException){prepared.cancel(e);throw e}
        catch(e:Exception){prepared.completeExceptionally(e);throw e}
    }
    override suspend fun loadCodeVerifier():String?=withContext(Dispatchers.IO){
        vault.loadPkce()?.let{bytes->
            val value=JSONObject(bytes.toString(Charsets.UTF_8))
            if(value.getString("project")!=project || System.currentTimeMillis()-value.getLong("created") !in 0..600_000){vault.clearPkce();null}
            else value.getString("verifier").also{require(it.length in 43..128)}
        }
    }
    override suspend fun deleteCodeVerifier()=withContext(NonCancellable+Dispatchers.IO){vault.clearPkce()}
}
