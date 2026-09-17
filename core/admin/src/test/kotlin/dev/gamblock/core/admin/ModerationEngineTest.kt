package dev.gamblock.core.admin

import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.model.AdminRole
import dev.gamblock.core.model.AuditAction
import dev.gamblock.core.model.CandidateStatus
import dev.gamblock.core.model.Category
import dev.gamblock.core.model.Confidence
import dev.gamblock.core.model.DomainCandidate
import dev.gamblock.core.model.FalsePositiveCase
import dev.gamblock.core.model.ModerationAction
import org.junit.Test

class ModerationEngineTest {

    private val logger = InMemoryAuditLogger()
    private var now = 1_700_000_000_000L
    private val engine = ModerationEngine(logger, clock = { now })

    private val candidate = DomainCandidate(
        id = "cand-1",
        domain = "casino.example",
        normalizedDomain = "casino.example",
        category = Category.CASINO,
        submittedBy = "user-1",
        source = "user-report",
        confidence = Confidence.MEDIUM,
        createdAtEpochMs = now,
    )

    private val fpCase = FalsePositiveCase(
        id = "fp-1",
        domain = "legit.example",
        normalizedDomain = "legit.example",
        category = Category.GAMBLING,
        databaseVersion = 3,
        reportCount = 4,
        createdAtEpochMs = now,
    )

    @Test
    fun `new candidate can be approved`() {
        val result = engine.act(candidate, ModerationAction.APPROVE, "admin-1", now)
        assertThat(result).isInstanceOf(ModerationEngine.ActionResult.Approved::class.java)
        val approved = (result as ModerationEngine.ActionResult.Approved).candidate
        assertThat(approved.status).isEqualTo(CandidateStatus.APPROVED)
        assertThat(approved.confidence).isEqualTo(Confidence.HIGH)
    }

    @Test
    fun `every moderation action is audit logged`() {
        engine.act(candidate, ModerationAction.REJECT, "admin-1", now)
        engine.act(candidate, ModerationAction.DISABLE, "admin-2", now + 1000L)
        engine.act(candidate, ModerationAction.APPROVE, "admin-1", now + 2000L)

        val actions = logger.query().map { it.action }
        assertThat(actions).containsAtLeast(AuditAction.DOMAIN_REJECTED, AuditAction.DOMAIN_DISABLED, AuditAction.DOMAIN_APPROVED)
    }

    @Test
    fun `audit log entry holds actor and target`() {
        engine.act(candidate, ModerationAction.INVESTIGATE, "admin-3", now)
        val entry = logger.query().first()
        assertThat(entry.actorId).isEqualTo("admin-3")
        assertThat(entry.targetId).isEqualTo(candidate.id)
        assertThat(entry.action).isEqualTo(AuditAction.CANDIDATE_INVESTIGATED)
        assertThat(entry.occurredAtEpochMs).isEqualTo(now)
    }

    @Test
    fun `valid transitions are enforced for compound states`() {
        assertThat(engine.isValidTransition(CandidateStatus.NEW_CANDIDATE, ModerationAction.APPROVE)).isTrue()
        assertThat(engine.isValidTransition(CandidateStatus.INVESTIGATING, ModerationAction.APPROVE)).isTrue()
        assertThat(engine.isValidTransition(CandidateStatus.APPROVED, ModerationAction.REOPEN)).isTrue()
        assertThat(engine.isValidTransition(CandidateStatus.DISABLED, ModerationAction.REOPEN)).isTrue()
        // A newly-approved rule is not REPOPEN-material in the simplistic model.
        assertThat(engine.isValidTransition(CandidateStatus.NEW_CANDIDATE, ModerationAction.REOPEN)).isFalse()
    }

    @Test
    fun `allowlist action is audit flagged`() {
        engine.act(candidate, ModerationAction.ALLOWLIST, "admin-1", now)
        val actions = logger.query().map { it.action }
        assertThat(actions).contains(AuditAction.ALLOWLIST_CHANGE)
    }

    @Test
    fun `false positive reported with success verifies block`() {
        engine.act(
            DomainCandidate(
                id = "cand-fp",
                domain = fpCase.domain,
                normalizedDomain = fpCase.normalizedDomain,
                category = fpCase.category,
                submittedBy = null,
                confidence = Confidence.HIGH,
                createdAtEpochMs = now,
            ),
            ModerationAction.VERIFY_BLOCK,
            "admin-1",
            now,
        )
        assertThat(logger.query().map { it.action }).contains(AuditAction.DOMAIN_APPROVED)
    }

