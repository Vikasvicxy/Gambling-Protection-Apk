package dev.gamblock.feature.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.model.ProtectionMode
import dev.gamblock.core.model.ProtectionSchedule
import dev.gamblock.data.preferences.SettingsRepository
import dev.gamblock.data.preferences.SettingsState
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val logger: ShieldLogger,
) : ViewModel() {

    val uiState: StateFlow<SettingsState> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())

    fun setProtectionMode(mode: ProtectionMode) {
        viewModelScope.launch {
            settingsRepository.setProtectionMode(mode)
        }
    }

    fun setVpnEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setVpnEnabled(enabled)
        }
    }

    fun setSchedule(schedule: ProtectionSchedule) {
        viewModelScope.launch {
            settingsRepository.setSchedule(schedule)
            logger.i(Logs.UI, "schedule saved: ${schedule.startHour}:${schedule.startMinute} - ${schedule.endHour}:${schedule.endMinute}")
        }
    }

    fun setGreetName(name: String) {
        viewModelScope.launch {
            settingsRepository.setGreetName(name)
        }
    }
}