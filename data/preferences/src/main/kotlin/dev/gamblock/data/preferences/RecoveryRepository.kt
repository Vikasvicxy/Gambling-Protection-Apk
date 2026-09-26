package dev.gamblock.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamblock.core.common.clock.WallClock
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.database.ShieldDatabase
import dev.gamblock.core.database.entity.CravingJournalEntity
import dev.gamblock.core.model.FinancialProfile
import dev.gamblock.core.model.FortressWindow
import dev.gamblock.core.model.RecoveryCurrency
import dev.gamblock.core.model.RecoveryMetrics
import dev.gamblock.core.model.RecoveryCalculator
import dev.gamblock.core.model.WeekDay
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class UrgeJournalEntry(
    val id: Long = 0L,
    val occurredAtEpochMs: Long,
    val intensity: Int,
    val triggers: List<String>,
    val note: String,
    val blockedDomain: String? = null,
)

data class CravingInsightsSnapshot(
    val entries: List<UrgeJournalEntry> = emptyList(),
    val totalEntries: Int = 0,
    val peakWindowLabel: String? = null,
    val topTrigger: String? = null,
    val averageIntensity: Double = 0.0,
    val triggerCounts: Map<String, Int> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = entries.isEmpty()

    val hasData: Boolean
        get() = totalEntries > 0
}

@Serializable
data class FortressSettings(
    val enabled: Boolean = false,
    val windows: List<FortressWindow> = emptyList(),
)

data class FortressSnapshot(
    val enabled: Boolean = false,
    val windows: List<FortressWindow> = emptyList(),
    val lockedDown: Boolean = false,
    val activeWindowLabel: String? = null,
    val nextWindowLabel: String? = null,
) {
    val hasWindows: Boolean
        get() = windows.isNotEmpty()
}

/**
 * All behavioural-recovery state: financial profile + clean streak, the urge
 * journal with local peak-time analytics, and recurring Fortress windows.
 * Journal rows live in Room; the small scalar settings live in DataStore.
 * Fully local, no network.
 */
