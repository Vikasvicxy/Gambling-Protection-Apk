package dev.gamblock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.gamblock.core.database.entity.CravingJournalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CravingJournalDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: CravingJournalEntity): Long

    /**
     * Bulk insert used by backup restore. Deliberately ABORT rather than REPLACE:
     * restore preserves original ids, so a payload that repeats an id is corrupt
     * and has to fail the surrounding transaction instead of silently collapsing
     * two journal rows into one.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entries: List<CravingJournalEntity>)

    @Query("SELECT * FROM craving_journal_entries ORDER BY occurredAtEpochMs DESC")
    fun observeAll(): Flow<List<CravingJournalEntity>>

    @Query("SELECT * FROM craving_journal_entries ORDER BY occurredAtEpochMs DESC")
    suspend fun findAll(): List<CravingJournalEntity>

    @Query("SELECT * FROM craving_journal_entries ORDER BY occurredAtEpochMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<CravingJournalEntity>

    @Query("SELECT COUNT(*) FROM craving_journal_entries")
    suspend fun count(): Int

    @Query("DELETE FROM craving_journal_entries WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM craving_journal_entries")
    suspend fun clear()

    @Query(
        "DELETE FROM craving_journal_entries WHERE id NOT IN " +
            "(SELECT id FROM craving_journal_entries ORDER BY occurredAtEpochMs DESC LIMIT :keep)",
    )
    suspend fun trimTo(keep: Int): Int
}
