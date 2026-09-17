package dev.gamblock.core.accountability

import dev.gamblock.core.model.AccountabilityEvent
import dev.gamblock.core.model.AccountabilityEventType
import dev.gamblock.core.model.AccountabilitySeverity
import dev.gamblock.core.model.Category
import dev.gamblock.core.model.EventGroupingState
import dev.gamblock.core.model.EventGroupResult
import dev.gamblock.core.model.GroupingConfig

/**
 * Event grouping / notification cooldown.
 *
 * A browser retrying the same blocked request 50x in a second must collapse to ONE
 * accountability event. Group keys are stable across (device, type, category,
 * optional-domain) and a cooldown window bounds how often a group may emit.
 *
 * Severity mapping (examples):
 *   single attempt                 -> LOW
 *   repeated attempts              -> MEDIUM
 *   VPN disabled                   -> HIGH
 *   protection heartbeat lost      -> CRITICAL
 */
class EventGrouper(
    private val config: GroupingConfig = GroupingConfig(),
    private val clock: () -> Long,
    private val severityCalculator: SeverityCalculator = SeverityCalculator(),
) {

    /** Key used to collapse a burst of identical occurrences. */
    fun groupKey(deviceId: String, type: AccountabilityEventType, category: Category?): String =
        buildString {
            append(deviceId)
            append('|')
            append(type.name)
            append('|')
            append(category?.name ?: "-")
        }

    /**
     * Incorporate an occurrence into grouping state. Returns Emitted only when the
     * cooldown has passed since the last emission for this group.
     */
    fun aggregate(
        state: EventGroupingState?,
        deviceId: String,
        type: AccountabilityEventType,
        category: Category?,
        nowEpochMs: Long,
    ): EventGroupResult {
        val key = groupKey(deviceId, type, category)
        return if (state == null) {
            // First occurrence: emit immediately (single attempt => LOW).
            val fresh = EventGroupingState(
                groupKey = key,
                firstOccurredAtEpochMs = nowEpochMs,
                lastOccurredAtEpochMs = nowEpochMs,
                count = 1,
                eventEmitted = true,
            )
            val event = buildEvent(deviceId, type, category, fresh.count, nowEpochMs)
            EventGroupResult.Emitted(fresh, event)
        } else {
            val cooldownElapsed = nowEpochMs - state.lastOccurredAtEpochMs >= config.cooldownWindowMillis
            if (cooldownElapsed) {
                // Cooldown passed: a new group begins; suppress this single occurrence.
                val fresh = EventGroupingState(
                    groupKey = key,
                    firstOccurredAtEpochMs = nowEpochMs,
                    lastOccurredAtEpochMs = nowEpochMs,
                    count = 1,
                    eventEmitted = true,
                )
                EventGroupResult.ResetThenSuppressed(fresh)
            } else {
                val updated = state.copy(
                    lastOccurredAtEpochMs = nowEpochMs,
                    count = state.count + 1,
                    eventEmitted = state.eventEmitted,
                )
                if (updated.count <= config.repeatedThreshold) {
                    // 10 repeated requests collapse first into REPEATED_* event.
                    if (updated.count == config.repeatedThreshold) {
                        val repeatedEvent = buildEvent(
                            deviceId,
                            AccountabilityEventType.REPEATED_GAMBLING_ATTEMPTS_BLOCKED,
                            category,
                            updated.count,
                            nowEpochMs,
                        )
                        EventGroupResult.Emitted(updated, repeatedEvent)
                    } else {
                        EventGroupResult.Suppressed(updated)
                    }
                } else {
                    EventGroupResult.Suppressed(updated)
                }
            }
        }
    }

    private fun buildEvent(
        deviceId: String,
        type: AccountabilityEventType,
        category: Category?,
        count: Int,
        nowEpochMs: Long,
    ): AccountabilityEvent {
        val severity = severityCalculator.forEvent(type, count)
        return AccountabilityEvent(
            id = "evt-${nowEpochMs}-${deviceId.hashCode()}",
            type = type,
            severity = severity,
            occurredAtEpochMs = nowEpochMs,
            deviceId = deviceId,
            category = category,
            count = count,
        )
    }
}

/** Maps event types and counts to severity levels. */
class SeverityCalculator {
    fun forEvent(type: AccountabilityEventType, count: Int): AccountabilitySeverity = when (type) {
        AccountabilityEventType.GAMBLING_APP_ATTEMPT_BLOCKED -> AccountabilitySeverity.MEDIUM
        AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED -> if (count > 1) AccountabilitySeverity.MEDIUM else AccountabilitySeverity.LOW
        AccountabilityEventType.REPEATED_GAMBLING_ATTEMPTS_BLOCKED -> AccountabilitySeverity.MEDIUM
        AccountabilityEventType.VPN_UNEXPECTEDLY_INACTIVE -> AccountabilitySeverity.HIGH
        AccountabilityEventType.PROTECTION_DEGRADED -> AccountabilitySeverity.HIGH
        AccountabilityEventType.REQUIRED_PERMISSION_REMOVED -> AccountabilitySeverity.HIGH
        AccountabilityEventType.HEARTBEAT_LOST -> AccountabilitySeverity.CRITICAL
        AccountabilityEventType.TAMPER_EVIDENCE_GENERATED -> AccountabilitySeverity.CRITICAL
        AccountabilityEventType.PROTECTED_DEVICE_REPLACED -> AccountabilitySeverity.MEDIUM
        AccountabilityEventType.PROTECTION_CONFIRMED_ACTIVE -> AccountabilitySeverity.LOW
    }

    fun needsImmediatePrompt(type: AccountabilityEventType): Boolean =
        type == AccountabilityEventType.HEARTBEAT_LOST ||
            type == AccountabilityEventType.TAMPER_EVIDENCE_GENERATED ||
            type == AccountabilityEventType.VPN_UNEXPECTEDLY_INACTIVE
}