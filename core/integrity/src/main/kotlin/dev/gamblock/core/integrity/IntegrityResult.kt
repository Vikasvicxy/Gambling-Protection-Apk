package dev.gamblock.core.integrity

/** Result of a [DeviceIntegrity.attest] call. */
sealed interface IntegrityResult {
    /** Attestation was produced and passed local envelope checks. */
    data class Success(val attestation: IntegrityAttestation) : IntegrityResult

    /** The integrity API is not available on this device / build. */
    data class Unavailable(val reason: String) : IntegrityResult

    /** The provider failed while producing evidence (request may be retried). */
    data class Failure(val reason: String, val retriable: Boolean) : IntegrityResult
}