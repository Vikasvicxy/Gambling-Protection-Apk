package dev.gamblock.core.admin

import dev.gamblock.core.model.AdminRole
import dev.gamblock.core.model.AuditAction
import dev.gamblock.core.model.AuditLogEntry
import dev.gamblock.core.model.CandidateStatus
import dev.gamblock.core.model.Confidence
import dev.gamblock.core.model.DomainCandidate
import dev.gamblock.core.model.FalsePositiveCase
import dev.gamblock.core.model.ModerationAction
import java.util.concurrent.ConcurrentHashMap

/**
 * Admin moderation workflow.
 *
 * Candidate lifecycle: NEW_CANDIDATE -> automated evidence -> provenance -> confidence ->
 * category -> critical-safety checks -> APPROVE / REJECT / INVESTIGATE. Approved entries
 * flow into the existing signed release pipeline; this module never publishes directly -
 * the signed pipeline remains the only production publication path.
 *
 * Every mutation is audit-logged via [AuditLogger].
 */
class ModerationEngine(
    private val auditLogger: AuditLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    sealed interface ActionResult {
        data class Approved(val candidate: DomainCandidate) : ActionResult
        data class Rejected(val candidate: DomainCandidate) : ActionResult
        data class Disabled(val candidate: DomainCandidate) : ActionResult
        data class Allowlisted(val candidate: DomainCandidate) : ActionResult
        data class Investigating(val candidate: DomainCandidate) : ActionResult
        data class Reopened(val candidate: DomainCandidate) : ActionResult
    }

    private sealed interface InternalResult

    private fun approve(
        candidate: DomainCandidate,
        actorId: String,
        evidence: List<String>,
        provenance: List<String>,
        category: dev.gamblock.core.model.Category,
        nowEpochMs: Long,
        reportCount: Int,
    ): ActionResult {
        val updated = candidate.copy(
            status = CandidateStatus.APPROVED,
            confidence = Confidence.HIGH,
            provenance = provenance,
            category = category,
        )
        auditLogger.log(
            AuditLogEntry(
                id = newId(),
                actorId = actorId,
                action = AuditAction.DOMAIN_APPROVED,
                targetType = "domain",
                targetId = candidate.id,
                detail = mapOf(
                    "domain" to candidate.normalizedDomain,
                    "category" to candidate.category.name,
                ),
                occurredAtEpochMs = nowEpochMs,
            ),
        )
        return ActionResult.Approved(updated)
    }

    fun act(
        candidate: DomainCandidate,
        action: ModerationAction,
        actorId: String,
        nowEpochMs: Long,
    ): ActionResult = when (action) {
        ModerationAction.APPROVE,
        ModerationAction.VERIFY_BLOCK,
        ->
            approve(
                candidate,
                actorId,
                candidate.evidence,
                candidate.provenance,
                candidate.category,
                nowEpochMs,
                candidate.reportCount,
            )

        ModerationAction.REJECT ->
            audit(actorId, AuditAction.DOMAIN_REJECTED, candidate, nowEpochMs)
                .let { ActionResult.Rejected(it) }

        ModerationAction.DISABLE ->
            audit(actorId, AuditAction.DOMAIN_DISABLED, candidate, nowEpochMs)
                .let { ActionResult.Disabled(it) }

        ModerationAction.ALLOWLIST ->
            audit(actorId, AuditAction.ALLOWLIST_CHANGE, candidate, nowEpochMs)
                .let { ActionResult.Allowlisted(it) }

        ModerationAction.INVESTIGATE -> {
            val updated = candidate.copy(status = CandidateStatus.INVESTIGATING)
            auditLogger.log(
                AuditLogEntry(
                    id = newId(),
                    actorId = actorId,
                    action = AuditAction.CANDIDATE_INVESTIGATED,
                    targetType = "domain",
                    targetId = candidate.id,
                    detail = mapOf("domain" to candidate.normalizedDomain),
                    occurredAtEpochMs = nowEpochMs,
                ),
            )
            ActionResult.Investigating(updated)
        }

        ModerationAction.REOPEN ->
            audit(actorId, AuditAction.FALSE_POSITIVE_VERIFIED_BLOCK, candidate, nowEpochMs)
                .let { ActionResult.Reopened(it.copy(status = CandidateStatus.NEW_CANDIDATE)) }
    }

    private fun audit(
        actorId: String,
        action: AuditAction,
        candidate: DomainCandidate,
        nowEpochMs: Long,
    ): DomainCandidate {
        auditLogger.log(
            AuditLogEntry(
                id = newId(),
                actorId = actorId,
                action = action,
                targetType = "domain",
                targetId = candidate.id,
                detail = mapOf("domain" to candidate.normalizedDomain),
                occurredAtEpochMs = nowEpochMs,
            ),
        )
        return candidate
    }

    /** NEW_CANDIDATE -> APPROVED is only valid from a NEW/INVESTIGATING state. */
    fun isValidTransition(from: CandidateStatus, action: ModerationAction): Boolean {
        if (action == ModerationAction.REOPEN) {
            return from == CandidateStatus.APPROVED ||
                from == CandidateStatus.ALLOWLISTED ||
                from == CandidateStatus.DISABLED
        }
        return from == CandidateStatus.NEW_CANDIDATE || from == CandidateStatus.INVESTIGATING
    }

    private var idCounter = 0L
    private fun newId(): String {
        idCounter += 1
        return "evt-${System.nanoTime()}-$idCounter"
    }
}

