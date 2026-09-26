package dev.gamblock.data.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the daily signed update sync. Periodic work is idempotent (UPDATE
 * policy), network-gated and idle-gated. Enqueue at app start.
 */
@Singleton
class UpdateScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun schedulePeriodic() {
        // No explicit backoff here: WorkManager rejects setBackoffCriteria on a
        // device-idle ("idle mode") request, and periodic work already retries
        // exponentially by default. Setting it crashed the app on launch.
        val request = PeriodicWorkRequestBuilder<BlocklistSyncWorker>(24, TimeUnit.HOURS, 8, TimeUnit.HOURS)
            .setConstraints(periodicConstraints())
            .build()
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(LEGACY_UPDATE_WORK_NAME)
        workManager.enqueueUniquePeriodicWork(
            SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Manual check for diagnostics; runs immediately when a network is available. */
    fun enqueueNow() {
        val request = OneTimeWorkRequestBuilder<BlocklistSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    private fun periodicConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .setRequiresDeviceIdle(true)
        .build()

    companion object {
        const val SYNC_WORK_NAME = "shield.blocklist.sync"
        const val LEGACY_UPDATE_WORK_NAME = "shield.blocklist.update.check"
    }
}