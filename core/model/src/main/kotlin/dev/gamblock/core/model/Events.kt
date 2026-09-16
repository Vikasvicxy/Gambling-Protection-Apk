package dev.gamblock.core.model

import kotlinx.serialization.Serializable

enum class ActivityEventType {
    DOMAIN_BLOCKED,
    BLOCK_GROUP_UPDATED,
    VPN_STARTED,
    VPN_STOPPED,
    VPN_REVOKED,
    PROTECTION_RECOVERED,
    DATABASE_LOADED,
    COMPATIBILITY_WARNING,
    BOOT_RECOVERY_STARTED,
    BOOT_RECOVERY_COMPLETED,
    COMMITMENT_STARTED,
    COMMITMENT_EXTENDED,
    COMMITMENT_COMPLETED,
    UPDATE_FAILED,
    UPDATE_OK,
    HEALTH_CHANGED,
    FALSE_POSITIVE_REPORTED,
    SCHEDULE_CHANGED,
}

@Serializable
data class ActivityEvent(
    val id: Long = 0L,
    val type: ActivityEventType,
    val message: String,
    val occurredAtEpochMs: Long,
    val domain: String? = null,
    val severity: Severity = Severity.INFO,
) {
    enum class Severity {
        INFO,
        WARNING,
        ERROR,
    }
}

/** A privacy-safe record of a blocked domain attempt; repeated attempts are grouped. */
@Serializable
data class BlockAttemptGroup(
    val id: Long = 0L,
    val normalizedDomain: String,
    val category: Category = Category.GAMBLING,
    val count: Int = 1,
    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long,
)

/** Privacy-safe summary shown on the dashboard. */
@Serializable
data class ProtectionStats(
    val totalBlockedAttempts: Int,
    val totalDistinctDomains: Int,
    val queriesHandled: Long,
    val queriesBlocked: Long,
    val queriesAllowed: Long,
)