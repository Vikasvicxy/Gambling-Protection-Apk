package dev.gamblock.core.model

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.serialization.Serializable

@Serializable
data class GuardianPinHash(
    val algorithm: String = GuardianPinHasher.ALGORITHM,
    val iterations: Int = GuardianPinHasher.ITERATIONS,
    val saltBase64: String,
    val hashBase64: String,
)

@Serializable
data class GuardianPinAttempt(
    val failedAttempts: Int = 0,
    val lockedUntilEpochMs: Long = 0L,
) {
    val isLocked: Boolean
        get() = lockedUntilEpochMs > 0L
}

object GuardianPinHasher {

    const val ALGORITHM: String = "PBKDF2WithHmacSHA256"
    const val ITERATIONS: Int = 120_000
    const val KEY_LENGTH_BITS: Int = 256
    const val SALT_LENGTH_BYTES: Int = 16
    const val PIN_LENGTH: Int = 4
    const val MAX_FAILED_ATTEMPTS: Int = 5
    const val LOCKOUT_MS: Long = 30_000L

    private val random = SecureRandom()

    fun isValidFormat(pin: String): Boolean =
        pin.length == PIN_LENGTH && pin.all { it.isDigit() }

    fun newSalt(size: Int = SALT_LENGTH_BYTES): ByteArray =
        ByteArray(size.coerceIn(8, 64)).also(random::nextBytes)

    fun hash(pin: String, salt: ByteArray = newSalt()): GuardianPinHash {
        require(isValidFormat(pin)) { "guardian pin must be $PIN_LENGTH digits" }
        require(salt.size >= 8) { "salt too short" }
        val derived = pbkdf2(pin, salt, ITERATIONS, KEY_LENGTH_BITS)
        return GuardianPinHash(
            algorithm = ALGORITHM,
            iterations = ITERATIONS,
            saltBase64 = encode(salt),
            hashBase64 = encode(derived),
        )
    }

    fun verify(pin: String, stored: GuardianPinHash): Boolean {
        if (!isValidFormat(pin)) return false
        val salt = decode(stored.saltBase64) ?: return false
        if (salt.size < 8) return false
        val expected = decode(stored.hashBase64) ?: return false
        val iterations = stored.iterations.coerceIn(1_000, 1_000_000)
        val actual = pbkdf2(pin, salt, iterations, expected.size * 8)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun pbkdf2(pin: String, salt: ByteArray, iterations: Int, keyLengthBits: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, keyLengthBits)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String): ByteArray? = runCatching {
        java.util.Base64.getDecoder().decode(value)
    }.getOrNull()
}

object GuardianPinPolicy {

    fun isLocked(attempt: GuardianPinAttempt, nowEpochMs: Long): Boolean =
        attempt.lockedUntilEpochMs > nowEpochMs

    fun remainingLockMs(attempt: GuardianPinAttempt, nowEpochMs: Long): Long =
        (attempt.lockedUntilEpochMs - nowEpochMs).coerceAtLeast(0L)

    fun registerSuccess(): GuardianPinAttempt = GuardianPinAttempt()

    fun registerFailure(
        attempt: GuardianPinAttempt,
        nowEpochMs: Long,
        maxAttempts: Int = GuardianPinHasher.MAX_FAILED_ATTEMPTS,
        lockoutMs: Long = GuardianPinHasher.LOCKOUT_MS,
    ): GuardianPinAttempt {
        if (isLocked(attempt, nowEpochMs)) return attempt
        val failures = attempt.failedAttempts + 1
        return if (failures >= maxAttempts.coerceAtLeast(1)) {
            GuardianPinAttempt(failedAttempts = 0, lockedUntilEpochMs = nowEpochMs + lockoutMs.coerceAtLeast(0L))
        } else {
            attempt.copy(failedAttempts = failures)
        }
    }
}
