package dev.gamblock.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamblock.core.common.clock.WallClock
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.model.ProtectionMode
import dev.gamblock.data.preferences.SettingsRepository
import dev.gamblock.protection.vpn.ShieldVpnService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges UI / boot recovery to the VPN service without creating a direct
 * protection:battery or feature dependency on the VPN transport.
 */
@Singleton
class ProtectionEnforcer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val wallClock: WallClock,
    private val logger: ShieldLogger,
) {
    fun enforceNow() {
        val settings = settingsRepository.settings.value
        if (settings.protectionMode == ProtectionMode.SELF_PROTECTION) {
            ShieldVpnService.launch(context)
        } else {
            logger.i(Logs.SECURITY, "non-self-protection mode requested; VPN not launched")
        }
    }

    fun stopNow(reason: String = "user-requested") {
        logger.i(Logs.SECURITY, "stopping protection: $reason")
        ShieldVpnService.stop(context)
    }
}