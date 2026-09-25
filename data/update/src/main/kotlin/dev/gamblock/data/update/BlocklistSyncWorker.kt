package dev.gamblock.data.update

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger

@HiltWorker
class BlocklistSyncWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val params: WorkerParameters,
    private val engine: BlocklistUpdateEngine,
    private val logger: ShieldLogger,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val outcome = engine.checkForUpdate()
            logger.i(Logs.DB, "blocklist sync: $outcome")
            Result.success()
        } catch (error: Exception) {
            logger.w(Logs.DB, "blocklist sync crashed; scheduling retry", error)
            Result.retry()
        }
    }
}
