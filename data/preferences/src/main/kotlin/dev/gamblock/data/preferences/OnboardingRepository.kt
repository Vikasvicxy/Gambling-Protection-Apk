package dev.gamblock.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.ShieldLogger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tracks onboarding completion. Phase 1 keeps a single [completed] flag per install.
 * A richer step-based tracker can layer on top without schema migration.
 */
@Singleton
class OnboardingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatchersProvider,
    private val logger: ShieldLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val dataStore: DataStore<Preferences> get() = context.onboardingStore

    private val _completed = MutableStateFlow(false)
    val completed: StateFlow<Boolean> = _completed.asStateFlow()

    init {
        scope.launch {
            dataStore.data
                .catch { e ->
                    logger.w(TAG, "onboarding prefs read error: ${e.message}")
                    emit(emptyPreferences())
                }
                .collect { prefs -> _completed.value = prefs[COMPLETED] == true }
        }
    }

    suspend fun markComplete() = withContext(dispatchers.io) {
        dataStore.edit { it[COMPLETED] = true }
        _completed.value = true
        logger.i(TAG, "onboarding marked complete")
    }

    suspend fun reset() = withContext(dispatchers.io) {
        dataStore.edit { it.remove(COMPLETED) }
        _completed.value = false
    }

    suspend fun isStepComplete(step: OnboardingStep): Boolean = withContext(dispatchers.io) {
        val prefs = dataStore.data.catch { emptyPreferences() }.first()
        prefs[stepKey(step)] == true
    }

    suspend fun markStepComplete(step: OnboardingStep) = withContext(dispatchers.io) {
        dataStore.edit { it[stepKey(step)] = true }
    }

    private fun stepKey(step: OnboardingStep) = booleanPreferencesKey("onboarding_step_${step.name}")

    companion object {
        private const val TAG = "OnboardingRepository"
        private val COMPLETED = booleanPreferencesKey("onboarding_complete")
    }
}

enum class OnboardingStep {
    WELCOME,
    CONSENT_REQUIRED,
    TARGET_SELECT,
    EXPLANATION_READ,
    TERMS_ACCEPTED,
    PROTECTION_SETUP,
}

private val Context.onboardingStore: DataStore<Preferences> by preferencesDataStore(name = "shield_onboarding")