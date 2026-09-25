package dev.gamblock.feature.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamblock.data.preferences.SettingsRepository
import dev.gamblock.data.repository.ActivityEventRepository
import dev.gamblock.data.repository.BlockEventRepository
import dev.gamblock.data.repository.FalsePositiveReportRepository
import dev.gamblock.protection.tamper.LockscreenAuthGate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val blockEventRepository: BlockEventRepository,
    private val activityEventRepository: ActivityEventRepository,
    private val falsePositiveReportRepository: FalsePositiveReportRepository,
    private val settingsRepository: SettingsRepository,
    val lockscreenGate: LockscreenAuthGate,
) : ViewModel() {

    val requireAuthBeforeClearHistory: StateFlow<Boolean> = settingsRepository.settings
        .map { it.requireAuthBeforeClearHistory }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _gateMessage = MutableStateFlow<String?>(null)
    val gateMessage: StateFlow<String?> = _gateMessage.asStateFlow()

    val recentBlocked: StateFlow<List<dev.gamblock.core.model.BlockAttemptGroup>> =
        blockEventRepository.observeRecent(50).stateIn(
            viewModelScope, SharingStarted.Eagerly, emptyList(),
        )

    val totalAttempts: StateFlow<Int> =
        blockEventRepository.observeTotalAttempts().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val activity = activityEventRepository.observeRecent(30)

    val submittedReports = falsePositiveReportRepository.observeRecent(20)

    fun clearHistory() {
        viewModelScope.launch {
            blockEventRepository.clear()
        }
    }

    fun showGateMessage(message: String) {
        _gateMessage.value = message
    }

    fun clearGateMessage() {
        if (_gateMessage.value != null) _gateMessage.value = null
    }
}