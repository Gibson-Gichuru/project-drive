package org.projectdrive.app.core.convoy

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.projectdrive.app.core.auth.AuthFailure

class TrackingStreamTest {
    private fun user(id: String = "a", token: String = "test") = Session(token, id, 1_000_000)
    private class Wire(val open: (Session) -> Flow<String>) : TrackingConnection {
        override fun events(session: Session, trip: String) = open(session)
        override fun send(message: String) = true
        override fun stop() = Unit
    }
    private class Finished : CancellationException()

    // JUnit is the blocking boundary; the production runner remains suspending.
    @Test fun offlineRefreshRetriesAndFreshSnapshotResetsBackoff() = runBlocking {
        var loads = 0; var sockets = 0; var connections = 0
        val waits = mutableListOf<Long>(); val tokens = mutableListOf<String>()
        val wire = Wire { session -> flow {
            tokens += session.token; sockets++
            if (sockets == 1) { emit("snapshot"); throw IOException("offline") }
            emit("snapshot"); throw Finished()
        } }
        try {
            TrackingStream(wire, now = { 0 }, jitter = { 0 }).run("a", "trip",
                session = { loads++; if (loads in setOf(1, 2, 4)) throw IOException("refresh offline"); user(token = "renewed-$loads") },
                connecting = { connections++ }, message = { it == "snapshot" },
                disconnected = { waits += it }, wait = {},
            )
            fail("runner swallowed cancellation")
        } catch (_: Finished) { }
        assertEquals(listOf(1000L, 2000L, 1000L, 2000L), waits)
        assertEquals(listOf("renewed-3", "renewed-5"), tokens)
        assertEquals(5, connections)
    }

    @Test fun removalAndProtocolFailuresNeverRetry() = runBlocking {
        for (error in listOf(ApiFailure(403, "removed"), ApiFailure(401, "revoked"), ApiFailure(404, "missing"), IllegalArgumentException("bad snapshot"))) {
            var attempts = 0
            val wire = Wire { flow { throw error } }
            try {
                TrackingStream(wire, now = { 0 }).run("a", "trip", { user() }, { attempts++ }, { true }, { fail("terminal access retried") })
                fail("terminal failure hidden")
            } catch (actual: Exception) {
                // Coroutine stack recovery may copy JVM exceptions.
                assertEquals(error.javaClass, actual.javaClass); assertEquals(error.message, actual.message)
            }
            assertEquals(1, attempts)
        }
    }

    @Test fun accountSwitchAndExpiredCredentialsNeverOpenSocket() = runBlocking {
        for (candidate in listOf(user("b"), Session("old", "a", 0))) {
            val wire = Wire { fail("unauthorized socket opened"); emptyFlow() }
            try {
                TrackingStream(wire, now = { 0 }).run("a", "trip", { candidate }, {}, { true }, { fail("identity failure retried") })
                fail("identity boundary ignored")
            } catch (error: ApiFailure) { assertEquals(401, error.status) }
        }
    }

    @Test fun snapshotDeadlineCancelsAttemptWithoutDependingOnSocketStop() = runBlocking {
        var attempts = 0; var disposed = false; var waits = 0
        val wire = Wire { flow { try { awaitCancellation() } finally { disposed = true } } }
        try {
            withTimeout(3000) {
                TrackingStream(wire, snapshotTimeoutMillis = 20, now = { 0 }, jitter = { 0 }).run("a", "trip", { user() }, { attempts++ }, { false }, {
                    assertTrue(disposed); assertEquals(1000L, it); waits++; throw Finished()
                })
            }
            fail("deadline did not terminate attempt")
        } catch (_: Finished) { }
        assertEquals(1, attempts); assertEquals(1, waits)
    }

    @Test fun cancellingBackoffCannotReconnectOrResumeConsent() = runBlocking {
        var attempts = 0; val waiting = CompletableDeferred<Unit>()
        val wire = Wire { flow { throw IOException("offline") } }
        val job = launch {
            TrackingStream(wire, now = { 0 }).run("a", "trip", { user() }, { attempts++ }, { true }, {}, wait = {
                waiting.complete(Unit); awaitCancellation()
            })
        }
        withTimeout(3000) { waiting.await() }
        job.cancelAndJoin()
        assertEquals(1, attempts)
    }

    @Test fun healthyStreamCancelsDeadlineAndCancellationDisposesTransport() = runBlocking {
        val ready = CompletableDeferred<Unit>(); var disposed = false
        val wire = Wire { flow { try { emit("snapshot"); ready.complete(Unit); awaitCancellation() } finally { disposed = true } } }
        val job = launch {
            TrackingStream(wire, snapshotTimeoutMillis = 20, now = { 0 }).run("a", "trip", { user() }, {}, { true }, { fail("healthy snapshot timed out") })
        }
        withTimeout(3000) { ready.await() }; delay(50)
        assertTrue(job.isActive); job.cancelAndJoin(); assertTrue(disposed)
    }

    @Test fun retryClassificationAndCloseCodesFailClosed() {
        for (status in listOf(0, 408, 425, 429, 500, 502, 503, 504)) assertTrue(retryableTrackingFailure(ApiFailure(status, "test")))
        for (status in listOf(400, 401, 403, 404, 409, 410, 422)) assertFalse(retryableTrackingFailure(ApiFailure(status, "test")))
        assertTrue(retryableTrackingFailure(IOException()))
        assertTrue(retryableTrackingFailure(AuthFailure(503)))
        assertFalse(retryableTrackingFailure(AuthFailure(401)))
        assertFalse(retryableTrackingFailure(IllegalStateException()))
        assertEquals(403, streamCloseStatus(1008))
        for (code in listOf(1002, 1003, 1007, 1009)) assertEquals(400, streamCloseStatus(code))
        assertEquals(0, streamCloseStatus(1012))
    }

    @Test fun renewalDisposesOldStreamAndLoadsNewCredential() = runBlocking {
        var loads = 0; var disposed = 0; val tokens = mutableListOf<String>()
        val wire = Wire { session -> flow {
            try {
                tokens += session.token; emit("snapshot")
                if (tokens.size == 2) throw Finished()
                awaitCancellation()
            } finally { disposed++ }
        } }
        try {
            withTimeout(4000) {
                TrackingStream(wire, now = { 0 }, jitter = { 0 }).run("a", "trip",
                    session = { loads++; Session("credential-$loads", "a", if (loads == 1) 45_001 else 1_000_000) },
                    connecting = {}, message = { true }, disconnected = { assertEquals(1, disposed) }, wait = {},
                )
            }
        } catch (_: Finished) { }
        assertEquals(listOf("credential-1", "credential-2"), tokens); assertEquals(2, disposed)
    }
}
