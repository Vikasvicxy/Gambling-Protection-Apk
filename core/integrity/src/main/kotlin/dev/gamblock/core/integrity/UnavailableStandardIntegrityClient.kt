package dev.gamblock.core.integrity

/**
 * Development / fail-closed [StandardIntegrityClient]. Used for local and debug
 * builds where no Play Integrity binding is configured. It reports the API as
 * unavailable and never fabricates a token; the enclosing [DeviceIntegrity]
 * falls back to [UnavailableDeviceIntegrity] before this is ever called.
 */
class UnavailableStandardIntegrityClient : StandardIntegrityClient {

    override fun isAvailable(): Boolean = false

    override suspend fun requestToken(nonce: ByteArray, cloudProjectNumber: Long): String =
        throw UnsupportedOperationException(
            "Play Integrity client not configured; invoke only when isAvailable() is true",
        )
}