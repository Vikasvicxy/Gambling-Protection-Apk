package dev.gamblock.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamblock.data.preferences.SettingsRepository
import dev.gamblock.data.preferences.SettingsState
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsState> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setHaptics(enabled) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setNotifications(enabled) }
    }

    fun setGreetName(name: String) {
        viewModelScope.launch { settingsRepository.setGreetName(name) }
    }
}