package dev.gamblock.data.update

import androidx.work.ListenableWorker
import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.testing.NoOpLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BlocklistSyncWorkerTest {

    @Test
    fun `successful sync returns success after invoking the engine`() = runTest {
        var invoked = false

        val result = executeBlocklistSync(
            checkForUpdate = {
                invoked = true
                "up to date"
            },
            logger = NoOpLogger,
        )

        assertThat(invoked).isTrue()
        assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
    }

    @Test
    fun `engine failure returns retry`() = runTest {
        val result = executeBlocklistSync(
            checkForUpdate = { throw IllegalStateException("offline") },
            logger = NoOpLogger,
        )

        assertThat(result).isInstanceOf(ListenableWorker.Result.Retry::class.java)
    }

    @Test
    fun `cancellation is not converted into retry`() = runTest {
        var cancelled = false

        try {
            executeBlocklistSync(
                checkForUpdate = { throw CancellationException("cancelled") },
                logger = NoOpLogger,
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertThat(cancelled).isTrue()
    }
}
