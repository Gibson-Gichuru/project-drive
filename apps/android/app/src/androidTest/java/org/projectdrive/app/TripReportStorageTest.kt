package org.projectdrive.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.projectdrive.app.core.database.ConvoyDatabase
import org.projectdrive.app.core.telemetry.*
import org.projectdrive.app.feature.telemetry.tripReportData
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TripReportStorageTest {
    @Test fun localChartSurvivesRecoveryButNeverEntersUploadSummary()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,ConvoyDatabase::class.java).build()
        try {
            val now=System.currentTimeMillis()
            val result=TelemetryProcessor(0,now-30_000).result(30_000).copy(speedBuckets=listOf(SpeedBucket(0,2,10.0,15.0),SpeedBucket(20,1,5.0,5.0)))
            val json=result.summary.toJSON().put("local_report",result.localReportJSON()).put("field_test",JSONObject().put("background_s",2))
            val original=json.toString()
            val record=LocalRecording(UUID.randomUUID().toString(),original,"[]",true,now+LOCAL_RETENTION_MILLIS,now+UPLOAD_WINDOW_MILLIS)
            val store=RoomTelemetryStore(db);store.save(record)
            val recovered=store.recover().single()
            val report=tripReportData(recovered)
            assertTrue(report.summary.interrupted);assertEquals(result.speedBuckets,report.speedBuckets)
            val upload=telemetryUploadSummary(recovered.summaryJSON)
            assertFalse(upload.has("local_report"));assertFalse(upload.has("field_test"))
            assertEquals(result.summary.copy(interrupted=true),summaryFromJSON(upload))
            assertTrue(JSONObject(recovered.summaryJSON).has("local_report"))
            assertFalse(recovered.summaryJSON.contains("latitude"));assertFalse(recovered.summaryJSON.contains("longitude"))
            store.acknowledge(record.id)
            assertEquals(result.speedBuckets,localSpeedBuckets(JSONObject(store.list().single().summaryJSON)))
            db.telemetry().save(store.list().single().copy(expiresAtMillis=now-1))
            assertTrue(store.list().isEmpty())
        } finally {db.close()}
    }
    @Test fun legacyAndInvalidChartFormatsNeverInventSpeedHistory() {
        val legacy=TelemetryProcessor(0,0).result(1000).summary.toJSON()
        assertTrue(localSpeedBuckets(legacy).isEmpty())
        val valid=JSONObject().put("start_s",0).put("samples",1).put("mean_mps",10).put("max_mps",12)
        val invalid=JSONObject().put("start_s",15).put("samples",1).put("mean_mps",80).put("max_mps",10)
        legacy.put("local_report",JSONObject().put("version",1).put("bucket_seconds",10).put("speed_buckets",JSONArray().put(valid).put(invalid).put(valid)))
        assertEquals(listOf(SpeedBucket(0,1,10.0,12.0)),localSpeedBuckets(legacy))
        legacy.getJSONObject("local_report").put("version",99)
        assertTrue(localSpeedBuckets(legacy).isEmpty())
    }
}
