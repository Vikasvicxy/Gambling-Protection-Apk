package dev.gamblock.protection.vpn

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Behaviour of [NetworkHandoffGate] under a controllable clock.
 *
 * The clock is injected so the deadline tests are deterministic instead of sleeping, which is the
 * difference between a test suite that runs in milliseconds and one that is flaky under CI load.
 */
class NetworkHandoffGateTest {

    private var now = 1_000L
    private val clock = { now }

    private fun gate(maxWaitMs: Long = 3_000L) = NetworkHandoffGate(maxWaitMs = maxWaitMs, clock = clock)

    private suspend fun advance(by: Long, sleep: suspend (Long) -> Unit) {
        now += by
        sleep(0)
    }

    // -------------------------------------------------------------- steady state

    @Test
    fun `forwards immediately when no handoff has ever happened`() {
        val gate = gate()
        assertThat(gate.verdict(0)).isEqualTo(NetworkHandoffGate.Verdict.FORWARD)
        assertThat(gate.isHandoffActive()).isFalse()
        assertThat(gate.pollIntervalMs()).isEqualTo(0L)
    }

    @Test
    fun `a fresh gate reports no handoffs`() {
        assertThat(gate().handoffCount).isEqualTo(0)
    }

    @Test
    fun `awaitStable returns immediately when nothing is in flight`() = runTest {
        val gate = gate()
        var slept = false
        val ok = gate.awaitStable(generation = { 0 }, sleep = { slept = true })
        assertThat(ok).isTrue()
        assertThat(slept).isFalse()
    }

    // --------------------------------------------------------- handoff lifecycle

    @Test
    fun `a handoff in progress parks the query`() {
        val gate = gate()
        gate.onHandoffStart(generation = 4)
        assertThat(gate.verdict(4)).isEqualTo(NetworkHandoffGate.Verdict.WAIT)
        assertThat(gate.isHandoffActive()).isTrue()
    }

    @Test
    fun `a moved generation releases the query without a callback`() {
        val gate = gate()
        gate.onHandoffStart(generation = 4)
        // The generation changed, so the new upstream is live even though no callback arrived.
        assertThat(gate.verdict(5)).isEqualTo(NetworkHandoffGate.Verdict.FORWARD)
    }

    @Test
    fun `onUpstreamGeneration with a new value ends the handoff`() {
        val gate = gate()
        gate.onHandoffStart(generation = 4)
        gate.onUpstreamGeneration(5)
        assertThat(gate.isHandoffActive()).isFalse()
        assertThat(gate.verdict(5)).isEqualTo(NetworkHandoffGate.Verdict.FORWARD)
    }

    @Test
    fun `onUpstreamGeneration with the same value does not end the handoff`() {
        val gate = gate()
        gate.onHandoffStart(generation = 4)
        gate.onUpstreamGeneration(4)
        assertThat(gate.isHandoffActive()).isTrue()
    }

    @Test
    fun `onUpstreamGeneration outside a handoff is a no-op`() {
        val gate = gate()
        gate.onUpstreamGeneration(9)
        assertThat(gate.isHandoffActive()).isFalse()
        assertThat(gate.handoffCount).isEqualTo(0)
    }

    @Test
    fun `repeated handoffs are counted once each`() {
        val gate = gate()
        gate.onHandoffStart(1)
        gate.onHandoffStart(1)
        gate.onHandoffStart(1)
        assertThat(gate.handoffCount).isEqualTo(1)

        gate.onUpstreamGeneration(2)
        gate.onHandoffStart(2)
        assertThat(gate.handoffCount).isEqualTo(2)
    }

    @Test
    fun `reset clears an in-flight handoff`() {
        val gate = gate()
        gate.onHandoffStart(7)
        gate.reset()
        assertThat(gate.isHandoffActive()).isFalse()
        assertThat(gate.parkedCount).isEqualTo(0)
        assertThat(gate.verdict(7)).isEqualTo(NetworkHandoffGate.Verdict.FORWARD)
    }

    // ------------------------------------------------------------------ deadlines

    @Test
    fun `a handoff that never completes expires on its own`() {
        val gate = gate(maxWaitMs = 500L)
        gate.onHandoffStart(1)
        now += 499
        assertThat(gate.verdict(1)).isEqualTo(NetworkHandoffGate.Verdict.WAIT)

        now += 1
        assertThat(gate.verdict(1)).isEqualTo(NetworkHandoffGate.Verdict.GIVE_UP)
        assertThat(gate.isHandoffActive()).isFalse()
    }

    @Test
    fun `an expired handoff stops being reported as active`() {
        val gate = gate(maxWaitMs = 100L)
        gate.onHandoffStart(1)
        now += 100
        assertThat(gate.isHandoffActive()).isFalse()
        assertThat(gate.pollIntervalMs()).isEqualTo(0L)
    }

