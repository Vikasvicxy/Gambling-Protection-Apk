package dev.gamblock.core.integrity

import java.security.SecureRandom

/**
 * Provides a fresh nonce per attestation request. Play Integrity requires a
 * unique, unpredictable nonce per request so a captured token cannot be
 * replayed against a different request.
 */
interface NonceProvider {
    /** Returns a fresh unpredictable nonce (byte array). */
    fun nonce(): ByteArray
}

/** [NonceProvider] backed by [SecureRandom] (32 bytes per request). */
class SecureNonceProvider : NonceProvider {
    private val random = SecureRandom()

    override fun nonce(): ByteArray = ByteArray(32).also(random::nextBytes)
}