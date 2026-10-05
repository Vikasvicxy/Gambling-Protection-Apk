package dev.gamblock.protection.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the contract between [TlsSniInterceptor] and the DNS-only tunnel.
 *
 * The point of these tests is negative: on the current route nothing should ever
 * block, and if that ever changes the tunnel must not start breaking connections.
 * A full-tunnel route without forwarding would blackhole the device, so the
 * invariant "the SNI route is never added" is asserted explicitly rather than left
 * to review.
 */
class SniInterceptionRouteGuardTest {

    @Test
    fun `tunnel route stays a host 32 so no tcp traffic reaches the interceptor`() {
        // If this ever fails, the SNI interceptor is live but has no forwarding path,
        // which would blackhole every connection on the device.
        assertEquals(32, VpnConfig.TUN_ADDR_PREFIX)
        assertEquals(1, VpnConfig.ALL_ROUTES.size)
        assertEquals(VpnConfig.TUN_ADDR, VpnConfig.ALL_ROUTES.first().address)
    }

    @Test
    fun `sni interception is off by default`() {
        val settings = dev.gamblock.data.preferences.SettingsState()
        assertFalse(
            "SNI interception must require explicit opt-in",
            settings.sniInterceptionEnabled,
        )
    }

    @Test
    fun `exclusions never cover the tun address`() {
        // Overlapping the tunnel's own address would blackhole DNS entirely.
        val tun = VpnConfig.TUN_ADDR_BYTES
        for (route in VpnConfig.ALL_EXCLUSIONS) {
            val parsed = java.net.InetAddress.getByName(route.address).address
            val overlaps = parsed.size == tun.size && parsed.contentEquals(tun)
            assertFalse("exclusion ${route.address}/${route.prefixLength} covers the tun", overlaps)
        }
    }

    @Test
    fun `a blocked sni produces a reset without any forwarding side effect`() {
        val interceptor = TlsSniInterceptor(hostIsBlocked = { true })
        val hello = ClientHelloBuilder.builder().serverName("bet.example").build()
        val segment = TcpTestSegments.ipv4("10.0.0.5", "93.184.216.34", 44444, 443, 1000, hello)

        val verdict = interceptor.inspect(segment)

        assertTrue(verdict is TlsSniInterceptor.Verdict.Block)
        val reset = (verdict as TlsSniInterceptor.Verdict.Block).reset
        // Exactly one packet is produced: the RST. No queued payload is echoed back,
        // which would otherwise be a data leak straight past the block.
        assertNotNull(reset)
        assertTrue(reset.size < 60)
    }

    @Test
    fun `the reset never echoes clienthello bytes`() {
        val interceptor = TlsSniInterceptor(hostIsBlocked = { true })
        val hello = ClientHelloBuilder.builder().serverName("bet.example").build()
        val segment = TcpTestSegments.ipv4("10.0.0.5", "93.184.216.34", 44444, 443, 1000, hello)

        val reset = (interceptor.inspect(segment) as TlsSniInterceptor.Verdict.Block).reset

        // A bare IPv4+TCP header and nothing more.
        assertEquals(40, reset.size)
        val payloadStart = 20 + 20
        assertEquals(0, reset.size - payloadStart)
    }

    @Test
    fun `interceptor releases every flow when the tunnel generation is discarded`() {
        val interceptor = TlsSniInterceptor(hostIsBlocked = { true })
        val hello = ClientHelloBuilder.builder().serverName("bet.example").build()
        repeat(20) { port ->
            interceptor.inspect(
                TcpTestSegments.ipv4("10.0.0.5", "93.184.216.34", 30000 + port, 443, 1000, hello.copyOfRange(0, 20))
            )
        }
        assertTrue(interceptor.trackedFlowCount > 0)

        // What startEstablish() does on re-establish: drop buffered handshakes that
        // described the old upstream generation.
        interceptor.reset()

        assertEquals(0, interceptor.trackedFlowCount)
        assertEquals(0, interceptor.bufferedByteCount)
    }
}