@Singleton
class RecoveryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wallClock: WallClock,
    private val dispatchers: DispatchersProvider,
    private val logger: ShieldLogger,
    private val database: ShieldDatabase,
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val dataStore: DataStore<Preferences> get() = context.recoveryStore
    private val journalDao get() = database.cravingJournalDao()
    private val json = Json { ignoreUnknownKeys = true }

    private val _profile = MutableStateFlow(FinancialProfile())
    val profile: StateFlow<FinancialProfile> = _profile.asStateFlow()

    private val _journal = MutableStateFlow<List<UrgeJournalEntry>>(emptyList())
    val journal: StateFlow<List<UrgeJournalEntry>> = _journal.asStateFlow()

    private val _fortress = MutableStateFlow(FortressSnapshot())
    val fortress: StateFlow<FortressSnapshot> = _fortress.asStateFlow()

    private val clockTick = MutableStateFlow(0L)

    val metrics: StateFlow<RecoveryMetrics> = combine(_profile, clockTick) { profile, _ ->
        RecoveryCalculator.metrics(profile, wallClock.nowEpochMillis())
    }.stateInScope(RecoveryMetrics())

    init {
        scope.launch { observeProfile() }
        scope.launch { observeJournal() }
        scope.launch { observeFortress() }
        scope.launch { tickClock() }
    }

    private suspend fun tickClock() {
        while (true) {
            kotlinx.coroutines.delay(CLOCK_TICK_MS)
            clockTick.value = wallClock.nowEpochMillis()
            _fortress.value = computeFortress(
                FortressSettings(enabled = _fortress.value.enabled, windows = _fortress.value.windows),
            )
        }
    }

    private fun <T> Flow<T>.stateInScope(initial: T): StateFlow<T> =
        stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = initial,
        )

    private suspend fun observeProfile() {
        dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs ->
                val spend = prefs[KEY_WEEKLY_SPEND] ?: 0L
                val currency = prefs[KEY_CURRENCY] ?: RecoveryCurrency.default.code
                val start = prefs[KEY_RECOVERY_START]
                FinancialProfile(
                    weeklySpendMinor = FinancialProfile.sanitize(spend),
                    currencyCode = currency,
                    recoveryStartEpochMs = start,
                )
            }
            .collect { _profile.value = it }
    }

    private suspend fun observeJournal() {
        journalDao.observeAll()
            .catch { error ->
                logger.w(Logs.DB, "Craving journal read failed", error)
                emit(emptyList())
            }
            .collect { rows ->
                _journal.value = rows.map { row -> row.toEntry() }
            }
    }

    private suspend fun observeFortress() {
        dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs ->
                val enabled = prefs[KEY_FORTRESS_ENABLED] ?: false
                val windows = prefs[KEY_FORTRESS_WINDOWS]?.let { raw ->
                    runCatching { json.decodeFromString<List<FortressWindow>>(raw) }
                        .getOrDefault(emptyList())
                        .map(FortressWindow::sanitize)
                } ?: defaultWindows()
                FortressSettings(enabled = enabled, windows = windows)
            }
            .collect { settings -> _fortress.value = computeFortress(settings) }
    }

    private fun computeFortress(settings: FortressSettings): FortressSnapshot {
        val now = wallClock.nowEpochMillis()
        val status = dev.gamblock.core.model.FortressPolicy.status(settings.windows, now)
        return FortressSnapshot(
            enabled = settings.enabled,
            windows = settings.windows,
            lockedDown = status.lockedDown,
            activeWindowLabel = status.activeWindow?.let { windowLabel(it) },
            nextWindowLabel = status.nextWindow?.let { windowLabel(it) },
        )
    }

    private fun windowLabel(window: FortressWindow): String =
        if (window.label.isBlank()) "${window.daysLabel} ${window.timeRangeLabel}" else window.label

    private fun defaultWindows(): List<FortressWindow> = listOf(
        FortressWindow.overnightDaily(startHour = 23, endHour = 5),
    )

    suspend fun setWeeklySpendMinor(minor: Long) = withContext(dispatchers.io) {
        val sanitized = FinancialProfile.sanitize(minor)
        dataStore.edit { it[KEY_WEEKLY_SPEND] = sanitized }
    }

    suspend fun setCurrency(currency: RecoveryCurrency) = withContext(dispatchers.io) {
        dataStore.edit { it[KEY_CURRENCY] = currency.code }
    }

    suspend fun setRecoveryStart(epochMs: Long?) = withContext(dispatchers.io) {
        dataStore.edit { prefs ->
            if (epochMs == null) prefs.remove(KEY_RECOVERY_START) else prefs[KEY_RECOVERY_START] = epochMs
        }
    }

    suspend fun startRecoveryNow() = setRecoveryStart(wallClock.nowEpochMillis())

    suspend fun metricsSnapshot(): RecoveryMetrics = withContext(dispatchers.io) {
        RecoveryCalculator.metrics(_profile.value, wallClock.nowEpochMillis())
    }

    suspend fun addJournalEntry(
        intensity: Int,
        triggers: Set<dev.gamblock.core.model.CravingTrigger>,
        note: String = "",
        blockedDomain: String? = null,
        occurredAtEpochMs: Long = wallClock.nowEpochMillis(),
    ): Long = withContext(dispatchers.io) {
        val entity = CravingJournalEntity(
            occurredAtEpochMs = occurredAtEpochMs,
            intensity = dev.gamblock.core.model.CravingEntry.sanitizeIntensity(intensity),
            triggers = dev.gamblock.core.model.CravingEntry.joinTriggers(triggers),
            note = note.trim().take(MAX_NOTE_LENGTH),
            blockedDomain = blockedDomain?.trim()?.take(MAX_DOMAIN_LENGTH)?.takeIf { it.isNotEmpty() },
        )
        val id = journalDao.insert(entity)
        journalDao.trimTo(MAX_JOURNAL_ENTRIES)
        id
    }

    private fun CravingJournalEntity.toEntry(): UrgeJournalEntry = UrgeJournalEntry(
        id = id,
        occurredAtEpochMs = occurredAtEpochMs,
        intensity = intensity,
        triggers = dev.gamblock.core.model.CravingEntry
            .parseTriggerList(triggers)
            .map { it.name }
            .sorted(),
        note = note,
        blockedDomain = blockedDomain,
    )

    suspend fun deleteJournalEntry(id: Long) = withContext(dispatchers.io) {
        journalDao.deleteById(id)
    }

    suspend fun clearJournal() = withContext(dispatchers.io) {
        journalDao.clear()
    }

    suspend fun journalInsights(): CravingInsightsSnapshot = withContext(dispatchers.io) {
        val entries = _journal.value
        val models = entries.map { entry ->
            dev.gamblock.core.model.CravingEntry(
                id = entry.id,
                occurredAtEpochMs = entry.occurredAtEpochMs,
                intensity = dev.gamblock.core.model.CravingEntry.sanitizeIntensity(entry.intensity),
                triggers = entry.triggers
                    .map { dev.gamblock.core.model.CravingTrigger.fromStorageOrDefault(it) }
                    .toSet(),
                note = entry.note,
                blockedDomain = entry.blockedDomain,
            )
        }
        val insight = dev.gamblock.core.model.CravingInsights.analyze(models)
        CravingInsightsSnapshot(
            entries = entries,
            totalEntries = insight.totalEntries,
            peakWindowLabel = insight.peakWindowLabel,
            topTrigger = insight.peakTrigger?.displayName,
            averageIntensity = insight.averageIntensity,
            triggerCounts = insight.triggerCounts.mapKeys { it.key.displayName },
        )
    }

    suspend fun setFortressEnabled(enabled: Boolean) = withContext(dispatchers.io) {
        dataStore.edit { it[KEY_FORTRESS_ENABLED] = enabled }
    }

    suspend fun setFortressWindows(windows: List<FortressWindow>) = withContext(dispatchers.io) {
        val cleaned = windows.map(FortressWindow::sanitize)
        dataStore.edit { it[KEY_FORTRESS_WINDOWS] = json.encodeToString(cleaned) }
    }

    suspend fun addFortressWindow(window: FortressWindow) = withContext(dispatchers.io) {
        val existing = _fortress.value.windows.filterNot { it.id == window.id }
        setFortressWindows(existing + window)
    }

    suspend fun removeFortressWindow(id: String) = withContext(dispatchers.io) {
        setFortressWindows(_fortress.value.windows.filterNot { it.id == id })
    }

    suspend fun isFortressLockedDown(): Boolean = withContext(dispatchers.io) {
        val snapshot = _fortress.value
        snapshot.enabled && snapshot.lockedDown
    }

    fun fortressStatusAt(nowEpochMs: Long): dev.gamblock.core.model.FortressStatus {
        val snapshot = _fortress.value
        if (!snapshot.enabled) return dev.gamblock.core.model.FortressStatus()
        return dev.gamblock.core.model.FortressPolicy.status(snapshot.windows, nowEpochMs)
    }

    companion object {
        private val KEY_WEEKLY_SPEND = longPreferencesKey("recovery_weekly_spend_minor")
        private val KEY_CURRENCY = stringPreferencesKey("recovery_currency")
        private val KEY_RECOVERY_START = longPreferencesKey("recovery_start_epoch_ms")
        private val KEY_FORTRESS_ENABLED = booleanPreferencesKey("fortress_enabled")
        private val KEY_FORTRESS_WINDOWS = stringPreferencesKey("fortress_windows_json")
        private const val MAX_JOURNAL_ENTRIES = 500
        private const val MAX_NOTE_LENGTH = 280
        private const val MAX_DOMAIN_LENGTH = 253
        private const val CLOCK_TICK_MS = 60_000L
    }
}

private val Context.recoveryStore: DataStore<Preferences> by preferencesDataStore(name = "shield_recovery")
