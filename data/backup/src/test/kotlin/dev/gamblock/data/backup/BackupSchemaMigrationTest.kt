package dev.gamblock.data.backup

import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.model.BackupSchema
import dev.gamblock.core.model.CravingTrigger
import dev.gamblock.core.model.DomainExceptionBackup
import dev.gamblock.core.model.FortressWindow
import dev.gamblock.core.model.JournalEntryBackup
import dev.gamblock.core.model.RecoveryBackupPayload
import dev.gamblock.core.model.UnsupportedBackupSchemaException
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Schema evolution. A backup taken on an older build has to keep restoring, so
 * the contract is that every field added after v1 is optional and that an
 * unknown-to-this-build version is refused rather than partially applied.
 */
class BackupSchemaMigrationTest {

    private val json = BackupSchema.json
    private val serializer = RecoveryBackupPayload.serializer()

    @Test
    fun `a payload with no optional sections decodes to the current schema`() {
        val minimal = """{"schemaVersion":1,"exportedAtEpochMs":1000}"""

        val decoded = json.decodeFromString(serializer, minimal)

        assertThat(decoded.schemaVersion).isEqualTo(BackupSchema.CURRENT_VERSION)
        assertThat(decoded.journal).isEmpty()
        assertThat(decoded.exceptions).isEmpty()
        assertThat(decoded.milestones).isEmpty()
        assertThat(decoded.fortress.windows).isEmpty()
        assertThat(decoded.gates.guardianPinEnabled).isFalse()
        // A file that omits the sections entirely is what an early build produced.
        assertThat(BackupSchema.migrate(decoded)).isEqualTo(decoded)
    }

    @Test
    fun `fields added by a later build are ignored rather than fatal`() {
        val fromTheFuture = """
            {
              "schemaVersion": 1,
              "exportedAtEpochMs": 1000,
              "somethingInventedLater": {"nested": [1, 2, 3]},
              "gates": {"guardianPinEnabled": true, "futureFlag": 7}
            }
        """.trimIndent()

        val decoded = json.decodeFromString(serializer, fromTheFuture)

        assertThat(decoded.gates.guardianPinEnabled).isTrue()
    }

    @Test
    fun `a payload from a newer schema is refused`() {
        val error = assertThrows(UnsupportedBackupSchemaException::class.java) {
            BackupSchema.requireSupported(BackupSchema.CURRENT_VERSION + 1)
        }

        assertThat(error.version).isEqualTo(BackupSchema.CURRENT_VERSION + 1)
    }

    @Test
    fun `migrate refuses a version this build cannot know about`() {
        assertThrows(UnsupportedBackupSchemaException::class.java) {
            BackupSchema.migrate(RecoveryBackupPayload(schemaVersion = 99))
        }
    }

    @Test
    fun `a v1 payload round trips through json unchanged`() {
        val original = samplePayload()

        val restored = json.decodeFromString(serializer, json.encodeToString(serializer, original))

        assertThat(restored).isEqualTo(original)
    }

    @Test
    fun `a backup survives a restart of the app on a different device`() {
        // Nothing device-specific may leak into the payload, or a restore onto a
        // different phone would be wrong. Compare the semantic content only.
        val original = samplePayload()

        val encoded = json.encodeToString(serializer, original)
        val decoded = json.decodeFromString(serializer, encoded)

        assertThat(decoded.profile).isEqualTo(original.profile)
        assertThat(decoded.journal).isEqualTo(original.journal)
        assertThat(decoded.exceptions).isEqualTo(original.exceptions)
        assertThat(decoded.fortress).isEqualTo(original.fortress)
        assertThat(decoded.gates).isEqualTo(original.gates)
    }

