package org.projectdrive.app.core.convoy

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.projectdrive.app.core.auth.AuthFailure
import kotlin.random.Random

/** One consent-owned connection loop. The caller owns its job and renders its state.
 * No coordinates or credentials survive an attempt; every attempt needs a snapshot.
 */
internal class TrackingStream(
    private val connection: TrackingConnection,
    private val snapshotTimeoutMillis: Long = 10_000,
    private val now: () -> Long = System::currentTimeMillis,
    private val jitter: () -> Long = { Random.nextLong(501) },
) {
    suspend fun run(
        userId: String,
        trip: String,
        session: suspend () -> Session,
        connecting: () -> Unit,
        message: (String) -> Boolean,
        disconnected: (Long) -> Unit,
        wait: suspend (Long) -> Unit = { delay(it) },
    ) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            connecting()
            try {
                val user = session()
                // An account switch must never reuse another account's sharing consent.
                if (user.userId != userId || user.expiresAtMillis <= now()) throw ApiFailure(401, "session_changed")
                coroutineScope {
                    val deadline = launch {
                        delay(snapshotTimeoutMillis)
                        throw ApiFailure(0, "snapshot_timeout")
                    }
                    val renewal = launch {
                        delay((user.expiresAtMillis - now() - 45_000).coerceAtLeast(1_000))
                        throw ApiFailure(0, "session_renewal")
                    }
                    try {
                        connection.events(user, trip).collect { text ->
                            if (message(text)) { deadline.cancel(); attempt = 0 }
                        }
                    } finally { deadline.cancel(); renewal.cancel() }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (!retryableTrackingFailure(e)) throw e }
            // Clear the roster before waiting: disconnected participants are never live.
            val pause = retryDelayMillis(attempt, jitter())
            attempt = (attempt + 1).coerceAtMost(5)
            disconnected(pause)
            wait(pause)
        }
    }
}

/** Retry availability failures only. Protocol errors and rejected access fail closed. */
internal fun retryableTrackingFailure(error: Exception): Boolean = when (error) {
    is ApiFailure -> error.status == 0 || error.status in setOf(408, 425, 429) || error.status in 500..599
    is AuthFailure -> error.status in setOf(408, 425, 429) || error.status in 500..599
    is io.github.jan.supabase.exceptions.RestException -> error.statusCode in setOf(408, 425, 429) || error.statusCode in 500..599
    is IOException -> true
    else -> false
}
