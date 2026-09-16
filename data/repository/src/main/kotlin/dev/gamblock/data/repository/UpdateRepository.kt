package dev.gamblock.data.repository

import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.database.dao.MetaDao
import dev.gamblock.core.model.UpdateState
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
 * the worker exists so the update plumbing can be wired and the dashboard shows
 * the correct state.
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

    suspend fun refresh() = withContext(dispatchers.io) {
        _state.value = UpdateState.NOT_CONFIGURED
        logger.i(Logs.UI, "update check foundation: no network source configured")
    }
}