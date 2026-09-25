package dev.gamblock.shield

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.data.blocklist.BlocklistRepository
import dev.gamblock.data.preferences.TimingAnchorRepository
import dev.gamblock.data.update.BlocklistUpdateEngine
import dev.gamblock.data.update.UpdateScheduler
import dev.gamblock.data.update.UpdateStateRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Bootstraps dependency injection, the WorkManager worker factory (required for
 * [dev.gamblock.protection.boot.ProtectionRecoveryWorker]) and the blocklist index,
 * then schedules the daily signed update sync.
 */
@HiltAndroidApp
class ShieldApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var blocklistRepository: BlocklistRepository
    @Inject lateinit var timingAnchorRepository: TimingAnchorRepository
    @Inject lateinit var updateStateRepository: UpdateStateRepository
    @Inject lateinit var updateScheduler: UpdateScheduler
    @Inject lateinit var dispatchers: DispatchersProvider
    @Inject lateinit var logger: ShieldLogger

    private val appScope = CoroutineScope(SupervisorJob())

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        updateScheduler.schedulePeriodic()
        appScope.launch(dispatchers.io) {
            timingAnchorRepository.ensureFirstRunEpochMs()
            updateStateRepository.initialize()
            blocklistRepository.initialize()
            logger.i(Logs.SECURITY, "shield initialized: blocklist+timing+update ready")
        }
    }
}