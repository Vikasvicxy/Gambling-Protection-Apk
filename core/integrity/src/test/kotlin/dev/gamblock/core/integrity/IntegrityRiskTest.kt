package dev.gamblock.core.integrity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IntegrityRiskTest {

    private val attestation = IntegrityAttestation(
        provider = "play_integrity_standard",
        nonce = "n",
        packageName = "dev.gamblock.shield",
        appCertificateDigestSha256 = "d",
        token = "t",
        obtainedAtEpochMs = 1L,
    )

    @Test
    fun `success maps to pending server verification`() {
        val risk = IntegrityResult.Success(attestation).asRisk()
        assertThat(risk).isEqualTo(IntegrityRisk.PENDING)
    }

    @Test
    fun `unavailable maps to unverified`() {
        val risk = IntegrityResult.Unavailable("no provider").asRisk()
        assertThat(risk).isEqualTo(IntegrityRisk.UNVERIFIED)
    }

    @Test
    fun `failure maps to error`() {
        val risk = IntegrityResult.Failure("boom", retriable = true).asRisk()
        assertThat(risk).isEqualTo(IntegrityRisk.ERROR)
    }
}