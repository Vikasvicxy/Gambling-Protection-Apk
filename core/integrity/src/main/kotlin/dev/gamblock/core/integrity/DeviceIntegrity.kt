package dev.gamblock.core.integrity

/**
 * Seam for acquiring attestation evidence about this install.
 *
 * The standard production client is Play Integrity's `StandardIntegrityManager`
 * (see [StandardIntegrityClient] for the exact contract). A real binding is a
 * deliberate follow-up: it requires the `com.google.android.play:integrity`
 * artifact, a Google Cloud project number, a server to verify the returned
 * token, and a device signed with a production keystore. Until then the app
 * ships with [UnavailableDeviceIntegrity], which fails closed without crashing.
 */
interface DeviceIntegrity {
    /**
     * Requests attestation evidence bound to a fresh nonce.
     *
     * Implementations must be safe to call from any dispatcher and must never
     * throw; errors surface as [IntegrityResult.Failure] / [IntegrityResult.Unavailable].
     */
    suspend fun attest(
        packageName: String,
        certificateDigestSha256: String,
        config: IntegrityConfig,
    ): IntegrityResult
}

/**
 * Documented contract of the Play Integrity standard API, kept as an interface
 * so the future adapter has a compile-checked target. NOT invoked today; the
 * real class is `com.google.android.play.core.integrity.StandardIntegrityManager`.
 */
interface StandardIntegrityClient {
    /**
     * Returns true when Google Play Services exposes a usable integrity API.
     */
    fun isAvailable(): Boolean

    /**
     * Requests a token for [unavailableText]-style request; the concrete SDK
     * signature is `requestStandardIntegrityToken(StandardIntegrityTokenRequest)`.
     * Implementations decode the returned token's `token()` string.
     */
    suspend fun requestToken(nonce: ByteArray, cloudProjectNumber: Long): String
}