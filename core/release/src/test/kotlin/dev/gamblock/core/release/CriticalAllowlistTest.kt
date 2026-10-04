package dev.gamblock.core.release

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The allowlist is what stops a poisoned upstream source from globally severing system
 * services. The suffix tests are the important part: a naive `endsWith` check without the
 * leading dot would let an attacker register `evilgoogle.com` and auto-activate a rule
 * against Google's infrastructure.
 */
class CriticalAllowlistTest {

    @Test
    fun `protects an exact critical base`() {
        assertThat(CriticalAllowlist.isProtected("google.com")).isTrue()
    }

    @Test
    fun `protects a direct subdomain`() {
        assertThat(CriticalAllowlist.isProtected("mail.google.com")).isTrue()
    }

    @Test
    fun `protects a deep subdomain`() {
        assertThat(CriticalAllowlist.isProtected("a.b.c.play.googleapis.com")).isTrue()
    }

    @Test
    fun `protects apple id which is a subdomain of apple com`() {
        assertThat(CriticalAllowlist.isProtected("appleid.apple.com")).isTrue()
    }

    @Test
    fun `protects android system services`() {
        assertThat(CriticalAllowlist.isProtected("android.com")).isTrue()
        assertThat(CriticalAllowlist.isProtected("clients3.google.com")).isTrue()
        assertThat(CriticalAllowlist.isProtected("redirector.gvt1.com")).isTrue()
    }

    @Test
    fun `protects dns resolvers`() {
        assertThat(CriticalAllowlist.isProtected("dns.quad9.net")).isTrue()
        assertThat(CriticalAllowlist.isProtected("doh.nextdns.io")).isTrue()
    }

    @Test
    fun `protects certificate infrastructure`() {
        assertThat(CriticalAllowlist.isProtected("ocsp.digicert.com")).isTrue()
        assertThat(CriticalAllowlist.isProtected("acme-v02.api.letsencrypt.org")).isTrue()
    }

    @Test
    fun `does not protect an unrelated domain`() {
        assertThat(CriticalAllowlist.isProtected("casino.example")).isFalse()
    }

    @Test
    fun `does not protect a lookalike prefix attack`() {
        // The classic suffix bug: these are attacker-registrable and must not match.
        assertThat(CriticalAllowlist.isProtected("evilgoogle.com")).isFalse()
        assertThat(CriticalAllowlist.isProtected("notgoogle.com")).isFalse()
        assertThat(CriticalAllowlist.isProtected("myfacebook.com")).isFalse()
        assertThat(CriticalAllowlist.isProtected("fakegithub.io")).isFalse()
    }

    @Test
    fun `does not protect a chained suffix attack`() {
        // google.com.evil.com ends with ".evil.com", not ".google.com".
        assertThat(CriticalAllowlist.isProtected("google.com.evil.example")).isFalse()
        assertThat(CriticalAllowlist.isProtected("github.io.attacker.example")).isFalse()
    }

    @Test
    fun `does not protect a bare substring of a critical name`() {
        assertThat(CriticalAllowlist.isProtected("google")).isFalse()
        assertThat(CriticalAllowlist.isProtected("com")).isFalse()
    }

    @Test
    fun `does not protect an empty domain`() {
        assertThat(CriticalAllowlist.isProtected("")).isFalse()
    }

    @Test
    fun `stays small enough to audit`() {
        // A growing allowlist quietly becomes an ad-blocking judgement, which the design
        // explicitly rejects. Keep it Tier-1 infrastructure only.
        assertThat(CriticalAllowlist.entries().size).isAtMost(100)
    }

    @Test
    fun `contains no duplicates after normalization`() {
        assertThat(CriticalAllowlist.entries().toList()).containsNoDuplicates()
    }

    @Test
    fun `every entry protects itself`() {
        CriticalAllowlist.entries().forEach { entry ->
            assertThat(CriticalAllowlist.isProtected(entry)).isTrue()
        }
    }

    @Test
    fun `every entry protects its own subdomain form`() {
        CriticalAllowlist.entries().forEach { entry ->
            assertThat(CriticalAllowlist.isProtected("sub.$entry")).isTrue()
        }
    }

    @Test
    fun `tier identifier is stable`() {
        assertThat(CriticalAllowlist.TIER).isEqualTo("critical-infrastructure")
    }
}

/**
 * Canonical encoding is the signature input. If the signer and the device disagree by a
 * single byte, every legitimate update fails verification, so determinism is a
 * correctness property rather than a nicety.
 */
class CanonicalCodecTest {

