package dev.gamblock.core.integrity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IntegrityClassifierTest {

    private val classifier = IntegrityClassifier(maxAgeMs = 1_000L)

    private fun attestation(
        nonce: String = "n1",
        token: String = "eyJhbGciOiJFUzI1NiJ9.payload.sig",
        obtainedAt: Long = 500L,
        certDigest: String = "abc",
    ) = IntegrityAttestation(
        provider = "test",
        nonce = nonce,
        packageName = "dev.gamblock.shield",
        appCertificateDigestSha256 = certDigest,
        token = token,
        obtainedAtEpochMs = obtainedAt,
    )

    @Test
    fun `fresh well bound evidence is valid`() {
        val result = classifier.review(attestation(), expectedNonce = "n1", nowEpochMs = 1_000L)
        assertThat(result.status).isEqualTo(IntegrityEvidenceStatus.VALID)
        assertThat(result.reason).contains("nonce")
    }

    @Test
    fun `empty token is invalid`() {
        val result = classifier.review(attestation(token = ""), expectedNonce = "n1", nowEpochMs = 1_000L)
        assertThat(result.status).isEqualTo(IntegrityEvidenceStatus.INVALID)
        assertThat(result.reason).contains("empty token")
    }

    @Test
    fun `nonce mismatch is invalid`() {
        val result = classifier.review(attestation(nonce = "stolen"), expectedNonce = "fresh", nowEpochMs = 1_000L)
        assertThat(result.status).isEqualTo(IntegrityEvidenceStatus.INVALID)
        assertThat(result.reason).contains("nonce mismatch")
    }

    @Test
    fun `evidence older than the window is stale`() {
        val result = classifier.review(attestation(obtainedAt = 500L), expectedNonce = "n1", nowEpochMs = 5_000L)
        assertThat(result.status).isEqualTo(IntegrityEvidenceStatus.STALE)
        assertThat(result.reason).contains("older")
    }

    @Test
    fun `evidence from the future is stale`() {
        val result = classifier.review(attestation(obtainedAt = 5_000L), expectedNonce = "n1", nowEpochMs = 1_000L)
        assertThat(result.status).isEqualTo(IntegrityEvidenceStatus.STALE)
        assertThat(result.reason).contains("future")
    }

    @Test
    fun `certificate digest matches only when equal`() {
        val with = attestation(certDigest = "abc")
        assertThat(classifier.certificateMatches(with, "abc")).isTrue()
        assertThat(classifier.certificateMatches(with, "abd")).isFalse()
        assertThat(classifier.certificateMatches(with, "")).isFalse()
    }

    @Test
    fun `blank observed digest never matches`() {
        val blank = attestation(certDigest = "")
        assertThat(classifier.certificateMatches(blank, "abc")).isFalse()
    }
}