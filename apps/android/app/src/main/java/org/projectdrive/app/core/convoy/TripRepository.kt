package org.projectdrive.app.core.convoy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.projectdrive.app.BuildConfig
import org.projectdrive.app.core.navigation.Coordinate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

class ApiFailure(val status: Int, val code: String, val inviteId: String? = null) : Exception("Trip request failed ($status)")
interface TripRepository {
    suspend fun request(session: Session, method: String, path: String, body: JSONObject? = null, key: String? = null): JSONObject
    fun trip(json: JSONObject): Trip
}
@Singleton
class HttpTripRepository @Inject constructor(private val client: OkHttpClient) : TripRepository {
    override suspend fun request(session: Session, method: String, path: String, body: JSONObject?, key: String?): JSONObject = withContext(Dispatchers.IO) {
        require(path.matches(Regex("/v1/trips(?:/[a-zA-Z0-9-]+){0,4}")))
        require(session.expiresAtMillis > System.currentTimeMillis()) { "Session expired" }
        val request = Request.Builder().url(BuildConfig.API_SERVER_URL + path)
            .header("Authorization", "Bearer ${session.token}").header("Cache-Control", "no-store")
        key?.let { UUID.fromString(it); request.header("Idempotency-Key", it) }
        request.method(method, if (method == "POST" || method == "PUT") (body?.toString() ?: "").toRequestBody("application/json".toMediaType()) else null)
        client.newCall(request.build()).execute().use { response ->
            val source = response.body?.source() ?: throw ApiFailure(response.code, "empty_response")
            val buffer = okio.Buffer()
            while (buffer.size <= 131072) { if (source.read(buffer, 8192) == -1L) break }
            require(buffer.size <= 131072) { "Response too large" }
            val json = JSONObject(buffer.readUtf8())
            if (!response.isSuccessful) throw ApiFailure(response.code, json.optString("code", "request_failed"), json.optString("invite_id").takeIf { it.isNotEmpty() })
            json
        }
    }
    override fun trip(json: JSONObject): Trip = Trip(json.getString("id"), json.getString("owner_id"), json.getString("name"), json.getString("status"), point(json.optJSONObject("origin")), point(json.optJSONObject("destination")))
    private fun point(json: JSONObject?) = json?.let { Coordinate(it.getDouble("latitude"), it.getDouble("longitude")) }
}
