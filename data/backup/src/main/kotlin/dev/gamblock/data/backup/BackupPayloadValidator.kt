package dev.gamblock.data.backup

import dev.gamblock.core.model.CravingEntry
import dev.gamblock.core.model.DomainExceptionBackup
import dev.gamblock.core.model.FinancialProfile
import dev.gamblock.core.model.FortressWindow
import dev.gamblock.core.model.JournalEntryBackup
import dev.gamblock.core.model.MilestoneBackup
import dev.gamblock.core.model.RecoveryBackupPayload

/**
 * Validates a decrypted payload before anything is written.
 *
 * The whole point is that restore never half-applies. Every check that can reject
 * a bad file runs here, ahead of the database transaction, so a malformed backup
 * leaves the device exactly as it was. The transaction is the second line of
 * defence rather than the first, because a rejection after a `clear()` is far
 * harder to reason about than a rejection up front.
 */
object BackupPayloadValidator {

    const val MAX_JOURNAL_ENTRIES: Int = 5_000
    const val MAX_EXCEPTIONS: Int = 5_000
    const val MAX_MILESTONES: Int = 16
    const val MAX_NOTE_LENGTH: Int = 4_000
    const val MAX_DOMAIN_LENGTH: Int = 512
    const val MAX_TRIGGER_LENGTH: Int = 64
    const val MAX_TRIGGERS_PER_ENTRY: Int = 32
    const val MAX_FORTNESS_WINDOWS: Int = 64

    /**
     * Returns a normalised copy, or throws [InvalidBackupPayloadException].
     * Normalisation is limited to clamping values that have a documented safe
     * range (spend, intensity, note length); anything that would change meaning
     * is rejected instead of guessed at.
     */
    fun validate(payload: RecoveryBackupPayload): RecoveryBackupPayload {
        if (payload.journal.size > MAX_JOURNAL_ENTRIES) {
            throw InvalidBackupPayloadException("backup holds more than $MAX_JOURNAL_ENTRIES journal entries")
        }
        if (payload.exceptions.size > MAX_EXCEPTIONS) {
            throw InvalidBackupPayloadException("backup holds more than $MAX_EXCEPTIONS custom exceptions")
        }
        if (payload.milestones.size > MAX_MILESTONES) {
            throw InvalidBackupPayloadException("backup holds more than $MAX_MILESTONES milestones")
        }
        if (payload.fortress.windows.size > MAX_FORTNESS_WINDOWS) {
            throw InvalidBackupPayloadException("backup holds more than $MAX_FORTNESS_WINDOWS Fortress windows")
        }
        return payload.copy(
            profile = validateProfile(payload.profile),
            milestones = validateMilestones(payload.milestones),
            journal = validateJournal(payload.journal),
            exceptions = validateExceptions(payload.exceptions),
            gates = payload.gates.copy(
                greetPersonalizationName = payload.gates.greetPersonalizationName.take(MAX_NOTE_LENGTH),
            ),
        )
    }

    private fun validateProfile(profile: dev.gamblock.core.model.RecoveryProfileBackup) = profile.copy(
        weeklySpendMinor = FinancialProfile.sanitize(profile.weeklySpendMinor),
        currencyCode = profile.currencyCode.take(16),
        recoveryStartEpochMs = profile.recoveryStartEpochMs?.takeIf { it > 0L },
    )

    private fun validateMilestones(milestones: List<MilestoneBackup>): List<MilestoneBackup> =
        milestones.filter { it.days > 0 }
            .map { it.copy(title = it.title.take(MAX_NOTE_LENGTH)) }
            .distinctBy { it.days }
            .sortedBy { it.days }

    private fun validateJournal(entries: List<JournalEntryBackup>): List<JournalEntryBackup> {
        val seenIds = HashSet<Long>(entries.size)
        return entries.map { entry ->
            if (entry.id <= 0L) {
                throw InvalidBackupPayloadException("a journal entry has no id")
            }
            if (!seenIds.add(entry.id)) {
                // Would also trip the ABORT insert, but catching it here means the
                // transaction is never opened for a file that cannot be applied.
                throw InvalidBackupPayloadException("journal entry ${entry.id} appears twice in the backup")
            }
            if (entry.occurredAtEpochMs <= 0L) {
                throw InvalidBackupPayloadException("journal entry ${entry.id} has no timestamp")
            }
            if (entry.triggers.size > MAX_TRIGGERS_PER_ENTRY) {
                throw InvalidBackupPayloadException("journal entry ${entry.id} has too many triggers")
            }
            entry.copy(
                intensity = CravingEntry.sanitizeIntensity(entry.intensity),
                note = entry.note.take(MAX_NOTE_LENGTH),
                triggers = entry.triggers
                    .map { it.trim().take(MAX_TRIGGER_LENGTH) }
                    .filter { it.isNotEmpty() }
                    .distinct(),
                blockedDomain = entry.blockedDomain
                    ?.trim()
                    ?.take(MAX_DOMAIN_LENGTH)
                    ?.takeIf { it.isNotEmpty() },
            )
        }
    }

    private fun validateExceptions(entries: List<DomainExceptionBackup>): List<DomainExceptionBackup> {
        val seenDomains = HashSet<String>(entries.size)
        return entries.map { entry ->
            val domain = entry.normalizedDomain.trim().lowercase()
            if (domain.isEmpty()) {
                throw InvalidBackupPayloadException("a custom exception has no domain")
            }
            if (domain.length > MAX_DOMAIN_LENGTH) {
                throw InvalidBackupPayloadException("custom exception domain is too long")
            }
            if (!seenDomains.add(domain)) {
                throw InvalidBackupPayloadException("custom exception '$domain' appears twice in the backup")
            }
            entry.copy(
                normalizedDomain = domain,
                note = entry.note.take(MAX_NOTE_LENGTH),
                expiresAtEpochMs = entry.expiresAtEpochMs?.takeIf { it > 0L },
                createdAtEpochMs = entry.createdAtEpochMs.takeIf { it > 0L } ?: 0L,
            )
        }
    }

    // FortressWindow windows need no value checks here: the type asserts its own
    // minute-of-day and day-offset invariants in its constructor, so an
    // out-of-range window cannot be decoded into existence at all. Hostile JSON
    // is rejected while it is being read, which the repository reports as an
    // invalid payload before any write is attempted.
}
