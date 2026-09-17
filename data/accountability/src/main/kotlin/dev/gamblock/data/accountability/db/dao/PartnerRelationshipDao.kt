package dev.gamblock.data.accountability.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.gamblock.data.accountability.db.entity.PartnerRelationshipEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PartnerRelationshipDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PartnerRelationshipEntity)

    @Query("SELECT * FROM partner_relationships WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): PartnerRelationshipEntity?

    @Query("SELECT * FROM partner_relationships WHERE protectedDeviceId = :deviceId")
    suspend fun forProtectedDevice(deviceId: String): List<PartnerRelationshipEntity>

    @Query("SELECT * FROM partner_relationships WHERE partnerDeviceId = :deviceId")
    suspend fun forPartnerDevice(deviceId: String): List<PartnerRelationshipEntity>

    @Query("SELECT * FROM partner_relationships ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<PartnerRelationshipEntity>>

    @Query("SELECT * FROM partner_relationships WHERE status = 'ACTIVE' ORDER BY createdAtEpochMs DESC")
    fun observeActive(): Flow<List<PartnerRelationshipEntity>>

    @Query("DELETE FROM partner_relationships")
    suspend fun deleteAll()
}