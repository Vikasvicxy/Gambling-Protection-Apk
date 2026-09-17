package dev.gamblock.core.integrity

/**
 * Compact integrity posture for health/UI surfaces. A failed or unavailable
 * attestation is reported as a risk signal; it never blocks or crashes startup.
 */
enum class IntegrityRisk {
    /** No attestation could be produced (provider absent, disabled or unconfigured). */
    UNVERIFIED,

    /** Attestation produced; token pending server-side verification. */
    PENDING,

    /** A provider was present but failed; operational risk flagged for retry. */
    ERROR,
}

/** Maps an attestation outcome onto an [IntegrityRisk] signal. Caller-safe: never throws. */
fun IntegrityResult.asRisk(): IntegrityRisk = when (this) {
    is IntegrityResult.Success -> IntegrityRisk.PENDING
    is IntegrityResult.Unavailable -> IntegrityRisk.UNVERIFIED
    is IntegrityResult.Failure -> IntegrityRisk.ERROR
}