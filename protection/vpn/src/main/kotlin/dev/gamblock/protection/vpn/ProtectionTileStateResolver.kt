package dev.gamblock.protection.vpn

import dev.gamblock.core.model.VpnRuntimeState

/** Visual states the Quick Settings tile can surface. */
enum class ProtectionTileUi {
    ACTIVE,
    CONNECTING,
    INACTIVE,
    UNAVAILABLE,
}

/**
 * Everything the tile needs to render, kept free of Android framework types so the
 * mapping from runtime state to a tile presentation is fully unit-testable.
 */
data class ProtectionTileState(
    val ui: ProtectionTileUi,
    val label: String,
    val subtitle: String,
    val queriesBlocked: Long,
)

/**
 * Translates [VpnRuntimeState] plus the user's protection preference into a tile
 * presentation. Purely functional - no dependencies other than the core model.
 */
object ProtectionTileStateResolver {

    fun resolve(vpnEnabled: Boolean, vpn: VpnRuntimeState): ProtectionTileState {
        val failure = vpn.failure
        val ui: ProtectionTileUi
        val label: String
        val subtitle: String

        when {
            // A hard failure means the app cannot currently run its tunnel.
            failure == VpnRuntimeState.VpnFailure.REVOKED ||
                failure == VpnRuntimeState.VpnFailure.AUTH_NOT_GRANTED ||
                failure == VpnRuntimeState.VpnFailure.STARTUP_ERROR ||
                failure == VpnRuntimeState.VpnFailure.ESTABLISH_FAILED -> {
                ui = ProtectionTileUi.UNAVAILABLE
                label = "Protection blocked"
                subtitle = if (vpnEnabled) "Tap to restart" else "Tap to set up"
            }

            vpn.isRunning -> {
                ui = ProtectionTileUi.ACTIVE
                label = "Shield active"
                subtitle = "${vpn.queriesBlocked} blocked queries"
            }

            vpnEnabled && !vpn.isRunning && failure == VpnRuntimeState.VpnFailure.IN_SCHEDULE_PAUSE -> {
                ui = ProtectionTileUi.INACTIVE
                label = "Paused by schedule"
                subtitle = "Tap to force on"
            }

            vpnEnabled && !vpn.isRunning -> {
                ui = ProtectionTileUi.CONNECTING
                label = "Shield starting"
                subtitle = "Establishing tunnel"
            }

            else -> {
                ui = ProtectionTileUi.INACTIVE
                label = "Shield off"
                subtitle = "Tap to protect"
            }
        }

        return ProtectionTileState(
            ui = ui,
            label = label,
            subtitle = subtitle,
            queriesBlocked = vpn.queriesBlocked,
        )
    }
}