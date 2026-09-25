package dev.gamblock.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-created custom domain exception (bypass). Unlike static allowlist rules
 * these carry an optional expiry so temporary exceptions self-clean; the hot path
 * never queries this table (an in-memory overlay is maintained instead).
 */
@Entity(
    tableName = "custom_domain_exceptions",
    indices = [Index(value = ["normalizedDomain"], unique = true)],
)
data class CustomDomainExceptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val normalizedDomain: String,
    val createdAtEpochMs: Long,
    /** Null = permanent; otherwise the exception self-removes after this instant. */
    val expiresAtEpochMs: Long? = null,
    val note: String = "",
)

fun CustomDomainExceptionEntity.toModel(): dev.gamblock.core.model.CustomDomainException =
    dev.gamblock.core.model.CustomDomainException(
        id = id,
        normalizedDomain = normalizedDomain,
        createdAtEpochMs = createdAtEpochMs,
        expiresAtEpochMs = expiresAtEpochMs,
        note = note,
    )