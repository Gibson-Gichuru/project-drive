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
import org.projectdrive.app.core.navigation.Coordinate
import org.projectdrive.app.feature.convoy.ConvoyViewModel
import java.io.File

@RunWith(AndroidJUnit4::class)
class TripWorkflowTest {
    @Test fun nativeTripRepositoryAndViewModelWorkflow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.filesDir, "convoy-workflow-fixture.json")
        assumeTrue("No private local workflow fixture supplied", file.exists())
        val fixture = try { JSONObject(file.readText()) } finally { file.delete() }
        val vault = SessionVault(context)
        val ownerBytes = JSONObject().put("token", fixture.getString("owner_token")).toString().toByteArray()
        val memberBytes = JSONObject().put("token", fixture.getString("member_token")).toString().toByteArray()
        val owner = vault.parse(ownerBytes); val member = vault.parse(memberBytes)
        val api = HttpTripRepository(OkHttpClient())
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun waitFor(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 20000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            assertTrue("Native trip workflow condition timed out", condition())
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var model: ConvoyViewModel? = null
            scenario.onActivity { model = ViewModelProvider(it)[ConvoyViewModel::class.java] }
            val vm = checkNotNull(model)
            waitFor { vm.state.value.signedIn && !vm.state.value.pendingStop }
            main { vm.closeTrip() }; waitFor { vm.state.value.trip == null && !vm.state.value.busy }
            main { vm.importSession(ownerBytes) }; waitFor { vm.state.value.userId == owner.userId && !vm.state.value.busy }
            val origin = Coordinate(-1.2864,36.82); val destination = Coordinate(-1.3152,36.8148)
            main { vm.create("Native workflow acceptance", origin, destination) }
            waitFor { vm.state.value.trip?.status == "planned" && !vm.state.value.busy }
            val trip = checkNotNull(vm.state.value.trip)
            assertEquals(origin, trip.origin); assertEquals(destination, trip.destination)
            main { vm.invite() }; waitFor { vm.state.value.invitation?.token?.isNotEmpty() == true && !vm.state.value.busy }
            val originalInvite = checkNotNull(vm.state.value.invitation)
            // Simulate retry after the raw secret was lost: only its ID may be recovered.
            main { vm.invite() }; waitFor { !vm.state.value.busy && vm.state.value.invitation?.token == "" }
            assertEquals(originalInvite.id, vm.state.value.invitation?.id)
            main { vm.revokeInvite() }; waitFor { vm.state.value.invitation == null && !vm.state.value.busy }
            main { vm.invite() }; waitFor { vm.state.value.invitation?.token?.isNotEmpty() == true && !vm.state.value.busy }
            val code = checkNotNull(vm.state.value.invitation).token
            main { vm.closeTrip() }; waitFor { vm.state.value.trip == null && !vm.state.value.busy }
            main { vm.importSession(memberBytes) }; waitFor { vm.state.value.userId == member.userId && !vm.state.value.busy }
            main { vm.join(code) }; waitFor { vm.state.value.trip?.id == trip.id && !vm.state.value.busy }
            assertTrue(vm.state.value.members.any { it.id == member.userId && !it.owner && !it.sharing })
            main { vm.lifecycle("leave") }; waitFor { vm.state.value.trip == null && !vm.state.value.busy }
            main { vm.join(code) }; waitFor { vm.state.value.trip?.id == trip.id && !vm.state.value.busy }
            runBlocking { api.request(owner, "POST", "/v1/trips/${trip.id}/start") }
            main { vm.refresh() }; waitFor { vm.state.value.trip?.status == "active" && !vm.state.value.busy }
            assertFalse(vm.state.value.sharing)
            runBlocking { api.request(owner, "POST", "/v1/trips/${trip.id}/end") }
            main { vm.refresh() }; waitFor { vm.state.value.trip?.status == "completed" && !vm.state.value.busy }
            main { vm.closeTrip() }; waitFor { vm.state.value.trip == null && !vm.state.value.busy }
            main { vm.importSession(ownerBytes) }; waitFor { vm.state.value.userId == owner.userId && !vm.state.value.busy }
            main { vm.create("Native cancellation acceptance", origin, destination) }; waitFor { vm.state.value.trip?.status == "planned" && !vm.state.value.busy }
            main { vm.lifecycle("start") }; waitFor { vm.state.value.trip?.status == "active" && !vm.state.value.busy }
            main { vm.lifecycle("cancel") }; waitFor { vm.state.value.trip?.status == "cancelled" && !vm.state.value.busy }
            assertFalse(vm.state.value.sharing)
            main { vm.closeTrip() }; waitFor { vm.state.value.trip == null && !vm.state.value.busy }
        }
    }
}
