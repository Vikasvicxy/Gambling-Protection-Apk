package dev.gamblock.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamblock.core.model.CravingTrigger
import dev.gamblock.core.model.FortressStatus
import dev.gamblock.core.model.RecoveryMetrics
import dev.gamblock.core.model.UrgeTimerConfig
import dev.gamblock.core.model.UrgeTimerMachine
import dev.gamblock.core.model.UrgeTimerSnapshot
import dev.gamblock.core.model.UrgeTimerState
import dev.gamblock.data.preferences.CravingInsightsSnapshot
import dev.gamblock.data.preferences.RecoveryRepository
import dev.gamblock.data.repository.ProtectionActionResult
import dev.gamblock.data.repository.ProtectionCommandCoordinator
import dev.gamblock.data.repository.ProtectionGateHolder
import dev.gamblock.protection.vpn.PrivateDnsStatus
import dev.gamblock.protection.vpn.PrivateDnsWatchdog
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RecoveryUiState(
    val metrics: RecoveryMetrics = RecoveryMetrics(),
    val insights: CravingInsightsSnapshot = CravingInsightsSnapshot(),
    val journalPromptDomain: String? = null,
    val fortress: FortressStatus = FortressStatus(),
    val privateDns: PrivateDnsStatus = PrivateDnsStatus(),
)

data class UrgeTimerUiState(
    val visible: Boolean = false,
    val machine: UrgeTimerSnapshot = UrgeTimerMachine().snapshot(),
    val pledgeText: String = "",
    val pinEntry: String = "",
    val pinMessage: String? = null,
    val fortressNotice: String? = null,
    val blocked: Boolean = false,
) {
    val countdown: UrgeTimerState
        get() = machine.state
}

@HiltViewModel
class RecoveryViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val settingsRepository: dev.gamblock.data.preferences.SettingsRepository,
    private val recoveryRepository: RecoveryRepository,
    private val coordinator: ProtectionCommandCoordinator,
    private val gateHolder: ProtectionGateHolder,
    private val privateDnsWatchdog: PrivateDnsWatchdog,
) : ViewModel() {

    private val _fortress = MutableStateFlow(FortressStatus())
    val fortress: StateFlow<FortressStatus> = _fortress.asStateFlow()

    private val _insights = MutableStateFlow(CravingInsightsSnapshot())
    val insights: StateFlow<CravingInsightsSnapshot> = _insights.asStateFlow()

    private val _journalPromptDomain = MutableStateFlow<String?>(null)
    val journalPromptDomain: StateFlow<String?> = _journalPromptDomain.asStateFlow()

    val metrics: StateFlow<RecoveryMetrics> = recoveryRepository.metrics
    val privateDns: StateFlow<PrivateDnsStatus> = privateDnsWatchdog.status

    init {
        privateDnsWatchdog.start()
        refreshInsights()
        refreshFortress()
    }

    override fun onCleared() {
        privateDnsWatchdog.stop()
        super.onCleared()
    }

    fun refreshInsights() {
        viewModelScope.launch {
            _insights.value = recoveryRepository.journalInsights()
        }
    }

    fun refreshFortress() {
        viewModelScope.launch {
            _fortress.value = coordinator.fortressStatus()
        }
    }

    fun refreshPrivateDns() {
        privateDnsWatchdog.refreshNow()
    }

    fun openWirelessSettings() {
        val intent = android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Turning the cooling-off timer off removes a safety net, so it is treated
     * as a sensitive change and goes through the shared gate.
     */
    fun onUrgeTimerEnabledChange(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                settingsRepository.setUrgeTimerEnabled(true)
                return@launch
            }
            gateHolder.requestSensitiveChange { settingsRepository.setUrgeTimerEnabled(false) }
        }
    }

    fun promptJournal(domain: String?) {
        _journalPromptDomain.value = domain
    }

    fun dismissJournalPrompt() {
        _journalPromptDomain.value = null
    }

    fun logUrge(
        intensity: Int,
        triggers: Set<CravingTrigger>,
        note: String,
        onLogged: () -> Unit = {},
    ) {
        val domain = _journalPromptDomain.value
        viewModelScope.launch {
            recoveryRepository.addJournalEntry(
                intensity = intensity,
                triggers = triggers,
                note = note,
                blockedDomain = domain,
            )
            _journalPromptDomain.value = null
            refreshInsights()
            onLogged()
        }
    }

    fun deleteJournalEntry(id: Long) {
        viewModelScope.launch {
            recoveryRepository.deleteJournalEntry(id)
            refreshInsights()
        }
    }

    fun clearJournal() {
        viewModelScope.launch {
            gateHolder.requestSensitiveChange {
                recoveryRepository.clearJournal()
                refreshInsights()
            }
        }
    }

    /** Resetting the streak date rewrites the savings figure, so it is gated. */
    fun startRecoveryNow() {
        viewModelScope.launch {
            gateHolder.requestSensitiveChange { recoveryRepository.startRecoveryNow() }
        }
    }

    fun setWeeklySpendMinor(minor: Long) {
        viewModelScope.launch { recoveryRepository.setWeeklySpendMinor(minor) }
    }

    fun setCurrency(symbol: String, code: String) {
        viewModelScope.launch {
            recoveryRepository.setCurrency(
                dev.gamblock.core.model.RecoveryCurrency.fromCodeOrSymbol(code, symbol),
            )
        }
    }

}
