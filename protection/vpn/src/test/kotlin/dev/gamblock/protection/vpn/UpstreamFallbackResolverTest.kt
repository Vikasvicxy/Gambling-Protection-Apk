package dev.gamblock.protection.vpn

import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import org.junit.Test

class UpstreamFallbackResolverTest {

    private fun addr(bytes: ByteArray): InetAddress = InetAddress.getByAddress(bytes)

    @Test
    fun `ordered keeps physical resolvers first`() {
        val physical = listOf(addr(byteArrayOf(10, 0, 0, 1)))
        val ordered = UpstreamFallbackResolver.ordered(physical)
        assertThat(ordered.first()).isEqualTo(physical.first())
        assertThat(ordered).hasSize(1 + UpstreamFallbackResolver.PUBLIC_FALLBACKS.size)
    }

    @Test
    fun `ordered dedupes physical resolvers that shadow fallbacks`() {
        // Physical already IS a public resolver; must not be duplicated.
        val cloudflare = UpstreamFallbackResolver.ipv4Literal("1.1.1.1")!!
        val ordered = UpstreamFallbackResolver.ordered(listOf(cloudflare))
        assertThat(ordered.count { it == cloudflare }).isEqualTo(1)
        assertThat(ordered).hasSize(UpstreamFallbackResolver.PUBLIC_FALLBACKS.size)
    }

    @Test
    fun `ordered appends public fallbacks when physical is empty`() {
        val ordered = UpstreamFallbackResolver.ordered(emptyList())
        assertThat(ordered).hasSize(UpstreamFallbackResolver.PUBLIC_FALLBACKS.size)
        assertThat(ordered.map { it.hostAddress }).containsExactlyElementsIn(
            UpstreamFallbackResolver.PUBLIC_FALLBACKS,
        )
    }

    @Test
    fun `ipv4Literal parses valid literals without DNS`() {
        val parsed = UpstreamFallbackResolver.ipv4Literal("9.9.9.9")!!
        assertThat(parsed.hostAddress).isEqualTo("9.9.9.9")
        assertThat(parsed.isAnyLocalAddress).isFalse()
    }

    @Test
    fun `ipv4Literal rejects malformed input`() {
        assertThat(UpstreamFallbackResolver.ipv4Literal("not-an-ip")).isNull()
        assertThat(UpstreamFallbackResolver.ipv4Literal("1.2.3")).isNull()
        assertThat(UpstreamFallbackResolver.ipv4Literal("1.2.3.999")).isNull()
    }
}