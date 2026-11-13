package org.projectdrive.app

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.projectdrive.app.core.convoy.*
import org.projectdrive.app.core.database.*
import org.projectdrive.app.feature.convoy.ConvoyViewModel
import org.projectdrive.app.core.location.LocationFix
import org.projectdrive.app.core.navigation.Coordinate
import androidx.room.Room
import java.io.File

/** Opt-in device acceptance harness. Credentials arrive over adb stdin into app-private
 * storage, never arguments, logs or APK assets. Only the emulator publisher injects a
 * fixed PUBLIC synthetic point directly into this test boundary, never a GPS provider. */
@RunWith(AndroidJUnit4::class)
class ConvoyDeviceTest {
    @Test fun processRestartRevokesWithoutResuming() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val armed = File(context.filesDir, "convoy-restart-armed")
        assumeTrue("No controlled process-death test armed", armed.exists())
        val trip = armed.readText(); armed.delete()
        val session = checkNotNull(SessionVault(context).load())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var model: ConvoyViewModel? = null
            scenario.onActivity { model = ViewModelProvider(it)[ConvoyViewModel::class.java] }
            val vm = checkNotNull(model)
            val deadline = SystemClock.elapsedRealtime() + 25000
            while ((vm.state.value.trip?.id != trip || vm.state.value.pendingStop) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            assertEquals(trip, vm.state.value.trip?.id)
            assertFalse(vm.state.value.sharing); assertFalse(vm.state.value.pendingStop); assertTrue(vm.state.value.participants.isEmpty())
            runBlocking {
                val members = HttpTripRepository(OkHttpClient()).request(session, "GET", "/v1/trips/$trip/members").getJSONArray("members")
                val self = (0 until members.length()).map { members.getJSONObject(it) }.single { it.getString("user_id") == session.userId }
                assertFalse(self.getBoolean("sharing_enabled"))
            }
            File(context.filesDir, "convoy-device-result.json").writeText(JSONObject().put("stage", "restart-safe").toString())
        }
    }

    @Test fun controlledConvoySession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixtureFile = File(context.filesDir, "convoy-device-fixture.json")
        assumeTrue("No private local acceptance fixture supplied", fixtureFile.exists())
        val fixture = try { JSONObject(fixtureFile.readText()) } finally { fixtureFile.delete() }
        val session = SessionVault(context).save(JSONObject().put("token", fixture.getString("token")).toString().toByteArray())
        val tripId = fixture.getString("trip_id")
        val role = fixture.getString("role")
        val result = File(context.filesDir, "convoy-device-result.json")
        val commandFile = File(context.filesDir, "convoy-device-command.json")
        result.delete(); commandFile.delete()
        fun report(stage: String) { result.writeText(JSONObject().put("stage", stage).toString()) }
        val api = HttpTripRepository(OkHttpClient.Builder().followRedirects(false).build())
        if (role == "outsider") {
            runBlocking {
                try { api.request(session, "GET", "/v1/trips/$tripId"); fail("outsider read trip") } catch (e: ApiFailure) { assertEquals(404, e.status) }
                try { api.request(session, "PUT", "/v1/trips/$tripId/sharing", JSONObject().put("enabled", true)); fail("outsider enabled sharing") } catch (e: ApiFailure) { assertEquals(404, e.status) }
            }
            report("unauthorized-denied"); return
        }
        val db = Room.databaseBuilder(context, ConvoyDatabase::class.java, "convoy.db").addMigrations(TELEMETRY_MIGRATION).build()
        runBlocking { db.convoy().select(SelectedTrip(userId = session.userId, tripId = tripId)) }
        db.close()
        var model: ConvoyViewModel? = null
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { model = ViewModelProvider(it)[ConvoyViewModel::class.java] }
        val vm = checkNotNull(model)
        fun main(action: () -> Unit) { instrumentation.runOnMainSync(action) }
        fun waitFor(timeout: Long = 20000, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            assertTrue("Device acceptance condition timed out", condition())
        }
        try {
            waitFor { vm.state.value.trip?.id == tripId && !vm.state.value.busy }
            assertFalse(vm.state.value.sharing)
            main { vm.startSharing() }
            waitFor { vm.state.value.connected }
            report("connected")
            var previous = ""
            val deadline = SystemClock.elapsedRealtime() + 240000
            var publishing = role == "publisher"
            var seen = false
            while (SystemClock.elapsedRealtime() < deadline) {
                if (publishing) {
                    // Test-only synthetic public landmark. Never invoked by production UI.
                    main { vm.onFix(LocationFix(if (fixture.optInt("point_index", 0) == 1) Coordinate(-1.2864, 36.82) else Coordinate(-1.3152, 36.8148), 12f, SystemClock.elapsedRealtime()), permissionGranted = true, playback = false) }
                }
                if (role == "observer" && vm.state.value.participants.any { it.id == fixture.getString("peer_id") }) {
                    if (!seen) { seen = true; report("peer-visible") }
                }
                val command = runCatching { JSONObject(commandFile.readText()).getString("command") }.getOrDefault("")
                if (command != previous && command.isNotEmpty()) {
                    previous = command
                    when (command) {
                        "checkpoint" -> report("checkpoint")
                        "assert-disconnected" -> { waitFor(25000) { !vm.state.value.connected }; report("disconnected-confirmed") }
                        "prepare-restart" -> {
                            var stopping: kotlinx.coroutines.Job? = null
                            main { stopping = vm.stopSharing() }; runBlocking { stopping?.join() }
                            assertFalse(vm.state.value.pendingStop)
                            runBlocking {
                                val journal = Room.databaseBuilder(context, ConvoyDatabase::class.java, "convoy.db").addMigrations(TELEMETRY_MIGRATION).build()
                                journal.convoy().remember(PendingStop(session.userId, tripId)); journal.close()
                                api.request(session, "PUT", "/v1/trips/$tripId/sharing", JSONObject().put("enabled", true))
                            }
                            File(context.filesDir, "convoy-restart-armed").writeText(tripId)
                            report("restart-armed")
                        }
                        "pause-updates" -> { publishing = false; report("updates-paused") }
                        "assert-stale" -> { waitFor { vm.state.value.participants.any { it.id == fixture.getString("peer_id") && it.stale(vm.state.value.now) } }; report("stale-confirmed") }
                        "assert-expired" -> { waitFor(40000) { vm.state.value.participants.none { it.id == fixture.getString("peer_id") } }; report("expiry-confirmed") }
                        "resume-updates" -> { publishing = true; report("updates-resumed") }
                        "assert-reconnected" -> { waitFor(45000) { vm.state.value.connected && vm.state.value.participants.any { it.id == fixture.getString("peer_id") } }; report("reconnected") }
                        "playback-guard" -> {
                            publishing = false
                            main { vm.onFix(LocationFix(Coordinate(-1.3152, 36.8148), 12f, SystemClock.elapsedRealtime()), true, true) }
                            report("playback-blocked")
                        }
                        "stop" -> { publishing = false; main { vm.stopSharing() }; waitFor { !vm.state.value.sharing && !vm.state.value.pendingStop }; report("sharing-stopped") }
                        "assert-removed" -> { waitFor { !vm.state.value.sharing && vm.state.value.participants.isEmpty() }; report("removal-confirmed") }
                        "permission-denied" -> { publishing = false; main { vm.onFix(LocationFix(Coordinate(-1.2864,36.82),12f,SystemClock.elapsedRealtime()), false, false) }; waitFor { !vm.state.value.sharing }; report("permission-stopped") }
                        "restart-sharing" -> { main { vm.startSharing() }; waitFor { vm.state.value.connected }; report("connected-again") }
                        "background" -> { scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED); waitFor { !vm.state.value.sharing && vm.state.value.participants.isEmpty() }; scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED); assertFalse(vm.state.value.sharing); report("background-safe") }
                        "rotate" -> { scenario.recreate(); assertFalse(vm.state.value.sharing); report("rotation-safe") }
                        "finish" -> { main { vm.stopSharing() }; report("finished"); return }
                    }
                }
                Thread.sleep(1100)
            }
            fail("Host did not finish the controlled session")
        } finally { main { vm.stopSharing() }; scenario.close(); commandFile.delete() }
    }
}
