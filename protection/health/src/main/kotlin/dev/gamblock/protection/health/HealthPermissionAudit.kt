package dev.gamblock.protection.health

import dev.gamblock.core.model.ComponentHealth
import dev.gamblock.core.model.HealthComponent
import dev.gamblock.core.model.HealthStatus

/**
 * Pure audit of the PERMISSION health component.
 *
 * Only permissions the app actually declares/uses can degrade this component. The
 * app uses no system overlay, so overlay-grant is never a requirement here.
 */
object HealthPermissionAudit {

    fun permissionHealth(
        notificationsEnabled: Boolean,
        batteryOptimizationExempt: Boolean,
        nowEpochMs: Long,
    ): ComponentHealth {
        val issues = mutableListOf<String>()
        if (!notificationsEnabled) issues.add("notifications blocked")
        if (!batteryOptimizationExempt) issues.add("battery optimization active")
        return ComponentHealth(
            HealthComponent.PERMISSION,
            if (issues.isEmpty()) HealthStatus.HEALTHY else HealthStatus.DEGRADED,
            if (issues.isEmpty()) "all relevant permissions granted" else issues.joinToString("; "),
            nowEpochMs,
        )
    }
}