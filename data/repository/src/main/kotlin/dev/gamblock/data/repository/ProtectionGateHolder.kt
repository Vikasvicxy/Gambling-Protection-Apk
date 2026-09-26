package dev.gamblock.data.repository

import dev.gamblock.core.common.clock.WallClock
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.model.UrgeTimerConfig
import dev.gamblock.core.model.UrgeTimerMachine
import dev.gamblock.core.model.UrgeTimerState
import dev.gamblock.data.preferences.GuardianPinUnlockState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class GateKind { None, FortressLocked, PinRequired, UrgeTimer }

/**
 * State for the single shared "you cannot do that yet" dialog. A Fortress
 * window always wins, then the Guardian PIN, then the urge timer.
 */
data class ProtectionGateUiState(
    val kind: GateKind = GateKind.None,
    val fortressLabel: String? = null,
    val pinMessage: String? = null,
    val pinLockedUntilRemainingMs: Long = 0L,
    val remainingAttempts: Int = 0,
    /** Urge timer: epoch ms when the countdown ends, and whether the pledge is met. */
    val urgeUnlockAtEpochMs: Long = 0L,
    val urgePledgeAccepted: Boolean = false,
    /** The exact text the user has to type before the pledge counts. */
    val urgePledgePhrase: String = UrgeTimerConfig.DEFAULT_PLEDGE,
) {
    val isVisible: Boolean
        get() = kind != GateKind.None

    /**
     * Milliseconds left on the countdown. Derived from [urgeUnlockAtEpochMs]
     * rather than published on a timer, so the gate decision never depends on
     * when a UI refresh last happened.
     */
    fun remainingMs(nowMs: Long): Long = (urgeUnlockAtEpochMs - nowMs).coerceAtLeast(0L)

    fun matchesPledge(text: String): Boolean =
        text.trim().equals(urgePledgePhrase.trim(), ignoreCase = true)
}

/**
 * Owns the gate state for every destructive protection action. Screens ask
 * [request] to run an action; if a gate applies the action is parked and only
 * runs once the user clears the gate. Because this is a singleton consulted
 * by every entry point, no screen can turn protection off by skipping a dialog.
 *
 * The cooling-off timer lives here rather than in [ProtectionCommandCoordinator]
 * because the coordinator only reports that the timer is *enabled*; the elapsed
 * countdown and the pledge are per-attempt state that must not reset the moment
 * a later gate is cleared.
 */
