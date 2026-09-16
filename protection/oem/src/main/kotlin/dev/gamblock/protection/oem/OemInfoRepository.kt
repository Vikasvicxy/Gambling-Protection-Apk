package dev.gamblock.protection.oem

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.model.BatteryStatusInfo
import dev.gamblock.core.model.OemGuidanceItem
import dev.gamblock.core.model.OemInfo
import dev.gamblock.core.model.OemKind
import dev.gamblock.core.model.VpnConflictInfo
import javax.inject.Inject
import javax.inject.Singleton

/** Normalized device facts and OEM-specific guidance. No network involved. */
@Singleton
class OemInfoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: ShieldLogger,
) {
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    val oemInfo: OemInfo
        get() = OemInfo(
            manufacturer = normalize(Build.MANUFACTURER),
            brand = normalize(Build.BRAND),
            model = Build.MODEL ?: "unknown",
            androidSdk = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE ?: "",
            kind = detectKind(),
        )

    val batteryStatus: BatteryStatusInfo
        get() {
            val charging = readChargingState()
            return BatteryStatusInfo(
                isIgnoringBatteryOptimizations = try {
                    powerManager.isIgnoringBatteryOptimizations(context.packageName)
                } catch (_: Exception) {
                    false
                },
                isDeviceIdle = powerManager.isDeviceIdleMode,
                lastBootElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                isCharging = charging,
            )
        }

    /** OEM-specific guidance for keeping a local VPN alive. */
    val guidance: List<OemGuidanceItem>
        get() {
            val items = ArrayList<OemGuidanceItem>()
            when (oemInfo.kind) {
                OemKind.XIAOMI, OemKind.REDMI, OemKind.POCO -> items += OemGuidanceItem(
                    id = "oem.xiaomi",
                    title = "MIUI autostart background",
                    body = "Enable autostart, lock Shield in the recent-apps guard and allow 'No restrictions' again.\n\nActual settings: app info → battery saver → 'No restrictions'.",
                    actionRoute = "diagnostics",
                )
                OemKind.OPPO, OemKind.REALME, OemKind.ONEPLUS, OemKind.VIVO -> items += OemGuidanceItem(
                    id = "oem.cn-battery",
                    title = "Aggressive battery police",
                    body = "Shield runs a foreground VPN service; give it 'Allow background running' in battery settings to avoid it being killed.",
                    actionRoute = "diagnostics",
                )
                OemKind.NOTHING -> items += OemGuidanceItem(
                    id = "oem.nothing",
                    title = "Nothing OS background",
                    body = "Enable 'Pause app activity if unused' exemption for Shield in the app-specific battery screen.",
                    actionRoute = "diagnostics",
                )
                OemKind.EMULATOR -> items += OemGuidanceItem(
                    id = "oem.emulator",
                    title = "Emulator: battery",
                    body = "The run in device mode is enough for a smoke test. Battery-optimization guidance is not relevant here.",
                    actionRoute = null,
                )
                else -> Unit
            }
            return items
        }

    val vpnConflict: VpnConflictInfo
        get() {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            return try {
                val active: Network? = connectivityManager.activeNetwork
                val capabilities: NetworkCapabilities? = active?.let { connectivityManager.getNetworkCapabilities(it) }
                val transportNames = buildList {
                    if (capabilities == null) return@buildList
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELLULAR")
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETHERNET")
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("BLUETOOTH")
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)) add("WIFI_AWARE")
                }
                VpnConflictInfo(
                    activeNetworkUsesVpnTransport = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
                    hasDefaultNetwork = active != null,
                    activeTransportNames = transportNames,
                )
            } catch (t: Throwable) {
                logger.w(TAG, "vpn conflict scan failed: ${t.message}")
                VpnConflictInfo(activeNetworkUsesVpnTransport = false, hasDefaultNetwork = false, activeTransportNames = emptyList())
            }
        }

    private fun readChargingState(): Boolean {
        return try {
            val sticky = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } catch (_: Exception) {
            false
        }
    }

    private fun detectKind(): OemKind {
        if (isEmulatorBuild()) return OemKind.EMULATOR
        val brand = normalize(Build.BRAND).lowercase()
        val manufacturer = normalize(Build.MANUFACTURER).lowercase()
        val device = normalize(Build.DEVICE).lowercase()
        val joined = "$brand $manufacturer $device"
        return when {
            joined.contains("xiaomi") || joined.contains("redmi") -> if (brand.contains("redmi")) OemKind.REDMI else OemKind.XIAOMI
            joined.contains("poco") -> OemKind.POCO
            joined.contains("oneplus") -> OemKind.ONEPLUS
            joined.contains("realme") -> OemKind.REALME
            joined.contains("oppo") -> OemKind.OPPO
            joined.contains("vivo") || joined.contains("iqoo") -> OemKind.VIVO
            joined.contains("honor") -> OemKind.HONOR
            joined.contains("huawei") -> OemKind.HUAWEI
            joined.contains("samsung") || joined.contains("sm-") -> OemKind.SAMSUNG
            joined.contains("motorola") || joined.contains("moto") -> OemKind.MOTOROLA
            joined.contains("nothing") -> OemKind.NOTHING
            joined.contains("pixel") || joined.contains("google") -> OemKind.PIXEL
            joined.contains("fairphone") -> OemKind.FAIRPHONE
            else -> OemKind.OTHER
        }
    }

    private fun isEmulatorBuild(): Boolean =
        (Build.FINGERPRINT ?: "").contains("generic")
            || Build.MODEL?.contains("Emulator") == true
            || Build.MODEL?.contains("Android SDK built for") == true
            || Build.PRODUCT?.contains("sdk_gphone") == true

    private fun normalize(value: String?): String = value?.takeIf { it.isNotBlank() } ?: "unknown"

    companion object {
        private const val TAG = "OemInfoRepository"
    }
}