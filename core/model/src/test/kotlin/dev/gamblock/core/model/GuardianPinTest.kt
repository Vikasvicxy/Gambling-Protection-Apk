package dev.gamblock.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GuardianPinTest {

    @Test
    fun `only four digit pins are accepted as a format`() {
        assertThat(GuardianPinHasher.isValidFormat("1234")).isTrue()
        assertThat(GuardianPinHasher.isValidFormat("123")).isFalse()
        assertThat(GuardianPinHasher.isValidFormat("12345")).isFalse()
        assertThat(GuardianPinHasher.isValidFormat("12a4")).isFalse()
        assertThat(GuardianPinHasher.isValidFormat("")).isFalse()
    }

    @Test
    fun `a pin verifies against its own hash`() {
        val stored = GuardianPinHasher.hash("2580")

        assertThat(GuardianPinHasher.verify("2580", stored)).isTrue()
    }

    @Test
    fun `a different pin does not verify`() {
        val stored = GuardianPinHasher.hash("2580")

        assertThat(GuardianPinHasher.verify("2581", stored)).isFalse()
    }

    @Test
    fun `a malformed candidate pin never verifies`() {
        val stored = GuardianPinHasher.hash("2580")

        assertThat(GuardianPinHasher.verify("abcd", stored)).isFalse()
        assertThat(GuardianPinHasher.verify("", stored)).isFalse()
    }

    @Test
    fun `two hashes of the same pin differ because the salt is random`() {
        val first = GuardianPinHasher.hash("2580")
        val second = GuardianPinHasher.hash("2580")

        assertThat(first.saltBase64).isNotEqualTo(second.saltBase64)
        assertThat(first.hashBase64).isNotEqualTo(second.hashBase64)
    }

    @Test
    fun `the stored hash never contains the raw pin`() {
        val stored = GuardianPinHasher.hash("2580")

        assertThat(stored.hashBase64).doesNotContain("2580")
        assertThat(stored.saltBase64).doesNotContain("2580")
    }

    @Test
    fun `a corrupt stored hash fails closed`() {
        val stored = GuardianPinHasher.hash("2580")
        val corrupt = stored.copy(hashBase64 = "!!!not-base64!!!")

        assertThat(GuardianPinHasher.verify("2580", corrupt)).isFalse()
    }

    @Test
    fun `failures below the limit do not lock the user out`() {
        var attempt = GuardianPinPolicy.registerSuccess()

        repeat(GuardianPinHasher.MAX_FAILED_ATTEMPTS - 1) {
            attempt = GuardianPinPolicy.registerFailure(attempt, nowEpochMs = 0L)
        }

        assertThat(GuardianPinPolicy.isLocked(attempt, nowEpochMs = 0L)).isFalse()
        assertThat(attempt.failedAttempts)
            .isEqualTo(GuardianPinHasher.MAX_FAILED_ATTEMPTS - 1)
    }

    @Test
    fun `the final failure triggers a cooldown`() {
        var attempt = GuardianPinPolicy.registerSuccess()

        repeat(GuardianPinHasher.MAX_FAILED_ATTEMPTS) {
            attempt = GuardianPinPolicy.registerFailure(attempt, nowEpochMs = 1_000L)
        }

        assertThat(GuardianPinPolicy.isLocked(attempt, nowEpochMs = 1_000L)).isTrue()
        assertThat(GuardianPinPolicy.remainingLockMs(attempt, nowEpochMs = 1_000L))
            .isEqualTo(GuardianPinHasher.LOCKOUT_MS)
    }

    @Test
    fun `the lock expires after the cooldown`() {
        val locked = GuardianPinAttempt(
            failedAttempts = 0,
            lockedUntilEpochMs = 10_000L,
        )

        assertThat(GuardianPinPolicy.isLocked(locked, nowEpochMs = 9_999L)).isTrue()
        assertThat(GuardianPinPolicy.isLocked(locked, nowEpochMs = 10_000L)).isFalse()
    }

    @Test
    fun `failures during a lockout do not extend it`() {
        val locked = GuardianPinAttempt(
            failedAttempts = 0,
            lockedUntilEpochMs = 10_000L,
        )

        val after = GuardianPinPolicy.registerFailure(locked, nowEpochMs = 2_000L)

        assertThat(after).isEqualTo(locked)
    }

    @Test
    fun `a success clears the failure counter`() {
        val failed = GuardianPinAttempt(failedAttempts = 3)

        assertThat(GuardianPinPolicy.registerSuccess().failedAttempts).isEqualTo(0)
        assertThat(failed.failedAttempts).isEqualTo(3)
    }
}
