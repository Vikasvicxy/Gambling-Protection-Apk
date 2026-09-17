package dev.gamblock.data.accountability.notification

import dev.gamblock.core.model.AuditLogEntry
import dev.gamblock.core.model.ShieldNotification

/**
 * Clean push/local notification abstraction.
 *
 * Implementations:
 *  - [LocalNotificationGateway] renders notifications on the same device.
 *  - An FCM-backed gateway can be added later (push payloads must stay minimal; protected
 *    details fetched behind auth via [ShieldNotification.detailRef]).
 *
 * Tokens for push are stored safely and rotated/removed via TokenRegistry interfaces.
 * End-to-end encryption is NOT claimed until a genuine device-to-device E2E layer is
 * implemented and tested.
 */
interface NotificationGateway {
    /** Deliver a minimal notification to the target. Implementations may suppress UI. */
    fun deliver(notification: ShieldNotification): Boolean

    /** Whether this gateway is available/authorized to deliver. */
    val available: Boolean
}

/** Push token storage + rotation/removal. */
interface NotificationTokenStore {
    suspend fun currentToken(): String?
    suspend fun rotate(token: String)
    suspend fun remove()
}

class NoOpNotificationGateway : NotificationGateway {
    override fun deliver(notification: ShieldNotification): Boolean = true
    override val available: Boolean = false
}