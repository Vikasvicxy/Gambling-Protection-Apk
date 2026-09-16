package dev.gamblock.protection.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives [Intent.ACTION_MY_PACKAGE_REPLACED] and delegates to [BootReceiver].
 * The manifest already declares it exported=false; the package-manager only
 * sends it to our own process, so no permission is needed.
 */
class ProtectionRecoveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            BootReceiver.enqueueRecovery(context)
        }
    }
}