package org.projectdrive.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.projectdrive.app.core.convoy.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Real Android OkHttp sockets with public synthetic points and in-memory credentials.
 * Does not access the owner's account, journal, location provider or production API.
 */
@RunWith(AndroidJUnit4::class)
class ConvoyRecoveryTest {
    private val trip = "00000000-0000-4000-8000-000000000010"
    private val peer = "00000000-0000-4000-8000-000000000011"
    private val instance = "00000000-0000-4000-8000-000000000012"
    private fun snapshot(populated: Boolean): String {
        val time = Instant.now()
        val markers = JSONArray()
        if (populated) markers.put(JSONObject().put("user_id", peer).put("latitude", -1.2864).put("longitude", 36.82)
            .put("accuracy_meters", 20).put("recorded_at", time).put("received_at", time).put("expires_at", time.plusSeconds(30)))
        return JSONObject().put("type", "trip.snapshot").put("version", 1).put("server_instance_id", instance).put("sequence", 0)
            .put("payload", JSONObject().put("server_time", time).put("stale_after_ms", 10000).put("position_ttl_ms", 30000).put("participants", markers)).toString()
    }
    private class Fixture(private val serve: (Int, Socket, List<String>) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val origin = "http://127.0.0.1:${server.localPort}"
        val error = AtomicReference<Throwable?>()
        private val worker = thread(name = "outset-convoy-fixture") {
            var attempt = 0
            while (!server.isClosed) try {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader()
                    val headers = mutableListOf<String>()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; headers += line }
                    serve(++attempt, socket, headers)
                }
            } catch (e: Throwable) { if (!server.isClosed) { error.set(e); break } }
        }
        override fun close() { server.close(); worker.join(4000) }
    }
    private fun upgrade(socket: Socket, headers: List<String>) {
        val key = headers.single { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(':').trim()
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
        socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\nSec-WebSocket-Protocol: project-drive.v1\r\n\r\n").toByteArray())
    }
    private fun frame(socket: Socket, text: String) {
        val data = text.toByteArray(); require(data.size <= 65535)
        val output = socket.getOutputStream(); output.write(0x81)
        if (data.size < 126) output.write(data.size) else { output.write(126); output.write(data.size shr 8); output.write(data.size and 255) }
        output.write(data); output.flush()
    }
    @Test fun transportRetriesAndFreshSnapshotRemovesOldParticipantBeforeRevocation() = runBlocking {
        var attempts = 0
        val fixture = Fixture { index, socket, headers ->
            attempts = index
            assertEquals("GET /v1/trips/$trip/stream HTTP/1.1", headers.first())
            assertTrue(headers.any { it == "Authorization: Bearer synthetic-$index" })
            if (index == 1) {
                socket.getOutputStream().write("HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } else {
                upgrade(socket, headers); frame(socket, snapshot(index == 2))
                if (index == 3) frame(socket, "{\"type\":\"error\",\"version\":1,\"payload\":{\"code\":\"sharing_disabled\"}}")
            }
        }
        val decoder = StreamDecoder(); val sizes = mutableListOf<Int>(); var credentials = 0; var clears = 0
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val connection = SocketTrackingConnection(client, fixture.origin)
        fixture.use {
            try {
                withTimeout(8000) {
                    TrackingStream(connection, jitter = { 0 }).run("self", trip,
                        session = { Session("synthetic-${++credentials}", "self", System.currentTimeMillis() + 600_000) },
                        connecting = { decoder.clear(); assertFalse(decoder.ready) },
                        message = { text -> decoder.accept(text, 100_000); sizes += decoder.visible(100_000).size; decoder.ready },
                        disconnected = { decoder.clear(); clears++; assertTrue(decoder.visible(100_000).isEmpty()) }, wait = {},
                    )
                }
                fail("revoked stream retried")
            } catch (e: ApiFailure) { assertEquals(403, e.status) }
            finally { connection.stop(); decoder.clear() }
        }
        fixture.error.get()?.let { throw AssertionError("Loopback fixture failed", it) }
        assertEquals(3, attempts); assertEquals(3, credentials); assertEquals(2, clears)
        assertEquals(listOf(1, 0), sizes); assertTrue(decoder.visible(100_000).isEmpty())
    }
    @Test fun socketHandshakeDenialNeverRetries() = runBlocking {
        var attempts = 0
        val fixture = Fixture { index, socket, _ ->
            attempts = index
            socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        }
        val connection = SocketTrackingConnection(OkHttpClient(), fixture.origin)
        fixture.use {
            try {
                withTimeout(5000) {
                    TrackingStream(connection).run("self", trip, { Session("synthetic", "self", System.currentTimeMillis() + 600_000) }, {}, { fail("denied stream sent data"); false }, { fail("denial retried") })
                }
                fail("handshake denial ignored")
            } catch (e: ApiFailure) { assertEquals(403, e.status) }
            finally { connection.stop() }
        }
        fixture.error.get()?.let { throw AssertionError("Loopback fixture failed", it) }
        assertEquals(1, attempts)
    }
}
