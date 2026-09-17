package dev.gamblock.data.update

import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.database.dao.MetaDao
import dev.gamblock.core.database.entity.MetaEntity
import dev.gamblock.core.model.UpdateHealthSnapshot
import dev.gamblock.core.model.UpdateMeta
import dev.gamblock.core.model.UpdateState
import dev.gamblock.data.blocklist.BlocklistRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Persisted pipeline bookkeeping + live [UpdateState] stream. Version/history lives in
 * the Room `meta` table (survives restarts); [checkForUpdate] drives this store.
 */
@Singleton
class UpdateStateRepository @Inject constructor(
    private val metaDao: MetaDao,
    private val dispatchers: DispatchersProvider,
) {

    private val _state = MutableStateFlow(UpdateState.NOT_CONFIGURED)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Rehydrates in-memory state from the durable meta table. Call at startup. */
    suspend fun initialize() {
        withContext(dispatchers.io) {
            _state.value = when {
                metaDao.get(UpdateMeta.KEY_LAST_FAILURE_REASON) != null -> UpdateState.FAILED
                metaDao.get(UpdateMeta.KEY_ACTIVE_RELEASE_ID) != null -> UpdateState.UP_TO_DATE
                else -> UpdateState.NOT_CONFIGURED
            }
        }
    }

    suspend fun beginCheck() {
        withContext(dispatchers.io) {
            _state.value = UpdateState.CHECKING
            metaDao.put(MetaEntity(UpdateMeta.KEY_LAST_CHECK_EPOCH_MS, System.currentTimeMillis().toString()))
        }
    }

    suspend fun recordSuccess(version: Int, maxObserved: Int, releaseId: String?, signingKeyId: String?, delta: Boolean) {
        withContext(dispatchers.io) {
            val now = System.currentTimeMillis()
            metaDao.put(MetaEntity(UpdateMeta.KEY_LAST_SUCCESS_EPOCH_MS, now.toString()))
            metaDao.put(MetaEntity(UpdateMeta.KEY_MAX_OBSERVED_VERSION, maxObserved.toString()))
            metaDao.put(MetaEntity(UpdateMeta.KEY_ACTIVE_RELEASE_ID, releaseId.orEmpty()))
            metaDao.put(MetaEntity(UpdateMeta.KEY_ACTIVE_SIGNING_KEY_ID, signingKeyId.orEmpty()))
            metaDao.put(MetaEntity(UpdateMeta.KEY_DELTA_LAST_APPLIED, delta.toString()))
            metaDao.put(MetaEntity(UpdateMeta.KEY_LAST_FAILURE_REASON, ""))
            metaDao.put(MetaEntity(BlocklistRepository.KEY_VERSION, version.toString()))
            _state.value = UpdateState.UP_TO_DATE
        }
    }

    suspend fun recordFailed(reason: String) {
        withContext(dispatchers.io) {
            metaDao.put(MetaEntity(UpdateMeta.KEY_LAST_FAILURE_REASON, reason.take(1024)))
            _state.value = UpdateState.FAILED
        }
    }

    suspend fun recordAvailable(version: Int) {
        withContext(dispatchers.io) {
            metaDao.put(MetaEntity(UpdateMeta.KEY_MAX_OBSERVED_VERSION, version.toString()))
            _state.value = UpdateState.UPDATE_AVAILABLE
        }
    }

    suspend fun installedVersion(): Int =
        metaDao.get(BlocklistRepository.KEY_VERSION)?.toIntOrNull() ?: 0

    suspend fun maxObservedVersion(): Int =
        metaDao.get(UpdateMeta.KEY_MAX_OBSERVED_VERSION)?.toIntOrNull() ?: 0

    /** Durable facts consumed by Protection Health ([UpdateHealthSnapshot]). */
    suspend fun durableSnapshot(): UpdateHealthSnapshot = withContext(dispatchers.io) {
        UpdateHealthSnapshot(
            installedVersion = installedVersion(),
            maxObservedVersion = maxObservedVersion(),
            activeReleaseId = metaDao.get(UpdateMeta.KEY_ACTIVE_RELEASE_ID)?.takeIf { it.isNotBlank() },
            activeSigningKeyId = metaDao.get(UpdateMeta.KEY_ACTIVE_SIGNING_KEY_ID)?.takeIf { it.isNotBlank() },
            lastSuccessEpochMs = metaDao.get(UpdateMeta.KEY_LAST_SUCCESS_EPOCH_MS)?.toLongOrNull() ?: 0L,
            lastFailureReason = metaDao.get(UpdateMeta.KEY_LAST_FAILURE_REASON)?.takeIf { it.isNotBlank() },
            deltaLastApplied = metaDao.get(UpdateMeta.KEY_DELTA_LAST_APPLIED)?.toBoolean() ?: false,
            rollbackEverApplied = metaDao.get(UpdateMeta.KEY_ROLLBACK_EVER_APPLIED)?.toBoolean() ?: false,
            previousGoodVersion = metaDao.get(UpdateMeta.KEY_PREVIOUS_GOOD_VERSION)?.toIntOrNull(),
            previousGoodAppliedEpochMs = metaDao.get(UpdateMeta.KEY_PREVIOUS_GOOD_APPLIED_EPOCH_MS)?.toLongOrNull() ?: 0L,
        )
    }
}