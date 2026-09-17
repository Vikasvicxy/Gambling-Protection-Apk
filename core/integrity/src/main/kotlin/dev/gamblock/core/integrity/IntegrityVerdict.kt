package dev.gamblock.core.integrity

/**
 * Verdict categories used across the integrity abstraction.
 *
 * Play Integrity verdicts (device/app/account) are only produced by Google's
 * server-side verification of a token. Locally we can only attest that a token
 * was obtained for this install; the decrypted verdict keys arrive after a
 * backend decode step which is deliberately out of scope (Phase 2 is a
 * foundation with no server). These constants mirror the documented verdict
 * vocabulary so downstream code does not depend on the SDK types.
 */
enum class IntegrityVerdict {
    /** Verdict is not known yet (token pending server-side verification). */
    UNKNOWN,
    /** Device integrity (basic/strong) satisfied. */
    DEVICE_INTEGRITY,
    /** App integrity satisfied (signature matches packaging cert). */
    APP_INTEGRITY,
    /** Both device and app integrity satisfied. */
    DEVICE_AND_APP_INTEGRITY,
    /** Evidence failed local checks (nonce/format/freshness). */
    INVALID,
    /** Attestation could not be produced at all. */
    UNAVAILABLE,
}