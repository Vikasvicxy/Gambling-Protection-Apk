package dev.gamblock.data.update

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger

/**
 * Periodic + on-demand entry point of the signed update pipeline.
 *
 * The engine records every outcome (including failures) into durable meta state, so a
 * worker run never fabricates success. WorkManager retries happen through the periodic
 * cadence + backoff; protection keeps the last-known-good database on any failure.
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val params: WorkerParameters,
    private val engine: BlocklistUpdateEngine,
    private val logger: ShieldLogger,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val outcome = engine.checkForUpdate()
            logger.i(Logs.DB, "update check: $outcome")
            Result.success()
        } catch (e: Exception) {
            logger.w(Logs.DB, "update check crashed; scheduling retry", e)
            Result.retry()
        }
    }
}