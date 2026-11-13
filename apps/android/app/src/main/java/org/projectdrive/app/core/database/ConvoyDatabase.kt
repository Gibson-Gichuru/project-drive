package org.projectdrive.app.core.database

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.projectdrive.app.core.telemetry.LocalRecording
import org.projectdrive.app.core.telemetry.TelemetryDao

// No credentials, invitations, coordinates or raw tracks belong in this database.
@Entity(tableName = "pending_stops", primaryKeys = ["userId", "tripId"])
data class PendingStop(val userId: String, val tripId: String)
@Entity(tableName = "selected_trip")
data class SelectedTrip(@PrimaryKey val slot: Int = 1, val userId: String, val tripId: String)
@Entity(tableName = "saved_invites", primaryKeys = ["userId", "tripId"])
data class SavedInvite(val userId: String, val tripId: String, val requestKey: String, val inviteId: String?)
@Dao
interface ConvoyDao {
    @Query("SELECT * FROM pending_stops WHERE userId=:user") suspend fun pending(user: String): List<PendingStop>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun remember(stop: PendingStop)
    @Delete suspend fun forget(stop: PendingStop)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun select(trip: SelectedTrip)
    @Query("SELECT * FROM selected_trip WHERE userId=:user LIMIT 1") suspend fun selected(user: String): SelectedTrip?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveInvite(invite: SavedInvite)
    @Query("SELECT * FROM saved_invites WHERE userId=:user AND tripId=:trip LIMIT 1") suspend fun invite(user: String, trip: String): SavedInvite?
    @Query("DELETE FROM saved_invites WHERE userId=:user AND tripId=:trip") suspend fun clearInvite(user: String, trip: String)
    @Query("DELETE FROM selected_trip") suspend fun clearSelection()
}
@Database(entities = [PendingStop::class, SelectedTrip::class, SavedInvite::class, LocalRecording::class], version = 2, exportSchema = true)
abstract class ConvoyDatabase : RoomDatabase() { abstract fun convoy(): ConvoyDao; abstract fun telemetry(): TelemetryDao }

val TELEMETRY_MIGRATION = object : Migration(1,2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS telemetry_recordings (id TEXT NOT NULL PRIMARY KEY, summaryJSON TEXT NOT NULL, eventsJSON TEXT NOT NULL, active INTEGER NOT NULL, expiresAtMillis INTEGER NOT NULL, uploadDeadlineMillis INTEGER NOT NULL, uploadTripId TEXT, uploadUserId TEXT, uploaded INTEGER NOT NULL)")
    }
}
