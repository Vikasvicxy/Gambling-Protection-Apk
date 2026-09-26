package dev.gamblock.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamblock.core.model.CravingTrigger
import dev.gamblock.core.model.FinancialProfile
import dev.gamblock.core.model.FortressStatus
import dev.gamblock.core.model.FortressWindow
import dev.gamblock.core.model.RecoveryCurrency
import dev.gamblock.core.model.RecoveryMetrics
import dev.gamblock.data.preferences.CravingInsightsSnapshot
import dev.gamblock.data.preferences.GuardianPinRepository
import dev.gamblock.data.preferences.GuardianPinUnlockState
import dev.gamblock.data.preferences.RecoveryRepository
import dev.gamblock.data.preferences.SettingsRepository
import dev.gamblock.data.preferences.SettingsState
import dev.gamblock.data.repository.ProtectionActionResult
import dev.gamblock.data.repository.ProtectionCommandCoordinator
import dev.gamblock.data.repository.ProtectionGateHolder
import dev.gamblock.data.repository.ProtectionUptimeSummary
import dev.gamblock.data.repository.SobrietyReportGenerator
import dev.gamblock.protection.vpn.PrivateDnsStatus
import dev.gamblock.protection.vpn.PrivateDnsWatchdog
import dev.gamblock.protection.vpn.VpnStateStore
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecoverySettingsUiState(
    val settings: SettingsState = SettingsState(),
    val metrics: RecoveryMetrics = RecoveryMetrics(),
    val profile: FinancialProfile = FinancialProfile(),
    val fortress: FortressStatus = FortressStatus(),
    val fortressWindows: List<FortressWindow> = emptyList(),
    val activeFortressWindowLabel: String? = null,
    val nextFortressWindowLabel: String? = null,
    val fortressEnabled: Boolean = false,
    val privateDns: PrivateDnsStatus = PrivateDnsStatus(),
    val insights: CravingInsightsSnapshot = CravingInsightsSnapshot(),
    val guardianPinConfigured: Boolean = false,
    val guardianPinEnabled: Boolean = false,
    val guardianPinState: GuardianPinUnlockState = GuardianPinUnlockState.NotConfigured,
    val quicDrops: Long = 0L,
    val statusMessage: String? = null,
    val reportFile: File? = null,
    val reportBusy: Boolean = false,
)

