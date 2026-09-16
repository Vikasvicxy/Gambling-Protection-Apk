package dev.gamblock.data.repository

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Phase 1 stub: exists so the WorkManager wiring can be tested and a real check
 * added later without changing call sites.
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val params: WorkerParameters,
    private val updateRepository: UpdateRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        updateRepository.refresh()
        return Result.success()
    }
}