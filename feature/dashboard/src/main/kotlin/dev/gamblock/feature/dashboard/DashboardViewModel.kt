package dev.gamblock.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamblock.core.model.BlockAttemptGroup
import dev.gamblock.core.model.BlocklistStats
import dev.gamblock.core.model.Commitment
import dev.gamblock.core.model.HealthReport
import dev.gamblock.core.model.VpnRuntimeState
import dev.gamblock.data.blocklist.BlocklistRepository
import dev.gamblock.data.repository.BlockEventRepository
import dev.gamblock.data.repository.CommitmentEngine
import dev.gamblock.data.preferences.SettingsRepository
import dev.gamblock.data.preferences.SettingsState
import dev.gamblock.protection.health.HealthEngine
import dev.gamblock.protection.tamper.LockscreenAuthGate
import dev.gamblock.protection.vpn.VpnStateStore
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardUiState(
    val settings: SettingsState = SettingsState(),
    val vpn: VpnRuntimeState = VpnRuntimeState(),
    val stats: BlocklistStats? = null,
    val totalBlockedAttempts: Int = 0,
    val recentBlocked: List<BlockAttemptGroup> = emptyList(),
    val commitment: Commitment? = null,
    val health: HealthReport? = null,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val vpnStateStore: VpnStateStore,
    private val blocklistRepository: BlocklistRepository,
    private val blockEventRepository: BlockEventRepository,
    private val commitmentEngine: CommitmentEngine,
    private val healthEngine: HealthEngine,
    val lockscreenGate: LockscreenAuthGate,
) : ViewModel() {

    private val _health = MutableStateFlow<HealthReport?>(null)

    private val _gateMessage = MutableStateFlow<String?>(null)
    val gateMessage: StateFlow<String?> = _gateMessage.asStateFlow()

    private var gateMessageJob: kotlinx.coroutines.Job? = null

    private val core: StateFlow<DashboardUiState> = combine(
        settingsRepository.settings,
        vpnStateStore.state,
        blocklistRepository.state,
        blockEventRepository.observeTotalAttempts(),
        blockEventRepository.observeRecent(5),
    ) { settings, vpn, blocklist, total, recent ->
        DashboardUiState(
            settings = settings,
            vpn = vpn,
            stats = blocklist?.stats,
            totalBlockedAttempts = total,
            recentBlocked = recent,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DashboardUiState())

    val uiState: StateFlow<DashboardUiState> = combine(
        core,
        commitmentEngine.current,
        _health,
    ) { base, commitment, health ->
        base.copy(commitment = commitment, health = health)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DashboardUiState())

    init {
        refreshHealth()
    }

    fun setProtectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setVpnEnabled(enabled)
        }
    }

    /** Shows a transient banner (e.g. "Set a device lock in Settings first"). */
    fun showGateMessage(message: String) {
        gateMessageJob?.cancel()
        gateMessageJob = viewModelScope.launch {
            _gateMessage.value = message
            delay(GATE_MESSAGE_MS)
            _gateMessage.value = null
        }
    }

    companion object {
        private const val GATE_MESSAGE_MS = 4_000L
    }

    fun refreshHealth() {
        viewModelScope.launch {
            _health.value = healthEngine.measure()
        }
    }
}