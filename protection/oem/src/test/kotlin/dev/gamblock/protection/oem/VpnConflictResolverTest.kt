package dev.gamblock.protection.oem

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VpnConflictResolverTest {

    @Test
    fun `another app VPN is a conflict when shield is not running`() {
        val result = VpnConflictResolver.resolve(
            activeNetworkHasVpnTransport = true,
            shieldVpnActive = false,
            hasDefaultNetwork = true,
            activeTransportNames = listOf("VPN", "WIFI"),
        )
        assertThat(result.activeNetworkUsesVpnTransport).isTrue()
        assertThat(result.activeNetworkVpnIsShield).isFalse()
        assertThat(result.hasDefaultNetwork).isTrue()
        assertThat(result.activeTransportNames).containsExactly("VPN", "WIFI").inOrder()
    }

    @Test
    fun `shield own tunnel is healthy and never reported as another VPN`() {
        val result = VpnConflictResolver.resolve(
            activeNetworkHasVpnTransport = true,
            shieldVpnActive = true,
            hasDefaultNetwork = true,
            activeTransportNames = listOf("VPN", "WIFI"),
        )
        assertThat(result.activeNetworkUsesVpnTransport).isFalse()
        assertThat(result.activeNetworkVpnIsShield).isTrue()
    }

    @Test
    fun `no vpn transport reports no conflict`() {
        val result = VpnConflictResolver.resolve(
            activeNetworkHasVpnTransport = false,
            shieldVpnActive = false,
            hasDefaultNetwork = true,
            activeTransportNames = listOf("WIFI"),
        )
        assertThat(result.activeNetworkUsesVpnTransport).isFalse()
        assertThat(result.activeNetworkVpnIsShield).isFalse()
    }

    @Test
    fun `no default network reports no conflict and no shield tunnel`() {
        val result = VpnConflictResolver.resolve(
            activeNetworkHasVpnTransport = false,
            shieldVpnActive = false,
            hasDefaultNetwork = false,
            activeTransportNames = emptyList(),
        )
        assertThat(result.hasDefaultNetwork).isFalse()
        assertThat(result.activeNetworkUsesVpnTransport).isFalse()
        assertThat(result.activeTransportNames).isEmpty()
    }
}