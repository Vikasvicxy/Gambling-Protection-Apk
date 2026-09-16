package dev.gamblock.core.model

import kotlinx.serialization.Serializable

/** Normalized OEM identification so guidance can be fetched/adapted without hard-coding. */
@Serializable
data class OemInfo(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val androidSdk: Int,
    val androidRelease: String,
    val kind: OemKind,
)

enum class OemKind {
    SAMSUNG,
    PIXEL,
    XIAOMI,
    REDMI,
    POCO,
    ONEPLUS,
    OPPO,
    REALME,
    VIVO,
    MOTOROLA,
    NOTHING,
    HONOR,
    HUAWEI,
    FAIRPHONE,
    OTHER,
    EMULATOR,
}

@Serializable
data class BatteryStatusInfo(
    val isIgnoringBatteryOptimizations: Boolean,
    val isDeviceIdle: Boolean,
    val lastBootElapsedRealtimeMs: Long,
    val isCharging: Boolean,
)

/** One actionable piece of guidance for a device configuration. */
@Serializable
data class OemGuidanceItem(
    val id: String,
    val title: String,
    val body: String,
    val actionRoute: String? = null,
)

@Serializable
data class VpnConflictInfo(
    /** True when the currently active network uses the VPN transport (another app's VPN). */
    val activeNetworkUsesVpnTransport: Boolean,
    /** True when the default network is present at all. */
    val hasDefaultNetwork: Boolean,
    /** Names of transports observed on the current network. */
    val activeTransportNames: List<String>,
)