package org.projectdrive.app.core.convoy

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.buffer
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import org.projectdrive.app.BuildConfig
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

interface TrackingConnection {
    fun events(session: Session, trip: String): Flow<String>
    fun send(message: String): Boolean
    fun stop()
}
class SocketTrackingConnection @Inject constructor(private val client: OkHttpClient) : TrackingConnection {
    @Volatile private var socket: WebSocket? = null
    override fun events(session: Session, trip: String): Flow<String> = callbackFlow {
        UUID.fromString(trip)
        val request = Request.Builder().url(BuildConfig.API_SERVER_URL + "/v1/trips/$trip/stream")
            .header("Authorization", "Bearer ${session.token}").header("Sec-WebSocket-Protocol", "project-drive.v1").build()
        val connection = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.toByteArray(Charsets.UTF_8).size > 65536 || !trySend(text).isSuccess) {
                    webSocket.cancel(); close(ApiFailure(0, "stream_overflow"))
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { webSocket.cancel(); close(ApiFailure(400, "binary_message")) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null); close(ApiFailure(if (code == 1008) 403 else 0, "stream_closed"))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close(ApiFailure(0, "stream_closed")) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { close(ApiFailure(response?.code ?: 0, "connection_failed")) }
        })
        socket = connection
        awaitClose { connection.cancel(); if (socket === connection) socket = null }
    }.buffer(16)
    override fun send(message: String): Boolean {
        val current = socket ?: return false
        // Never queue a route replay or an unbounded stream of obsolete positions.
        if (current.queueSize() > 4096) { current.cancel(); return false }
        return current.send(message)
    }
    override fun stop() { socket?.cancel(); socket = null }
}

/** Server time is anchored to monotonic receipt, so local wall-clock changes cannot
 * keep a marker fresh. Freshness uses the earlier recorded/received timestamp. */
class StreamDecoder(private val roster: LiveRoster = LiveRoster()) {
    private var serverMillis = 0L
    private var anchorElapsed = 0L
    private var staleMillis = 10_000L
    private var ttlMillis = 30_000L
    var ready = false; private set
    fun clear() { roster.clear(); ready = false }
    fun accept(text: String, now: Long): String {
        val envelope = JSONObject(text); require(envelope.getInt("version") == 1)
        val type = envelope.getString("type")
        if (type == "trip.ended") throw ApiFailure(403, "access_revoked")
        if (type == "error") {
            val code = envelope.getJSONObject("payload").getString("code")
            throw ApiFailure(if (code in setOf("server_shutdown", "authorization_unavailable", "slow_consumer", "tracking_capacity", "rate_limited")) 0 else 403, code)
        }
        val server = envelope.getString("server_instance_id"); UUID.fromString(server)
        val seq = envelope.getLong("sequence"); require(seq >= 0)
        val payload = envelope.getJSONObject("payload")
        if (type == "trip.snapshot") {
            serverMillis = Instant.parse(payload.getString("server_time")).toEpochMilli(); anchorElapsed = now
            staleMillis = payload.getLong("stale_after_ms"); ttlMillis = payload.getLong("position_ttl_ms")
            require(staleMillis in 1..300_000 && ttlMillis in staleMillis..600_000)
            val list = payload.getJSONArray("participants"); require(list.length() <= 100)
            roster.snapshot(server, seq, (0 until list.length()).map { marker(list.getJSONObject(it), now) }); ready = true
        } else {
            check(ready) { "Snapshot required" }
            when (type) {
                "participant.position" -> roster.update(server, seq, marker(payload, now))
                "participant.left" -> roster.update(server, seq, removed = payload.getString("user_id"))
                "participant.joined" -> roster.update(server, seq)
                else -> error("Unsupported stream event")
            }
        }
        return type
    }
    fun visible(now: Long) = roster.visible(now)
    private fun marker(json: JSONObject, now: Long): Participant {
        val id = json.getString("user_id"); UUID.fromString(id)
        val coordinate = org.projectdrive.app.core.navigation.Coordinate(json.getDouble("latitude"), json.getDouble("longitude"))
        val accuracy = json.getDouble("accuracy_meters"); require(accuracy.isFinite() && accuracy > 0 && accuracy <= 1000)
        val recorded = Instant.parse(json.getString("recorded_at")).toEpochMilli()
        val received = Instant.parse(json.getString("received_at")).toEpochMilli()
        val expires = Instant.parse(json.getString("expires_at")).toEpochMilli()
        val estimatedServerNow = serverMillis + (now - anchorElapsed).coerceAtLeast(0)
        val effective = minOf(recorded, received)
        val age = (estimatedServerNow - effective).coerceAtLeast(0)
        val lifetime = minOf(ttlMillis, (expires - effective).coerceAtLeast(0))
        return Participant(id, coordinate, accuracy, now - age, staleMillis, lifetime)
    }
}