    private fun samplePayload() = RecoveryBackupPayload(
        schemaVersion = 1,
        appVersionName = "0.2.0",
        appVersionCode = 2L,
        exportedAtEpochMs = 1_757_000_000_000L,
        profile = dev.gamblock.core.model.RecoveryProfileBackup(
            weeklySpendMinor = 12_500L,
            currencyCode = "INR",
            recoveryStartEpochMs = 1_750_000_000_000L,
        ),
        milestones = listOf(dev.gamblock.core.model.MilestoneBackup(7, "One week strong")),
        journal = listOf(
            JournalEntryBackup(
                id = 1,
                occurredAtEpochMs = 1_756_000_000_000L,
                intensity = 4,
                triggers = CravingTrigger.entries.take(2).map { it.name },
                note = "late match",
                blockedDomain = "bet.example",
            ),
        ),
        exceptions = listOf(
            DomainExceptionBackup(
                id = 1,
                normalizedDomain = "news.example",
                createdAtEpochMs = 1_756_000_000_000L,
                expiresAtEpochMs = null,
                note = "odds",
            ),
        ),
        fortress = dev.gamblock.core.model.FortressBackup(
            enabled = true,
            windows = listOf(FortressWindow.overnightDaily(23, 5)),
        ),
        gates = dev.gamblock.core.model.GateConfigBackup(
            urgeTimerEnabled = true,
            guardianPinEnabled = true,
        ),
    )
}

/** The value-level rules that decide whether a payload may be written at all. */
class BackupPayloadValidatorTest {

    @Test
    fun `a clean payload passes through unchanged`() {
        val payload = RecoveryBackupPayload(
            journal = listOf(JournalEntryBackup(id = 1, occurredAtEpochMs = 5L, intensity = 3, note = "ok")),
        )

        assertThat(BackupPayloadValidator.validate(payload)).isEqualTo(payload)
    }

    @Test
    fun `intensity outside the supported range is clamped`() {
        val payload = RecoveryBackupPayload(
            journal = listOf(
                JournalEntryBackup(id = 1, occurredAtEpochMs = 5L, intensity = 9_999, note = "x"),
            ),
        )

        val validated = BackupPayloadValidator.validate(payload)

        assertThat(validated.journal.first().intensity).isAtMost(5)
    }

    @Test
    fun `oversized text is truncated rather than rejected`() {
        val payload = RecoveryBackupPayload(
            journal = listOf(
                JournalEntryBackup(
                    id = 1,
                    occurredAtEpochMs = 5L,
                    intensity = 1,
                    note = "n".repeat(BackupPayloadValidator.MAX_NOTE_LENGTH + 500),
                ),
            ),
        )

        val validated = BackupPayloadValidator.validate(payload)

        assertThat(validated.journal.first().note).hasLength(BackupPayloadValidator.MAX_NOTE_LENGTH)
    }

    @Test
    fun `a journal entry with no id is rejected`() {
        val payload = RecoveryBackupPayload(
            journal = listOf(JournalEntryBackup(id = 0, occurredAtEpochMs = 5L, note = "x")),
        )

        assertThrows(InvalidBackupPayloadException::class.java) { BackupPayloadValidator.validate(payload) }
    }

    @Test
    fun `a journal entry with no timestamp is rejected`() {
        val payload = RecoveryBackupPayload(
            journal = listOf(JournalEntryBackup(id = 1, occurredAtEpochMs = 0L, note = "x")),
        )

        assertThrows(InvalidBackupPayloadException::class.java) { BackupPayloadValidator.validate(payload) }
    }

    @Test
    fun `duplicate journal ids are rejected`() {
        val payload = RecoveryBackupPayload(
            journal = listOf(
                JournalEntryBackup(id = 4, occurredAtEpochMs = 5L, note = "a"),
                JournalEntryBackup(id = 4, occurredAtEpochMs = 6L, note = "b"),
            ),
        )

        val error = assertThrows(InvalidBackupPayloadException::class.java) {
            BackupPayloadValidator.validate(payload)
        }

        assertThat(error).hasMessageThat().contains("twice")
    }

