package dev.gamblock.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamblock.core.model.CravingTrigger
import dev.gamblock.core.model.FinancialProfile
import dev.gamblock.core.model.FortressStatus
import dev.gamblock.core.model.FortressWindow
import dev.gamblock.core.model.GamblingAppScanner
import dev.gamblock.core.model.InstalledAppCandidate
import dev.gamblock.core.model.RecoveryCurrency
import dev.gamblock.core.model.RecoveryMetrics
import dev.gamblock.data.backup.PreparedRestore
import dev.gamblock.data.backup.RecoveryBackupRepository
import dev.gamblock.data.preferences.AppExclusionRepository
import dev.gamblock.data.preferences.CravingInsightsSnapshot
import dev.gamblock.data.preferences.GuardianPinRepository
import dev.gamblock.data.preferences.GuardianPinUnlockState
import dev.gamblock.data.preferences.RecoveryRepository
import dev.gamblock.data.preferences.SettingsRepository
import dev.gamblock.data.preferences.SettingsState
import dev.gamblock.core.common.dispatcher.DispatchersProvider
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
    val ipv6Suppressed: Long = 0L,
    val searchEngineQueries: Long = 0L,
    val appRiskScan: AppRiskScanUiState = AppRiskScanUiState(),
    val excludedPackages: Set<String> = emptySet(),
    val installableApps: List<InstalledAppCandidate> = emptyList(),
    val statusMessage: String? = null,
    val reportFile: File? = null,
    val reportBusy: Boolean = false,
    val backupBusy: Boolean = false,
    /**
     * A backup that has been opened and validated but not yet applied. Non-null
     * means the confirmation sheet is showing what the user is about to replace
     * their current data with.
     */
    val pendingRestore: BackupRestorePreview? = null,
)

/**
 * Result of the offline gambling-app scan, for display only.
 *
 * [partialCoverage] is carried all the way to the screen because a clean scan
 * does not mean a clean phone: Android 11+ only reveals the packages declared
 * in the manifest `<queries>` element, so the honest phrasing is "nothing
 * matched in the N apps Shield could see", never "your phone is clear".
 */
data class AppRiskScanUiState(
    val scanning: Boolean = false,
    val scannedCount: Int = 0,
    val verdicts: List<GamblingAppScanner.Verdict> = emptyList(),
    val partialCoverage: Boolean = true,
    val error: String? = null,
)

