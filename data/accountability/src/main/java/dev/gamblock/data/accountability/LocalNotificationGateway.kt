package dev.gamblock.data.accountability

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.gamblock.core.model.ShieldNotification
import dev.gamblock.data.accountability.notification.NotificationGateway
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local notification rendering for in-app events. Uses the app's POST_NOTIFICATIONS
 * permission; channel names stay user-readable. No sensitive data (URLs) is rendered in
 * the body by default.
 *
 * From Android 13 (API 33) the permission is a runtime grant, so delivery checks it
 * first and reports failure instead of posting a notification the system will drop.
 */
@Singleton
class LocalNotificationGateway @Inject constructor(
    private val context: Context,
) : NotificationGateway {

    override val available: Boolean = true

    /**
     * The POST_NOTIFICATIONS grant is checked inline immediately above the
     * [NotificationManagerCompat.notify] call, but lint cannot follow a check that
     * is itself gated on the API level, so the warning is suppressed deliberately
     * rather than left as a false positive. Do not add a notify() call to this
     * method without repeating the check.
     */
    @SuppressLint("MissingPermission")
    override fun deliver(notification: ShieldNotification): Boolean {
        return try {
            // From Android 13 the permission is a runtime grant, so it is checked
            // inline here: without it the system silently drops the notification.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
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