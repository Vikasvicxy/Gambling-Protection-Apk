package dev.gamblock.core.integrity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceIntegrityBindingTest {

    private val availableClient = object : StandardIntegrityClient {
        override fun isAvailable(): Boolean = true
        override suspend fun requestToken(nonce: ByteArray, cloudProjectNumber: Long): String = "token"
    }

    private val placeholder = IntegrityConfig(cloudProjectNumber = -1L)
    private val configured = IntegrityConfig(cloudProjectNumber = 123L)

    @Test
    fun `placeholder config keeps the client fail-closed`() {
        assertThat(standardIntegrityClientFor(placeholder) is UnavailableStandardIntegrityClient).isTrue()
    }

    @Test
    fun `disabled config keeps the client fail-closed`() {
        assertThat(standardIntegrityClientFor(configured.copy(enabled = false)) is UnavailableStandardIntegrityClient).isTrue()
    }

    @Test
    fun `debug build resolves the unavailable provider even with an available client`() {
        val provider = deviceIntegrityFor(availableClient, SecureNonceProvider(), placeholder)
        assertThat(provider is UnavailableDeviceIntegrity).isTrue()
    }

    @Test
    fun `disabled config resolves the unavailable provider`() {
        val provider = deviceIntegrityFor(availableClient, SecureNonceProvider(), configured.copy(enabled = false))
        assertThat(provider is UnavailableDeviceIntegrity).isTrue()
    }

    @Test
    fun `unavailable client resolves the unavailable provider`() {
        val client = UnavailableStandardIntegrityClient()
        val provider = deviceIntegrityFor(client, SecureNonceProvider(), configured)
        assertThat(provider is UnavailableDeviceIntegrity).isTrue()
    }

    @Test
    fun `configured and available client resolves the play integrity adapter`() {
        val provider = deviceIntegrityFor(availableClient, SecureNonceProvider(), configured)
        assertThat(provider is PlayIntegrityDeviceIntegrity).isTrue()
    }
}