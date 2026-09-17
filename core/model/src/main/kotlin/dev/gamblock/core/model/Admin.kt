package dev.gamblock.core.model

import kotlinx.serialization.Serializable

/**
 * Phase 3 Admin / moderation / audit-log models.
 *
 * Admin console concepts are kept small and privacy-safe: no personal browsing data
 * exists anywhere, so nothing personal can be exposed. Every administrative action is
 * audit logged. Strong auth is expected at the transport/API layer (MFA/passkey-ready,
 * server-side roles, sessions, CSRF, rate limiting).
 */

/** Server-side admin roles. Never trusted from the client alone. */
enum class AdminRole {
    VIEWER,
    MODERATOR,
    PUBLISHER,
    SUPER_ADMIN,
}

/** Lifecycle of a candidate domain under review. */
enum class CandidateStatus {
    NEW_CANDIDATE,
    INVESTIGATING,
    APPROVED,
    REJECTED,
    ALLOWLISTED,
    DISABLED,
}

/** Audit-logged actions taken by an admin. */
enum class AuditAction {
    DOMAIN_APPROVED,
    DOMAIN_REJECTED,
    DOMAIN_DISABLED,
    ALLOWLIST_CHANGE,
    FALSE_POSITIVE_VERIFIED_BLOCK,
    FALSE_POSITIVE_ALLOWLISTED,
    RELEASE_BUILT,
    RELEASE_PUBLISHED,
    ROLLBACK_TRIGGERED,
    ADMIN_ROLE_CHANGED,
    PARTNER_RELATIONSHIP_CHANGED,
    REPORT_FLAGGED,
    CANDIDATE_OPENED,
    CANDIDATE_INVESTIGATED,
    SOURCE_ADDED,
    SOURCE_DISABLED,
}

/**
 * One entry in the audit log. Deliberately excludes personal/sensitive data beyond an
 * actor id and opaque target references.
 */
@Serializable
data class AuditLogEntry(
    val id: String,
    val actorId: String,
    val action: AuditAction,
    val targetType: String? = null,
    val targetId: String? = null,
    val detail: Map<String, String> = emptyMap(),
    val occurredAtEpochMs: Long,
)

/** Domain candidate under moderation review. */
@Serializable
data class DomainCandidate(
    val id: String,
    val domain: String,
    val normalizedDomain: String,
    val category: Category,
    val submittedBy: String? = null,
    val source: String? = null,
    val confidence: Confidence = Confidence.UNKNOWN,
    val evidence: List<String> = emptyList(),
    val provenance: List<String> = emptyList(),
    val status: CandidateStatus = CandidateStatus.NEW_CANDIDATE,
    val reportCount: Int = 0,
    val createdAtEpochMs: Long,
) {
    companion object {
        const val MAX_EVIDENCE_ITEMS = 8
    }
}

/** False-positive moderation case (domain reported as incorrectly blocked). */
@Serializable
data class FalsePositiveCase(
    val id: String,
    val domain: String,
    val normalizedDomain: String,
    val category: Category,
    val databaseVersion: Int,
    val reportCount: Int,
    val sources: List<String> = emptyList(),
    val confidence: Confidence = Confidence.UNKNOWN,
    val status: CandidateStatus = CandidateStatus.NEW_CANDIDATE,
    val falsePositiveHistoryCount: Int = 0,
    val createdAtEpochMs: Long,
)

/** Moderation actions available at each workflow step. */
@Serializable
enum class ModerationAction {
    APPROVE,
    REJECT,
    INVESTIGATE,
    VERIFY_BLOCK,
    ALLOWLIST,
    DISABLE,
    REOPEN,
}

/** A domain/report aggregation row shown in admin UI (never personal data). */
@Serializable
data class AdminDomainRow(
    val domain: String,
    val normalizedDomain: String,
    val category: Category,
    val status: BlockStatus,
    val confidence: Confidence,
    val sourceIds: List<String> = emptyList(),
    val databaseVersion: Int,
    val falsePositiveReports: Int = 0,
    val lastSeenEpochMs: Long = 0L,
)

/** A single inbound user report (new gambling site / false positive). */
@Serializable
data class InboundReport(
    val id: String,
    val type: ReportType,
    val domain: String,
    val normalizedDomain: String,
    val category: Category,
    val note: String? = null,
    val anonymousDeviceId: String? = null,
    val createdAtEpochMs: Long,
) {
    enum class ReportType {
        NEW_GAMBLING_SITE,
        FALSE_POSITIVE,
    }
}