package dev.gamblock.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamblock.core.model.CustomDomainException
import dev.gamblock.data.blocklist.CustomDomainExceptionRepository
import dev.gamblock.data.preferences.SettingsRepository
import dev.gamblock.data.preferences.SettingsState
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val exceptionRepository: CustomDomainExceptionRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsState> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())

    val exceptions: StateFlow<List<CustomDomainException>> = exceptionRepository.observeExceptions()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Transient validation/status message shown inline in the exceptions card. */
    private val _exceptionMessage = MutableStateFlow<String?>(null)
    val exceptionMessage: StateFlow<String?> = _exceptionMessage.asStateFlow()

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setHaptics(enabled) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setNotifications(enabled) }
    }

    fun setGreetName(name: String) {
        viewModelScope.launch { settingsRepository.setGreetName(name) }
    }

    fun addException(domain: String, durationHours: Long?) {
        viewModelScope.launch {
            val result = exceptionRepository.addException(domain.trim(), durationHours)
            _exceptionMessage.value = result.fold(
                onSuccess = { "Added exception for $it" },
                onFailure = { "Could not add: ${it.message ?: "invalid domain"}" },
            )
        }
    }

    fun removeException(id: Long) {
        viewModelScope.launch { exceptionRepository.removeException(id) }
    }

    fun clearExceptionMessage() {
        _exceptionMessage.value = null
    }
}