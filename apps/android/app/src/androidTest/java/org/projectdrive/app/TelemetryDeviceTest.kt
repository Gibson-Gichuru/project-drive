package org.projectdrive.app

import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.projectdrive.app.core.convoy.*
import org.projectdrive.app.core.location.LocationFix
import org.projectdrive.app.core.navigation.Coordinate
import org.projectdrive.app.feature.telemetry.TelemetryViewModel
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

@RunWith(AndroidJUnit4::class)
class TelemetryDeviceTest {
    @Test fun captureSaveUploadAndArmProcessDeath() {
        assumeTrue("Synthetic recording is emulator-only",Build.HARDWARE in listOf("ranchu","goldfish"))
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val fixtureFile=File(context.filesDir,"telemetry-device-fixture.json");assumeTrue("No private local fixture",fixtureFile.exists())
        val fixture=try{JSONObject(fixtureFile.readText())}finally{fixtureFile.delete()}
        SessionVault(context).save(JSONObject().put("token",fixture.getString("token")).toString().toByteArray())
        val session=checkNotNull(SessionVault(context).load())
        val api=HttpTripRepository(OkHttpClient())
        val trip=runBlocking{api.trip(api.request(session,"GET","/v1/trips/${fixture.getString("trip_id")}"))}
        fun main(action:()->Unit)=instrumentation.runOnMainSync(action)
        fun waitFor(test:()->Boolean){val end=SystemClock.elapsedRealtime()+25000;while(!test()&&SystemClock.elapsedRealtime()<end)Thread.sleep(100);assertTrue("Telemetry acceptance condition timed out",test())}
        val scenario=ActivityScenario.launch(MainActivity::class.java);var model:TelemetryViewModel?=null
        scenario.onActivity{model=ViewModelProvider(it)[TelemetryViewModel::class.java]};val vm=checkNotNull(model)
        waitFor{!vm.state.value.saving}
        main{vm.start()};waitFor{vm.state.value.recording}
        val speeds=listOf(5f,9f,13f,13f,8f,3f,3f,7f,11f)
        for((i,speed)in speeds.withIndex()){
            Thread.sleep(1100)
            main{vm.onFix(LocationFix(Coordinate(-1.2864+i*0.0001,36.82),10f,SystemClock.elapsedRealtime(),speed,1f,0f,5f))}
        }
        scenario.recreate();waitFor{!vm.state.value.recording&&!vm.state.value.saving&&vm.state.value.recordings.isNotEmpty()}
        val recording=vm.state.value.recordings.first();val summary=JSONObject(recording.summaryJSON)
        assertTrue(summary.getBoolean("interrupted"));assertTrue(summary.getDouble("distance_m")>0);assertTrue(summary.getInt("speed_samples")>=9)
        assertFalse(recording.summaryJSON.contains("latitude"));assertFalse(recording.eventsJSON.contains("longitude"))
        main{vm.upload(recording,trip)};waitFor{!vm.state.value.uploading&&vm.state.value.recordings.any{it.id==recording.id&&it.uploaded}}
        assertEquals("[]",vm.state.value.recordings.single{it.id==recording.id}.eventsJSON)
        main{vm.remote(trip)};waitFor{vm.state.value.remoteCount!=null&&!vm.state.value.uploading};assertTrue(checkNotNull(vm.state.value.remoteCount)>0)
        // Leave a durable active checkpoint, then the host kills the real process.
        main{vm.start()};waitFor{vm.state.value.recording};Thread.sleep(6500)
        File(context.filesDir,"telemetry-restart-armed").writeText("armed")
        File(context.filesDir,"telemetry-device-result.json").writeText("{\"stage\":\"kill-ready\"}")
        Thread.sleep(60000);scenario.close();fail("Host did not perform controlled process death")
    }
    @Test fun recoverAfterRealProcessDeath() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val armed=File(context.filesDir,"telemetry-restart-armed");assumeTrue("No process-death test armed",armed.exists());armed.delete()
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            var model:TelemetryViewModel?=null;scenario.onActivity{model=ViewModelProvider(it)[TelemetryViewModel::class.java]};val vm=checkNotNull(model)
            val end=SystemClock.elapsedRealtime()+20000
            while(vm.state.value.saving&&SystemClock.elapsedRealtime()<end)Thread.sleep(100)
            assertFalse(vm.state.value.recording);assertFalse(vm.state.value.saving)
            assertTrue(vm.state.value.recordings.any{JSONObject(it.summaryJSON).getBoolean("interrupted")&&!it.active})
            for(recording in vm.state.value.recordings){instrumentation.runOnMainSync{vm.delete(recording)}}
        }
    }
}
