package dev.gamblock.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Versioned, device-independent snapshot of everything a user would lose on a
 * phone change: the recovery streak, the urge journal, custom exceptions,
 * Fortress windows and the gate configuration.
 *
 * Security rules baked into this schema:
 *  - No Guardian PIN hash, PIN attempt counter or any other key material is ever
 *    written here. A restored device re-establishes its own PIN; see
 *    [GateConfigBackup] for the non-secret flags that are carried over.
 *  - The Guardian PIN record lives in a separate DataStore and is deliberately
 *    not reachable from a backup, so a stolen backup file cannot be brute-forced
 *    into a working PIN at only 10,000 candidates.
 *  - Nothing here is uploaded. The payload only ever exists on local storage the
 *    user picked through the Storage Access Framework, encrypted.
 */
@Serializable
data class RecoveryBackupPayload(
    val schemaVersion: Int = BackupSchema.CURRENT_VERSION,
    val appVersionName: String = "",
    val appVersionCode: Long = 0L,
    val exportedAtEpochMs: Long = 0L,
    val profile: RecoveryProfileBackup = RecoveryProfileBackup(),
    val milestones: List<MilestoneBackup> = emptyList(),
    val journal: List<JournalEntryBackup> = emptyList(),
    val exceptions: List<DomainExceptionBackup> = emptyList(),
    val fortress: FortressBackup = FortressBackup(),
    val gates: GateConfigBackup = GateConfigBackup(),
)

/** Financial profile + streak anchor. The streak is derived from the start date. */
@Serializable
data class RecoveryProfileBackup(
    val weeklySpendMinor: Long = 0L,
    val currencyCode: String = RecoveryCurrency.default.code,
    val recoveryStartEpochMs: Long? = null,
)

/** A milestone the user has already reached, kept so history survives a restore. */
@Serializable
data class MilestoneBackup(
    val days: Int = 0,
    val title: String = "",
)

/** One urge-journal row. Triggers are stored as names so a new enum value on a
 *  newer build cannot break an older backup. */
@Serializable
data class JournalEntryBackup(
    val id: Long = 0L,
    val occurredAtEpochMs: Long = 0L,
    val intensity: Int = 0,
    val triggers: List<String> = emptyList(),
    val note: String = "",
    val blockedDomain: String? = null,
)

/** A user-created custom domain exception (allowlist rule). */
@Serializable
data class DomainExceptionBackup(
    val id: Long = 0L,
    val normalizedDomain: String = "",
    val createdAtEpochMs: Long = 0L,
    val expiresAtEpochMs: Long? = null,
    val note: String = "",
)

/** Recurring Fortress lock-down windows. */
@Serializable
data class FortressBackup(
    val enabled: Boolean = false,
    val windows: List<FortressWindow> = emptyList(),
)

/**
 * Non-secret gate flags. `guardianPinEnabled` is carried so a restored device
 * stays protected, but the PIN itself is not: the user must set a new one.
 */
@Serializable
data class GateConfigBackup(
    val urgeTimerEnabled: Boolean = false,
    val fortressModeEnabled: Boolean = false,
    val guardianPinEnabled: Boolean = false,
    val requireAuthBeforeDisable: Boolean = false,
    val requireAuthBeforeClearHistory: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val greetPersonalizationName: String = "",
)

/** What the confirmation sheet shows the user before an overwrite. */
data class BackupSummary(
    val daysClean: Int = 0,
    val journalEntries: Int = 0,
    val customExceptions: Int = 0,
    val fortressWindows: Int = 0,
    val milestonesReached: Int = 0,
    val weeklySpendMinor: Long = 0L,
    val currencyCode: String = RecoveryCurrency.default.code,
    val exportedAtEpochMs: Long = 0L,
    val appVersionName: String = "",
)

/**
 * Schema versioning for the backup payload. The crypto envelope carries its own
 * format version (see `BackupCryptoManager`), and this is the independent
 * version of the JSON body, so the body can evolve without breaking the header.
 */
object BackupSchema {

    const val CURRENT_VERSION: Int = 1

    /** Payload older than this cannot be interpreted safely. */
    const val MINIMUM_SUPPORTED_VERSION: Int = 1

    /**
     * Brings a decoded payload up to [CURRENT_VERSION].
     *
     * Every field added after v1 must be nullable-with-default in the data class
     * so an older file still decodes, and any new requirement is normalised here
     * rather than at the point of use. A payload from a *newer* build is refused
     * instead of guessed at, because silently dropping fields the user expects to
     * come back would quietly lose recovery history.
     */
    fun migrate(payload: RecoveryBackupPayload): RecoveryBackupPayload {
        var current = payload
        var version = current.schemaVersion

        // v1 is the first published schema, so there is nothing to step through
        // yet. The loop is here so v2 can be added as a single `if (version == 1)`
        // step without changing callers.
        while (version < CURRENT_VERSION) {
            current = when (version) {
                else -> throw UnsupportedBackupSchemaException(version)
            }
            version = current.schemaVersion
        }
        return current
    }

    fun requireSupported(version: Int) {
        if (version > CURRENT_VERSION) throw UnsupportedBackupSchemaException(version)
        if (version < MINIMUM_SUPPORTED_VERSION) throw UnsupportedBackupSchemaException(version)
    }

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
}

/** Thrown when a payload's schema version cannot be read by this build. */
class UnsupportedBackupSchemaException(val version: Int) :
    IllegalStateException("unsupported backup schema version: $version")
