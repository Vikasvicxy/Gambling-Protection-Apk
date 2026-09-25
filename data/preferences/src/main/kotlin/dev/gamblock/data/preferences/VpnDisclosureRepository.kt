package dev.gamblock.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.ShieldLogger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class VpnDisclosureState(
    val version: Int = VpnDisclosurePolicy.CURRENT_VERSION,
    val accepted: Boolean = false,
)

object VpnDisclosurePolicy {
    const val CURRENT_VERSION = 1

    fun accepted(storedVersion: Int?): Boolean = storedVersion == CURRENT_VERSION

    fun action(storedVersion: Int?): VpnDisclosureAction =
        if (accepted(storedVersion)) VpnDisclosureAction.ALLOW else VpnDisclosureAction.SHOW

    fun state(storedVersion: Int?): VpnDisclosureState = VpnDisclosureState(
        version = CURRENT_VERSION,
        accepted = accepted(storedVersion),
    )
}

enum class VpnDisclosureAction {
    SHOW,
    ALLOW,
}

@Singleton
class VpnDisclosureRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatchersProvider,
    private val logger: ShieldLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val dataStore: DataStore<Preferences> get() = context.disclosureStore
    private val _state = MutableStateFlow(VpnDisclosureState())
    val state: StateFlow<VpnDisclosureState> = _state.asStateFlow()

    init {
        scope.launch {
            dataStore.data
                .catch { error ->
                    logger.w(TAG, "disclosure prefs read error: ${error.message}")
                    emit(emptyPreferences())
                }
                .collect { preferences ->
                    _state.value = VpnDisclosurePolicy.state(preferences[ACCEPTED_VERSION])
                }
        }
    }

    suspend fun isAccepted(): Boolean = withContext(dispatchers.io) {
        val storedVersion = dataStore.data.catch { emptyPreferences() }.first()[ACCEPTED_VERSION]
        VpnDisclosurePolicy.accepted(storedVersion).also {
            _state.value = VpnDisclosurePolicy.state(storedVersion)
        }
    }

    suspend fun accept() = withContext(dispatchers.io) {
        dataStore.edit { preferences ->
            preferences[ACCEPTED_VERSION] = VpnDisclosurePolicy.CURRENT_VERSION
        }
        _state.value = VpnDisclosureState(
            version = VpnDisclosurePolicy.CURRENT_VERSION,
            accepted = true,
        )
    }

    suspend fun clear() = withContext(dispatchers.io) {
        dataStore.edit { preferences -> preferences.remove(ACCEPTED_VERSION) }
        _state.value = VpnDisclosureState()
    }

    companion object {
        private val ACCEPTED_VERSION = intPreferencesKey("vpn_disclosure_accepted_version")
        private const val TAG = "VpnDisclosureRepository"
    }
}

@Singleton
class ReviewerModeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatchersProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val dataStore: DataStore<Preferences> get() = context.reviewerModeStore
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    init {
        scope.launch {
            dataStore.data
                .catch { emit(emptyPreferences()) }
                .collect { preferences -> _enabled.value = preferences[ENABLED] == true }
        }
    }

    suspend fun setEnabled(enabled: Boolean) = withContext(dispatchers.io) {
        dataStore.edit { preferences -> preferences[ENABLED] = enabled }
        _enabled.value = enabled
    }

    fun isDemoBlockedHost(host: String): Boolean = ReviewerModePolicy.isBlockedHost(host)

    companion object {
        private val ENABLED = booleanPreferencesKey("reviewer_demo_enabled")
    }
}

object ReviewerModePolicy {
    val blockedHosts: Set<String> = setOf(
        "reviewer-blocked.test",
        "demo-casino.test",
        "demo-sportsbook.test",
    )

    val allowedHosts: Set<String> = setOf(
        "reviewer-allowed.test",
        "safe-example.test",
    )

    fun isBlockedHost(host: String): Boolean = host.lowercase().removeSuffix(".") in blockedHosts
}

private val Context.disclosureStore: DataStore<Preferences> by preferencesDataStore(name = "shield_vpn_disclosure")
private val Context.reviewerModeStore: DataStore<Preferences> by preferencesDataStore(name = "shield_reviewer_mode")
