package dev.gamblock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.gamblock.core.database.entity.DomainEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DomainDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<DomainEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DomainEntity)

    @Query("SELECT * FROM domain_rules WHERE status IN ('ACTIVE', 'ALLOWLISTED')")
    suspend fun enabledRules(): List<DomainEntity>

    @Query("SELECT * FROM domain_rules WHERE status IN ('ACTIVE', 'ALLOWLISTED')")
    fun observeEnabledRules(): Flow<List<DomainEntity>>

    @Query("SELECT * FROM domain_rules WHERE normalizedDomain = :normalized LIMIT 1")
    suspend fun findByNormalized(normalized: String): DomainEntity?

    @Query("SELECT * FROM domain_rules")
    suspend fun findAll(): List<DomainEntity>

    @Query("SELECT COUNT(*) FROM domain_rules WHERE status = 'ACTIVE'")
    suspend fun activeCount(): Int

    @Query("SELECT COUNT(*) FROM domain_rules WHERE status = 'ALLOWLISTED'")
    suspend fun allowlistCount(): Int

    @Query("DELETE FROM domain_rules")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM domain_rules")
    suspend fun totalRows(): Int
}