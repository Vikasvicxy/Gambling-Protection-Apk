package dev.gamblock.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.gamblock.core.model.Commitment
import dev.gamblock.core.model.CommitmentState
import dev.gamblock.core.model.ProtectionLevel
import dev.gamblock.core.model.ProtectionMode

@Entity(
    tableName = "commitments",
    indices = [Index(value = ["state"])],
)
data class CommitmentEntity(
    @PrimaryKey val id: String,
    val mode: ProtectionMode,
    val level: ProtectionLevel,
    val state: CommitmentState,
    val createdAtEpochMs: Long,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val intendedDurationMs: Long,
    val accumulatedElapsedMillis: Long,
    val lastAccountedElapsedMs: Long,
    val bootCountAtCreation: Int,
    val extensionCount: Int,
    val canFinish: Boolean,
)

fun CommitmentEntity.toModel() = Commitment(
    id = id,
    mode = mode,
    level = level,
    state = state,
    createdAtEpochMs = createdAtEpochMs,
    startEpochMs = startEpochMs,
    endEpochMs = endEpochMs,
    intendedDurationMs = intendedDurationMs,
    accumulatedElapsedMillis = accumulatedElapsedMillis,
    lastAccountedElapsedMs = lastAccountedElapsedMs,
    bootCountAtCreation = bootCountAtCreation,
    extensionCount = extensionCount,
    canFinish = canFinish,
)

fun Commitment.toEntity() = CommitmentEntity(
    id = id,
    mode = mode,
    level = level,
    state = state,
    createdAtEpochMs = createdAtEpochMs,
    startEpochMs = startEpochMs,
    endEpochMs = endEpochMs,
    intendedDurationMs = intendedDurationMs,
    accumulatedElapsedMillis = accumulatedElapsedMillis,
    lastAccountedElapsedMs = lastAccountedElapsedMs,
    bootCountAtCreation = bootCountAtCreation,
    extensionCount = extensionCount,
    canFinish = canFinish,
)

/** Key-value metadata row (blocklist version, integrity digest, boot counters, ...). */
@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)