package dev.gamblock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.gamblock.core.database.entity.ActivityEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ActivityEventEntity): Long

    @Query("SELECT * FROM activity_events ORDER BY occurredAtEpochMs DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ActivityEventEntity>>

    @Query("SELECT * FROM activity_events ORDER BY occurredAtEpochMs DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ActivityEventEntity>

    @Query("DELETE FROM activity_events WHERE occurredAtEpochMs < :olderThanEpochMs")
    suspend fun prune(olderThanEpochMs: Long): Int

    @Query("SELECT COUNT(*) FROM activity_events")
    suspend fun countAll(): Int
}