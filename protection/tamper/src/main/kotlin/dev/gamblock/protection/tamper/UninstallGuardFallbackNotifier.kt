package dev.gamblock.protection.tamper

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.data.preferences.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Raises the "your uninstall guard switched itself off" notice.
 *
 * Split out of the receiver so the notify-or-stay-quiet decision can be asserted
 * without a running Android process, and so the notification plumbing lives in one
 * place.
 *
 * The decision to notify is deliberately narrow:
 *
 * - The user must have turned the guard on. Telling someone their guard is off when
 *   they never enabled it is noise.
 * - The service must genuinely be unbound *now*, not merely idle. Reporting a
 *   problem that does not exist trains people to dismiss the real one.
 *
 * There is no polling and no retry loop. If the notice is not acted on, that is the
 * user's decision to make; nagging a guardian feature into compliance is exactly the
 * behaviour this app is trying not to have.
 */
@Singleton
class UninstallGuardFallbackNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val logger: ShieldLogger,
) {

    /**
     * Posts the fallback notice when, and only when, the guard was expected to be
     * active but the accessibility service is not currently enabled in system
     * Settings.
     *
     * @param trigger the broadcast action that prompted this check, for logs only.
     * @return true when a notification was posted.
     */
    fun notifyIfGuardExpected(context: Context, trigger: String): Boolean {
        val appContext = context.applicationContext
        return try {
            if (!shouldNotify(appContext)) return false
            deliver(appContext)
        } catch (error: Exception) {
            // A fallback that throws inside a BroadcastReceiver would crash the app
            // during boot, which is strictly worse than staying quiet.
            logger.w(TAG, "fallback notice failed after $trigger", error)
            false
        }
    }

    private fun shouldNotify(appContext: Context): Boolean {
        // Broadcast receivers get roughly ten seconds, so the settings read has to be
        // bounded. On timeout we stay silent: a missed notice is recoverable, a
        // blocked broadcast receiver is not.
        val guardEnabled = runBlocking {
            withTimeoutOrNull(READ_TIMEOUT_MS) {
                settingsRepository.settings.first().uninstallGuardEnabled
            }
        }
        // A timed-out or failed read is indistinguishable from "off" here, which
        // resolves to not notifying. That is the safe direction: never invent a
        // warning we cannot substantiate.
            ?: return false

        val policy = UninstallGuardFallbackPolicy.shouldNotify(
            guardEnabled = guardEnabled,
            serviceEnabledInSystem = ShieldAccessibilityGuard.isEnabled(appContext),
        )
        if (policy) logger.d(TAG, "guard is on but the service is unbound")
        return policy
    }

    @SuppressLint("MissingPermission")
    private fun deliver(appContext: Context): Boolean {
        // From Android 13 POST_NOTIFICATIONS is a runtime grant. Without it the
        // system drops the notification silently, so there is nothing to do.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // minSdk is 26, so channels always exist. Re-creating one is a no-op, which
        // makes this safe to repeat on every boot and update.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.uninstall_guard_fallback_channel),
                // DEFAULT, not HIGH: the guard being off is worth surfacing but it is
                // not an ongoing emergency, and a heads-up banner on every update
                // would be.
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = appContext.getString(
                    R.string.uninstall_guard_fallback_channel_description,
                )
            },
        )

        val settingsIntent = PendingIntent.getActivity(
            appContext,
            0,
            ShieldAccessibilityGuard.accessibilitySettingsIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val body = appContext.getString(R.string.uninstall_guard_fallback_body)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(appContext.getString(R.string.uninstall_guard_fallback_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(settingsIntent)
            .setAutoCancel(true)
            // A guard that has been off since before this update should not re-alert
            // on every single boot.
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
        return true
    }

    companion object {
        const val CHANNEL_ID = "shield_uninstall_guard_fallback"
        const val NOTIFICATION_ID = 4401
        private const val TAG = "UninstallGuardFallback"
        private const val READ_TIMEOUT_MS = 3_000L
    }
}

/**
 * The notify-or-stay-quiet decision, with no Android types so it can be asserted
 * directly.
 */
object UninstallGuardFallbackPolicy {

    /**
     * @param guardEnabled whether the user turned the uninstall guard on.
     * @param serviceEnabledInSystem whether the accessibility service is currently
     *   enabled according to system Settings.
     */
    fun shouldNotify(guardEnabled: Boolean, serviceEnabledInSystem: Boolean): Boolean =
        // Never nag about a guard the user never turned on.
        guardEnabled &&
            // Never report a problem that does not exist. A bound-but-idle service
            // still reads as enabled here, so this is only true when the platform has
            // genuinely unbound us.
            !serviceEnabledInSystem
}