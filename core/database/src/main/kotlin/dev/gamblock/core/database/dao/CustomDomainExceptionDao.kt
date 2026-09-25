package dev.gamblock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.gamblock.core.database.entity.CustomDomainExceptionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomDomainExceptionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CustomDomainExceptionEntity): Long

    @Query("SELECT * FROM custom_domain_exceptions ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<CustomDomainExceptionEntity>>

    @Query("SELECT * FROM custom_domain_exceptions")
    suspend fun findAll(): List<CustomDomainExceptionEntity>

    @Query("DELETE FROM custom_domain_exceptions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM custom_domain_exceptions WHERE expiresAtEpochMs IS NOT NULL AND expiresAtEpochMs <= :nowEpochMs")
    suspend fun deleteExpired(nowEpochMs: Long): Int

    @Query("SELECT COUNT(*) FROM custom_domain_exceptions")
    suspend fun count(): Int
}