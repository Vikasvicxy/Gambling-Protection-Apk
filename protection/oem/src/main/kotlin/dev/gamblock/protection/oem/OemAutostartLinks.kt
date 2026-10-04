package dev.gamblock.protection.oem

import dev.gamblock.core.model.OemKind

/**
 * A candidate vendor autostart/battery screen. Kept as plain data (no `ComponentName`)
 * so the table stays pure Kotlin and unit-testable without Android framework stubs.
 */
data class OemAutostartTarget(
    val label: String,
    val packageName: String,
    val className: String,
)

/**
 * Vendor-specific autostart screens that aggressive battery managers use to kill a
 * foreground VPN. Android has no public API for these, so we probe a short ordered
 * list per [OemKind] and use the first one that actually resolves on the device.
 * A wrong guess is harmless: resolution failure just falls through to the next entry.
 */
object OemAutostartLinks {
    fun candidates(kind: OemKind): List<OemAutostartTarget> = when (kind) {
        OemKind.XIAOMI, OemKind.REDMI, OemKind.POCO -> listOf(
            OemAutostartTarget(
                "MIUI autostart",
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
        )
        OemKind.OPPO, OemKind.REALME, OemKind.ONEPLUS -> listOf(
            OemAutostartTarget(
                "Background activity manager",
                "com.oplus.battery",
                "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
            ),
            OemAutostartTarget(
                "App battery management",
                "com.coloros.oppoguardelf",
                "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
            ),
        )
        OemKind.VIVO -> listOf(
            OemAutostartTarget(
                "Background power management",
                "com.vivo.powermanager",
                "com.vivo.powermanager.activity.BgPowerManagerActivity",
            ),
            OemAutostartTarget(
                "iManager",
                "com.vivo.safe",
                "com.vivo.safe.ui.TelephonyFaultExperienceActivity",
            ),
        )
        OemKind.HUAWEI, OemKind.HONOR -> listOf(
            OemAutostartTarget(
                "App launch management",
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            OemAutostartTarget(
                "Protected apps",
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity",
            ),
        )
        OemKind.SAMSUNG -> listOf(
            OemAutostartTarget(
                "Battery usage",
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity",
            ),
        )
        OemKind.MOTOROLA -> listOf(
            OemAutostartTarget(
                "Battery optimisation",
                "com.motorola.launcher3",
                "com.motorola.launcher3.settings.BatteryOptimizationSettingsActivity",
            ),
        )
        OemKind.NOTHING -> listOf(
            OemAutostartTarget(
                "Battery settings",
                "com.nothing.sysui",
                "com.nothing.sysui.activity.BatterySettingsActivity",
            ),
        )
        OemKind.PIXEL, OemKind.FAIRPHONE, OemKind.OTHER,
        OemKind.EMULATOR,
        -> emptyList()
    }
}