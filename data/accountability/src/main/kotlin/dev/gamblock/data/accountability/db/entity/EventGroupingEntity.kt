package dev.gamblock.data.accountability.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.gamblock.core.model.EventGroupingState

@Entity(tableName = "event_groupings")
data class EventGroupingEntity(
    @PrimaryKey val groupKey: String,
    val firstOccurredAtEpochMs: Long,
    val lastOccurredAtEpochMs: Long,
    val count: Int,
    val eventEmitted: Boolean,
)

fun EventGroupingEntity.toModel() = EventGroupingState(
    groupKey = groupKey,
    firstOccurredAtEpochMs = firstOccurredAtEpochMs,
    lastOccurredAtEpochMs = lastOccurredAtEpochMs,
    count = count,
    eventEmitted = eventEmitted,
)

fun EventGroupingState.toEntity() = EventGroupingEntity(
    groupKey = groupKey,
    firstOccurredAtEpochMs = firstOccurredAtEpochMs,
    lastOccurredAtEpochMs = lastOccurredAtEpochMs,
    count = count,
    eventEmitted = eventEmitted,
)