package dev.gamblock.core.accountability

import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.model.AccountabilityEvent
import dev.gamblock.core.model.AccountabilityEventType
import dev.gamblock.core.model.Category
import dev.gamblock.core.model.PartnerCapability
import dev.gamblock.core.model.PartnerRelationship
import dev.gamblock.core.model.PartnerRole
import dev.gamblock.core.model.RelationshipStatus
import dev.gamblock.core.model.ShieldRole
import org.junit.Test

class PartnerRelationshipManagerTest {

    private val manager = PartnerRelationshipManager()
    private val protectedId = "aa".repeat(16)
    private val partnerId = "bb".repeat(16)
    private val unrelatedId = "cc".repeat(16)

    private val pending = PartnerRelationship(
        id = "rel-1",
        protectedDeviceId = protectedId,
        partnerDeviceId = partnerId,
        role = PartnerRole.TRUSTED_PARTNER,
        status = RelationshipStatus.PENDING,
        capabilities = setOf(PartnerCapability.RECEIVE_GAMBLING_ATTEMPTS),
        createdAtEpochMs = 1_700_000_000_000L,
    )

    @Test
    fun `create rejects device pairing with itself`() {
        val result = manager.create(protectedId, protectedId, PartnerRole.TRUSTED_PARTNER, emptySet(), 1L)
        assertThat(result).isInstanceOf(PartnerRelationshipManager.CreateResult.Rejected::class.java)
    }

    @Test
    fun `create requires capabilities for accountability partner`() {
        val result = manager.create(protectedId, partnerId, PartnerRole.TRUSTED_PARTNER, emptySet(), 1L)
        assertThat(result).isInstanceOf(PartnerRelationshipManager.CreateResult.Rejected::class.java)
    }

    @Test
    fun `create succeeds with capabilities`() {
        val result = manager.create(
            protectedId,
            partnerId,
            PartnerRole.TRUSTED_PARTNER,
            setOf(PartnerCapability.RECEIVE_GAMBLING_ATTEMPTS),
            1_700_000_000_000L,
        )
        assertThat(result).isInstanceOf(PartnerRelationshipManager.CreateResult.Success::class.java)
        val rel = (result as PartnerRelationshipManager.CreateResult.Success).relationship
        assertThat(rel.status).isEqualTo(RelationshipStatus.PENDING)
        assertThat(rel.acceptedAtEpochMs).isNull()
    }

    @Test
    fun `protected user cannot accept their own invite`() {
        val result = manager.acceptPending(pending, protectedId, 1_700_000_100_000L)
        assertThat((result as PartnerRelationshipManager.AcceptResult.Rejected).reason)
            .contains("cannot accept their own invite")
    }

    @Test
    fun `unrelated device cannot accept`() {
        val result = manager.acceptPending(pending, unrelatedId, 1_700_000_100_000L)
        assertThat((result as PartnerRelationshipManager.AcceptResult.Rejected).reason)
            .contains("unrelated device")
    }

    @Test
    fun `partner actively accepting links the relationship`() {
        val result = manager.acceptPending(pending, partnerId, 1_700_000_100_000L)
        val rel = (result as PartnerRelationshipManager.AcceptResult.Success).relationship
        assertThat(rel.status).isEqualTo(RelationshipStatus.ACTIVE)
        assertThat(rel.acceptedAtEpochMs).isEqualTo(1_700_000_100_000L)
    }

    @Test
    fun `accepting a relationship already active is rejected`() {
        val active = pending.copy(status = RelationshipStatus.ACTIVE)
        val result = manager.acceptPending(active, partnerId, 1L)
        assertThat((result as PartnerRelationshipManager.AcceptResult.Rejected).reason)
            .contains("not pending")
    }

    @Test
    fun `authorized devices in an active relationship are granted`() {
        val active = pending.copy(status = RelationshipStatus.ACTIVE)
        assertThat(manager.authorize(active, protectedId))
            .isInstanceOf(PartnerRelationshipManager.AuthorizationResult.Granted::class.java)
        assertThat(manager.authorize(active, partnerId))
            .isInstanceOf(PartnerRelationshipManager.AuthorizationResult.Granted::class.java)
    }

    @Test
    fun `partner accessing another user relationship is denied`() {
        val active = pending.copy(status = RelationshipStatus.ACTIVE)
        assertThat(manager.authorize(active, unrelatedId))
            .isInstanceOf(PartnerRelationshipManager.AuthorizationResult.Denied::class.java)
    }

    @Test
    fun `paused relationship denies access`() {
        val paused = pending.copy(status = RelationshipStatus.ACTIVE, pausedByProtectedUser = true)
        assertThat(manager.authorize(paused, partnerId))
            .isInstanceOf(PartnerRelationshipManager.AuthorizationResult.Denied::class.java)
    }

    @Test
    fun `inactive relationship denies access`() {
        assertThat(manager.authorize(pending, protectedId))
            .isInstanceOf(PartnerRelationshipManager.AuthorizationResult.Denied::class.java)
    }

    @Test
    fun `mayReceiveEvent respects capability gating`() {
        val event = AccountabilityEvent(
            id = "e1",
            type = AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED,
            severity = dev.gamblock.core.model.AccountabilitySeverity.LOW,
            occurredAtEpochMs = 1L,
            deviceId = protectedId,
            category = Category.GAMBLING,
        )
        assertThat(manager.mayReceiveEvent(event, setOf(PartnerCapability.RECEIVE_GAMBLING_ATTEMPTS))).isTrue()
        assertThat(manager.mayReceiveEvent(event, setOf(PartnerCapability.RECEIVE_TAMPER_ALERTS))).isFalse()
        assertThat(manager.mayReceiveEvent(event, emptySet())).isFalse()
    }

    @Test
    fun `heartbeat-lost event is gated by heartbeat capability`() {
        val event = AccountabilityEvent(
            id = "e2",
            type = AccountabilityEventType.HEARTBEAT_LOST,
            severity = dev.gamblock.core.model.AccountabilitySeverity.CRITICAL,
            occurredAtEpochMs = 1L,
            deviceId = protectedId,
        )
        assertThat(manager.mayReceiveEvent(event, setOf(PartnerCapability.RECEIVE_HEARTBEAT_ALERTS))).isTrue()
        assertThat(manager.mayReceiveEvent(event, setOf(PartnerCapability.RECEIVE_GAMBLING_ATTEMPTS))).isFalse()
    }

    @Test
    fun `protected user pretending to be admin is always rejected`() {
        val result = manager.authorizeRole(ShieldRole.ADMIN, protectedId, protectedId)
        assertThat(result).isInstanceOf(PartnerRelationshipManager.AuthorizationResult.Denied::class.java)
    }

    @Test
    fun `protected user scope is enforced`() {
        val ok = manager.authorizeRole(ShieldRole.PROTECTED_USER, protectedId, protectedId)
        assertThat(ok).isInstanceOf(PartnerRelationshipManager.AuthorizationResult.Granted::class.java)

        val wrong = manager.authorizeRole(ShieldRole.PROTECTED_USER, protectedId, unrelatedId)
        assertThat(wrong).isInstanceOf(PartnerRelationshipManager.AuthorizationResult.Denied::class.java)
    }

    @Test
    fun `client role claim is never accepted for admin`() {
        assertThat(manager.assertNotAdmin(ShieldRole.ADMIN)).isFalse()
        assertThat(manager.assertNotAdmin(ShieldRole.TRUSTED_PARTNER)).isTrue()
    }
}