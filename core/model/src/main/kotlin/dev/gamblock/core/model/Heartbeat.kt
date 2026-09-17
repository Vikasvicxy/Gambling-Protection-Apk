package dev.gamblock.core.model

import kotlinx.serialization.Serializable

/**
 * Phase 3 Protection Heartbeat models.
 *
 * The heartbeat is a privacy-minimized check-in that lets a trusted partner or parent
 * determine whether protection has silently disappeared, WITHOUT transmitting browsing
 * history, messages, location or unrelated activity.
 */

/** Effective protection state reported by a protected device. */
enum class ProtectionState {
    ACTIVE,
    DEGRADED,
    INACTIVE,
    UNKNOWN,
}

/**
 * Server-side heartbeat state for one protected device, keyed by pseudonymous install id.
 * `lastCheckInEpochMs` is authoritative for "silently disappeared" detection.
 */
@Serializable
data class HeartbeatState(
    val deviceId: String,
    val protectionState: ProtectionState,
    val health: HealthStatus,
    val lastCheckInEpochMs: Long,
    val appVersion: String,
    val databaseVersion: Int,
    val databaseFreshnessEpochMs: Long? = null,
    val heartbeatSequence: Long = 0L,
) {
    companion object {
        const val DATABASE_FRESHNESS_UNKNOWN: Long = -1L
    }
}

/** Configurable heartbeat cadence + silence threshold. */
@Serializable
data class HeartbeatConfig(
    /** Desired check-in interval. */
    val intervalMillis: Long = 6 * 60 * 60 * 1000L,
    /**
     * Threshold after which a partner is told "Protection status could not be confirmed."
     * Deliberately message-agostic about the cause (offline, stopped, off, removed...).
     */
    val lostThresholdMillis: Long = 24 * 60 * 60 * 1000L,
    /** If true the protected device skips heartbeat transmission (privacy per user choice). */
    val heartbeatEnabled: Boolean = true,
) {
    init {
        require(intervalMillis in 1L..(60L * 24 * 60 * 60 * 1000L)) {
            "heartbeat interval out of range"
        }
        require(lostThresholdMillis >= intervalMillis) {
            "lost threshold must be >= interval"
        }
    }
}

/** Outcome of evaluating a stored heartbeat against the clock. */
@Serializable
enum class HeartbeatStatus {
    /** Recent check-in; protection recently seen. */
    RECENT,
    /** Some time since check-in but inside threshold. */
    UNKNOWN_WITHIN_THRESHOLD,
    /** Check-in is beyond lost threshold. */
    LOST,
    /** No check-in recorded at all. */
    NEVER,
}

/** Result of evaluating whether a heartbeat is lost, with reason labels. */
@Serializable
data class HeartbeatEvaluation(
    val status: HeartbeatStatus,
    val lastCheckInEpochMs: Long?,
    val nowEpochMs: Long,
    val ageMillis: Long,
) {
    val message: String
        get() = when (status) {
            HeartbeatStatus.RECENT -> "Protection confirmed recently."
            HeartbeatStatus.UNKNOWN_WITHIN_THRESHOLD -> "Protection status not yet confirmed."
            HeartbeatStatus.LOST -> "Protection status could not be confirmed."
            HeartbeatStatus.NEVER -> "No protection check-in has been received."
        }

    companion object {
        /** The one notification copy a partner receives on threshold breach. */
        const val HEARTBEAT_LOST_DEFAULT_MESSAGE = "Protection status could not be confirmed."
    }
}

/** Parsed, trusted minima about a device reported only via heartbeat. */
@Serializable
data class DeviceHeartbeatSnapshot(
    val deviceId: String,
    val protectionState: ProtectionState,
    val health: HealthStatus,
    val databaseVersion: Int,
    val appVersion: String,
)