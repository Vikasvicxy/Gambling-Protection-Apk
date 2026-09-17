package dev.gamblock.core.release

import java.security.KeyPair
import java.security.PublicKey

/** A fully built, signed release ready for publication. */
data class BuiltRelease(
    val envelope: SignedReleaseEnvelope,
    val fullPayload: ByteArray,
    val deltaPayload: ByteArray?, // null when there is no previous version
    val domainCount: Int,
    /** Public key that signed this release (for tests/tooling; never shipped). */
    val signingPublicKey: PublicKey,
)

/**
 * Immutable set of trusted current + next (rotating) signing keys.
 *
 * Rotation design (see docs/phase2/06-key-management.md):
 *  - [current] is used to verify the daily channel.
 *  - [next] may be used to verify a successor key's manifests BEFORE [current]
 *    is retired, so clients can migrate without an app update.
 *  - Emergency revocation = ship an app update that removes the compromised key (a key
 *    that can be revoked with app updates is a feature, not a weakness: every client
 *    additionally requires a minimum app version).
 */
class TrustedKeyRing(keys: List<PublicKey>) {
    private val keys: List<PublicKey> = keys.distinctBy { ReleaseCrypto.fingerprint(it) }
    val keyIds: List<String> get() = keys.map { ReleaseCrypto.fingerprint(it) }

    fun containsKeyId(keyId: String): Boolean = keys.any { ReleaseCrypto.fingerprint(it) == keyId }

    fun verify(envelope: SignedReleaseEnvelope): VerificationResult {
        for (key in keys) {
            val result = ReleaseCrypto.verify(envelope, key)
            if (result.ok) return result
        }
        return VerificationResult.fail("no trusted key verified the manifest signature")
    }

    fun verifyWithKeyId(envelope: SignedReleaseEnvelope, keyId: String): VerificationResult {
        val key = keys.firstOrNull { ReleaseCrypto.fingerprint(it) == keyId }
            ?: return VerificationResult.fail("keyId $keyId not in trusted key ring")
        return ReleaseCrypto.verify(envelope, key)
    }
}

/** Builds signed release artifacts deterministically. Used by the CI release job. */
object ReleaseBuilder {

    fun build(
        previousRecords: List<ReleaseDomainRecord>?,
        nextRecords: List<ReleaseDomainRecord>,
        releaseId: String,
        version: Int,
        channel: UpdateChannel,
        generatedAtEpochMs: Long,
        minimumAppVersion: Int,
        signingKey: KeyPair,
        changelog: String = "",
    ): BuiltRelease {
        ReleaseValidator.validateRecords(nextRecords)

        val previousByDomain = previousRecords
            ?.let { ReleaseValidator.validateRecords(it).zip(it).associate { (domain, rec) -> domain to rec } }
            ?: emptyMap()
        val nextByDomain = nextRecords.associate { it.normalizedDomain to it }
        val previousVersion = if (previousRecords == null) null else version - 1

        val fullFileName = "blocklist-$version.json.gz"
        val fullPayload = PayloadCodec.encodeRecords(nextRecords)
        val deltaPayload: ByteArray?
        val deltaArtifact: DeltaArtifact?

        if (previousRecords != null) {
            val plan = DeltaEngine.plan(previousByDomain, nextByDomain, version - 1, version)
            val ops = DeltaEngine.ops(plan, version - 1, version)
            deltaPayload = DeltaCodec.encode(ops)
            deltaArtifact = DeltaArtifact(
                baseVersion = version - 1,
                fileName = "delta-${version - 1}-$version.json.gz",
                sha256 = ReleaseValidator.sha256Hex(deltaPayload),
                sizeBytes = deltaPayload.size.toLong(),
                addedCount = plan.addedCount,
                removedCount = plan.removedCount,
                modifiedCount = plan.modifiedCount,
            )
        } else {
            deltaPayload = null
            deltaArtifact = null
        }

        val manifest = SignedReleaseManifest(
            releaseId = releaseId,
            version = version,
            previousVersion = previousVersion,
            schemaVersion = 1,
            channel = channel,
            generatedAtEpochMs = generatedAtEpochMs,
            minimumAppVersion = minimumAppVersion,
            rollback = false,
            full = FullArtifact(
                fileName = fullFileName,
                sha256 = ReleaseValidator.sha256Hex(fullPayload),
                sizeBytes = fullPayload.size.toLong(),
            ),
            delta = deltaArtifact,
            changelog = changelog,
        )

        val envelope = ReleaseCrypto.envelope(manifest, signingKey)
        return BuiltRelease(
            envelope = envelope,
            fullPayload = fullPayload,
            deltaPayload = deltaPayload,
            domainCount = nextRecords.size,
            signingPublicKey = signingKey.public,
        )
    }
}