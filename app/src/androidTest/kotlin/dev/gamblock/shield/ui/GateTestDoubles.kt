package dev.gamblock.shield.ui

import dev.gamblock.core.common.clock.WallClock
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.data.preferences.GuardianPinUnlockState
import dev.gamblock.data.repository.ProtectionActionResult
import dev.gamblock.data.repository.ProtectionGateEvaluator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Correct PIN used by [FakeGateEvaluator]; any other value is rejected. */
const val CORRECT_PIN: String = "1357"

/**
 * A clock the test moves by hand, so countdown and cooldown windows are
 * exercised without sleeping.
 */
class MutableClock(var nowMs: Long = 1_000L) : WallClock {
    override fun nowEpochMillis(): Long = nowMs
}

/**
 * Stands in for the real gatekeeper so these UI tests exercise the dialog
 * rather than the persistence layer. [pinRequired] and [timerEnabled] model the
 * user's settings; [unlocked] mirrors the single-use Guardian unlock.
 */
class FakeGateEvaluator : ProtectionGateEvaluator {
    var fortressLocked: Boolean = false
    var pinRequired: Boolean = false
    var timerEnabled: Boolean = false
    var unlocked: Boolean = false
    var relockCount: Int = 0
    var wrongState: GuardianPinUnlockState = GuardianPinUnlockState.Wrong(remainingAttempts = 2)

    private fun gate(timerApplies: Boolean): ProtectionActionResult = when {
        fortressLocked -> ProtectionActionResult.FortressLocked("Fri 18:00 - Sat 06:00")
        pinRequired && !unlocked -> ProtectionActionResult.GuardianPinRequired
        timerApplies -> ProtectionActionResult.UrgeTimerRequired
        else -> ProtectionActionResult.Allowed
    }

    override suspend fun evaluateDisable(): ProtectionActionResult =
        gate(timerApplies = timerEnabled)

    override suspend fun evaluateSensitiveChange(requiresUrgeTimer: Boolean): ProtectionActionResult =
        gate(timerApplies = requiresUrgeTimer && timerEnabled)

    override suspend fun verifyGuardianPin(pin: String): Boolean {
        if (pin != CORRECT_PIN) return false
        unlocked = true
        return true
    }

    override suspend fun reLockGuardian() {
        unlocked = false
        relockCount++
    }

    override fun guardianUnlockState(): GuardianPinUnlockState =
        if (unlocked) GuardianPinUnlockState.Unlocked else wrongState
}

/**
 * Runs everything inline on the calling thread so `submitPinBlocking` and
 * `dismiss` take effect before the next assertion, instead of racing the main
 * looper.
 */
object ImmediateDispatchers : DispatchersProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
    override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
    override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
    override val dnsUpstream: CoroutineDispatcher get() = Dispatchers.Unconfined
}
