package dev.gamblock.protection.health

import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.model.HealthComponent
import dev.gamblock.core.model.HealthStatus
import org.junit.Test

class HealthPermissionTest {
    @Test
    fun `all granted reports healthy`() {
        val result = HealthPermissionAudit.permissionHealth(
            notificationsEnabled = true,
            batteryOptimizationExempt = true,
            nowEpochMs = 1_000L,
        )
        assertThat(result.component).isEqualTo(HealthComponent.PERMISSION)
        assertThat(result.status).isEqualTo(HealthStatus.HEALTHY)
        assertThat(result.message).isEqualTo("all relevant permissions granted")
    }

    @Test
    fun `notifications blocked reports degraded`() {
        val result = HealthPermissionAudit.permissionHealth(
            notificationsEnabled = false,
            batteryOptimizationExempt = true,
            nowEpochMs = 1_000L,
        )
        assertThat(result.status).isEqualTo(HealthStatus.DEGRADED)
        assertThat(result.message).contains("notifications blocked")
    }

    @Test
    fun `battery optimization active reports degraded`() {
        val result = HealthPermissionAudit.permissionHealth(
            notificationsEnabled = true,
            batteryOptimizationExempt = false,
            nowEpochMs = 1_000L,
        )
        assertThat(result.status).isEqualTo(HealthStatus.DEGRADED)
        assertThat(result.message).contains("battery optimization active")
    }

    @Test
    fun `both missing report both reasons`() {
        val result = HealthPermissionAudit.permissionHealth(
            notificationsEnabled = false,
            batteryOptimizationExempt = false,
            nowEpochMs = 1_000L,
        )
        assertThat(result.status).isEqualTo(HealthStatus.DEGRADED)
        assertThat(result.message).contains("notifications blocked")
        assertThat(result.message).contains("battery optimization active")
    }

    @Test
    fun `overlay permission is never required for health`() {
        val result = HealthPermissionAudit.permissionHealth(
            notificationsEnabled = true,
            batteryOptimizationExempt = true,
            nowEpochMs = 1_000L,
        )
        assertThat(result.message).doesNotContain("overlay")
    }
}