    @Test
    fun `a poll interval is offered only while the handoff can still succeed`() {
        val gate = gate(maxWaitMs = 100L)
        gate.onHandoffStart(1)
        assertThat(gate.pollIntervalMs()).isGreaterThan(0L)
        now += 100
        assertThat(gate.pollIntervalMs()).isEqualTo(0L)
    }

    // ----------------------------------------------------------------- awaitStable

    @Test
    fun `awaitStable parks until the generation moves, then succeeds`() = runTest {
        val gate = gate()
        gate.onHandoffStart(1)
        var generation = 1L
        var sleeps = 0

        val ok = gate.awaitStable(
            generation = { generation },
            sleep = { ms: Long ->
                sleeps++
                now += ms
                // The replacement upstream lands partway through the wait.
                if (sleeps == 2) generation = 2
            },
        )

        assertThat(ok).isTrue()
        assertThat(sleeps).isEqualTo(2)
        assertThat(gate.isHandoffActive()).isFalse()
    }

    @Test
    fun `awaitStable gives up when the deadline passes and leaves the gate usable`() = runTest {
        val gate = gate(maxWaitMs = 120L)
        gate.onHandoffStart(1)

        val ok = gate.awaitStable(generation = { 1 }, sleep = { ms -> now += ms })

        assertThat(ok).isFalse()
        // Critically, the gate must not be left latched in WAIT, which would strand every later
        // query on a device that simply has no network.
        assertThat(gate.isHandoffActive()).isFalse()
        assertThat(gate.verdict(1)).isEqualTo(NetworkHandoffGate.Verdict.FORWARD)
        assertThat(gate.parkedCount).isEqualTo(0)
    }

    @Test
    fun `a later query succeeds once a timed-out handoff has been cleared`() = runTest {
        val gate = gate(maxWaitMs = 120L)
        gate.onHandoffStart(1)
        assertThat(gate.awaitStable(generation = { 1 }, sleep = { now += it })).isFalse()

        // Network came back properly.
        gate.onHandoffStart(1)
        assertThat(gate.awaitStable(generation = { 2 }, sleep = { now += it })).isTrue()
    }

    @Test
    fun `awaitStable counts the parked queries while waiting`() = runTest {
        val gate = gate(maxWaitMs = 1_000L)
        gate.onHandoffStart(1)
        var generation = 1L
        // parkedCount is a live gauge that is cleared once the handoff settles, so the peak has to
        // be sampled from inside the wait rather than read afterwards.
        var peak = 0

        gate.awaitStable(
            generation = { generation },
            sleep = { ms ->
                now += ms
                peak = maxOf(peak, gate.parkedCount)
                if (gate.parkedCount >= 3) generation = 2
            },
        )

        assertThat(peak).isAtLeast(3)
        assertThat(gate.parkedCount).isEqualTo(0)
    }

    @Test
    fun `awaitStable does not spin once the deadline has already passed`() = runTest {
        val gate = gate(maxWaitMs = 40L)
        gate.onHandoffStart(1)
        // Deadline is already in the past by the time anyone asks.
        now += 40

        var sleeps = 0
        val ok = gate.awaitStable(generation = { 1 }, sleep = { sleeps++; Unit })

        assertThat(ok).isFalse()
        assertThat(sleeps).isEqualTo(0)
    }

    @Test
    fun `a zero-length wait window yields immediately`() = runTest {
        val gate = gate(maxWaitMs = 0L)
        gate.onHandoffStart(1)
        assertThat(gate.verdict(1)).isEqualTo(NetworkHandoffGate.Verdict.GIVE_UP)
        assertThat(gate.awaitStable(generation = { 1 }, sleep = { })).isFalse()
    }

    @Test
    fun `the default window is long enough to cover a real handover`() {
        // Guard against someone "tightening" this into a value that drops queries in normal use.
        assertThat(NetworkHandoffGate.DEFAULT_MAX_WAIT_MS).isAtLeast(1_000L)
    }

    @Test
    fun `the poll interval is short relative to the window`() {
        // Many polls per window is what makes the wait feel instant; one poll per window is not.
        val polls = NetworkHandoffGate.DEFAULT_MAX_WAIT_MS / NetworkHandoffGate.POLL_INTERVAL_MS
        assertThat(polls).isAtLeast(10L)
    }

    @Test
    fun `advance helper keeps the fake clock and the sleep in step`() = runTest {
        val gate = gate(maxWaitMs = 200L)
        gate.onHandoffStart(1)
        advance(50) { now += it }
        assertThat(gate.verdict(1)).isEqualTo(NetworkHandoffGate.Verdict.WAIT)
        advance(200) { now += it }
        assertThat(gate.verdict(1)).isEqualTo(NetworkHandoffGate.Verdict.GIVE_UP)
    }
}
