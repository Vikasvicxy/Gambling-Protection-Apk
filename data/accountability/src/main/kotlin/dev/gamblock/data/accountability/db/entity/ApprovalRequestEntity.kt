package dev.gamblock.data.accountability.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.gamblock.core.model.ApprovalRequest
import dev.gamblock.core.model.ApprovalStatus
import dev.gamblock.core.model.SensitiveChange

@Entity(
    tableName = "approval_requests",
    indices = [Index(value = ["relationshipId"])],
)
data class ApprovalRequestEntity(
    @PrimaryKey val id: String,
    val relationshipId: String,
    val requestedByDeviceId: String,
    val change: SensitiveChange,
    val description: String,
    val requestedAtEpochMs: Long,
    val status: ApprovalStatus,
    val decidedByDeviceId: String? = null,
    val decidedAtEpochMs: Long? = null,
)

fun ApprovalRequestEntity.toModel() = ApprovalRequest(
    id = id,
    relationshipId = relationshipId,
    requestedByDeviceId = requestedByDeviceId,
    change = change,
    description = description,
    requestedAtEpochMs = requestedAtEpochMs,
    status = status,
    decidedByDeviceId = decidedByDeviceId,
    decidedAtEpochMs = decidedAtEpochMs,
)

fun ApprovalRequest.toEntity() = ApprovalRequestEntity(
    id = id,
    relationshipId = relationshipId,
    requestedByDeviceId = requestedByDeviceId,
    change = change,
    description = description,
    requestedAtEpochMs = requestedAtEpochMs,
    status = status,
    decidedByDeviceId = decidedByDeviceId,
    decidedAtEpochMs = decidedAtEpochMs,
)