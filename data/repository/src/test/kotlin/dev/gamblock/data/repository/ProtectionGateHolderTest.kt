package dev.gamblock.data.repository

import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.common.clock.WallClock
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.model.UrgeTimerConfig
import dev.gamblock.data.preferences.GuardianPinUnlockState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the shared destructive-action gate chain. Every case maps
 * to a way the chain could be talked into running a destructive action: dropping
 * the parked action when a later gate escalates, treating a standing PIN
 * requirement as consent, restarting the countdown forever, or leaving the
 * Guardian unlocked after a dismissal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProtectionGateHolderTest {

    private val scheduler = TestCoroutineScheduler()
    private val testDispatcher = UnconfinedTestDispatcher(scheduler)
    private val dispatchers = object : DispatchersProvider {
        override val main: CoroutineDispatcher get() = testDispatcher
        override val io: CoroutineDispatcher get() = testDispatcher
        override val default: CoroutineDispatcher get() = testDispatcher
        override val dnsUpstream: CoroutineDispatcher get() = testDispatcher
    }

    private class FakeClock(var nowMs: Long = 1_000L) : WallClock {
        override fun nowEpochMillis(): Long = nowMs
    }

    /**
     * Stands in for the real gatekeeper. [fortressLocked], [pinRequired] and
     * [timerEnabled] model the user's settings, and [unlocked] mirrors the
     * single-use Guardian unlock that [reLockGuardian] clears.
     */
    private class FakeEvaluator : ProtectionGateEvaluator {
        var fortressLocked = false
        var pinRequired = false
        var timerEnabled = false
        var unlocked = false
        var relockCount = 0
        var wrongState: GuardianPinUnlockState = GuardianPinUnlockState.Wrong(2)

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

    private lateinit var clock: FakeClock
    private lateinit var evaluator: FakeEvaluator
    private lateinit var holder: ProtectionGateHolder

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        clock = FakeClock()
        evaluator = FakeEvaluator()
        holder = ProtectionGateHolder(evaluator, dispatchers, clock)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Moves the fake clock past the countdown. Nothing is animating in the
     * holder, so this is the only thing needed to satisfy the timer.
     */
    private fun runOutTimer() {
        clock.nowMs += ProtectionGateHolder.URGE_TIMER_DURATION_MS
    }

    private fun runOutTimerAndPledge() {
        runOutTimer()
        holder.submitPledgeBlocking(UrgeTimerConfig.DEFAULT_PLEDGE)
    }

    @Test
    fun `runs immediately when no gate applies`() = runTest(testDispatcher) {
        var ran = false
        val result = holder.request { ran = true }

        assertThat(result).isEqualTo(ProtectionActionResult.Allowed)
        assertThat(ran).isTrue()
        assertThat(holder.state.value.isVisible).isFalse()
    }

    @Test
    fun `fortress lock parks the action and never releases it`() = runTest(testDispatcher) {
        evaluator.fortressLocked = true
        var ran = false
        holder.request { ran = true }

        assertThat(holder.state.value.kind).isEqualTo(GateKind.FortressLocked)
        assertThat(ran).isFalse()

        // Lifting every other gate must still not release a fortress-locked action.
        evaluator.fortressLocked = false
        evaluator.pinRequired = false
        evaluator.timerEnabled = false
        assertThat(holder.completeChain()).isFalse()
        assertThat(ran).isFalse()
    }

    @Test
    fun `a correct PIN that meets the timer keeps the parked action`() = runTest(testDispatcher) {
        // The original bug: escalating PIN -> timer cleared `pending`, so the
        // destructive action could never complete.
        evaluator.pinRequired = true
        evaluator.timerEnabled = true
        var ran = false
        holder.request { ran = true }

        assertThat(holder.state.value.kind).isEqualTo(GateKind.PinRequired)
        assertThat(holder.submitPin(CORRECT_PIN)).isTrue()

        // The PIN only clears the PIN gate; the timer still applies.
        assertThat(holder.state.value.kind).isEqualTo(GateKind.UrgeTimer)
        assertThat(ran).isFalse()

        runOutTimerAndPledge()

        assertThat(ran).isTrue()
        assertThat(holder.state.value.isVisible).isFalse()
    }

    @Test
    fun `a pledge never runs an action that the PIN still guards`() = runTest(testDispatcher) {
        evaluator.timerEnabled = true
        var ran = false
        holder.request { ran = true }
        assertThat(holder.state.value.kind).isEqualTo(GateKind.UrgeTimer)

        // The PIN becomes required only after the timer gate is already showing.
        evaluator.pinRequired = true
        runOutTimerAndPledge()

        // The pledge must park the action behind the PIN, not treat it as consent.
        assertThat(ran).isFalse()
        assertThat(holder.state.value.kind).isEqualTo(GateKind.PinRequired)

        assertThat(holder.submitPin(CORRECT_PIN)).isTrue()
        assertThat(ran).isTrue()
    }

    @Test
    fun `a wrong PIN is rejected and the action stays parked`() = runTest(testDispatcher) {
        evaluator.pinRequired = true
        var ran = false
        holder.request { ran = true }

        assertThat(holder.submitPin("0000")).isFalse()

        assertThat(ran).isFalse()
        assertThat(holder.state.value.kind).isEqualTo(GateKind.PinRequired)
        assertThat(holder.state.value.pinMessage).isNotNull()
    }

    @Test
    fun `a wrong pledge is rejected and the action stays parked`() = runTest(testDispatcher) {
        evaluator.timerEnabled = true
        var ran = false
        holder.request { ran = true }

        runOutTimer()
        holder.submitPledgeBlocking("abc")

        assertThat(ran).isFalse()
        assertThat(holder.state.value.urgePledgeAccepted).isFalse()
    }

    @Test
    fun `dismissing after a correct PIN re-locks the Guardian`() = runTest(testDispatcher) {
        evaluator.pinRequired = true
        evaluator.timerEnabled = true
        var ran = false
        holder.request { ran = true }
        assertThat(holder.submitPin(CORRECT_PIN)).isTrue()
        assertThat(holder.state.value.kind).isEqualTo(GateKind.UrgeTimer)
        assertThat(evaluator.unlocked).isTrue()

        holder.dismiss()

        // A verified PIN must not survive the dismissal, or the next attempt
        // would sail straight through.
        assertThat(evaluator.unlocked).isFalse()
        assertThat(ran).isFalse()

        val second = holder.request { ran = true }
        assertThat(second).isEqualTo(ProtectionActionResult.GuardianPinRequired)
    }

    @Test
    fun `a new request never inherits a previous authorisation`() = runTest(testDispatcher) {
        evaluator.pinRequired = true
        evaluator.timerEnabled = true
        var firstRan = false
        holder.request { firstRan = true }
        assertThat(holder.submitPin(CORRECT_PIN)).isTrue()

        // Abandoned mid-chain: the Guardian is re-armed for the new intent.
        var secondRan = false
        val result = holder.request { secondRan = true }

        assertThat(evaluator.unlocked).isFalse()
        assertThat(result).isEqualTo(ProtectionActionResult.GuardianPinRequired)
        assertThat(firstRan).isFalse()
        assertThat(secondRan).isFalse()
    }

    @Test
    fun `sensitive changes never demand the cooling-off timer`() = runTest(testDispatcher) {
        evaluator.timerEnabled = true
        var ran = false
        val result = holder.requestSensitiveChange { ran = true }

        assertThat(result).isEqualTo(ProtectionActionResult.Allowed)
        assertThat(ran).isTrue()
        assertThat(holder.state.value.isVisible).isFalse()
    }

    @Test
    fun `sensitive changes still require the PIN`() = runTest(testDispatcher) {
        evaluator.pinRequired = true
        var ran = false
        holder.requestSensitiveChange { ran = true }

        assertThat(holder.state.value.kind).isEqualTo(GateKind.PinRequired)
        assertThat(ran).isFalse()

        assertThat(holder.submitPin(CORRECT_PIN)).isTrue()
        assertThat(ran).isTrue()
    }

    @Test
    fun `a fortress window opening mid-chain re-blocks the action`() = runTest(testDispatcher) {
        evaluator.timerEnabled = true
        var ran = false
        holder.request { ran = true }

        // The window opens after the user served the countdown but before they
        // handed the pledge back.
        runOutTimer()
        evaluator.fortressLocked = true
        holder.submitPledgeBlocking(UrgeTimerConfig.DEFAULT_PLEDGE)

        assertThat(ran).isFalse()
        assertThat(holder.state.value.kind).isEqualTo(GateKind.FortressLocked)
    }

    @Test
    fun `the countdown does not restart once served`() = runTest(testDispatcher) {
        evaluator.timerEnabled = true
        var ran = false
        holder.request { ran = true }
        assertThat(holder.state.value.kind).isEqualTo(GateKind.UrgeTimer)

        runOutTimerAndPledge()

        // The coordinator keeps reporting UrgeTimerRequired because the toggle is
        // still on; that standing verdict must not park the action forever.
        assertThat(ran).isTrue()
        assertThat(holder.state.value.isVisible).isFalse()
    }

    @Test
    fun `the action runs exactly once`() = runTest(testDispatcher) {
        var count = 0
        holder.request { count++ }

        // A later completion attempt must not replay the action.
        assertThat(holder.completeChain()).isFalse()
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `the pledge phrase is the one the timer machine enforces`() = runTest(testDispatcher) {
        evaluator.timerEnabled = true
        holder.request { }
        val phrase = holder.state.value.urgePledgePhrase

        assertThat(phrase).isEqualTo(UrgeTimerConfig.DEFAULT_PLEDGE)
        assertThat(holder.state.value.matchesPledge(phrase)).isTrue()
        assertThat(holder.state.value.matchesPledge("abc")).isFalse()
    }

    private companion object {
        const val CORRECT_PIN = "1234"
    }
}
