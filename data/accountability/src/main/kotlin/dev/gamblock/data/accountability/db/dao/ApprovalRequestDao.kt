package dev.gamblock.data.accountability.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.gamblock.data.accountability.db.entity.ApprovalRequestEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ApprovalRequestDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ApprovalRequestEntity)

    @Query("SELECT * FROM approval_requests WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ApprovalRequestEntity?

    @Query("SELECT * FROM approval_requests WHERE relationshipId = :relationshipId")
    suspend fun forRelationship(relationshipId: String): List<ApprovalRequestEntity>

    @Query("SELECT * FROM approval_requests WHERE status = 'PENDING' ORDER BY requestedAtEpochMs ASC")
    fun observePending(): Flow<List<ApprovalRequestEntity>>

    @Query("SELECT * FROM approval_requests ORDER BY requestedAtEpochMs DESC")
    fun observeAll(): Flow<List<ApprovalRequestEntity>>
}