package dev.gamblock.feature.diagnostics

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.model.HealthComponent
import dev.gamblock.core.model.HealthReport
import dev.gamblock.core.model.HealthStatus
import dev.gamblock.core.model.OemGuidanceItem
import dev.gamblock.core.model.OemInfo
import dev.gamblock.core.model.VpnConflictInfo
import dev.gamblock.protection.health.HealthEngine
import dev.gamblock.protection.oem.OemInfoRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DiagnosticsUiState(
    val report: HealthReport? = null,
    val oem: OemInfo? = null,
    val conflict: VpnConflictInfo? = null,
    val guidance: List<OemGuidanceItem> = emptyList(),
    val isBatteryOptimizationExempt: Boolean = false,
    val isCharging: Boolean = false,
)

/** Groups a [HealthComponent] under a human diagnostics bucket. */
enum class DiagnosticsGroup(val displayName: String) {
    NETWORK_ENGINE("Network engine"),
    SYSTEM_COMPLIANCE("System compliance"),
    DATA_INTEGRITY("Data integrity"),
    ENGAGEMENT("Engagement"),
}

fun DiagnosticsGroup.of(component: HealthComponent): DiagnosticsGroup = when (component) {
    HealthComponent.VPN, HealthComponent.DNS, HealthComponent.NETWORK -> DiagnosticsGroup.NETWORK_ENGINE
    HealthComponent.BATTERY, HealthComponent.PERMISSION, HealthComponent.BOOT -> DiagnosticsGroup.SYSTEM_COMPLIANCE
    HealthComponent.BLOCKLIST, HealthComponent.DATABASE, HealthComponent.UPDATE -> DiagnosticsGroup.DATA_INTEGRITY
    HealthComponent.COMMITMENT -> DiagnosticsGroup.ENGAGEMENT
}

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val healthEngine: HealthEngine,
    private val oemInfoRepository: OemInfoRepository,
    private val logger: ShieldLogger,
) : ViewModel() {

    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = DiagnosticsUiState(
                report = healthEngine.measure(),
                oem = oemInfoRepository.oemInfo,
                conflict = oemInfoRepository.vpnConflict,
                guidance = oemInfoRepository.guidance,
                isBatteryOptimizationExempt = oemInfoRepository.batteryStatus.isIgnoringBatteryOptimizations,
                isCharging = oemInfoRepository.batteryStatus.isCharging,
            )
        }
    }

    /** True when we can offer a one-tap system "Fix" for a degraded component. */
    fun canFix(component: HealthComponent): Boolean = when (component) {
        HealthComponent.VPN,
        HealthComponent.BATTERY,
        HealthComponent.PERMISSION -> true
        else -> false
    }

    /** Directly ambles the user to the right system settings screen. */
    fun applyFix(component: HealthComponent) {
        val intent = fixIntent(component) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            logger.w(Logs.UI, "diagnostics fix failed for $component: ${t.message}")
        }
    }

    private fun fixIntent(component: HealthComponent): Intent? = when (component) {
        HealthComponent.VPN -> {
            val prepare = VpnService.prepare(context)
            when {
                prepare != null -> prepare
                else -> context.packageManager.getLaunchIntentForPackage(context.packageName)
            }
        }
        HealthComponent.BATTERY -> Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        ).setData(android.net.Uri.parse("package:${context.packageName}"))
        HealthComponent.PERMISSION -> Intent(ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        else -> null
    }

    companion object {
        /** Components whose fix action is available regardless of measured state. */
        fun fixableComponents(): Set<HealthComponent> =
            setOf(HealthComponent.VPN, HealthComponent.BATTERY, HealthComponent.PERMISSION)

        /** List of components belonging to a bucket, in render order. */
        fun componentsOf(group: DiagnosticsGroup): List<HealthComponent> = when (group) {
            DiagnosticsGroup.NETWORK_ENGINE -> listOf(HealthComponent.VPN, HealthComponent.DNS, HealthComponent.NETWORK)
            DiagnosticsGroup.SYSTEM_COMPLIANCE -> listOf(HealthComponent.BATTERY, HealthComponent.PERMISSION, HealthComponent.BOOT)
            DiagnosticsGroup.DATA_INTEGRITY -> listOf(HealthComponent.BLOCKLIST, HealthComponent.DATABASE, HealthComponent.UPDATE)
            DiagnosticsGroup.ENGAGEMENT -> listOf(HealthComponent.COMMITMENT)
        }

        fun isDegraded(status: HealthStatus): Boolean =
            status == HealthStatus.DEGRADED || status == HealthStatus.CRITICAL

        /** Diagnostic-friendly names for each component. */
        fun displayName(component: HealthComponent): String = when (component) {
            HealthComponent.VPN -> "VPN tunnel"
            HealthComponent.DNS -> "DNS filtering"
            HealthComponent.NETWORK -> "Upstream connectivity"
            HealthComponent.BATTERY -> "Battery optimization"
            HealthComponent.PERMISSION -> "Notifications & permissions"
            HealthComponent.BOOT -> "Boot auto-start"
            HealthComponent.BLOCKLIST -> "Blocklist engine"
            HealthComponent.DATABASE -> "Signed database integrity"
            HealthComponent.UPDATE -> "Blocklist updates"
            HealthComponent.COMMITMENT -> "Commitment engine"
        }
    }
}