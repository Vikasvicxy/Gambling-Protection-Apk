package dev.gamblock.data.accountability.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.gamblock.core.model.PairingKind
import dev.gamblock.core.model.StoredPairingToken

@Entity(
    tableName = "pairing_tokens",
    indices = [Index(value = ["tokenHash"], unique = true)],
)
data class PairingTokenEntity(
    @PrimaryKey val id: String,
    val tokenHash: String,
    val kind: PairingKind,
    val createdByDeviceId: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val consumedAtEpochMs: Long? = null,
    val label: String? = null,
)

fun PairingTokenEntity.toModel() = StoredPairingToken(
    id = id,
    tokenHash = tokenHash,
    kind = kind,
    createdByDeviceId = createdByDeviceId,
    createdAtEpochMs = createdAtEpochMs,
    expiresAtEpochMs = expiresAtEpochMs,
    consumedAtEpochMs = consumedAtEpochMs,
    label = label,
)

fun StoredPairingToken.toEntity() = PairingTokenEntity(
    id = id,
    tokenHash = tokenHash,
    kind = kind,
    createdByDeviceId = createdByDeviceId,
    createdAtEpochMs = createdAtEpochMs,
    expiresAtEpochMs = expiresAtEpochMs,
    consumedAtEpochMs = consumedAtEpochMs,
    label = label,
)