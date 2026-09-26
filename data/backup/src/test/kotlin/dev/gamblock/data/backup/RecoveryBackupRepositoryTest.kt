package dev.gamblock.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.database.ShieldDatabase
import dev.gamblock.core.database.entity.CravingJournalEntity
import dev.gamblock.core.database.entity.CustomDomainExceptionEntity
import dev.gamblock.core.model.BackupSchema
import dev.gamblock.core.model.DomainExceptionBackup
import dev.gamblock.core.model.FortressWindow
import dev.gamblock.core.model.JournalEntryBackup
import dev.gamblock.core.model.MilestoneBackup
import dev.gamblock.core.model.RecoveryBackupPayload
import dev.gamblock.core.model.RecoveryProfileBackup
import dev.gamblock.core.model.FortressBackup
import dev.gamblock.core.security.BackupCryptoManager
import dev.gamblock.core.security.InvalidBackupFormatException
import dev.gamblock.core.security.InvalidPassphraseException
import dev.gamblock.core.security.UnsupportedBackupVersionException
import dev.gamblock.core.testing.FakeWallClock
import dev.gamblock.core.testing.NoOpLogger
import dev.gamblock.core.testing.TestDispatchersProvider
import dev.gamblock.data.preferences.RecoveryRepository
import dev.gamblock.data.preferences.SettingsRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecoveryBackupRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: ShieldDatabase
    private lateinit var recoveryRepository: RecoveryRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var crypto: BackupCryptoManager
    private lateinit var repository: RecoveryBackupRepository
    private val clock = FakeWallClock(1_757_000_000_000L)
    private val passphrase = "a strong backup passphrase".toCharArray()

    private val exportUri: Uri = Uri.parse("content://test/shield-recovery-backup.shld")
    private var exportedBytes: ByteArrayOutputStream = ByteArrayOutputStream()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, ShieldDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dispatchers = TestDispatchersProvider(UnconfinedTestDispatcher())
        recoveryRepository = RecoveryRepository(
            context = context,
            wallClock = clock,
            dispatchers = dispatchers,
            logger = NoOpLogger,
            database = database,
        )
        settingsRepository = SettingsRepository(
            context = context,
            wallClock = clock,
            dispatchers = dispatchers,
            logger = NoOpLogger,
        )
        crypto = BackupCryptoManager()
        repository = RecoveryBackupRepository(
            context = context,
            database = database,
            recoveryRepository = recoveryRepository,
            settingsRepository = settingsRepository,
            crypto = crypto,
            dispatchers = dispatchers,
            wallClock = clock,
            logger = NoOpLogger,
        )
        // Robolectric only serves streams that the test registers, which also lets
        // the assertions look at the exact bytes that reached "storage".
        exportedBytes = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(exportUri, exportedBytes)

        runBlocking { resetPreferences() }
    }

    /**
     * The preferences DataStore files outlive an individual test in the same
     * Robolectric sandbox, so each test starts from a known-empty baseline
     * instead of inheriting whatever the previous one wrote.
     */
    private suspend fun resetPreferences() {
        recoveryRepository.setWeeklySpendMinor(0L)
        recoveryRepository.setRecoveryStart(null)
        recoveryRepository.setFortressEnabled(false)
        recoveryRepository.setFortressWindows(emptyList())
        settingsRepository.update { dev.gamblock.data.preferences.SettingsState() }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun makeRestorable(
        bytes: ByteArray = exportedBytes.toByteArray(),
        uri: Uri = exportUri,
    ) {
        shadowOf(context.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(bytes))
    }

    private suspend fun seedJournal() {
        database.cravingJournalDao().insert(
            CravingJournalEntity(
                occurredAtEpochMs = 1_756_000_000_000L,
                intensity = 4,
                triggers = "BOREDOM",
                note = "triggered by a late match",
                blockedDomain = "bet.example",
            ),
        )
        database.cravingJournalDao().insert(
            CravingJournalEntity(
                occurredAtEpochMs = 1_756_100_000_000L,
                intensity = 2,
                triggers = "STRESS, LONELINESS",
                note = "second entry",
            ),
        )
    }

    private suspend fun seedException() {
        database.customDomainExceptionDao().upsert(
            CustomDomainExceptionEntity(
                normalizedDomain = "news.example",
                createdAtEpochMs = 1_756_000_000_000L,
                note = "reading the odds",
            ),
        )
    }

    @Test
    fun `export then restore returns the data to an empty device`() = runTest {
        seedJournal()
        seedException()
        recoveryRepository.setWeeklySpendMinor(12_500L)
        recoveryRepository.setRecoveryStart(1_750_000_000_000L)
        recoveryRepository.setFortressEnabled(true)
        settingsRepository.setUrgeTimerEnabled(true)

        assertThat(repository.createBackup(exportUri, passphrase).isSuccess).isTrue()
        assertThat(exportedBytes.toByteArray().size).isGreaterThan(0)

        // Wipe everything, as if the user had just changed phones.
        database.cravingJournalDao().clear()
        database.customDomainExceptionDao().clear()
        recoveryRepository.setWeeklySpendMinor(0L)
        recoveryRepository.setRecoveryStart(null)
        settingsRepository.setUrgeTimerEnabled(false)

        makeRestorable()
        val result = repository.restoreBackup(exportUri, passphrase)

        assertThat(result.isSuccess).isTrue()
        val summary = result.getOrThrow()
        assertThat(summary.journalEntries).isEqualTo(2)
        assertThat(summary.customExceptions).isEqualTo(1)
        assertThat(summary.weeklySpendMinor).isEqualTo(12_500L)

        val journal = database.cravingJournalDao().findAll()
        assertThat(journal).hasSize(2)
        assertThat(journal.map { it.note }).contains("triggered by a late match")
        assertThat(journal.first { it.note == "second entry" }.intensity).isEqualTo(2)

        val exceptions = database.customDomainExceptionDao().findAll()
        assertThat(exceptions).hasSize(1)
        assertThat(exceptions.first().normalizedDomain).isEqualTo("news.example")

        assertThat(recoveryRepository.profile.value.weeklySpendMinor).isEqualTo(12_500L)
        assertThat(recoveryRepository.profile.value.recoveryStartEpochMs).isEqualTo(1_750_000_000_000L)
        assertThat(recoveryRepository.fortress.value.enabled).isTrue()
        assertThat(settingsRepository.settings.value.urgeTimerEnabled).isTrue()
    }

    @Test
    fun `the exported file is not readable as plaintext`() = runTest {
        seedJournal()

        repository.createBackup(exportUri, passphrase)

        val bytes = exportedBytes.toByteArray()
        assertThat(bytes.copyOfRange(0, 8).toString(Charsets.US_ASCII)).isEqualTo("SHLDBAK1")
        assertThat(bytes.toString(Charsets.ISO_8859_1)).doesNotContain("triggered by")
        // And it cannot be opened without the passphrase.
        makeRestorable()
        assertThat(repository.prepareRestore(exportUri, "wrong passphrase here".toCharArray()).isFailure).isTrue()
    }

    @Test
    fun `the guardian pin is never written into a backup`() = runTest {
        seedJournal()

        repository.createBackup(exportUri, passphrase)
        makeRestorable()

        val prepared = repository.prepareRestore(exportUri, passphrase).getOrThrow()

        // A backup carries "guardianPinEnabled" as a flag, never a hash or the PIN.
        val json = BackupSchema.json.encodeToString(
            RecoveryBackupPayload.serializer(),
            prepared.payload,
        )
        assertThat(json).doesNotContain("guardian_pin")
        assertThat(json).doesNotContain("saltBase64")
        assertThat(json).doesNotContain("hashBase64")
    }

    @Test
    fun `a wrong passphrase leaves the device untouched`() = runTest {
        seedJournal()
        val before = database.cravingJournalDao().findAll()
        repository.createBackup(exportUri, passphrase)
        makeRestorable()

        val result = repository.restoreBackup(exportUri, "not the passphrase".toCharArray())

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(InvalidPassphraseException::class.java)
        assertThat(database.cravingJournalDao().findAll()).isEqualTo(before)
    }

    @Test
    fun `a file that is not a Shield backup is refused`() = runTest {
        seedJournal()
        val before = database.cravingJournalDao().findAll()
        makeRestorable(ByteArray(2048) { 0x41 })

        val result = repository.restoreBackup(exportUri, passphrase)

        assertThat(result.exceptionOrNull()).isInstanceOf(InvalidBackupFormatException::class.java)
        assertThat(database.cravingJournalDao().findAll()).isEqualTo(before)
    }

    @Test
    fun `a corrupted export is refused and changes nothing`() = runTest {
        seedJournal()
        repository.createBackup(exportUri, passphrase)
        val corrupted = exportedBytes.toByteArray().also {
            it[it.size - 20] = (it[it.size - 20].toInt() xor 0x7F).toByte()
        }
        val before = database.cravingJournalDao().findAll()
        makeRestorable(corrupted)

        val result = repository.restoreBackup(exportUri, passphrase)

        assertThat(result.exceptionOrNull()).isInstanceOf(InvalidPassphraseException::class.java)
        assertThat(database.cravingJournalDao().findAll()).isEqualTo(before)
    }

    @Test
    fun `a backup from a newer schema is refused`() = runTest {
        val envelope = crypto.encrypt(
            """{"schemaVersion":99}""".toByteArray(),
            passphrase,
        )
        makeRestorable(envelope)

        val result = repository.restoreBackup(exportUri, passphrase)

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("schema version")
    }

    @Test
    fun `a newer crypto format is refused`() = runTest {
        val envelope = crypto.encrypt("{}".toByteArray(), passphrase)
        envelope[8] = 99.toByte()
        makeRestorable(envelope)

        val result = repository.restoreBackup(exportUri, passphrase)

        assertThat(result.exceptionOrNull()).isInstanceOf(UnsupportedBackupVersionException::class.java)
    }

    @Test
    fun `a weak passphrase cannot create a backup`() = runTest {
        val result = repository.createBackup(exportUri, "1234".toCharArray())

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).hasMessageThat().contains("at least 8 characters")
    }

    @Test
    fun `prepare shows a preview without writing anything`() = runTest {
        seedJournal()
        seedException()
        recoveryRepository.setWeeklySpendMinor(9_900L)
        repository.createBackup(exportUri, passphrase)
        database.cravingJournalDao().clear()
        database.customDomainExceptionDao().clear()
        makeRestorable()

        val prepared = repository.prepareRestore(exportUri, passphrase).getOrThrow()

        assertThat(prepared.summary.journalEntries).isEqualTo(2)
        assertThat(prepared.summary.customExceptions).isEqualTo(1)
        assertThat(prepared.summary.weeklySpendMinor).isEqualTo(9_900L)
        // Preparing is not applying.
        assertThat(database.cravingJournalDao().findAll()).isEmpty()
        assertThat(database.customDomainExceptionDao().findAll()).isEmpty()
    }

    @Test
    fun `restoring twice is idempotent`() = runTest {
        seedJournal()
        repository.createBackup(exportUri, passphrase)
        val bytes = exportedBytes.toByteArray()
        database.cravingJournalDao().clear()
        makeRestorable(bytes)
        repository.restoreBackup(exportUri, passphrase).getOrThrow()
        val afterFirst = database.cravingJournalDao().findAll()

        makeRestorable(bytes)
        repository.restoreBackup(exportUri, passphrase).getOrThrow()

        assertThat(database.cravingJournalDao().findAll()).isEqualTo(afterFirst)
    }

    @Test
    fun `restore replaces existing rows instead of merging them`() = runTest {
        seedJournal()
        repository.createBackup(exportUri, passphrase)
        val bytes = exportedBytes.toByteArray()
        // Add a row that was not in the backup.
        database.cravingJournalDao().insert(
            CravingJournalEntity(occurredAtEpochMs = 1_756_900_000_000L, intensity = 5, triggers = "STRESS", note = "after the backup"),
        )
        assertThat(database.cravingJournalDao().findAll()).hasSize(3)
        makeRestorable(bytes)

        repository.restoreBackup(exportUri, passphrase).getOrThrow()

        assertThat(database.cravingJournalDao().findAll()).hasSize(2)
        assertThat(database.cravingJournalDao().findAll().map { it.note }).doesNotContain("after the backup")
    }

    @Test
    fun `fortress windows survive a round trip`() = runTest {
        val window = FortressWindow.weekendToMonday(startHour = 20, endHour = 6)
        recoveryRepository.setFortressWindows(listOf(window))
        recoveryRepository.setFortressEnabled(true)
        repository.createBackup(exportUri, passphrase)
        recoveryRepository.setFortressWindows(emptyList())
        recoveryRepository.setFortressEnabled(false)
        makeRestorable()

        repository.restoreBackup(exportUri, passphrase).getOrThrow()

        val restored = recoveryRepository.fortress.value.windows
        assertThat(restored).hasSize(1)
        assertThat(restored.first().startMinuteOfDay).isEqualTo(window.startMinuteOfDay)
        assertThat(restored.first().endDayOffset).isEqualTo(window.endDayOffset)
        assertThat(recoveryRepository.fortress.value.enabled).isTrue()
    }

    @Test
    fun `reached milestones are carried across`() = runTest {
        // 400 days clean reaches every milestone the app knows about.
        recoveryRepository.setRecoveryStart(clock.nowEpochMillis() - 400L * 24 * 60 * 60 * 1000)
        repository.createBackup(exportUri, passphrase)
        makeRestorable()

        val prepared = repository.prepareRestore(exportUri, passphrase).getOrThrow()

        assertThat(prepared.payload.milestones.map { it.days }).containsExactly(7, 30, 90, 365)
    }

    @Test
    fun `an empty device exports and restores cleanly`() = runTest {
        assertThat(repository.createBackup(exportUri, passphrase).isSuccess).isTrue()
        makeRestorable()

        val result = repository.restoreBackup(exportUri, passphrase)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().journalEntries).isEqualTo(0)
    }

    @Test
    fun `fortress and gate flags round trip`() = runTest {
        settingsRepository.setUrgeTimerEnabled(true)
        settingsRepository.setFortressModeEnabled(true)
        settingsRepository.setRequireAuthBeforeClearHistory(true)
        settingsRepository.setGreetName("Sam")
        repository.createBackup(exportUri, passphrase)
        settingsRepository.update { it.copy(urgeTimerEnabled = false, greetPersonalizationName = "") }
        makeRestorable()

        repository.restoreBackup(exportUri, passphrase).getOrThrow()

        val settings = settingsRepository.settings.value
        assertThat(settings.urgeTimerEnabled).isTrue()
        assertThat(settings.fortressModeEnabled).isTrue()
        assertThat(settings.requireAuthBeforeClearHistory).isTrue()
        assertThat(settings.greetPersonalizationName).isEqualTo("Sam")
    }

    @Test
    fun `a malformed entry is rejected before any write`() = runTest {
        seedJournal()
        val before = database.cravingJournalDao().findAll()
        val payload = RecoveryBackupPayload(
            journal = listOf(
                JournalEntryBackup(id = 1, occurredAtEpochMs = 1L, intensity = 3, note = "fine"),
                // No id: cannot be written back faithfully.
                JournalEntryBackup(id = 0, occurredAtEpochMs = 2L, intensity = 3, note = "broken"),
            ),
        )
        val envelope = crypto.encrypt(
            BackupSchema.json.encodeToString(RecoveryBackupPayload.serializer(), payload).toByteArray(),
            passphrase,
        )
        makeRestorable(envelope)

        val result = repository.restoreBackup(exportUri, passphrase)

        assertThat(result.exceptionOrNull()).isInstanceOf(InvalidBackupPayloadException::class.java)
        assertThat(database.cravingJournalDao().findAll()).isEqualTo(before)
    }

    @Test
    fun `a duplicate journal id is rejected before any write`() = runTest {
        seedJournal()
        val before = database.cravingJournalDao().findAll()
        val payload = RecoveryBackupPayload(
            journal = listOf(
                JournalEntryBackup(id = 7, occurredAtEpochMs = 1L, intensity = 3, note = "one"),
                JournalEntryBackup(id = 7, occurredAtEpochMs = 2L, intensity = 3, note = "two"),
            ),
        )
        val envelope = crypto.encrypt(
            BackupSchema.json.encodeToString(RecoveryBackupPayload.serializer(), payload).toByteArray(),
            passphrase,
        )
        makeRestorable(envelope)

        val result = repository.restoreBackup(exportUri, passphrase)

        assertThat(result.exceptionOrNull()).isInstanceOf(InvalidBackupPayloadException::class.java)
        assertThat(database.cravingJournalDao().findAll()).isEqualTo(before)
    }

    @Test
    fun `a duplicate exception domain is rejected before any write`() = runTest {
        seedException()
        val before = database.customDomainExceptionDao().findAll()
        val payload = RecoveryBackupPayload(
            exceptions = listOf(
                DomainExceptionBackup(id = 1, normalizedDomain = "dup.example"),
                DomainExceptionBackup(id = 2, normalizedDomain = "DUP.example"),
            ),
        )
        val envelope = crypto.encrypt(
            BackupSchema.json.encodeToString(RecoveryBackupPayload.serializer(), payload).toByteArray(),
            passphrase,
        )
        makeRestorable(envelope)

        val result = repository.restoreBackup(exportUri, passphrase)

        assertThat(result.exceptionOrNull()).isInstanceOf(InvalidBackupPayloadException::class.java)
        assertThat(database.customDomainExceptionDao().findAll()).isEqualTo(before)
    }

    @Test
    fun `a transaction failure mid restore rolls the whole swap back`() = runTest {
        seedJournal()
        seedException()
        val journalBefore = database.cravingJournalDao().findAll()
        val exceptionsBefore = database.customDomainExceptionDao().findAll()

        // Built directly instead of via prepareRestore, because the validator is
        // supposed to stop a duplicate id long before this point. Skipping it is
        // the only way to make the failure happen *inside* the transaction, which
        // is exactly the case Room's rollback has to survive.
        val hostile = PreparedRestore(
            payload = RecoveryBackupPayload(
                journal = listOf(
                    JournalEntryBackup(id = 1, occurredAtEpochMs = 1L, intensity = 1, note = "a"),
                    // Same primary key: the first insert lands, the second aborts.
                    JournalEntryBackup(id = 1, occurredAtEpochMs = 2L, intensity = 1, note = "b"),
                ),
                exceptions = listOf(DomainExceptionBackup(id = 1, normalizedDomain = "x.example")),
            ),
            summary = dev.gamblock.core.model.BackupSummary(),
        )

        val result = repository.applyRestore(hostile)

        assertThat(result.isFailure).isTrue()
        // The clear() and the first insert both ran before the failure, so if the
        // transaction had not rolled back these tables would now hold the payload.
        assertThat(database.cravingJournalDao().findAll()).isEqualTo(journalBefore)
        assertThat(database.customDomainExceptionDao().findAll()).isEqualTo(exceptionsBefore)
        assertThat(recoveryRepository.profile.value.weeklySpendMinor).isEqualTo(0L)
    }

    @Test
    fun `milestone and profile normalisation clamps out of range values`() = runTest {
        val payload = RecoveryBackupPayload(
            profile = RecoveryProfileBackup(weeklySpendMinor = Long.MAX_VALUE, currencyCode = "ZZZ"),
            milestones = listOf(MilestoneBackup(days = 0, title = "ignored"), MilestoneBackup(days = 7, title = "One week strong")),
            fortress = FortressBackup(enabled = true, windows = emptyList()),
        )
        val envelope = crypto.encrypt(
            BackupSchema.json.encodeToString(RecoveryBackupPayload.serializer(), payload).toByteArray(),
            passphrase,
        )
        makeRestorable(envelope)

        val prepared = repository.prepareRestore(exportUri, passphrase).getOrThrow()

        assertThat(prepared.payload.profile.weeklySpendMinor)
            .isEqualTo(dev.gamblock.core.model.FinancialProfile.MAX_WEEKLY_SPEND_MINOR)
        assertThat(prepared.payload.milestones.map { it.days }).containsExactly(7)
    }

    @Test
    fun `a backup with an impossible fortress window is refused cleanly`() = runTest {
        seedJournal()
        val before = database.cravingJournalDao().findAll()
        val hostile = """
            {
              "schemaVersion": 1,
              "journal": [{"id": 1, "occurredAtEpochMs": 5, "intensity": 2, "note": "ok"}],
              "fortress": {
                "enabled": true,
                "windows": [{"id": "w1", "startMinuteOfDay": 10, "endMinuteOfDay": 20, "endDayOffset": 99}]
              }
            }
        """.trimIndent()
        makeRestorable(crypto.encrypt(hostile.toByteArray(), passphrase))

        val result = repository.restoreBackup(exportUri, passphrase)

        assertThat(result.exceptionOrNull()).isInstanceOf(InvalidBackupPayloadException::class.java)
        assertThat(database.cravingJournalDao().findAll()).isEqualTo(before)
    }

    @Test
    fun `createBackup rejects an empty passphrase`() = runTest {
        val result = repository.createBackup(exportUri, CharArray(0))

        assertThat(result.isFailure).isTrue()
    }
}
