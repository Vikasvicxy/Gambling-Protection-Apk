package dev.gamblock.data.repository

import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.database.dao.MetaDao
import dev.gamblock.core.model.UpdateHealthSnapshot
import dev.gamblock.core.model.UpdateMeta
import dev.gamblock.core.model.UpdateState
import dev.gamblock.data.blocklist.BlocklistRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Update foundation. Phase 1 is purely local (no network check implemented yet);
 * Phase 2 supersedes the local-only behaviour from `data:update`: the signed pipeline
 * drives this repository's live [state] stream, and durable facts for Protection Health
 * are read via [durableSnapshot].
 */
@Singleton
class UpdateRepository @Inject constructor(
    private val metaDao: MetaDao,
    private val dispatchers: DispatchersProvider,
    private val logger: ShieldLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    private val _state = MutableStateFlow(UpdateState.NOT_CONFIGURED)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    init {
        scope.launch {
            _state.value = UpdateState.NOT_CONFIGURED
        }
    }

    /** Published by the signed pipeline (`data:update`) after every check outcome. */
    fun publish(state: UpdateState, message: String = "") {
        _state.value = state
        if (message.isNotBlank()) logger.i(Logs.UI, "update state -> $state: $message")
    }

    /** Durable health-relevant facts; reads the same `meta` keys written by the pipeline. */
    suspend fun durableSnapshot(): UpdateHealthSnapshot = withContext(dispatchers.io) {
        UpdateHealthSnapshot(
            installedVersion = metaDao.get(BlocklistRepository.KEY_VERSION)?.toIntOrNull() ?: 0,
            maxObservedVersion = metaDao.get(UpdateMeta.KEY_MAX_OBSERVED_VERSION)?.toIntOrNull() ?: 0,
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

    suspend fun refresh() = withContext(dispatchers.io) {
        _state.value = UpdateState.NOT_CONFIGURED
        logger.i(Logs.UI, "update check foundation: superseded by data:update pipeline")
    }
}