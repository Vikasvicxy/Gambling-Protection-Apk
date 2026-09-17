package dev.gamblock.core.model

import kotlinx.serialization.Serializable

/** Overall health states for protection. */
enum class HealthStatus {
    HEALTHY,
    DEGRADED,
    CRITICAL,
    UNKNOWN,
}

/** Individual tracked components of protection health. */
enum class HealthComponent {
    VPN,
    DNS,
    BLOCKLIST,
    BOOT,
    BATTERY,
    PERMISSION,
    NETWORK,
    COMMITMENT,
    UPDATE,
    DATABASE,
}

@Serializable
data class ComponentHealth(
    val component: HealthComponent,
    val status: HealthStatus,
    val message: String,
    val measuredAtEpochMs: Long,
    val detail: Map<String, String> = emptyMap(),
)

@Serializable
data class HealthReport(
    val overall: HealthStatus,
    val components: List<ComponentHealth>,
    val measuredAtEpochMs: Long,
) {
    fun component(component: HealthComponent): ComponentHealth? =
        components.firstOrNull { it.component == component }

    companion object {
        fun aggregate(components: List<ComponentHealth>, measuredAt: Long): HealthReport {
            val overall = when {
                components.isEmpty() -> HealthStatus.UNKNOWN
                components.any { it.status == HealthStatus.CRITICAL } -> HealthStatus.CRITICAL
                components.any { it.status == HealthStatus.DEGRADED } -> HealthStatus.DEGRADED
                components.any { it.status == HealthStatus.UNKNOWN } -> HealthStatus.UNKNOWN
                else -> HealthStatus.HEALTHY
            }
            return HealthReport(
                overall = overall,
                components = components.sortedBy { it.component.ordinal },
                measuredAtEpochMs = measuredAt,
            )
        }
    }
}