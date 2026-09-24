package dev.gamblock.core.accountability

import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.model.InviteCapabilitySelection
import dev.gamblock.core.model.PairingKind
import dev.gamblock.core.model.PartnerCapability
import org.junit.Test

class PairingTokenServiceTest {

    private val service = PairingTokenService(tokenTtlMillis = 600_000L)

    private val partnerSelection = InviteCapabilitySelection(
        kind = PairingKind.ACCOUNTABILITY_PARTNER,
        capabilities = setOf(PartnerCapability.RECEIVE_GAMBLING_ATTEMPTS),
    )

    @Test
    fun `create produces a high-entropy token with id and secret separated`() {
        val result = service.create(
            createdByDeviceId = "aa".repeat(16),
            kind = PairingKind.ACCOUNTABILITY_PARTNER,
            selection = partnerSelection,
            nowEpochMs = 1_700_000_000_000L,
        )
        assertThat(result).isInstanceOf(PairingTokenService.CreateResult.Created::class.java)
        val token = (result as PairingTokenService.CreateResult.Created).token
        assertThat(token.id).isNotEmpty()
        assertThat(token.secret).isNotEmpty()
        assertThat(token.fullToken).isEqualTo("${token.id}.${token.secret}")
        // id and secret each 16 random bytes -> 22 base64url chars.
        assertThat(token.id.length).isEqualTo(22)
        assertThat(token.secret.length).isEqualTo(22)
    }

    @Test
    fun `create expires the token within ttl`() {
        val now = 1_700_000_000_000L
        val result = service.create("aa".repeat(16), PairingKind.ACCOUNTABILITY_PARTNER, partnerSelection, now)
        val token = (result as PairingTokenService.CreateResult.Created).token
        assertThat(token.expiresAtEpochMs).isEqualTo(now + 600_000L)
    }

    @Test
    fun `create rejects blank device id`() {
        val result = service.create("", PairingKind.ACCOUNTABILITY_PARTNER, partnerSelection, 1_700_000_000_000L)
        assertThat(result).isInstanceOf(PairingTokenService.CreateResult.Rejected::class.java)
    }

    @Test
    fun `parent invite without capabilities is rejected`() {
        val result = service.create(
            "aa".repeat(16),
            PairingKind.PARENT_SUPERVISED_CHILD,
            InviteCapabilitySelection(kind = PairingKind.PARENT_SUPERVISED_CHILD, capabilities = emptySet()),
            1_700_000_000_000L,
        )
        assertThat((result as PairingTokenService.CreateResult.Rejected).reason).contains("at least one capability")
    }

    @Test
    fun `third party can verify a token by its full form`() {
        val now = 1_700_000_000_000L
        val created = (service.create("aa".repeat(16), PairingKind.ACCOUNTABILITY_PARTNER, partnerSelection, now)
            as PairingTokenService.CreateResult.Created)
        val hash = PairingTokenService.hash(created.token.fullToken)

        val verify = service.validateToken(created.token.fullToken, hash, now)
        assertThat(verify).isInstanceOf(PairingTokenService.ValidateResult.Valid::class.java)
    }

    @Test
    fun `validation rejects wrong token value`() {
        val now = 1_700_000_000_000L
        val created = (service.create("aa".repeat(16), PairingKind.ACCOUNTABILITY_PARTNER, partnerSelection, now)
            as PairingTokenService.CreateResult.Created)
        val hash = PairingTokenService.hash(created.token.fullToken)

        val verify = service.validateToken("wrong.id.and.secret", hash, now)
        assertThat(verify).isInstanceOf(PairingTokenService.ValidateResult.Invalid::class.java)
    }

    @Test
    fun `forged token with correct id but false secret fails`() {
        val created = (service.create("aa".repeat(16), PairingKind.ACCOUNTABILITY_PARTNER, partnerSelection, 1_700_000_000_000L)
            as PairingTokenService.CreateResult.Created)
        val hash = PairingTokenService.hash(created.token.fullToken)

        val forged = "${created.token.id}.not-the-secret"
        val verify = service.validateToken(forged, hash, 1_700_000_000_000L)
        assertThat(verify).isInstanceOf(PairingTokenService.ValidateResult.Invalid::class.java)
    }

