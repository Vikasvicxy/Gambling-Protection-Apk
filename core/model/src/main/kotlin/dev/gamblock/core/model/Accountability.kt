package dev.gamblock.core.model

import kotlinx.serialization.Serializable

/**
 * Phase 3 Accountability system models.
 *
 * Accountability is a voluntary adult-to-adult protection mode. A protected user may
 * invite one or more trusted partners who receive privacy-minimized protection events.
 * This file deliberately separates ROLE, CAPABILITY, RELATIONSHIP and EVENT concepts so
 * each is independently enforceable and testable.
 */

/** Distinct participant roles in the Shield ecosystem. */
enum class ShieldRole {
    /** Person protecting themselves. */
    PROTECTED_USER,

    /** Voluntary adult accountability partner. */
    TRUSTED_PARTNER,

    /** Genuine parent/guardian over a supervised child device. */
    PARENT_OR_GUARDIAN,

    /** Server-side administrator. */
    ADMIN,
}

/** Lifecycle state of a pairing / relationship. */
enum class RelationshipStatus {
    /** Invitation created; not yet accepted. */
    PENDING,

    /** Accepted by both sides; fully linked. */
    ACTIVE,

    /** Temporarily paused by the protected user. */
    PAUSED,

    /** Ended; records retained for audit but no events are relayed. */
    ENDED,
}

/** Kind of pairing being established. */
enum class PairingKind {
    ACCOUNTABILITY_PARTNER,
    PARENT_SUPERVISED_CHILD,
}

/**
 * Granular partner capabilities. Least privilege by default: a partner receives only the
 * capabilities a protected user explicitly grants. Sensitive changes (device replacement,
 * partner changes) are separate capabilities so approval can never be granted implicitly.
 */
@Serializable
enum class PartnerCapability(val key: String) {
    RECEIVE_PROTECTION_ALERTS("receive_protection_alerts"),
    RECEIVE_HEARTBEAT_ALERTS("receive_heartbeat_alerts"),
    RECEIVE_GAMBLING_ATTEMPTS("receive_gambling_attempts"),
    RECEIVE_TAMPER_ALERTS("receive_tamper_alerts"),
    RECEIVE_REPLACEMENT_NOTICES("receive_replacement_notices"),
    APPROVE_DEVICE_REPLACEMENT("approve_device_replacement"),
    APPROVE_PARTNER_CHANGES("approve_partner_changes"),
    APPROVE_ALERT_DISABLE("approve_alert_disable"),
    EXTEND_PROTECTION("extend_protection"),
    PARENT_APPROVE_CONFIG_CHANGES("parent_approve_config_changes"),
    PARENT_RECEIVE_STATS("parent_receive_stats"),
    PARENT_LOCK_CATEGORIES("parent_lock_categories"),
}

/** Events a trusted partner / parent may be configured to receive. */
enum class AccountabilityEventType {
    /** A single gambling attempt was blocked (LOW). */
    GAMBLING_ATTEMPT_BLOCKED,

    /** Many repeated attempts were grouped into one event (MEDIUM). */
    REPEATED_GAMBLING_ATTEMPTS_BLOCKED,

    /** A gambling app (package) attempt was blocked. */
    GAMBLING_APP_ATTEMPT_BLOCKED,

    /** VPN went unexpectedly inactive (HIGH). */
    VPN_UNEXPECTEDLY_INACTIVE,

    /** Protection state degraded but still present. */
    PROTECTION_DEGRADED,

    /** A required permission was removed. */
    REQUIRED_PERMISSION_REMOVED,

    /** Heartbeat lost beyond the configurable threshold (CRITICAL). */
    HEARTBEAT_LOST,

    /** Tamper evidence was generated. */
    TAMPER_EVIDENCE_GENERATED,

    /** Protected device replaced (after confirmation). */
    PROTECTED_DEVICE_REPLACED,

    /** Sent when protection is confirmed active again (recovery). */
    PROTECTION_CONFIRMED_ACTIVE,
}

/**
 * Severity levels for accountability events. Used both for display and for
 * partner-permission gating of high-severity event types.
 */
@Serializable
enum class AccountabilitySeverity(val rank: Int) {
    LOW(0),
    MEDIUM(1),
    HIGH(2),
    CRITICAL(3);

    companion object {
        fun max(vararg severities: AccountabilitySeverity): AccountabilitySeverity =
            severities.maxByOrNull { it.rank } ?: LOW
    }
}

/**
 * A single privacy-minimized accountability event sent to a trusted partner or parent.
 * Never carries URLs, browsing history, messages, contacts, passwords or location by
 * default. Category and domain are opt-in.
 */
@Serializable
data class AccountabilityEvent(
    val id: String,
    val type: AccountabilityEventType,
    val severity: AccountabilitySeverity,
    val occurredAtEpochMs: Long,
    /** Pseudonymous install id of the protected device. */
    val deviceId: String,
    /** Optional category aggregate (opt-in). */
    val category: Category? = null,
    /** Number of underlying occurrences grouped into this event. */
    val count: Int = 1,
    /** Set when the sender opts into exact-domain sharing. Not sent by default. */
    val exactDomain: String? = null,
) {
    val grouped: Boolean get() = count > 1
}

/**
 * Grouping state used to collapse repeated events (e.g. a browser retrying the same
 * blocked request 50 times) into ONE accountability event within a cooldown window.
 */
@Serializable
data class EventGroupingState(
    /** Stable grouping key (device + type + optional category + optional domain). */
    val groupKey: String,
    val firstOccurredAtEpochMs: Long,
    val lastOccurredAtEpochMs: Long,
    val count: Int,
    /** True once a grouped event has been emitted for this group. */
    val eventEmitted: Boolean,
) {
    fun domainHint(): String? = null
}

/** Result of aggregating a fresh occurrence into a grouping. */
sealed interface EventGroupResult {
    /** Occurrence absorbed into current group; nothing to notify. */
    data class Suppressed(val state: EventGroupingState) : EventGroupResult

    /** Occurrence triggered a fresh grouped notification. */
    data class Emitted(val state: EventGroupingState, val event: AccountabilityEvent) : EventGroupResult

    /** Cooldown elapsed; group reset happened before this occurrence. */
    data class ResetThenSuppressed(val state: EventGroupingState) : EventGroupResult
}

/** Thresholds governing event grouping / notification cooldown. */
@Serializable
data class GroupingConfig(
    /** Occurrences within this window are bounded into one group. */
    val cooldownWindowMillis: Long = 60_000L,
    /** Count at which a group is considered "repeated" (MEDIUM). */
    val repeatedThreshold: Int = 3,
)