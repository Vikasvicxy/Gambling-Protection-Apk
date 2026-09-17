package dev.gamblock.data.accountability.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.gamblock.data.accountability.db.entity.PairingTokenEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PairingTokenDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PairingTokenEntity)

    @Update
    suspend fun update(entity: PairingTokenEntity)

    @Query("SELECT * FROM pairing_tokens WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): PairingTokenEntity?

    @Query("SELECT * FROM pairing_tokens WHERE tokenHash = :tokenHash LIMIT 1")
    suspend fun byHash(tokenHash: String): PairingTokenEntity?

    @Query("SELECT * FROM pairing_tokens WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<PairingTokenEntity?>

    @Query("DELETE FROM pairing_tokens WHERE expiresAtEpochMs < :nowEpochMs")
    suspend fun deleteExpired(nowEpochMs: Long)
}