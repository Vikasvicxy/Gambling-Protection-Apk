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
import dev.gamblock.protection.vpn.VpnStateStore
import javax.inject.Inject
import javax.inject.Singleton

/** Normalized device facts and OEM-specific guidance. No network involved. */
@Singleton
class OemInfoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vpnStateStore: VpnStateStore,
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
                    body = "Enable autostart, lock Shield in the recent-apps guard and allow 'No restrictions' again.",
                    steps = listOf(
                        "Settings → Apps → Manage apps → Shield → Autostart: on",
                        "Open Recent apps, long-press Shield, tap the padlock to keep it in memory",
                        "Settings → Battery → Battery saver → Shield → 'No restrictions'",
                    ),
                    actionRoute = "diagnostics",
                )
                OemKind.OPPO, OemKind.REALME, OemKind.ONEPLUS, OemKind.VIVO -> items += OemGuidanceItem(
                    id = "oem.cn-battery",
                    title = "Aggressive battery police",
                    body = "Shield runs a foreground VPN service; give it 'Allow background running' in battery settings to avoid it being killed.",
                    steps = listOf(
                        "Settings → Battery → App battery management → Shield → 'Allow background running'",
                        "Settings → App management → Shield → Allow self-start and auto-launch",
                        "Lock Shield in the Recent apps view (drag down on its card)",
                    ),
                    actionRoute = "diagnostics",
                )
                OemKind.NOTHING -> items += OemGuidanceItem(
                    id = "oem.nothing",
                    title = "Nothing OS background",
                    body = "Enable 'Pause app activity if unused' exemption for Shield in the app-specific battery screen.",
                    steps = listOf(
                        "Settings → Apps → Shield → Battery → 'Pause app activity if unused' → uncheck",
                        "Settings → Battery → Background restriction → Shield → 'Unrestricted'",
                    ),
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
                VpnConflictResolver.resolve(
                    activeNetworkHasVpnTransport = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
                    shieldVpnActive = activeVpnIsShield(capabilities),
                    hasDefaultNetwork = active != null,
                    activeTransportNames = transportNames,
                )
            } catch (t: Throwable) {
                logger.w(TAG, "vpn conflict scan failed: ${t.message}")
                VpnConflictInfo(activeNetworkUsesVpnTransport = false, hasDefaultNetwork = false, activeTransportNames = emptyList())
            }
        }

    /**
     * True when the active network's VPN transport belongs to Shield. On API 30+ the
     * platform exposes the creating UID on [NetworkCapabilities], which is exact;
     * below that we trust the process-local VPN service state.
     */
    private fun activeVpnIsShield(capabilities: NetworkCapabilities?): Boolean {
        if (capabilities == null) return false
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            // getOwnerUid landed in API 30, not 29; calling it on 29 throws.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        ) {
            return try {
                capabilities.getOwnerUid() == context.applicationInfo.uid
            } catch (t: Throwable) {
                vpnStateStore.state.value.isRunning
            }
        }
        return vpnStateStore.state.value.isRunning
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