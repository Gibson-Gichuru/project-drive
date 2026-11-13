package org.projectdrive.app.core.convoy

import org.projectdrive.app.core.navigation.Coordinate

// The session is deliberately not a data class: generated toString must not expose a bearer.
class Session(val token: String, val userId: String, val expiresAtMillis: Long)
data class Trip(val id: String, val ownerId: String, val name: String, val status: String, val origin: Coordinate?, val destination: Coordinate?)
data class Member(val id: String, val name: String, val owner: Boolean, val sharing: Boolean)
data class Invitation(val id: String, val token: String, val expires: String)
data class Participant(val id: String, val coordinate: Coordinate, val accuracy: Double, val observedElapsed: Long, val staleAfterMillis: Long, val ttlMillis: Long) {
    fun age(now: Long) = (now - observedElapsed).coerceAtLeast(0)
    fun stale(now: Long) = age(now) >= staleAfterMillis
    fun expired(now: Long) = age(now) >= ttlMillis
}

/** An instance change is accepted only through a snapshot. Snapshot is a new baseline,
 * including when a server evicts an idle trip and restarts its sequence. */
class LiveRoster {
    private var instance: String? = null
    private var sequence = -1L
    private var values = emptyMap<String, Participant>()
    fun clear() { instance = null; sequence = -1; values = emptyMap() }
    fun snapshot(server: String, seq: Long, markers: List<Participant>) {
        require(seq >= 0 && markers.size <= 100)
        instance = server; sequence = seq; values = markers.associateBy { it.id }
    }
    fun update(server: String, seq: Long, marker: Participant? = null, removed: String? = null): Boolean {
        if (instance != server || seq <= sequence) return false
        sequence = seq
        values = when { removed != null -> values - removed; marker != null -> (values + (marker.id to marker)).entries.take(100).associate { it.toPair() }; else -> values }
        return true
    }
    fun visible(now: Long): List<Participant> {
        values = values.filterValues { !it.expired(now) }
        return values.values.toList()
    }
}
fun retryDelayMillis(attempt: Int, jitter: Long = 0) = ((1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L) + jitter.coerceIn(0, 500))
