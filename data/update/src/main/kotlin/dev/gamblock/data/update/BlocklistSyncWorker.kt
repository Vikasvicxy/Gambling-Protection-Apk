package dev.gamblock.data.update

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import kotlinx.coroutines.CancellationException

@HiltWorker
class BlocklistSyncWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val params: WorkerParameters,
    private val engine: BlocklistUpdateEngine,
    private val logger: ShieldLogger,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = executeBlocklistSync(
        checkForUpdate = { engine.checkForUpdate() },
        logger = logger,
    )
}

internal suspend fun executeBlocklistSync(
    checkForUpdate: suspend () -> String,
    logger: ShieldLogger,
): ListenableWorker.Result {
    return try {
        val outcome = checkForUpdate()
        logger.i(Logs.DB, "blocklist sync: $outcome")
        ListenableWorker.Result.success()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        logger.w(Logs.DB, "blocklist sync crashed; scheduling retry", error)
        ListenableWorker.Result.retry()
    }
}
