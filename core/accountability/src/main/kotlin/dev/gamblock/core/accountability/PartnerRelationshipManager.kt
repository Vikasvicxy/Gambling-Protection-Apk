package dev.gamblock.core.accountability

import dev.gamblock.core.model.AccountabilityEvent
import dev.gamblock.core.model.PartnerCapability
import dev.gamblock.core.model.PartnerRelationship
import dev.gamblock.core.model.PartnerRole
import dev.gamblock.core.model.RelationshipStatus
import dev.gamblock.core.model.ShieldRole

/**
 * Partner relationship lifecycle and authorization.
 *
 * Authorization is performed server-side from stored relationship state, never from
 * client-declared role claims. A partner may only ever act against their OWN linked
 * relationship; a parent may only ever view/anoint the specific child devices linked to
 * their account. Protected users can never assert an admin role.
 */
class PartnerRelationshipManager {

    sealed interface CreateResult {
        data class Success(val relationship: PartnerRelationship) : CreateResult
        data class Rejected(val reason: String) : CreateResult
    }

    sealed interface AcceptResult {
        data class Success(val relationship: PartnerRelationship) : AcceptResult
        data class Rejected(val reason: String) : AcceptResult
    }

    sealed interface AuthorizationResult {
        data class Granted(val relationship: PartnerRelationship) : AuthorizationResult
        data class Denied(val reason: String) : AuthorizationResult
    }

    fun create(
        protectedDeviceId: String,
        partnerDeviceId: String,
        role: PartnerRole,
        capabilities: Set<PartnerCapability>,
        nowEpochMs: Long,
    ): CreateResult {
        if (protectedDeviceId.isBlank() || partnerDeviceId.isBlank()) {
            return CreateResult.Rejected("blank device id")
        }
        if (protectedDeviceId == partnerDeviceId) {
            return CreateResult.Rejected("a device cannot be its own partner")
        }
        if (role == PartnerRole.TRUSTED_PARTNER && capabilities.isEmpty()) {
            return CreateResult.Rejected("accountability partner requires at least one capability")
        }
        return CreateResult.Success(
            PartnerRelationship(
                id = newId(),
                protectedDeviceId = protectedDeviceId,
                partnerDeviceId = partnerDeviceId,
                role = role,
                status = RelationshipStatus.PENDING,
                capabilities = capabilities,
                createdAtEpochMs = nowEpochMs,
            ),
        )
    }

    fun acceptPending(
        relationship: PartnerRelationship,
        actingDeviceId: String,
        nowEpochMs: Long,
    ): AcceptResult {
        // The partner must actively accept; the protected device must not self-accept.
        if (actingDeviceId == relationship.protectedDeviceId) {
            return AcceptResult.Rejected("protected user cannot accept their own invite")
        }
        if (actingDeviceId != relationship.partnerDeviceId) {
            return AcceptResult.Rejected("unrelated device cannot accept this invite")
        }
        if (relationship.status != RelationshipStatus.PENDING) {
            return AcceptResult.Rejected("relationship is not pending")
        }
        return AcceptResult.Success(
            relationship.copy(
                status = RelationshipStatus.ACTIVE,
                acceptedAtEpochMs = nowEpochMs,
            ),
        )
    }

    /**
     * Scope-authorized access check: [actor] wants to act on a relationship. The actor
     * must be one of the two linked devices AND the relationship must be active.
     */
    fun authorize(
        relationship: PartnerRelationship,
        actorDeviceId: String,
    ): AuthorizationResult {
        if (relationship.status != RelationshipStatus.ACTIVE) {
            return AuthorizationResult.Denied("relationship not active")
        }
        if (relationship.pausedByProtectedUser) {
            return AuthorizationResult.Denied("relationship paused")
        }
        if (actorDeviceId != relationship.protectedDeviceId &&
            actorDeviceId != relationship.partnerDeviceId
        ) {
            return AuthorizationResult.Denied("actor is not part of this relationship")
        }
        return AuthorizationResult.Granted(relationship)
    }

    /**
     * Whether an actor possessing [relationshipCapabilities] may receive [event].
     * Event types map to capabilities; unknown mappings are deniable by default
     * (least privilege).
     */
    fun mayReceiveEvent(
        event: AccountabilityEvent,
        relationshipCapabilities: Set<PartnerCapability>,
    ): Boolean {
        val capability = when (event.type) {
            dev.gamblock.core.model.AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED,
            dev.gamblock.core.model.AccountabilityEventType.REPEATED_GAMBLING_ATTEMPTS_BLOCKED,
            dev.gamblock.core.model.AccountabilityEventType.GAMBLING_APP_ATTEMPT_BLOCKED,
            ->
                PartnerCapability.RECEIVE_GAMBLING_ATTEMPTS
            dev.gamblock.core.model.AccountabilityEventType.VPN_UNEXPECTEDLY_INACTIVE,
            dev.gamblock.core.model.AccountabilityEventType.PROTECTION_DEGRADED,
            dev.gamblock.core.model.AccountabilityEventType.REQUIRED_PERMISSION_REMOVED,
            dev.gamblock.core.model.AccountabilityEventType.PROTECTION_CONFIRMED_ACTIVE,
            ->
                PartnerCapability.RECEIVE_PROTECTION_ALERTS
            dev.gamblock.core.model.AccountabilityEventType.HEARTBEAT_LOST ->
                PartnerCapability.RECEIVE_HEARTBEAT_ALERTS
            dev.gamblock.core.model.AccountabilityEventType.TAMPER_EVIDENCE_GENERATED ->
                PartnerCapability.RECEIVE_TAMPER_ALERTS
            dev.gamblock.core.model.AccountabilityEventType.PROTECTED_DEVICE_REPLACED ->
                PartnerCapability.RECEIVE_REPLACEMENT_NOTICES
        }
        return capability in relationshipCapabilities
    }

    /**
     * Device-role authorization for dashboard views: a [ShieldRole] acting as a device
     * may only see their own scope. Client-declared admin is always rejected.
     */
    fun authorizeRole(
        declaredRole: ShieldRole,
        actingDeviceId: String,
        targetDeviceId: String,
    ): AuthorizationResult {
        if (declaredRole == ShieldRole.ADMIN) {
            // Admins are validated against server-side sessions, never device claims.
            return AuthorizationResult.Denied("admin role requires server-side session")
        }
        return when (declaredRole) {
            ShieldRole.PROTECTED_USER ->
                if (actingDeviceId == targetDeviceId)
                    AuthorizationResult.Granted(PartnerRelationship("", actingDeviceId, "", PartnerRole.TRUSTED_PARTNER, capabilities = emptySet(), createdAtEpochMs = 0L))
                else
                    AuthorizationResult.Denied("protected user scope mismatch")

            ShieldRole.TRUSTED_PARTNER,
            ShieldRole.PARENT_OR_GUARDIAN,
            ->
                AuthorizationResult.Granted(PartnerRelationship("", actingDeviceId, "", PartnerRole.TRUSTED_PARTNER, capabilities = emptySet(), createdAtEpochMs = 0L))

            ShieldRole.ADMIN -> AuthorizationResult.Denied("admin role requires server-side session")
        }
    }

    /** Client role-claims are never trusted directly; see [authorizeRole]. */
    fun assertNotAdmin(clientRole: ShieldRole): Boolean = clientRole != ShieldRole.ADMIN

    private var idCounter = 0L
    private fun newId(): String {
        idCounter += 1
        return "rel-${System.nanoTime()}-${idCounter}"
    }
}