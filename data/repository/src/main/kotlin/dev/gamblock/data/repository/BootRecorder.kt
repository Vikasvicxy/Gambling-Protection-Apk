package dev.gamblock.data.repository

import dev.gamblock.core.common.clock.WallClock
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.database.ShieldDatabase
import dev.gamblock.core.database.entity.MetaEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * Tracks device boot sessions in the meta table. The commitment engine uses this
 * to reconcile elapsed-time accumulation across reboots.
 */
@Singleton
class BootRecorder @Inject constructor(
    private val db: ShieldDatabase,
    private val wallClock: WallClock,
    private val dispatchers: DispatchersProvider,
    private val logger: ShieldLogger,
) {
    private val meta get() = db.metaDao()

    suspend fun recordBoot() = withContext(dispatchers.io) {
        val newCount = (meta.get(KEY_BOOT_COUNT)?.toIntOrNull() ?: 0) + 1
        meta.put(MetaEntity(KEY_BOOT_COUNT, newCount.toString()))
        meta.put(MetaEntity(KEY_BOOT_LAST_RECORD, wallClock.nowEpochMillis().toString()))
        logger.i(Logs.BOOT, "boot #$newCount recorded")
    }

    suspend fun lastBootEpochMs(): Long = withContext(dispatchers.io) {
        meta.get(KEY_BOOT_LAST_RECORD)?.toLongOrNull() ?: 0L
    }

    suspend fun bootCount(): Int = withContext(dispatchers.io) {
        meta.get(KEY_BOOT_COUNT)?.toIntOrNull() ?: 0
    }

    companion object {
        private const val KEY_BOOT_COUNT = "boot_count"
        private const val KEY_BOOT_LAST_RECORD = "boot_last_recorded_epoch"
    }
}