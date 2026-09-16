package dev.gamblock.data.repository

import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.database.ShieldDatabase
import dev.gamblock.core.database.entity.toEntity
import dev.gamblock.core.database.entity.toModel
import dev.gamblock.core.model.ActivityEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class ActivityEventRepository @Inject constructor(
    private val db: ShieldDatabase,
    private val dispatchers: DispatchersProvider,
) {
    private val dao get() = db.activityEventDao()

    fun observeRecent(limit: Int = 50): Flow<List<ActivityEvent>> =
        dao.observeRecent(limit).map { list -> list.map { it.toModel() } }

    suspend fun record(event: ActivityEvent) = withContext(dispatchers.io) {
        dao.insert(event.toEntity())
    }

    suspend fun prune(olderThanEpochMs: Long) = withContext(dispatchers.io) {
        dao.prune(olderThanEpochMs)
    }
}