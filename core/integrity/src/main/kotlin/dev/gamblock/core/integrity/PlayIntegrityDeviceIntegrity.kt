package dev.gamblock.core.integrity

import java.util.Base64
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Production [DeviceIntegrity] adapter driving the Play Integrity standard API
 * through the [StandardIntegrityClient] seam.
 *
 * Failure or unavailability never throws and never crashes startup: outages
 * surface as [IntegrityResult.Unavailable] / [IntegrityResult.Failure], which
 * callers report as an [IntegrityRisk] signal. The returned token is only
 * envelope-checked locally; real verdicts require server-side verification.
 */
class PlayIntegrityDeviceIntegrity(
    private val client: StandardIntegrityClient,
    private val nonceProvider: NonceProvider,
) : DeviceIntegrity {

    override suspend fun attest(
        packageName: String,
        certificateDigestSha256: String,
        config: IntegrityConfig,
    ): IntegrityResult {
        if (!config.enabled) return IntegrityResult.Unavailable("integrity disabled by configuration")
        if (!client.isAvailable()) return IntegrityResult.Unavailable("StandardIntegrity API not available")

        val nonce = nonceProvider.nonce()
        val token = runCatching {
            withTimeoutOrNull(config.timeoutMs) {
                client.requestToken(nonce, config.cloudProjectNumber)
            }
        }.getOrElse { t ->
            return IntegrityResult.Failure("integrity request failed: ${t.message}", retriable = true)
        }
        if (token == null) {
            return IntegrityResult.Failure(
                "integrity request timed out after ${config.timeoutMs} ms",
                retriable = true,
            )
        }

        return IntegrityResult.Success(
            IntegrityAttestation(
                provider = PROVIDER,
                nonce = Base64.getEncoder().encodeToString(nonce),
                packageName = packageName,
                appCertificateDigestSha256 = certificateDigestSha256,
                token = token,
                obtainedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    companion object {
        const val PROVIDER = "play_integrity_standard"
    }
}