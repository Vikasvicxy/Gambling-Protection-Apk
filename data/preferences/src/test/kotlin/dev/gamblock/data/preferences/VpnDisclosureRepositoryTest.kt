package dev.gamblock.data.preferences

import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.testing.NoOpLogger
import dev.gamblock.core.testing.TestDispatchersProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VpnDisclosureRepositoryTest {

    @Test
    fun `accept persists until cleared`() = runTest {
        val repository = VpnDisclosureRepository(
            context = RuntimeEnvironment.getApplication(),
            dispatchers = TestDispatchersProvider(UnconfinedTestDispatcher()),
            logger = NoOpLogger,
        )
        repository.clear()

        assertThat(repository.isAccepted()).isFalse()
        repository.accept()
        assertThat(repository.isAccepted()).isTrue()
        assertThat(repository.state.value.accepted).isTrue()

        repository.clear()
        assertThat(repository.isAccepted()).isFalse()
        assertThat(repository.state.value.accepted).isFalse()
    }
}
