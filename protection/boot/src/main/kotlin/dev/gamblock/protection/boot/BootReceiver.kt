package dev.gamblock.protection.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Receives device and package lifecycle events and delegates recovery to
 * [ProtectionRecoveryWorker] via WorkManager (guaranteed execution even if the
 * app process was killed).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action !in VALID_ACTIONS) return
        enqueueRecovery(context)
    }

    companion object {
        val VALID_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )

        fun enqueueRecovery(context: Context) {
            val request = OneTimeWorkRequestBuilder<ProtectionRecoveryWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        private const val WORK_NAME = "shield-recovery"
    }
}