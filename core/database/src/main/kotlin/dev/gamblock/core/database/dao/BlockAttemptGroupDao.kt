package dev.gamblock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.gamblock.core.database.entity.BlockAttemptGroupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockAttemptGroupDao {

    @Query("SELECT * FROM block_attempt_groups WHERE normalizedDomain = :domain LIMIT 1")
    suspend fun find(domain: String): BlockAttemptGroupEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: BlockAttemptGroupEntity): Long

    @Update
    suspend fun update(entity: BlockAttemptGroupEntity)

    @Query(
        """SELECT * FROM block_attempt_groups
           ORDER BY lastSeenEpochMs DESC LIMIT :limit""",
    )
    fun observeRecent(limit: Int): Flow<List<BlockAttemptGroupEntity>>

    @Query("SELECT COUNT(*) FROM block_attempt_groups")
    fun observeGroupCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(count), 0) FROM block_attempt_groups")
    fun observeTotalAttempts(): Flow<Int>

    @Query("SELECT COALESCE(SUM(count), 0) FROM block_attempt_groups")
    suspend fun totalAttemptsNow(): Int

    @Query("DELETE FROM block_attempt_groups")
    suspend fun clear()

    /** Deletes groups older than [olderThanEpochMs] except the newest [keepNewest]. */
    @Query(
        """DELETE FROM block_attempt_groups WHERE lastSeenEpochMs < :olderThanEpochMs
           AND id NOT IN (SELECT id FROM block_attempt_groups ORDER BY lastSeenEpochMs DESC LIMIT :keepNewest)""",
    )
    suspend fun prune(olderThanEpochMs: Long, keepNewest: Int)

    @Transaction
    suspend fun upsertGrouped(entity: BlockAttemptGroupEntity, existing: BlockAttemptGroupEntity?) {
        val current = existing ?: find(entity.normalizedDomain)
        if (current == null) {
            insert(entity)
        } else {
            update(current.copy(count = current.count + entity.count, lastSeenEpochMs = entity.lastSeenEpochMs))
        }
    }
}