package dev.gamblock.core.integrity

/**
 * Locally reviews attestation evidence against what a device can check alone.
 *
 * Play Integrity tokens can only be *verified* server-side; on-device we still
 * enforce the envelope: the token must be bound to the request nonce, must be
 * non-empty, and must be fresh within a validity window. This prevents a stale
 * or mismatched token from ever being treated as current evidence.
 */
class IntegrityClassifier(
    private val maxAgeMs: Long = 24L * 60 * 60 * 1000,
) {

    fun review(
        attestation: IntegrityAttestation,
        expectedNonce: String,
        nowEpochMs: Long,
    ): IntegrityEvidenceReview {
        if (attestation.token.isBlank()) {
            return IntegrityEvidenceReview(
                status = IntegrityEvidenceStatus.INVALID,
                reason = "empty token",
                attestation = attestation,
            )
        }
        if (attestation.nonce != expectedNonce) {
            return IntegrityEvidenceReview(
                status = IntegrityEvidenceStatus.INVALID,
                reason = "nonce mismatch",
                attestation = attestation,
            )
        }
        val age = nowEpochMs - attestation.obtainedAtEpochMs
        if (age > maxAgeMs || age < 0) {
            return IntegrityEvidenceReview(
                status = IntegrityEvidenceStatus.STALE,
                reason = if (age < 0) "attestation timestamp in future" else "attestation older than $maxAgeMs ms",
                attestation = attestation,
            )
        }
        return IntegrityEvidenceReview(
            status = IntegrityEvidenceStatus.VALID,
            reason = "evidence bound to nonce, fresh, pending server verification",
            attestation = attestation,
        )
    }

    /**
     * Pins the app certificate digest observed by a provider against the known
     * embedded expectation (base64 of SHA-256 of the signing cert). A mismatch
     * means a modified APK is presenting itself - flag as invalid. Blank
     * digests never match (we would rather fail closed than compare nothing).
     */
    fun certificateMatches(attestation: IntegrityAttestation, expectedDigestBase64: String): Boolean {
        val observed = attestation.appCertificateDigestSha256.trim()
        val expected = expectedDigestBase64.trim()
        if (observed.isEmpty() || expected.isEmpty()) return false
        return observed == expected
    }
}