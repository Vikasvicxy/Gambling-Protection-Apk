package dev.gamblock.data.repository

import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.database.ShieldDatabase
import dev.gamblock.core.database.entity.toModel
import dev.gamblock.core.model.Commitment
import dev.gamblock.core.model.CommitmentDurationOption
import dev.gamblock.core.model.CommitmentExtendResult
import dev.gamblock.core.model.CommitmentStartResult
import dev.gamblock.core.model.CommitmentState
import dev.gamblock.core.model.ProtectionLevel
import dev.gamblock.core.model.ProtectionMode
import dev.gamblock.core.testing.FakeMonotonicClock
import dev.gamblock.core.testing.FakeWallClock
import dev.gamblock.core.testing.NoOpLogger
import dev.gamblock.core.testing.TestDispatchersProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CommitmentEngineTest {

    private val dispatchers = TestDispatchersProvider(UnconfinedTestDispatcher())
    private val monotonicClock = FakeMonotonicClock()
    private val wallClock = FakeWallClock(1_700_000_000_000L)
    private lateinit var db: ShieldDatabase
    private lateinit var engine: CommitmentEngine

    @Before
    fun setUp() {
        db = ShieldDatabase.inMemory(RuntimeEnvironment.getApplication())
        engine = CommitmentEngine(db, monotonicClock, wallClock, dispatchers, NoOpLogger)
    }

    /** The engine's current flow is fed asynchronously by Room; poll briefly. */
    private suspend fun awaitActive(expected: Commitment) {
        withContext(Dispatchers.Default) {
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                if (engine.current.value?.id == expected.id) return@withContext
                delay(10)
            }
        }
        assertThat(engine.current.value).isEqualTo(expected)
    }

    @Test
    fun `create rejects out-of-range durations`() = runTest {
        assertThat(engine.createCommitment(CommitmentDurationOption.MIN_DURATION_MILLIS - 1))
            .isEqualTo(CommitmentStartResult.Rejected("duration out of valid range"))
        assertThat(engine.createCommitment(CommitmentDurationOption.MAX_DURATION_MILLIS + 1))
            .isEqualTo(CommitmentStartResult.Rejected("duration out of valid range"))
    }

    @Test
    fun `create persists an active commitment and exposes it as current`() = runTest {
        val result = engine.createCommitment(CommitmentDurationOption.H24.durationMillis)
        assertThat(result).isInstanceOf(CommitmentStartResult.Success::class.java)
        val created = (result as CommitmentStartResult.Success).commitment
        val start = wallClock.nowEpochMillis()

        assertThat(created.state).isEqualTo(CommitmentState.ACTIVE)
        assertThat(created.mode).isEqualTo(ProtectionMode.SELF_PROTECTION)
        assertThat(created.level).isEqualTo(ProtectionLevel.DNS_DOMAIN_BLOCKING)
        assertThat(created.startEpochMs).isEqualTo(start)
        assertThat(created.endEpochMs).isEqualTo(start + CommitmentDurationOption.H24.durationMillis)
        assertThat(created.intendedDurationMs).isEqualTo(CommitmentDurationOption.H24.durationMillis)
        assertThat(created.accumulatedElapsedMillis).isEqualTo(0L)
        assertThat(created.canFinish).isFalse()
        assertThat(created.extensionCount).isEqualTo(0)

        awaitActive(created)
        assertThat(engine.active?.id).isEqualTo(created.id)
        assertThat(db.commitmentDao().latest()?.toModel()).isEqualTo(created)
    }

    @Test
    fun `create is rejected while an active commitment exists`() = runTest {
        val first = (engine.createCommitment(CommitmentDurationOption.H24.durationMillis) as CommitmentStartResult.Success).commitment
        awaitActive(first)
        assertThat(engine.createCommitment(CommitmentDurationOption.D3.durationMillis))
            .isEqualTo(CommitmentStartResult.Rejected("existing commitment is active"))
    }

    @Test
    fun `tick accumulates monotonic elapsed time and persists it`() = runTest {
        val created = (engine.createCommitment(CommitmentDurationOption.H24.durationMillis) as CommitmentStartResult.Success).commitment
        awaitActive(created)

        monotonicClock.advance(3_600_000L)
        val ticked = engine.tick()!!
        assertThat(ticked.accumulatedElapsedMillis).isEqualTo(3_600_000L)
        assertThat(ticked.lastAccountedElapsedMs).isEqualTo(3_600_000L)
        assertThat(ticked.canFinish).isFalse()
        assertThat(db.commitmentDao().latest()?.toModel()?.accumulatedElapsedMillis).isEqualTo(3_600_000L)

        val stale = engine.tick()!!
        assertThat(stale.accumulatedElapsedMillis).isEqualTo(3_600_000L)
    }

    @Test
    fun `tick flips canFinish once the intended duration has accumulated`() = runTest {
        val created = (engine.createCommitment(CommitmentDurationOption.H24.durationMillis) as CommitmentStartResult.Success).commitment
        awaitActive(created)

        monotonicClock.advance(CommitmentDurationOption.H24.durationMillis)
        val done = engine.tick()!!
        assertThat(done.canFinish).isTrue()

        monotonicClock.advance(7 * 24 * 60 * 60 * 1000L)
        val over = engine.tick()!!
        assertThat(over.canFinish).isTrue()
        assertThat(over.accumulatedElapsedMillis)
            .isEqualTo(CommitmentDurationOption.H24.durationMillis + 7 * 24 * 60 * 60 * 1000L)
    }

    @Test
    fun `extendBy rejects when no active commitment exists`() = runTest {
        assertThat(engine.extendBy(60 * 60 * 1000L)).isEqualTo(CommitmentExtendResult.NoActiveCommitment)
    }

    @Test
    fun `extendBy rejects non-positive or over-range extensions`() = runTest {
        val created = (engine.createCommitment(CommitmentDurationOption.H24.durationMillis) as CommitmentStartResult.Success).commitment
        awaitActive(created)

        assertThat(engine.extendBy(0L))
            .isEqualTo(CommitmentExtendResult.Rejected("requested extension out of range; max=${CommitmentDurationOption.MAX_DURATION_MILLIS - created.intendedDurationMs}ms"))
        val maxExtendable = CommitmentDurationOption.MAX_DURATION_MILLIS - created.intendedDurationMs
        assertThat(engine.extendBy(maxExtendable + 1))
            .isEqualTo(CommitmentExtendResult.Rejected("requested extension out of range; max=${maxExtendable}ms"))
    }

    @Test
    fun `extendBy grows the intended duration and increments the extension count`() = runTest {
        val created = (engine.createCommitment(CommitmentDurationOption.H24.durationMillis) as CommitmentStartResult.Success).commitment
        awaitActive(created)

        val extended = (engine.extendBy(CommitmentDurationOption.H24.durationMillis) as CommitmentExtendResult.Success).commitment
        assertThat(extended.intendedDurationMs).isEqualTo(2 * CommitmentDurationOption.H24.durationMillis)
        assertThat(extended.endEpochMs)
            .isEqualTo(created.endEpochMs + CommitmentDurationOption.H24.durationMillis)
        assertThat(extended.extensionCount).isEqualTo(1)
        assertThat(extended.canFinish).isFalse()
        assertThat(db.commitmentDao().latest()?.toModel()).isEqualTo(extended)
    }

    @Test
    fun `finish returns null until the genuine end is met`() = runTest {
        val created = (engine.createCommitment(CommitmentDurationOption.H24.durationMillis) as CommitmentStartResult.Success).commitment
        awaitActive(created)
        assertThat(engine.finish()).isNull()
        assertThat(engine.active).isNotNull()
    }

    @Test
    fun `finish completes the commitment once canFinish is true`() = runTest {
        val created = (engine.createCommitment(CommitmentDurationOption.H24.durationMillis) as CommitmentStartResult.Success).commitment
        awaitActive(created)
        monotonicClock.advance(25 * 60 * 60 * 1000L)
        engine.tick()

        val finished = engine.finish()!!
        assertThat(finished.state).isEqualTo(CommitmentState.COMPLETED)
        assertThat(finished.canFinish).isTrue()
        assertThat(db.commitmentDao().latest()?.toModel()).isEqualTo(finished)
        assertThat(engine.active).isNull()
    }

    @Test
    fun `clock rollback after a reboot does not add elapsed time`() = runTest {
        val created = (engine.createCommitment(CommitmentDurationOption.H24.durationMillis) as CommitmentStartResult.Success).commitment
        awaitActive(created)
        monotonicClock.advance(60 * 60 * 1000L)
        assertThat(engine.tick()!!.accumulatedElapsedMillis).isEqualTo(60 * 60 * 1000L)

        monotonicClock.reboot()
        val afterReboot = engine.tick()!!
        assertThat(afterReboot.accumulatedElapsedMillis).isEqualTo(60 * 60 * 1000L)
        assertThat(db.commitmentDao().latest()?.toModel()?.accumulatedElapsedMillis).isEqualTo(60 * 60 * 1000L)
    }

    @Test
    fun `deleteAll clears all commitments`() = runTest {
        val created = (engine.createCommitment(CommitmentDurationOption.H24.durationMillis) as CommitmentStartResult.Success).commitment
        awaitActive(created)
        engine.deleteAll()
        assertThat(db.commitmentDao().latest()).isNull()
    }
}