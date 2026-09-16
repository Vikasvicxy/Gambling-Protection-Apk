package dev.gamblock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.gamblock.core.database.entity.FalsePositiveReportEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FalsePositiveReportDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FalsePositiveReportEntity): Long

    @Query("SELECT * FROM false_positive_reports ORDER BY createdAtEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<FalsePositiveReportEntity>>

    @Query("SELECT * FROM false_positive_reports WHERE status = 'QUEUED'")
    suspend fun queued(): List<FalsePositiveReportEntity>

    @Query("UPDATE false_positive_reports SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: dev.gamblock.core.model.ReportStatus)
}