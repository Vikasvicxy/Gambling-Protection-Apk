package dev.gamblock.protection.vpn

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.data.preferences.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Quick Settings tile to glance at status and toggle protection directly from the
 * status shade. The tile runs in the app's process, so it reads the same local
 * [VpnStateStore] singleton the VPN service writes; state stays 100% on-device.
 */
@AndroidEntryPoint
class ProtectionTileService : TileService() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var stateStore: VpnStateStore
    @Inject lateinit var logger: ShieldLogger

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null
    private var lastUi: ProtectionTileState? = null

    override fun onTileAdded() {
        super.onTileAdded()
        qsTile?.let { tile ->
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_shield)
            tile.label = getString(R.string.shield_tile_label)
            tile.updateTile()
        }
        syncNow()
    }

    override fun onStartListening() {
        super.onStartListening()
        observeState()
    }

    override fun onStopListening() {
        super.onStopListening()
        observeJob?.cancel()
        observeJob = null
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) return

        val state = ProtectionTileStateResolver.resolve(
            vpnEnabled = settingsRepository.settings.value.vpnEnabled,
            vpn = stateStore.state.value,
        )

        when (state.ui) {
            ProtectionTileUi.ACTIVE -> {
                scope.launch { settingsRepository.setVpnEnabled(false) }
                ShieldVpnService.stop(this)
                logger.i(Logs.VPN, "tile: protection off")
            }
            ProtectionTileUi.INACTIVE,
            ProtectionTileUi.UNAVAILABLE,
            ProtectionTileUi.CONNECTING -> {
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.putExtra("dev.gamblock.shield.extra.NAV_DESTINATION", "setup")
                    startActivityAndCollapse(launchIntent)
                }
            }
        }
        syncNow()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun observeState() {
        observeJob?.cancel()
        observeJob = scope.launch {
            combine(
                settingsRepository.settings,
                stateStore.state,
            ) { settings, vpn ->
                ProtectionTileStateResolver.resolve(settings.vpnEnabled, vpn)
            }.collect { state ->
                lastUi = state
                applyToTile(state)
            }
        }
    }

    private fun syncNow() {
        val state = ProtectionTileStateResolver.resolve(
            vpnEnabled = settingsRepository.settings.value.vpnEnabled,
            vpn = stateStore.state.value,
        )
        lastUi = state
        applyToTile(state)
    }

    private fun applyToTile(state: ProtectionTileState) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_shield)
        tile.label = state.label
        tile.subtitle = state.subtitle
        tile.state = when (state.ui) {
            ProtectionTileUi.ACTIVE -> Tile.STATE_ACTIVE
            ProtectionTileUi.CONNECTING, ProtectionTileUi.INACTIVE -> Tile.STATE_INACTIVE
            ProtectionTileUi.UNAVAILABLE -> Tile.STATE_UNAVAILABLE
        }
        tile.updateTile()
    }
}