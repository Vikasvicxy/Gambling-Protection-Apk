package dev.gamblock.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UrgeTimerMachineTest {

    private val config = UrgeTimerConfig(
        durationMs = 60_000L,
        pledgePhrase = "I choose my future over a bet",
    )
    private val machine = UrgeTimerMachine(config)

    @Test
    fun `starts idle and not locked`() {
        val snapshot = machine.snapshot()

        assertThat(snapshot.state).isEqualTo(UrgeTimerState.Idle)
        assertThat(snapshot.locked).isFalse()
        assertThat(snapshot.canCancel).isFalse()
        assertThat(snapshot.canSubmitPledge).isFalse()
    }

    @Test
    fun `starting locks the timer and cannot be cancelled from idle`() {
        machine.start(nowEpochMs = 0L)

        val snapshot = machine.snapshot()
        assertThat(snapshot.state).isInstanceOf(UrgeTimerState.Counting::class.java)
        assertThat(snapshot.locked).isTrue()
        assertThat(snapshot.canCancel).isTrue()
    }

    @Test
    fun `countdown decreases as time passes`() {
        machine.start(nowEpochMs = 0L)

        val midway = machine.tick(nowEpochMs = 30_000L) as UrgeTimerState.Counting

        assertThat(midway.remainingMs).isEqualTo(30_000L)
        assertThat(midway.remainingSeconds).isEqualTo(30L)
        assertThat(midway.progress).isWithin(0.001f).of(0.5f)
    }

    @Test
    fun `the pledge cannot be submitted before the timer expires`() {
        machine.start(nowEpochMs = 0L)
        machine.tick(nowEpochMs = 59_000L)

        val state = machine.submitPledge(config.pledgePhrase)

        assertThat(state).isInstanceOf(UrgeTimerState.Counting::class.java)
        assertThat(machine.snapshot().canSubmitPledge).isFalse()
    }

    @Test
    fun `expiry moves the machine to awaiting pledge`() {
        machine.start(nowEpochMs = 0L)

        val state = machine.tick(nowEpochMs = 60_000L)

        assertThat(state).isInstanceOf(UrgeTimerState.AwaitingPledge::class.java)
        assertThat(machine.snapshot().canSubmitPledge).isTrue()
    }

    @Test
    fun `an incorrect pledge does not unlock and counts the attempt`() {
        machine.start(nowEpochMs = 0L)
        machine.tick(nowEpochMs = 60_000L)

        val state = machine.submitPledge("just one more bet") as UrgeTimerState.AwaitingPledge

        assertThat(state.attempts).isEqualTo(1)
        assertThat(state.lastPledgeAccepted).isFalse()
    }

    @Test
    fun `the correct pledge unlocks after expiry`() {
        machine.start(nowEpochMs = 0L)
        machine.tick(nowEpochMs = 60_000L)

        val state = machine.submitPledge(config.pledgePhrase)

        assertThat(state).isEqualTo(UrgeTimerState.Unlocked)
        assertThat(machine.snapshot().locked).isFalse()
    }

    @Test
    fun `pledge matching ignores case and surrounding whitespace`() {
        machine.start(nowEpochMs = 0L)
        machine.tick(nowEpochMs = 60_000L)

        val state = machine.submitPledge("  I CHOOSE MY FUTURE OVER A BET  ")

        assertThat(state).isEqualTo(UrgeTimerState.Unlocked)
    }

    @Test
    fun `cancelling returns to idle so protection stays on`() {
        machine.start(nowEpochMs = 0L)
        machine.tick(nowEpochMs = 60_000L)

        val state = machine.cancel()

        assertThat(state).isEqualTo(UrgeTimerState.Idle)
        assertThat(machine.snapshot().locked).isFalse()
    }

    @Test
    fun `ticking an idle machine does nothing`() {
        assertThat(machine.tick(nowEpochMs = 999_999L)).isEqualTo(UrgeTimerState.Idle)
    }

    @Test
    fun `a partial pledge can be retried until it matches`() {
        machine.start(nowEpochMs = 0L)
        machine.tick(nowEpochMs = 60_000L)
        machine.submitPledge("I choose")
        machine.submitPledge("I choose my future")

        val state = machine.submitPledge(config.pledgePhrase)

        assertThat(state).isEqualTo(UrgeTimerState.Unlocked)
    }

    @Test
    fun `the default duration is fifteen minutes`() {
        assertThat(UrgeTimerConfig.DEFAULT_DURATION_MS).isEqualTo(15L * 60L * 1_000L)
    }
}
