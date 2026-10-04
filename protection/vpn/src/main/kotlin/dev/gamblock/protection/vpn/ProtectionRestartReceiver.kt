package dev.gamblock.protection.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.gamblock.data.preferences.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Restarts protection after events that reliably kill it.
 *
 * Two situations end a tunnel that should have stayed up:
 *
 *  - **The device rebooted.** No VPN survives a reboot on any Android version. The notification is
 *    gone by the time the user unlocks the phone, so without this the app is simply off until they
 *    notice it.
 *  - **The package was replaced.** An update tears down the running service, so the user is
 *    unprotected until they open the app after every single update.
 *
 * Only restarts when the user had actually asked for protection. Bouncing the VPN on boot for
 * someone who deliberately turned it off is a bug, not a convenience.
 *
 * This does not try to defeat an explicit "don't allow auto-start" decision. If the OS has
 * blocked background starts the call below simply fails, and the diagnostics card tells the user
 * what to change. Silently retrying past a user's stated preference would be the wrong behaviour.
 *
 * The preference read is a suspend DataStore call, so it runs under [goAsync] rather than
 * blocking the main thread. A blocked broadcast here is a dropped protection restart.
 */
@AndroidEntryPoint
class ProtectionRestartReceiver : BroadcastReceiver() {

    @Inject
    lateinit var settings: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (settings.getSettingsSnapshot().vpnEnabled) {
                    ShieldVpnService.launch(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /**
         * MY_PACKAGE_REPLACED is delivered to the app itself.
         *
         * QUICKBOOT_POWERON is not part of the platform API - HTC's boot broadcast never became
         * standard, so a stock filter for it is silently ignored on real devices. Declaring it
         * alongside BOOT_COMPLETED is harmless where unused and covers the hardware that needs it,
         * whereas registering it dynamically would require surviving long enough to register.
         */
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}