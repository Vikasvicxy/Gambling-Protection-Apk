package dev.gamblock.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.gamblock.core.model.BlockStatus
import dev.gamblock.core.model.Category
import dev.gamblock.core.model.Confidence
import dev.gamblock.core.model.Operator
import dev.gamblock.core.model.RiskLevel

/**
 * A persisted blocklist rule. The in-memory lookup index is (re)built from this table.
 * A raw text file is never the permanent architecture: Room holds canonical state,
 * a compiled index holds the hot path.
 */
@Entity(
    tableName = "domain_rules",
    indices = [Index(value = ["normalizedDomain"], unique = true), Index(value = ["status"])],
)
data class DomainEntity(
    @PrimaryKey val domain: String,
    val normalizedDomain: String,
    val category: Category,
    val status: BlockStatus,
    val confidence: Confidence,
    val riskLevel: RiskLevel,
    val firstSeenEpochMs: Long,
    val lastVerifiedEpochMs: Long,
    val source: String,
    val operatorId: Operator,
    val databaseVersion: Int,
    val appliesToSubdomains: Boolean,
)

fun DomainEntity.toModel() = dev.gamblock.core.model.DomainRecord(
    domain = domain,
    normalizedDomain = normalizedDomain,
    category = category,
    status = status,
    confidence = confidence,
    riskLevel = riskLevel,
    firstSeenEpochMs = firstSeenEpochMs,
    lastVerifiedEpochMs = lastVerifiedEpochMs,
    source = source,
    operatorId = operatorId,
    databaseVersion = databaseVersion,
    appliesToSubdomains = appliesToSubdomains,
)

fun dev.gamblock.core.model.DomainRecord.toEntity() = DomainEntity(
    domain = domain,
    normalizedDomain = normalizedDomain,
    category = category,
    status = status,
    confidence = confidence,
    riskLevel = riskLevel,
    firstSeenEpochMs = firstSeenEpochMs,
    lastVerifiedEpochMs = lastVerifiedEpochMs,
    source = source,
    operatorId = operatorId,
    databaseVersion = databaseVersion,
    appliesToSubdomains = appliesToSubdomains,
)