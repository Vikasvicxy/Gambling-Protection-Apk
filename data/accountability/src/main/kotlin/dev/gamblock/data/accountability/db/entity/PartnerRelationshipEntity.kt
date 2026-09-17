package dev.gamblock.data.accountability.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.gamblock.core.model.PartnerCapability
import dev.gamblock.core.model.PartnerRelationship
import dev.gamblock.core.model.PartnerRole
import dev.gamblock.core.model.RelationshipStatus

@Entity(
    tableName = "partner_relationships",
    indices = [
        Index(value = ["protectedDeviceId"]),
        Index(value = ["partnerDeviceId"]),
    ],
)
data class PartnerRelationshipEntity(
    @PrimaryKey val id: String,
    val protectedDeviceId: String,
    val partnerDeviceId: String,
    val role: PartnerRole,
    val status: RelationshipStatus,
    val capabilities: String,
    val pausedByProtectedUser: Boolean,
    val createdAtEpochMs: Long,
    val acceptedAtEpochMs: Long? = null,
    val endedAtEpochMs: Long? = null,
)

fun PartnerRelationshipEntity.toModel() = PartnerRelationship(
    id = id,
    protectedDeviceId = protectedDeviceId,
    partnerDeviceId = partnerDeviceId,
    role = role,
    status = status,
    capabilities = capabilities.split(',').filter { it.isNotBlank() }.map { PartnerCapability.valueOf(it) }.toSet(),
    pausedByProtectedUser = pausedByProtectedUser,
    createdAtEpochMs = createdAtEpochMs,
    acceptedAtEpochMs = acceptedAtEpochMs,
    endedAtEpochMs = endedAtEpochMs,
)

fun PartnerRelationship.toEntity() = PartnerRelationshipEntity(
    id = id,
    protectedDeviceId = protectedDeviceId,
    partnerDeviceId = partnerDeviceId,
    role = role,
    status = status,
    capabilities = capabilities.joinToString(",") { it.name },
    pausedByProtectedUser = pausedByProtectedUser,
    createdAtEpochMs = createdAtEpochMs,
    acceptedAtEpochMs = acceptedAtEpochMs,
    endedAtEpochMs = endedAtEpochMs,
)