package dev.gamblock.core.accountability

import dev.gamblock.core.model.AccountabilityEvent
import dev.gamblock.core.model.AccountabilitySeverity
import dev.gamblock.core.model.NotificationDefaults
import dev.gamblock.core.model.NotificationPolicy
import dev.gamblock.core.model.ShieldNotification
import dev.gamblock.core.model.ShieldNotificationChannel

/**
 * Builds the notification a partner/parent actually sees, from a minimal event.
 *
 * Privacy rules:
 *  - Exact domain is included ONLY when [NotificationPolicy.includeExactDomain] is set
 *    (explicit separate opt-in).
 *  - Category aggregates are included by default; body is always the safe default text.
 *  - Events below [NotificationPolicy.minimumSeverity] are suppressed.
 */
class NotificationPolicyEngine(
    private val policy: NotificationPolicy = NotificationPolicy(),
) {

    sealed interface BuildResult {
        data class Notify(val notification: ShieldNotification) : BuildResult
        data class Suppressed(val reason: String) : BuildResult
    }

    fun build(
        event: AccountabilityEvent,
        relationshipId: String,
        nowEpochMs: Long,
    ): BuildResult {
        if (event.severity.rank < policy.minimumSeverity.rank) {
            return BuildResult.Suppressed("severity below minimum")
        }

        val title = NotificationDefaults.titleFor(event.type)
        val body = NotificationDefaults.bodyFor(event.type, grouped = event.count > 1)

        val detail = mutableMapOf<String, String>()
        val category = event.category
        if (policy.includeCategory && category != null) {
            detail["category"] = category.name
        }
        val exactDomain = event.exactDomain
        if (policy.includeExactDomain && exactDomain != null) {
            // Explicit opt-in only.
            detail["domain"] = exactDomain
        }

        return BuildResult.Notify(
            ShieldNotification(
                id = "n-${nowEpochMs}-${relationshipId.hashCode()}",
                channel = channelFor(event.severity),
                title = title,
                body = body,
                detailRef = if (detail.isNotEmpty()) event.id else null,
                eventType = event.type,
                severity = event.severity,
                createdAtEpochMs = nowEpochMs,
                targetDeviceId = event.deviceId,
            ),
        )
    }

    companion object {
        fun channelFor(severity: AccountabilitySeverity): ShieldNotificationChannel =
            when {
                severity == AccountabilitySeverity.CRITICAL -> ShieldNotificationChannel.HEARTBEAT_ALERTS
                severity.rank >= AccountabilitySeverity.HIGH.rank -> ShieldNotificationChannel.PROTECTION_ALERTS
                else -> ShieldNotificationChannel.PROTECTION_ALERTS
            }
    }
}