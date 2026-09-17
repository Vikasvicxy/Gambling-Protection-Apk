package dev.gamblock.core.integrity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SecureNonceProviderTest {

    private val provider = SecureNonceProvider()

    @Test
    fun `nonces are 32 bytes`() {
        assertThat(provider.nonce().size).isEqualTo(32)
    }

    @Test
    fun `nonces are unique`() {
        val first = provider.nonce().toHex()
        val second = provider.nonce().toHex()
        assertThat(first).isNotEqualTo(second)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}