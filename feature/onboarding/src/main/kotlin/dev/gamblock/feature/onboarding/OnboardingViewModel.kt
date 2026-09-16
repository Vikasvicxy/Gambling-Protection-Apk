package dev.gamblock.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gamblock.data.preferences.OnboardingRepository
import dev.gamblock.data.preferences.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val onboardingRepository: OnboardingRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val alreadyCompleted: StateFlow<Boolean> = onboardingRepository.completed
        .map { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val personalizationName: StateFlow<String> = settingsRepository.settings
        .map { it.greetPersonalizationName }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun complete() {
        viewModelScope.launch {
            onboardingRepository.markComplete()
        }
    }
}