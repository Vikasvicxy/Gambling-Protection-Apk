package dev.gamblock.core.integrity

import kotlinx.serialization.Serializable

/**
 * Attestation evidence produced by an integrity provider.
 *
 * The [token] is the raw Play Integrity token (JWS). It must be verified
 * server-side before any verdict key is trusted; on-device we only check the
 * envelope (nonce binding, format, freshness). See [IntegrityClassifier].
 */
@Serializable
data class IntegrityAttestation(
    /** Provider instance that produced this evidence. */
    val provider: String,
    /** Nonce the attestation is bound to; must equal the request nonce. */
    val nonce: String,
    /** Package this attestation applies to. */
    val packageName: String,
    /** Install-time embedded certificate digest (base64, SHA-256). */
    val appCertificateDigestSha256: String,
    /** Raw signed token from the provider (JWS for Play Integrity). */
    val token: String,
    /** When the token was obtained, epoch ms. */
    val obtainedAtEpochMs: Long,
    /** Locally-known verdict (UNKNOWN until server verification). */
    val verdict: IntegrityVerdict = IntegrityVerdict.UNKNOWN,
)

/** Status of a local evidence check (not the server-side verdict). */
enum class IntegrityEvidenceStatus {
    /** Evidence is fresh, correct, and pending server verification. */
    VALID,
    /** Evidence is well-formed but outside the freshness window. */
    STALE,
    /** Evidence failed a local check (nonce/format). */
    INVALID,
}

/** Result of locally reviewing an attached piece of integrity evidence. */
data class IntegrityEvidenceReview(
    val status: IntegrityEvidenceStatus,
    val reason: String,
    val attestation: IntegrityAttestation?,
)