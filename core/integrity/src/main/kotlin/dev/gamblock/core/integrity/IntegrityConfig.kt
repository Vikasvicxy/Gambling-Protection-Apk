package dev.gamblock.core.integrity

/**
 * Static, secret-free configuration for integrity attestation.
 *
 * [cloudProjectNumber] is only load-bearing once a real Play Integrity client
 * is bound (it is the numeric Google Cloud project used by
 * StandardIntegrityManager). It has no default because guessing one is worse
 * than failing fast; the provided value in the module graph is a documented
 * placeholder that must be replaced before shipping a wired client.
 */
data class IntegrityConfig(
    val cloudProjectNumber: Long,
    val timeoutMs: Long = 10_000L,
    /** Master switch; false keeps the provider permanently unavailable. */
    val enabled: Boolean = true,
)