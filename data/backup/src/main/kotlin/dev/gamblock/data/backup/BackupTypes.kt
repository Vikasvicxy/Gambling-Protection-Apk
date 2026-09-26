package dev.gamblock.data.backup

/**
 * A payload decrypted and fully validated, but not yet written anywhere.
 *
 * Restore is deliberately two-phase. The user picks a file and types a
 * passphrase, sees exactly what is inside it, and only then agrees to have the
 * current recovery data replaced. Holding the validated payload in memory
 * between those steps also means the confirmation screen and the write operate
 * on the same bytes, so what was shown is what gets applied.
 */
class PreparedRestore internal constructor(
    val payload: dev.gamblock.core.model.RecoveryBackupPayload,
    val summary: dev.gamblock.core.model.BackupSummary,
    /** Bytes written to the destination, for the export result. */
    val byteCount: Int = 0,
)

/**
 * The decrypted payload is structurally valid JSON but the values cannot be
 * trusted as recovery data, so it is refused before any write happens.
 */
class InvalidBackupPayloadException(message: String, cause: Throwable? = null) :
    java.security.GeneralSecurityException(message, cause)

/** The export or restore could not be completed (I/O, storage permission). */
class BackupStorageException(message: String, cause: Throwable? = null) :
    java.io.IOException(message, cause)
