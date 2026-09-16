package dev.gamblock.data.repository

import dev.gamblock.core.common.clock.MonotonicClock
import dev.gamblock.core.common.clock.WallClock
import dev.gamblock.core.common.dispatcher.DispatchersProvider
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.database.ShieldDatabase
import dev.gamblock.core.database.entity.toEntity
import dev.gamblock.core.database.entity.toModel
import dev.gamblock.core.model.Commitment
import dev.gamblock.core.model.CommitmentDurationOption
import dev.gamblock.core.model.CommitmentExtendResult
import dev.gamblock.core.model.CommitmentStartResult
import dev.gamblock.core.model.CommitmentState
import dev.gamblock.core.model.ProtectionLevel
import dev.gamblock.core.model.ProtectionMode
import dev.gamblock.core.model.util.randomId128
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Commitment lifecycle manager. Accumulates monotonic elapsed time; wall clock is
 * display-only. Extend-only until the user taps Finish once the genuine end is met.
 */
@Singleton
class CommitmentEngine @Inject constructor(
    private val db: ShieldDatabase,
    private val monotonicClock: MonotonicClock,
    private val wallClock: WallClock,
    private val dispatchers: DispatchersProvider,
    private val logger: ShieldLogger,
) {
    private val dao = db.commitmentDao()
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    private val _current = MutableStateFlow<Commitment?>(null)
    val current: StateFlow<Commitment?> = _current.asStateFlow()

    init {
        scope.launch {
            dao.observeLatest()
                .map { it?.toModel() }
                .collect { _current.value = it }
        }
    }

    val active: Commitment?
        get() = _current.value?.takeIf { it.state == CommitmentState.ACTIVE }

    suspend fun createCommitment(durationMillis: Long): CommitmentStartResult = withContext(dispatchers.io) {
        if (!CommitmentDurationOption.isValidDuration(durationMillis)) {
            return@withContext CommitmentStartResult.Rejected("duration out of valid range")
        }
        if (active != null) {
            return@withContext CommitmentStartResult.Rejected("existing commitment is active")
        }
        val nowEpoch = wallClock.nowEpochMillis()
        val nowElapsed = monotonicClock.nowElapsedMillis()
        val id = randomId128()
        val entity = dev.gamblock.core.database.entity.CommitmentEntity(
            id = id,
            mode = ProtectionMode.SELF_PROTECTION,
            level = ProtectionLevel.DNS_DOMAIN_BLOCKING,
            state = CommitmentState.ACTIVE,
            createdAtEpochMs = nowEpoch,
            startEpochMs = nowEpoch,
            endEpochMs = nowEpoch + durationMillis,
            intendedDurationMs = durationMillis,
            accumulatedElapsedMillis = 0L,
            lastAccountedElapsedMs = nowElapsed,
            bootCountAtCreation = getBootCount(),
            extensionCount = 0,
            canFinish = false,
        )
        dao.upsert(entity)
        logger.i(Logs.COMMITMENT, "created $id: intended=${durationMillis / 3600_000}h")
        CommitmentStartResult.Success(entity.toModel())
    }

    /** Accumulates elapsed time. Call on every foreground entry. */
    suspend fun tick(): Commitment? = withContext(dispatchers.io) {
        val latest = _current.value ?: return@withContext null
        if (latest.state != CommitmentState.ACTIVE) return@withContext latest
        val nowElapsed = monotonicClock.nowElapsedMillis()
        val delta = nowElapsed - latest.lastAccountedElapsedMs
        if (delta <= 0) return@withContext latest
        val newAccumulated = latest.accumulatedElapsedMillis + delta
        val done = newAccumulated >= latest.intendedDurationMs
        val updated = latest.copy(
            accumulatedElapsedMillis = newAccumulated,
            lastAccountedElapsedMs = nowElapsed,
            canFinish = done,
        )
        dao.upsert(updated.toEntity())
        logger.d(Logs.COMMITMENT, "tick: ${updated.accumulatedElapsedMillis}/${latest.intendedDurationMs} done=$done")
        _current.value = updated
        updated
    }

    /** Extend-only: adds [extraMillis] to the intended duration (never shrinks). */
    suspend fun extendBy(extraMillis: Long): CommitmentExtendResult = withContext(dispatchers.io) {
        val latest = active ?: return@withContext CommitmentExtendResult.NoActiveCommitment
        val maxExtendable = (CommitmentDurationOption.MAX_DURATION_MILLIS - latest.intendedDurationMs).coerceAtLeast(0L)
        if (extraMillis <= 0 || extraMillis > maxExtendable) {
            return@withContext CommitmentExtendResult.Rejected("requested extension out of range; max=${maxExtendable}ms")
        }
        val updated = latest.copy(
            endEpochMs = latest.endEpochMs + extraMillis,
            intendedDurationMs = latest.intendedDurationMs + extraMillis,
            extensionCount = latest.extensionCount + 1,
            canFinish = false,
        )
        dao.upsert(updated.toEntity())
        logger.i(Logs.COMMITMENT, "extended +${extraMillis / 3600_000}h, total=${updated.intendedDurationMs / 3600_000}h")
        _current.value = updated
        CommitmentExtendResult.Success(updated)
    }

    /** User taps Finish after the genuine end conditions have been met. */
    suspend fun finish(): Commitment? = withContext(dispatchers.io) {
        val latest = _current.value ?: return@withContext null
        if (latest.state != CommitmentState.ACTIVE || !latest.canFinish) return@withContext null
        val finished = latest.copy(state = CommitmentState.COMPLETED, canFinish = true)
        dao.upsert(finished.toEntity())
        _current.value = finished
        logger.i(Logs.COMMITMENT, "finished ${finished.id}")
        finished
    }

    suspend fun deleteAll() = withContext(dispatchers.io) {
        dao.deleteAll()
    }

    private fun getBootCount(): Int = try {
        @Suppress("DEPRECATION")
        android.os.Build.SERIAL.hashCode()
    } catch (_: Exception) {
        0
    }
}