package org.projectdrive.app.core.telemetry

import androidx.room.*
import org.json.JSONObject
import org.projectdrive.app.core.database.ConvoyDatabase
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "telemetry_recordings")
data class LocalRecording(
    @PrimaryKey val id: String, val summaryJSON: String, val eventsJSON: String,
    val active: Boolean, val expiresAtMillis: Long, val uploadDeadlineMillis: Long,
    val uploadTripId: String? = null, val uploadUserId: String? = null, val uploaded: Boolean = false,
)
@Dao
interface TelemetryDao {
    @Query("SELECT * FROM telemetry_recordings ORDER BY expiresAtMillis DESC LIMIT 20") suspend fun recordings(): List<LocalRecording>
    @Query("SELECT * FROM telemetry_recordings WHERE id=:id") suspend fun get(id: String): LocalRecording?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(record: LocalRecording)
    @Query("DELETE FROM telemetry_recordings WHERE id=:id AND active=0") suspend fun delete(id: String)
    @Query("DELETE FROM telemetry_recordings WHERE expiresAtMillis<=:now") suspend fun purge(now: Long)
    @Query("UPDATE telemetry_recordings SET eventsJSON='[]' WHERE uploadDeadlineMillis<=:now OR uploaded=1") suspend fun purgeEvents(now: Long)
    @Query("UPDATE telemetry_recordings SET uploadTripId=:trip,uploadUserId=:user WHERE id=:id AND active=0 AND uploaded=0 AND (uploadTripId IS NULL OR (uploadTripId=:trip AND uploadUserId=:user))") suspend fun bind(id: String, trip: String,user: String): Int
    @Query("UPDATE telemetry_recordings SET uploaded=1,eventsJSON='[]' WHERE id=:id") suspend fun acknowledge(id: String)
}
interface LocalTelemetryStore {
    suspend fun recover(): List<LocalRecording>
    suspend fun list(): List<LocalRecording>
    suspend fun save(record: LocalRecording)
    suspend fun delete(id: String)
    suspend fun bind(id: String,trip: String,user: String): LocalRecording
    suspend fun acknowledge(id: String)
}
@Singleton
class RoomTelemetryStore @Inject constructor(private val db: ConvoyDatabase) : LocalTelemetryStore {
    private val dao get() = db.telemetry()
    override suspend fun recover(): List<LocalRecording> = db.withTransaction {
        val now = System.currentTimeMillis(); dao.purge(now); dao.purgeEvents(now)
        for (record in dao.recordings().filter { it.active }) {
            val summary = JSONObject(record.summaryJSON).put("interrupted",true)
            dao.save(record.copy(active=false,summaryJSON=summary.toString()))
        }
        dao.recordings()
    }
    override suspend fun list(): List<LocalRecording> = db.withTransaction { val now=System.currentTimeMillis();dao.purge(now);dao.purgeEvents(now);dao.recordings() }
    override suspend fun save(record: LocalRecording) = db.withTransaction {
        dao.purge(System.currentTimeMillis())
        check(dao.get(record.id)!=null || dao.recordings().size<20) { "Delete a local summary before recording again" }
        dao.save(record)
    }
    override suspend fun delete(id: String) = dao.delete(id)
    override suspend fun bind(id: String,trip: String,user: String): LocalRecording = db.withTransaction {
        val record = checkNotNull(dao.get(id));check(!record.active && !record.uploaded && record.uploadDeadlineMillis>System.currentTimeMillis())
        check(dao.bind(id,trip,user)==1) { "Retry this recording with its original trip and identity" }
        checkNotNull(dao.get(id))
    }
    override suspend fun acknowledge(id: String) = dao.acknowledge(id)
}
