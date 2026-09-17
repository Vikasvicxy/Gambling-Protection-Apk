package dev.gamblock.data.accountability.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.gamblock.data.accountability.db.entity.HeartbeatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HeartbeatDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HeartbeatEntity)

    @Query("SELECT * FROM heartbeats WHERE deviceId = :deviceId LIMIT 1")
    suspend fun byDeviceId(deviceId: String): HeartbeatEntity?

    @Query("SELECT * FROM heartbeats WHERE deviceId = :deviceId LIMIT 1")
    fun observeByDeviceId(deviceId: String): Flow<HeartbeatEntity?>

    @Query("DELETE FROM heartbeats WHERE deviceId = :deviceId")
    suspend fun delete(deviceId: String)
}