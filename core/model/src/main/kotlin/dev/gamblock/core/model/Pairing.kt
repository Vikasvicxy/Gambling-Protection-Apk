package dev.gamblock.core.model

import kotlinx.serialization.Serializable

/**
 * Phase 3 secure pairing models.
 *
 * Pairing is the mechanism by which a protected user links their device to a trusted
 * partner (Accountability mode) or a parent/guardian device (Parent mode). Tokens are
 * short-lived, single-use, high-entropy and never contain sensitive data directly.
 */

/**
 * A pairing invitation, before acceptance. The displayed token is `id + "." + secret` so
 * that the id and secret are never confused with other data. In transit/storage we prefer
 * the SHA-256 hash of the full token; the raw secret exists only transiently on the
 * creating device and on the accepting device at accept-time.
 */
@Serializable
data class PairingToken(
    val id: String,
    val secret: String,
    val kind: PairingKind,
    /** Pseudonymous install id of the protected (creating) device. */
    val createdByDeviceId: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    /** Human display label, e.g. "Alice's Shield". */
    val label: String? = null,
) {
    val fullToken: String get() = "$id.$secret"

    val expiredAt: Long?
        get() = null
}

/** Serialized form persisted server-side / locally. Contains NO raw secret. */
@Serializable
data class StoredPairingToken(
    val id: String,
    val tokenHash: String,
    val kind: PairingKind,
    val createdByDeviceId: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val consumedAtEpochMs: Long? = null,
    val label: String? = null,
) {
    val consumed: Boolean get() = consumedAtEpochMs != null
    val expiredAt: Long get() = expiresAtEpochMs
}

/**
 * Result of consuming a pairing invitation at accept-time.
 * The relationship is created with explicit partner consent (active accept).
 */
@Serializable
data class PairingAcceptResult(
    val relationshipId: String,
    val partnerDeviceId: String,
    val role: PartnerRole,
    val acceptedAtEpochMs: Long,
)

/** Roles within an established Accountability / parent-child relationship. */
@Serializable
enum class PartnerRole(val label: String) {
    TRUSTED_PARTNER("Trusted Partner"),
    PARENT_OR_GUARDIAN("Parent / Guardian"),
}

/**
 * An established partner<->protected-device link. Capabilities are a configured subset of
 * [PartnerCapability] chosen at invite time and editable only through partner-approved
 * sensitive change flows.
 */
@Serializable
data class PartnerRelationship(
    val id: String,
    /** Pseudonymous install id of the protected device. */
    val protectedDeviceId: String,
    /** Pseudonymous install id of the partner/parent device. */
    val partnerDeviceId: String,
    val role: PartnerRole,
    val status: RelationshipStatus = RelationshipStatus.PENDING,
    val capabilities: Set<PartnerCapability> = emptySet(),
    val pausedByProtectedUser: Boolean = false,
    val createdAtEpochMs: Long,
    val acceptedAtEpochMs: Long? = null,
    val endedAtEpochMs: Long? = null,
) {
    val active: Boolean get() = status == RelationshipStatus.ACTIVE
}

/** Choice of which capabilities an invitation grants (selected at invite time). */
@Serializable
data class InviteCapabilitySelection(
    val kind: PairingKind,
    val capabilities: Set<PartnerCapability>,
    /** True if the invite opts into exact-domain sharing (default false). */
    val shareExactDomain: Boolean = false,
    val label: String? = null,
)

/**
 * Sensitive changes that require partner approval when the relationship grants the
 * matching capability. Distinct from parental-control policy exemptions: this is a
 * voluntary adult Accountability approval, never a bypass of self-protection.
 */
enum class SensitiveChange {
    REPLACE_PROTECTED_DEVICE,
    CHANGE_ACCOUNTABILITY_PARTNER,
    DISABLE_ACCOUNTABILITY_ALERTS,
    DISABLE_PROTECTION,
    EXTEND_RELATIONSHIP,
}

@Serializable
data class ApprovalRequest(
    val id: String,
    val relationshipId: String,
    val requestedByDeviceId: String,
    val change: SensitiveChange,
    val description: String,
    val requestedAtEpochMs: Long,
    val status: ApprovalStatus = ApprovalStatus.PENDING,
    val decidedByDeviceId: String? = null,
    val decidedAtEpochMs: Long? = null,
)

enum class ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED,
    REVOKED,
}

@Serializable
data class ApprovalDecision(
    val request: ApprovalRequest,
    val approved: Boolean,
    val decidedByDeviceId: String,
    val decidedAtEpochMs: Long,
)