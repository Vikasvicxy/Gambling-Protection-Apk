package dev.gamblock.data.accountability

import dev.gamblock.core.accountability.DeviceIdentityService
import dev.gamblock.core.accountability.EventGrouper
import dev.gamblock.core.accountability.HeartbeatEngine
import dev.gamblock.core.accountability.NotificationPolicyEngine
import dev.gamblock.core.accountability.PairingTokenService
import dev.gamblock.core.accountability.PartnerRelationshipManager
import dev.gamblock.core.accountability.ParentAuthorization
import dev.gamblock.core.accountability.ReplacementService
import dev.gamblock.core.accountability.SensitiveChangePolicy
import dev.gamblock.core.model.AccountabilityEvent
import dev.gamblock.core.model.AccountabilityEventType
import dev.gamblock.core.model.AccountabilitySeverity
import dev.gamblock.core.model.ApprovalRequest
import dev.gamblock.core.model.ApprovalStatus
import dev.gamblock.core.model.EventGroupResult
import dev.gamblock.core.model.HeartbeatEvaluation
import dev.gamblock.core.model.HeartbeatState
import dev.gamblock.core.model.HeartbeatStatus
import dev.gamblock.core.model.InviteCapabilitySelection
import dev.gamblock.core.model.PairingKind
import dev.gamblock.core.model.PairingToken
import dev.gamblock.core.model.PartnerCapability
import dev.gamblock.core.model.PartnerRelationship
import dev.gamblock.core.model.PartnerRole
import dev.gamblock.core.model.RelationshipStatus
import dev.gamblock.core.model.SensitiveChange
import dev.gamblock.data.accountability.db.dao.ApprovalRequestDao
import dev.gamblock.data.accountability.db.dao.EventGroupingDao
import dev.gamblock.data.accountability.db.dao.HeartbeatDao
import dev.gamblock.data.accountability.db.dao.PairingTokenDao
import dev.gamblock.data.accountability.db.dao.PartnerRelationshipDao
import dev.gamblock.data.accountability.db.dao.ReplacementRequestDao
import dev.gamblock.data.accountability.db.entity.ApprovalRequestEntity
import dev.gamblock.data.accountability.db.entity.EventGroupingEntity
import dev.gamblock.data.accountability.db.entity.HeartbeatEntity
import dev.gamblock.data.accountability.db.entity.PairingTokenEntity
import dev.gamblock.data.accountability.db.entity.PartnerRelationshipEntity
import dev.gamblock.data.accountability.db.entity.ReplacementRequestEntity
import dev.gamblock.data.accountability.db.entity.toEntity
import dev.gamblock.data.accountability.db.entity.toModel
import dev.gamblock.core.model.DeviceReplacementRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single entry point for the accountability/parent/partner subsystem on a device.
 *
 * Owns secure pairing, relationship state, heartbeat tracking, event grouping and
 * approvals. All authorization decisions short-circuit through the pure-JVM managers so
 * device and unit tests exercise identical logic.
 */
