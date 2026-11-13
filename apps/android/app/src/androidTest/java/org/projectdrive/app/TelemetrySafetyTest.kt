package org.projectdrive.app

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.projectdrive.app.core.database.*
import org.projectdrive.app.core.telemetry.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TelemetrySafetyTest {
    @Test fun roomMigrationPreservesConsentJournalAndSelectedTrip()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val name="telemetry-migration-test.db";context.deleteDatabase(name)
        val schema=JSONObject(instrumentation.context.assets.open("org.projectdrive.app.core.database.ConvoyDatabase/1.json").bufferedReader().use{it.readText()}).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply{parentFile?.mkdirs()},null).use { old ->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()){val entity=entities.getJSONObject(i);old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))}
            val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length())old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO pending_stops VALUES('test-user','test-trip')")
            old.execSQL("INSERT INTO selected_trip VALUES(1,'test-user','test-trip')")
            old.version=1
        }
        val db=Room.databaseBuilder(context,ConvoyDatabase::class.java,name).addMigrations(TELEMETRY_MIGRATION).build()
        assertEquals(listOf(PendingStop("test-user","test-trip")),db.convoy().pending("test-user"));assertEquals("test-trip",db.convoy().selected("test-user")?.tripId)
        assertTrue(db.telemetry().recordings().isEmpty());db.close();context.deleteDatabase(name);Unit
    }
    @Test fun recoveryExpiryBoundsAndUploadBindingAreDurable()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val name="telemetry-store-test.db";context.deleteDatabase(name)
        val db=Room.databaseBuilder(context,ConvoyDatabase::class.java,name).build();val store=RoomTelemetryStore(db)
        val now=System.currentTimeMillis();val result=TelemetryProcessor(0,now-1000).result(1000)
        fun record(id:String=UUID.randomUUID().toString())=LocalRecording(id,result.summary.toJSON().toString(),result.eventsJSON().toString(),true,now+LOCAL_RETENTION_MILLIS,now+UPLOAD_WINDOW_MILLIS)
        val first=record();store.save(first)
        val restored=store.recover().single();assertFalse(restored.active);assertTrue(JSONObject(restored.summaryJSON).getBoolean("interrupted"))
        assertFalse(restored.summaryJSON.contains("latitude"));assertFalse(restored.eventsJSON.contains("longitude"))
        store.bind(first.id,"trip-a","user-a")
        try{store.bind(first.id,"trip-b","user-a");fail("uncertain upload moved trips")}catch(_:IllegalStateException){}
        store.acknowledge(first.id);assertEquals("[]",store.list().single().eventsJSON)
        for(i in 1..19)store.save(record())
        try{store.save(record());fail("unbounded local recordings")}catch(_:IllegalStateException){}
        for(r in db.telemetry().recordings())db.telemetry().save(r.copy(expiresAtMillis=now-1))
        assertTrue(store.list().isEmpty())
        val expiredDetails=record().copy(active=false,uploadDeadlineMillis=now-1,eventsJSON="[{\"synthetic\":true}]")
        store.save(expiredDetails);assertEquals("[]",store.list().single().eventsJSON)
        db.close();context.deleteDatabase(name);Unit
    }
    @Test fun supportedSensorsCanStartAndStopWithoutGPSCollection() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=AndroidTelemetrySource(context);val count=java.util.concurrent.atomic.AtomicInteger()
        var sensors=emptySet<String>();val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { sensors=source.start{count.incrementAndGet()} };Thread.sleep(1500);instrumentation.runOnMainSync { source.stop() }
        val stopped=count.get();Thread.sleep(200);assertEquals(stopped,count.get())
        if(sensors.isNotEmpty())assertTrue("Advertised sensors produced no callbacks",stopped>0)
    }
}
