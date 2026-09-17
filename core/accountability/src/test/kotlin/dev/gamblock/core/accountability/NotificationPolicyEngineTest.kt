package dev.gamblock.core.accountability

import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.model.AccountabilityEvent
import dev.gamblock.core.model.AccountabilityEventType
import dev.gamblock.core.model.AccountabilitySeverity
import dev.gamblock.core.model.Category
import dev.gamblock.core.model.NotificationPolicy
import org.junit.Test

class NotificationPolicyEngineTest {

    @Test
    fun `default block message is exactly the required default text`() {
        val engine = NotificationPolicyEngine(NotificationPolicy())
        val event = AccountabilityEvent(
            id = "e",
            type = AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED,
            severity = AccountabilitySeverity.LOW,
            occurredAtEpochMs = 1L,
            deviceId = "aa".repeat(16),
        )
        val result = engine.build(event, "rel-1", 1L)
        assertThat(result).isInstanceOf(NotificationPolicyEngine.BuildResult.Notify::class.java)
        val notification = (result as NotificationPolicyEngine.BuildResult.Notify).notification
        assertThat(notification.body).isEqualTo(
            "Gambling access attempt was blocked.\nProtection remains active.",
        )
        assertThat(notification.detailRef).isNull()
    }

    @Test
    fun `category is included by default`() {
        val engine = NotificationPolicyEngine(NotificationPolicy(includeCategory = true))
        val event = AccountabilityEvent(
            id = "e",
            type = AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED,
            severity = AccountabilitySeverity.LOW,
            occurredAtEpochMs = 1L,
            deviceId = "aa".repeat(16),
            category = Category.CASINO,
        )
        val result = engine.build(event, "rel-1", 1L)
        val notification = (result as NotificationPolicyEngine.BuildResult.Notify).notification
        assertThat(notification.detailRef).isEqualTo(event.id)
    }

    @Test
    fun `exact domain is NOT included by default`() {
        val engine = NotificationPolicyEngine(NotificationPolicy(includeExactDomain = false))
        val event = AccountabilityEvent(
            id = "e",
            type = AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED,
            severity = AccountabilitySeverity.LOW,
            occurredAtEpochMs = 1L,
            deviceId = "aa".repeat(16),
            exactDomain = "casino.example.com",
        )
        val result = engine.build(event, "rel-1", 1L)
        val notification = (result as NotificationPolicyEngine.BuildResult.Notify).notification
        // No exact domain unless explicitly opt-in.
        assertThat(notification.body).doesNotContain("casino.example.com")
    }

    @Test
    fun `exact domain included only when explicitly opted in`() {
        val engine = NotificationPolicyEngine(NotificationPolicy(includeExactDomain = true))
        val event = AccountabilityEvent(
            id = "e",
            type = AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED,
            severity = AccountabilitySeverity.LOW,
            occurredAtEpochMs = 1L,
            deviceId = "aa".repeat(16),
            exactDomain = "casino.example.com",
        )
        val result = engine.build(event, "rel-1", 1L)
        val notification = (result as NotificationPolicyEngine.BuildResult.Notify).notification
        assertThat(notification.detailRef).isEqualTo(event.id)
    }

    @Test
    fun `events below minimum severity are suppressed`() {
        val engine = NotificationPolicyEngine(NotificationPolicy(minimumSeverity = AccountabilitySeverity.HIGH))
        val low = AccountabilityEvent(
            id = "e",
            type = AccountabilityEventType.GAMBLING_ATTEMPT_BLOCKED,
            severity = AccountabilitySeverity.LOW,
            occurredAtEpochMs = 1L,
            deviceId = "aa".repeat(16),
        )
        assertThat(engine.build(low, "rel-1", 1L))
            .isInstanceOf(NotificationPolicyEngine.BuildResult.Suppressed::class.java)
    }

    @Test
    fun `critical heartbeat-lost maps to heartbeat channel`() {
        val engine = NotificationPolicyEngine(NotificationPolicy())
        val event = AccountabilityEvent(
            id = "e",
            type = AccountabilityEventType.HEARTBEAT_LOST,
            severity = AccountabilitySeverity.CRITICAL,
            occurredAtEpochMs = 1L,
            deviceId = "aa".repeat(16),
        )
        val result = engine.build(event, "rel-1", 1L)
        val notification = (result as NotificationPolicyEngine.BuildResult.Notify).notification
        assertThat(notification.body).isEqualTo("Protection status could not be confirmed.")
        assertThat(notification.channel).isEqualTo(dev.gamblock.core.model.ShieldNotificationChannel.HEARTBEAT_ALERTS)
    }

    @Test
    fun `tamper alert uses default body`() {
        val engine = NotificationPolicyEngine(NotificationPolicy())
        val event = AccountabilityEvent(
            id = "e",
            type = AccountabilityEventType.TAMPER_EVIDENCE_GENERATED,
            severity = AccountabilitySeverity.CRITICAL,
            occurredAtEpochMs = 1L,
            deviceId = "aa".repeat(16),
        )
        val result = engine.build(event, "rel-1", 1L)
        val notification = (result as NotificationPolicyEngine.BuildResult.Notify).notification
        assertThat(notification.body).contains("Tamper evidence")
    }
}