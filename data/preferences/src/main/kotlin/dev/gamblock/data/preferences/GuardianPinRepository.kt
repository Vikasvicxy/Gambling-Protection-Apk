package dev.gamblock.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamblock.core.common.clock.WallClock
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.model.GuardianPinAttempt
import dev.gamblock.core.model.GuardianPinHash
import dev.gamblock.core.model.GuardianPinHasher
import dev.gamblock.core.model.GuardianPinPolicy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GuardianPinRecord(
    val hash: GuardianPinHash,
    val attempt: GuardianPinAttempt = GuardianPinAttempt(),
)

sealed interface GuardianPinUnlockState {
    data object NotConfigured : GuardianPinUnlockState
    data class Locked(val remainingMs: Long) : GuardianPinUnlockState
    data class Wrong(val remainingAttempts: Int) : GuardianPinUnlockState
    data object Unlocked : GuardianPinUnlockState
}

/**
 * Optional 4-digit Guardian PIN, independent of the device lock. The PIN is never
 * stored: only a PBKDF2-HMAC-SHA256 hash with a random salt lives in DataStore,
 * and repeated failures trigger a short local lockout.
 */
@Singleton
class GuardianPinRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wallClock: WallClock,
    private val dispatchers: DispatchersProvider,
    private val logger: ShieldLogger,
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val dataStore: DataStore<Preferences> get() = context.guardianStore
    private val json = Json { ignoreUnknownKeys = true }

    private val _isConfigured = MutableStateFlow(false)
    val isConfigured: StateFlow<Boolean> = _isConfigured.asStateFlow()

    private val _unlockState = MutableStateFlow<GuardianPinUnlockState>(GuardianPinUnlockState.NotConfigured)
    val unlockState: StateFlow<GuardianPinUnlockState> = _unlockState.asStateFlow()

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    init {
        scope.launch { observe() }
    }

    private suspend fun observe() {
        dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs -> prefs[KEY_RECORD]?.let { decode(it) } }
            .collect { record ->
                _isConfigured.value = record != null
                if (record == null) {
                    _unlockState.value = GuardianPinUnlockState.NotConfigured
                } else {
                    val now = wallClock.nowEpochMillis()
                    _unlockState.value = if (GuardianPinPolicy.isLocked(record.attempt, now)) {
                        GuardianPinUnlockState.Locked(GuardianPinPolicy.remainingLockMs(record.attempt, now))
                    } else {
                        GuardianPinUnlockState.Wrong(
                            remainingAttempts = (GuardianPinHasher.MAX_FAILED_ATTEMPTS - record.attempt.failedAttempts)
                                .coerceAtLeast(0),
                        )
                    }
                }
            }
    }

    private fun decode(raw: String): GuardianPinRecord? =
        runCatching { json.decodeFromString<GuardianPinRecord>(raw) }.getOrNull()

    suspend fun setPin(pin: String): Result<Unit> = withContext(dispatchers.io) {
        if (!GuardianPinHasher.isValidFormat(pin)) {
            return@withContext Result.failure(IllegalArgumentException("Guardian PIN must be ${GuardianPinHasher.PIN_LENGTH} digits"))
        }
        val record = GuardianPinRecord(hash = GuardianPinHasher.hash(pin))
        runCatching {
            dataStore.edit { it[KEY_RECORD] = json.encodeToString(record) }
            _unlockState.value = GuardianPinUnlockState.Unlocked
            _unlocked.value = true
        }.onFailure { logger.w(TAG, "pin save failed: ${it.message}") }
    }

    suspend fun verify(pin: String): Boolean = withContext(dispatchers.io) {
        val raw = dataStore.data.map { it[KEY_RECORD] }.first()
        val record = raw?.let(::decode)
        if (record == null) {
            _unlockState.value = GuardianPinUnlockState.NotConfigured
            return@withContext false
        }
        val now = wallClock.nowEpochMillis()
        if (GuardianPinPolicy.isLocked(record.attempt, now)) {
            _unlockState.value = GuardianPinUnlockState.Locked(GuardianPinPolicy.remainingLockMs(record.attempt, now))
            return@withContext false
        }
        if (!GuardianPinHasher.verify(pin, record.hash)) {
            val next = GuardianPinPolicy.registerFailure(record.attempt, now)
            dataStore.edit { it[KEY_RECORD] = json.encodeToString(record.copy(attempt = next)) }
            _unlockState.value = if (GuardianPinPolicy.isLocked(next, now)) {
                GuardianPinUnlockState.Locked(GuardianPinPolicy.remainingLockMs(next, now))
            } else {
                GuardianPinUnlockState.Wrong(
                    (GuardianPinHasher.MAX_FAILED_ATTEMPTS - next.failedAttempts).coerceAtLeast(0),
                )
            }
            return@withContext false
        }
        dataStore.edit { it[KEY_RECORD] = json.encodeToString(record.copy(attempt = GuardianPinPolicy.registerSuccess())) }
        _unlockState.value = GuardianPinUnlockState.Unlocked
        _unlocked.value = true
        true
    }

    suspend fun clearPin() = withContext(dispatchers.io) {
        dataStore.edit { it.remove(KEY_RECORD) }
        _unlocked.value = false
        _unlockState.value = GuardianPinUnlockState.NotConfigured
    }

    suspend fun lock() = withContext(dispatchers.io) {
        _unlocked.value = false
    }

    companion object {
        private val KEY_RECORD = stringPreferencesKey("guardian_pin_record")
        private const val TAG = "GuardianPinRepository"
    }
}

private val Context.guardianStore: DataStore<Preferences> by preferencesDataStore(name = "shield_guardian")