    @Test
    fun `malformed token without separator fails`() {
        val verify = service.validateToken("no-separator-here", "whatever", 1_700_000_000_000L)
        assertThat(verify).isInstanceOf(PairingTokenService.ValidateResult.Invalid::class.java)
    }

    @Test
    fun `hash is deterministic sha256`() {
        val a = PairingTokenService.hash("tok.sec")
        val b = PairingTokenService.hash("tok.sec")
        assertThat(a).isEqualTo(b)
        assertThat(a).hasLength(64)
        assertThat(a).isNotEqualTo(PairingTokenService.hash("tok.secret"))
    }
}

class SensitiveChangePolicyTest {

    @Test
    fun `partners cannot approve without capability`() {
        assertThat(
            SensitiveChangePolicy.partnerMayApprove(
                dev.gamblock.core.model.SensitiveChange.REPLACE_PROTECTED_DEVICE,
                emptySet(),
            ),
        ).isFalse()
    }

    @Test
    fun `partners with capability can approve`() {
        assertThat(
            SensitiveChangePolicy.partnerMayApprove(
                dev.gamblock.core.model.SensitiveChange.REPLACE_PROTECTED_DEVICE,
                setOf(PartnerCapability.APPROVE_DEVICE_REPLACEMENT),
            ),
        ).isTrue()
    }

    @Test
    fun `config change requires parent approval when locked`() {
        assertThat(
            SensitiveChangePolicy.partnerMayApprove(
                dev.gamblock.core.model.SensitiveChange.CHANGE_ACCOUNTABILITY_PARTNER,
                setOf(PartnerCapability.APPROVE_PARTNER_CHANGES),
            ),
        ).isTrue()
    }

    @Test
    fun `every approval-required sensitive change requires exactly the capability mapped to it`() {
        val approvalMapped = mapOf(
            dev.gamblock.core.model.SensitiveChange.REPLACE_PROTECTED_DEVICE to PartnerCapability.APPROVE_DEVICE_REPLACEMENT,
            dev.gamblock.core.model.SensitiveChange.CHANGE_ACCOUNTABILITY_PARTNER to PartnerCapability.APPROVE_PARTNER_CHANGES,
            dev.gamblock.core.model.SensitiveChange.DISABLE_ACCOUNTABILITY_ALERTS to PartnerCapability.APPROVE_ALERT_DISABLE,
            dev.gamblock.core.model.SensitiveChange.EXTEND_RELATIONSHIP to PartnerCapability.EXTEND_PROTECTION,
        )
        for ((change, required) in approvalMapped) {
            assertThat(SensitiveChangePolicy.capabilityRequired(change)).isEqualTo(required)
            assertThat(SensitiveChangePolicy.partnerMayApprove(change, setOf(required))).isTrue()
            // A different capability must not authorize this change.
            val others = PartnerCapability.entries.minus(required)
            for (other in others) {
                assertThat(SensitiveChangePolicy.partnerMayApprove(change, setOf(other))).isFalse()
            }
            assertThat(SensitiveChangePolicy.partnerMayApprove(change, emptySet())).isFalse()
        }
    }

    @Test
    fun `sensitive change without approval mapping is deniable by default`() {
        // DISABLE_PROTECTION is deliberately NOT approvable by any partner capability:
        // self-protection can never be switched off through an accountability approval.
        assertThat(SensitiveChangePolicy.capabilityRequired(dev.gamblock.core.model.SensitiveChange.DISABLE_PROTECTION))
            .isNull()
        assertThat(SensitiveChangePolicy.partnerMayApprove(dev.gamblock.core.model.SensitiveChange.DISABLE_PROTECTION, PartnerCapability.entries.toSet()))
            .isFalse()
    }
}