@Singleton
class ProtectionGateHolder @Inject constructor(
    private val coordinator: ProtectionGateEvaluator,
    private val dispatchers: DispatchersProvider,
    private val wallClock: WallClock,
) {

    private enum class GateOperation {
        /** Turning protection off: Fortress, then PIN, then the cooling-off timer. */
        DestructiveDisable,

        /** Weakening or erasing settings: Fortress, then PIN, never the timer. */
        SensitiveChange,
    }

    private val _state = MutableStateFlow(ProtectionGateUiState())
    val state: StateFlow<ProtectionGateUiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
    private val timerConfig = UrgeTimerConfig(durationMs = URGE_TIMER_DURATION_MS)
    private val timerMachine = UrgeTimerMachine(timerConfig)

    private var pending: (suspend () -> Unit)? = null
    private var pendingKind: GateOperation = GateOperation.DestructiveDisable

    /** True once the parked action has served its 15 minutes and pledged. */
    private var timerCleared = false

    /**
     * Runs [action] immediately when no gate applies; otherwise parks it behind
     * the strongest applicable gate and returns the verdict so callers can show
     * their own inline messaging if they want to.
     */
    suspend fun request(action: suspend () -> Unit): ProtectionActionResult =
        begin(action, GateOperation.DestructiveDisable)

    /**
     * Same as [request] but for changes that should not require the cooling-off
     * timer (settings edits, whitelists, history clears, recovery date edits).
     */
    suspend fun requestSensitiveChange(action: suspend () -> Unit): ProtectionActionResult =
        begin(action, GateOperation.SensitiveChange)

    private suspend fun begin(
        action: suspend () -> Unit,
        operation: GateOperation,
    ): ProtectionActionResult {
        // A new intent never inherits a previous authorisation, so anything
        // already parked is dropped and the Guardian is re-armed.
        abandon()
        val result = evaluate(operation)
        if (result == ProtectionActionResult.Allowed) {
            runAction(action)
        } else {
            pending = action
            pendingKind = operation
            showGate(result)
        }
        return result
    }

    private suspend fun evaluate(operation: GateOperation): ProtectionActionResult = when (operation) {
        GateOperation.DestructiveDisable -> coordinator.evaluateDisable()
        GateOperation.SensitiveChange -> coordinator.evaluateSensitiveChange()
    }

    private suspend fun runAction(action: suspend () -> Unit) {
        timerMachine.reset()
        try {
            action()
        } finally {
            // The PIN authorises exactly one action, so the unlock never outlives
            // the call even when the action itself throws.
            coordinator.reLockGuardian()
        }
    }

    private fun showGate(result: ProtectionActionResult) {
        when (result) {
            is ProtectionActionResult.FortressLocked -> _state.value = ProtectionGateUiState(
                kind = GateKind.FortressLocked,
                fortressLabel = result.windowLabel,
            )
            ProtectionActionResult.GuardianPinRequired -> _state.value = ProtectionGateUiState(
                kind = GateKind.PinRequired,
                urgePledgePhrase = timerConfig.pledgePhrase,
            )
            ProtectionActionResult.UrgeTimerRequired -> startTimer()
            ProtectionActionResult.Allowed, is ProtectionActionResult.Rejected ->
                _state.value = ProtectionGateUiState()
        }
    }

    private fun startTimer() {
        val nowMs = wallClock.nowEpochMillis()
        timerMachine.start(nowMs)
        _state.value = ProtectionGateUiState(
            kind = GateKind.UrgeTimer,
            urgeUnlockAtEpochMs = nowMs + URGE_TIMER_DURATION_MS,
            urgePledgePhrase = timerConfig.pledgePhrase,
        )
    }

    /** Verifies the typed PIN and continues the gate chain on success. */
    suspend fun submitPin(pin: String): Boolean {
        val verified = coordinator.verifyGuardianPin(pin)
        if (verified) {
            // The action is still parked, so re-checking here can escalate to the
            // cooling-off timer instead of silently dropping the request.
            _state.update { it.copy(kind = GateKind.None, pinMessage = null) }
            completeChain()
            return true
        }
        val unlockState = coordinator.guardianUnlockState()
        _state.update { current ->
            current.copy(
                kind = GateKind.PinRequired,
                pinMessage = messageFor(unlockState),
                pinLockedUntilRemainingMs = lockRemaining(unlockState),
                remainingAttempts = remainingAttempts(unlockState),
            )
        }
        return false
    }

    /** Non-suspending wrapper for Compose callbacks that cannot launch coroutines. */
    fun submitPinBlocking(pin: String) {
        scope.launch { submitPin(pin) }
    }

    fun submitPledgeBlocking(text: String) {
        scope.launch { completeChainAfterPledge(text) }
    }

    private suspend fun completeChainAfterPledge(text: String) {
        submitPledge(text)
        if (_state.value.urgePledgeAccepted) completeChain()
    }

    /**
     * Marks the pledge as accepted once the countdown has elapsed. The parked
     * action still only runs when every gate in the chain is satisfied, so a
     * pledge alone never skips a Fortress lock or the PIN.
     */
    fun submitPledge(text: String) {
        if (_state.value.kind != GateKind.UrgeTimer) return
        timerMachine.tick(wallClock.nowEpochMillis())
        timerMachine.submitPledge(text)
        _state.update {
            it.copy(urgePledgeAccepted = timerMachine.state is UrgeTimerState.Unlocked)
        }
    }

    /**
     * Runs the parked action once every gate has cleared. Returns true when the
     * action actually ran, so callers can show a confirmation.
     *
     * The action stays parked across every escalation: a correct PIN that runs
     * into the cooling-off timer parks the same request behind the timer, and a
     * pledge that still needs a PIN parks it behind the PIN. Only a final
     * Allowed verdict consumes it.
     */
    suspend fun completeChain(): Boolean {
        val action = pending ?: return false
        val current = _state.value
        when (current.kind) {
            // Absolute and user-cleared gates: only the next evaluation moves on.
            GateKind.FortressLocked, GateKind.PinRequired -> return false
            GateKind.UrgeTimer -> {
                val expired = current.remainingMs(wallClock.nowEpochMillis()) <= 0L
                if (!(expired && current.urgePledgeAccepted)) return false
                timerCleared = true
            }
            GateKind.None -> Unit
        }

        // Re-evaluate: a Fortress window may have opened, or the PIN may still be
        // standing, while the user was typing out the pledge.
        val result = evaluate(pendingKind)
        // The standing UrgeTimerRequired verdict no longer blocks once this
        // attempt has served its countdown; every other gate is still honoured,
        // so a PIN requirement can never be mistaken for consent.
        val cleared = result == ProtectionActionResult.Allowed ||
            (result == ProtectionActionResult.UrgeTimerRequired && timerCleared)
        if (!cleared) {
            showGate(result)
            return false
        }

        pending = null
        pendingKind = GateOperation.DestructiveDisable
        timerCleared = false
        _state.value = ProtectionGateUiState()
        runAction(action)
        return true
    }

    fun dismiss() {
        scope.launch { abandon() }
    }

    /**
     * Abandons the parked action and makes sure no authorisation survives it, so
     * dismissing after a correct PIN still forces the next attempt to re-enter
     * it.
     */
    private suspend fun abandon() {
        pending = null
        pendingKind = GateOperation.DestructiveDisable
        timerCleared = false
        timerMachine.cancel()
        _state.value = ProtectionGateUiState()
        coordinator.reLockGuardian()
    }

    fun pinState(): GuardianPinUnlockState = coordinator.guardianUnlockState()

    private fun messageFor(state: GuardianPinUnlockState): String? = when (state) {
        is GuardianPinUnlockState.Locked -> null
        is GuardianPinUnlockState.Wrong ->
            "Incorrect PIN. ${state.remainingAttempts} attempts left before a cooldown."
        else -> "Incorrect PIN."
    }

    private fun lockRemaining(state: GuardianPinUnlockState): Long =
        (state as? GuardianPinUnlockState.Locked)?.remainingMs ?: 0L

    private fun remainingAttempts(state: GuardianPinUnlockState): Int =
        (state as? GuardianPinUnlockState.Wrong)?.remainingAttempts ?: 0

    companion object {
        const val URGE_TIMER_DURATION_MS: Long = 15L * 60L * 1000L
    }
}
