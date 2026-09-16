package dev.gamblock.protection.boot

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.data.repository.BootRecorder
import dev.gamblock.data.repository.ProtectionEnforcer

/**
 * Runs once on boot or app update. Records the new boot session and re-enables
 * protection if the user's settings demand it.
 */
@HiltWorker
class ProtectionRecoveryWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val params: WorkerParameters,
    private val bootRecorder: BootRecorder,
    private val protectionEnforcer: ProtectionEnforcer,
    private val logger: ShieldLogger,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            bootRecorder.recordBoot()
            protectionEnforcer.enforceNow()
            Result.success()
        } catch (t: Throwable) {
            logger.e(Logs.BOOT, "recovery failed: ${t.message}", t)
            Result.retry()
        }
    }
}