/** What a backup file holds, shown before the user commits to restoring it. */
data class BackupRestorePreview(
    val daysClean: Int = 0,
    val journalEntries: Int = 0,
    val customExceptions: Int = 0,
    val fortressWindows: Int = 0,
    val milestonesReached: Int = 0,
    val weeklySpendMinor: Long = 0L,
    val currency: RecoveryCurrency = RecoveryCurrency.default,
    val exportedAtEpochMs: Long = 0L,
    val appVersionName: String = "",
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
    private val backupRepository: RecoveryBackupRepository,
    private val appExclusionRepository: AppExclusionRepository,
    private val installedAppsProvider: InstalledAppsProvider,
    private val dispatchers: DispatchersProvider,
) : ViewModel() {

    private val _status = MutableStateFlow<String?>(null)
    private val _installableApps = MutableStateFlow<List<InstalledAppCandidate>>(emptyList())
    private val _appRiskScan = MutableStateFlow(AppRiskScanUiState())
    val appRiskScan: StateFlow<AppRiskScanUiState> = _appRiskScan.asStateFlow()
    private val _report = MutableStateFlow<File?>(null)
    private val _reportBusy = MutableStateFlow(false)
    private val _backupBusy = MutableStateFlow(false)
    private val _pendingRestore = MutableStateFlow<PreparedRestore?>(null)

    /**
     * Held only in memory, and only between the passphrase dialog and the write.
     * It is deliberately not in Compose `rememberSaveable` state, which would
     * write it into the saved instance state Bundle on disk.
     */
    private var pendingExportPassphrase: CharArray? = null
    private var pendingRestoreUri: Uri? = null

    /**
     * Whether the last export or restore has actually run to completion. The
     * protection gate can park either one behind the Guardian PIN, and it can be
     * dismissed instead of cleared, so the UI needs to tell "still waiting on the
     * user" apart from "finished, here is the result".
     */
    private var exportSettled = true
    private var restoreSettled = true

    private val reportState = combine(_report, _reportBusy) { file, busy -> file to busy }
    private val backupState = combine(_backupBusy, _pendingRestore) { busy, pending -> busy to pending }
    private val exclusionState =
        combine(appExclusionRepository.state, _installableApps) { state, apps ->
            state.excludedPackages to apps
        }

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
            backupState,
            exclusionState,
            _appRiskScan,
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
            val backup = values[10] as Pair<Boolean, PreparedRestore?>
            val exclusion = values[11] as Pair<Set<String>, List<InstalledAppCandidate>>
        val riskScan = values[12] as AppRiskScanUiState
        val file = report.first
        val busy = report.second
        RecoverySettingsUiState(
                settings = settings,
                metrics = metrics,
                profile = profile,
                excludedPackages = exclusion.first,
                installableApps = exclusion.second,
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
              ipv6Suppressed = vpn.ipv6Suppressed,
              searchEngineQueries = vpn.searchEngineQueries,
              appRiskScan = riskScan,
            statusMessage = status,
            reportFile = file,
            reportBusy = busy,
            backupBusy = backup.first,
            pendingRestore = backup.second?.let(::toPreview),
        )
    }

    private fun toPreview(prepared: PreparedRestore) = BackupRestorePreview(
        daysClean = prepared.summary.daysClean,
        journalEntries = prepared.summary.journalEntries,
        customExceptions = prepared.summary.customExceptions,
        fortressWindows = prepared.summary.fortressWindows,
        milestonesReached = prepared.summary.milestonesReached,
        weeklySpendMinor = prepared.summary.weeklySpendMinor,
        currency = RecoveryCurrency.fromCodeOrSymbol(prepared.summary.currencyCode, null),
        exportedAtEpochMs = prepared.summary.exportedAtEpochMs,
        appVersionName = prepared.summary.appVersionName,
    )

    val uiState: StateFlow<RecoverySettingsUiState> =
        baseState.stateIn(viewModelScope, SharingStarted.Eagerly, RecoverySettingsUiState())

    init {
        privateDnsWatchdog.start()
        watchForDismissedBackup()
    }

    /**
     * A parked export or restore only reports a result if the gate is actually
     * cleared. If the user dismisses the gate instead, nothing ever calls back, so
     * the buttons would stay disabled forever with a passphrase still in memory.
     * Watching the gate close without a settled action is what releases it.
     */
    private fun watchForDismissedBackup() {
        viewModelScope.launch {
            gateHolder.state.collect { gate ->
                if (gate.isVisible) return@collect
                if (!exportSettled && _backupBusy.value) {
                    exportSettled = true
                    clearExportPassphrase()
                    if (pendingRestoreUri != null || _pendingRestore.value != null) return@collect
                    _status.value = "Backup cancelled"
                    _backupBusy.value = false
                } else if (!restoreSettled && _backupBusy.value) {
                    restoreSettled = true
                    _backupBusy.value = false
                    _status.value = "Restore cancelled"
                }
            }
        }
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

    /**
     * Loads the apps the platform will let us see.
     *
     * Called when the exemption section opens rather than at construction: the
     * query can touch a few hundred packages, and there is no reason to pay for
     * it on every settings visit.
     */
    fun refreshInstallableApps() {
        viewModelScope.launch {
            _installableApps.value = runCatching {
                installedAppsProvider.candidates(dispatchers.io)
            }.getOrElse {
                _status.value = "Could not read the installed app list."
                emptyList()
            }
        }
    }

    fun toggleAppExclusion(packageName: String) {
        viewModelScope.launch {
            val current = appExclusionRepository.excludedPackages
            appExclusionRepository.toggle(packageName, packageName !in current)
        }
    }

    /**
     * Runs the offline gambling-app scan over the apps the platform will show us.
     *
     * Kept on demand rather than on every settings visit for the same reason as
     * [refreshInstallableApps]: the query touches a few hundred packages. The scan
     * itself is pure and local, so there is no network cost to avoid - only the
     * `PackageManager` walk.
     *
     * The result is advice, not action. Nothing here blocks, uninstalls, or
     * exempts anything; see `GamblingAppScanner` for why a heuristic is not
     * allowed to act on its own.
     */
    fun runAppRiskScan() {
        if (_appRiskScan.value?.scanning == true) return
        viewModelScope.launch {
            _appRiskScan.value = AppRiskScanUiState(scanning = true)
            val result = runCatching {
                val candidates = installedAppsProvider.candidates(
                    dispatcher = dispatchers.io,
                    includeSystem = true,
                )
                GamblingAppScanner.scan(candidates)
            }.getOrElse {
                _appRiskScan.value = AppRiskScanUiState(
                    scanning = false,
                    error = "Could not read the installed app list.",
                )
                return@launch
            }
            _appRiskScan.value = AppRiskScanUiState(
                scanning = false,
                scannedCount = result.scannedCount,
                verdicts = result.verdicts,
                partialCoverage = result.partialCoverage,
            )
        }
    }

    fun clearAppRiskScan() {
        _appRiskScan.value = AppRiskScanUiState()
    }

    /** Adds a package the user typed by hand, validating before it is stored. */
    fun addExclusionByPackageName(packageName: String) {
        viewModelScope.launch {
            val trimmed = packageName.trim()
            if (!dev.gamblock.core.model.AppExclusionFilter.isValidPackageName(trimmed)) {
                _status.value = "\"$trimmed\" is not a valid Android package name."
                return@launch
            }
            appExclusionRepository.add(trimmed)
            _status.value = if (trimmed in appExclusionRepository.excludedPackages) {
                "$trimmed now bypasses DNS filtering."
            } else {
                "$trimmed could not be stored."
            }
        }
    }

    fun clearAllExclusions() {
        viewModelScope.launch {
            appExclusionRepository.clear()
            _status.value = "All app exemptions removed."
        }
    }

    fun setIpv6LeakProtectionEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setIpv6LeakProtectionEnabled(enabled) }
    }

    fun setSafeSearchAssistEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSafeSearchAssistEnabled(enabled) }
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

    // ---------------------------------------------------------------------
    // Encrypted backup and restore
    // ---------------------------------------------------------------------

    /**
     * Called once the user has typed and confirmed an export passphrase. The
     * passphrase is parked in memory only; the caller then launches the system
     * file picker and hands the chosen Uri to [exportTo].
     */
    fun stageExportPassphrase(passphrase: CharArray) {
        clearExportPassphrase()
        pendingExportPassphrase = passphrase
    }

    /**
     * Writes the backup to the Uri the user picked.
     *
     * Exporting hands the journal to a file on shared storage, so it clears the
     * same sensitive-change gate as a history clear. That gate can *park* the
     * request behind the Guardian PIN, which means the passphrase has to outlive
     * this call: the running action reads it from [pendingExportPassphrase] at the
     * moment it fires, because zeroing it on return would hand the parked export
     * a key full of spaces.
     */
    fun exportTo(uri: Uri) {
        if (pendingExportPassphrase == null) {
            _status.value = "Choose a backup passphrase first"
            return
        }
        if (_backupBusy.value) return
        _backupBusy.value = true
        exportSettled = false
        viewModelScope.launch {
            gateHolder.requestSensitiveChange {
                val passphrase = pendingExportPassphrase
                if (passphrase == null) {
                    finishExport("That backup was cancelled")
                } else {
                    runExport(uri, passphrase)
                }
            }
        }
    }

    private suspend fun runExport(uri: Uri, passphrase: CharArray) {
        backupRepository.createBackup(uri, passphrase)
            .onSuccess { summary -> finishExport(exportSuccessCopy(summary)) }
            .onFailure { error -> finishExport(describeBackupError(error)) }
    }

    private fun finishExport(message: String) {
        _status.value = message
        _backupBusy.value = false
        exportSettled = true
        clearExportPassphrase()
    }

    private fun exportSuccessCopy(summary: dev.gamblock.core.model.BackupSummary): String =
        "Backup saved: ${summary.journalEntries} journal entries, " +
            "${summary.customExceptions} custom exceptions"

    /** The user picked a file; the passphrase comes next. */
    fun stageRestoreUri(uri: Uri) {
        pendingRestoreUri = uri
    }

    /**
     * Opens the chosen file with the given passphrase and, on success, raises the
     * confirmation preview. Nothing is written until [confirmRestore].
     *
     * Reading is not put behind the Guardian gate: it writes nothing, and refusing
     * to show somebody their own backup behind a PIN prompt protects nobody.
     */
    fun openRestore(passphrase: CharArray) {
        val uri = pendingRestoreUri
        pendingRestoreUri = null
        if (uri == null) {
            _status.value = "Choose a backup file first"
            return
        }
        if (_backupBusy.value) return
        _backupBusy.value = true
        viewModelScope.launch {
            try {
                backupRepository.prepareRestore(uri, passphrase)
                    .onSuccess { prepared ->
                        _pendingRestore.value = prepared
                        _backupBusy.value = false
                    }
                    .onFailure { error ->
                        _status.value = describeBackupError(error)
                        _backupBusy.value = false
                    }
            } finally {
                passphrase.fill(' ')
            }
        }
    }

    /**
     * The user confirmed the preview, so the swap happens now.
     *
     * A restore rewrites the clean streak and the protection configuration, so it
     * clears the sensitive-change gate like any other protected change. The parked
     * action keeps a reference to the same validated payload the user was shown, so
     * what gets applied is exactly what the confirmation described.
     */
    fun confirmRestore() {
        val prepared = _pendingRestore.value ?: return
        if (_backupBusy.value) return
        _backupBusy.value = true
        restoreSettled = false
        viewModelScope.launch {
            gateHolder.requestSensitiveChange {
                backupRepository.applyRestore(prepared)
                    .onSuccess { summary ->
                        finishRestore(
                            "Restored: ${'$'}{summary.journalEntries} journal entries, " +
                                "${'$'}{summary.customExceptions} custom exceptions",
                        )
                    }
                    .onFailure { error -> finishRestore(describeBackupError(error)) }
            }
        }
    }

    private fun finishRestore(message: String) {
        _status.value = message
        _pendingRestore.value = null
        _backupBusy.value = false
        restoreSettled = true
    }

    fun dismissRestorePreview() {
        _pendingRestore.value = null
        pendingRestoreUri = null
    }

    override fun onCleared() {
        // Never leave a passphrase in memory once the screen is gone.
        clearExportPassphrase()
        super.onCleared()
    }

    private fun clearExportPassphrase() {
        pendingExportPassphrase?.fill(' ')
        pendingExportPassphrase = null
    }

    /**
     * Turns a failure into something a person can act on. The repository publishes
     * the copy so the wording lives next to the exceptions it describes, and no
     * passphrase or payload content is ever included in it.
     */
    private fun describeBackupError(error: Throwable): String {
        var cause: Throwable? = error
        while (cause != null) {
            RecoveryBackupRepository.USER_FACING[cause.javaClass.name]?.let { return it }
            cause = cause.cause
        }
        // The passphrase policy throws IllegalArgumentException. The dialog should
        // have caught it first, but a raw exception must never reach the screen.
        if (error is IllegalArgumentException || error is IllegalStateException) {
            return "That passphrase is not usable. Use at least 8 characters."
        }
        return "Backup could not be completed. Nothing on this device was changed."
    }
}