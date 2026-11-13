package org.projectdrive.app.core.database

import androidx.room.*

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
@Database(entities = [PendingStop::class, SelectedTrip::class, SavedInvite::class], version = 1, exportSchema = true)
abstract class ConvoyDatabase : RoomDatabase() { abstract fun convoy(): ConvoyDao }