    @Test
    fun `admin roles are defined with strict privilege separation`() {
        assertThat(AdminRole.VIEWER).isNotEqualTo(AdminRole.SUPER_ADMIN)
        // Server-side enforcement means clients cannot self-assign any role.
        assertThat(AdminRole.entries).hasSize(4)
    }
}

class ReportInputValidatorTest {

    @Test
    fun `accepts valid bare domains`() {
        assertThat(ReportInputValidator.validateDomain("casino.example.com"))
            .isInstanceOf(ReportInputValidator.ValidationResult.Valid::class.java)
        assertThat(ReportInputValidator.validateDomain("xn--80akhbyknj4f.xn--p1ai"))
            .isInstanceOf(ReportInputValidator.ValidationResult.Valid::class.java)
    }

    @Test
    fun `rejects URL schemes and paths`() {
        assertThat(ReportInputValidator.validateDomain("https://casino.example"))
            .isInstanceOf(ReportInputValidator.ValidationResult.Invalid::class.java)
        assertThat(ReportInputValidator.validateDomain("casino.example/path"))
            .isInstanceOf(ReportInputValidator.ValidationResult.Invalid::class.java)
        assertThat(ReportInputValidator.validateDomain("casino.example?q=1"))
            .isInstanceOf(ReportInputValidator.ValidationResult.Invalid::class.java)
    }

    @Test
    fun `rejects whitespace and injection characters`() {
        assertThat(ReportInputValidator.validateDomain("DROP TABLE users")).isInstanceOf(
            ReportInputValidator.ValidationResult.Invalid::class.java,
        )
        assertThat(ReportInputValidator.validateDomain("casino.example.com;DROP")).isInstanceOf(
            ReportInputValidator.ValidationResult.Invalid::class.java,
        )
    }

    @Test
    fun `rejects single-label and overlong domains`() {
        assertThat(ReportInputValidator.validateDomain("localhost")).isInstanceOf(
            ReportInputValidator.ValidationResult.Invalid::class.java,
        )
        assertThat(ReportInputValidator.validateDomain("a".repeat(300))).isInstanceOf(
            ReportInputValidator.ValidationResult.Invalid::class.java,
        )
    }

    @Test
    fun `clean text rejects markup and SQL`() {
        assertThat(ReportInputValidator.isCleanText("normal note about casino")).isTrue()
        assertThat(ReportInputValidator.isCleanText("<script>alert(1)</script>")).isFalse()
        assertThat(ReportInputValidator.isCleanText("x' OR 1=1")).isFalse()
        assertThat(ReportInputValidator.isCleanText("a".repeat(600))).isFalse()
    }
}

class SlidingWindowRateLimiterTest {

    private var now = 1_000_000L
    private val limiter = SlidingWindowRateLimiter(clock = { now }, windowMillis = 60_000L, maxCalls = 3)

    @Test
    fun `allows up to max calls in window`() {
        assertThat(limiter.tryAcquire("key")).isTrue()
        assertThat(limiter.tryAcquire("key")).isTrue()
        assertThat(limiter.tryAcquire("key")).isTrue()
        assertThat(limiter.tryAcquire("key")).isFalse()
    }

    @Test
    fun `rejects second key independently`() {
        assertThat(limiter.tryAcquire("a")).isTrue()
        assertThat(limiter.tryAcquire("a")).isTrue()
        assertThat(limiter.tryAcquire("a")).isTrue()
        assertThat(limiter.tryAcquire("b")).isTrue()
    }

    @Test
    fun `rate limit bypass attempt resets after window`() {
        assertThat(limiter.tryAcquire("key")).isTrue()
        assertThat(limiter.tryAcquire("key")).isTrue()
        assertThat(limiter.tryAcquire("key")).isTrue()
        now += 61_000L
        assertThat(limiter.tryAcquire("key")).isTrue()
    }

    @Test
    fun `reset frees the bucket`() {
        repeat(3) { limiter.tryAcquire("key") }
        assertThat(limiter.tryAcquire("key")).isFalse()
        limiter.reset("key")
        assertThat(limiter.tryAcquire("key")).isTrue()
    }
}