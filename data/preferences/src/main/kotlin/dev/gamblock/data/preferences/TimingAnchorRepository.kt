package dev.gamblock.data.preferences

import android.content.Context
import android.os.Build
import android.os.SystemClock
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistent anchor used to detect and compensate for user-initiated clock changes.
 *
 * On every app resume the elapsedRealtime-to-wallClock delta is compared against
 * the persisted anchor. A shift larger than [THRESHOLD_MS] is surfaced as a
 * safety alert; the commitment engine also uses the persisted bootCount + elapsed
 * as the authoritative accumulation source.
 */
@Serializable
data class TimingAnchor(
    val firstRunEpochMs: Long = 0L,
    val lastResumeEpochMs: Long = 0L,
    val lastResumeElapsedMs: Long = 0L,
    val lastKnownBootCount: Int = 0,
    val elapsedAccumulatedMs: Long = 0L,
    val totalWallShiftsDetected: Int = 0,
)

@Singleton
class TimingAnchorRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wallClock: WallClock,
    private val dispatchers: DispatchersProvider,
    private val logger: ShieldLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val dataStore: DataStore<Preferences> get() = context.timingStore

    private val _anchor = MutableStateFlow(TimingAnchor())
    val anchor: StateFlow<TimingAnchor> = _anchor.asStateFlow()

    init {
        scope.launch {
            dataStore.data
                .catch { e ->
                    logger.w(TAG, "timing anchor read error: ${e.message}")
                    emit(emptyPreferences())
                }
                .map { prefs ->
                    prefs[ANCHOR_JSON]?.let {
                        try {
                            Json.decodeFromString<TimingAnchor>(it)
                        } catch (_: Exception) {
                            TimingAnchor()
                        }
                    } ?: TimingAnchor()
                }
                .collect { _anchor.value = it }
        }
    }

    /** Returns first-run epoch, seeding on first call. */
    suspend fun ensureFirstRunEpochMs(): Long = withContext(dispatchers.io) {
        val current = _anchor.value
        if (current.firstRunEpochMs > 0L) return@withContext current.firstRunEpochMs
        val now = wallClock.nowEpochMillis()
        val updated = current.copy(firstRunEpochMs = now)
        saveAnchor(updated)
        updated.firstRunEpochMs
    }

    /** Computes accumulated elapsed-ms delta for commitment logic on each app foreground entry. */
    suspend fun onAppResume(
        elapsedMs: Long = SystemClock.elapsedRealtime(),
        bootCount: Int = getBootCount(),
    ): TimingAnchor = withContext(dispatchers.io) {
        val prev = _anchor.value
        val deltaElapsed = elapsedMs - prev.lastResumeElapsedMs
        val clockShifted = prev.lastResumeEpochMs > 0L && kotlin.math.abs(
            (wallClock.nowEpochMillis() - prev.lastResumeEpochMs) - deltaElapsed,
        ) > THRESHOLD_MS

        val safeDelta = if (deltaElapsed in 0..MAX_REASONABLE_ELAPSED) deltaElapsed else 0L
        val bootChanged = bootCount != prev.lastKnownBootCount
        val newElapsed = prev.elapsedAccumulatedMs + if (bootChanged) 0L else safeDelta

        val updated = TimingAnchor(
            firstRunEpochMs = prev.firstRunEpochMs.ifZero { wallClock.nowEpochMillis() },
            lastResumeEpochMs = wallClock.nowEpochMillis(),
            lastResumeElapsedMs = elapsedMs,
            lastKnownBootCount = bootCount,
            elapsedAccumulatedMs = newElapsed,
            totalWallShiftsDetected = prev.totalWallShiftsDetected + if (clockShifted) 1 else 0,
        )
        saveAnchor(updated)
        if (clockShifted) {
            logger.w(TAG, "wall-clock shift detected (total: ${updated.totalWallShiftsDetected}); elapsed kept at ${updated.elapsedAccumulatedMs}ms")
        }
        updated
    }

    suspend fun reset() = withContext(dispatchers.io) {
        saveAnchor(TimingAnchor())
    }

    private suspend fun saveAnchor(anchor: TimingAnchor) {
        dataStore.edit { prefs ->
            prefs[ANCHOR_JSON] = Json.encodeToString(anchor)
        }
    }

    private fun Long.ifZero(block: () -> Long): Long = if (this == 0L) block() else this

    private fun getBootCount(): Int = try {
        @Suppress("DEPRECATION")
        Build.SERIAL.hashCode()
    } catch (_: Exception) {
        0
    }

    companion object {
        private const val TAG = "TimingAnchorRepo"
        private val ANCHOR_JSON = stringPreferencesKey("timing_anchor_json")
        private const val THRESHOLD_MS = 5 * 60 * 1000L
        private const val MAX_REASONABLE_ELAPSED = 12 * 60 * 60 * 1000L
    }
}

private val Context.timingStore: DataStore<Preferences> by preferencesDataStore(name = "shield_timing")