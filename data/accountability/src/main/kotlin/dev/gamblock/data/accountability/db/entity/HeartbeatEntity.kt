package dev.gamblock.data.accountability.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.gamblock.core.model.HealthStatus
import dev.gamblock.core.model.HeartbeatState
import dev.gamblock.core.model.ProtectionState

@Entity(tableName = "heartbeats")
data class HeartbeatEntity(
    @PrimaryKey val deviceId: String,
    val protectionState: ProtectionState,
    val health: HealthStatus,
    val lastCheckInEpochMs: Long,
    val appVersion: String,
    val databaseVersion: Int,
    val databaseFreshnessEpochMs: Long? = null,
    val heartbeatSequence: Long,
)

fun HeartbeatEntity.toModel() = HeartbeatState(
    deviceId = deviceId,
    protectionState = protectionState,
    health = health,
    lastCheckInEpochMs = lastCheckInEpochMs,
    appVersion = appVersion,
    databaseVersion = databaseVersion,
    databaseFreshnessEpochMs = databaseFreshnessEpochMs,
    heartbeatSequence = heartbeatSequence,
)

fun HeartbeatState.toEntity() = HeartbeatEntity(
    deviceId = deviceId,
    protectionState = protectionState,
    health = health,
    lastCheckInEpochMs = lastCheckInEpochMs,
    appVersion = appVersion,
    databaseVersion = databaseVersion,
    databaseFreshnessEpochMs = databaseFreshnessEpochMs,
    heartbeatSequence = heartbeatSequence,
)