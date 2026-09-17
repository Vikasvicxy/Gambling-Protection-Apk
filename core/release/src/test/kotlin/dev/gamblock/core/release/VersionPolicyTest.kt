package dev.gamblock.core.release

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VersionPolicyTest {

    private fun manifest(version: Int, rollback: Boolean = false, minApp: Int = 1): SignedReleaseManifest =
        SignedReleaseManifest(
            releaseId = "r$version",
            version = version,
            previousVersion = version - 1,
            generatedAtEpochMs = 1_000L,
            minimumAppVersion = minApp,
            rollback = rollback,
            full = FullArtifact("blob", "f".repeat(64), 10),
        )

    @Test
    fun `forward upgrade allowed`() {
        val r = VersionPolicy.evaluateUpgrade(manifest(2), installedVersion = 1, currentAppVersionCode = 10, maxObservedVersion = 1)
        assertThat(r.allowed).isTrue()
        assertThat(r.reason).contains("forward")
    }

    @Test
    fun `downgrade rejected`() {
        val r = VersionPolicy.evaluateUpgrade(manifest(1), installedVersion = 2, currentAppVersionCode = 10, maxObservedVersion = 2)
        assertThat(r.allowed).isFalse()
        assertThat(r.reason).contains("downgrade")
    }

    @Test
    fun `same version replay rejected`() {
        val r = VersionPolicy.evaluateUpgrade(manifest(2), installedVersion = 2, currentAppVersionCode = 10, maxObservedVersion = 2)
        assertThat(r.allowed).isFalse()
        assertThat(r.reason).contains("replay")
    }

    @Test
    fun `rollback allowed only when strictly less and signed`() {
        // installed=5, maxObserved=5 → a rollback to 4 is legal.
        val r = VersionPolicy.evaluateUpgrade(
            manifest(4, rollback = true),
            installedVersion = 5,
            currentAppVersionCode = 10,
            maxObservedVersion = 5,
        )
        assertThat(r.allowed).isTrue()
        assertThat(r.reason).contains("rollback")
    }

    @Test
    fun `forward rollback flag can never be used as an upgrade`() {
        // rollback flag with version>=installed must be rejected outright.
        val r = VersionPolicy.evaluateUpgrade(
            manifest(4, rollback = true),
            installedVersion = 3,
            currentAppVersionCode = 10,
            maxObservedVersion = 3,
        )
        assertThat(r.allowed).isFalse()
    }

    @Test
    fun `rollback below maxObserved rejected`() {
        // maxObserved=4 means the floor of ever-signed versions is 4; rolling further
        // below that (to a version >= the floor) is impossible.
        val r = VersionPolicy.evaluateUpgrade(
            manifest(4, rollback = true),
            installedVersion = 5,
            currentAppVersionCode = 10,
            maxObservedVersion = 4,
        )
        assertThat(r.allowed).isFalse()
        assertThat(r.reason).contains("impossible")
    }

    @Test
    fun `unsigned downgrade without rollback flag rejected`() {
        val r = VersionPolicy.evaluateUpgrade(manifest(4), installedVersion = 5, currentAppVersionCode = 10, maxObservedVersion = 5)
        assertThat(r.allowed).isFalse()
    }

    @Test
    fun `minimum app version gate`() {
        val r = VersionPolicy.evaluateUpgrade(manifest(2, minApp = 50), installedVersion = 1, currentAppVersionCode = 10, maxObservedVersion = 1)
        assertThat(r.allowed).isFalse()
        assertThat(r.reason).contains("minimum app version")
    }
}