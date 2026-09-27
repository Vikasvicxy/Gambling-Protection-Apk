package dev.gamblock.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.model.AppExclusionFilter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The user's per-app DNS exemptions, persisted locally.
 *
 * Stored as one serialised blob rather than one DataStore key per package so a
 * multi-select edit is a single atomic write: adding ten banking apps at once
 * either lands completely or not at all, which matters because a partially
 * applied exemption set is exactly the state a user cannot reason about.
 *
 * Deliberately local-only. Exempting an app weakens protection, so this list
 * never syncs anywhere and is never included in a backup payload.
 */
@Serializable
data class AppExclusionState(
    val excludedPackages: Set<String> = emptySet(),
)

@Singleton
class AppExclusionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatchersProvider,
    private val logger: ShieldLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val dataStore: DataStore<Preferences> get() = context.appExclusionStore

    private val _state = MutableStateFlow(AppExclusionState())
    val state: StateFlow<AppExclusionState> = _state.asStateFlow()

    /** Convenience accessor for the hot path in the VPN service. */
    val excludedPackages: Set<String> get() = _state.value.excludedPackages

    init {
        scope.launch {
            dataStore.data
                .catch { e ->
                    logger.w(TAG, "app exclusion read error: ${e.message}")
                    emit(emptyPreferences())
                }
                .map { prefs ->
                    prefs[EXCLUSIONS_JSON]?.let { json ->
                        try {
                            Json.decodeFromString<AppExclusionState>(json)
                        } catch (_: Exception) {
                            logger.w(TAG, "app exclusion payload unreadable; starting empty")
                            AppExclusionState()
                        }
                    } ?: AppExclusionState()
                }
                .collect { _state.value = it }
        }
    }

    private suspend fun update(transform: (AppExclusionState) -> AppExclusionState) =
        withContext(dispatchers.io) {
            val next = transform(_state.value)
            dataStore.edit { prefs ->
                prefs[EXCLUSIONS_JSON] = Json.encodeToString(next)
            }
            _state.update { next }
            logger.i(TAG, "app exclusions updated: ${next.excludedPackages.size} package(s)")
        }

    /**
     * Replaces the whole set, discarding anything malformed.
     *
     * Validation happens on write rather than only on read, so a bad value can
     * never sit in storage waiting to be replayed into the VPN builder.
     */
    suspend fun setExclusions(packages: Collection<String>) = update { current ->
        val cleaned = packages
            .map { it.trim() }
            .filter { AppExclusionFilter.isValidPackageName(it) }
            .toSet()
        current.copy(excludedPackages = cleaned)
    }

    suspend fun add(packageName: String) {
        val pkg = packageName.trim()
        if (!AppExclusionFilter.isValidPackageName(pkg)) {
            logger.w(TAG, "refusing to store malformed package name: ${pkg.take(64)}")
            return
        }
        setExclusions(_state.value.excludedPackages + pkg)
    }

    suspend fun remove(packageName: String) =
        setExclusions(_state.value.excludedPackages - packageName.trim())

    suspend fun toggle(packageName: String, excluded: Boolean) {
        if (excluded) add(packageName) else remove(packageName)
    }

    suspend fun clear() = setExclusions(emptySet())

    suspend fun snapshot(): AppExclusionState = withContext(dispatchers.io) { _state.value }

    companion object {
        private val EXCLUSIONS_JSON = stringPreferencesKey("app_exclusions_json")
        private const val TAG = "AppExclusionRepository"
    }
}

private val Context.appExclusionStore: DataStore<Preferences> by
    preferencesDataStore(name = "shield_app_exclusions")
