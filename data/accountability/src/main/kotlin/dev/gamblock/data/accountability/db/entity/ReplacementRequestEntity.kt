package dev.gamblock.data.accountability.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.gamblock.core.model.DeviceReplacementRequest
import dev.gamblock.core.model.DeviceReplacementState

@Entity(tableName = "replacement_requests")
data class ReplacementRequestEntity(
    @PrimaryKey val id: String,
    val oldDeviceId: String,
    val newDeviceId: String,
    val remainingCommitmentMillis: Long,
    val requestedAtEpochMs: Long,
    val status: DeviceReplacementState,
    val confirmsNeeded: Int,
    val confirmedBy: String,
    val confirmedAtEpochMs: Long? = null,
    val carryBlocklist: Boolean,
)

fun ReplacementRequestEntity.toModel() = DeviceReplacementRequest(
    id = id,
    oldDeviceId = oldDeviceId,
    newDeviceId = newDeviceId,
    remainingCommitmentMillis = remainingCommitmentMillis,
    requestedAtEpochMs = requestedAtEpochMs,
    status = status,
    confirmsNeeded = confirmsNeeded,
    confirmedBy = confirmedBy.split(',').filter { it.isNotBlank() }.toSet(),
    confirmedAtEpochMs = confirmedAtEpochMs,
    carryBlocklist = carryBlocklist,
)

fun DeviceReplacementRequest.toEntity() = ReplacementRequestEntity(
    id = id,
    oldDeviceId = oldDeviceId,
    newDeviceId = newDeviceId,
    remainingCommitmentMillis = remainingCommitmentMillis,
    requestedAtEpochMs = requestedAtEpochMs,
    status = status,
    confirmsNeeded = confirmsNeeded,
    confirmedBy = confirmedBy.joinToString(","),
    confirmedAtEpochMs = confirmedAtEpochMs,
    carryBlocklist = carryBlocklist,
)