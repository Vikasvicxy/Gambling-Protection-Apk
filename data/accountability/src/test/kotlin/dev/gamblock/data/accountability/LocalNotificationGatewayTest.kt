package dev.gamblock.data.accountability

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.model.AccountabilitySeverity
import dev.gamblock.core.model.ShieldNotification
import dev.gamblock.core.model.ShieldNotificationChannel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * POST_NOTIFICATIONS became a runtime grant in Android 13. Without an explicit
 * check, [LocalNotificationGateway.deliver] reported success while the system
 * silently dropped every notification, so the reminders simply never appeared.
 */
@RunWith(RobolectricTestRunner::class)
class LocalNotificationGatewayTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun notification() = ShieldNotification(
        id = "n1",
        channel = ShieldNotificationChannel.PROTECTION_ALERTS,
        title = "Protection is on",
        body = "No threats blocked yet.",
        severity = AccountabilitySeverity.LOW,
        createdAtEpochMs = 1_000L,
        targetDeviceId = "device-1",
    )

    private fun setNotificationPermission(granted: Boolean) {
        if (granted) {
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @Test
    @Config(sdk = [33])
    fun deliverReportsFailureWhenPermissionIsDenied() {
        setNotificationPermission(granted = false)

        val delivered = LocalNotificationGateway(context).deliver(notification())

        assertThat(delivered).isFalse()
    }

    @Test
    @Config(sdk = [33])
    fun deliverPostsWhenPermissionIsGranted() {
        setNotificationPermission(granted = true)

        val delivered = LocalNotificationGateway(context).deliver(notification())

        assertThat(delivered).isTrue()
    }

    @Test
    @Config(sdk = [32])
    fun deliverStillWorksBeforeThePermissionExisted() {
        // Below Android 13 the permission does not exist, so the SDK guard must
        // let the notification through without a runtime grant.
        val delivered = LocalNotificationGateway(context).deliver(notification())

        assertThat(delivered).isTrue()
    }
}