@Singleton
class AccountabilityRepository @Inject constructor(
    private val pairingTokenDao: PairingTokenDao,
    private val relationshipDao: PartnerRelationshipDao,
    private val heartbeatDao: HeartbeatDao,
    private val groupingDao: EventGroupingDao,
    private val approvalDao: ApprovalRequestDao,
    private val replacementDao: ReplacementRequestDao,
    private val pairingTokens: PairingTokenService,
    private val relationships: PartnerRelationshipManager,
    private val heartbeats: HeartbeatEngine,
    private val grouper: EventGrouper,
    private val notificationPolicy: NotificationPolicyEngine,
    private val parentAuthz: ParentAuthorization,
    private val deviceIdentity: DeviceIdentityService,
    private val replacements: ReplacementService,
) {

    // ---- Pairing ----

    sealed interface PairingResult {
        data class Created(
            val token: PairingToken,
            val tokenHash: String,
            val stored: Boolean,
        ) : PairingResult

        data class Rejected(val reason: String) : PairingResult
    }

    sealed interface AcceptOutcome {
        data class Linked(
            val relationshipId: String,
            val partnerDeviceId: String,
            val role: PartnerRole,
            val acceptedAtEpochMs: Long,
        ) : AcceptOutcome

        data class Rejected(val reason: String) : AcceptOutcome
    }

    /** Creates and persists a pairing invite for [protectedDeviceId]. */
    suspend fun createInvitation(
        protectedDeviceId: String,
        kind: PairingKind,
        selection: InviteCapabilitySelection,
        nowEpochMs: Long,
    ): PairingResult {
        val result = pairingTokens.create(protectedDeviceId, kind, selection, nowEpochMs)
        return when (result) {
            is PairingTokenService.CreateResult.Rejected -> PairingResult.Rejected(result.reason)
            is PairingTokenService.CreateResult.Created -> {
                val token = result.token
                // Relationship created in PENDING; server stores only the token hash.
                val relResult = relationships.create(
                    protectedDeviceId = protectedDeviceId,
                    partnerDeviceId = "", // reserved for accept
                    role = if (kind == PairingKind.PARENT_SUPERVISED_CHILD)
                        PartnerRole.PARENT_OR_GUARDIAN
                    else
                        PartnerRole.TRUSTED_PARTNER,
                    capabilities = selection.capabilities,
                    nowEpochMs = nowEpochMs,
                )
                val stored = when (relResult) {
                    is PartnerRelationshipManager.CreateResult.Rejected -> false
                    is PartnerRelationshipManager.CreateResult.Success -> {
                        relationshipDao.upsert(relResult.relationship.toEntity())
                        pairingTokenDao.upsert(
                            PairingTokenEntity(
                                id = token.id,
                                tokenHash = PairingTokenService.hash(token.fullToken),
                                kind = token.kind,
                                createdByDeviceId = token.createdByDeviceId,
                                createdAtEpochMs = token.createdAtEpochMs,
                                expiresAtEpochMs = token.expiresAtEpochMs,
                                label = token.label,
                            ),
                        )
                        true
                    }
                }
                PairingResult.Created(token, result.tokenHash, stored)
            }
        }
    }

    /**
     * Accepts a pairing invitation. The [storedTokenHash] must be the SHA-256 of the full
     * token, matching the create-time record. Tokens are single-use and expire.
     */
    suspend fun acceptInvitation(
        fullToken: String,
        partnerDeviceId: String,
        nowEpochMs: Long,
    ): AcceptOutcome {
        val tokenHash = PairingTokenService.hash(fullToken)
        val stored = pairingTokenDao.byHash(tokenHash) ?: return AcceptOutcome.Rejected("unknown token")
        if (stored.consumedAtEpochMs != null) return AcceptOutcome.Rejected("token already used")
        if (stored.expiresAtEpochMs < nowEpochMs) return AcceptOutcome.Rejected("token expired")

        val relationshipRecord = relationshipDao
            .forProtectedDevice(stored.createdByDeviceId)
            .firstOrNull { it.status == RelationshipStatus.PENDING }
            ?: return AcceptOutcome.Rejected("no pending relationship for invite")

        val verified = relationships.acceptPending(
            relationship = relationshipRecord.toModel().copy(
                partnerDeviceId = partnerDeviceId,
                status = RelationshipStatus.PENDING,
            ),
            actingDeviceId = partnerDeviceId,
            nowEpochMs = nowEpochMs,
        )
        return when (verified) {
            is PartnerRelationshipManager.AcceptResult.Rejected -> AcceptOutcome.Rejected(verified.reason)
            is PartnerRelationshipManager.AcceptResult.Success -> {
                // Mark token consumed (single-use) and link the relationship.
                pairingTokenDao.update(stored.copy(consumedAtEpochMs = nowEpochMs))
                relationshipDao.upsert(verified.relationship.toEntity())
                AcceptOutcome.Linked(
                    relationshipId = verified.relationship.id,
                    partnerDeviceId = partnerDeviceId,
                    role = verified.relationship.role,
                    acceptedAtEpochMs = nowEpochMs,
                )
            }
        }
    }

    fun observeRelationships(): Flow<List<PartnerRelationship>> =
        relationshipDao.observeAll().map { list -> list.map { it.toModel() } }

    // ---- Heartbeat ----

    sealed interface HeartbeatUploadResult {
        data class Accepted(val state: HeartbeatState) : HeartbeatUploadResult
        data class Rejected(val reason: String) : HeartbeatUploadResult
    }

    /**
     * Records/replaces a protected device's heartbeat. Replay protection: a sequence not
     * strictly newer than the existing one is rejected.
     */
    suspend fun uploadHeartbeat(heartbeat: HeartbeatState): HeartbeatUploadResult {
        val existing = heartbeatDao.byDeviceId(heartbeat.deviceId)?.toModel()
        if (heartbeats.isReplay(existing, heartbeat)) {
            return HeartbeatUploadResult.Rejected("heartbeat sequence not newer")
        }
        heartbeatDao.upsert(heartbeat.toEntity())
        return HeartbeatUploadResult.Accepted(heartbeat)
    }

    suspend fun observeHeartbeat(deviceId: String): Flow<HeartbeatState?> =
        heartbeatDao.observeByDeviceId(deviceId).map { it?.toModel() }

    /** Evaluates the stored heartbeat for [deviceId] against the current clock. */
    suspend fun evaluateHeartbeat(deviceId: String): HeartbeatEvaluation {
        val stored = heartbeatDao.byDeviceId(deviceId)?.toModel()
        return heartbeats.evaluate(stored)
    }

    /** Replaces [deviceId]'s heartbeat with a sequence superscripted (used by engine). */
    suspend fun replaceHeartbeat(deviceId: String, state: HeartbeatState) {
        heartbeatDao.upsert(state.toEntity())
    }

    // ---- Event grouping ----

    /**
     * Aggregates an occurrence and, when a group should emit, persists the updated group
     * and returns the notification-level event to send.
     */
    suspend fun aggregateEventOccurrence(
        deviceId: String,
        type: AccountabilityEventType,
        category: dev.gamblock.core.model.Category?,
        nowEpochMs: Long,
    ): AccountabilityEvent? {
        val key = grouper.groupKey(deviceId, type, category)
        val current = groupingDao.byKey(key)?.toModel()
        val outcome = grouper.aggregate(current, deviceId, type, category, nowEpochMs)
        return when (outcome) {
            is EventGroupResult.Emitted -> {
                groupingDao.upsert(outcome.state.toEntity())
                outcome.event
            }
            is EventGroupResult.Suppressed -> {
                groupingDao.upsert(outcome.state.toEntity())
                null
            }
            is EventGroupResult.ResetThenSuppressed -> {
                groupingDao.upsert(outcome.state.toEntity())
                null
            }
        }
    }

    suspend fun currentGroupState(groupKey: String) = groupingDao.byKey(groupKey)?.toModel()

    // ---- Approvals ----

    sealed interface ApprovalOutcome {
        data class Pending(val request: ApprovalRequest) : ApprovalOutcome
        data class Decided(val request: ApprovalRequest) : ApprovalOutcome
        data class Rejected(val reason: String) : ApprovalOutcome
    }

    suspend fun requestApproval(
        relationshipId: String,
        protectedDeviceId: String,
        change: SensitiveChange,
        description: String,
        nowEpochMs: Long,
    ): ApprovalOutcome {
        val rel = relationshipDao.byId(relationshipId)?.toModel()
            ?: return ApprovalOutcome.Rejected("unknown relationship")
        val authz = relationships.authorize(rel, protectedDeviceId)
        if (authz is PartnerRelationshipManager.AuthorizationResult.Denied) {
            return ApprovalOutcome.Rejected(authz.reason)
        }
        val required = SensitiveChangePolicy.capabilityRequired(change)
        if (required != null && required !in rel.capabilities) {
            return ApprovalOutcome.Rejected("partner lacks capability to approve this change")
        }
        val request = ApprovalRequest(
            id = "ap-${nowEpochMs}-${relationshipId.hashCode()}",
            relationshipId = relationshipId,
            requestedByDeviceId = protectedDeviceId,
            change = change,
            description = description,
            requestedAtEpochMs = nowEpochMs,
            status = ApprovalStatus.PENDING,
        )
        approvalDao.upsert(request.toEntity())
        return ApprovalOutcome.Pending(request)
    }

    suspend fun decideApproval(
        requestId: String,
        actingDeviceId: String,
        approved: Boolean,
        nowEpochMs: Long,
    ): ApprovalOutcome {
        val request = approvalDao.byId(requestId)?.toModel()
            ?: return ApprovalOutcome.Rejected("unknown approval request")
        if (request.status != ApprovalStatus.PENDING) {
            return ApprovalOutcome.Rejected("approval already decided")
        }
        val rel = relationshipDao.byId(request.relationshipId)?.toModel()
            ?: return ApprovalOutcome.Rejected("unknown relationship")
        if (actingDeviceId == request.requestedByDeviceId) {
            return ApprovalOutcome.Rejected("requester cannot decide their own request")
        }
        val authz = relationships.authorize(rel, actingDeviceId)
        if (authz is PartnerRelationshipManager.AuthorizationResult.Denied) {
            return ApprovalOutcome.Rejected(authz.reason)
        }
        val required = SensitiveChangePolicy.capabilityRequired(request.change)
        if (required != null && required !in rel.capabilities) {
            return ApprovalOutcome.Rejected("actor lacks capability for this change")
        }
        val decided = request.copy(
            status = if (approved) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED,
            decidedByDeviceId = actingDeviceId,
            decidedAtEpochMs = nowEpochMs,
        )
        approvalDao.upsert(decided.toEntity())
        return ApprovalOutcome.Decided(decided)
    }

    fun observeApprovals(): Flow<List<ApprovalRequest>> =
        approvalDao.observeAll().map { list -> list.map { it.toModel() } }

    // ---- Device replacement ----

    sealed interface ReplacementOutcome {
        data class Created(val request: DeviceReplacementRequest) : ReplacementOutcome
        data class Confirmed(val request: DeviceReplacementRequest) : ReplacementOutcome
        data class Rejected(val reason: String) : ReplacementOutcome
    }

    @Throws(Exception::class)
    suspend fun createReplacementRequest(
        oldDeviceId: String,
        newDeviceId: String,
        remainingCommitmentMillis: Long,
        nowEpochMs: Long,
    ): ReplacementOutcome {
        if (oldDeviceId.isBlank() || newDeviceId.isBlank()) {
            return ReplacementOutcome.Rejected("blank device id")
        }
        if (oldDeviceId == newDeviceId) {
            return ReplacementOutcome.Rejected("replacement must use a different device")
        }
        val policy = replacements.evaluate(remainingCommitmentMillis)
        return when (policy) {
            is dev.gamblock.core.model.ReplacementDecision.Denied ->
                ReplacementOutcome.Rejected(policy.reason)
            is dev.gamblock.core.model.ReplacementDecision.Allowed -> {
                val request = DeviceReplacementRequest(
                    id = "rel-${nowEpochMs}-${oldDeviceId.hashCode()}",
                    oldDeviceId = oldDeviceId,
                    newDeviceId = newDeviceId,
                    remainingCommitmentMillis = remainingCommitmentMillis,
                    requestedAtEpochMs = nowEpochMs,
                    status = dev.gamblock.core.model.DeviceReplacementState.AWAITING_CONFIRMATION,
                    confirmsNeeded = 1,
                )
                replacementDao.upsert(request.toEntity())
                ReplacementOutcome.Created(request)
            }
        }
    }

    suspend fun confirmReplacement(
        requestId: String,
        actingDeviceId: String,
        nowEpochMs: Long,
    ): ReplacementOutcome {
        val existing = replacementDao.byId(requestId)?.toModel()
            ?: return ReplacementOutcome.Rejected("unknown replacement request")
        val outcome = replacements.confirm(existing, actingDeviceId, nowEpochMs)
        return when (outcome) {
            is ReplacementService.ConfirmResult.Rejected -> ReplacementOutcome.Rejected(outcome.reason)
            is ReplacementService.ConfirmResult.Success -> {
                replacementDao.upsert(outcome.request.toEntity())
                ReplacementOutcome.Confirmed(outcome.request)
            }
        }
    }

    fun observeReplacementRequests(): Flow<List<DeviceReplacementRequest>> =
        replacementDao.observeAll().map { list -> list.map { it.toModel() } }

    // ---- Parent helpers ----

    fun parentAuthz(): ParentAuthorization = parentAuthz

    // ---- Device identity ----

    fun deviceIdentity(): DeviceIdentityService = deviceIdentity

    fun relationshipsForProtected(deviceId: String): Flow<List<PartnerRelationship>> =
        relationshipDao.observeActive()
            .map { list -> list.map { it.toModel() }.filter { it.protectedDeviceId == deviceId } }
}