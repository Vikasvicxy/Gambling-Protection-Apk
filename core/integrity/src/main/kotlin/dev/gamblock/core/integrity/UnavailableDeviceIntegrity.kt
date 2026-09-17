package dev.gamblock.core.integrity

/**
 * Fail-closed device-integrity provider used when no real integrity API is
 * bound (no Play Services requirement, no Google Cloud project, debug builds).
 *
 * It never throws and never fabricates evidence: attestation always reports
 * [IntegrityResult.Unavailable]. This is deliberate - a "fake authenticated"
 * verdict would be worse than none.
 */
class UnavailableDeviceIntegrity : DeviceIntegrity {
    override suspend fun attest(
        packageName: String,
        certificateDigestSha256: String,
        config: IntegrityConfig,
    ): IntegrityResult = IntegrityResult.Unavailable(
        "no integrity provider bound; Play Integrity adapter is a Phase 2 follow-up",
    )
}