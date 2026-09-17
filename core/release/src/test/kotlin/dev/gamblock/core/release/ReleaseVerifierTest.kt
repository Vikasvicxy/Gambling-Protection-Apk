package dev.gamblock.core.release

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReleaseVerifierTest {

    private val key = ReleaseCrypto.generateKeyPair()

    private fun record(domain: String) = ReleaseDomainRecord(
        domain = domain,
        normalizedDomain = domain,
        category = "CASINO",
        confidence = "HIGH",
        status = "ACTIVE",
        riskLevel = "MEDIUM",
        sourceIds = listOf("stevenblack"),
        firstSeenEpochMs = 1L,
        lastVerifiedEpochMs = 1L,
        databaseVersion = 2,
    )

    private fun base(t: Int) = LinkedHashMap<String, ReleaseDomainRecord>().apply {
        put("a.test", record("a.test"))
        put("b.test", record("b.test"))
        put("c.test", record("c.test"))
    }

    private fun target(t: Int) = LinkedHashMap<String, ReleaseDomainRecord>().apply {
        put("a.test", record("a.test"))
        put("b.test", record("b.test"))
        put("c.test", record("c.test"))
        put("d.test", record("d.test"))
    }

    @Test
    fun `full release verifies end to end`() {
        val built = ReleaseBuilder.build(
            previousRecords = null,
            nextRecords = target(1).values.toList(),
            releaseId = "r2",
            version = 2,
            channel = UpdateChannel.STABLE,
            generatedAtEpochMs = 2_000L,
            minimumAppVersion = 1,
            signingKey = key,
        )
        val result = ReleaseVerifier.verifyFull(
            interpretString(built),
            built.fullPayload,
            TrustedKeyRing(listOf(key.public)),
            installedVersion = 1,
            currentAppVersionCode = 10,
            maxObservedVersion = 1,
        )
        assertThat(result.ok).isTrue()
    }

    private fun interpretString(built: BuiltRelease): String {
        // Suspenders: use the canonical encoder on the parsed envelope (constant with what
        // a real CDN hosts) to guarantee the test exercises the same wire bytes.
        return ReleaseVerifier.encodeEnvelope(built.envelope)
    }

    @Test
    fun `full release with tampered payload fails`() {
        val built = ReleaseBuilder.build(
            previousRecords = null,
            nextRecords = target(1).values.toList(),
            releaseId = "r2",
            version = 2,
            channel = UpdateChannel.STABLE,
            generatedAtEpochMs = 2_000L,
            minimumAppVersion = 1,
            signingKey = key,
        )
        val tampered = built.fullPayload.copyOf()
        tampered[tampered.size / 2] = (tampered[tampered.size / 2] + 1).toByte()
        val result = ReleaseVerifier.verifyFull(
            interpretString(built),
            tampered,
            TrustedKeyRing(listOf(key.public)),
            installedVersion = 1,
            currentAppVersionCode = 10,
            maxObservedVersion = 1,
        )
        assertThat(result.ok).isFalse()
        assertThat(result.reason).contains("sha256 mismatch")
    }

    @Test
    fun `full release with replayed lower version is rejected`() {
        val built = ReleaseBuilder.build(
            previousRecords = null,
            nextRecords = target(1).values.toList(),
            releaseId = "r2",
            version = 2,
            channel = UpdateChannel.STABLE,
            generatedAtEpochMs = 2_000L,
            minimumAppVersion = 1,
            signingKey = key,
        )
        // Device already saw version 2 (maxObserved=2) → replay guard.
        val result = ReleaseVerifier.verifyFull(
            interpretString(built),
            built.fullPayload,
            TrustedKeyRing(listOf(key.public)),
            installedVersion = 2,
            currentAppVersionCode = 10,
            maxObservedVersion = 2,
        )
        assertThat(result.ok).isFalse()
        assertThat(result.reason).contains("replay")
    }

    @Test
    fun `delta release verifies and base mismatch instead falls back to full`() {
        val prev = base(1)
        val next = target(2)
        val built = ReleaseBuilder.build(
            previousRecords = prev.values.toList(),
            nextRecords = next.values.toList(),
            releaseId = "r2",
            version = 2,
            channel = UpdateChannel.STABLE,
            generatedAtEpochMs = 2_000L,
            minimumAppVersion = 1,
            signingKey = key,
        )
        assertThat(built.deltaPayload).isNotNull()

        val ok = ReleaseVerifier.verifyDelta(
            built.envelope,
            built.deltaPayload!!,
            TrustedKeyRing(listOf(key.public)),
            installedVersion = 1,
            currentAppVersionCode = 10,
            maxObservedVersion = 1,
        )
        assertThat(ok.ok).isTrue()

        // Device on an older version must not apply the delta; fall back to full.
        val mismatch = ReleaseVerifier.verifyDelta(
            built.envelope,
            built.deltaPayload!!,
            TrustedKeyRing(listOf(key.public)),
            installedVersion = 0,
            currentAppVersionCode = 10,
            maxObservedVersion = 0,
        )
        assertThat(mismatch.ok).isFalse()
        assertThat(mismatch.reason).contains("fall back to full")
    }

    @Test
    fun `delta release with tampered ops fails`() {
        val prev = base(1)
        val next = target(2)
        val built = ReleaseBuilder.build(
            previousRecords = prev.values.toList(),
            nextRecords = next.values.toList(),
            releaseId = "r2",
            version = 2,
            channel = UpdateChannel.STABLE,
            generatedAtEpochMs = 2_000L,
            minimumAppVersion = 1,
            signingKey = key,
        )
        val tampered = built.deltaPayload!!.copyOf()
        tampered[0] = (tampered[0] + 1).toByte()
        val result = ReleaseVerifier.verifyDelta(
            built.envelope,
            tampered,
            TrustedKeyRing(listOf(key.public)),
            installedVersion = 1,
            currentAppVersionCode = 10,
            maxObservedVersion = 1,
        )
        assertThat(result.ok).isFalse()
    }

    @Test
    fun `min app version gate blocks older clients`() {
        val built = ReleaseBuilder.build(
            previousRecords = null,
            nextRecords = target(1).values.toList(),
            releaseId = "r2",
            version = 2,
            channel = UpdateChannel.STABLE,
            generatedAtEpochMs = 2_000L,
            minimumAppVersion = 99,
            signingKey = key,
        )
        val result = ReleaseVerifier.verifyFull(
            interpretString(built),
            built.fullPayload,
            TrustedKeyRing(listOf(key.public)),
            installedVersion = 1,
            currentAppVersionCode = 10,
            maxObservedVersion = 1,
        )
        assertThat(result.ok).isFalse()
        assertThat(result.reason).contains("minimum app version")
    }
}