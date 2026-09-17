package dev.gamblock.core.model

import kotlinx.serialization.Serializable

/**
 * Phase 3 notification models.
 *
 * A clean notification abstraction so Android local notifications, FCM push, and future
 * E2E delivery can share one policy layer. Payloads carry MINIMAL data; protected
 * details are fetched behind authentication where appropriate. E2E encryption is NOT
 * claimed unless actually implemented and tested.
 */

/** High-level notification channels visible to users. */
enum class ShieldNotificationChannel(val id: String) {
    PROTECTION_ALERTS("protection_alerts"),
    HEARTBEAT_ALERTS("heartbeat_alerts"),
    REPORT_UPDATES("report_updates"),
    ACCOUNT_AND_SETUP("account_setup"),
    PARENT_UPDATES("parent_updates"),
}

/** The single message a partner/parent receives. Never a raw URL dump. */
@Serializable
data class ShieldNotification(
    val id: String,
    val channel: ShieldNotificationChannel,
    val title: String,
    val body: String,
    /** Optional server-issued, authenticated detail id to fetch protected content. */
    val detailRef: String? = null,
    val eventType: AccountabilityEventType? = null,
    val severity: AccountabilitySeverity = AccountabilitySeverity.LOW,
    val createdAtEpochMs: Long,
    /** Opaque device target (pseudonymous). */
    val targetDeviceId: String,
) {
    companion object {
        const val DEFAULT_BLOCK_MESSAGE = "Gambling access attempt was blocked.\nProtection remains active."
        const val REPEATED_BLOCK_MESSAGE = "Repeated gambling access attempts were blocked."
    }
}

/** Preferences governing what a partner/parent receives (privacy opt-ins). */
@Serializable
data class NotificationPolicy(
    /** Include category aggregates (allowed by default). */
    val includeCategory: Boolean = true,
    /** Include the exact domain (opt-in explicitly; default off). */
    val includeExactDomain: Boolean = false,
    /** Cooldown window before a group may emit again. */
    val cooldownMillis: Long = 60_000L,
    /** Minimum severity worth notifying (LOW default). */
    val minimumSeverity: AccountabilitySeverity = AccountabilitySeverity.LOW,
)

/** Default text mapping per event type, used for the "default notification" requirement. */
object NotificationDefaults {
    fun titleFor(type: AccountabilityEventType): String = when (type) {
        AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED,
        AccountabilityEventType.REPEATED_GAMBLING_ATTEMPTS_BLOCKED,
        AccountabilityEventType.GAMBLING_APP_ATTEMPT_BLOCKED,
        ->
            "Blocked attempt"
        AccountabilityEventType.VPN_UNEXPECTEDLY_INACTIVE -> "Protection may be off"
        AccountabilityEventType.PROTECTION_DEGRADED -> "Protection degraded"
        AccountabilityEventType.REQUIRED_PERMISSION_REMOVED -> "Permission removed"
        AccountabilityEventType.HEARTBEAT_LOST ->
            "Protection status could not be confirmed"
        AccountabilityEventType.TAMPER_EVIDENCE_GENERATED -> "Tamper evidence"
        AccountabilityEventType.PROTECTED_DEVICE_REPLACED -> "Protected device replaced"
        AccountabilityEventType.PROTECTION_CONFIRMED_ACTIVE -> "Protection confirmed"
    }

    fun bodyFor(type: AccountabilityEventType, grouped: Boolean): String = when (type) {
        AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED ->
            if (grouped) REPEATED_BLOCK_MESSAGE else DEFAULT_BLOCK_MESSAGE
        AccountabilityEventType.REPEATED_GAMBLING_ATTEMPTS_BLOCKED ->
            REPEATED_BLOCK_MESSAGE
        AccountabilityEventType.GAMBLING_APP_ATTEMPT_BLOCKED ->
            "A gambling application was blocked.\nProtection remains active."
        AccountabilityEventType.VPN_UNEXPECTEDLY_INACTIVE ->
            "Protection may have stopped. Check the protected device."
        AccountabilityEventType.PROTECTION_DEGRADED ->
            "Protection state degraded. Review health on the protected device."
        AccountabilityEventType.REQUIRED_PERMISSION_REMOVED ->
            "A permission required for protection was removed."
        AccountabilityEventType.HEARTBEAT_LOST ->
            HeartbeatEvaluation.HEARTBEAT_LOST_DEFAULT_MESSAGE
        AccountabilityEventType.TAMPER_EVIDENCE_GENERATED ->
            "Tamper evidence was generated on the protected device."
        AccountabilityEventType.PROTECTED_DEVICE_REPLACED ->
            "The protected device was replaced under partner confirmation."
        AccountabilityEventType.PROTECTION_CONFIRMED_ACTIVE ->
            "Protection is active again."
    }

    /** Exact default notification text (Play policy / spec-mandated). */
    const val DEFAULT_BLOCK_MESSAGE: String = "Gambling access attempt was blocked.\nProtection remains active."

    /** Repeated-attempt notification text (spec-mandated). */
    const val REPEATED_BLOCK_MESSAGE: String = "Repeated gambling access attempts were blocked."
}