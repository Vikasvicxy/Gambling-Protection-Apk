package dev.gamblock.protection.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamblock.core.model.VpnRuntimeState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the persistent foreground-service notification: a clean, described channel
 * (Android 13+ POST_NOTIFICATIONS compliant) plus a live summary that is rebuilt
 * with throttled query/block counts and one-tap actions.
 */
@Singleton
class VpnNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val manager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_vpn_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_vpn_desc)
                setShowBadge(false)
            },
        )
    }

    fun buildLive(state: VpnRuntimeState): Notification {
        val summary = "Filtering locally · ${state.queriesHandled} queries · ${state.queriesBlocked} blocked"
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_vpn_title))
            .setContentText(summary)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Queries handled: ${state.queriesHandled}\n" +
                        "Queries blocked: ${state.queriesBlocked}\n" +
                        "Queries allowed: ${state.queriesAllowed}\n" +
                        "Bypass exceptions applied: ${state.exceptionsApplied}",
                ),
            )
            .setSmallIcon(R.drawable.ic_stat_shield_vpn)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(viewActivityPendingIntent())
            .addAction(0, context.getString(R.string.notif_action_activity), viewActivityPendingIntent())
            .addAction(0, context.getString(R.string.notif_action_pause), pausePendingIntent())
            .build()
    }

    fun update(state: VpnRuntimeState) {
        try {
            manager.notify(NOTIFICATION_ID, buildLive(state))
        } catch (t: Throwable) {
            // Notification updates are cosmetic; never let them kill the tunnel.
        }
    }

    private fun viewActivityPendingIntent(): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        launch?.putExtra(EXTRA_NAV_DESTINATION, DESTINATION_REPORTS)
        launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun pausePendingIntent(): PendingIntent {
        val pause = Intent(context, ShieldVpnService::class.java)
            .setAction(ShieldVpnService.ACTION_STOP)
        return PendingIntent.getService(
            context,
            1,
            pause,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val CHANNEL_ID = "shield_vpn"
        const val NOTIFICATION_ID = 1
        const val EXTRA_NAV_DESTINATION = "dev.gamblock.shield.extra.NAV_DESTINATION"
        const val DESTINATION_REPORTS = "reports"
        const val UPDATE_THROTTLE_MS = 4_000L
    }
}