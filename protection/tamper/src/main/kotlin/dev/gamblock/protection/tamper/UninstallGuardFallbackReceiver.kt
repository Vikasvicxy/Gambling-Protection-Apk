package dev.gamblock.protection.tamper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Fallback notice for when the accessibility service is switched off underneath us.
 *
 * ## Why this exists
 *
 * The uninstall guard is an AccessibilityService, and the platform offers no
 * callback when one is disabled at runtime: the service is simply unbound and
 * `onUnbind` fires, with no way to distinguish "the user turned it off" from "the
 * system killed the process". The result is that the guard can silently stop
 * protecting the app while the settings screen still reports it as enabled, which
 * is the worst possible failure mode for a feature whose entire job is to not
 * quietly stop working.
 *
 * ## What this does and does not do
 *
 * It cannot re-enable the service. No public API can, and anything that claimed to
 * would be lying. Instead it raises a single notification pointing at the system
 * Accessibility settings so the user can restore the guard themselves.
 *
 * ## Deliberately conservative triggering
 *
 * The receiver only fires on explicit, unambiguous lifecycle events. It does not
 * poll on a timer: a periodic nag about a service the user deliberately disabled
 * is harassment, and the user is the one who decided. It also does not post on every
 * package event, only when the guard was believed to be active.
 *
 * The broadcast itself is delivered by the system to an unexported receiver, so
 * there is no third-party spoofing surface here.
 */
@AndroidEntryPoint
class UninstallGuardFallbackReceiver : BroadcastReceiver() {

    @Inject lateinit var fallbackNotifier: UninstallGuardFallbackNotifier

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in VALID_ACTIONS) return
        fallbackNotifier.notifyIfGuardExpected(
            context = context.applicationContext,
            trigger = action,
        )
    }

    companion object {
        /**
         * Events after which the guard may have silently stopped.
         *
         * [Intent.ACTION_MY_PACKAGE_REPLACED] covers an app update: the process dies
         * and comes back, and a service that was enabled can come back disabled
         * depending on how the update was installed. [Intent.ACTION_BOOT_COMPLETED]
         * covers reboot, where the same can happen on some OEM builds.
         */
        val VALID_ACTIONS = setOf(
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
        )
    }
}