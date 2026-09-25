package dev.gamblock.protection.vpn

import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import org.junit.Test

class DnsUpstreamProviderTest {

    private fun address(host: String): InetAddress = InetAddress.getByName(host)

    @Test
    fun `usables keeps normal resolvers`() {
        val servers = listOf(address("8.8.8.8"), address("192.168.1.1"), address("10.0.0.2"))
        assertThat(DnsUpstreamProvider.usables(servers)).containsExactlyElementsIn(servers).inOrder()
    }

    @Test
    fun `usables drops loopback and any-local addresses`() {
        val loopback = address("127.0.0.1")
        val anyLocal = address("0.0.0.0")
        assertThat(DnsUpstreamProvider.usables(listOf(loopback, anyLocal))).isEmpty()
    }

    @Test
    fun `usables keeps private tun-style addresses as valid upstream targets`() {
        // The physical network may legitimately serve DNS from a private router
        // address (e.g. inside 10.147.2.0/24); it must not be filtered out.
        val private = address("10.147.2.1")
        assertThat(DnsUpstreamProvider.usables(listOf(private))).containsExactly(private)
    }
}