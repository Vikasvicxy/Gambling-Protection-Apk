package dev.gamblock.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamblock.core.common.clock.WallClock
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.database.ShieldDatabase
import dev.gamblock.core.database.entity.CravingJournalEntity
import dev.gamblock.core.database.entity.CustomDomainExceptionEntity
import dev.gamblock.core.model.BackupSchema
import dev.gamblock.core.model.BackupSummary
import dev.gamblock.core.model.CravingEntry
import dev.gamblock.core.model.DomainExceptionBackup
import dev.gamblock.core.model.FortressWindow
import dev.gamblock.core.model.JournalEntryBackup
import dev.gamblock.core.model.MilestoneBackup
import dev.gamblock.core.model.RecoveryBackupPayload
import dev.gamblock.core.model.RecoveryCalculator
import dev.gamblock.core.model.RecoveryMilestone
import dev.gamblock.core.security.BackupCryptoManager
import dev.gamblock.core.security.BackupPassphrase
import dev.gamblock.core.security.InvalidBackupFormatException
import dev.gamblock.core.security.InvalidPassphraseException
import dev.gamblock.core.security.UnsupportedBackupVersionException
import dev.gamblock.data.preferences.RecoveryRepository
import dev.gamblock.data.preferences.SettingsRepository
import dev.gamblock.data.preferences.SettingsState
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * Offline, encrypted export and restore of recovery data.
 *
 * The file is written to, or read from, a [Uri] the user chose through the
 * Storage Access Framework, so this class never picks a path, never touches a
 * shared directory and has no cloud path of any kind. There is no upload, no
 * background sync and no network call anywhere in this module.
 *
 * The encryption key is the user's passphrase and nothing else. Authorisation to
 * export is handled one layer up by the Guardian gate, because the journal holds
 * the user's own private notes.
 */
