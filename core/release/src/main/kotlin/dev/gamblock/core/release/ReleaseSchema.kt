package dev.gamblock.core.release

import kotlinx.serialization.Serializable

/**
 * Production signed-release schema (Phase 2).
 *
 * Everything in this module is deliberately a pure-JVM / Android-free layer so the
 * exact same code drives the release-pipeline tool (CI), the on-device verifier and
 * the unit tests. Wire round-trips are byte-identical on both sides.
 *
 * Signature coverage model
 * --------------------------
 * A signed release is an envelope:
 *
 *   {
 *     "manifest": { ...SignedReleaseManifest (no signature fields)... },
 *     "signature": { "algorithm": "ECDSA-P256-SHA256", "keyId": ..., "signatureBase64": ... }
 *   }
 *
 * The signature is produced over the canonical JSON bytes of [SignedReleaseManifest]
 * only (see [CanonicalCodec]). The manifest itself pins the SHA-256 and size of every
 * artifact it references, so verifying one signature authenticates the manifest AND
 * (through the hashes) every payload artifact.
 */

/** Deployment channel. Each channel has its own signed manifest. */
@Serializable
enum class UpdateChannel {
    INTERNAL,
    CANARY,
    STABLE,
}

/** Full-database artifact reference. */
@Serializable
data class FullArtifact(
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long,
)

/** Optional delta artifact reference (forward-only, single hop). */
@Serializable
data class DeltaArtifact(
    val baseVersion: Int,
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long,
    /** Counts embedded in the delta for early structural validation. */
    val addedCount: Int,
    val removedCount: Int,
    val modifiedCount: Int,
) {
    val targetVersion: Int get() = baseVersion + 1
}

/**
 * Canonical signed-release manifest. The `signature` lives in the envelope, NOT here,
 * so [CanonicalCodec] can re-encode this object deterministically for verification.
 */
@Serializable
data class SignedReleaseManifest(
    val releaseId: String,
    /** Monotonic release number. Newer full/delta artifacts MUST be > installed. */
    val version: Int,
    /** The version this release advances from; null for the first release. */
    val previousVersion: Int? = null,
    /** Release-payload schema version (independent of versions of the app). */
    val schemaVersion: Int = 1,
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val generatedAtEpochMs: Long,
    /** Minimum app `versionCode` allowed to consume this release. */
    val minimumAppVersion: Int = 1,
    /**
     * True for signed emergency rollbacks. A rollback release intentionally targets a
     * LOWER [version] than what the client currently has. It is ONLY honored when this
     * flag is signed (i.e. produced by a trusted signing key) and is never applied as a
     * regular downgrade.
     */
    val rollback: Boolean = false,
    val full: FullArtifact,
    val delta: DeltaArtifact? = null,
    /** Human changelog markdown; informational only. */
    val changelog: String = "",
)

@Serializable
data class SignatureMetadata(
    val algorithm: String,
    /** Fingerprint of the verifying public key (see [ReleaseCrypto.fingerprint]). */
    val keyId: String,
    val signatureBase64: String,
)

@Serializable
data class SignedReleaseEnvelope(
    val manifest: SignedReleaseManifest,
    val signature: SignatureMetadata,
)

/**
 * Per-domain record as stored in a release payload (NDJSON, gzip-compressed).
 * Carries full provenance so Phase-2 false-positive triage can attribute rules.
 */
@Serializable
data class ReleaseDomainRecord(
    val domain: String,
    val normalizedDomain: String,
    val category: String,
    val confidence: String,
    val status: String,
    val riskLevel: String,
    val sourceIds: List<String> = emptyList(),
    val operatorId: String? = null,
    val brandId: String? = null,
    val mirrorOf: String? = null,
    val country: String? = null,
    val firstSeenEpochMs: Long = 0L,
    val lastVerifiedEpochMs: Long = 0L,
    val databaseVersion: Int = 0,
    val appliesToSubdomains: Boolean = true,
)

/**
 * Operator/brand/domain intelligence annex shipped alongside the payload. This is the
 * seed of mirror "family" detection (future pipeline expands it with affiliates and
 * application packages).
 */
@Serializable
data class OperatorAnnex(
    val operators: List<OperatorEntry> = emptyList(),
)

@Serializable
data class OperatorEntry(
    val operatorId: String,
    val name: String,
    val country: String? = null,
    val brands: List<BrandEntry> = emptyList(),
    val knownMirrorDomains: List<String> = emptyList(),
)

@Serializable
data class BrandEntry(
    val brandId: String,
    val name: String,
    /** High-priority domains the brand controls directly. */
    val primaryDomains: List<String> = emptyList(),
)

/** Outcome of every verification step; machine-readable for tests and diagnostics. */
data class VerificationResult(
    val ok: Boolean,
    val reason: String,
) {
    companion object {
        fun ok(reason: String = "ok") = VerificationResult(true, reason)
        fun fail(reason: String) = VerificationResult(false, reason)
    }
}