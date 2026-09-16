package dev.gamblock.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamblock.core.common.clock.WallClock
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.database.ShieldDatabase
import dev.gamblock.core.database.entity.BlockAttemptGroupEntity
import dev.gamblock.core.database.entity.toModel
import dev.gamblock.core.model.BlockAttemptGroup
import dev.gamblock.core.model.BlockDecision
import dev.gamblock.core.model.Category
import dev.gamblock.protection.vpn.BlockEventRecorder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Persists grouped block attempts. Implements the [BlockEventRecorder] interface
 * from the VPN transport; incoming records are fire-and-forget so the tun thread
 * never stalls on Room.
 */
@Singleton
class BlockEventRepository @Inject constructor(
    private val db: ShieldDatabase,
    private val wallClock: WallClock,
    private val dispatchers: DispatchersProvider,
    private val logger: ShieldLogger,
) : BlockEventRecorder {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val groupDao get() = db.blockAttemptGroupDao()

    override fun recordBlocked(domain: String, decision: BlockDecision) {
        val category = decision.ruleHit?.category ?: Category.UNKNOWN
        val now = wallClock.nowEpochMillis()
        val normalized = domain.lowercase()
        scope.launch {
            try {
                groupDao.upsertGrouped(
                    entity = BlockAttemptGroupEntity(
                        normalizedDomain = normalized,
                        category = category,
                        count = 1,
                        firstSeenEpochMs = now,
                        lastSeenEpochMs = now,
                        signature = decision.signature,
                    ),
                    existing = groupDao.find(normalized),
                )
            } catch (t: Throwable) {
                logger.w(Logs.DB, "block event insert failed: ${t.message}")
            }
        }
    }

    fun observeRecent(limit: Int = 20): Flow<List<BlockAttemptGroup>> =
        groupDao.observeRecent(limit).map { list -> list.map { it.toModel() } }

    fun observeTotalAttempts(): Flow<Int> = groupDao.observeTotalAttempts()

    suspend fun prune(olderThanEpochMs: Long, keepNewest: Int = 100) = withContext(dispatchers.io) {
        groupDao.prune(olderThanEpochMs, keepNewest)
    }

    suspend fun clear() = withContext(dispatchers.io) {
        groupDao.clear()
    }
}