package dev.gamblock.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamblock.core.model.CustomDomainException
import dev.gamblock.data.blocklist.CustomDomainExceptionRepository
import dev.gamblock.data.preferences.SettingsRepository
import dev.gamblock.data.preferences.SettingsState
import dev.gamblock.data.repository.ProtectionGateHolder
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
    private val gateHolder: ProtectionGateHolder,
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

    /**
     * Whitelisting a domain is a destructive action: it creates a hole in the
     * filter, so it goes through the shared gate (Fortress window, then PIN)
     * instead of writing directly.
     */
    fun addException(domain: String, durationHours: Long?) {
        val target = domain.trim()
        viewModelScope.launch {
            gateHolder.requestSensitiveChange {
                val result = exceptionRepository.addException(target, durationHours)
                _exceptionMessage.value = result.fold(
                    onSuccess = { "Added exception for $it" },
                    onFailure = { "Could not add: ${it.message ?: "invalid domain"}" },
                )
            }
        }
    }

    fun removeException(id: Long) {
        viewModelScope.launch {
            gateHolder.requestSensitiveChange { exceptionRepository.removeException(id) }
        }
    }

    /**
     * Turning the device-lock or clear-history requirements off weakens the
     * accountability guarantees, so it is gated too.
     */
    fun setRequireAuthBeforeDisable(enabled: Boolean) {
        viewModelScope.launch {
            gateHolder.requestSensitiveChange { settingsRepository.setRequireAuthBeforeDisable(enabled) }
        }
    }

    fun setRequireAuthBeforeClearHistory(enabled: Boolean) {
        viewModelScope.launch {
            gateHolder.requestSensitiveChange { settingsRepository.setRequireAuthBeforeClearHistory(enabled) }
        }
    }

    fun clearExceptionMessage() {
        _exceptionMessage.value = null
    }
}