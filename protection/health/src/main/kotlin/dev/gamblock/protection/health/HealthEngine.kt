package dev.gamblock.protection.health

import android.app.NotificationManager
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamblock.core.common.clock.WallClock
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.model.BatteryStatusInfo
import dev.gamblock.core.model.BlocklistStats
import dev.gamblock.core.model.ComponentHealth
import dev.gamblock.core.model.CommitmentState
import dev.gamblock.core.model.HealthComponent
import dev.gamblock.core.model.HealthReport
import dev.gamblock.core.model.HealthStatus
import dev.gamblock.core.model.VpnRuntimeState
import dev.gamblock.data.blocklist.BlocklistRepository
import dev.gamblock.data.repository.CommitmentEngine
import dev.gamblock.data.repository.UpdateRepository
import dev.gamblock.data.preferences.SettingsRepository
import dev.gamblock.protection.oem.OemInfoRepository
import dev.gamblock.protection.oem.NetworkInfoProvider
import dev.gamblock.protection.vpn.VpnStateStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import android.os.Build
import android.provider.Settings
import android.content.Intent
import android.net.Uri

@Singleton
class HealthEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vpnStateStore: VpnStateStore,
    private val blocklistRepository: BlocklistRepository,
    private val oemRepository: OemInfoRepository,
    private val networkInfoProvider: NetworkInfoProvider,
    private val settingsRepository: SettingsRepository,
    private val commitmentEngine: CommitmentEngine,
    private val updateRepository: UpdateRepository,
    private val wallClock: WallClock,
    private val dispatchers: DispatchersProvider,
    private val logger: ShieldLogger,
) {

    suspend fun measure(): HealthReport = withContext(dispatchers.default) {
        val now = wallClock.nowEpochMillis()
        val components = listOf(
            checkVpn(),
            checkDns(),
            checkBlocklist(),
            checkNetwork(),
            checkBattery(),
            checkPermission(),
            checkCommitment(),
            checkBoot(),
            checkUpdate(),
        )
        val report = HealthReport.aggregate(components, now)
        logger.i(Logs.HEALTH, "health: ${report.overall} (${report.components.size} components)")
        report
    }

    private fun checkVpn(): ComponentHealth {
        val vpn = vpnStateStore.state.value
        return when {
            !vpn.isRunning && vpn.failure == VpnRuntimeState.VpnFailure.REVOKED ->
                ComponentHealth(HealthComponent.VPN, HealthStatus.CRITICAL, "VPN permission revoked", wallClock.nowEpochMillis())
            !vpn.isRunning && vpn.failure == VpnRuntimeState.VpnFailure.IN_SCHEDULE_PAUSE ->
                ComponentHealth(HealthComponent.VPN, HealthStatus.HEALTHY, "paused by schedule", wallClock.nowEpochMillis())
            !vpn.isRunning ->
                ComponentHealth(HealthComponent.VPN, HealthStatus.DEGRADED, "not running", wallClock.nowEpochMillis())
            else ->
                ComponentHealth(HealthComponent.VPN, HealthStatus.HEALTHY, "active", wallClock.nowEpochMillis())
        }
    }

    private fun checkDns(): ComponentHealth {
        val vpn = vpnStateStore.state.value
        return when {
            !vpn.isRunning -> ComponentHealth(HealthComponent.DNS, HealthStatus.UNKNOWN, "VPN off", wallClock.nowEpochMillis())
            vpn.queriesHandled == 0L -> ComponentHealth(HealthComponent.DNS, HealthStatus.DEGRADED, "no queries seen yet", wallClock.nowEpochMillis())
            else -> ComponentHealth(HealthComponent.DNS, HealthStatus.HEALTHY, "${vpn.queriesHandled} queries, ${vpn.queriesBlocked} blocked", wallClock.nowEpochMillis())
        }
    }

    private fun checkBlocklist(): ComponentHealth {
        val snapshot = blocklistRepository.state.value
        return if (snapshot == null) {
            ComponentHealth(HealthComponent.BLOCKLIST, HealthStatus.CRITICAL, "not loaded", wallClock.nowEpochMillis())
        } else {
            val stats = snapshot.stats
            ComponentHealth(
                HealthComponent.BLOCKLIST,
                if (stats.enabledRuleCount > 0) HealthStatus.HEALTHY else HealthStatus.DEGRADED,
                "v${stats.databaseVersion}: ${stats.enabledRuleCount} rules, digest ${stats.integrityDigest.take(10)}",
                wallClock.nowEpochMillis(),
            )
        }
    }

    private fun checkNetwork(): ComponentHealth {
        val online = networkInfoProvider.isOnline
        return ComponentHealth(
            HealthComponent.NETWORK,
            if (online) HealthStatus.HEALTHY else HealthStatus.DEGRADED,
            if (online) "online" else "offline",
            wallClock.nowEpochMillis(),
        )
    }

    private fun checkBattery(): ComponentHealth {
        val battery = oemRepository.batteryStatus
        val exempt = battery.isIgnoringBatteryOptimizations
        return ComponentHealth(
            HealthComponent.BATTERY,
            when {
                !exempt -> HealthStatus.DEGRADED
                battery.isDeviceIdle -> HealthStatus.DEGRADED
                else -> HealthStatus.HEALTHY
            },
            buildString {
                append(if (exempt) "optimizations exempt" else "optimizations enforced")
                if (battery.isDeviceIdle) append(", device idle")
                if (battery.isCharging) append(", charging")
            },
            wallClock.nowEpochMillis(),
        )
    }

    private fun checkPermission(): ComponentHealth {
        val issues = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null && !nm.areNotificationsEnabled()) issues.add("notifications blocked")
        }
        if (!Settings.canDrawOverlays(context)) issues.add("overlay not granted")
        val exempt = try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) { false }
        if (!exempt) issues.add("battery optimization active")
        return ComponentHealth(
            HealthComponent.PERMISSION,
            if (issues.isEmpty()) HealthStatus.HEALTHY else HealthStatus.DEGRADED,
            if (issues.isEmpty()) "all relevant permissions granted" else issues.joinToString("; "),
            wallClock.nowEpochMillis(),
        )
    }

    private fun checkCommitment(): ComponentHealth {
        val commitment = commitmentEngine.active
        return when {
            commitment == null -> ComponentHealth(HealthComponent.COMMITMENT, HealthStatus.UNKNOWN, "no active commitment", wallClock.nowEpochMillis())
            !commitment.canFinish -> ComponentHealth(HealthComponent.COMMITMENT, HealthStatus.HEALTHY, "active: ${commitment.intendedDurationMs / 3600_000}h intended, ${commitment.accumulatedElapsedMillis / 3600_000}h elapsed", wallClock.nowEpochMillis())
            else -> ComponentHealth(HealthComponent.COMMITMENT, HealthStatus.DEGRADED, "elapsed target reached; awaiting user finish", wallClock.nowEpochMillis())
        }
    }

    private fun checkBoot(): ComponentHealth {
        // Boot receiver is declared; health is measured after boot events.
        return ComponentHealth(HealthComponent.BOOT, HealthStatus.HEALTHY, "receiver declared", wallClock.nowEpochMillis())
    }

    private fun checkUpdate(): ComponentHealth {
        val state = updateRepository.state.value
        return ComponentHealth(
            HealthComponent.UPDATE,
            when (state) {
                dev.gamblock.core.model.UpdateState.IDLE,
                dev.gamblock.core.model.UpdateState.NOT_CONFIGURED -> HealthStatus.UNKNOWN
                dev.gamblock.core.model.UpdateState.CHECKING -> HealthStatus.DEGRADED
                dev.gamblock.core.model.UpdateState.UP_TO_DATE -> HealthStatus.HEALTHY
                dev.gamblock.core.model.UpdateState.UPDATE_AVAILABLE -> HealthStatus.DEGRADED
                dev.gamblock.core.model.UpdateState.FAILED -> HealthStatus.CRITICAL
            },
            state.name,
            wallClock.nowEpochMillis(),
        )
    }
}