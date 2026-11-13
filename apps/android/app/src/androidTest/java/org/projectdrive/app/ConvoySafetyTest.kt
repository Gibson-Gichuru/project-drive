package org.projectdrive.app

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.projectdrive.app.core.convoy.*
import org.projectdrive.app.core.database.*
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ConvoySafetyTest {
    @Test fun snapshotOrderingFreshnessAndRestartAreSafe() {
        val decoder = StreamDecoder()
        val time = Instant.parse("2026-11-13T12:00:00Z")
        val marker = JSONObject().put("user_id", "00000000-0000-4000-8000-000000000001")
            .put("latitude", -1.2864).put("longitude", 36.82).put("accuracy_meters", 20)
            .put("recorded_at", time.minusSeconds(8)).put("received_at", time.minusSeconds(2)).put("expires_at", time.plusSeconds(22))
        fun event(type: String, seq: Int, payload: JSONObject, instance: String = "00000000-0000-4000-8000-000000000010") = JSONObject().put("type", type).put("version", 1).put("sequence", seq).put("server_instance_id", instance).put("payload", payload).toString()
        val payload = JSONObject().put("participants", org.json.JSONArray().put(marker)).put("server_time", time).put("stale_after_ms", 10000).put("position_ttl_ms", 30000)
        decoder.accept(event("trip.snapshot", 8, payload), 100_000)
        assertFalse(decoder.visible(101_999).single().stale(101_999))
        assertTrue(decoder.visible(102_000).single().stale(102_000))
        decoder.accept(event("participant.left", 7, JSONObject().put("user_id", marker.getString("user_id"))), 102_001)
        assertEquals(1, decoder.visible(102_001).size)
        assertTrue(decoder.visible(122_000).isEmpty())
        decoder.clear(); assertFalse(decoder.ready)
        try { decoder.accept(event("participant.position", 9, marker), 123_000); fail("accepted event before snapshot") } catch (_: IllegalStateException) { }
        decoder.accept(event("trip.snapshot", 0, payload.put("participants", org.json.JSONArray())), 124_000)
        assertTrue(decoder.visible(124_000).isEmpty())
    }
    @Test fun gracefulServerRestartRetriesButRevocationDoesNot() {
        val decoder = StreamDecoder()
        for ((code, status) in listOf("server_shutdown" to 0, "authorization_unavailable" to 0, "sharing_disabled" to 403, "expired_token" to 403)) {
            val event = JSONObject().put("type", "error").put("version", 1).put("payload", JSONObject().put("code", code))
            try { decoder.accept(event.toString(), 1000); fail("terminal control ignored") } catch (e: ApiFailure) { assertEquals(status, e.status) }
        }
    }
    @Test fun pendingStopJournalPersistsWithoutCoordinatesOrCredentials() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "convoy-safety-test.db"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, ConvoyDatabase::class.java, name).build()
        val stop = PendingStop("test-user", "test-trip")
        db.convoy().remember(stop); db.close()
        db = Room.databaseBuilder(context, ConvoyDatabase::class.java, name).build()
        assertEquals(listOf(stop), db.convoy().pending("test-user"))
        db.convoy().forget(stop); assertTrue(db.convoy().pending("test-user").isEmpty())
        db.close(); context.deleteDatabase(name); Unit
    }
    @Test fun credentialsAreEncryptedAndRejectInvalidImports() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.cacheDir, "convoy-vault-test").apply { mkdirs() }
        val isolated = object : android.content.ContextWrapper(context) {
            override fun getNoBackupFilesDir() = directory
        }
        val vault = SessionVault(isolated)
        val claims = JSONObject().put("sub", "00000000-0000-4000-8000-000000000099").put("exp", System.currentTimeMillis() / 1000 + 600)
        val encoded = android.util.Base64.encodeToString(claims.toString().toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        // Local parsing is not authentication. This deliberately unsigned test token
        // never leaves the test; the actual API requires a verified Ed25519 signature.
        val token = "e30.$encoded.dGVzdA"
        try {
            vault.save(JSONObject().put("token", token).toString().toByteArray())
            val encrypted = java.io.File(directory, "convoy-session.aes")
            val before = encrypted.readBytes()
            assertFalse(before.toString(Charsets.UTF_8).contains(token))
            assertEquals(claims.getString("sub"), vault.load()?.userId)
            try { vault.save("{\"token\":\"invalid\"}".toByteArray()); fail("invalid bearer accepted") } catch (_: IllegalArgumentException) { }
            assertArrayEquals(before, encrypted.readBytes())
            before[before.lastIndex] = (before.last().toInt() xor 1).toByte()
            encrypted.writeBytes(before)
            assertNull(vault.load())
        } finally { directory.deleteRecursively() }
    }
}
