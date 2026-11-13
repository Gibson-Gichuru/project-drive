package org.projectdrive.app.feature.convoy

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.projectdrive.app.core.convoy.*
import org.projectdrive.app.core.database.*
import org.projectdrive.app.core.location.LocationFix
import org.projectdrive.app.core.navigation.Coordinate
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlin.random.Random

data class ConvoyState(
    val signedIn: Boolean = false, val userId: String = "", val trip: Trip? = null,
    val members: List<Member> = emptyList(), val participants: List<Participant> = emptyList(),
    val sharing: Boolean = false, val connected: Boolean = false, val busy: Boolean = false,
    val pendingStop: Boolean = false, val message: String = "Import a local test session to use convoys",
    val invitation: Invitation? = null, val now: Long = 0, val snapshotGeneration: Long = 0,
)

@HiltViewModel
class ConvoyViewModel @Inject constructor(
    private val api: TripRepository, private val vault: SessionVault, private val dao: ConvoyDao,
    private val connection: SocketTrackingConnection,
) : ViewModel() {
    private val mutable = MutableStateFlow(ConvoyState())
    val state = mutable.asStateFlow()
    private var session: Session? = null
    private val mutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var streamJob: Job? = null
    private var desired = false
    private var foreground = false
    private var generation = 0L
    private var lastSent = Long.MIN_VALUE
    private var lastFix = Long.MIN_VALUE
    private val decoder = StreamDecoder()
    private var createKey: String? = null
    private var createBody: String? = null
    private var inviteKey: String? = null

    init {
        viewModelScope.launch {
            session = withContext(Dispatchers.IO) { vault.load() }
            session?.let { mutable.update { s -> s.copy(signedIn = true, userId = it.userId, message = "Sharing off · local test session") } }
            mutex.withLock {
                revokePending()
                session?.let { current -> dao.selected(current.userId)?.let { saved -> runCatching { loadTrip(saved.tripId) } } }
            }
            while (true) {
                delay(1_000)
                val now = SystemClock.elapsedRealtime()
                mutable.update { it.copy(now = now, participants = decoder.visible(now)) }
                if (desired && (session?.expiresAtMillis ?: 0) <= System.currentTimeMillis()) stopSharing("Session expired · import a new session")
                if (!desired && mutable.value.pendingStop && foreground) mutex.withLock { revokePending() }
            }
        }
    }
    fun resume() { foreground = true }
    fun pause() { foreground = false; stopSharing("Sharing off · app left the foreground") }
    fun importSession(bytes: ByteArray) = action {
        check(!desired)
        val candidate = vault.parse(bytes)
        if (!revokePending() && candidate.userId != session?.userId) error("Refresh the current identity to finish pending revocation")
        val next = withContext(Dispatchers.IO) { vault.save(bytes) }
        session = next; dao.clearSelection(); decoder.clear()
        mutable.value = ConvoyState(signedIn = true, userId = next.userId, busy = true, message = "Sharing off · local test session")
        revokePending()
    }
    private fun current() = session?.takeIf { it.expiresAtMillis > System.currentTimeMillis() } ?: error("Import a fresh local test session")
    fun create(name: String, origin: Coordinate, destination: Coordinate) = action {
        require(name.isNotBlank() && name.length <= 120)
        require(mutable.value.trip == null) { "Close the current trip first" }
        val body = JSONObject().put("name", name.trim()).put("origin", point(origin)).put("destination", point(destination))
        // Preserve the key after uncertain responses; changed input starts a distinct intent.
        if (createBody != body.toString()) { createBody = body.toString(); createKey = UUID.randomUUID().toString() }
        val trip = api.trip(api.request(current(), "POST", "/v1/trips", body, createKey))
        select(trip); createKey = null; createBody = null
    }
    fun join(token: String) = action {
        require(mutable.value.trip == null)
        require(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        select(api.trip(api.request(current(), "POST", "/v1/trips/join", JSONObject().put("token", token))))
    }
    fun open(id: String) = action { require(mutable.value.trip == null); UUID.fromString(id); loadTrip(id) }
    fun refresh() = action { mutable.value.trip?.let { loadTrip(it.id) } }
    fun lifecycle(operation: String) {
        require(operation in setOf("start", "end", "cancel", "leave"))
        if (operation != "start") stopSharing()
        action {
            val trip = mutable.value.trip ?: return@action
            api.request(current(), "POST", "/v1/trips/${trip.id}/$operation")
            if (operation == "leave") { dao.clearSelection(); mutable.update { it.copy(trip = null, members = emptyList(), invitation = null) } }
            else loadTrip(trip.id)
        }
    }
    fun invite() = action {
        val trip = mutable.value.trip ?: return@action
        if (inviteKey == null) inviteKey = dao.invite(current().userId, trip.id)?.requestKey ?: UUID.randomUUID().toString()
        dao.saveInvite(SavedInvite(current().userId, trip.id, checkNotNull(inviteKey), null))
        try {
            val json = api.request(current(), "POST", "/v1/trips/${trip.id}/invites", JSONObject().put("expires_in_s", 900).put("max_uses", 10), inviteKey)
            dao.saveInvite(SavedInvite(current().userId, trip.id, checkNotNull(inviteKey), json.getString("id")))
            mutable.update { it.copy(invitation = Invitation(json.getString("id"), json.getString("token"), json.getString("expires_at")), message = "Private invite · expires in 15 minutes") }
            inviteKey = null
        } catch (e: ApiFailure) {
            if (e.code == "invite_already_created") { e.inviteId?.let { id -> dao.saveInvite(SavedInvite(current().userId, trip.id, checkNotNull(inviteKey), id)); mutable.update { it.copy(invitation = Invitation(id, "", "Secret unavailable · revoke before creating another")) } }; inviteKey = null; throw e }
            throw e
        }
    }
    fun revokeInvite() = action {
        val trip = mutable.value.trip ?: return@action
        val invite = mutable.value.invitation ?: return@action
        api.request(current(), "DELETE", "/v1/trips/${trip.id}/invites/${invite.id}")
        dao.clearInvite(current().userId, trip.id)
        mutable.update { it.copy(invitation = null, message = "Invitation revoked") }
    }
    fun remove(id: String) = action {
        UUID.fromString(id)
        val trip = mutable.value.trip ?: return@action
        api.request(current(), "DELETE", "/v1/trips/${trip.id}/members/$id"); loadTrip(trip.id)
    }
    fun closeTrip() {
        stopSharing()
        action { dao.clearSelection(); mutable.update { it.copy(trip = null, members = emptyList(), invitation = null) } }
    }
    fun startSharing() {
        if (!foreground || desired || mutable.value.busy) return
        desired = true; val epoch = ++generation
        action {
            check(foreground && desired && generation == epoch)
            check(revokePending()) { "Waiting for previous sharing to stop" }
            val user = current(); val trip = mutable.value.trip ?: error("Choose a trip")
            check(trip.status == "active") { "Start the trip first" }
            dao.remember(PendingStop(user.userId, trip.id)) // durable BEFORE the network request
            mutable.update { it.copy(pendingStop = true) }
            api.request(user, "PUT", "/v1/trips/${trip.id}/sharing", JSONObject().put("enabled", true))
            if (!foreground || !desired || epoch != generation) { revokePending(); return@action }
            mutable.update { it.copy(sharing = true, message = "Sharing on · connecting") }
            streamJob = viewModelScope.launch { stream(user, trip.id, epoch) }
        }
    }
    fun stopSharing(message: String = "Sharing off"): Job {
        desired = false; generation++; streamJob?.cancel(); connection.stop(); decoder.clear()
        lastSent = Long.MIN_VALUE; lastFix = Long.MIN_VALUE
        mutable.update { it.copy(sharing = false, connected = false, participants = emptyList(), invitation = it.invitation?.copy(token = ""), message = message) }
        return cleanupScope.launch {
            mutex.withLock {
                if (desired) return@withLock
                val done = revokePending()
                if (!done) mutable.update { it.copy(message = "$message · server cleanup pending; last position expires within 30 seconds") }
            }
        }
    }
    fun onFix(fix: LocationFix, permissionGranted: Boolean, playback: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!permissionGranted) { if (desired) stopSharing("Sharing off · location permission unavailable"); return }
        if (!foreground || !desired || !mutable.value.connected || playback || !fix.isFresh(now) || fix.accuracyMeters <= 0 || fix.accuracyMeters > 1000 ||
            fix.elapsedRealtimeMillis <= lastFix || (lastSent != Long.MIN_VALUE && now - lastSent < 1000)) return
        val recorded = Instant.ofEpochMilli(System.currentTimeMillis() - (now - fix.elapsedRealtimeMillis))
        val payload = point(fix.coordinate).put("accuracy_meters", fix.accuracyMeters.toDouble()).put("recorded_at", recorded.toString())
        if (connection.send(JSONObject().put("type", "position.update").put("version", 1).put("payload", payload).toString())) {
            lastSent = now; lastFix = fix.elapsedRealtimeMillis
        }
    }
    private suspend fun stream(user: Session, trip: String, epoch: Long) {
        var attempt = 0
        while (foreground && desired && generation == epoch) {
            decoder.clear(); mutable.update { it.copy(connected = false, participants = emptyList(), message = "Sharing on · reconnecting") }
            try {
                coroutineScope {
                    val deadline = launch { delay(10_000); if (!decoder.ready) connection.stop() }
                    try {
                        connection.events(user, trip).collect { text ->
                            if (!desired || generation != epoch || !foreground) return@collect
                            val kind = decoder.accept(text, SystemClock.elapsedRealtime())
                            if (decoder.ready) { deadline.cancel(); attempt = 0 }
                            mutable.update { it.copy(connected = decoder.ready, snapshotGeneration = it.snapshotGeneration + if (kind == "trip.snapshot") 1 else 0, participants = decoder.visible(SystemClock.elapsedRealtime()), message = "Sharing on · connected · foreground only") }
                        }
                    } finally { deadline.cancel() }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e !is ApiFailure || e.status in setOf(400, 401, 403, 404, 409, 410)) {
                    stopSharing("Sharing stopped · refresh membership or session")
                    refresh(); return
                }
            }
            mutable.update { it.copy(connected = false, message = "Disconnected · last known positions; reconnecting") }
            delay(retryDelayMillis(attempt++, Random.nextLong(501)))
        }
    }
    private suspend fun select(trip: Trip) {
        dao.select(SelectedTrip(userId = current().userId, tripId = trip.id))
        mutable.update { it.copy(trip = trip, invitation = null) }; loadTrip(trip.id)
    }
    private suspend fun loadTrip(id: String) {
        val user = current()
        val trip = api.trip(api.request(user, "GET", "/v1/trips/$id"))
        val list = api.request(user, "GET", "/v1/trips/$id/members").getJSONArray("members")
        require(list.length() <= 100)
        val members = (0 until list.length()).map { list.getJSONObject(it).let { m -> Member(m.getString("user_id"), m.getString("display_name"), m.getString("role") == "owner", m.getBoolean("sharing_enabled")) } }
        dao.select(SelectedTrip(userId = user.userId, tripId = trip.id))
        val savedInvite = dao.invite(user.userId, trip.id)
        inviteKey = savedInvite?.takeIf { it.inviteId == null }?.requestKey
        mutable.update { it.copy(trip = trip, members = members, invitation = it.invitation?.takeIf { old -> old.id == savedInvite?.inviteId } ?: savedInvite?.inviteId?.let { id -> Invitation(id, "", "Secret hidden · revoke to create another") }) }
        if (trip.status != "active" && desired) stopSharing("Sharing off · trip is ${trip.status}")
    }
    private suspend fun revokePending(): Boolean {
        val user = session ?: return true
        val pending = dao.pending(user.userId)
        for (stop in pending) {
            try { api.request(user, "PUT", "/v1/trips/${stop.tripId}/sharing", JSONObject().put("enabled", false)); dao.forget(stop) }
            catch (e: ApiFailure) { if (e.status == 404) dao.forget(stop) else { mutable.update { it.copy(pendingStop = true) }; return false } }
            catch (_: Exception) { mutable.update { it.copy(pendingStop = true) }; return false }
        }
        mutable.update { it.copy(pendingStop = false) }; return true
    }
    private fun action(block: suspend () -> Unit) { viewModelScope.launch {
        mutex.withLock {
            mutable.update { it.copy(busy = true) }
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.update { it.copy(message = when {
                    e is ApiFailure && e.status == 401 -> "Session expired or unauthorized · import a new session"
                    e is ApiFailure && e.status == 404 -> "Trip unavailable · membership may have been removed"
                    e is ApiFailure -> "Trip request refused (${e.code}) · refresh before retrying"
                    else -> "Request unavailable · check session, connection and input; retry safely"
                }) }
                if (desired && !mutable.value.sharing) stopSharing(mutable.value.message)
            } finally { mutable.update { it.copy(busy = false) } }
        }
    } }
    private fun point(p: Coordinate) = JSONObject().put("latitude", p.latitude).put("longitude", p.longitude)
    override fun onCleared() { stopSharing(); cleanupScope.launch { delay(15_000); cleanupScope.cancel() }; super.onCleared() }
}
