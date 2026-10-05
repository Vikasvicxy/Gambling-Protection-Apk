package dev.gamblock.protection.vpn

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards the routing invariants that broke once already: the tunnel address
 * living inside an excluded private range, which blackholes every DNS lookup.
 */
class VpnRoutingTest {

    private fun ipv4(address: String): ByteArray =
        address.split('.').map { it.toInt().toByte() }.toByteArray()

    private fun ipv4ToLong(bytes: ByteArray): Long =
        bytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }

    private fun mask(prefix: Int): Long {
        require(prefix in 0..32)
        return if (prefix == 0) 0L else (-1L shl (32 - prefix)) and 0xFFFF_FFFFL
    }

    private fun contains(route: LanRoute, target: ByteArray): Boolean {
        val network = ipv4ToLong(ipv4(route.address)) and mask(route.prefixLength)
        val address = ipv4ToLong(target)
        return (address and mask(route.prefixLength)) == network
    }

    @Test
    fun `tunnel address is in the benchmarking range`() {
        val tun = ipv4(VpnConfig.TUN_ADDR)

        assertThat(contains(LanRoute("198.18.0.0", 15), tun)).isTrue()
    }

    @Test
    fun `tunnel address is outside every excluded route`() {
        val tun = ipv4(VpnConfig.TUN_ADDR)

        VpnConfig.ALL_EXCLUSIONS.filter { it.address.contains('.') }.forEach { route ->
            assertThat(contains(route, tun)).isFalse()
        }
    }

    @Test
    fun `tunnel address is not inside the rfc1918 ranges`() {
        val tun = ipv4(VpnConfig.TUN_ADDR)

        listOf(LanRoute("10.0.0.0", 8), LanRoute("172.16.0.0", 12), LanRoute("192.168.0.0", 16)).forEach {
            assertThat(contains(it, tun)).isFalse()
        }
    }

    @Test
    fun `excludes the full rfc1918 space`() {
        listOf("10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.254", "192.168.0.1", "192.168.255.254")
            .forEach { host ->
                val matching = VpnConfig.LAN_EXCLUSIONS.filter { contains(it, ipv4(host)) }
                assertThat(matching).isNotEmpty()
            }
    }

    @Test
    fun `does not exclude addresses just outside rfc1918`() {
        listOf("9.255.255.255", "11.0.0.0", "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0")
            .forEach { host ->
                val matching = VpnConfig.LAN_EXCLUSIONS.filter { contains(it, ipv4(host)) }
                assertThat(matching).isEmpty()
            }
    }

    @Test
    fun `excludes link-local`() {
        assertThat(contains(LanRoute("169.254.0.0", 16), ipv4("169.254.1.1"))).isTrue()
        assertThat(VpnConfig.LOCAL_EXCLUSIONS.any { contains(it, ipv4("169.254.1.1")) }).isTrue()
    }

    @Test
    fun `every exclusion address is a valid parseable literal`() {
        VpnConfig.ALL_EXCLUSIONS.forEach { route ->
            val parsed = runCatching { java.net.InetAddress.getByName(route.address) }
            assertThat(parsed.isSuccess).isTrue()
        }
    }

    @Test
    fun `every exclusion parses to the family its prefix implies`() {
        VpnConfig.ALL_EXCLUSIONS.forEach { route ->
            val parsed = java.net.InetAddress.getByName(route.address)
            if (route.address.contains(':')) {
                assertThat(parsed.javaClass.name).contains("Inet6")
                assertThat(route.prefixLength).isAtMost(128)
            } else {
                assertThat(parsed.javaClass.name).contains("Inet4")
                assertThat(route.prefixLength).isAtMost(32)
            }
        }
    }

    @Test
    fun `exclusion addresses carry no host bits because IpPrefix rejects them`() {
        // IpPrefix(address, prefix) throws if bits below the prefix are set, which
        // would silently drop every exclusion at runtime.
        VpnConfig.ALL_EXCLUSIONS.forEach { route ->
            val parsed = java.net.InetAddress.getByName(route.address).address
            val expectedLength = if (route.address.contains(':')) 16 else 4
            assertThat(parsed.size).isEqualTo(expectedLength)

            val masked = java.net.InetAddress.getByAddress(
                route.address,
                java.util.Arrays.copyOf(parsed, parsed.size),
            )
            val prefix = route.prefixLength
            val fullBytes = prefix / 8
            val spareBits = prefix % 8
            val normalized = java.util.Arrays.copyOf(parsed, parsed.size)
            if (spareBits != 0 && fullBytes < normalized.size) {
                val keep = (0xFF shl (8 - spareBits)) and 0xFF
                normalized[fullBytes] = (parsed[fullBytes].toInt() and keep).toByte()
                for (i in fullBytes + 1 until normalized.size) normalized[i] = 0
            }
            assertThat(masked.address.toList()).isEqualTo(normalized.toList())
        }
    }

    @Test
    fun `exclusion list has no duplicate routes`() {
        assertThat(VpnConfig.ALL_EXCLUSIONS).containsNoDuplicates()
    }

    @Test
    fun `exclusion list covers ipv4 and ipv6`() {
        assertThat(VpnConfig.ALL_EXCLUSIONS.any { it.address.contains(':') }).isTrue()
        assertThat(VpnConfig.ALL_EXCLUSIONS.any { it.address.contains('.') }).isTrue()
    }

    @Test
    fun `every prefix length is valid`() {
        VpnConfig.ALL_EXCLUSIONS.forEach { route ->
            assertThat(route.prefixLength).isAtLeast(0)
            if (route.address.contains(':')) {
                assertThat(route.prefixLength).isAtMost(128)
            } else {
                assertThat(route.prefixLength).isAtMost(32)
            }
        }
    }

    @Test
    fun `mask handles the degenerate prefixes`() {
        assertThat(mask(0)).isEqualTo(0L)
        assertThat(mask(32)).isEqualTo(-1L and 0xFFFF_FFFFL)
    }

    @Test
    fun `tun bytes constant matches the address`() {
        assertThat(VpnConfig.TUN_ADDR_BYTES.toList()).isEqualTo(listOf(198.toByte(), 18.toByte(), 0.toByte(), 1.toByte()))
    }

    @Test
    fun `dns port and mtu are unchanged by the address move`() {
        assertThat(VpnConfig.DNS_PORT).isEqualTo(53)
        assertThat(VpnConfig.TUN_MTU).isEqualTo(1400)
    }
}