/** Character set of a well-formed domain label. */
private val DOMAIN_LABEL_CHARS = Regex("^*[a-z0-9]([a-z0-9-]*[a-z0-9])?$")

/**
 * Server-side validation of URL/domain inputs. Refuses anything that is obviously not a
 * domain (no scheme, no port, no path, no query, no whitespace, idn/punycode friendly).
 */
object ReportInputValidator {
    const val MAX_LENGTH = 253

    sealed interface ValidationResult {
        data class Valid(val normalized: String) : ValidationResult
        data class Invalid(val reason: String) : ValidationResult
    }

    fun validateDomain(raw: String): ValidationResult {
        val trimmed = raw.trim().lowercase().removeSuffix(".")
        if (trimmed.isEmpty()) return ValidationResult.Invalid("empty domain")
        if (trimmed.length > MAX_LENGTH) return ValidationResult.Invalid("domain too long")
        if (trimmed.contains(' ') || trimmed.contains('\n') || trimmed.contains('\t')) {
            return ValidationResult.Invalid("whitespace not allowed")
        }
        if (!trimmed.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) {
            return ValidationResult.Invalid("invalid characters")
        }
        // Reject obvious URLs: schemes, ports, paths, queries.
        if (trimmed.contains("://") || trimmed.contains('/') || trimmed.contains('?') ||
            trimmed.contains('#') || trimmed.contains('@') || trimmed.contains(':')
        ) {
            return ValidationResult.Invalid("not a bare domain")
        }
        val labels = trimmed.split('.')
        if (labels.size < 2) return ValidationResult.Invalid("not a fully qualified domain")
        if (labels.any { it.isEmpty() }) return ValidationResult.Invalid("empty label")
        if (labels.any { it.length > 63 }) return ValidationResult.Invalid("label too long")
        return ValidationResult.Valid(trimmed)
    }

    /** Guards against injection-style inputs (SQL, HTML). */
    fun isCleanText(value: String, maxLength: Int = 500): Boolean {
        if (value.length > maxLength) return false
        if (value.contains("<") || value.contains(">") || value.contains(";") ||
            value.contains("--") || value.contains("'") || value.contains("\"")
        ) {
            return false
        }
        return true
    }
}

/** Sliding-window rate limiter (server-side). */
class SlidingWindowRateLimiter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val maxCalls: Int = DEFAULT_MAX_CALLS,
) {
    private val buckets = ConcurrentHashMap<String, MutableList<Long>>()

    fun tryAcquire(key: String): Boolean {
        val now = clock()
        val windowStart = now - windowMillis
        val calls = buckets.computeIfAbsent(key) { mutableListOf() }
        synchronized(calls) {
            calls.removeAll { it < windowStart }
            if (calls.size >= maxCalls) return false
            calls.add(now)
            return true
        }
    }

    fun reset(key: String) {
        buckets.remove(key)
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 60_000L
        const val DEFAULT_MAX_CALLS = 30
    }
}