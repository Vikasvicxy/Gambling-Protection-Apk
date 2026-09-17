package dev.gamblock.data.accountability.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.gamblock.data.accountability.db.entity.EventGroupingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EventGroupingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EventGroupingEntity)

    @Query("SELECT * FROM event_groupings WHERE groupKey = :groupKey LIMIT 1")
    suspend fun byKey(groupKey: String): EventGroupingEntity?

    @Query("SELECT * FROM event_groupings WHERE groupKey = :groupKey LIMIT 1")
    fun observeByKey(groupKey: String): Flow<EventGroupingEntity?>

    @Query("DELETE FROM event_groupings")
    suspend fun deleteAll()
}