package dev.gamblock.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.gamblock.core.model.ActivityEventType

/** Local privacy-safe activity feed. */
@Entity(
    tableName = "activity_events",
    indices = [Index(value = ["occurredAtEpochMs"]), Index(value = ["type"])],
)
data class ActivityEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val type: ActivityEventType,
    val message: String,
    val occurredAtEpochMs: Long,
    val domain: String?,
    val severity: Severity,
) {
    enum class Severity {
        INFO,
        WARNING,
        ERROR,
    }
}

fun ActivityEventEntity.toModel() = dev.gamblock.core.model.ActivityEvent(
    id = id,
    type = type,
    message = message,
    occurredAtEpochMs = occurredAtEpochMs,
    domain = domain,
    severity = when (severity) {
        ActivityEventEntity.Severity.INFO -> dev.gamblock.core.model.ActivityEvent.Severity.INFO
        ActivityEventEntity.Severity.WARNING -> dev.gamblock.core.model.ActivityEvent.Severity.WARNING
        ActivityEventEntity.Severity.ERROR -> dev.gamblock.core.model.ActivityEvent.Severity.ERROR
    },
)

fun dev.gamblock.core.model.ActivityEvent.toEntity() = ActivityEventEntity(
    id = id,
    type = type,
    message = message,
    occurredAtEpochMs = occurredAtEpochMs,
    domain = domain,
    severity = when (severity) {
        dev.gamblock.core.model.ActivityEvent.Severity.INFO -> ActivityEventEntity.Severity.INFO
        dev.gamblock.core.model.ActivityEvent.Severity.WARNING -> ActivityEventEntity.Severity.WARNING
        dev.gamblock.core.model.ActivityEvent.Severity.ERROR -> ActivityEventEntity.Severity.ERROR
    },
)