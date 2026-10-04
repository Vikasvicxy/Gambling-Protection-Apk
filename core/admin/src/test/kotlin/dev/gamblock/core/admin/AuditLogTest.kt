package dev.gamblock.core.admin

import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.model.AuditAction
import dev.gamblock.core.model.AuditLogEntry
import org.junit.Test

class InMemoryAuditLoggerTest {

    private fun entry(
        id: String,
        action: AuditAction = AuditAction.DOMAIN_APPROVED,
        at: Long = 0L,
    ) = AuditLogEntry(
        id = id,
        actorId = "admin-1",
        action = action,
        targetType = "domain",
        targetId = id,
        occurredAtEpochMs = at,
    )

    @Test
    fun `starts empty`() {
        val logger = InMemoryAuditLogger()

        assertThat(logger.count()).isEqualTo(0)
        assertThat(logger.query()).isEmpty()
    }

    @Test
    fun `counts logged entries`() {
        val logger = InMemoryAuditLogger()
        logger.log(entry("a"))
        logger.log(entry("b"))

        assertThat(logger.count()).isEqualTo(2)
    }

    @Test
    fun `returns newest entries first`() {
        val logger = InMemoryAuditLogger()
        logger.log(entry("a", at = 1))
        logger.log(entry("b", at = 2))
        logger.log(entry("c", at = 3))

        assertThat(logger.query().map { it.id }).containsExactly("c", "b", "a").inOrder()
    }

    @Test
    fun `honours the query limit`() {
        val logger = InMemoryAuditLogger()
        (1..5).forEach { logger.log(entry("e$it", at = it.toLong())) }

        assertThat(logger.query(limit = 2).map { it.id }).containsExactly("e5", "e4").inOrder()
    }

    @Test
    fun `a limit larger than the log returns everything`() {
        val logger = InMemoryAuditLogger()
        logger.log(entry("a"))
        logger.log(entry("b"))

        assertThat(logger.query(limit = 100)).hasSize(2)
    }

    @Test
    fun `a zero limit returns nothing`() {
        val logger = InMemoryAuditLogger()
        logger.log(entry("a"))

        assertThat(logger.query(limit = 0)).isEmpty()
        assertThat(logger.count()).isEqualTo(1)
    }

    @Test
    fun `preserves entry detail`() {
        val logger = InMemoryAuditLogger()
        logger.log(
            AuditLogEntry(
                id = "e1",
                actorId = "admin-7",
                action = AuditAction.ROLLBACK_TRIGGERED,
                targetType = "release",
                targetId = "20260404-3",
                detail = mapOf("reason" to "hash mismatch", "from" to "7"),
                occurredAtEpochMs = 1_777_000_000_000L,
            ),
        )

        val stored = logger.query().single()
        assertThat(stored.actorId).isEqualTo("admin-7")
        assertThat(stored.detail).containsExactly("reason", "hash mismatch", "from", "7")
        assertThat(stored.occurredAtEpochMs).isEqualTo(1_777_000_000_000L)
    }

    @Test
    fun `uses the injected clock only for caller supplied timestamps`() {
        var now = 1_000L
        val logger = InMemoryAuditLogger(clock = { now })

        now = 2_000L
        logger.log(entry("a"))

        assertThat(logger.query().single().occurredAtEpochMs).isEqualTo(0L)
    }

    @Test
    fun `handles a large volume without loss`() {
        val logger = InMemoryAuditLogger()
        repeat(1_000) { logger.log(entry("e$it", at = it.toLong())) }

        assertThat(logger.count()).isEqualTo(1_000)
        assertThat(logger.query(limit = 1).single().id).isEqualTo("e999")
    }

    @Test
    fun `keeps duplicates rather than deduplicating`() {
        val logger = InMemoryAuditLogger()
        logger.log(entry("same"))
        logger.log(entry("same"))

        assertThat(logger.count()).isEqualTo(2)
    }
}

class AuditLogFiltersTest {

    private fun entry(id: String, action: AuditAction) =
        AuditLogEntry(
            id = id,
            actorId = "admin-1",
            action = action,
            occurredAtEpochMs = 0L,
        )

    @Test
    fun `filters by action`() {
        val entries = listOf(
            entry("a", AuditAction.DOMAIN_APPROVED),
            entry("b", AuditAction.DOMAIN_REJECTED),
            entry("c", AuditAction.DOMAIN_APPROVED),
        )

        val approved = AuditLogFilters.byAction(entries, AuditAction.DOMAIN_APPROVED)

        assertThat(approved.map { it.id }).containsExactly("a", "c").inOrder()
    }

    @Test
    fun `returns an empty list when nothing matches`() {
        val entries = listOf(entry("a", AuditAction.DOMAIN_APPROVED))

        assertThat(AuditLogFilters.byAction(entries, AuditAction.RELEASE_PUBLISHED)).isEmpty()
    }

    @Test
    fun `handles an empty input`() {
        assertThat(AuditLogFilters.byAction(emptyList(), AuditAction.DOMAIN_APPROVED)).isEmpty()
    }

    @Test
    fun `preserves input order`() {
        val entries = listOf(
            entry("a", AuditAction.RELEASE_BUILT),
            entry("b", AuditAction.RELEASE_BUILT),
            entry("c", AuditAction.RELEASE_BUILT),
        )

        assertThat(AuditLogFilters.byAction(entries, AuditAction.RELEASE_BUILT).map { it.id })
            .containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `matches every security relevant action`() {
        val actions = listOf(
            AuditAction.ROLLBACK_TRIGGERED,
            AuditAction.ADMIN_ROLE_CHANGED,
            AuditAction.ALLOWLIST_CHANGE,
            AuditAction.FALSE_POSITIVE_ALLOWLISTED,
        )
        val entries = actions.mapIndexed { i, a -> entry("e$i", a) }

        actions.forEach { action ->
            assertThat(AuditLogFilters.byAction(entries, action)).hasSize(1)
        }
    }
}