package dev.gamblock.core.integrity

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UnavailableDeviceIntegrityTest {

    private val provider = UnavailableDeviceIntegrity()
    private val config = IntegrityConfig(cloudProjectNumber = 123L)

    @Test
    fun `attest never fabricates evidence`() = runTest {
        val result = provider.attest("dev.gamblock.shield", "digest", config)
        assertThat(result is IntegrityResult.Unavailable).isTrue()
        val unavailable = result as IntegrityResult.Unavailable
        assertThat(unavailable.reason).contains("no integrity provider")
    }

    @Test
    fun `attest returns unavailable even when disabled`() = runTest {
        val disabled = config.copy(enabled = false)
        val result = provider.attest("dev.gamblock.shield", "digest", disabled)
        assertThat(result is IntegrityResult.Unavailable).isTrue()
    }
}