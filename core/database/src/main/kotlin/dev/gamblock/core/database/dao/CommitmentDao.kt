package dev.gamblock.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.gamblock.core.database.entity.CommitmentEntity
import dev.gamblock.core.database.entity.MetaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CommitmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CommitmentEntity)

    @Query("SELECT * FROM commitments ORDER BY createdAtEpochMs DESC LIMIT 1")
    fun observeLatest(): Flow<CommitmentEntity?>

    @Query("SELECT * FROM commitments ORDER BY createdAtEpochMs DESC LIMIT 1")
    suspend fun latest(): CommitmentEntity?

    @Query("DELETE FROM commitments")
    suspend fun deleteAll()
}

@Dao
interface MetaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: MetaEntity)

    @Query("SELECT value FROM meta WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM meta WHERE `key` = :key LIMIT 1")
    fun observe(key: String): Flow<String?>
}