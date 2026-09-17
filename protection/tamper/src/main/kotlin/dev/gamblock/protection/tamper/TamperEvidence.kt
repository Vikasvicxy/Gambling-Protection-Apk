package dev.gamblock.protection.tamper

import kotlinx.serialization.Serializable

/**
 * A single tamper-evident evidence record.
 *
 * - [contentHash] = SHA-256 over the canonical fields (excluding the hash and
 *   HMAC themselves): sequence, category, timestamp, sorted detail, prevHash.
 * - [prevHash] = HMAC of the immediately preceding record (zeros for the head).
 * - [hmac] = keyed HMAC-SHA256 over the same canonical bytes.
 *
 * Editing any field, reordering records, or deleting a middle record breaks
 * either a content hash or the chain linkage, so the log is tamper-evident in
 * practice. Silently dropping the newest tail record is NOT detected by replay
 * alone (the survivor chain remains internally consistent); hardening against
 * tail truncation requires an external anchor for the latest tail HMAC (see
 * the Phase 2 report - not implemented here).
 */
@Serializable
data class TamperEvidence(
    val sequence: Long,
    val category: TamperCategory,
    val timestampEpochMs: Long,
    val detail: Map<String, String> = emptyMap(),
    val contentHash: String,
    val prevHash: String,
    val hmac: String,
)

/** Result of replaying a [TamperEvidenceChain.verify]. */
data class ChainVerification(
    val valid: Boolean,
    val verifiedCount: Int,
    val brokenAtIndex: Int = -1,
    val reason: String = "",
)

/** Human-readable, serializable overview for health/UI surfaces. */
@Serializable
data class TamperOverview(
    val totalEvidence: Int,
    val chainValid: Boolean,
    val generatedAtEpochMs: Long,
    val categoryCounts: Map<String, Int>,
    val lastHmac: String = "",
    val lastCategory: TamperCategory? = null,
)