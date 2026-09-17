package dev.gamblock.core.release

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReleaseCryptoTest {

    private fun signed(): BuiltRelease {
        val records = listOf(
            record("casino-bet.test", "CASINO"),
            record("poker.test", "POKER"),
        )
        return ReleaseBuilder.build(
            previousRecords = null,
            nextRecords = records,
            releaseId = "2026.09.16.1",
            version = 1051,
            channel = UpdateChannel.STABLE,
            generatedAtEpochMs = 1_700_000_000_000L,
            minimumAppVersion = 1,
            signingKey = ReleaseCrypto.generateKeyPair(),
        )
    }

    @Test
    fun `generateKeyPair produces a verifiable self-signed release`() {
        // Pact-generator style: keyPair => envelope with matching keyId.
        val keyPair = ReleaseCrypto.generateKeyPair()
        val manifest = ReleaseCryptoTestData.manifest()
        val envelope = ReleaseCrypto.envelope(manifest, keyPair)

        assertThat(ReleaseCrypto.verify(envelope, keyPair.public).ok).isTrue()
        assertThat(envelope.signature.keyId).hasLength(16)
        assertThat(envelope.signature.keyId).isEqualTo(ReleaseCrypto.fingerprint(keyPair.public))
    }

    @Test
    fun `tampered manifest field fails verification`() {
        val keyPair = ReleaseCrypto.generateKeyPair()
        val envelope = ReleaseCrypto.envelope(ReleaseCryptoTestData.manifest(), keyPair)
        val tampered = envelope.manifest.copy(version = envelope.manifest.version + 1000)

        val result = ReleaseCrypto.verify(envelope.copy(manifest = tampered), keyPair.public)
        assertThat(result.ok).isFalse()
        assertThat(result.reason).contains("verification")
    }

    @Test
    fun `modified database payload fails artifact hash check`() {
        val built = signed()
        val keyRing = TrustedKeyRing(listOf(built.signingPublicKey))
        val intact = ReleaseVerifier.verifyFullEnvelope(
            built.envelope,
            built.fullPayload,
            keyRing,
            installedVersion = 1050,
            currentAppVersionCode = 99,
            maxObservedVersion = 1050,
        )
        assertThat(intact.ok).isTrue()

        val corrupted = built.fullPayload.copyOf()
        corrupted[corrupted.size / 2] = (corrupted[corrupted.size / 2] + 1).toByte()

        val broken = ReleaseVerifier.verifyFullEnvelope(
            built.envelope,
            corrupted,
            keyRing,
            installedVersion = 1050,
            currentAppVersionCode = 99,
            maxObservedVersion = 1050,
        )
        assertThat(broken.ok).isFalse()
        assertThat(broken.reason).contains("sha256 mismatch")
    }

    @Test
    fun `wrong key fails verification with keyId mismatch`() {
        val signer = ReleaseCrypto.generateKeyPair()
        val other = ReleaseCrypto.generateKeyPair()
        val envelope = ReleaseCrypto.envelope(ReleaseCryptoTestData.manifest(), signer)

        val result = ReleaseCrypto.verify(envelope, other.public)
        assertThat(result.ok).isFalse()
        assertThat(result.reason).contains("keyId mismatch")
    }

    @Test
    fun `unsupported algorithm is rejected`() {
        val keyPair = ReleaseCrypto.generateKeyPair()
        val envelope = ReleaseCrypto.envelope(ReleaseCryptoTestData.manifest(), keyPair)
        val got = ReleaseCrypto.verify(
            envelope.copy(signature = envelope.signature.copy(algorithm = "Ed25519-NOOPS")),
            keyPair.public,
        )
        assertThat(got.ok).isFalse()
        assertThat(got.reason).contains("unsupported signature algorithm")
    }

    @Test
    fun `non base64 signature is rejected`() {
        val keyPair = ReleaseCrypto.generateKeyPair()
        val envelope = ReleaseCrypto.envelope(ReleaseCryptoTestData.manifest(), keyPair)
        val got = ReleaseCrypto.verify(
            envelope.copy(signature = envelope.signature.copy(signatureBase64 = "!!!not-base64!!!")),
            keyPair.public,
        )
        assertThat(got.ok).isFalse()
        assertThat(got.reason).contains("base64")
    }

    @Test
    fun `corrupted package gzip is rejected during decode`() {
        val built = signed()
        val keyRing = TrustedKeyRing(listOf(built.signingPublicKey))
        val truncated = built.fullPayload.copyOf(built.fullPayload.size - 8) // cut the gzip trailer

        val result = ReleaseVerifier.verifyFullEnvelope(
            built.envelope,
            truncated,
            keyRing,
            installedVersion = 1050,
            currentAppVersionCode = 99,
            maxObservedVersion = 1050,
        )
        // The hash catches a truncated payload even before gzip is attempted.
        assertThat(result.ok).isFalse()
    }

    @Test
    fun `PEM public key round trips`() {
        val keyPair = ReleaseCrypto.generateKeyPair()
        val pem = ReleaseCrypto.publicKeyPem(keyPair.public)
        val restored = ReleaseCrypto.parsePublicKeyPem(pem)
        assertThat(ReleaseCrypto.fingerprint(restored)).isEqualTo(ReleaseCrypto.fingerprint(keyPair.public))
        assertThat(ReleaseCrypto.verify(ReleaseCrypto.envelope(ReleaseCryptoTestData.manifest(), keyPair), restored).ok).isTrue()
    }

    @Test
    fun `PEM private key round trips`() {
        val keyPair = ReleaseCrypto.generateKeyPair()
        val pem = ReleaseCrypto.privateKeyPem(keyPair.private)
        val restored = ReleaseCrypto.parsePrivateKeyPem(pem)

        val rebuilt = java.security.KeyPair(keyPair.public, restored)
        val envelope = ReleaseCrypto.envelope(ReleaseCryptoTestData.manifest(), rebuilt)
        assertThat(ReleaseCrypto.verify(envelope, keyPair.public).ok).isTrue()
    }

    @Test
    fun `TrustedKeyRing verifies only trusted keys`() {
        val current = ReleaseCrypto.generateKeyPair()
        val rotated = ReleaseCrypto.generateKeyPair()
        val ring = TrustedKeyRing(listOf(current.public, rotated.public))
        assertThat(ring.verify(ReleaseCrypto.envelope(ReleaseCryptoTestData.manifest(), rotated)).ok).isTrue()
        assertThat(ring.verify(ReleaseCrypto.envelope(ReleaseCryptoTestData.manifest(), current)).ok).isTrue()

        val stranger = TrustedKeyRing(listOf(ReleaseCrypto.generateKeyPair().public))
        assertThat(stranger.verify(ReleaseCrypto.envelope(ReleaseCryptoTestData.manifest(), current)).ok).isFalse()
    }

    @Test
    fun `envelope JSON round trips deterministically`() {
        val built = signed()
        val text = ReleaseVerifier.encodeEnvelope(built.envelope)
        val decoded = ReleaseVerifier.parseEnvelope(text)
        assertThat(decoded.manifest.releaseId).isEqualTo(built.envelope.manifest.releaseId)
        assertThat(decoded.signature.signatureBase64).isEqualTo(built.envelope.signature.signatureBase64)
        val re = ReleaseVerifier.encodeEnvelope(decoded)
        assertThat(re).isEqualTo(text)
    }

    private fun record(domain: String, category: String) = ReleaseDomainRecord(
        domain = domain,
        normalizedDomain = domain,
        category = category,
        confidence = "HIGH",
        status = "ACTIVE",
        riskLevel = "MEDIUM",
        sourceIds = listOf("stevenblack"),
        firstSeenEpochMs = 1_700_000_000_000L,
        lastVerifiedEpochMs = 1_700_000_000_000L,
        databaseVersion = 1051,
    )
}

/** Shared manifest/helpers for tests in this package. */
object ReleaseCryptoTestData {
    fun manifest(): SignedReleaseManifest = SignedReleaseManifest(
        releaseId = "2026.09.16.1",
        version = 1051,
        previousVersion = 1050,
        generatedAtEpochMs = 1_700_000_000_000L,
        full = FullArtifact(
            fileName = "blocklist-1051.json.gz",
            sha256 = "a".repeat(64),
            sizeBytes = 1234,
        ),
    )
}