package dev.gamblock.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamblock.core.common.clock.WallClock
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.model.ProtectionMode
import dev.gamblock.core.model.ProtectionSchedule
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SettingsState(
    val vpnEnabled: Boolean = false,
    val protectionMode: ProtectionMode = ProtectionMode.SELF_PROTECTION,
    val schedule: ProtectionSchedule = ProtectionSchedule(),
    val greetPersonalizationName: String = "",
    val hapticsEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val requireAuthBeforeDisable: Boolean = false,
    val requireAuthBeforeClearHistory: Boolean = false,
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wallClock: WallClock,
    private val dispatchers: DispatchersProvider,
    private val logger: ShieldLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val dataStore: DataStore<Preferences> get() = context.settingsStore

    private val _settings = MutableStateFlow(SettingsState())
    val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    init {
        scope.launch {
            dataStore.data
                .catch { e ->
                    logger.w(TAG, "settings read error: ${e.message}")
                    emit(emptyPreferences())
                }
                .map { prefs ->
                    prefs[SETTINGS_JSON]?.let { json ->
                        try {
                            Json.decodeFromString<SettingsState>(json)
                        } catch (_: Exception) {
                            SettingsState()
                        }
                    } ?: SettingsState()
                }
                .collect { _settings.value = it }
        }
    }

    suspend fun update(transform: (SettingsState) -> SettingsState) = withContext(dispatchers.io) {
        val prev = _settings.value
        val next = transform(prev)
        dataStore.edit { prefs ->
            prefs[SETTINGS_JSON] = Json.encodeToString(next)
        }
        _settings.update { next }
        if (next.schedule != prev.schedule) {
            logger.i(TAG, "schedule updated: enabled=${next.schedule.enabled}")
        }
    }

    suspend fun isScheduleActive(nowEpochMs: Long): Boolean = withContext(dispatchers.io) {
        _settings.value.schedule.isSystemCurrentlyActive(nowEpochMs)
    }

    suspend fun getSettingsSnapshot(): SettingsState = withContext(dispatchers.io) {
        _settings.value
    }

    suspend fun setVpnEnabled(enabled: Boolean) = update { it.copy(vpnEnabled = enabled) }

    suspend fun setProtectionMode(mode: ProtectionMode) = update { it.copy(protectionMode = mode) }

    suspend fun setSchedule(schedule: ProtectionSchedule) = update { it.copy(schedule = schedule) }

    suspend fun setGreetName(name: String) = update { it.copy(greetPersonalizationName = name) }

    suspend fun setHaptics(enabled: Boolean) = update { it.copy(hapticsEnabled = enabled) }

    suspend fun setNotifications(enabled: Boolean) = update { it.copy(notificationsEnabled = enabled) }

    suspend fun setRequireAuthBeforeDisable(enabled: Boolean) = update { it.copy(requireAuthBeforeDisable = enabled) }

    suspend fun setRequireAuthBeforeClearHistory(enabled: Boolean) = update { it.copy(requireAuthBeforeClearHistory = enabled) }

    companion object {
        private val SETTINGS_JSON = stringPreferencesKey("settings_json")
        private const val TAG = "SettingsRepository"
    }
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "shield_settings")