@HiltViewModel
class RecoverySettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val recoveryRepository: RecoveryRepository,
    private val guardianPinRepository: GuardianPinRepository,
    private val coordinator: ProtectionCommandCoordinator,
    private val gateHolder: ProtectionGateHolder,
    private val reportGenerator: SobrietyReportGenerator,
    private val privateDnsWatchdog: PrivateDnsWatchdog,
    private val vpnStateStore: VpnStateStore,
) : ViewModel() {

    private val _status = MutableStateFlow<String?>(null)
    private val _report = MutableStateFlow<File?>(null)
    private val _reportBusy = MutableStateFlow(false)

    private val reportState = combine(_report, _reportBusy) { file, busy -> file to busy }

    private val baseState = combine(
        settingsRepository.settings,
        recoveryRepository.metrics,
        recoveryRepository.profile,
        recoveryRepository.fortress,
        guardianPinRepository.isConfigured,
        guardianPinRepository.unlockState,
        privateDnsWatchdog.status,
        vpnStateStore.state,
        _status,
        reportState,
    ) {         values: Array<Any?> ->
        val settings = values[0] as SettingsState
        val metrics = values[1] as RecoveryMetrics
        val profile = values[2] as FinancialProfile
        val fortress = values[3] as dev.gamblock.data.preferences.FortressSnapshot
        val pinConfigured = values[4] as Boolean
        val pinState = values[5] as GuardianPinUnlockState
        val privateDns = values[6] as PrivateDnsStatus
        val vpn = values[7] as dev.gamblock.core.model.VpnRuntimeState
        val status = values[8] as String?
        val report = values[9] as Pair<File?, Boolean>
        val file = report.first
        val busy = report.second
        RecoverySettingsUiState(
            settings = settings,
            metrics = metrics,
            profile = profile,
            fortress = FortressStatus(
                lockedDown = fortress.lockedDown,
                activeWindow = fortress.windows.firstOrNull { it.label == fortress.activeWindowLabel },
                nextWindow = fortress.windows.firstOrNull { it.label == fortress.nextWindowLabel },
            ),
            activeFortressWindowLabel = fortress.activeWindowLabel,
            nextFortressWindowLabel = fortress.nextWindowLabel,
            fortressWindows = fortress.windows,
            fortressEnabled = fortress.enabled,
            privateDns = privateDns,
            guardianPinConfigured = pinConfigured,
            guardianPinEnabled = settings.guardianPinEnabled,
            guardianPinState = pinState,
            quicDrops = vpn.quicDrops,
            statusMessage = status,
            reportFile = file,
            reportBusy = busy,
        )
    }

    val uiState: StateFlow<RecoverySettingsUiState> =
        baseState.stateIn(viewModelScope, SharingStarted.Eagerly, RecoverySettingsUiState())

    init {
        privateDnsWatchdog.start()
    }

    fun setUrgeTimerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                settingsRepository.setUrgeTimerEnabled(true)
                return@launch
            }
            gateHolder.requestSensitiveChange {
                settingsRepository.setUrgeTimerEnabled(false)
                _status.value = "Cooling-off timer off"
            }
        }
    }

    /**
     * Fortress mode is the strongest gate, so turning it off has to clear the
     * same Fortress and PIN checks as any other destructive change. An active
     * window still blocks it outright.
     */
    fun setFortressModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                recoveryRepository.setFortressEnabled(true)
                settingsRepository.setFortressModeEnabled(true)
                _status.value = "Fortress mode on"
                return@launch
            }
            gateHolder.requestSensitiveChange {
                recoveryRepository.setFortressEnabled(false)
                settingsRepository.setFortressModeEnabled(false)
                _status.value = "Fortress mode off"
            }
        }
    }

    /** Turning DoH/DoQ blocking off weakens the DNS shield, so it is gated. */
    fun setBlockEncryptedBrowsers(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                settingsRepository.setBlockEncryptedBrowsers(true)
                return@launch
            }
            gateHolder.requestSensitiveChange {
                settingsRepository.setBlockEncryptedBrowsers(false)
                _status.value = "Encrypted browser blocking off"
            }
        }
    }

    /** Muting the Private DNS alarm also weakens the shield, so it is gated. */
    fun setPrivateDnsAlertEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                settingsRepository.setPrivateDnsAlertEnabled(true)
                return@launch
            }
            gateHolder.requestSensitiveChange {
                settingsRepository.setPrivateDnsAlertEnabled(false)
                _status.value = "Private DNS warning off"
            }
        }
    }

    /**
     * Disabling PIN enforcement is itself a gated change: otherwise the PIN
     * could be switched off in one tap, which would make it decorative.
     */
    fun setGuardianPinEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && !guardianPinRepository.isConfigured.value) {
                _status.value = "Set a 4-digit guardian PIN first"
                return@launch
            }
            if (!enabled) {
                gateHolder.requestSensitiveChange {
                    settingsRepository.setGuardianPinEnabled(false)
                    _status.value = "Guardian PIN enforcement off"
                }
                return@launch
            }
            settingsRepository.setGuardianPinEnabled(true)
        }
    }

    /**
     * Setting the first PIN is the bootstrap case and needs no authorisation.
     * Once a PIN exists, replacing it requires clearing the existing one, so it
     * goes through the same gate as any other protected change.
     */
    fun setGuardianPin(pin: String) {
        viewModelScope.launch {
            if (guardianPinRepository.isConfigured.value) {
                gateHolder.requestSensitiveChange {
                    saveGuardianPin(pin, "Guardian PIN updated")
                }
                return@launch
            }
            saveGuardianPin(pin, "Guardian PIN saved")
        }
    }

    private suspend fun saveGuardianPin(pin: String, successMessage: String) {
        guardianPinRepository.setPin(pin)
            .onSuccess {
                settingsRepository.setGuardianPinEnabled(true)
                _status.value = successMessage
            }
            .onFailure { error ->
                _status.value = error.message ?: "Could not save the PIN"
            }
    }

    /** Removing the PIN removes the gate itself, so it has to be authorised. */
    fun clearGuardianPin() {
        viewModelScope.launch {
            if (!guardianPinRepository.isConfigured.value) {
                _status.value = "No guardian PIN is set"
                return@launch
            }
            gateHolder.requestSensitiveChange {
                guardianPinRepository.clearPin()
                settingsRepository.setGuardianPinEnabled(false)
                _status.value = "Guardian PIN removed"
            }
        }
    }

    fun setWeeklySpendMinor(minor: Long) {
        viewModelScope.launch { recoveryRepository.setWeeklySpendMinor(minor) }
    }

    fun setCurrency(currency: RecoveryCurrency) {
        viewModelScope.launch { recoveryRepository.setCurrency(currency) }
    }

    /**
     * Resetting the streak date rewrites the savings figure, so it is a gated
     * change rather than a free action.
     */
    fun startStreakNow() {
        viewModelScope.launch {
            gateHolder.requestSensitiveChange { recoveryRepository.startRecoveryNow() }
        }
    }

    fun addNightlyFortressWindow() {
        viewModelScope.launch {
            recoveryRepository.addFortressWindow(FortressWindow.overnightDaily(23, 5))
        }
    }

    fun addWeekendFortressWindow() {
        viewModelScope.launch {
            recoveryRepository.addFortressWindow(FortressWindow.weekendToMonday(20, 6))
        }
    }

    fun removeFortressWindow(id: String) {
        viewModelScope.launch { recoveryRepository.removeFortressWindow(id) }
    }

    fun generateReport() {
        if (_reportBusy.value) return
        _reportBusy.value = true
        viewModelScope.launch {
            val uptime = ProtectionUptimeSummary(
                connectedSessions = if (uiState.value.settings.vpnEnabled) 1L else 0L,
                totalSessions = 1L,
                daysWithoutInterruption = uiState.value.metrics.daysClean,
                queriesBlocked = vpnStateStore.state.value.queriesBlocked,
                quicDrops = vpnStateStore.state.value.quicDrops,
            )
            runCatching { reportGenerator.generate(uptime = uptime) }
                .onSuccess { report ->
                    _report.value = report.file
                    _status.value = "Report ready to share"
                }
                .onFailure { error ->
                    _status.value = "Could not create report: ${error.message}"
                }
            _reportBusy.value = false
        }
    }

    fun clearReport() {
        _report.value = null
    }

    fun clearStatus() {
        _status.value = null
    }
}
