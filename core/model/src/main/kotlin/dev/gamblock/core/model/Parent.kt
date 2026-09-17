package dev.gamblock.core.model

import kotlinx.serialization.Serializable

/**
 * Phase 3 Parent / Guardian mode models.
 *
 * Parent mode is a GENUINE parent->child supervised relationship, architecturally
 * distinct from self-protection and from voluntary adult Accountability. It never
 * masquerades as self-protection to grab stronger Android privileges. Stronger
 * restrictions are only applied where legitimately permitted by Android APIs and
 * Google Play policy.
 */

/** Configuration of a Parent / Guardian account. */
@Serializable
data class ParentModeConfig(
    /** Pseudonymous parent account id. */
    val parentAccountId: String,
    /** Display name shown on the parent dashboard. */
    val displayName: String,
    /** If true the child-protected configuration changes require parent approval. */
    val requireApprovalForConfigChanges: Boolean = true,
    /** Categories locked by the parent (child cannot self-exempt). */
    val lockedCategories: Set<Category> = emptySet(),
    /** If true the parent receives daily aggregate stats. */
    val dailyStatsEnabled: Boolean = true,
    /** If true the parent receives tamper/removal alerts. */
    val tamperAlertsEnabled: Boolean = true,
    val createdAtEpochMs: Long,
)

/** Privacy-minimized view of one supervised child device for the parent dashboard. */
@Serializable
data class SupervisedChildDevice(
    val childDeviceId: String,
    val displayName: String,
    val protectionActive: Boolean,
    val health: HealthStatus,
    val databaseCurrent: Boolean,
    val lastCheckInEpochMs: Long?,
    /** Today's blocked attempt count. */
    val todayBlockedCount: Int,
    /** Optional aggregate categories (e.g. 2 sports betting, 1 casino). */
    val categoryBreakdown: Map<Category, Int> = emptyMap(),
    val lockedCategories: Set<Category> = emptySet(),
    val relationshipStatus: RelationshipStatus,
)

/**
 * Aggregate protected stats a parent may view. Never full browsing-history surveillance.
 */
@Serializable
data class ChildAggregateStats(
    val childDeviceId: String,
    val todayBlockedCount: Int,
    val weekBlockedCount: Int,
    val categoryBreakdown: Map<Category, Int> = emptyMap(),
    val heartbeat: HeartbeatState? = null,
)

/** A schedule window a parent may define for supervised protection (reuses existing week). */
@Serializable
data class ParentSchedule(
    val parentAccountId: String,
    val childDeviceId: String,
    val schedule: ProtectionSchedule? = null,
)

/** Blocker-of-last-resort labels for Play policy documentation. */
@Serializable
data class ParentPolicyDisclosure(
    val usesAccessibility: Boolean = false,
    val usesDeviceAdmin: Boolean = false,
    val usesFullPackageVisibility: Boolean = false,
)