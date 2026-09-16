package dev.gamblock.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.gamblock.core.model.Category

/**
 * A grouped, privacy-safe record of repeated visits to a blocked domain.
 * No browsing history, no app attribution, no page content.
 */
@Entity(
    tableName = "block_attempt_groups",
    indices = [Index(value = ["normalizedDomain"], unique = true)],
)
data class BlockAttemptGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val normalizedDomain: String,
    val category: Category,
    val count: Int,
    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long,
    val signature: String,
)

fun BlockAttemptGroupEntity.toModel() = dev.gamblock.core.model.BlockAttemptGroup(
    id = id,
    normalizedDomain = normalizedDomain,
    category = category,
    count = count,
    firstSeenEpochMs = firstSeenEpochMs,
    lastSeenEpochMs = lastSeenEpochMs,
)

fun dev.gamblock.core.model.BlockAttemptGroup.toEntity(signature: String) = BlockAttemptGroupEntity(
    id = id,
    normalizedDomain = normalizedDomain,
    category = category,
    count = count,
    firstSeenEpochMs = firstSeenEpochMs,
    lastSeenEpochMs = lastSeenEpochMs,
    signature = signature,
)