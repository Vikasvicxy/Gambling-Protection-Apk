package dev.gamblock.protection.oem

import dev.gamblock.core.model.VpnConflictInfo

/**
 * Pure resolution of the "is another VPN active" question.
 *
 * A VPN transport on the currently active network is only a *conflict* when it is
 * NOT Shield's own tunnel. When Shield's VPN service is running, the active VPN
 * transport is ours and the network is healthy. Kept free of Android dependencies
 * so the semantics are unit-testable.
 */
object VpnConflictResolver {

    fun resolve(
        activeNetworkHasVpnTransport: Boolean,
        shieldVpnActive: Boolean,
        hasDefaultNetwork: Boolean,
        activeTransportNames: List<String>,
    ): VpnConflictInfo = VpnConflictInfo(
        activeNetworkUsesVpnTransport = activeNetworkHasVpnTransport && !shieldVpnActive,
        activeNetworkVpnIsShield = activeNetworkHasVpnTransport && shieldVpnActive,
        hasDefaultNetwork = hasDefaultNetwork,
        activeTransportNames = activeTransportNames,
    )
}