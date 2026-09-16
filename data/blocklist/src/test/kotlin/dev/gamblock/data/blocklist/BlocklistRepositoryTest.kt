package dev.gamblock.data.blocklist

import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.database.ShieldDatabase
import dev.gamblock.core.model.DecisionKind
import dev.gamblock.core.model.UpdateState
import dev.gamblock.core.testing.FakeWallClock
import dev.gamblock.core.testing.NoOpLogger
import dev.gamblock.core.testing.TestDispatchersProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BlocklistRepositoryTest {

    private val dispatchers = TestDispatchersProvider(UnconfinedTestDispatcher())
    private val wallClock = FakeWallClock(1_700_000_000_000L)
    private lateinit var repo: BlocklistRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val db = ShieldDatabase.inMemory(context)
        val loader = SeedBlocklistLoader(context, dispatchers, NoOpLogger)
        repo = BlocklistRepository(context, db, loader, dispatchers, wallClock, NoOpLogger)
    }

    @Test
    fun `fails open before initialize`() {
        val decision = repo.decide("bet-example.test", scheduleActive = true)
        assertThat(decision.decision).isEqualTo(DecisionKind.ALLOW)
        assertThat(decision.reason).contains("fail-open")
    }

    @Test
    fun `initialize seeds the database and compiles the working index`() = runTest {
        repo.initialize()
        assertThat(repo.ruleCount).isEqualTo(12)
        val snapshot = repo.state.value!!
        assertThat(snapshot.compiled.enabledCount).isEqualTo(12)
        assertThat(snapshot.compiled.allowlistCount).isEqualTo(4)
        assertThat(snapshot.sourceVersion).isEqualTo(1)
        assertThat(snapshot.compiled.digest).hasLength(64)
        assertThat(snapshot.stats.updateState).isEqualTo(UpdateState.IDLE)
        assertThat(snapshot.stats.lastLoadedEpochMs).isEqualTo(1_700_000_000_000L)
        assertThat(repo.decide("bet-example.test", true).decision).isEqualTo(DecisionKind.BLOCK)
        assertThat(repo.decide("safe-example.test", true).decision).isEqualTo(DecisionKind.ALLOW)
    }

    @Test
    fun `initialize is idempotent`() = runTest {
        repo.initialize()
        repo.initialize()
        assertThat(repo.ruleCount).isEqualTo(12)
        assertThat(repo.state.value!!.compiled.enabledCount).isEqualTo(12)
    }

    @Test
    fun `addAllowlist overrides a blocked rule for its subdomains but not the exact host`() = runTest {
        repo.initialize()
        repo.addAllowlist("www.bet-example.test")
        assertThat(repo.state.value!!.compiled.allowlistCount).isEqualTo(5)
        assertThat(repo.decide("www.bet-example.test", true).decision).isEqualTo(DecisionKind.ALLOW)
        assertThat(repo.decide("a.b.www.bet-example.test", true).decision).isEqualTo(DecisionKind.ALLOW)
        assertThat(repo.decide("bet-example.test", true).decision).isEqualTo(DecisionKind.BLOCK)
    }

    @Test
    fun `addAllowlist inserts a new rule when absent`() = runTest {
        repo.initialize()
        repo.addAllowlist("new.example.test")
        assertThat(repo.ruleCount).isEqualTo(12)
        assertThat(repo.state.value!!.compiled.allowlistCount).isEqualTo(5)
        assertThat(repo.decide("new.example.test", true).decision).isEqualTo(DecisionKind.ALLOW)
        assertThat(repo.decide("sub.new.example.test", true).decision).isEqualTo(DecisionKind.ALLOW)
    }
}