@Singleton
class RecoveryBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: ShieldDatabase,
    private val recoveryRepository: RecoveryRepository,
    private val settingsRepository: SettingsRepository,
    private val crypto: BackupCryptoManager,
    private val dispatchers: DispatchersProvider,
    private val wallClock: WallClock,
    private val logger: ShieldLogger,
) {

    private val journalDao get() = database.cravingJournalDao()
    private val exceptionDao get() = database.customDomainExceptionDao()

    /** Serialises every piece of recovery state and writes it encrypted to [uri]. */
    suspend fun createBackup(uri: Uri, passphrase: CharArray): Result<BackupSummary> =
        withContext(dispatchers.io) {
            runCatching {
                require(passphrase.isNotEmpty()) { "a passphrase is required" }
                BackupPassphrase.rejectionReason(passphrase)?.let { throw IllegalArgumentException(it) }

                val payload = collectPayload()
                val plaintext = BackupSchema.json
                    .encodeToString(RecoveryBackupPayload.serializer(), payload)
                    .toByteArray(Charsets.UTF_8)
                val envelope = crypto.encrypt(plaintext, passphrase)

                // "wt" truncates, so re-picking the same filename overwrites cleanly
                // instead of appending a "(1)" duplicate.
                context.contentResolver.openOutputStream(uri, "wt")?.use { sink ->
                    sink.write(envelope)
                    sink.flush()
                } ?: throw BackupStorageException("could not open the chosen file for writing")

                logger.i(TAG, "backup written: ${envelope.size} bytes, ${payload.journal.size} journal rows")
                summarise(payload)
            }.onFailure { error ->
                // Deliberately logs no passphrase and no payload content.
                logger.w(TAG, "backup export failed: ${error.javaClass.simpleName}", error)
            }
        }

    /**
     * Decrypts and validates [uri] without writing anything, so the UI can show
     * the user what is inside before their current data is replaced.
     *
     * The passphrase is not retained on the returned [PreparedRestore]; it is only
     * needed to open the file.
     */
    suspend fun prepareRestore(uri: Uri, passphrase: CharArray): Result<PreparedRestore> =
        withContext(dispatchers.io) {
            runCatching {
                require(passphrase.isNotEmpty()) { "a passphrase is required" }

                val envelope = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw BackupStorageException("could not open the selected file")

                val plaintext = crypto.decrypt(envelope, passphrase)
                val decoded = try {
                    BackupSchema.json.decodeFromString(
                        RecoveryBackupPayload.serializer(),
                        plaintext.toString(Charsets.UTF_8),
                    )
                } catch (e: Exception) {
                    // The magic, the version and the GCM tag have all verified by
                    // this point, so the file is genuinely ours and only the body is
                    // wrong. Reporting it as a bad file would send the user looking
                    // for the wrong problem; an out-of-range Fortress window also
                    // surfaces here as a constructor failure during decoding.
                    throw InvalidBackupPayloadException("backup contents could not be read", e)
                }
                BackupSchema.requireSupported(decoded.schemaVersion)
                val validated = BackupPayloadValidator.validate(BackupSchema.migrate(decoded))
                logger.i(
                    TAG,
                    "backup opened: schema ${decoded.schemaVersion}, ${validated.journal.size} journal rows",
                )
                PreparedRestore(payload = validated, summary = summarise(validated), byteCount = envelope.size)
            }.onFailure { error ->
                logger.w(TAG, "backup restore read failed: ${error.javaClass.simpleName}", error)
            }
        }

    /**
     * Writes a [PreparedRestore] over the current recovery data.
     *
     * Atomicity: the database is replaced inside a single Room transaction, so a
     * failure part-way through rolls the whole swap back and the previous journal
     * and exceptions are intact. Preferences live in a different store and cannot
     * join that transaction, so they are written only after the database has
     * committed; each DataStore `edit` is itself atomic. The ordering matters: if
     * the database step fails, nothing else has been touched yet.
     */
    suspend fun applyRestore(prepared: PreparedRestore): Result<BackupSummary> =
        withContext(dispatchers.io) {
            runCatching {
                val payload = prepared.payload

                database.withTransaction {
                    // Both tables are replaced wholesale: a restore is a restore of
                    // the snapshot the user is looking at, not a merge.
                    journalDao.clear()
                    journalDao.insertAll(payload.journal.map(JournalEntryBackup::toEntity))
                    exceptionDao.clear()
                    exceptionDao.upsertAll(payload.exceptions.map(DomainExceptionBackup::toEntity))
                }

                writePreferences(payload)

                logger.i(TAG, "backup restored: ${payload.journal.size} journal rows")
                summarise(payload)
            }.onFailure { error ->
                logger.w(TAG, "backup restore write failed: ${error.javaClass.simpleName}", error)
            }
        }

    /** Convenience for callers that do not need the confirmation step. */
    suspend fun restoreBackup(uri: Uri, passphrase: CharArray): Result<BackupSummary> =
        prepareRestore(uri, passphrase).fold(
            onSuccess = { applyRestore(it) },
            onFailure = { Result.failure(it) },
        )

    private suspend fun collectPayload(): RecoveryBackupPayload {
        val profile = recoveryRepository.profile.value
        val fortress = recoveryRepository.fortress.value
        val settings = settingsRepository.settings.value
        val metrics = recoveryRepository.metrics.value

        val journal = journalDao.findAll().map { row ->
            JournalEntryBackup(
                id = row.id,
                occurredAtEpochMs = row.occurredAtEpochMs,
                intensity = row.intensity,
                triggers = CravingEntry.parseTriggerList(row.triggers).map { it.name }.sorted(),
                note = row.note,
                blockedDomain = row.blockedDomain,
            )
        }
        val exceptions = exceptionDao.findAll().map { row ->
            DomainExceptionBackup(
                id = row.id,
                normalizedDomain = row.normalizedDomain,
                createdAtEpochMs = row.createdAtEpochMs,
                expiresAtEpochMs = row.expiresAtEpochMs,
                note = row.note,
            )
        }

        return RecoveryBackupPayload(
            schemaVersion = BackupSchema.CURRENT_VERSION,
            appVersionName = appVersionName(),
            appVersionCode = appVersionCode(),
            exportedAtEpochMs = wallClock.nowEpochMillis(),
            profile = dev.gamblock.core.model.RecoveryProfileBackup(
                weeklySpendMinor = profile.weeklySpendMinor,
                currencyCode = profile.currencyCode,
                recoveryStartEpochMs = profile.recoveryStartEpochMs,
            ),
            milestones = RecoveryMilestone.ordered
                .filter { it.days <= metrics.daysClean }
                .map { MilestoneBackup(days = it.days, title = it.title) },
            journal = journal,
            exceptions = exceptions,
            fortress = dev.gamblock.core.model.FortressBackup(
                enabled = fortress.enabled,
                windows = fortress.windows,
            ),
            gates = dev.gamblock.core.model.GateConfigBackup(
                urgeTimerEnabled = settings.urgeTimerEnabled,
                fortressModeEnabled = settings.fortressModeEnabled,
                guardianPinEnabled = settings.guardianPinEnabled,
                requireAuthBeforeDisable = settings.requireAuthBeforeDisable,
                requireAuthBeforeClearHistory = settings.requireAuthBeforeClearHistory,
                notificationsEnabled = settings.notificationsEnabled,
                hapticsEnabled = settings.hapticsEnabled,
                greetPersonalizationName = settings.greetPersonalizationName,
            ),
        )
    }

    private suspend fun writePreferences(payload: RecoveryBackupPayload) {
        recoveryRepository.applyBackupPreferences(payload)
        settingsRepository.update { current -> current.withBackupGates(payload.gates) }
    }

    private fun SettingsState.withBackupGates(
        gates: dev.gamblock.core.model.GateConfigBackup,
    ) = copy(
        urgeTimerEnabled = gates.urgeTimerEnabled,
        fortressModeEnabled = gates.fortressModeEnabled,
        guardianPinEnabled = gates.guardianPinEnabled,
        requireAuthBeforeDisable = gates.requireAuthBeforeDisable,
        requireAuthBeforeClearHistory = gates.requireAuthBeforeClearHistory,
        notificationsEnabled = gates.notificationsEnabled,
        hapticsEnabled = gates.hapticsEnabled,
        greetPersonalizationName = gates.greetPersonalizationName,
    )

    /** Drives the "18-day streak, 14 journal entries, 3 exceptions" preview. */
    fun summarise(payload: RecoveryBackupPayload): BackupSummary {
        val startEpochMs = payload.profile.recoveryStartEpochMs
        val daysClean = startEpochMs
            ?.let { RecoveryCalculator.daysProtected(it, wallClock.nowEpochMillis()) }
            ?.coerceAtLeast(0)
            ?: 0
        return BackupSummary(
            daysClean = daysClean,
            journalEntries = payload.journal.size,
            customExceptions = payload.exceptions.size,
            fortressWindows = payload.fortress.windows.count(FortressWindow::enabled),
            milestonesReached = payload.milestones.size,
            weeklySpendMinor = payload.profile.weeklySpendMinor,
            currencyCode = payload.profile.currencyCode,
            exportedAtEpochMs = payload.exportedAtEpochMs,
            appVersionName = payload.appVersionName,
        )
    }

    private fun appVersionName(): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun appVersionCode(): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrDefault(0L)

    companion object {
        private const val TAG = "RecoveryBackup"

        /**
         * Exceptions a caller is expected to translate into user-facing copy.
         * Exposed so the UI layer does not have to import the crypto module.
         */
        val USER_FACING: Map<String, String> = mapOf(
            InvalidPassphraseException::class.java.name to
                "That passphrase does not open this file, or the file was changed after it was written.",
            InvalidBackupFormatException::class.java.name to
                "That is not a Shield recovery backup file.",
            UnsupportedBackupVersionException::class.java.name to
                "This backup was made by a different version of Shield. Update the app, then try again.",
            InvalidBackupPayloadException::class.java.name to
                "This backup is damaged and was not applied. Nothing on this device has changed.",
            BackupStorageException::class.java.name to
                "That file could not be read or written. Check you still have access to it.",
            IOException::class.java.name to "That file could not be read or written.",
        )
    }
}

private fun JournalEntryBackup.toEntity() = CravingJournalEntity(
    id = id,
    occurredAtEpochMs = occurredAtEpochMs,
    intensity = intensity,
    triggers = CravingEntry.joinTriggers(triggers.mapNotNull { runCatching { dev.gamblock.core.model.CravingTrigger.valueOf(it) }.getOrNull() }.toSet()),
    note = note,
    blockedDomain = blockedDomain,
)

private fun DomainExceptionBackup.toEntity() = CustomDomainExceptionEntity(
    id = id,
    normalizedDomain = normalizedDomain,
    createdAtEpochMs = createdAtEpochMs,
    expiresAtEpochMs = expiresAtEpochMs,
    note = note,
)
