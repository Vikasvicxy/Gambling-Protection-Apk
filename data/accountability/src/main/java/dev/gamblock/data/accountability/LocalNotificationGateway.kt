package dev.gamblock.data.accountability

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.gamblock.core.model.ShieldNotification
import dev.gamblock.data.accountability.notification.NotificationGateway
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local notification rendering for in-app events. Uses the app's POST_NOTIFICATIONS
 * permission; channel names stay user-readable. No sensitive data (URLs) is rendered in
 * the body by default.
 */
@Singleton
class LocalNotificationGateway @Inject constructor(
    private val context: Context,
) : NotificationGateway {

    override val available: Boolean = true

    override fun deliver(notification: ShieldNotification): Boolean {
        return try {
            val channelId = notification.channel.id
            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_stat_shield)
                .setContentTitle(notification.title)
                .setContentText(notification.body)
                .setAutoCancel(true)
                .setPriority(priorityFor(notification))
            NotificationManagerCompat.from(context).notify(notification.id.hashCode(), builder.build())
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun priorityFor(notification: ShieldNotification): Int = when {
        notification.severity.rank >= 3 -> NotificationCompat.PRIORITY_HIGH
        notification.severity.rank >= 2 -> NotificationCompat.PRIORITY_HIGH
        else -> NotificationCompat.PRIORITY_DEFAULT
    }
}