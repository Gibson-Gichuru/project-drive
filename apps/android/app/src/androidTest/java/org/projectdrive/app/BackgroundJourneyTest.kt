package org.projectdrive.app

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.projectdrive.app.core.journey.*
import org.projectdrive.app.feature.telemetry.TelemetryViewModel
import java.io.File

/** Explicit opt-in. Only task-created stationary records are removed. No GPS injection. */
@RunWith(AndroidJUnit4::class)
class BackgroundJourneyTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun optIn()=assumeTrue(InstrumentationRegistry.getArguments().getString("backgroundJourneyChecks")=="true")
    private fun emulator()=Build.HARDWARE in listOf("ranchu","goldfish")
    private fun await(message:String,condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+30000
        while(SystemClock.elapsedRealtime()<end){if(condition())return;SystemClock.sleep(100)}
        fail(message)
    }
    private fun main(action:()->Unit)=instrumentation.runOnMainSync(action)
    @Test fun recordingSurvivesBackgroundAndRotationThenNotificationStopsIt() {
        optIn();assumeTrue(emulator()||InstrumentationRegistry.getArguments().getString("stationaryPhoneRecording")=="true")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm:TelemetryViewModel;lateinit var runtime:JourneyRuntime
            scenario.onActivity {vm=ViewModelProvider(it)[TelemetryViewModel::class.java];runtime=it.runtime}
            assumeTrue(runtime.hasLocationPermission()&&runtime.notificationsAllowed())
            await("Local store did not initialize"){!vm.state.value.saving}
            val original=vm.state.value.recordings.map{it.id}.toSet()
            try {
                main {vm.start()}
                await("Recording service did not start"){runtime.serviceRunning.value&&vm.state.value.recording}
                val started=checkNotNull(vm.state.value.current).startedAtMillis
                scenario.moveToState(Lifecycle.State.CREATED)
                val initial=checkNotNull(vm.state.value.current).durationSeconds
                val screenOff=InstrumentationRegistry.getArguments().getString("screenOffCheck")=="true"
                if(screenOff)File(context.filesDir,"background-screen-ready").writeText("ready")
                await("Background recording stopped or its timer stalled"){vm.state.value.recording&&(vm.state.value.current?.durationSeconds?:0.0)>initial+if(screenOff)12 else 6}
                scenario.moveToState(Lifecycle.State.RESUMED);scenario.recreate()
                scenario.onActivity {vm=ViewModelProvider(it)[TelemetryViewModel::class.java]}
                assertTrue("Rotation stopped recording",vm.state.value.recording)
                assertTrue("Rotation started a different recording",vm.state.value.current?.startedAtMillis==started)
                val manager=context.getSystemService(NotificationManager::class.java)
                val notification=checkNotNull(manager.activeNotifications.firstOrNull{it.id==JourneyService.NOTIFICATION}).notification
                checkNotNull(notification.actions.firstOrNull{it.title.toString()=="Stop journey"}).actionIntent.send()
                await("Notification Stop did not finish/save and release the service"){!vm.state.value.recording&&!vm.state.value.saving&&!runtime.serviceRunning.value}
                val created=vm.state.value.recordings.filter{it.id !in original}
                assertEquals("Stationary test must create exactly one summary",1,created.size)
                val json=JSONObject(created.single().summaryJSON);val field=json.getJSONObject("field_test")
                assertFalse("A normal background/rotation session was marked interrupted",json.getBoolean("interrupted"))
                assertTrue("Background duration was not retained",field.getDouble("background_s")>=5)
                if(screenOff)assertTrue("Screen-off time was not retained",field.getDouble("screen_off_s")>=2)
                assertTrue(field.has("battery_start_percent"));assertTrue(field.has("charging_s"))
                assertFalse(created.single().summaryJSON.contains("latitude"));assertFalse(created.single().eventsJSON.contains("longitude"))
                assertFalse(manager.activeNotifications.any{it.id==JourneyService.NOTIFICATION})
            } finally {
                File(context.filesDir,"background-screen-ready").delete()
                main {runtime.stopAll("Stationary check finished")}
                await("Stationary test cleanup did not finish"){!vm.state.value.recording&&!vm.state.value.saving}
                for(record in vm.state.value.recordings.filter{it.id !in original})main {vm.delete(record)}
                await("Task-created stationary summary was not removed"){vm.state.value.recordings.all{it.id in original}}
            }
        }
    }
    @Test fun guidanceSurvivesBackgroundAndActivityReplacement() {
        optIn();assumeTrue(emulator())
        var runtime:JourneyRuntime?=null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model:NavigationViewModel
                scenario.onActivity {runtime=it.runtime;model=ViewModelProvider(it)[NavigationViewModel::class.java]}
                assumeTrue(checkNotNull(runtime).hasLocationPermission()&&checkNotNull(runtime).notificationsAllowed())
                main {model.setOrigin("-1.2864, 36.82");model.setDestination("-1.3152, 36.8148");model.previewRoute()}
                await("Public fixture route was unavailable"){model.state.value.route!=null}
                // Emulator ViewModel fixture only, never a phone/provider injection or field proof.
                main {model.requestLocation(false);model.onLocation(org.projectdrive.app.core.location.LocationFix(org.projectdrive.app.core.navigation.Coordinate(-1.2864,36.82),5f,SystemClock.elapsedRealtime()));model.startGuidance()}
                await("Guidance service did not start"){model.state.value.guiding&&checkNotNull(runtime).serviceRunning.value}
                scenario.moveToState(Lifecycle.State.CREATED)
                SystemClock.sleep(2500)
                assertTrue("Backgrounding stopped active guidance",model.state.value.guiding)
            }
            // Destroy the actual Activity and its ViewModelStore while the service stays active.
            ActivityScenario.launch(MainActivity::class.java).use { reopened ->
                reopened.onActivity {
                    assertTrue("Activity replacement lost the service-owned navigation session",ViewModelProvider(it)[NavigationViewModel::class.java].state.value.guiding)
                }
                context.getSystemService(NotificationManager::class.java).activeNotifications.first{it.id==JourneyService.NOTIFICATION}.notification.actions.first{it.title.toString()=="Stop journey"}.actionIntent.send()
                await("Notification did not stop guidance"){!checkNotNull(runtime).serviceRunning.value}
            }
        }finally{main{runtime?.stopAll("Guidance test cleanup")}}
    }
    @Test fun notificationDenialDoesNotStartHiddenRecording() {
        optIn();assumeTrue(emulator())
        assumeTrue(Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                val vm=ViewModelProvider(it)[TelemetryViewModel::class.java]
                vm.start();assertFalse(vm.state.value.recording);assertFalse(it.runtime.serviceRunning.value)
            }
        }
    }
    @Test fun armCheckpointForHostProcessTermination() {
        optIn();assumeTrue(emulator())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm:TelemetryViewModel
            scenario.onActivity {vm=ViewModelProvider(it)[TelemetryViewModel::class.java]}
            await("Local store did not initialize"){!vm.state.value.saving}
            val original=vm.state.value.recordings.map{it.id}.toSet()
            main {vm.start()};await("Could not arm background recording"){vm.state.value.recording}
            scenario.moveToState(Lifecycle.State.CREATED)
            await("Checkpoint timer stalled"){(vm.state.value.current?.durationSeconds?:0.0)>=7}
            File(context.filesDir,"background-test-original.json").writeText(org.json.JSONArray(original.toList()).toString())
            File(context.filesDir,"background-test-armed").writeText("ready")
            // The host explicitly terminates this disposable emulator process.
            await("Host did not terminate the armed emulator process"){false}
        }
    }
    @Test fun terminatedSessionRecoversWithoutAutomaticallyRestarting() {
        optIn();assumeTrue(emulator())
        val marker=File(context.filesDir,"background-test-original.json");assumeTrue(marker.exists())
        val originalJSON=org.json.JSONArray(marker.readText());marker.delete()
        val original=(0 until originalJSON.length()).map{originalJSON.getString(it)}.toSet()
        File(context.filesDir,"background-test-armed").delete()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm:TelemetryViewModel
            scenario.onActivity {vm=ViewModelProvider(it)[TelemetryViewModel::class.java];assertFalse(it.runtime.serviceRunning.value)}
            await("Checkpoint recovery did not complete"){!vm.state.value.saving}
            assertFalse(vm.state.value.recording)
            val created=vm.state.value.recordings.filter{it.id !in original}
            assertEquals(1,created.size)
            assertFalse(created.single().active)
            val summary=JSONObject(created.single().summaryJSON)
            assertTrue(summary.getBoolean("interrupted"));assertTrue(summary.has("field_test"))
            for(record in created)main{vm.delete(record)}
            await("Recovery test summary was not removed"){vm.state.value.recordings.all{it.id in original}}
        }
    }
}