    private fun manifest(
        releaseId: String = "20260404-1",
        version: Int = 7,
        previousVersion: Int? = 6,
        rollback: Boolean = false,
        changelog: String = "added 12 domains",
    ) = SignedReleaseManifest(
        releaseId = releaseId,
        version = version,
        previousVersion = previousVersion,
        generatedAtEpochMs = 1_777_000_000_000L,
        minimumAppVersion = 5,
        rollback = rollback,
        full = FullArtifact("full-7.ndjson.gz", "a".repeat(64), 123_456L),
        delta = DeltaArtifact(6, "delta-7.ndjson.gz", "b".repeat(64), 7_890L, 1, 2, 3),
        changelog = changelog,
    )

    @Test
    fun `encoding is byte-identical across calls`() {
        val subject = manifest()

        val first = CanonicalCodec.canonicalBytes(subject)
        val second = CanonicalCodec.canonicalBytes(subject)

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `field order is stable so a signer and verifier agree`() {
        val rendered = CanonicalCodec.manifestToString(manifest())

        assertThat(rendered.indexOf("releaseId")).isLessThan(rendered.indexOf("version"))
        assertThat(rendered.indexOf("version")).isLessThan(rendered.indexOf("channel"))
        assertThat(rendered.indexOf("channel")).isLessThan(rendered.indexOf("full"))
    }

    @Test
    fun `emits no pretty printing whitespace`() {
        val rendered = CanonicalCodec.manifestToString(manifest())

        assertThat(rendered).doesNotContain("\n")
        assertThat(rendered).doesNotContain(": ")
    }

    @Test
    fun `encodes defaults so an omitted field still signs deterministically`() {
        val bare = SignedReleaseManifest(
            releaseId = "r",
            version = 1,
            generatedAtEpochMs = 0L,
            full = FullArtifact("f.gz", "c".repeat(64), 1L),
        )

        val rendered = CanonicalCodec.manifestToString(bare)

        assertThat(rendered).contains("\"schemaVersion\":1")
        assertThat(rendered).contains("\"channel\":\"STABLE\"")
        assertThat(rendered).contains("\"minimumAppVersion\":1")
        assertThat(rendered).contains("\"rollback\":false")
    }

    @Test
    fun `encodes an explicit null previousVersion`() {
        val rendered = CanonicalCodec.manifestToString(manifest(previousVersion = null))

        assertThat(rendered).contains("\"previousVersion\":null")
    }

    @Test
    fun `encodes an absent delta as explicit null`() {
        val noDelta = SignedReleaseManifest(
            releaseId = "r",
            version = 1,
            generatedAtEpochMs = 0L,
            full = FullArtifact("f.gz", "c".repeat(64), 1L),
        )

        assertThat(CanonicalCodec.manifestToString(noDelta)).contains("\"delta\":null")
    }

    @Test
    fun `utf-8 bytes match the rendered string`() {
        val subject = manifest(changelog = "café 🚀")

        assertThat(String(CanonicalCodec.canonicalBytes(subject), Charsets.UTF_8))
            .isEqualTo(CanonicalCodec.manifestToString(subject))
    }

    @Test
    fun `carries the rollback flag into the signed bytes`() {
        assertThat(CanonicalCodec.manifestToString(manifest(rollback = true))).contains("\"rollback\":true")
    }

    @Test
    fun `delta targetVersion is base plus one`() {
        val delta = DeltaArtifact(6, "d.gz", "b".repeat(64), 1L, 0, 0, 0)

        assertThat(delta.targetVersion).isEqualTo(7)
    }

    @Test
    fun `rejects an unknown field so a downgraded signer cannot smuggle data`() {
        val json = CanonicalCodec.manifestToString(manifest()).dropLast(1) + ",\"surprise\":true}"

        val error = runCatching {
            CanonicalCodec.json.decodeFromString(SignedReleaseManifest.serializer(), json)
        }.exceptionOrNull()

        assertThat(error).isNotNull()
    }

    @Test
    fun `round trips a manifest through canonical json`() {
        val original = manifest()

        val decoded = CanonicalCodec.json.decodeFromString(
            SignedReleaseManifest.serializer(),
            CanonicalCodec.manifestToString(original),
        )

        assertThat(CanonicalCodec.canonicalBytes(decoded)).isEqualTo(CanonicalCodec.canonicalBytes(original))
    }

    @Test
    fun `round trips a rollback manifest`() {
        val original = manifest(rollback = true)

        val decoded = CanonicalCodec.json.decodeFromString(
            SignedReleaseManifest.serializer(),
            CanonicalCodec.manifestToString(original),
        )

        assertThat(decoded.rollback).isTrue()
        assertThat(decoded.previousVersion).isEqualTo(6)
    }
}