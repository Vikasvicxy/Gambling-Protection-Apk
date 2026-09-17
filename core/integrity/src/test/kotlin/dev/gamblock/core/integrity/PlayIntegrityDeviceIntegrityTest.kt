package dev.gamblock.core.integrity

import com.google.common.truth.Truth.assertThat
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlayIntegrityDeviceIntegrityTest {

    private val nonce = ByteArray(32) { it.toByte() }

    private fun provider(
        client: StandardIntegrityClient,
        config: IntegrityConfig = IntegrityConfig(cloudProjectNumber = 123L),
    ) = PlayIntegrityDeviceIntegrity(
        client = client,
        nonceProvider = FixedNonceProvider(nonce),
    ) to config

    @Test
    fun `available client returns attestation bound to the request nonce`() = runTest {
        val client = FakeIntegrityClient(token = "eyJhbGciOiJFUzI1NiJ9.payload.sig")
        val (provider, config) = provider(client)

        val result = provider.attest("dev.gamblock.shield", "digest", config)

        assertThat(result is IntegrityResult.Success).isTrue()
        val success = result as IntegrityResult.Success
        assertThat(success.attestation.provider).isEqualTo(PlayIntegrityDeviceIntegrity.PROVIDER)
        assertThat(success.attestation.packageName).isEqualTo("dev.gamblock.shield")
        assertThat(success.attestation.appCertificateDigestSha256).isEqualTo("digest")
        assertThat(success.attestation.token).isEqualTo("eyJhbGciOiJFUzI1NiJ9.payload.sig")
        assertThat(success.attestation.nonce).isEqualTo(Base64.getEncoder().encodeToString(nonce))
        assertThat(success.attestation.verdict).isEqualTo(IntegrityVerdict.UNKNOWN)
    }

    @Test
    fun `request forwards nonce and cloud project number`() = runTest {
        val client = FakeIntegrityClient(token = "t0")
        val (provider, config) = provider(client)

        provider.attest("dev.gamblock.shield", "digest", config)

        assertThat(client.lastCloudProjectNumber).isEqualTo(123L)
        assertThat(client.lastNonce).isEqualTo(nonce)
    }

    @Test
    fun `unavailable client fails closed without crashing`() = runTest {
        val (provider, config) = provider(FakeIntegrityClient(available = false))

        val result = provider.attest("dev.gamblock.shield", "digest", config)

        assertThat(result is IntegrityResult.Unavailable).isTrue()
        val unavailable = result as IntegrityResult.Unavailable
        assertThat(unavailable.reason).contains("not available")
    }

    @Test
    fun `disabled config stays unavailable even when client is available`() = runTest {
        val (provider, _) = provider(
            FakeIntegrityClient(available = true),
            config = IntegrityConfig(cloudProjectNumber = 123L, enabled = false),
        )

        val result = provider.attest("dev.gamblock.shield", "digest", IntegrityConfig(cloudProjectNumber = 123L, enabled = false))

        assertThat(result is IntegrityResult.Unavailable).isTrue()
        val unavailable = result as IntegrityResult.Unavailable
        assertThat(unavailable.reason).contains("disabled")
    }

    @Test
    fun `throwing client surfaces a retriable failure`() = runTest {
        val (provider, config) = provider(
            FakeIntegrityClient(throwOnRequest = IllegalStateException("gms down")),
        )

        val result = provider.attest("dev.gamblock.shield", "digest", config)

        assertThat(result is IntegrityResult.Failure).isTrue()
        val failure = result as IntegrityResult.Failure
        assertThat(failure.retriable).isTrue()
        assertThat(failure.reason).contains("gms down")
    }

    @Test
    fun `slow client is cut off by the timeout as a retriable failure`() = runTest {
        val slowClient = FakeIntegrityClient(requestDelayMs = 10_000L)
        val (provider, config) = provider(
            slowClient,
            config = IntegrityConfig(cloudProjectNumber = 123L, timeoutMs = 50L),
        )

        val result = provider.attest("dev.gamblock.shield", "digest", config)

        assertThat(result is IntegrityResult.Failure).isTrue()
        val failure = result as IntegrityResult.Failure
        assertThat(failure.retriable).isTrue()
        assertThat(failure.reason).contains("timed out")
    }

    private class FakeIntegrityClient(
        private val available: Boolean = true,
        private val token: String = "token.payload.sig",
        private val throwOnRequest: Throwable? = null,
        private val requestDelayMs: Long = 0L,
    ) : StandardIntegrityClient {
        var lastNonce: ByteArray? = null
        var lastCloudProjectNumber: Long = -1L

        override fun isAvailable(): Boolean = available

        override suspend fun requestToken(nonce: ByteArray, cloudProjectNumber: Long): String {
            if (throwOnRequest != null) throw throwOnRequest
            if (requestDelayMs > 0) delay(requestDelayMs)
            lastNonce = nonce
            lastCloudProjectNumber = cloudProjectNumber
            return token
        }
    }

    private class FixedNonceProvider(
        private val nonce: ByteArray,
    ) : NonceProvider {
        override fun nonce(): ByteArray = nonce.copyOf()
    }
}