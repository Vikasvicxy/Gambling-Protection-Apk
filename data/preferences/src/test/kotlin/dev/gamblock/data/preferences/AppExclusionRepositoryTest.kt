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
class AppExclusionRepositoryTest {

    private fun repository() = AppExclusionRepository(
        context = RuntimeEnvironment.getApplication(),
        dispatchers = TestDispatchersProvider(UnconfinedTestDispatcher()),
        logger = NoOpLogger,
    )

    @Test
    fun `starts empty`() = runTest {
        val repository = repository()
        repository.clear()

        assertThat(repository.excludedPackages).isEmpty()
        assertThat(repository.snapshot().excludedPackages).isEmpty()
    }

    @Test
    fun `add then remove round-trips`() = runTest {
        val repository = repository()
        repository.clear()

        repository.add("com.phonepe.app")
        assertThat(repository.excludedPackages).containsExactly("com.phonepe.app")

        repository.remove("com.phonepe.app")
        assertThat(repository.excludedPackages).isEmpty()
    }

    @Test
    fun `malformed package names are refused on write`() = runTest {
        val repository = repository()
        repository.clear()

        repository.add("not a package")
        repository.add("")
        repository.add("com..broken")

        assertThat(repository.excludedPackages).isEmpty()
    }

    @Test
    fun `setExclusions discards malformed entries in a mixed batch`() = runTest {
        val repository = repository()
        repository.clear()

        repository.setExclusions(listOf("com.phonepe.app", "nonsense", "com.paytm.pktv"))

        assertThat(repository.excludedPackages)
            .containsExactly("com.phonepe.app", "com.paytm.pktv")
    }

    @Test
    fun `setExclusions trims surrounding whitespace`() = runTest {
        val repository = repository()
        repository.clear()

        repository.setExclusions(listOf("  com.phonepe.app  "))

        assertThat(repository.excludedPackages).containsExactly("com.phonepe.app")
    }

    @Test
    fun `duplicates collapse`() = runTest {
        val repository = repository()
        repository.clear()

        repository.setExclusions(listOf("com.phonepe.app", "com.phonepe.app", "com.phonepe.app"))

        assertThat(repository.excludedPackages).hasSize(1)
    }

    @Test
    fun `toggle adds when absent and removes when present`() = runTest {
        val repository = repository()
        repository.clear()

        repository.toggle("com.phonepe.app", excluded = true)
        assertThat(repository.excludedPackages).contains("com.phonepe.app")

        repository.toggle("com.phonepe.app", excluded = false)
        assertThat(repository.excludedPackages).isEmpty()
    }

    @Test
    fun `clear removes everything`() = runTest {
        val repository = repository()
        repository.setExclusions(listOf("com.phonepe.app", "com.paytm.pktv"))

        repository.clear()

        assertThat(repository.excludedPackages).isEmpty()
    }

    @Test
    fun `state flow reflects the stored set`() = runTest {
        val repository = repository()
        repository.clear()

        repository.setExclusions(listOf("com.phonepe.app"))

        assertThat(repository.state.value.excludedPackages).containsExactly("com.phonepe.app")
    }
}
