package dev.gamblock.core.accountability

import dev.gamblock.core.model.HeartbeatConfig
import dev.gamblock.core.model.HeartbeatEvaluation
import dev.gamblock.core.model.HeartbeatState
import dev.gamblock.core.model.HeartbeatStatus

/**
 * Privacy-minimized heartbeat evaluation.
 *
 * Exposes only required state (device pseudonymous id, protection state, health, last
 * check-in, app version, database freshness). The engine answers one question: has
 * protection silently disappeared? It deliberately does NOT guess the cause - the LOST
 * message ("Protection status could not be confirmed.") covers offline, stopped, powered
 * off, removed, permission removed.
 */
class HeartbeatEngine(
    private val clockMillis: () -> Long,
    private val config: HeartbeatConfig = HeartbeatConfig(),
) {

    fun evaluate(lastHeartbeat: HeartbeatState?): HeartbeatEvaluation {
        val now = clockMillis()
        if (lastHeartbeat == null) {
            return HeartbeatEvaluation(
                status = HeartbeatStatus.NEVER,
                lastCheckInEpochMs = null,
                nowEpochMs = now,
                ageMillis = 0L,
            )
        }
        val age = (now - lastHeartbeat.lastCheckInEpochMs).coerceAtLeast(0L)
        val status = when {
            age <= config.intervalMillis -> HeartbeatStatus.RECENT
            age <= config.lostThresholdMillis -> HeartbeatStatus.UNKNOWN_WITHIN_THRESHOLD
            else -> HeartbeatStatus.LOST
        }
        return HeartbeatEvaluation(
            status = status,
            lastCheckInEpochMs = lastHeartbeat.lastCheckInEpochMs,
            nowEpochMs = now,
            ageMillis = age,
        )
    }

    fun shouldNotifyLost(lastEvaluation: HeartbeatStatus, now: HeartbeatStatus): Boolean {
        // Emit once when transitioning into LOST (or NEVER), not repeatedly.
        return now == HeartbeatStatus.LOST && lastEvaluation != HeartbeatStatus.LOST
    }

    /** Whether a new heartbeat supersedes a previously lost state. */
    fun isRecoveryFromLost(newHeartbeat: HeartbeatState, lastEvaluation: HeartbeatStatus): Boolean =
        lastEvaluation == HeartbeatStatus.LOST && newHeartbeat.lastCheckInEpochMs > 0L

    /** A heartbeat may be accepted only if its sequence is strictly newer. */
    fun isReplay(previous: HeartbeatState?, incoming: HeartbeatState): Boolean {
        if (previous == null) return false
        return incoming.heartbeatSequence <= previous.heartbeatSequence
    }
}

/** Snapshot policy: heartbeat fields a partner may receive. */
object HeartbeatPrivacy {
    /** Fields that leave the protected device in a heartbeat. Deliberately minimal. */
    val TRANSMITTED_FIELDS = setOf(
        "deviceId",
        "protectionState",
        "health",
        "lastCheckInEpochMs",
        "appVersion",
        "databaseVersion",
        "databaseFreshnessEpochMs",
    )
}