    @Test
    fun `exception domains are normalised so case cannot smuggle a duplicate past`() {
        val payload = RecoveryBackupPayload(
            exceptions = listOf(
                DomainExceptionBackup(id = 1, normalizedDomain = "  News.Example  "),
            ),
        )

        val validated = BackupPayloadValidator.validate(payload)

        assertThat(validated.exceptions.first().normalizedDomain).isEqualTo("news.example")
    }

    @Test
    fun `two exceptions differing only in case are rejected`() {
        val payload = RecoveryBackupPayload(
            exceptions = listOf(
                DomainExceptionBackup(id = 1, normalizedDomain = "news.example"),
                DomainExceptionBackup(id = 2, normalizedDomain = "NEWS.EXAMPLE"),
            ),
        )

        assertThrows(InvalidBackupPayloadException::class.java) { BackupPayloadValidator.validate(payload) }
    }

    @Test
    fun `an exception with a blank domain is rejected`() {
        val payload = RecoveryBackupPayload(
            exceptions = listOf(DomainExceptionBackup(id = 1, normalizedDomain = "   ")),
        )

        assertThrows(InvalidBackupPayloadException::class.java) { BackupPayloadValidator.validate(payload) }
    }

    @Test
    fun `an absurd number of journal rows is refused before allocation`() {
        val payload = RecoveryBackupPayload(
            journal = List(BackupPayloadValidator.MAX_JOURNAL_ENTRIES + 1) { index ->
                JournalEntryBackup(id = index + 1L, occurredAtEpochMs = 1L, note = "x")
            },
        )

        assertThrows(InvalidBackupPayloadException::class.java) { BackupPayloadValidator.validate(payload) }
    }

    @Test
    fun `milestones are de-duplicated and ordered by days`() {
        val payload = RecoveryBackupPayload(
            milestones = listOf(
                dev.gamblock.core.model.MilestoneBackup(90, "three months"),
                dev.gamblock.core.model.MilestoneBackup(7, "one week"),
                dev.gamblock.core.model.MilestoneBackup(90, "duplicate"),
                dev.gamblock.core.model.MilestoneBackup(0, "nonsense"),
            ),
        )

        val validated = BackupPayloadValidator.validate(payload)

        assertThat(validated.milestones.map { it.days }).containsExactly(7, 90).inOrder()
    }

    @Test
    fun `a fortress window with impossible times cannot be decoded`() {
        // FortressWindow asserts its own invariants, so a hostile window fails
        // while the JSON is being read and can never exist as an object. That is
        // the whole reason no window value check is needed further downstream.
        val hostile = """
            {
              "schemaVersion": 1,
              "fortress": {
                "enabled": true,
                "windows": [
                  {"id": "w1", "startMinuteOfDay": 10, "endMinuteOfDay": 20, "endDayOffset": 99}
                ]
              }
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            BackupSchema.json.decodeFromString(
                RecoveryBackupPayload.serializer(),
                hostile,
            )
        }
    }

    @Test
    fun `a valid fortress window decodes and revalidates cleanly`() {
        val friendly = """
            {
              "schemaVersion": 1,
              "fortress": {
                "enabled": true,
                "windows": [
                  {"id": "w1", "startMinuteOfDay": 1380, "endMinuteOfDay": 300, "endDayOffset": 1}
                ]
              }
            }
        """.trimIndent()

        val decoded = BackupSchema.json.decodeFromString(
            RecoveryBackupPayload.serializer(),
            friendly,
        )

        val validated = BackupPayloadValidator.validate(decoded)
        assertThat(validated.fortress.windows).hasSize(1)
        assertThat(validated.fortress.enabled).isTrue()
    }

    @Test
    fun `a zero start date is treated as no streak rather than the epoch`() {
        val payload = RecoveryBackupPayload(
            profile = dev.gamblock.core.model.RecoveryProfileBackup(recoveryStartEpochMs = 0L),
        )

        assertThat(BackupPayloadValidator.validate(payload).profile.recoveryStartEpochMs).isNull()
    }
}
