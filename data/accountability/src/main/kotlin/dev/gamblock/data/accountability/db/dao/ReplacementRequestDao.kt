package dev.gamblock.data.accountability.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.gamblock.data.accountability.db.entity.ReplacementRequestEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReplacementRequestDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReplacementRequestEntity)

    @Query("SELECT * FROM replacement_requests WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ReplacementRequestEntity?

    @Query("SELECT * FROM replacement_requests WHERE oldDeviceId = :deviceId ORDER BY requestedAtEpochMs DESC")
    suspend fun forDevice(deviceId: String): List<ReplacementRequestEntity>

    @Query("SELECT * FROM replacement_requests ORDER BY requestedAtEpochMs DESC")
    fun observeAll(): Flow<List<ReplacementRequestEntity>>
}