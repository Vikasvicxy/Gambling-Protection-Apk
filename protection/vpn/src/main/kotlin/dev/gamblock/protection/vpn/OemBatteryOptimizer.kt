package dev.gamblock.protection.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the OS is currently allowed to kill this app, and how to ask it not to.
 *
 * Protection here is a foreground VPN service, which stock Android already exempts from battery
 * optimisation. Several large Android skins do not honour that, and when they do not the symptom
 * is the worst kind: protection silently stops after the user switches away from the app, and
 * nothing on screen says so. There is no way to detect that from inside the app other than by
 * noticing the service died, which is why the diagnostics card surfaces the exemption state
 * instead of silently assuming it.
 *
 * Nothing here is required for correctness on stock devices, and every step degrades to a no-op
 * when the OEM surface is absent.
 */
@Singleton
class OemBatteryOptimizer @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val powerManager: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    /** The OEM skin in use, for display and for choosing a target screen. */
    val vendor: OemBatteryPolicies.Vendor
        get() = OemBatteryPolicies.vendorFor(Build.MANUFACTURER)

    /** True when the OS will not kill this app for battery reasons. */
    fun isExempt(): Boolean {
        val pm = powerManager ?: return true
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)
    }

    /** True when this skin needs the user to also allow auto-start, which has no public API. */
    fun needsManualAutostartStep(): Boolean = vendor.needsManualAutostartStep

    /**
     * The best screen we can actually open, most specific first.
     *
     * Returns null when nothing resolves, which the UI renders as "open Settings yourself" rather
     * than as a broken button.
     */
    fun batterySettingsIntent(): Intent? {
        val candidates = mutableListOf<Intent>()
        // A dedicated OEM screen is more likely to be the one the user actually needs than the
        // generic list, so those are tried first. Each is a best-effort target: these activity
        // names come from OEM system UI packages and move between firmware versions.
        for (component in OemBatteryPolicies.dedicatedComponentsFor(vendor)) {
            candidates.add(Intent().setComponent(ComponentName.unflattenFromString(component)))
        }
        candidates.add(
            Intent(OemBatteryPolicies.ACTION_REQUEST_IGNORE_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            },
        )
        candidates.add(Intent(OemBatteryPolicies.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        return candidates.firstOrNull { resolves(it) }
    }

    /**
     * The read-only exemption list, which always exists on stock and needs no permission.
     *
     * Used as the fallback when [batterySettingsIntent] finds nothing, and as the link in the
     * card's explanatory text.
     */
    fun genericListIntent(): Intent = Intent(OemBatteryPolicies.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    private fun resolves(intent: Intent): Boolean = runCatching {
        // FLAG_ACTIVITY_NEW_TASK because these are fired from a non-activity context.
        val probe = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.packageManager.resolveActivity(probe, 0) != null
    }.getOrDefault(false)
}